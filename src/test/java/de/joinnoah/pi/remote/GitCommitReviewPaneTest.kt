package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitCommitReviewPaneTest {
    @get:Rule val compose = createComposeRule()

    private fun actions(toggle: (String) -> Unit = {}, confirm: () -> Unit = {}, acknowledge: () -> Unit = {}) =
        GitCommitReviewActions({}, toggle, {}, {}, {}, confirm, acknowledge)

    @Test fun emptySelectionAndOmittedFilesCannotBeSubmitted() {
        val snapshot = GitStatus(GitBase.HEAD, "Q2hhbmdlc1NuYXBzaG90MQ", listOf(
            GitFile("ok.txt", GitChange.MODIFIED, 1, 1),
            GitFile("large.bin", GitChange.ADDED, omitted = GitOmitted.TOO_LARGE),
            GitFile("nested", GitChange.MODIFIED, submodule = true),
        ), truncated = true, branch = "feature")
        var state by mutableStateOf(GitCommitReviewState(open = true, snapshot = snapshot, message = "Change"))
        val toggled = mutableListOf<String>()
        compose.setContent { MaterialTheme { GitCommitReviewPane(state, actions(toggle = { toggled.add(it); state = state.copy(selected = setOf(it)) })) } }
        compose.onNodeWithTag("commitReview").assertIsNotEnabled()
        compose.onNodeWithTag("commitFile:large.bin").assertIsNotEnabled()
        compose.onNodeWithTag("commitFile:nested").assertIsNotEnabled()
        compose.onNodeWithTag("commitTruncated").assertIsDisplayed()
        compose.onNodeWithTag("commitFile:ok.txt").assertIsOff().performClick()
        compose.onNodeWithTag("commitFile:ok.txt").assertIsOn()
        compose.runOnIdle { assertEquals(listOf("ok.txt"), toggled) }
    }

    @Test fun confirmationShowsExactValuesAndDispatchesOnlyOnConfirm() {
        val confirmation = GitCommitConfirmation("feature", listOf("a.txt"), "My message", "r", "snapshot")
        var calls = 0
        compose.setContent { MaterialTheme { GitCommitReviewPane(GitCommitReviewState(open = true, confirmation = confirmation), actions(confirm = { calls++ })) } }
        compose.onNodeWithTag("commitBranch").assertIsDisplayed()
        compose.onNodeWithTag("commitConfirmedMessage").assertIsDisplayed()
        compose.onNodeWithTag("commitConfirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun unknownHasNoRetryAndIndexFailureIsVisible() {
        var state by mutableStateOf(GitCommitReviewState(open = true, result = GitWriteResult.Commit.Unknown))
        var acknowledgements = 0
        compose.setContent { MaterialTheme { GitCommitReviewPane(state, actions(acknowledge = { acknowledgements++ })) } }
        compose.onNodeWithTag("commitUnknown").assertIsDisplayed()
        compose.onNodeWithTag("commitConfirm").assertDoesNotExist()
        compose.onNodeWithTag("commitUnknownAcknowledge").performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements) }
        compose.onNodeWithTag("commitUnknownConfirm").performClick()
        compose.runOnIdle { assertEquals(1, acknowledgements) }
        compose.runOnIdle { state = state.copy(result = GitWriteResult.Commit.Committed("feature", "a".repeat(40), 1, emptyList(), false)) }
        compose.onNodeWithTag("commitSuccess").assertIsDisplayed()
        compose.onNodeWithTag("commitIndexWarning").assertIsDisplayed()
    }
    @Test fun errorsExplainTheirSpecificCause() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var state by mutableStateOf(GitCommitReviewState(open = true, error = GitCommitReviewError.INVALID_MESSAGE))
        compose.setContent { MaterialTheme { GitCommitReviewPane(state, actions()) } }
        compose.onNodeWithTag("commitError").assertTextEquals(context.getString(R.string.remote_commit_error_invalid_message))
        compose.runOnIdle { state = state.copy(error = GitCommitReviewError.STORAGE) }
        compose.onNodeWithTag("commitError").assertTextEquals(context.getString(R.string.remote_commit_error_storage))
    }

}
