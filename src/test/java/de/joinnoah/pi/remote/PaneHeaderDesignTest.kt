package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun nestedLongTitleAndActionKeepTheirDistance() {
        assertNarrowHeader(nested = true, reload = false, actionCount = 1, buttons = listOf("jobsBack", "jobsClose", "action0"))
    }

    @Test fun longTitleAndReloadKeepTheirDistance() {
        assertNarrowHeader(nested = false, reload = true, actionCount = 0, buttons = listOf("jobsClose", "jobsReload"))
    }

    @Test fun longTitleReloadAndOverflowKeepTheirDistance() {
        assertNarrowHeader(nested = true, reload = true, actionCount = 5, buttons = listOf("jobsBack", "jobsClose", "jobsMoreActions", "jobsReload"))
        compose.onNodeWithTag("action0").assertDoesNotExist()
    }

    private fun assertNarrowHeader(nested: Boolean, reload: Boolean, actionCount: Int, buttons: List<String>) {
        val fontScale = mutableStateOf(1f)
        val actions = List(actionCount) { PaneAction("Action $it", Icons.Default.MoreVert, "action$it") }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).testTag("headerParent")) {
                        PaneHeader(
                            title = "Background job: " + "very-long-command-with-arguments-".repeat(20),
                            subtitle = null, nested = nested, busy = false, tag = "jobs",
                            backLabel = "Back", closeLabel = "Close", reloadLabel = "Refresh",
                            onBack = {}, onClose = {}, onReload = if (reload) ({}) else null, actions = actions,
                        )
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val parent = compose.onNodeWithTag("headerParent").getUnclippedBoundsInRoot()
            val title = compose.onNodeWithTag("jobsHeaderPill").getUnclippedBoundsInRoot()
            val trailing = compose.onNodeWithTag("jobsReloadPill").getUnclippedBoundsInRoot()
            val gap = trailing.left - title.right
            assertTrue("fontScale=$scale: pill gap was $gap", gap >= 11.5.dp)
            assertTrue("fontScale=$scale: trailing pill exceeds the 16dp gutter", trailing.right <= parent.right - 15.5.dp)
            assertTrue("fontScale=$scale: title pill outside parent", title.left >= parent.left && title.right > title.left)
            buttons.forEach { button ->
                compose.onNodeWithTag(button).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
        }
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
