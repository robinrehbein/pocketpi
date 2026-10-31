package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class GitPushReviewTest {
    private val selection = RemoteSelection("host", "project", "session")
    private val token = "Q2hhbmdlc1NuYXBzaG90MQ"
    private val target = GitWriteTarget("origin", "feature", "example.com", "team/repo")
    private val head = "a".repeat(40)
    private inner class Repo(private val fake: NavigationFakeRepository = NavigationFakeRepository()) : RemoteRepository by fake {
        override val state = fake.state
        var stored: GitPushIntent? = null
        var prepares = 0; var sends = 0; var checks = 0; var saveFails = false
        var onRead: (suspend () -> Unit)? = null
        var onClear: (suspend () -> Unit)? = null
        var loadGate: CompletableDeferred<Unit>? = null
        val reply = CompletableDeferred<GitWriteResult.Push>()
        override suspend fun gitPushIntent(selection: RemoteSelection): GitPushIntent? { val captured = stored; loadGate?.await(); return captured }
        override suspend fun saveGitPushIntent(selection: RemoteSelection, intent: GitPushIntent?) { if (saveFails) error("storage"); stored = intent; if (intent == null) onClear?.invoke() }
        override suspend fun gitPushPrepare(sessionId: String): GitWriteResult.Branch { prepares++; onRead?.invoke(); return GitWriteResult.Branch.Available(token, "feature", null, GitRemoteTip.AHEAD, 1, target, emptyList(), null, emptyList(), head) }
        override suspend fun gitPush(sessionId: String, writeId: String, requestId: String, onDispatch: () -> Unit): GitWriteResult.Push { sends++; onDispatch(); return reply.await() }
        override suspend fun gitPushStatus(sessionId: String, writeId: String): GitWriteResult.PushStatus { checks++; return GitWriteResult.PushStatus(GitPushStatus.MATCHES) }
    }
    private fun ready(repo: Repo, push: Boolean = true) { repo.state.value = RemoteState(connected = true, capabilitiesKnown = true, selection = selection, capabilities = setOf(GIT_PUSH_STATUS_CAPABILITY) + if (push) setOf(GIT_PUSH_CAPABILITY) else emptySet()) }
    private fun intent() = GitPushIntent("feature", head, token, "request-1", target)

    @Test fun pushIntentRoundTripsAndRejectsInconsistentTarget() {
        assertEquals(intent(), parseGitPushIntent(intent().json()))
        assertTrue(runCatching { parseGitPushIntent(intent().copy(target = target.copy(branch = "other")).json()) }.isFailure)
    }
    @Test fun reviewPersistsBeforeOneDispatchAndShowsConfirmedResult() = runTest {
        val repo = Repo(); ready(repo); val controller = GitPushReviewController(repo, backgroundScope, selection)
        controller.prepare(); runCurrent(); controller.review(); val confirmation = checkNotNull(controller.state.value.confirmation)
        assertEquals(head, confirmation.head); assertEquals(target, confirmation.target); assertEquals(token, confirmation.writeId)
        controller.confirm(); controller.confirm(); runCurrent(); assertEquals(1, repo.sends); assertEquals(confirmation, repo.stored)
        repo.reply.complete(GitWriteResult.Push.Pushed("feature", "feature", head, true, false)); runCurrent()
        assertTrue(controller.state.value.result is GitWriteResult.Push.Pushed); assertNull(repo.stored)
    }
    @Test fun restartUnknownCanCheckWithOwnerOffAndNeedsExplicitAck() = runTest {
        val repo = Repo(); repo.stored = intent(); ready(repo, push = false)
        val controller = GitPushReviewController(repo, backgroundScope, selection); controller.prepare(); runCurrent()
        assertEquals(GitWriteResult.Push.Unknown, controller.state.value.result); assertTrue(controller.state.value.canCheckRemote)
        controller.checkRemote(); runCurrent(); assertEquals(GitPushStatus.MATCHES, controller.state.value.recovery?.state)
        assertNotNull(repo.stored); assertEquals(0, repo.sends); assertEquals(0, repo.prepares)
        repo.saveFails = true; controller.acknowledgeUnknown(); runCurrent(); assertNotNull(repo.stored); assertEquals(GitPushReviewError.STORAGE, controller.state.value.error)
        repo.saveFails = false; controller.acknowledgeUnknown(); runCurrent(); assertNull(repo.stored); assertTrue(controller.state.value.invalidated)
    }
    @Test fun staleSelectionOrStorageFailureNeverDispatches() = runTest {
        val repo = Repo(); ready(repo); val controller = GitPushReviewController(repo, backgroundScope, selection)
        controller.prepare(); runCurrent(); controller.review(); repo.saveFails = true; controller.confirm(); runCurrent(); assertEquals(0, repo.sends)
        repo.saveFails = false; controller.review(); repo.state.value = repo.state.value.copy(selection = RemoteSelection("host", "project", "other"), gitReviewGeneration = 1)
        controller.confirm(); runCurrent(); assertEquals(0, repo.sends)
    }
    @Test fun cancelledAfterDispatchRetainsPendingWithoutRetry() = runTest {
        val repo = Repo(); ready(repo); val job = SupervisorJob()
        val controller = GitPushReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), selection)
        controller.prepare(); runCurrent(); controller.review(); controller.confirm(); runCurrent(); assertEquals(1, repo.sends)
        job.cancel(); runCurrent(); assertEquals(GitWriteResult.Push.Unknown, controller.state.value.result); assertNotNull(repo.stored); assertEquals(1, repo.sends)
    }

    @Test fun ownerOffHydratesPendingWhileClosed() = runTest {
        val repo = Repo(); repo.stored = intent(); ready(repo, push = false)
        val controller = GitPushReviewController(repo, backgroundScope, selection); runCurrent()
        assertFalse(controller.state.value.open); assertEquals(intent(), controller.state.value.pending)
        assertEquals(GitWriteResult.Push.Unknown, controller.state.value.result); assertEquals(0, repo.prepares); assertEquals(0, repo.sends)
    }

    @Test fun storedDraftRetainsComposerCommitAndPushIntent() {
        val commit = GitCommitDraft("subject", GitCommitConfirmation("feature", listOf("x"), "subject", "request-2", token))
        val encoded = JsonArray(listOf(Wire.objectOf("routeId" to "host", "sessionId" to "session", "text" to "composer", "gitCommit" to commit.json(), "gitPush" to intent().json())))
        val restored = parseStoredDrafts(encoded.toString().toByteArray()).getValue(DraftKey("host", "session"))
        assertEquals("composer", restored.text); assertEquals(commit, restored.gitCommit); assertEquals(intent(), restored.gitPush)
    }

    @Test fun queuedOldComposerSnapshotCannotErasePushOrCommitAndUnpairDoesNotRestoreIt() = runTest {
        val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
        val pairings = object : PairingStorage { override fun load() = listOf(host); override fun save(hosts: List<PairedHost>) {} }
        val storage = object : DraftStorage {
            var values = emptyMap<DraftKey, StoredDraft>()
            var hook: ((Map<DraftKey, StoredDraft>) -> Unit)? = null
            var fail = false
            override fun load() = values
            override fun save(drafts: Map<DraftKey, StoredDraft>) { if (fail) error("storage"); hook?.invoke(drafts); values = drafts.toMap() }
        }
        val transport = object : RemoteTransport {
            override lateinit var listener: RemoteTransport.Listener
            override fun connect(host: PairedHost) {}
            override fun pair(value: kotlinx.serialization.json.JsonObject) {}
            override fun close() {}
            override fun send(payload: kotlinx.serialization.json.JsonObject) {}
        }
        val repo = DefaultRemoteRepository(pairings, storage, transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime })
        runCurrent()
        val commit = GitCommitDraft("subject", GitCommitConfirmation("feature", listOf("x"), "subject", "request-2", token))
        repo.saveGitCommitDraft(selection, commit)
        var queued = false
        storage.hook = { values -> if (!queued && values[DraftKey("host", "session")]?.gitPush != null) {
            queued = true; repo.draft(selection, "composer"); backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.flush() }
        } }
        repo.saveGitPushIntent(selection, intent()); runCurrent(); repo.flush()
        assertTrue(queued); val saved = storage.values.getValue(DraftKey("host", "session"))
        assertEquals("composer", saved.text); assertEquals(commit, saved.gitCommit); assertEquals(intent(), saved.gitPush)
        storage.hook = null
        storage.fail = true; assertTrue(runCatching { repo.saveGitPushIntent(selection, null) }.isFailure)
        assertEquals(intent(), repo.gitPushIntent(selection))
        storage.fail = false; repo.draft(selection, "later"); repo.flush()
        assertEquals(intent(), storage.values[DraftKey("host", "session")]?.gitPush)
        assertEquals(commit, storage.values[DraftKey("host", "session")]?.gitCommit)
        repo.remove("host"); runCurrent(); repo.flush()
        assertTrue(storage.values.keys.none { it.routeId == "host" })
    }

    @Test fun staleInitialHydrationCannotResurrectIntentAfterPrepare() = runTest {
        val repo = Repo(); ready(repo, push = false); repo.stored = intent(); val gate = CompletableDeferred<Unit>(); repo.loadGate = gate
        val controller = GitPushReviewController(repo, backgroundScope, selection); runCurrent()
        repo.stored = null; controller.prepare(); runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(controller.state.value.pending); assertNull(controller.state.value.result); assertEquals(0, repo.sends)
    }
    @Test fun authorityChangedWhilePreparingDiscardsStaleBranch() = runTest {
        val repo = Repo(); ready(repo); val gate = CompletableDeferred<Unit>(); repo.onRead = { gate.await() }
        val controller = GitPushReviewController(repo, backgroundScope, selection); controller.prepare(); runCurrent()
        repo.state.value = repo.state.value.copy(gitReviewGeneration = 1); runCurrent(); gate.complete(Unit); runCurrent()
        assertNull(controller.state.value.preparation); assertTrue(controller.state.value.invalidated); assertEquals(0, repo.sends)
    }

    @Test fun cancellationDuringConfirmedIntentClearRetainsKnownResult() = runTest {
        val repo = Repo(); ready(repo); val job = SupervisorJob()
        val controller = GitPushReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), selection)
        controller.prepare(); runCurrent(); controller.review(); controller.confirm(); runCurrent()
        repo.onClear = { job.cancel(); yield() }
        repo.reply.complete(GitWriteResult.Push.Pushed("feature", "feature", head, true, false)); runCurrent()
        assertNull(repo.stored); assertEquals(GitWriteResult.Push.Pushed("feature", "feature", head, true, false), controller.state.value.result); assertEquals(1, repo.sends)
    }

    @Test fun ownerSwitchOffBeforeConfirmedReplyRetainsKnownResult() = runTest {
        val repo = Repo(); ready(repo); val controller = GitPushReviewController(repo, backgroundScope, selection)
        controller.prepare(); runCurrent(); controller.review(); controller.confirm(); runCurrent()
        repo.state.value = repo.state.value.copy(capabilities = setOf(GIT_PUSH_STATUS_CAPABILITY), gitReviewGeneration = 1)
        repo.reply.complete(GitWriteResult.Push.Pushed("feature", "feature", head, true, false)); runCurrent()
        assertEquals(GitWriteResult.Push.Pushed("feature", "feature", head, true, false), controller.state.value.result)
        assertTrue(controller.state.value.invalidated); assertNull(repo.stored); assertEquals(1, repo.sends)
    }
}
