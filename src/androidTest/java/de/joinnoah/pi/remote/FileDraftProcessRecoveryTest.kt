package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two invocations separated by a root-controlled force-stop; synthetic Host, real encrypted store. */
@RunWith(AndroidJUnit4::class)
class FileDraftProcessRecoveryTest {
    @Test fun draftAndUnansweredWriteSurviveProcessRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit private process-recovery run only", args.getString("fileDraftPhase") != null)
        val nonce = requireNotNull(args.getString("fileDraftNonce"))
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val phase = requireNotNull(args.getString("fileDraftPhase"))
        require(phase in setOf("seed", "restore"))
        val app = instrumentation.targetContext.applicationContext
        val directory = File(app.noBackupFilesDir, "file-draft-process-$nonce")
        check(directory.mkdirs() || directory.isDirectory)
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val key = FileDraftKey("fixture-$nonce", "session", "src/main.kt")
        val pending = PendingWrite("request-$nonce", "original-version", "sent-$nonce")
        val draft = StoredFileDraft("original-version", "original-$nonce", "draft-$nonce-ä", listOf(pending))
        val journal = FileDraftJournal(AppStores(isolated).fileDrafts)
        val pidFile = File(directory, "seed-pid")
        if (phase == "seed") {
            check(!pidFile.exists())
            journal.put(key, draft)
            assertEquals(draft, journal.get(key))
            val ciphertext = File(directory, "file-drafts.enc").readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains(draft.text))
            pidFile.outputStream().use { it.write(Process.myPid().toString().toByteArray()); it.fd.sync() }
        } else {
            val seedPid = pidFile.readText().toInt()
            assertNotEquals(seedPid, Process.myPid())
            assertEquals(draft, journal.get(key))
            var writes = 0
            var state = RemoteState(connected = true, capabilitiesKnown = true,
                capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
                selection = RemoteSelection(key.routeId, "project", key.sessionId),
                files = FilesState(key.sessionId, file = OpenFile(key.path, loading = false,
                    version = "changed-host-version", content = "changed-host-$nonce")))
            val source = object : FileSource {
                override suspend fun filesList(sessionId: String, path: String, after: String?): FileListing = error("unused")
                override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk = error("unused")
                override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String): FileWritten {
                    writes++
                    error("Recovery must not replay a write")
                }
            }
            withContext(Dispatchers.Main) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                try {
                    val loader = ProjectFilesLoader(scope, source, { state }, journal = journal) { state = it(state) }
                    loader.startEditing()
                    withTimeout(10_000) { while (state.files?.editor?.recovery == null) delay(20) }
                    assertEquals("changed-host-$nonce", state.files!!.editor!!.text)
                    assertEquals(0, writes)
                    loader.restoreEditorDraft()
                    withTimeout(10_000) { while (state.files?.editor?.latestText != draft.text) delay(20) }
                    assertEquals(draft.text, state.files!!.editor!!.latestText)
                    assertEquals(pending, state.files!!.editor!!.unconfirmed)
                    assertTrue(state.files!!.editor!!.conflict)
                    assertEquals(0, writes)
                } finally { scope.cancel() }
            }
            journal.remove(key)
            check(directory.deleteRecursively())
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("fileDraftProof", "phase=$phase nonce=$nonce pid=${Process.myPid()} encrypted=true replayed=false")
        })
    }
}
