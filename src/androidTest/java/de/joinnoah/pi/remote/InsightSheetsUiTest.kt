package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InsightSheetsUiTest {
    @get:Rule val compose = createComposeRule()
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun zeroErrorsCanDisableSelectedFilter() {
        var toggleCount = 0
        compose.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf(true) }
                ErrorInsightSheet(0, selected, onToggle = { selected = !selected; toggleCount++ }, onDismiss = {})
            }
        }
        val filter = compose.onNodeWithTag("insightErrorFilter")
        filter.assertTextEquals(resources.getString(R.string.insight_errors_filter))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertEquals(1, toggleCount)
        filter.performClick().assertIsOn()
        assertEquals(2, toggleCount)
    }

    @Test
    fun filesActionDismissesAndOpensChanges() {
        var changes = false
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                TouchedFilesSheet(TouchedFiles(emptyList(), emptyList()), onDismiss = { dismissed = true }, onOpen = {}, onViewChanges = { changes = true })
            }
        }
        compose.onNodeWithText(resources.getString(R.string.insight_files_view_changes)).performClick()
        assertTrue(changes)
        assertTrue(dismissed)
    }

    @Test
    fun subagentSheetRespectsEligibilityAndKeepsAbortSeparate() {
        val live = SubagentStripEntry("child", "scout", "Scout", "running", "Reading", true)
        val unavailable = SubagentStripEntry(null, "planner", "Plan", "queued", null, false)
        var aborted: SubagentStripEntry? = null
        var opened: SubagentStripEntry? = null
        compose.setContent {
            MaterialTheme {
                SubagentInsightSheet(SubagentStrip(listOf(live, unavailable), 1, emptySet(), 0), { true }, { opened = it }, { aborted = it }, {})
            }
        }
        compose.onAllNodesWithText(resources.getString(R.string.remote_subagent_open_details)).assertCountEquals(1)
        compose.onNodeWithContentDescription(resources.getString(R.string.remote_panel_subagent_abort)).performClick()
        assertEquals(live, aborted)
        assertEquals(null, opened)
        compose.onNodeWithText(resources.getString(R.string.remote_subagent_open_details)).performClick()
        assertEquals(live, opened)
    }
}
