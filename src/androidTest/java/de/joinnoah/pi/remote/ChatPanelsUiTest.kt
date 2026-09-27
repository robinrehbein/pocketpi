package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatPanelsUiTest {
    @get:Rule val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun touchedFilesChipShowsCountAndRowTapReportsLatestItem() {
        val files =
            TouchedFiles(
                changed = listOf(TouchedFile("src/main/App.kt", 2, "edit-2"), TouchedFile("README.md", 1, "write-1")),
                read = listOf(TouchedFile("docs/guide/setup.md", 3, "read-7")),
            )
        var opened: String? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                TouchedFilesChip(files) { open = true }
                if (open)
                    TouchedFilesSheet(
                        files,
                        onDismiss = {
                            dismissed = true
                            open = false
                        },
                        onOpen = { opened = it },
                    )
            }
        }
        val description = resources.getQuantityString(R.plurals.remote_panel_files_touched, 3, 3)
        compose.onNodeWithTag("touchedFilesChip").assertIsDisplayed().assertTextContains("3")
        compose.onNodeWithContentDescription(description).assertExists()
        compose.onNodeWithTag("touchedFilesChip").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("touchedFilesSheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("touchedFileRow").assertCountEquals(3)
        compose.onNodeWithText("setup.md").performClick()
        compose.waitForIdle()
        assertEquals("read-7", opened)
        assertTrue(dismissed)
        compose.onNodeWithTag("touchedFilesSheet").assertDoesNotExist()
    }

    @Test
    fun touchedFilesChipIsHiddenWithoutFiles() {
        compose.setContent { MaterialTheme { TouchedFilesChip(TouchedFiles(emptyList(), emptyList())) {} } }
        compose.onNodeWithTag("touchedFilesChip").assertDoesNotExist()
    }

    @Test
    fun subagentStripShowsPluralAndAbortOnlyWhenAbortable() {
        val live = SubagentStripEntry("child-1", "scout", "Scout the repo", "running", "reading files", abortable = true)
        val pending = SubagentStripEntry(null, "planner", "Plan it", "running", null, abortable = false)
        val strip = SubagentStrip(listOf(live, pending), running = 2, missingSessionIds = emptySet(), unmatchedRunning = 1)
        val aborted = mutableListOf<SubagentStripEntry>()
        val openedEntries = mutableListOf<SubagentStripEntry>()
        compose.setContent {
            MaterialTheme {
                SubagentStrip(strip, canAbort = { true }, onOpen = { openedEntries += it }, onAbort = { aborted += it })
            }
        }
        val summary = resources.getQuantityString(R.plurals.remote_panel_subagents_running, 2, 2)
        compose.onNodeWithTag("subagentStrip").assertIsDisplayed()
        compose.onNodeWithText(summary).assertIsDisplayed().performClick()
        compose.onAllNodesWithTag("subagentStripEntry").assertCountEquals(2)
        val abort = resources.getString(R.string.remote_panel_subagent_abort)
        compose.onAllNodesWithContentDescription(abort).assertCountEquals(1)
        compose.onNodeWithContentDescription(abort).performClick()
        assertEquals(listOf(live), aborted)
        compose.onNodeWithText("scout").performClick()
        assertEquals(listOf(live), openedEntries)
        compose.onNodeWithText("planner").performClick()
        assertEquals(listOf(live), openedEntries)
    }

    @Test
    fun subagentStripHidesAbortWhenNotAllowed() {
        val live = SubagentStripEntry("child-1", "scout", "Scout", "running", null, abortable = true)
        compose.setContent {
            MaterialTheme {
                SubagentStrip(
                    SubagentStrip(listOf(live), 1, emptySet(), 0),
                    canAbort = { false },
                    onOpen = {},
                    onAbort = {},
                )
            }
        }
        compose.onNodeWithText(resources.getQuantityString(R.plurals.remote_panel_subagents_running, 1, 1))
            .performClick()
        compose.onNodeWithContentDescription(resources.getString(R.string.remote_panel_subagent_abort))
            .assertDoesNotExist()
    }

    @Test
    fun timelineRailJumpsToNearestMarker() {
        val markers =
            listOf(
                TimelineMarker("first", TimelineMarkerKind.EDIT, 0f),
                TimelineMarker("middle", TimelineMarkerKind.ERROR, 0.5f),
                TimelineMarker("last", TimelineMarkerKind.QUESTION, 1f),
            )
        val jumps = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { TimelineRail(markers, onJump = { jumps += it }, modifier = Modifier.height(400.dp)) }
        }
        val rail = compose.onNodeWithTag("timelineRail").assertIsDisplayed()
        rail.performTouchInput { click(Offset(centerX, height * 0.55f)) }
        rail.performTouchInput { click(Offset(centerX, height - 1f)) }
        rail.performTouchInput { click(Offset(centerX, 1f)) }
        compose.waitForIdle()
        assertEquals(listOf("middle", "last", "first"), jumps)
    }

    @Test
    fun timelineRailLetsDragsScrollTheListAndIgnoresFarTaps() {
        val markers = listOf(TimelineMarker("only", TimelineMarkerKind.EDIT, 0f))
        val jumps = mutableListOf<String>()
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                Box(Modifier.height(400.dp)) {
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(200) { Text("row $it", Modifier.height(40.dp)) }
                    }
                    TimelineRail(
                        markers,
                        onJump = { jumps += it },
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                        scrollState = list,
                    )
                }
            }
        }
        val rail = compose.onNodeWithTag("timelineRail").assertIsDisplayed()
        rail.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(list.firstVisibleItemIndex > 0)
        rail.performTouchInput { click(Offset(centerX, height * 0.8f)) }
        compose.waitForIdle()
        assertTrue(jumps.isEmpty())
    }

    @Test
    fun timelineRailIsHiddenWithoutMarkers() {
        compose.setContent { MaterialTheme { TimelineRail(emptyList(), onJump = {}) } }
        compose.onNodeWithTag("timelineRail").assertDoesNotExist()
    }

    @Test
    fun compactionBannerIsHiddenWhenStale() {
        val now = 100_000_000L
        var compaction by mutableStateOf<SessionCompaction?>(SessionCompaction("threshold", now - 1_000))
        compose.setContent { MaterialTheme { CompactionBanner(compaction, now) } }
        compose.onNodeWithTag("compactionBanner").assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.remote_panel_compaction_threshold)).assertIsDisplayed()
        compaction = SessionCompaction("threshold", now - 11 * 60 * 1000L)
        compose.waitForIdle()
        compose.onNodeWithTag("compactionBanner").assertDoesNotExist()
        compaction = null
        compose.waitForIdle()
        compose.onNodeWithTag("compactionBanner").assertDoesNotExist()
    }

    @Test
    fun errorFilterChipHidesAtZeroUnlessSelected() {
        var count by mutableStateOf(0)
        var selected by mutableStateOf(false)
        compose.setContent { MaterialTheme { ErrorFilterChip(count, selected) { selected = !selected } } }
        compose.onNodeWithTag("errorFilterChip").assertDoesNotExist()
        count = 2
        compose.onNodeWithText(resources.getString(R.string.remote_panel_errors_filter, 2)).assertIsDisplayed()
        compose.onNodeWithTag("errorFilterChip").performClick()
        compose.onNodeWithTag("errorFilterChip").assertIsSelected()
        count = 0
        compose.onNodeWithTag("errorFilterChip").assertIsDisplayed()
    }
}
