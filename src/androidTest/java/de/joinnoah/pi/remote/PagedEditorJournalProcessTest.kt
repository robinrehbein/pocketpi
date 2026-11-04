package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in two-process proof of the real encrypted journal, not of a Pi owner or restored authority. */
@RunWith(AndroidJUnit4::class)
class PagedEditorJournalProcessTest {
    @Test fun exactDraftAndUncertainAttemptSurviveProcessDeath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit private paged-journal process run", args.getString("pagedJournalPhase") != null)
        val phase = requireNotNull(args.getString("pagedJournalPhase"))
        require(phase in setOf("seed", "restore"))
        val nonce = requireNotNull(args.getString("pagedJournalNonce"))
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val app = instrumentation.targetContext.applicationContext
        val directory = File(app.noBackupFilesDir, "paged-journal-process-$nonce")
        check(directory.mkdirs() || directory.isDirectory)
        val isolated = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val key = PagedJournalKey("fixture-$nonce", "session", "question")
        val submitted = "x".repeat(1024 * 1024)
        // These code units cannot be submitted as Unicode scalar text; the visible draft is exact.
        val visible = submitted + "\uD800\u0000"
        val attempt = StoredPagedAttempt(key, "attempt-$nonce", "old-grant", "upload-$nonce", "commit-$nonce", submitted, 1)
        val journal = PagedEditorJournal(AppStores(isolated).pagedEditor)
        val pidFile = File(directory, "seed-pid")
        if (phase == "seed") {
            check(!pidFile.exists())
            journal.reserve(attempt)
            journal.putDraft(key, StoredPagedDraft(visible, 2))
            assertEquals(visible, journal.snapshot().drafts[key]?.text)
            assertEquals(attempt, journal.snapshot().attempts[attempt.attemptId])
            val ciphertext = File(directory, "paged-editor-journal.enc").readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains("x".repeat(256)))
            pidFile.outputStream().use { it.write(Process.myPid().toString().toByteArray()); it.fd.sync() }
        } else {
            assertNotEquals(pidFile.readText().toInt(), Process.myPid())
            val restored = journal.snapshot()
            assertEquals(visible, restored.drafts[key]?.text)
            assertEquals(1024 * 1024 + 2, restored.drafts[key]!!.text.length)
            assertEquals(attempt, restored.attempts[attempt.attemptId])
            var rejected = false
            try { journal.reserve(attempt.copy(attemptId = "replacement-$nonce", grantId = "new-grant", commitRequestId = "new-commit")) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("A new grant must not replace the old uncertain attempt", rejected)
            assertEquals(restored, journal.snapshot())
            // The nonce-scoped fixture has no remote owner; explicit test cleanup is not outcome proof.
            check(directory.deleteRecursively())
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("pagedJournalProof", "phase=$phase nonce=$nonce pid=${Process.myPid()} submittedBytes=1048576 draftCodeUnits=${visible.length} attempts=1 authorityRestored=false")
        })
    }
}
