package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitPullRequestReviewPaneTest {
    @get:Rule val compose = createComposeRule()
    private val target = GitWriteTarget("origin", "feature", "github.com", "team/repo")
    private val head = "a".repeat(40)
    private val intent = GitPullRequestIntent("feature", head, "token", "request", target, "main", "Title", "Body\nsecond line", true)
    private fun actions(confirm: () -> Unit = {}, check: () -> Unit = {}, ack: () -> Unit = {}) =
        GitPullRequestReviewActions({}, {}, {}, {}, {}, {}, {}, {}, confirm, check, ack)

    @Test fun confirmationDisplaysEntireIntentAndRequiresExplicitConfirmation() {
        var calls = 0
        compose.setContent { MaterialTheme { GitPullRequestReviewPane(GitPullRequestReviewState(open = true, confirmation = intent), actions(confirm = { calls++ })) } }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag("prBranch").assertTextEquals(context.getString(R.string.pr_branch, "feature"))
        compose.onNodeWithTag("prTarget").assertTextEquals(context.getString(R.string.pr_target, "github.com", "team/repo"))
        compose.onNodeWithTag("prRemoteBranch").assertTextEquals(context.getString(R.string.pr_remote_branch, "origin", "feature"))
        compose.onNodeWithTag("prHead").assertTextEquals(context.getString(R.string.pr_head, head))
        compose.onNodeWithTag("prBase").assertTextEquals(context.getString(R.string.pr_base_value, "main"))
        compose.onNodeWithTag("prTitle").assertTextEquals(context.getString(R.string.pr_title_value, "Title"))
        compose.onNodeWithTag("prBody").assertTextEquals(context.getString(R.string.pr_body_value, "Body\nsecond line"))
        compose.onNodeWithTag("prDraft").assertTextEquals(context.getString(R.string.pr_draft_value))
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("prConfirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun unknownRequiresExplicitCheckAndSeparateAcknowledgement() {
        var checks = 0
        var acknowledgements = 0
        var state by mutableStateOf(GitPullRequestReviewState(open = true, pending = intent, result = GitWriteResult.PullRequest.Unknown))
        compose.setContent { MaterialTheme { GitPullRequestReviewPane(state, actions(check = { checks++ }, ack = { acknowledgements++ })) } }
        compose.onNodeWithTag("prCheckRemote").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, checks); state = state.copy(canCheckRemote = true) }
        compose.onNodeWithTag("prCheckRemote").performClick()
        compose.runOnIdle { assertEquals(1, checks); state = state.copy(recovery = GitWriteResult.PullRequestStatus(GitPullRequestStatus.ABSENT, null)) }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag("prRecovery").assertTextEquals(context.getString(R.string.pr_status_absent))
        compose.onNodeWithTag("prUnknownAcknowledge").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements) }
        compose.onNodeWithTag("prUnknownConfirm").performClick()
        compose.runOnIdle { assertEquals(1, acknowledgements) }
        compose.onNodeWithTag("prConfirm").assertDoesNotExist()
    }
    @Test fun unavailableExplainsWhatCanBeChanged() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var state by mutableStateOf(GitPullRequestReviewState(open = true, result = GitWriteResult.PullRequest.Unavailable(GitPullRequestReason.NOT_PUSHED)))
        compose.setContent { MaterialTheme { GitPullRequestReviewPane(state, actions()) } }
        compose.onNodeWithTag("prUnavailable").assertTextEquals(context.getString(R.string.pr_reason_not_pushed))
        compose.runOnIdle { state = state.copy(result = GitWriteResult.PullRequest.Unavailable(GitPullRequestReason.GH_AUTH)) }
        compose.onNodeWithTag("prUnavailable").assertTextEquals(context.getString(R.string.pr_reason_gh_auth))
        compose.runOnIdle { state = state.copy(result = GitWriteResult.PullRequest.Unavailable(GitPullRequestReason.REJECTED)) }
        compose.onNodeWithTag("prUnavailable").assertTextEquals(context.getString(R.string.pr_reason_rejected))
    }

}
