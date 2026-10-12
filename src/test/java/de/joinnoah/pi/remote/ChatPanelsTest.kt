package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatPanelsTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val files =
        TouchedFiles(
            changed = listOf(TouchedFile("a.kt", 1, "1"), TouchedFile("b.kt", 2, "2")),
            read = listOf(TouchedFile("c.kt", 1, "3")),
        )

    @Test
    fun touchedFilesSummaryShowsCountAndLineDelta() {
        var taps = 0
        compose.setContent {
            MaterialTheme { TouchedFilesSummary(files, TouchedLineCounts(12, 4)) { taps++ } }
        }
        val summary = compose.onNodeWithTag("touchedFilesSummary")
        summary.assertTextContains(context.resources.getQuantityString(R.plurals.remote_panel_files_changed, 2, 2))
        summary.assertTextContains("+12")
        summary.assertTextContains("−4")
        val description =
            listOf(
                context.resources.getQuantityString(R.plurals.remote_panel_files_changed_description, 2, 2),
                context.resources.getQuantityString(R.plurals.remote_panel_files_read_description, 1, 1),
                context.resources.getQuantityString(R.plurals.remote_panel_lines_added, 12, 12),
                context.resources.getQuantityString(R.plurals.remote_panel_lines_removed, 4, 4),
            ).joinToString(", ")
        compose.onNodeWithContentDescription(description).assertExists()
        summary.performClick()
        assertEquals(1, taps)
    }

    @Test
    fun touchedFilesSummaryWithoutDiffsShowsTheCountAlone() {
        compose.setContent { MaterialTheme { TouchedFilesSummary(files, lines = null) {} } }
        val texts =
            compose.onNodeWithTag("touchedFilesSummary").fetchSemanticsNode().config[SemanticsProperties.Text]
                .map { it.text }
        assertEquals(listOf(context.resources.getQuantityString(R.plurals.remote_panel_files_changed, 2, 2)), texts)
    }

    @Test
    fun touchedFilesSummaryOfAReadOnlyChatSaysRead() {
        val readOnly = TouchedFiles(changed = emptyList(), read = files.changed + files.read)
        compose.setContent { MaterialTheme { TouchedFilesSummary(readOnly, lines = null) {} } }
        val texts =
            compose.onNodeWithTag("touchedFilesSummary").fetchSemanticsNode().config[SemanticsProperties.Text]
                .map { it.text }
        assertEquals(listOf(context.resources.getQuantityString(R.plurals.remote_panel_files_read, 3, 3)), texts)
        compose
            .onNodeWithContentDescription(
                context.resources.getQuantityString(R.plurals.remote_panel_files_read_description, 3, 3)
            )
            .assertExists()
    }

    @Test
    fun touchedFilesSummaryIsHiddenWithoutFiles() {
        compose.setContent {
            MaterialTheme { TouchedFilesSummary(TouchedFiles(emptyList(), emptyList()), TouchedLineCounts(1, 1)) {} }
        }
        compose.onNodeWithTag("touchedFilesSummary").assertDoesNotExist()
    }

    @Test
    fun subagentStripRowsMeetTheMinimumTouchTarget() {
        val strip =
            SubagentStrip(
                entries =
                    listOf(
                        SubagentStripEntry("s1", "explorer", "explorer task", "running", "reading files", true),
                        SubagentStripEntry(null, "reviewer", "short", "queued", null, false, openable = false),
                    ),
                running = 1,
                missingSessionIds = emptySet(),
                unmatchedRunning = 0,
            )
        compose.setContent {
            MaterialTheme { SubagentStrip(strip, canAbort = { false }, onOpen = {}, onAbort = {}) }
        }
        // Rows are collapsed until the header is expanded.
        compose.onNodeWithText("explorer task").assertDoesNotExist()
        compose.onNodeWithTag("subagentStrip").performClick()
        val rows = compose.onAllNodesWithTag("subagentStripEntry")
        rows.assertCountEquals(2)
        rows[0].assertHeightIsAtLeast(48.dp)
        rows[1].assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun compactionBannerIsAPoliteLiveRegion() {
        compose.setContent {
            MaterialTheme { CompactionBanner(SessionCompaction("threshold", 0L), nowMillis = 0L) }
        }
        val liveRegion =
            compose.onNodeWithTag("compactionBanner").fetchSemanticsNode().config[SemanticsProperties.LiveRegion]
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, liveRegion)
    }
}
