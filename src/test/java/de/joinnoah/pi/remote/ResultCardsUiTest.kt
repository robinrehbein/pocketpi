package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultCardsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardsSwitchToSearchableRawOutputAndBack() {
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            ToolDetailScreen(
                item = ConversationItem.Activity(id = "t", sourceId = "m", name = "bash",
                    arguments = """{"command":"./check-plan.sh"}""",
                    output = "--- 2026-10-05T17:57:52Z id\nPlan approved: safe", state = "complete", truncated = false),
                download = null, canAskToFix = false, canLoadFullOutput = false,
                onLoadFullOutput = {}, onCancelFullOutput = {}, onAskToFix = {}, onQuote = {}, onClose = {})
        } } }
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasTestTag("toolResultCard"))
        compose.onNodeWithTag("toolResultCard").assertExists()
        compose.onNodeWithTag("toolDetailRawToggle").performScrollTo().performClick()
        compose.onNodeWithTag("toolResultCard").assertDoesNotExist()
        compose.onNodeWithTag("toolDetailRawToggle").performScrollTo().performClick()
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasTestTag("toolResultCard"))
        compose.onNodeWithTag("toolResultCard").assertExists()
        compose.onNodeWithTag("toolDetailSearch").performTextInput("approved")
        compose.onNodeWithTag("toolResultCard").assertDoesNotExist()
        compose.onNodeWithTag("toolDetailSearch").performTextClearance()
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasTestTag("toolResultCard"))
        compose.onNodeWithTag("toolResultCard").assertExists()
    }

    @Test fun anchoredPopoverKeepsSeparateOpenAndStopActions() {
        var opened = 0
        var stopped = 0
        val entry = SubagentStripEntry("s1", "implementer", "task", "running", "running tests", true)
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            SubagentStrip(SubagentStrip(listOf(entry), 1, emptySet(), 0), canAbort = { true },
                onOpen = { opened++ }, onAbort = { stopped++ })
        } } }
        val pill = compose.onNodeWithTag("subagentStrip")
        pill.performClick()
        val panel = compose.onNodeWithTag("subagentPopover").fetchSemanticsNode().boundsInWindow
        assertTrue(panel.bottom < pill.fetchSemanticsNode().boundsInWindow.top)
        compose.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
        assertEquals(0, opened)
        compose.onNodeWithText("Open details").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("subagentPopover").assertDoesNotExist()
    }
}
