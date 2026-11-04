package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
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

    @Test
    fun subagentStatesMapToDistinctLabelsAndGlyphs() {
        assertEquals(R.string.remote_status_waiting, subagentStateLabel("waiting"))
        assertEquals(StatusGlyphKind.NEEDS_INPUT, subagentStateGlyph("waiting"))
        assertEquals(StatusGlyphKind.WAITING, subagentStateGlyph("queued"))
        assertEquals(StatusGlyphKind.DONE, subagentStateGlyph("succeeded"))
        assertEquals(StatusGlyphKind.FAILED, subagentStateGlyph("failed"))
        assertEquals(StatusGlyphKind.STOPPED, subagentStateGlyph("cancelled"))
        assertEquals(R.string.remote_subagent_cancelled, subagentStateLabel("cancelled"))
        assertEquals(null, subagentStateGlyph("ended"))
        // Unknown states are neutral, never claimed as cancelled or successful.
        assertEquals(R.string.remote_subagent_ended, subagentStateLabel("mystery"))
        assertEquals(null, subagentStateGlyph("mystery"))
    }

    @Test
    fun waitingSubagentKeepsChipVisibleWithNeedsInputLabelAndGlyph() {
        val strip = SubagentStrip(
            listOf(SubagentStripEntry("c", "scout", "Scout", "waiting", null, true)), 0, emptySet(), 0,
        )
        compose.setContent { MaterialTheme { SubagentStrip(strip, { false }, {}, {}) } }
        val needsYou = ApplicationProvider.getApplicationContext<Context>().getString(R.string.remote_status_waiting)
        compose.onNodeWithTag("subagentStrip").assertIsDisplayed()
        compose.onNodeWithText(needsYou).assertExists()
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithTag("subagentGlyph_NEEDS_INPUT", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("subagentGlyph_SESSION_RUNNING", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun entry(id: String, state: String, lastKnown: Boolean = false) =
        SubagentStripEntry(id, id, id, state, null, abortable = !lastKnown && state in setOf("running", "waiting"), lastKnown = lastKnown)

    @Test
    fun waitingLeadsTheChipAndKeepsTheRunningCount() {
        val strip = SubagentStrip(listOf(entry("w", "waiting"), entry("a", "running"), entry("b", "running")), 2, emptySet(), 0)
        compose.setContent { MaterialTheme { SubagentStrip(strip, { false }, {}, {}) } }
        val expected = context.getString(R.string.remote_status_waiting) + " · " +
            context.resources.getQuantityString(R.plurals.remote_panel_subagents_running, 2, 2)
        compose.onNodeWithTag("subagentStrip").assertTextContains(expected)
    }

    @Test
    fun cachedWaitingDoesNotClaimACurrentRequestForInput() {
        val strip = SubagentStrip(listOf(entry("w", "waiting", lastKnown = true), entry("a", "running")), 1, emptySet(), 0)
        compose.setContent { MaterialTheme { SubagentStrip(strip, { true }, {}, {}) } }
        val running = context.resources.getQuantityString(R.plurals.remote_panel_subagents_running, 1, 1)
        val needsYou = context.getString(R.string.remote_status_waiting)
        compose.onNodeWithTag("subagentStrip").assertTextContains(running)
        compose.onNodeWithText(needsYou).assertDoesNotExist()
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithText(context.getString(R.string.remote_status_last_known, needsYou)).assertExists()
        compose.onNodeWithTag("subagentGlyph_NEEDS_INPUT", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun onlyCachedStatesAreLabelledLastKnownOnTheChip() {
        val strip = SubagentStrip(listOf(entry("a", "running", lastKnown = true)), 0, emptySet(), 0)
        compose.setContent { MaterialTheme { SubagentStrip(strip, { true }, {}, {}) } }
        val running = context.resources.getQuantityString(R.plurals.remote_panel_subagents_running, 1, 1)
        compose.onNodeWithTag("subagentStrip").assertTextContains(context.getString(R.string.remote_status_last_known, running))
        compose.onNodeWithTag("subagentStrip").performClick()
        compose.onNodeWithTag("subagentGlyph_SESSION_RUNNING", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun cachedQueuedChipSaysLastKnownAndYieldsToCurrentAgents() {
        val queued = context.resources.getQuantityString(R.plurals.remote_panel_subagents_queued, 1, 1)
        val cachedQueued = context.getString(R.string.remote_status_last_known, queued)
        val orphan = SubagentStrip(listOf(entry("q", "queued", lastKnown = true)), 0, emptySet(), 0)
        assertTrue(subagentChipShown(orphan, expanded = false))
        val counts = subagentChipCounts(orphan)
        assertEquals(0, counts.queued)
        assertEquals(1, counts.cachedQueued)
        compose.setContent { MaterialTheme { SubagentStrip(orphan, { false }, {}, {}) } }
        compose.onNodeWithTag("subagentStrip").assertTextContains(cachedQueued)
        compose.onNodeWithText(context.getString(R.string.remote_subagent_popover_title)).assertDoesNotExist()
        compose.onNodeWithText(queued).assertDoesNotExist()
    }

    @Test
    fun currentRunningLeadsOverCachedQueued() {
        val strip = SubagentStrip(listOf(entry("a", "running"), entry("q", "queued", lastKnown = true)), 1, emptySet(), 0)
        compose.setContent { MaterialTheme { SubagentStrip(strip, { false }, {}, {}) } }
        val running = context.resources.getQuantityString(R.plurals.remote_panel_subagents_running, 1, 1)
        val queued = context.resources.getQuantityString(R.plurals.remote_panel_subagents_queued, 1, 1)
        compose.onNodeWithTag("subagentStrip").assertTextContains(running)
        compose.onNodeWithText(queued).assertDoesNotExist()
    }

    @Test
    fun childPickerUsesTheSessionListGlyphRule() {
        val base = SessionListItem("w", "Waiting", "p", 0, false, SessionAvailability.WAITING, null, null, false, false, false)
        compose.setContent {
            MaterialTheme {
                ChildSessionPickerSheet(listOf(base, base.copy(id = "c", title = "Cached", lastKnown = true)), {}, {})
            }
        }
        compose.onAllNodesWithTag("candidateGlyph_NEEDS_INPUT", useUnmergedTree = true).assertCountEquals(1)
        val needsYou = context.getString(R.string.remote_status_waiting)
        compose.onNodeWithText(context.getString(R.string.remote_status_last_known, needsYou), substring = true).assertExists()
    }

    /**
     * The dimmed compaction glyph keeps 3:1 (non-text contrast) on the opaque containers it sits on in
     * the built-in schemes: the banner's secondary container and the chip's surface container. Glass
     * is translucent, so its contrast depends on the content behind it and is not asserted here.
     */
    @Test
    fun compactionGlyphKeepsNonTextContrastOnOpaqueContainers() {
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
            val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (light + 0.05f) / (dark + 0.05f)
        }
        for (dark in listOf(false, true)) {
            for (synthwave in listOf(false, true)) {
                val scheme = remoteColorScheme(dark, synthwave)
                val pairs =
                    listOf(
                        scheme.secondaryContainer to scheme.onSecondaryContainer,
                        scheme.surfaceContainer to scheme.onSurface,
                    )
                for ((container, content) in pairs) {
                    val glyph = content.copy(alpha = COMPACTION_GLYPH_ALPHA).compositeOver(container)
                    val ratio = contrast(glyph, container)
                    assertTrue("dark=$dark synthwave=$synthwave container=$container ratio=$ratio", ratio >= 3f)
                }
            }
        }
    }
}
