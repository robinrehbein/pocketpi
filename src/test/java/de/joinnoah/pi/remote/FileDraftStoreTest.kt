package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FileDraftStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(app.cacheDir, "file-drafts-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(app) {
        override fun getNoBackupFilesDir() = directory
    }
    private val key = FileDraftKey("route", "session", "folder/file.txt")
    private val draft = StoredFileDraft("base", "original\n😀", "draft\u0000\nä", listOf(PendingWrite("req", "base", "sent\n😀")))

    @Before fun setUp() { FakeAndroidKeyStore.install(); directory.mkdirs() }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun encryptedStoreRoundTripsExactContentAndSeparatesIdentities() {
        val otherRoute = key.copy(routeId = "route2")
        val otherSession = key.copy(sessionId = "session2")
        val otherPath = key.copy(path = "folder/other.txt")
        val expected = mapOf(key to draft, otherRoute to draft.copy(text = "r"), otherSession to draft.copy(text = "s"), otherPath to draft.copy(text = "p"))
        FileDraftStore(context).save(expected)
        assertEquals(expected, FileDraftStore(context).load())
    }

    @Test fun malformedAndUnreadableDataDoNotAppearAsEmptyDrafts() {
        val file = EncryptedFileStore(context, "file-drafts.enc", "pi-remote-file-drafts-v1")
        file.write("not json".toByteArray())
        assertThrows(Exception::class.java) { FileDraftStore(context).load() }
        assertThrows(Exception::class.java) { FileDraftStore(context).save(mapOf(key to draft)) }
        File(directory, "file-drafts.enc").writeBytes(byteArrayOf(1, 2))
        assertThrows(Exception::class.java) { FileDraftStore(context).load() }
        assertArrayEquals(byteArrayOf(1, 2), File(directory, "file-drafts.enc").readBytes())
        assertThrows(Exception::class.java) { FileDraftStore(context).load() }
        assertThrows(Exception::class.java) { FileDraftStore(context).save(mapOf(key to draft)) }
    }

    @Test fun invalidAuthenticationTagRemainsAvailableForDiagnosis() {
        FileDraftStore(context).save(mapOf(key to draft))
        val ciphertext = File(directory, "file-drafts.enc")
        val corrupt = ciphertext.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        ciphertext.writeBytes(corrupt)
        assertThrows(Exception::class.java) { FileDraftStore(context).load() }
        assertThrows(Exception::class.java) { FileDraftStore(context).load() }
        assertArrayEquals(corrupt, ciphertext.readBytes())
    }

    @Test fun keyFailureAfterLoadingDoesNotReplaceExistingCiphertext() {
        val store = FileDraftStore(context)
        store.save(mapOf(key to draft))
        val ciphertext = File(directory, "file-drafts.enc")
        val before = ciphertext.readBytes()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val originalKey = keys.getKey("pi-remote-file-drafts-v1", null)
        assertNotNull(originalKey)
        val originalLoadKey = EncryptedFileStore.loadKey
        try {
            EncryptedFileStore.forgetCachedKeys()
            EncryptedFileStore.loadKey = { throw UnrecoverableKeyException("key unavailable") }
            assertThrows(Exception::class.java) { store.save(mapOf(key to draft.copy(text = "new"))) }
            assertArrayEquals(before, ciphertext.readBytes())
            assertEquals(originalKey, keys.getKey("pi-remote-file-drafts-v1", null))
        } finally {
            EncryptedFileStore.loadKey = originalLoadKey
            EncryptedFileStore.forgetCachedKeys()
        }
        assertEquals(mapOf(key to draft), FileDraftStore(context).load())
    }

    @Test fun rejectsInvalidKeysAndCapacityWithoutReplacingExistingData() {
        val store = FileDraftStore(context)
        store.save(mapOf(key to draft))
        for (invalid in listOf("../file", "/absolute", "a/../file", "a//file", "a/./file", "a\nfile", "")) {
            assertThrows(IllegalArgumentException::class.java) { store.save(mapOf(key.copy(path = invalid) to draft)) }
        }
        assertThrows(IllegalArgumentException::class.java) { store.save(mapOf(key.copy(routeId = " ") to draft)) }
        assertThrows(IllegalArgumentException::class.java) { store.save((0..8).associate { key.copy(path = "file$it") to draft }) }
        assertThrows(IllegalArgumentException::class.java) { store.save(mapOf(key to draft.copy(pending = List(17) { PendingWrite("req$it", "base", "sent") }))) }
        assertThrows(IllegalArgumentException::class.java) { store.save(mapOf(key to draft.copy(text = "x".repeat(1024 * 1024 + 1)))) }
        assertThrows(IllegalArgumentException::class.java) { store.save(mapOf(key to draft.copy(pending = listOf(PendingWrite("req", "base", "x".repeat(192 * 1024)))))) }
        assertThrows(IllegalArgumentException::class.java) {
            store.save((0..7).associate { key.copy(path = "file$it") to draft.copy(original = "x".repeat(1024 * 1024), text = "y".repeat(1024 * 1024)) })
        }
        assertEquals(mapOf(key to draft), store.load())
    }

    @Test fun failedSaveAndLoadNeverChangeJournalState() = runBlocking {
        val storage = MemoryStorage(mapOf(key to draft))
        val journal = FileDraftJournal(storage, Dispatchers.Unconfined)
        assertEquals(draft, journal.get(key))
        storage.failSave = true
        try { journal.put(key, draft.copy(text = "new")); fail("save should fail") } catch (_: IOException) { }
        assertEquals(draft, journal.get(key))
        storage.failSave = false
        journal.put(key.copy(path = "second"), draft)
        assertEquals(draft, storage.drafts[key])
        val failing = MemoryStorage(mapOf(key to draft)).apply { failLoad = true }
        val retry = FileDraftJournal(failing, Dispatchers.Unconfined)
        try { retry.get(key); fail("load should fail") } catch (_: IOException) { }
        failing.failLoad = false
        assertEquals(draft, retry.get(key))
    }

    @Test fun failedReadbackAfterCommitDoesNotLetNextMutationErasePendingWrite() = runBlocking {
        val store = FileDraftStore(context)
        val journal = FileDraftJournal(store, Dispatchers.Unconfined)
        journal.put(key, draft.copy(pending = emptyList()))
        val encrypted = FileDraftStore::class.java.getDeclaredField("file").apply { isAccessible = true }.get(store)
        val atomic = object : AtomicFile(File(directory, "file-drafts.enc")) {
            var failRead = false
            var armed = true
            override fun finishWrite(stream: FileOutputStream) {
                super.finishWrite(stream)
                if (armed) { failRead = true; armed = false }
            }
            override fun readFully(): ByteArray {
                if (failRead) {
                    failRead = false
                    throw IOException("readback failed after commit")
                }
                return super.readFully()
            }
        }
        EncryptedFileStore::class.java.getDeclaredField("file").apply { isAccessible = true; set(encrypted, atomic) }

        assertTrue(runCatching { journal.put(key, draft) }.exceptionOrNull() is IOException)
        journal.put(key.copy(path = "other.txt"), draft.copy(pending = emptyList()))
        assertEquals(draft, FileDraftStore(context).load()[key])
    }

    private class MemoryStorage(var drafts: Map<FileDraftKey, StoredFileDraft>) : FileDraftStorage {
        var failLoad = false
        var failSave = false
        override fun load(): Map<FileDraftKey, StoredFileDraft> {
            if (failLoad) throw IOException("load")
            return drafts
        }
        override fun save(drafts: Map<FileDraftKey, StoredFileDraft>) {
            if (failSave) throw IOException("save")
            this.drafts = drafts
        }
    }
}
