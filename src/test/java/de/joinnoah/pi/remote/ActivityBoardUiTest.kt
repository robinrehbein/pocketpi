package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityBoardUiTest {
    @get:Rule val compose = createComposeRule()

    private fun card(id: String, status: String, unread: Boolean = false, pinned: Boolean = false) =
        ActivityCard("host", "project", "Pocket Mac", id, "Title $id", "Preview $id", status, 1L, pinned, unread)

    private fun render(cards: List<ActivityCard>, onOpen: (ActivityCard) -> Unit = {}, wide: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                // Tall enough that the lazy list composes every group.
                Box(Modifier.requiredSize(400.dp, 2000.dp)) {
                    ActivityBoardContent(
                        groups = activityBoard(cards),
                        lastKnown = false,
                        wide = wide,
                        onBack = {},
                        onOpen = onOpen,
                    )
                }
            }
        }
    }

    @Test
    fun theBoardGroupsWaitingWorkingAndFinishedSessions() {
        render(
            listOf(
                card("waiting", "waiting"),
                card("running", "running"),
                card("finished", "idle", unread = true),
            ),
        )

        // Each group heading also names its cards' status, so the headings are checked by existence.
        assertTrue(compose.onAllNodesWithText("Needs you").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Working").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Finished since last look").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Title waiting").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Title finished").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun tappingACardOpensItsSession() {
        val opened = mutableListOf<String>()
        render(listOf(card("running", "running")), onOpen = { opened += it.sessionId })

        compose.onNodeWithContentDescription("Pocket Mac, Title running, Preview running, Working", substring = true)
            .performClick()

        assertEquals(listOf("running"), opened)
    }

    @Test
    fun aPinnedCardIsDescribedAsPinned() {
        render(listOf(card("pinned", "running", pinned = true)))

        // The card merges its text into one description, which names the pin.
        assertTrue(compose.onAllNodesWithContentDescription("Pinned", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theStripButtonBadgesTheSessionsThatWaitAndNamesThemForAccessibility() {
        var opened = 0
        compose.setContent { MaterialTheme { ActivityStripButton(needsYou = 2) { opened++ } } }

        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithContentDescription("Activity, 2", substring = true).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theStripButtonShowsNoBadgeWhenNothingWaits() {
        compose.setContent { MaterialTheme { ActivityStripButton(needsYou = 0) {} } }

        assertTrue(compose.onAllNodesWithText("0").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Activity").assertExists()
    }
}
