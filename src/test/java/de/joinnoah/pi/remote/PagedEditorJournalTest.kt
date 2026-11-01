package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
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

    @Test fun failedReadbackAfterCommitDoesNotLetNextMutationEraseCommittedAttempt() = runBlocking {
        val store = PagedEditorStore(context)
        val journal = PagedEditorJournal(store, Dispatchers.Unconfined)
        journal.putDraft(key, StoredPagedDraft("old", 1))
        val encrypted = PagedEditorStore::class.java.getDeclaredField("file").apply { isAccessible = true }.get(store)
        val base = File(dir, "paged-editor-journal.enc")
        val atomic = object : AtomicFile(base) {
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

        assertTrue(runCatching { journal.reserve(attempt) }.exceptionOrNull() is IOException)
        // The commit happened despite the reported failure. A later independent mutation
        // must reload that outcome instead of writing the cached pre-reservation snapshot.
        journal.putDraft(key.copy(questionId = "other"), StoredPagedDraft("other", 2))
        assertEquals(attempt, PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot().attempts[attempt.attemptId])
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
            "[4,[],[],[]]",
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

    @Test fun readsLiteralVersionOneEncryptedFixtureWithoutLosingLegacyFence() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        // Literal v1 data includes an isolated UTF-16 surrogate in both draft and attempt.
        raw.write("""[1,[["route","session","question","2AA=",7]],[["route","session","question","attempt","grant","upload","commit","2AA=",42]]]""".toByteArray())
        val snapshot = PagedEditorStore(context).load()
        assertEquals(StoredPagedDraft("\uD800", 7), snapshot.drafts[key])
        assertEquals(attempt.copy(text = "\uD800"), snapshot.attempts["attempt"])
        assertNull(snapshot.attempts.getValue("attempt").outcomeBinding)
        PagedEditorStore(context).save(snapshot)
        assertEquals(snapshot, PagedEditorStore(context).load())
    }

    @Test fun readsVersionTwoExplicitNullAsLegacyUnknown() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        raw.write("""[2,[],[["route","session","question","attempt","grant","upload","commit","AGEAYgBj",42,null]]]""".toByteArray())
        val restored = PagedEditorStore(context).load().attempts.getValue("attempt")
        assertEquals("abc", restored.text)
        assertNull(restored.outcomeBinding)
    }

    @Test fun boundAttemptRoundTripsAndRejectsDifferentRetry() = runBlocking {
        val binding = StoredPagedOutcomeBinding("device", "owner", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val bound = attempt.copy(text = "abc", outcomeBinding = binding)
        val journal = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined)
        journal.reserve(bound)
        assertEquals(bound, PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot().attempts["attempt"])
        assertTrue(runCatching { journal.reserve(bound.copy(outcomeBinding = binding.copy(deviceId = "other"))) }.isFailure)
        assertEquals(bound, journal.snapshot().attempts["attempt"])
    }

    @Test fun invalidBindingOrStorageFailureDoesNotPublishReservation() = runBlocking {
        val binding = StoredPagedOutcomeBinding("device", "owner", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val storage = MemoryStorage()
        val journal = PagedEditorJournal(storage, Dispatchers.Unconfined)
        journal.putDraft(key, StoredPagedDraft("old", 1))
        val before = journal.snapshot()
        val bad = listOf(
            attempt.copy(text = "abc", outcomeBinding = binding.copy(byteLength = 2)),
            attempt.copy(text = "abc", outcomeBinding = binding.copy(sha256 = "0".repeat(64))),
            attempt.copy(text = "abc", outcomeBinding = binding.copy(deviceId = " ")),
            attempt.copy(text = "\uD800", outcomeBinding = binding),
            attempt.copy(text = "x".repeat(1024 * 1024 + 1), outcomeBinding = binding.copy(byteLength = 1024 * 1024 + 1)),
        )
        bad.forEach { assertTrue(runCatching { journal.reserve(it) }.isFailure); assertEquals(before, journal.snapshot()) }
        storage.fail = true
        assertTrue(runCatching { journal.reserve(attempt.copy(text = "abc", outcomeBinding = binding)) }.isFailure)
        assertEquals(before, journal.snapshot())
        assertEquals(before, PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot())
    }

    @Test fun malformedVersionTwoBindingsFailClosedAndPreserveEncryptedBytes() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        val prefix = """[2,[],[["route","session","question","attempt","grant","upload","commit","AGEAYgBj",42,"""
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val malformed = listOf("[]", "[null,\"owner\",3,\"$hash\"]", "[\"device\",\"owner\",null,\"$hash\"]", "[\"device\",\"owner\",3,null]", "[\"device\",\"owner\",3,\"$hash\",1]")
        for (binding in malformed) {
            raw.write((prefix + binding + "]]]").toByteArray())
            val file = File(dir, "paged-editor-journal.enc")
            val before = file.readBytes()
            assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
            assertThrows(Exception::class.java) { PagedEditorStore(context).save(PagedJournalSnapshot()) }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun retiringBoundAttemptAtomicallyQueuesMetadataAndExactRemovalIsSafe() = runBlocking {
        val binding = StoredPagedOutcomeBinding("device", "owner", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val bound = attempt.copy(text = "abc", outcomeBinding = binding)
        val expected = StoredPagedRelease("attempt", key, "device", "owner", "grant", "upload", "commit", 3, binding.sha256)
        val journal = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined)
        journal.reserve(bound)
        assertTrue(runCatching { journal.retireApplied(bound.copy(commitRequestId = "other")) }.isFailure)
        assertEquals(bound, journal.snapshot().attempts["attempt"])
        journal.retireApplied(bound)
        val restored = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot()
        assertTrue(restored.attempts.isEmpty())
        assertEquals("abc", restored.drafts.getValue(key).text)
        assertEquals(expected, restored.pendingReleases["attempt"])
        journal.retireApplied(bound)
        journal.removeRelease(expected.copy(ownerIncarnationId = "other"))
        assertEquals(expected, journal.snapshot().pendingReleases["attempt"])
        journal.removeRelease(expected)
        assertTrue(PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot().pendingReleases.isEmpty())
        journal.retireApplied(bound)
        assertTrue(journal.snapshot().pendingReleases.isEmpty())
    }

    @Test fun retirementFailurePreservesFenceDraftAndQueueAcrossReconstruction() = runBlocking {
        val binding = StoredPagedOutcomeBinding("device", "owner", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val bound = attempt.copy(text = "abc", outcomeBinding = binding)
        val storage = MemoryStorage(); val journal = PagedEditorJournal(storage, Dispatchers.Unconfined)
        journal.reserve(bound)
        val before = journal.snapshot(); storage.fail = true
        assertTrue(runCatching { journal.retireApplied(bound) }.isFailure)
        assertEquals(before, journal.snapshot())
        assertEquals(before, PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot())
        storage.fail = false; journal.retireApplied(bound)
        val queued = journal.snapshot(); storage.fail = true
        assertTrue(runCatching { journal.removeRelease(queued.pendingReleases.getValue("attempt")) }.isFailure)
        assertEquals(queued, PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot())
        assertTrue(runCatching { PagedEditorJournal(MemoryStorage(), Dispatchers.Unconfined).retireApplied(attempt) }.isFailure)
    }

    @Test fun fullRouteDeviceQueuePreventsRetirementWithoutEviction() = runBlocking {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val releases = (0 until 32).associate { n -> "r$n" to StoredPagedRelease("r$n", key.copy(questionId = "q$n"), "device", "owner", "grant", "upload", "c$n", 3, hash) }
        val storage = MemoryStorage().apply { saved = PagedJournalSnapshot(pendingReleases = releases) }
        val journal = PagedEditorJournal(storage, Dispatchers.Unconfined)
        val bound = attempt.copy(text = "abc", outcomeBinding = StoredPagedOutcomeBinding("device", "owner", 3, hash))
        journal.reserve(bound); val before = journal.snapshot()
        assertTrue(runCatching { journal.retireApplied(bound) }.exceptionOrNull() is PagedReleaseCapacityException)
        assertEquals(before, journal.snapshot())
        assertEquals(before, PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot())
    }

    @Test fun fullGlobalQueueRetainsAttemptWithoutEviction() = runBlocking {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val releases = (0 until 128).associate { n -> "r$n" to StoredPagedRelease("r$n", key.copy(routeId = "route$n"), "device", "owner", "grant", "upload", "c$n", 3, hash) }
        val storage = MemoryStorage().apply { saved = PagedJournalSnapshot(pendingReleases = releases) }
        val journal = PagedEditorJournal(storage, Dispatchers.Unconfined)
        val bound = attempt.copy(text = "abc", outcomeBinding = StoredPagedOutcomeBinding("device", "owner", 3, hash))
        journal.reserve(bound); val before = journal.snapshot()
        assertTrue(runCatching { journal.retireApplied(bound) }.isFailure)
        assertEquals(before, PagedEditorJournal(storage, Dispatchers.Unconfined).snapshot())
    }

    @Test fun successfulReleaseReclaimsCapacityAcrossMoreThanThirtyTwoRetirements() = runBlocking {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val journal = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined)
        repeat(40) { n ->
            val bound = attempt.copy(attemptId = "a$n", text = "abc", outcomeBinding = StoredPagedOutcomeBinding("device", "owner", 3, hash))
            journal.reserve(bound); journal.retireApplied(bound)
            journal.removeRelease(journal.snapshot().pendingReleases.getValue("a$n"))
        }
        val restored = PagedEditorJournal(PagedEditorStore(context), Dispatchers.Unconfined).snapshot()
        assertTrue(restored.attempts.isEmpty())
        assertTrue(restored.pendingReleases.isEmpty())
        assertEquals("abc", restored.drafts.getValue(key).text)
    }

    @Test fun readsLiteralVersionThreeAndRejectsMalformedAndDuplicateReleaseRecords() {
        val raw = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1")
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val entry = """["a","route","session","question","device","owner","grant","upload","commit",3,"$hash"]"""
        raw.write("""[3,[],[],[$entry]]""".toByteArray())
        val expected = StoredPagedRelease("a", key, "device", "owner", "grant", "upload", "commit", 3, hash)
        assertEquals(expected, PagedEditorStore(context).load().pendingReleases["a"])
        val malformed = listOf(
            """[3,[],[],[$entry,$entry]]""",
            """[3,[],[],[$entry,["b","route","session","question","device","owner","grant","upload","commit",3,"$hash"]]]""",
            """[3,[],[["route","session","question","a","grant","upload","commit","AGEAYgBj",42,["device","owner",3,"$hash"]]],[$entry]]""",
            """[3,[],[],[["a","route","session","question","device",null,"grant","upload","commit",3,"$hash"]]]""",
            """[3,[],[],[["a","route","session","question","device","owner","grant","upload","commit",1048577,"$hash"]]]""",
            """[3,[],[],[$entry],[]]""",
        )
        for (input in malformed) {
            raw.write(input.toByteArray())
            val file = File(dir, "paged-editor-journal.enc"); val before = file.readBytes()
            assertThrows(Exception::class.java) { PagedEditorStore(context).load() }
            assertThrows(Exception::class.java) { PagedEditorStore(context).save(PagedJournalSnapshot()) }
            assertArrayEquals(before, file.readBytes())
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
