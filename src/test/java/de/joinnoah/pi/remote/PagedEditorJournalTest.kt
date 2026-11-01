package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
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
class PagedEditorJournalTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(app.cacheDir, "paged-journal-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = dir }
    private val key = PagedJournalKey("route", "session", "question")
    private val text = "a\uD800😀\uDC00\u0000"
    private val attempt = StoredPagedAttempt(key, "attempt", "grant", "upload", "commit", text, 42)

    @Before fun setup() { FakeAndroidKeyStore.install(); dir.mkdirs() }
    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun encryptedRoundTripPreservesCodeUnitsAndSeparatesKeys() = runBlocking {
        val journal = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined)
        journal.putDraft(key.copy(routeId = "other"), StoredPagedDraft("other", 1))
        journal.reserve(attempt)
        val restored = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot()
        assertEquals(text, restored.drafts.getValue(key).text)
        assertEquals(attempt, restored.attempts.getValue("attempt"))
        assertEquals("other", restored.drafts.getValue(key.copy(routeId = "other")).text)
    }

    @Test fun reservationIsAtomicAndFailureLeavesCachedAndPersistedStateUntouched() = runBlocking {
        val storage = MemoryStorage()
        val journal = PagedEditorJournal(storage, Dispatchers.Unconfined)
        journal.putDraft(key, StoredPagedDraft("old", 1))
        storage.fail = true
        assertTrue(runCatching { journal.reserve(attempt) }.isFailure)
        assertEquals("old", journal.snapshot().drafts.getValue(key).text)
        assertTrue(PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot().attempts.isEmpty())
        storage.fail = false
        journal.reserve(attempt)
        val saved = PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot()
        assertEquals(text, saved.drafts.getValue(key).text)
        assertEquals(attempt, saved.attempts["attempt"])
        journal.removeDraft(key)
        assertEquals(attempt, journal.snapshot().attempts["attempt"])
    }

    @Test fun exactRetryIsIdempotentButAnyOtherAttemptForKeyIsBlocked() = runBlocking {
        val journal = PagedEditorJournal(MemoryStorage(), Dispatchers.Unconfined)
        journal.reserve(attempt)
        journal.reserve(attempt)
        assertTrue(runCatching { journal.reserve(attempt.copy(text = "different")) }.isFailure)
        assertTrue(runCatching { journal.reserve(attempt.copy(attemptId = "second", grantId = "new")) }.isFailure)
        journal.removeAttempt("unknown")
        assertEquals(attempt, journal.snapshot().attempts["attempt"])
    }

    @Test fun quotasRejectWithoutEvictingEvidence() = runBlocking {
        val journal = PagedEditorJournal(MemoryStorage(), Dispatchers.Unconfined)
        repeat(32) { journal.reserve(attempt.copy(key = key.copy(questionId = "q$it"), attemptId = "a$it")) }
        val before = journal.snapshot()
        assertTrue(runCatching { journal.reserve(attempt.copy(key = key.copy(questionId = "extra"), attemptId = "extra")) }.isFailure)
        assertEquals(before, journal.snapshot())
        assertTrue(runCatching { journal.putDraft(key, StoredPagedDraft("x".repeat(2 * 1024 * 1024 + 1), 0)) }.isFailure)
        assertEquals(before, journal.snapshot())
    }

    @Test fun aggregateDraftBytesRefuseAdmissionWithoutEviction() = runBlocking {
        val journal = PagedEditorJournal(MemoryStorage(), Dispatchers.Unconfined)
        val large = "x".repeat(1024 * 1024)
        repeat(4) { journal.putDraft(key.copy(questionId = "q$it"), StoredPagedDraft(large, 1)) }
        val before = journal.snapshot()
        assertTrue(runCatching { journal.putDraft(key, StoredPagedDraft("x", 1)) }.isFailure)
        assertEquals(before, journal.snapshot())
    }

    @Test fun malformedAndUnreadableFilesFailClosedAndRemainUnchanged() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        raw.write("{bad".toByteArray())
        val file = File(dir, "paged-editor-journal.enc")
        val malformed = file.readBytes()
        assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
        assertThrows(Exception::class.java) { PagedEditorStore(context).save(PagedJournalSnapshot()) }
        assertArrayEquals(malformed, file.readBytes())
        file.writeBytes(byteArrayOf(1, 2))
        assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
        assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
    }

    @Test fun rejectsMalformedSchemaDuplicateRecordsAndInvalidIdentifiers() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        val badInputs = listOf(
            "[2,[],[]]",
            "[1,[[\"r\",\"s\",\"q\",\"\",0],[\"r\",\"s\",\"q\",\"\",0]],[]]",
            "[1,[[\"r\",\"s\",\"q\",\"A\",0]],[]]",
            "[1,[[\" \",\"s\",\"q\",\"\",0]],[]]",
            "[1,[[\"r\",\"s\",\"q\",\"\",-1]],[]]",
        )
        for (input in badInputs) {
            raw.write(input.toByteArray())
            val before = File(dir, "paged-editor-journal.enc").readBytes()
            assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
            assertArrayEquals(before, File(dir, "paged-editor-journal.enc").readBytes())
        }
    }

    @Test fun badTagAndUnavailableKeyPreserveCiphertext() {
        PagedEditorStore(context).save(PagedJournalSnapshot(mapOf(key to StoredPagedDraft(text, 1))))
        val file = File(dir, "paged-editor-journal.enc")
        val before = file.readBytes()
        val originalLoad = EncryptedFileStore.loadKey
        try {
            EncryptedFileStore.forgetCachedKeys()
            EncryptedFileStore.loadKey = { throw UnrecoverableKeyException("lost") }
            assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
            assertArrayEquals(before, file.readBytes())
        } finally { EncryptedFileStore.loadKey = originalLoad; EncryptedFileStore.forgetCachedKeys() }
        val corrupt = before.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
        assertArrayEquals(corrupt, file.readBytes())
    }

    private class MemoryStorage : PagedEditorStorage {
        var saved = PagedJournalSnapshot()
        var fail = false
        override fun load() = saved
        override fun save(snapshot: PagedJournalSnapshot) { if (fail) throw IOException("disk full"); saved = snapshot }
    }
}
