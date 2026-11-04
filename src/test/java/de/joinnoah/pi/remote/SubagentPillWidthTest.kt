package de.joinnoah.pi.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubagentPillWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun summaryKeepsWidthInsideHorizontallyScrollingInsights() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(320.dp).horizontalScroll(rememberScrollState())) {
                    StatusPopover(
                        summary = "1 Subagent läuft",
                        icon = Icons.Outlined.SmartToy,
                        expanded = false,
                        onExpandedChange = {},
                        expandDescription = "Expand",
                        collapseDescription = "Collapse",
                        title = "Subagents",
                        closeDescription = "Close",
                        chipTag = "subagentStrip",
                        popoverTag = "subagentPopover",
                    ) {}
                }
            }
        }
        val bounds = compose.onNodeWithText("1 Subagent läuft", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The summary must not collapse to zero width", bounds.width > 0f)
    }
}
