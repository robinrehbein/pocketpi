package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private val allKinds =
        listOf(
            TimelineMarker("e", TimelineMarkerKind.ERROR, 0f),
            TimelineMarker("q", TimelineMarkerKind.QUESTION, 0.3f),
            TimelineMarker("d", TimelineMarkerKind.EDIT, 0.6f),
            TimelineMarker("p", TimelineMarkerKind.PLAN, 1f),
        )

    private fun renderRail(markers: List<TimelineMarker>, onJump: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { TimelineRail(markers, onJump = onJump, modifier = Modifier.height(400.dp)) }
        }
    }

    @Test
    fun railNeedsAtLeastTwoMarkers() {
        assertFalse(timelineRailVisible(emptyList()))
        assertFalse(timelineRailVisible(allKinds.take(1)))
        assertTrue(timelineRailVisible(allKinds.take(2)))
    }

    @Test
    fun railIsHiddenWithASingleMarker() {
        renderRail(allKinds.take(1))
        compose.onNodeWithTag("timelineRail").assertDoesNotExist()
    }

    @Test
    fun railMarkersHaveDistinctContentDescriptionsPerKind() {
        val jumps = mutableListOf<String>()
        renderRail(allKinds) { jumps += it }
        compose.onNodeWithTag("timelineRail").assertExists()
        val markers = compose.onAllNodesWithTag("timelineMarker")
        markers.assertCountEquals(4)
        val descriptions =
            markers.fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }
        val expected =
            listOf(
                R.string.remote_panel_marker_error,
                R.string.remote_panel_marker_question,
                R.string.remote_panel_marker_edit,
                R.string.remote_panel_marker_plan,
            ).map(context::getString)
        assertEquals(expected, descriptions)
        assertEquals(4, descriptions.toSet().size)
        // TalkBack can jump straight to a marker.
        compose.onNodeWithContentDescription(expected[2]).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("d"), jumps)
    }

    @Test
    fun longPressOnTheRailShowsTheLegend() {
        renderRail(allKinds)
        compose.onNodeWithTag("timelineLegend").assertDoesNotExist()
        compose.onNodeWithTag("timelineRail").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("timelineLegend").assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_panel_timeline_legend)).assertExists()
        for (label in
            listOf(
                R.string.remote_panel_marker_error,
                R.string.remote_panel_marker_question,
                R.string.remote_panel_marker_edit,
                R.string.remote_panel_marker_plan,
            ))
            compose.onNodeWithText(context.getString(label)).assertExists()
        compose.onNodeWithTag("timelineLegend").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("timelineLegend").assertDoesNotExist()
    }

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
        summary.assertTextContains(context.resources.getQuantityString(R.plurals.remote_panel_files_count, 3, 3))
        summary.assertTextContains("+12")
        summary.assertTextContains("−4")
        val description =
            listOf(
                context.resources.getQuantityString(R.plurals.remote_panel_files_touched, 3, 3),
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
        assertEquals(listOf(context.resources.getQuantityString(R.plurals.remote_panel_files_count, 3, 3)), texts)
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
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("subagentStrip"))).performClick()
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
