package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChangesPaneTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val snapshot = "Q2hhbmdlc1NuYXBzaG90MQ"

    private val status =
        GitStatus(
            GitBase.SESSION,
            snapshot,
            listOf(
                GitFile("big.kt", GitChange.MODIFIED, 2000, 0),
                GitFile("logo.png", GitChange.ADDED, binary = true),
                GitFile("dump.sql", GitChange.ADDED, omitted = GitOmitted.TOO_LARGE),
                GitFile("notes.md", GitChange.ADDED, omitted = GitOmitted.BASE_UNAVAILABLE),
            ),
            since = GitSince(true, 1_790_000_000_000),
        )

    private val bigPatch =
        "@@ -0,0 +1,2000 @@\n" + (1..2000).joinToString("") { "+line $it\n" }

    private class Recorder {
        val prefills = mutableListOf<String>()
        val reviews = mutableListOf<String>()
        val opened = mutableListOf<String?>()
        var saveResult = true
        var prefillResult = true
    }

    private fun render(initial: ChangesState, recorder: Recorder) {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    state,
                    ChangesActions(
                        onClose = {},
                        onSelectBase = {},
                        onReload = {},
                        onOpenFile = { recorder.opened += it },
                        onSetComment = { comment ->
                            recorder.saveResult.also { saved ->
                                if (saved)
                                    state = state.copy(comments = state.comments.filterNot { it.sameLine(comment) } + comment)
                            }
                        },
                        onRemoveComment = {},
                        onUseReview = { recorder.reviews += it; recorder.prefillResult },
                        onPrefill = { recorder.prefills += it; recorder.prefillResult },
                    ),
                )
            }
        }
    }

    @Test
    fun fileListShowsSinceLabelKindsAndStates() {
        val recorder = Recorder()
        render(ChangesState("s", loading = false, status = status, log = GitLog()), recorder)
        val time =
            android.text.format.DateUtils.formatDateTime(
                context,
                1_790_000_000_000,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
            )
        compose.onNodeWithText(context.getString(R.string.remote_changes_since_first_contact, time)).assertExists()
        compose.onAllNodesWithTag("changesFile").assertCountEquals(4)
        compose.onNodeWithText(context.getString(R.string.remote_changes_base_content_gone)).assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_changes_binary)).assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_changes_too_large)).assertExists()
        compose.onNodeWithText("+2000").assertExists()
        compose.onNodeWithText("big.kt").performClick()
        assertEquals(listOf<String?>("big.kt"), recorder.opened)
    }

    @Test
    fun longDiffIsLazyAndACommentBecomesTheReviewPrompt() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, bigPatch, binary = false, truncated = false),
            ),
            recorder,
        )
        val composed = compose.onAllNodesWithTag("changesDiffLine").fetchSemanticsNodes().size
        assertTrue("lazy list composed $composed rows", composed in 1..200)

        compose.onNodeWithTag("changesDiff").performScrollToNode(hasTestTag("changesDiffLine"))
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onNodeWithTag("changesCommentField").performTextInput("Why this line?")
        compose.onNodeWithTag("changesCommentSave").performClick()
        compose.onNodeWithText("Why this line?").assertExists()

        compose.onNodeWithTag("changesUseReview").performClick()
        assertEquals(
            listOf(
                context.getString(R.string.remote_changes_review_intro) + "\n\n" +
                    context.getString(
                        R.string.remote_changes_review_line,
                        "big.kt",
                        1,
                        context.getString(R.string.remote_changes_review_base_session),
                    ) +
                    "\n> line 1\nWhy this line?"
            ),
            recorder.reviews,
        )
        assertTrue(recorder.prefills.isEmpty())
    }

    @Test
    fun quickActionsOnlyPrefill() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "logo.png",
                diff = GitDiff("logo.png", snapshot, "", binary = true, truncated = false),
            ),
            recorder,
        )
        compose.onNodeWithText(context.getString(R.string.remote_changes_diff_binary)).assertExists()
        compose.onNodeWithTag("changesActionRevert").performClick()
        compose.onNodeWithTag("changesActionExplain").performClick()
        assertEquals(
            listOf(
                context.getString(
                    R.string.remote_changes_prompt_revert,
                    "logo.png",
                    context.getString(R.string.remote_changes_prompt_base_session),
                ),
                context.getString(R.string.remote_changes_prompt_explain, "logo.png"),
            ),
            recorder.prefills,
        )
        assertTrue(recorder.reviews.isEmpty())
    }

    @Test
    fun failedSaveKeepsTheDialogAndFailedPrefillIsShown() {
        val recorder = Recorder().apply {
            saveResult = false
            prefillResult = false
        }
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, "@@ -1 +1 @@\n-a\n+b\n", binary = false, truncated = false),
            ),
            recorder,
        )
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onNodeWithTag("changesCommentField").performTextInput("Note")
        compose.onNodeWithTag("changesCommentSave").performClick()
        compose.onNodeWithTag("changesCommentError").assertExists()
        compose.onNodeWithTag("changesCommentField").assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_changes_comment_cancel)).performClick()
        compose.onNodeWithTag("changesActionTests").performClick()
        compose.onNodeWithTag("changesPrefillFailed").assertExists()
    }

    @Test
    fun commentsShowOnlyUnderTheirOwnBase() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, "@@ -1 +1 @@\n-a\n+b\n", binary = false, truncated = false),
                comments = listOf(
                    ReviewComment("big.kt", null, 1, "b", "Head note", GitBase.HEAD),
                    ReviewComment("big.kt", null, 1, "b", "Session note", GitBase.SESSION),
                ),
            ),
            recorder,
        )
        compose.onNodeWithText("Session note").assertExists()
        compose.onNodeWithText("Head note").assertDoesNotExist()
    }

    @Test
    fun failedPrefillShowsAfterOpeningAFile() {
        var prefills = 0
        var state by mutableStateOf(ChangesState("s", loading = false, status = status, log = GitLog()))
        val actions =
            ChangesActions(
                onClose = {},
                onSelectBase = {},
                onReload = {},
                onOpenFile = {},
                onSetComment = { false },
                onRemoveComment = {},
                onUseReview = { false },
                onPrefill = {
                    prefills++
                    false
                },
            )
        compose.setContent { MaterialTheme { ChangesPane(state, actions) } }
        compose.onNodeWithTag("changesPrefillFailed").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                file = "logo.png",
                diff = GitDiff("logo.png", snapshot, "", binary = true, truncated = false),
            )
        }
        compose.onNodeWithTag("changesActionTests").performClick()
        compose.onNodeWithTag("changesPrefillFailed").assertExists()
        assertEquals(1, prefills)
        // Another file starts without the old error.
        compose.runOnIdle { state = state.copy(file = "big.kt", diff = null) }
        compose.onNodeWithTag("changesPrefillFailed").assertDoesNotExist()
    }
}
