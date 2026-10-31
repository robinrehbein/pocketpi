package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class GitCommitReviewTest {
    private val target = RemoteSelection("host", "project", "session")
    private val token = "Q2hhbmdlc1NuYXBzaG90MQ"

    private class Repo(private val fake: NavigationFakeRepository = NavigationFakeRepository()) : RemoteRepository by fake {
        override val state = fake.state
        var draft = GitCommitDraft()
        var status = GitStatus(GitBase.HEAD, "Q2hhbmdlc1NuYXBzaG90MQ", listOf(
            GitFile("one", GitChange.MODIFIED, 1, 0),
            GitFile("omit", GitChange.MODIFIED, omitted = GitOmitted.TOO_LARGE),
            GitFile("sub", GitChange.MODIFIED, binary = true, submodule = true),
        ), branch = "feature")
        var readCount = 0
        var sends = 0
        var failSave = false
        val reply = CompletableDeferred<GitWriteResult.Commit>()
        override suspend fun gitCommitStatus(sessionId: String): GitStatus { readCount++; return status }
        override suspend fun gitCommitDraft(selection: RemoteSelection) = draft
        override suspend fun saveGitCommitDraft(selection: RemoteSelection, draft: GitCommitDraft) { if (failSave) error("storage"); this.draft = draft }
        override suspend fun gitCommit(sessionId: String, snapshotId: String, message: String, paths: List<String>, requestId: String, onDispatch: () -> Unit): GitWriteResult.Commit {
            sends++; onDispatch(); return reply.await()
        }
    }

    private fun ready(repo: Repo) { repo.state.value = RemoteState(connected = true, selection = target, capabilities = setOf(GIT_CAPABILITY, GIT_WRITE_CAPABILITY)) }

    @Test fun draftAndUnresolvedIntentRoundTrip() {
        val pending = GitCommitConfirmation("feature", listOf("one"), "subject", "request-1", token)
        val draft = GitCommitDraft("subject", pending)
        assertEquals(draft, parseGitCommitDraft(draft.json()))
    }

    @Test fun selectionAndConfirmationAreExplicitAndSnapshotBound() = runTest {
        val repo = Repo(); ready(repo)
        val controller = GitCommitReviewController(repo, backgroundScope, target)
        controller.prepare(); runCurrent()
        assertEquals(1, repo.readCount)
        assertTrue(controller.state.value.selected.isEmpty())
        controller.toggle("omit"); controller.toggle("sub"); assertTrue(controller.state.value.selected.isEmpty())
        controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        val confirmation = checkNotNull(controller.state.value.confirmation)
        assertEquals(token, confirmation.snapshotId)
        assertEquals(listOf("one"), confirmation.paths)
        assertEquals("feature", confirmation.branch)
        controller.confirm(); controller.confirm(); runCurrent()
        assertEquals(1, repo.sends)
        assertEquals(confirmation, repo.draft.pending)
        repo.reply.complete(GitWriteResult.Commit.Committed("feature", "a".repeat(40), 1, emptyList(), false)); runCurrent()
        assertFalse((controller.state.value.result as GitWriteResult.Commit.Committed).indexUpdated)
        assertNull(repo.draft.pending)
    }

    @Test fun connectionLossInvalidatesReviewAndRestartShowsPendingUnknown() = runTest {
        val repo = Repo(); ready(repo)
        val controller = GitCommitReviewController(repo, backgroundScope, target)
        controller.prepare(); runCurrent(); controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        controller.confirm(); runCurrent()
        repo.state.value = repo.state.value.copy(connected = false); runCurrent()
        assertTrue(controller.state.value.invalidated)
        val next = GitCommitReviewController(repo, backgroundScope, target)
        ready(repo); next.prepare(); runCurrent()
        assertEquals(GitWriteResult.Commit.Unknown, next.state.value.result)
        next.toggle("one"); next.review(); next.confirm(); runCurrent()
        assertEquals(1, repo.sends)
        repo.failSave = true
        next.acknowledgeUnknown(); runCurrent()
        assertEquals(GitWriteResult.Commit.Unknown, next.state.value.result)
        assertNotNull(repo.draft.pending)
        repo.failSave = false
        next.acknowledgeUnknown(); runCurrent()
        assertNull(next.state.value.result)
        assertNull(repo.draft.pending)
        assertTrue(next.state.value.invalidated)
        assertEquals(1, repo.sends)
        assertEquals(2, repo.readCount)
        next.prepare(); runCurrent()
        assertEquals(3, repo.readCount)
    }

    @Test fun targetChangeBeforeConfirmationPreventsDispatch() = runTest {
        val repo = Repo(); ready(repo)
        val controller = GitCommitReviewController(repo, backgroundScope, target)
        controller.prepare(); runCurrent(); controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        repo.state.value = repo.state.value.copy(selection = RemoteSelection("host", "project", "other")); runCurrent()
        controller.confirm(); runCurrent()
        assertEquals(0, repo.sends)
        assertTrue(controller.state.value.invalidated)
    }

    @Test fun failedIntentStoragePreventsDispatch() = runTest {
        val repo = Repo(); ready(repo)
        val controller = GitCommitReviewController(repo, backgroundScope, target)
        controller.prepare(); runCurrent(); controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        repo.failSave = true
        controller.confirm(); runCurrent()
        assertEquals(0, repo.sends)
        assertEquals(GitCommitReviewError.STORAGE, controller.state.value.error)
        repo.failSave = false
        controller.review(); controller.confirm(); runCurrent()
        assertEquals(1, repo.sends)
    }

    @Test fun fastAuthorityRoundTripInvalidatesReviewBeforeFlowCollectorRuns() = runTest {
        val repo = Repo(); ready(repo)
        val controller = GitCommitReviewController(repo, backgroundScope, target)
        controller.prepare(); runCurrent(); controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        val original = repo.state.value
        repo.state.value = original.copy(connected = false, gitReviewGeneration = original.gitReviewGeneration + 1)
        repo.state.value = original.copy(gitReviewGeneration = original.gitReviewGeneration + 2)
        controller.confirm(); runCurrent()
        assertEquals(0, repo.sends)
        assertTrue(controller.state.value.invalidated)
    }

    @Test fun cancellationAfterDispatchRetainsUnknownAndPendingIntent() = runTest {
        val repo = Repo(); ready(repo)
        val job = SupervisorJob()
        val controller = GitCommitReviewController(repo, CoroutineScope(job + StandardTestDispatcher(testScheduler)), target)
        controller.prepare(); runCurrent(); controller.toggle("one"); controller.setMessage("subject"); runCurrent(); controller.review()
        controller.confirm(); runCurrent()
        assertEquals(1, repo.sends)
        job.cancel(); runCurrent()
        assertEquals(GitWriteResult.Commit.Unknown, controller.state.value.result)
        assertNotNull(repo.draft.pending)
    }
}
