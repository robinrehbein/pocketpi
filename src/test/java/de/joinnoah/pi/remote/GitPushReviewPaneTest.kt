package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
class GitPushReviewPaneTest {
    @get:Rule val compose = createComposeRule()
    private val target = GitWriteTarget("origin", "feature", "example.com", "team/repo")
    private val head = "a".repeat(40)
    private val intent = GitPushIntent("feature", head, "token", "request", target)
    private fun actions(prepare: () -> Unit = {}, confirm: () -> Unit = {}, check: () -> Unit = {}, ack: () -> Unit = {}, resend: () -> Unit = {}) =
        GitPushReviewActions(prepare, {}, {}, {}, confirm, check, ack, resend)

    @Test fun confirmationShowsTargetAndFullCommitBeforeExplicitPush() {
        var calls = 0
        compose.setContent { MaterialTheme { GitPushReviewPane(GitPushReviewState(open = true, confirmation = intent), actions(confirm = { calls++ })) } }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag("pushBranch").assertTextEquals(context.getString(R.string.remote_push_branch, "feature"))
        compose.onNodeWithTag("pushTarget").assertTextEquals(context.getString(R.string.remote_push_target, "example.com", "team/repo"))
        compose.onNodeWithTag("pushHead").assertTextEquals(context.getString(R.string.remote_push_head, head))
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("pushConfirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun unknownRequiresExplicitNetworkReadAndSeparateAcknowledgement() {
        var checks = 0
        var acknowledgements = 0
        var prepares = 0
        var state by mutableStateOf(GitPushReviewState(open = true, pending = intent, result = GitWriteResult.Push.Unknown, canCheckRemote = false, invalidated = true))
        compose.setContent { MaterialTheme { GitPushReviewPane(state, actions(prepare = { prepares++ }, check = { checks++ }, ack = { acknowledgements++ })) } }
        compose.onNodeWithTag("pushInvalidated").assertDoesNotExist()
        compose.onNodeWithTag("pushUnknown").assertIsDisplayed()
        compose.onNodeWithTag("pushCheckRemote").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, checks); state = state.copy(canCheckRemote = true) }
        compose.onNodeWithTag("pushCheckRemote").performClick()
        compose.runOnIdle { assertEquals(1, checks); state = state.copy(recovery = GitWriteResult.PushStatus(GitPushStatus.MATCHES)) }
        compose.onNodeWithTag("pushRecovery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pushUnknownAcknowledge").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements) }
        compose.onNodeWithTag("pushUnknownConfirm").performClick()
        compose.runOnIdle { assertEquals(1, acknowledgements) }
        compose.onNodeWithTag("pushConfirm").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(pending = null, result = null, recovery = null, invalidated = true)
            assertEquals(0, prepares)
        }
        compose.onNodeWithTag("pushPrepareAgain").performClick()
        compose.runOnIdle { assertEquals(1, prepares) }
    }

    @Test fun unknownOffersAnExplicitResendOnlyWhenAllowed() {
        var resends = 0
        var state by mutableStateOf(GitPushReviewState(open = true, pending = intent, result = GitWriteResult.Push.Unknown, canResend = false))
        compose.setContent { MaterialTheme { GitPushReviewPane(state, actions(resend = { resends++ })) } }
        compose.onNodeWithTag("pushResend").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(canResend = true) }
        compose.onNodeWithTag("pushResend").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resends); state = state.copy(canResend = false, resendNotice = GitPushResendNotice.REFUSED, resendRefusal = GitPushReason.NETWORK) }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithTag("pushResendNotice").performScrollTo().assertTextEquals(
            context.getString(R.string.remote_push_resend_refused, context.getString(R.string.remote_push_reason_network)))
        compose.onNodeWithTag("pushUnknown").assertIsDisplayed()
    }

    @Test fun observedRemoteStatesAreDescribedAsCurrentNotPastSuccess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var state by mutableStateOf(GitPushReviewState(open = true, pending = intent, result = GitWriteResult.Push.Unknown, recovery = GitWriteResult.PushStatus(GitPushStatus.ABSENT)))
        compose.setContent { MaterialTheme { GitPushReviewPane(state, actions()) } }
        compose.onNodeWithTag("pushRecovery").assertTextEquals(context.getString(R.string.remote_push_status_absent))
        compose.runOnIdle { state = state.copy(recovery = GitWriteResult.PushStatus(GitPushStatus.DIFFERENT)) }
        compose.onNodeWithTag("pushRecovery").assertTextEquals(context.getString(R.string.remote_push_status_different))
        compose.runOnIdle { state = state.copy(recovery = GitWriteResult.PushStatus(GitPushStatus.UNAVAILABLE)) }
        compose.onNodeWithTag("pushRecovery").assertTextEquals(context.getString(R.string.remote_push_status_unavailable))
    }
    @Test fun successfulPushWithUnchangedUpstreamIsNotReportedAsFailure() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = GitWriteResult.Push.Pushed("feature", "feature", head, true, false)
        compose.setContent { MaterialTheme { GitPushReviewPane(GitPushReviewState(open = true, result = result), actions()) } }
        compose.onNodeWithTag("pushSuccess").assertTextEquals(context.getString(R.string.remote_push_created, "feature"))
        compose.onNodeWithTag("pushResultHead").assertTextEquals(context.getString(R.string.remote_push_head, head))
        compose.onNodeWithTag("pushUpstreamUnchanged").assertIsDisplayed()
    }

}
