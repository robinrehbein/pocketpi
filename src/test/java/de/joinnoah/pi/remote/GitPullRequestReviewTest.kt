package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class GitPullRequestReviewTest {
    private val selection = RemoteSelection("host", "project", "session")
    private val token = "Q2hhbmdlc1NuYXBzaG90MQ"
    private val target = GitWriteTarget("origin", "feature", "example.com", "team/repo")
    private val head = "a".repeat(40)
    private inner class Repo(private val fake: NavigationFakeRepository = NavigationFakeRepository()) : RemoteRepository by fake {
        override val state = fake.state
        var stored: GitPullRequestIntent? = null
        var edit: GitPullRequestDraft? = null
        var prepares = 0; var sends = 0; var checks = 0; var saveFails = false
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var removed = false
        val reply = CompletableDeferred<GitWriteResult.PullRequest>()
        override suspend fun gitPullRequestIntent(selection: RemoteSelection): GitPullRequestIntent? { val captured = stored; loadGate?.await(); return captured }
        override suspend fun gitPullRequestDraft(selection: RemoteSelection): GitPullRequestDraft? = edit
        override suspend fun saveGitPullRequestDraft(selection: RemoteSelection, draft: GitPullRequestDraft) { saveGate?.await(); if (saveFails || removed) error("storage"); edit = draft }
        override suspend fun saveGitPullRequestIntent(selection: RemoteSelection, intent: GitPullRequestIntent?) { if (saveFails) error("storage"); stored = intent }
        override suspend fun gitPullRequestPrepare(sessionId: String): GitWriteResult.Branch { prepares++; return GitWriteResult.Branch.Available(token, "feature", null, GitRemoteTip.EQUAL, 0, target, listOf("dev", "main"), "Suggested", emptyList(), head) }
        override suspend fun gitPullRequest(sessionId: String, writeId: String, base: String, title: String, body: String, draft: Boolean, requestId: String, onDispatch: () -> Unit): GitWriteResult.PullRequest { sends++; check(stored?.requestId == requestId); onDispatch(); return reply.await() }
        override suspend fun gitPullRequestStatus(sessionId: String, writeId: String): GitWriteResult.PullRequestStatus { checks++; return GitWriteResult.PullRequestStatus(GitPullRequestStatus.ABSENT, null) }
    }
    private fun ready(repo: Repo, create: Boolean = true) { repo.state.value = RemoteState(connected = true, capabilitiesKnown = true, selection = selection, capabilities = setOf(SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY) + if (create) setOf(SESSION_GIT_PULL_REQUEST_CAPABILITY) else emptySet()) }
    private fun intent() = GitPullRequestIntent("feature", head, token, "request-1", target, "dev", "Title", "body", true)

    @Test fun confirmedIntentPersistsBeforeOneDispatchAndKnownReplySurvivesAuthorityChange() = runTest {
        val repo = Repo(); ready(repo); val controller = GitPullRequestReviewController(repo, backgroundScope, selection)
        controller.prepare(); runCurrent(); assertEquals("Suggested", controller.state.value.draft.title)
        controller.setTitle("My title"); controller.setBody("body"); controller.review(); val confirmation = checkNotNull(controller.state.value.confirmation)
        assertEquals("My title", confirmation.title); assertEquals(head, confirmation.head); assertEquals("dev", confirmation.base)
        controller.confirm(); controller.confirm(); runCurrent(); assertEquals(1, repo.sends); assertEquals(confirmation, repo.stored)
        repo.state.value = repo.state.value.copy(gitReviewGeneration = 1); runCurrent()
        repo.reply.complete(GitWriteResult.PullRequest.Available(true, 42, "https://example.com/pr/42", "dev")); runCurrent()
        assertTrue(controller.state.value.result is GitWriteResult.PullRequest.Available); assertNull(repo.stored); assertTrue(controller.state.value.invalidated)
    }
    @Test fun recoveryWithWritesOffNeverCreatesAndAcknowledgementIsPersistFirst() = runTest {
        val repo = Repo(); repo.stored = intent(); ready(repo, false)
        val controller = GitPullRequestReviewController(repo, backgroundScope, selection); controller.prepare(); runCurrent()
        assertEquals(GitWriteResult.PullRequest.Unknown, controller.state.value.result); assertTrue(controller.state.value.canCheckRemote)
        controller.checkRemote(); runCurrent(); assertEquals(GitPullRequestStatus.ABSENT, controller.state.value.recovery?.state)
        assertEquals(0, repo.sends); assertEquals(0, repo.prepares); assertNotNull(repo.stored)
        repo.saveFails = true; controller.acknowledgeUnknown(); runCurrent(); assertNotNull(repo.stored); assertEquals(GitPullRequestReviewError.STORAGE, controller.state.value.error)
        repo.saveFails = false; controller.acknowledgeUnknown(); runCurrent(); assertNull(repo.stored); assertTrue(controller.state.value.invalidated)
    }
    @Test fun clearedTitleSurvivesRestartAndInvalidBaseCannotBeReviewed() = runTest {
        val repo = Repo(); repo.edit = GitPullRequestDraft("dev", "", "saved", true); ready(repo)
        val controller = GitPullRequestReviewController(repo, backgroundScope, selection); controller.prepare(); runCurrent()
        assertEquals("", controller.state.value.draft.title); controller.setBase("other"); controller.setTitle("Title"); controller.review()
        assertNull(controller.state.value.confirmation); assertEquals(GitPullRequestReviewError.INVALID_DRAFT, controller.state.value.error)
        runCurrent(); assertEquals("other", repo.edit?.base)
    }
    @Test fun cancelledAfterDispatchKeepsIntentAndNeverRetries() = runTest {
        val repo = Repo(); ready(repo); val job = SupervisorJob()
        val controller = GitPullRequestReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), selection)
        controller.prepare(); runCurrent(); controller.review(); controller.confirm(); runCurrent(); assertEquals(1, repo.sends)
        job.cancel(); runCurrent(); assertNotNull(repo.stored); assertEquals(GitWriteResult.PullRequest.Unknown, controller.state.value.result); assertEquals(1, repo.sends)
    }
    @Test fun lastVisibleEditSurvivesImmediateControllerScopeCancellation() = runTest {
        val repo = Repo(); ready(repo); val job = SupervisorJob()
        val controller = GitPullRequestReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), selection)
        controller.prepare(); runCurrent()
        controller.setTitle("Last visible title"); job.cancel()
        runCurrent()
        assertEquals("Last visible title", repo.edit?.title)
    }
    @Test fun queuedEditSurvivesCancellationButCannotResurrectForgottenHost() = runTest {
        val repo = Repo(); ready(repo); val job = SupervisorJob()
        val controller = GitPullRequestReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), selection)
        controller.prepare(); runCurrent()
        val gate = CompletableDeferred<Unit>(); repo.saveGate = gate
        controller.setTitle("first"); controller.setTitle("last"); job.cancel()
        gate.complete(Unit); runCurrent(); assertEquals("last", repo.edit?.title)

        val other = Repo(); ready(other); val otherJob = SupervisorJob()
        val otherController = GitPullRequestReviewController(other, CoroutineScope(otherJob + StandardTestDispatcher(testScheduler)), selection)
        otherController.prepare(); runCurrent()
        val otherGate = CompletableDeferred<Unit>(); other.saveGate = otherGate
        otherController.setTitle("first"); otherController.setTitle("last"); otherJob.cancel()
        other.removed = true; other.edit = null; otherGate.complete(Unit); runCurrent()
        assertNull(other.edit)
    }
    @Test fun staleSelectionAndStorageFailurePreventDispatch() = runTest {
        val repo = Repo(); ready(repo); val controller = GitPullRequestReviewController(repo, backgroundScope, selection)
        controller.prepare(); runCurrent(); controller.review(); repo.saveFails = true; controller.confirm(); runCurrent(); assertEquals(0, repo.sends)
        repo.saveFails = false; controller.review(); repo.state.value = repo.state.value.copy(selection = selection.copy(sessionId = "other"), gitReviewGeneration = 1)
        controller.confirm(); runCurrent(); assertEquals(0, repo.sends)
    }
}
