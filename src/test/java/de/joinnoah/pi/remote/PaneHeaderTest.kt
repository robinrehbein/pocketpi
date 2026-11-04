package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp")
class PaneHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortTitlePillWrapsContentInsteadOfFillingAvailableWidth() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PaneHeader("Files", "Pi", false, false, "files", "Back", "Close", "Refresh", {}, {}, {})
                }
            }
        }
        val title = compose.onNodeWithTag("filesHeaderPill").getUnclippedBoundsInRoot()
        val reload = compose.onNodeWithTag("filesReloadPill").getUnclippedBoundsInRoot()
        assertTrue((title.right - title.left).value < 250f)
        assertTrue(reload.left.value - title.right.value > 100f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun longNestedSubtitleLeavesRoomForCloseAndRefresh() {
        compose.setContent {
            MaterialTheme {
                PaneHeader("Changes", "src/" + "long-path/".repeat(20), true, false, "changes", "Back", "Close", "Refresh", {}, {}, {})
            }
        }
        val pill = compose.onNodeWithTag("changesHeaderPill").getUnclippedBoundsInRoot()
        val reload = compose.onNodeWithTag("changesReloadPill").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithTag("changesSubtitle").getUnclippedBoundsInRoot()
        val close = compose.onNodeWithTag("changesClose").getUnclippedBoundsInRoot()
        assertTrue(pill.right < reload.left)
        assertTrue(subtitle.right <= close.left)
        compose.onNodeWithTag("changesBack").assertIsDisplayed()
        compose.onNodeWithTag("changesClose").assertIsDisplayed()
    }
}
