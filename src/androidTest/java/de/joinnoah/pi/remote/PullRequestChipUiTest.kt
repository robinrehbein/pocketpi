package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PullRequestChipUiTest {
    @get:Rule val compose = createComposeRule()

    private fun pr(failed: Int, checks: PullRequestChecksState, draft: Boolean = false) =
        PullRequestStatus(
            123,
            "https://github.com/o/r/pull/123",
            draft,
            PullRequestReview.NONE,
            checks,
            failed,
            if (failed > 0) listOf("build") else emptyList(),
        )

    @Test
    fun chipShowsTheNumberAndFailingChecksAndTapReportsAClick() {
        var taps = 0
        compose.setContent {
            MaterialTheme { PullRequestChip(pr(2, PullRequestChecksState.DONE)) { taps++ } }
        }
        val chip = compose.onNodeWithTag("pullRequestChip")
        chip.assertIsDisplayed().assertHasClickAction()
        chip.assertTextContains("PR #123", substring = true).assertTextContains("2 failing", substring = true)
        chip.performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun draftChipSaysDraftInWordsAndHasNoActionWithoutAHandler() {
        compose.setContent {
            MaterialTheme { PullRequestChip(pr(0, PullRequestChecksState.NONE, draft = true), onClick = null) }
        }
        val chip = compose.onNodeWithTag("pullRequestChip")
        chip.assertTextEquals("PR #123 · draft")
    }
}
