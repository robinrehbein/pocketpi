package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class SubagentHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun finishedAgentKeepsChipAndShowsResultAndDetailsWithoutStop() {
        val live = SubagentStripEntry("child", "scout", "Scout", "running", "reading", true)
        val current = mutableStateOf(SubagentStrip(listOf(live), 1, emptySet(), 0))
        val opened = mutableListOf<SubagentStripEntry>()
        compose.setContent {
            MaterialTheme {
                SubagentStrip(current.value, { true }, { opened += it }, {}, sessionId = "parent")
            }
        }
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_stop_label)).assertExists()
        val finished = live.copy(state = "succeeded", activity = "All checks passed", abortable = false)
        compose.runOnIdle { current.value = SubagentStrip(listOf(finished), 0, emptySet(), 0) }
        compose.onNodeWithTag("subagentStrip").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_succeeded)).assertExists()
        compose.onNodeWithText("All checks passed").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_stop_label)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_subagent_popover_close)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_popover_title)).assertExists()
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_open_details)).performClick()
        assertEquals(listOf(finished), opened)
        compose.onNodeWithTag("subagentPopover").assertDoesNotExist()
    }

    @Test
    fun queuedSummaryCountsOnlyQueuedAgentsNotFinishedHistory() {
        val entries = listOf(
            SubagentStripEntry(null, "queued", "Queued", "queued", null, false),
            SubagentStripEntry(null, "done", "Done", "succeeded", null, false),
            SubagentStripEntry(null, "failed", "Failed", "failed", null, false),
        )
        compose.setContent {
            MaterialTheme { SubagentStrip(SubagentStrip(entries, 0, emptySet(), 0), { true }, {}, {}) }
        }
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.remote_panel_subagents_queued, 1, 1)).assertExists()
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onAllNodesWithTag("subagentStripEntry").assertCountEquals(3)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_stop_label)).assertDoesNotExist()
    }

    @Test
    fun completedHistoryRemainsScrollableOnNarrowScreens() {
        val entries = (1..25).map { index ->
            SubagentStripEntry("child-$index", "scout $index", "Scout $index", "succeeded", "Result $index", false)
        }
        val opened = mutableListOf<SubagentStripEntry>()
        compose.setContent {
            MaterialTheme {
                SubagentStrip(SubagentStrip(entries, 0, emptySet(), 0), { true }, { opened += it }, {}, sessionId = "parent")
            }
        }
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("subagentPopover")))
            .performScrollToNode(hasText("Result 25"))
        compose.onNodeWithText("Result 25").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_subagent_stop_label)).assertDoesNotExist()
    }

    @Test
    fun switchingParentClosesHistoryAndShowsOnlyNewParentsEntries() {
        val parent = mutableStateOf("first")
        val current = mutableStateOf(SubagentStrip(listOf(
            SubagentStripEntry("old-child", "old scout", "Old", "succeeded", "Old result", false),
        ), 0, emptySet(), 0))
        compose.setContent {
            MaterialTheme { SubagentStrip(current.value, { true }, {}, {}, sessionId = parent.value) }
        }
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithText("Old result").assertExists()
        compose.runOnIdle {
            parent.value = "second"
            current.value = SubagentStrip(listOf(
                SubagentStripEntry("new-child", "new scout", "New", "failed", "New result", false),
            ), 0, emptySet(), 0)
        }
        compose.onNodeWithTag("subagentPopover").assertDoesNotExist()
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithText("New result").assertExists()
        compose.onNodeWithText("Old result").assertDoesNotExist()
    }
}
