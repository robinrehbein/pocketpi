package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
class ViewerHeaderDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineHeaderHasMinimumHeightAndHeading() {
        val state = ArtifactViewerState("source").apply { showCode = true }
        compose.setContent {
            MaterialTheme {
                ArtifactViewerBody(state, ArtifactKind.Html, "path", "Title", reload = null)
            }
        }
        compose.onNodeWithTag("artifactViewerHeaderPill").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("artifactViewerTitle", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun longTitleKeepsViewerActionsVisibleAndWorking() {
        var closed = 0
        var poppedOut = 0
        var reloaded = 0
        val state = ArtifactViewerState("source").apply { showCode = true }
        compose.setContent {
            MaterialTheme {
                ArtifactViewerBody(
                    state = state,
                    kind = ArtifactKind.Html,
                    path = "src/" + "long-path/".repeat(30),
                    title = "Long title ".repeat(30),
                    reload = { "updated" },
                    onClose = { closed++ },
                    onPopOut = { poppedOut++ },
                    onReloaded = { reloaded++ },
                )
            }
        }
        val close = compose.onNodeWithTag("artifactViewerClose")
        val reload = compose.onNodeWithTag("artifactViewerReload")
        val share = compose.onNodeWithTag("artifactViewerShare")
        val popOut = compose.onNodeWithTag("artifactPopOut")
        close.assertIsDisplayed()
        reload.assertIsDisplayed()
        share.assertIsDisplayed()
        popOut.assertIsDisplayed()
        assertTrue(close.getUnclippedBoundsInRoot().right <= reload.getUnclippedBoundsInRoot().left)
        close.performClick()
        popOut.performClick()
        reload.performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(1, poppedOut)
        assertEquals(1, reloaded)
        assertEquals("updated", state.text)
    }
}
