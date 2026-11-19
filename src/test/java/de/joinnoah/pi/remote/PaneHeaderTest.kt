package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private val longTitle = "Background job: " + "very-long-command-with-arguments-".repeat(10)

    private fun showCompact(subtitle: String) = compose.setContent {
        MaterialTheme {
            PaneHeader(longTitle, subtitle, false, false, "terminal", "Back", "Close", "Refresh", {}, {}, {}, compact = true)
        }
    }

    @Test fun compactLongTitleTakesTheRoomAShortSubtitleLeaves() {
        showCompact("Pi")
        val pill = compose.onNodeWithTag("terminalHeaderPill").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("terminalTitle").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithTag("terminalSubtitle").getUnclippedBoundsInRoot()
        // 600 - 32 gutter - (88 + 48 reload + 12 gap) leaves the line 420dp; 60% would be 252dp.
        assertTrue("title ${title.right - title.left}", (title.right - title.left).value > 330f)
        assertTrue("the short subtitle is whole", (subtitle.right - subtitle.left).value < 40f)
        assertTrue(subtitle.right <= pill.right)
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals("Pi")
        compose.onNodeWithTag("terminalHeaderPill").assertHeightIsEqualTo(48.dp)
    }

    @Test fun compactLongTitleAndLongSubtitleShareTheLine() {
        val longSubtitle = "Ctrl Alt Shift " + "modifier ".repeat(20)
        showCompact(longSubtitle)
        val pill = compose.onNodeWithTag("terminalHeaderPill").getUnclippedBoundsInRoot()
        val reload = compose.onNodeWithTag("terminalReloadPill").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("terminalTitle").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithTag("terminalSubtitle").getUnclippedBoundsInRoot()
        val titleWidth = (title.right - title.left).value
        val subtitleWidth = (subtitle.right - subtitle.left).value
        assertTrue("title $titleWidth", titleWidth in 230f..260f)
        assertTrue("subtitle $subtitleWidth", subtitleWidth in 150f..175f)
        assertTrue(subtitle.right <= pill.right && pill.right < reload.left)
        // The cut-off subtitle still says all of it.
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals(longSubtitle)
        compose.onNodeWithTag("terminalHeaderPill").assertHeightIsEqualTo(48.dp)
    }

    @Test fun compactTitleWidthsGiveTheRestToWhoNeedsIt() {
        // Both fit: each what it needs.
        assertEquals(100 to 50, compactTitleWidths(400, 100, 50, 48))
        // A short subtitle keeps its width; the title gets all the rest.
        assertEquals(370 to 30, compactTitleWidths(400, 1000, 30, 48))
        // A short title keeps its width; the subtitle gets all the rest.
        assertEquals(80 to 320, compactTitleWidths(400, 80, 1000, 48))
        // Two long ones: 60:40.
        assertEquals(240 to 160, compactTitleWidths(400, 1000, 1000, 48))
        // The title never goes below its minimum, also on a tiny line.
        assertEquals(48 to 0, compactTitleWidths(48, 1000, 1000, 48))
        assertEquals(48 to 50, compactTitleWidths(400, 10, 50, 48))
        // The title floor counts toward the fit, so the pair never exceeds the line.
        assertEquals(48 to 12, compactTitleWidths(60, 10, 50, 48))
    }
}
