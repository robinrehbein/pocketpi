package de.joinnoah.pi.remote

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
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
@Config(sdk = [35], qualifiers = "w600dp-h800dp")
class PaneHeaderDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedCloseAndBackRespectLeaveEnabledAndTitleIsHeading() {
        val enabled = mutableStateOf(false)
        var closed = 0
        compose.setContent {
            MaterialTheme {
                PaneHeader(
                    "Files", null, true, false, "files", "Back", "Close", "Refresh",
                    onBack = {}, onClose = { closed++ }, onReload = null, leaveEnabled = enabled.value,
                )
            }
        }
        compose.onNodeWithTag("filesTitle").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("filesBack").assertIsNotEnabled()
        compose.onNodeWithTag("filesClose").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("filesClose").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun inlineMenuActionRunsAndDismisses() {
        var selected = 0
        val actions = listOf(PaneAction("Options", Icons.Default.MoreVert, "options", items = listOf(
            PaneMenuItem("Choose", "choose") { selected++ },
        )))
        compose.setContent {
            MaterialTheme {
                PaneHeader("Files", null, false, false, "files", "Back", "Close", "Refresh",
                    onBack = {}, onClose = {}, onReload = null, actions = actions)
            }
        }
        compose.onNodeWithTag("options").performClick()
        compose.onNodeWithTag("choose").performClick()
        compose.onNodeWithTag("choose").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, selected) }
    }
}
