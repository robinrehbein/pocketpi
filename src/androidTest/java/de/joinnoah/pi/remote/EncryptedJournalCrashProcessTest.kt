package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three externally orchestrated process phases. After seed, force-stop the target package; run write,
 * wait for the synced `barrier` file, force-stop again, and run restore in a fresh process. Arguments:
 * encryptedJournalCrashPhase=seed|write|restore, encryptedJournalCrashNonce=<32 lowercase hex>,
 * encryptedJournalCrashOperation=reserve|retire|release, and
 * encryptedJournalCrashBoundary=partial-write|before-finish|after-finish.
 *
 * `barrier` contains the write PID, selected boundary, operation, byte counts and expected snapshot
 * digests. It is written only after the indicated real AtomicFile boundary. A successful write phase
 * never returns: the orchestrator must kill it. This exercises local storage only; no remote owner,
 * authority, acknowledgement or network action is simulated or established.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedJournalCrashProcessTest {
    @Test fun interruptedEncryptedJournalWrite() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("encryptedJournalCrashPhase") != null)
        val phase = requireNotNull(args.getString("encryptedJournalCrashPhase"))
        val nonce = requireNotNull(args.getString("encryptedJournalCrashNonce"))
        val operation = requireNotNull(args.getString("encryptedJournalCrashOperation"))
        val boundary = requireNotNull(args.getString("encryptedJournalCrashBoundary"))
        require(phase in setOf("seed", "write", "restore"))
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        require(operation in setOf("reserve", "retire", "release"))
        require(boundary in setOf("partial-write", "before-finish", "after-finish"))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val dir = File(app.noBackupFilesDir, "encrypted-journal-crash-$nonce")
        check(dir.mkdirs() || dir.isDirectory)
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = dir }
        val fixture = Fixture(nonce, operation)
        val store = PagedEditorStore(context)
        val journal = PagedEditorJournal(store)
        val seedFile = File(dir, "seed")
        val barrierFile = File(dir, "barrier")
        val contract = "operation=$operation boundary=$boundary old=${digest(fixture.old)} new=${digest(fixture.new)}"

        when (phase) {
            "seed" -> {
                check(!seedFile.exists() && !barrierFile.exists()) { "Use a fresh nonce" }
                store.save(fixture.old)
                assertEquals(fixture.old, journal.snapshot())
                val encrypted = File(dir, "paged-editor-journal.enc").readBytes()
                assertFalse(encrypted.toString(Charsets.UTF_8).contains("crash-payload-".repeat(16)))
                syncFile(seedFile, "pid=${Process.myPid()} $contract\n")
            }
            "write" -> {
                val seed = seedFile.readText()
                check(seed.contains(contract)) { "Seed parameters differ" }
                check(!barrierFile.exists()) { "Write phase already started" }
                assertNotEquals(parsePid(seed), Process.myPid())
                assertEquals(fixture.old, journal.snapshot())
                val encryptedField = PagedEditorStore::class.java.getDeclaredField("file").apply { isAccessible = true }
                val encrypted = encryptedField.get(store) as EncryptedFileStore
                val atomicField = EncryptedFileStore::class.java.getDeclaredField("file").apply { isAccessible = true }
                val real = atomicField.get(encrypted) as AtomicFile
                atomicField.set(encrypted, PausingAtomicFile(real, boundary) { written, total ->
                    syncFile(barrierFile, "pid=${Process.myPid()} $contract written=$written total=$total\n")
                    instrumentation.sendStatus(0, Bundle().apply { putString("encryptedJournalCrashBarrier", barrierFile.readText()) })
                    CountDownLatch(1).await()
                })
                when (operation) {
                    "reserve" -> journal.reserve(fixture.targetAttempt)
                    "retire" -> journal.retireApplied(fixture.targetAttempt)
                    "release" -> journal.removeRelease(fixture.targetRelease)
                }
                fail("The selected boundary must block until the process is killed")
            }
            "restore" -> {
                val seed = seedFile.readText()
                val barrier = barrierFile.readText()
                check(seed.contains(contract) && barrier.contains(contract)) { "Process parameters differ" }
                val writePid = parsePid(barrier)
                assertNotEquals(parsePid(seed), writePid)
                assertNotEquals(writePid, Process.myPid())
                val actual = journal.snapshot()
                val expected = if (boundary == "after-finish") fixture.new else fixture.old
                assertEquals(expected, actual)
                assertEquals(digest(expected), digest(actual))
                // The unrelated unknown-outcome fence remains effective across every crash point.
                try {
                    journal.reserve(fixture.foreignAttempt.copy(attemptId = "replacement-$nonce"))
                    fail("An unresolved foreign attempt must fence its question")
                } catch (_: IllegalArgumentException) { }
                assertEquals(expected, journal.snapshot())
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("encryptedJournalCrashProof", "phase=$phase nonce=$nonce pid=${Process.myPid()} $contract authorityRestored=false")
        })
    }

    private class PausingAtomicFile(
        private val real: AtomicFile,
        private val boundary: String,
        private val pause: (Int, Int) -> Unit,
    ) : AtomicFile(real.baseFile) {
        private lateinit var original: FileOutputStream
        private var written = 0
        private var total = 0

        override fun startWrite(): FileOutputStream {
            original = real.startWrite()
            return object : FileOutputStream(original.fd) {
                override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    total = length
                    if (boundary == "partial-write") {
                        val half = length / 2
                        check(half > 0 && half < length)
                        original.write(bytes, offset, half)
                        written = half
                        pause(written, total)
                        error("Process was not stopped at partial write")
                    }
                    original.write(bytes, offset, length)
                    written = length
                }
            }
        }

        override fun finishWrite(stream: FileOutputStream) {
            if (boundary == "before-finish") pause(written, total)
            real.finishWrite(original)
            if (boundary == "after-finish") pause(written, total)
        }

        override fun failWrite(stream: FileOutputStream) = real.failWrite(original)
        override fun readFully(): ByteArray = real.readFully()
    }

    private class Fixture(nonce: String, operation: String) {
        private val targetKey = PagedJournalKey("route-$nonce", "target-session", "target-question")
        private val foreignKey = PagedJournalKey("foreign-$nonce", "foreign-session", "foreign-question")
        private val text = "crash-payload-".repeat(4096) + "😀"
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private val binding = StoredPagedOutcomeBinding("device", "owner", bytes.size, sha(bytes))
        val targetAttempt = StoredPagedAttempt(targetKey, "target-$nonce", "grant", "upload", "commit", text, 7, binding)
        val foreignAttempt = StoredPagedAttempt(foreignKey, "foreign-$nonce", "foreign-grant", "foreign-upload", "foreign-commit", "unknown", 3)
        val targetRelease = StoredPagedRelease(targetAttempt.attemptId, targetKey, binding.deviceId, binding.ownerIncarnationId,
            targetAttempt.grantId, targetAttempt.uploadId, targetAttempt.commitRequestId, binding.byteLength, binding.sha256)
        private val foreignRelease = StoredPagedRelease("queued-$nonce", foreignKey, "other-device", "other-owner", "other-grant", "other-upload", "other-commit", 0, sha(byteArrayOf()))
        private val drafts = linkedMapOf(targetKey to StoredPagedDraft("visible draft\uD800\u0000", 5), foreignKey to StoredPagedDraft("foreign draft", 4))
        val old = PagedJournalSnapshot(drafts, linkedMapOf<String, StoredPagedAttempt>().apply {
            put(foreignAttempt.attemptId, foreignAttempt)
            if (operation == "retire") put(targetAttempt.attemptId, targetAttempt)
        }, linkedMapOf<String, StoredPagedRelease>().apply {
            put(foreignRelease.releaseId, foreignRelease)
            if (operation == "release") put(targetRelease.releaseId, targetRelease)
        })
        val new = when (operation) {
            "reserve" -> old.copy(drafts = old.drafts + (targetKey to StoredPagedDraft(text, 7)), attempts = old.attempts + (targetAttempt.attemptId to targetAttempt))
            "retire" -> old.copy(attempts = old.attempts - targetAttempt.attemptId, pendingReleases = old.pendingReleases + (targetRelease.releaseId to targetRelease))
            else -> old.copy(pendingReleases = old.pendingReleases - targetRelease.releaseId)
        }
    }

    companion object {
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun digest(snapshot: PagedJournalSnapshot) = sha(snapshot.toString().toByteArray(Charsets.UTF_8))
        private fun parsePid(contents: String) = contents.substringAfter("pid=").substringBefore(' ').toInt()
        private fun syncFile(file: File, text: String) = file.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }
}
