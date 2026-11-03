package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.UnrecoverableKeyException
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FileMutationStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(app.cacheDir, "file-mutations-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(app) {
        override fun getNoBackupFilesDir() = directory
    }
    private val payload get() = File(directory, "file-mutations.enc")
    private val alias = "pi-remote-file-mutations-v1"
    private val request = FileMutationRequest("route", "device", "session", "operation", "epoch", FileMutationKind.RENAME, "folder/old.txt", "folder/new.txt", "version")
    private val key = FileMutationKey(request.deviceId, request.operationId)
    private val entry = FileMutationEntry(request, FileMutationState.UNKNOWN)

    private fun store(expectProvisioned: Boolean = true, maxEntries: Int = 8) =
        FileMutationStore(context, maxEntries, expectProvisioned)

    @Before fun setUp() { FakeAndroidKeyStore.install(); directory.mkdirs() }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun encryptedDataSurvivesStoreRecreation() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val ciphertext = payload.readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(request.path))
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains(request.operationId))
        EncryptedFileStore.forgetCachedKeys()
        assertEquals(mapOf(key to entry), store().load())
    }

    @Test fun epochAndLostStateSurviveStoreRecreation() {
        val lost = FileMutationEntry(request.copy(epoch = "other-epoch"), FileMutationState.LOST)
        store(expectProvisioned = false).save(mapOf(key to lost))
        EncryptedFileStore.forgetCachedKeys()
        assertEquals(mapOf(key to lost), store().load())
    }

    @Test fun missingProvisionedPayloadDoesNotBecomeEmptyOrAllowOverwrite() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        assertTrue(payload.delete())
        assertThrows(Exception::class.java) { store().load() }
        assertThrows(Exception::class.java) { store().save(emptyMap()) }
        assertFalse(payload.exists())
    }

    @Test fun expectedProvisionedStoreRejectsMissingLocalArtifacts() {
        assertThrows(Exception::class.java) { store().load() }
        assertThrows(Exception::class.java) { store().save(emptyMap()) }
        assertFalse(payload.exists())
    }

    @Test fun unfinishedFirstProvisionPayloadIsPreservedAndFailsClosed() {
        // android.util.AtomicFile writes to the base path with ".new" appended.
        val unfinished = File(directory, "file-mutations.enc.new")
        val evidence = byteArrayOf(4, 8, 15, 16, 23, 42)
        unfinished.writeBytes(evidence)

        assertThrows(Exception::class.java) { store(expectProvisioned = false).load() }
        assertArrayEquals(evidence, unfinished.readBytes())
        assertThrows(Exception::class.java) { store(expectProvisioned = false).save(emptyMap()) }
        assertArrayEquals(evidence, unfinished.readBytes())
        assertFalse(payload.exists())
    }

    @Test fun confirmedResetReprovisionsAnEmptyJournalFromUnreadableOrHalfLostEvidence() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        payload.writeBytes(byteArrayOf(1, 2))
        assertThrows(Exception::class.java) { store().load() }
        store().reset()
        assertEquals(emptyMap<FileMutationKey, FileMutationEntry>(), store().load())
        // A payload without its marker fails closed until a reset, too.
        store().save(mapOf(key to entry))
        File(directory, "file-mutations-v1.provisioned").delete()
        assertThrows(Exception::class.java) { store(expectProvisioned = false).load() }
        store().reset()
        assertEquals(emptyMap<FileMutationKey, FileMutationEntry>(), store().load())
    }

    @Test fun truncatedAndTamperedCiphertextIsPreserved() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val corruptions = listOf(
            payload.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            byteArrayOf(1, 2),
        )
        for (corrupt in corruptions) {
            payload.writeBytes(corrupt)
            assertThrows(Exception::class.java) { store().load() }
            assertThrows(Exception::class.java) { store().save(emptyMap()) }
            assertArrayEquals(corrupt, payload.readBytes())
        }
    }

    @Test fun firstProvisionFlagDoesNotOverwriteExistingUnreadableEvidence() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val corrupt = byteArrayOf(1, 2)
        payload.writeBytes(corrupt)
        assertThrows(Exception::class.java) { store(expectProvisioned = false).save(emptyMap()) }
        assertArrayEquals(corrupt, payload.readBytes())
    }

    @Test fun legacyBackupCannotReplaceExistingPayloadBeforeAuthentication() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val baseEvidence = byteArrayOf(1, 2, 3)
        val backupEvidence = byteArrayOf(4, 5, 6, 7)
        val backup = File(directory, "file-mutations.enc.bak")
        payload.writeBytes(baseEvidence)
        backup.writeBytes(backupEvidence)

        assertThrows(Exception::class.java) { store().load() }
        assertArrayEquals(baseEvidence, payload.readBytes())
        assertArrayEquals(backupEvidence, backup.readBytes())
        assertThrows(Exception::class.java) { store().save(emptyMap()) }
        assertArrayEquals(baseEvidence, payload.readBytes())
        assertArrayEquals(backupEvidence, backup.readBytes())
    }

    @Test fun failedCommitRenamePreservesBaseAndUnfinishedPayload() {
        val first = store(expectProvisioned = false)
        first.save(mapOf(key to entry))
        val original = payload.readBytes()
        val unfinished = File(directory, "file-mutations.enc.new")
        var unfinishedBytes: ByteArray? = null
        val interrupted = object : AtomicFile(payload) {
            override fun finishWrite(stream: FileOutputStream) {
                // Simulate AtomicFile logging a failed rename and returning without committing.
                stream.close()
                unfinishedBytes = unfinished.readBytes()
            }
        }
        val encrypted = FileMutationStore::class.java.getDeclaredField("encrypted")
            .apply { isAccessible = true }.get(first)
        EncryptedFileStore::class.java.getDeclaredField("file")
            .apply { isAccessible = true; set(encrypted, interrupted) }

        assertThrows(Exception::class.java) {
            first.save(mapOf(key to entry.copy(state = FileMutationState.RESERVED)))
        }
        assertNotNull(unfinishedBytes)
        assertArrayEquals(original, payload.readBytes())
        assertTrue("Unfinished payload must remain available", unfinished.exists())
        assertArrayEquals(unfinishedBytes, unfinished.readBytes())
    }

    @Test fun oversizedCiphertextIsRejectedBeforeReadingFileContents() {
        val first = store(expectProvisioned = false)
        first.save(mapOf(key to entry))
        // Maximum plaintext is 8 MiB; encrypted files add a 12-byte IV and 16-byte tag.
        val oversizedLength = 8L * 1024 * 1024 + 29
        RandomAccessFile(payload, "rw").use { it.setLength(oversizedLength) }
        val encrypted = FileMutationStore::class.java.getDeclaredField("encrypted").apply { isAccessible = true }.get(first)
        val intercepted = object : AtomicFile(payload) {
            var readAttempted = false
            override fun readFully(): ByteArray {
                readAttempted = true
                throw AssertionError("oversized ciphertext must be rejected before readFully")
            }
        }
        EncryptedFileStore::class.java.getDeclaredField("file").apply { isAccessible = true; set(encrypted, intercepted) }

        assertThrows(Exception::class.java) { first.load() }
        assertFalse(intercepted.readAttempted)
        assertEquals(oversizedLength, payload.length())
    }

    @Test fun malformedPlaintextIsPreservedAndNeverOverwritten() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val encrypted = EncryptedFileStore(context, "file-mutations.enc", alias, preserveUnreadable = true)
        for (bad in listOf("not json", "{}", "[{\"unexpected\":true}]")) {
            encrypted.write(bad.toByteArray())
            val before = payload.readBytes()
            assertThrows(Exception::class.java) { store().load() }
            assertThrows(Exception::class.java) { store().save(emptyMap()) }
            assertArrayEquals(before, payload.readBytes())
        }
    }

    @Test fun overCapacityDataFailsClosedOnLoadAndSave() {
        val second = request.copy(operationId = "second")
        val entries = mapOf(key to entry, FileMutationKey(second.deviceId, second.operationId) to FileMutationEntry(second, FileMutationState.RESERVED))
        store(expectProvisioned = false, maxEntries = 2).save(entries)
        val before = payload.readBytes()
        assertThrows(Exception::class.java) { store(maxEntries = 1).load() }
        assertThrows(Exception::class.java) { store(maxEntries = 1).save(mapOf(key to entry)) }
        assertArrayEquals(before, payload.readBytes())
        assertEquals(entries, store(maxEntries = 2).load())
    }

    @Test fun invalidRecordsAreRejectedWithoutReplacingEvidence() {
        store(expectProvisioned = false).save(mapOf(key to entry))
        val before = payload.readBytes()
        val invalidPath = request.copy(path = "../escape")
        val mismatchedKey = FileMutationKey("other-device", request.operationId)
        assertThrows(Exception::class.java) { store().save(mapOf(key to entry.copy(request = invalidPath))) }
        assertThrows(Exception::class.java) { store().save(mapOf(mismatchedKey to entry)) }
        assertArrayEquals(before, payload.readBytes())
        assertEquals(mapOf(key to entry), store().load())
    }

    @Test fun canonicalLocationSharesStableLockIdentity() {
        val otherContext = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(directory, "../${directory.name}")
        }
        val first = store(expectProvisioned = false)
        val second = FileMutationStore(otherContext, 8, false)
        assertEquals(first.lockIdentity, first.lockIdentity)
        assertEquals(first.lockIdentity, second.lockIdentity)
        assertEquals(first.lockIdentity.hashCode(), second.lockIdentity.hashCode())
    }

    @Test fun keyFailureOnSavePreservesPreviousEvidence() {
        val first = store(expectProvisioned = false)
        first.save(mapOf(key to entry))
        val before = payload.readBytes()
        val original = EncryptedFileStore.loadKey
        try {
            EncryptedFileStore.forgetCachedKeys()
            EncryptedFileStore.loadKey = { throw UnrecoverableKeyException("key unavailable") }
            assertThrows(Exception::class.java) { first.save(emptyMap()) }
            assertArrayEquals(before, payload.readBytes())
        } finally {
            EncryptedFileStore.loadKey = original
            EncryptedFileStore.forgetCachedKeys()
        }
        assertEquals(mapOf(key to entry), store().load())
    }
}
