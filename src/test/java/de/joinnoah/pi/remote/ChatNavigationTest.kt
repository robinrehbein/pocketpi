package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // ---- Maths ---------------------------------------------------------------------------

    @Test
    fun visibleSpanCountsPartlyVisibleItemsByTheirShare() {
        // Half of the first item is scrolled away, the last one sticks out by three quarters.
        assertEquals(1.75f, visibleItemSpan(listOf(-50, 50, 150), listOf(100, 100, 400), 250), 0.0001f)
        assertEquals(0f, visibleItemSpan(listOf(0), listOf(0), 100))
    }

    @Test
    fun thumbAndTicksShareTheIndexFractionModel() {
        val metrics = ScrollMetrics(position = 30f, visible = 10f, total = 100)
        val thumb = scrollbarThumb(metrics)!!
        assertEquals(0.3f, thumb.top, 0.0001f)
        assertEquals(0.1f, thumb.size, 0.0001f)
        // Items 30 to 39 are on screen: their ticks lie inside the thumb, their neighbours outside.
        for (index in 30..39) assertTrue(scrollbarTick(index, 100) in thumb.top..(thumb.top + thumb.size))
        assertFalse(scrollbarTick(29, 100) in thumb.top..(thumb.top + thumb.size))
        assertFalse(scrollbarTick(40, 100) in thumb.top..(thumb.top + thumb.size))
        assertEquals(0.005f, scrollbarTick(0, 100), 0.0001f)
        assertEquals(0.995f, scrollbarTick(99, 100), 0.0001f)
        // Past the end (a pending question) stays on the track.
        assertEquals(1f, scrollbarTick(150, 100))
    }

    @Test
    fun thumbKeepsAMinimumSizeAndReachesBothEnds() {
        val top = scrollbarThumb(ScrollMetrics(0f, 2f, 1000), minSize = 0.05f)!!
        assertEquals(ScrollbarThumb(0f, 0.05f), top)
        val bottom = scrollbarThumb(ScrollMetrics(998f, 2f, 1000), minSize = 0.05f)!!
        assertEquals(0.95f, bottom.top + 0f, 0.0001f)
        assertEquals(1f, bottom.top + bottom.size, 0.0001f)
        // Nothing to scroll, nothing to show.
        assertNull(scrollbarThumb(ScrollMetrics(0f, 5f, 5)))
        assertNull(scrollbarThumb(ScrollMetrics(0f, 0f, 0)))
    }

    @Test
    fun seekingCentresTheThumbOnTheFinger() {
        val metrics = ScrollMetrics(0f, 10f, 100)
        assertEquals(0f, scrollbarSeek(0f, 0.1f, metrics), 0.0001f)
        assertEquals(0f, scrollbarSeek(0.05f, 0.1f, metrics), 0.0001f)
        assertEquals(45f, scrollbarSeek(0.5f, 0.1f, metrics), 0.0001f)
        assertEquals(90f, scrollbarSeek(0.95f, 0.1f, metrics), 0.0001f)
        assertEquals(90f, scrollbarSeek(1f, 0.1f, metrics), 0.0001f)
        // Seeking lands where the thumb then sits under the finger.
        val thumb = scrollbarThumb(metrics.copy(position = scrollbarSeek(0.5f, 0.1f, metrics)))!!
        assertEquals(0.5f, thumb.top + thumb.size / 2, 0.0001f)
    }

    @Test
    fun crowdedTicksKeepTheMostImportantKindPerBucket() {
        fun tick(id: String, kind: TimelineMarkerKind, fraction: Float) = ScrollbarTick(TimelineMarker(id, kind, 0), fraction)
        val ticks =
            listOf(
                tick("u1", TimelineMarkerKind.USER, 0.01f),
                tick("e1", TimelineMarkerKind.EDIT, 0.02f),
                tick("u2", TimelineMarkerKind.USER, 0.5f),
                tick("u3", TimelineMarkerKind.USER, 0.51f),
                tick("x", TimelineMarkerKind.ERROR, 0.98f),
                tick("q", TimelineMarkerKind.QUESTION, 0.99f),
            )
        // Ten buckets: an edit beats a user message, the later of equals wins, an error beats a question.
        assertEquals(listOf("e1", "u3", "x"), thinTicks(ticks, 10).map { it.marker.itemId })
        // Enough room keeps every tick, early ones included.
        assertEquals(6, thinTicks(ticks, 1000).size)
        assertEquals(emptyList<ScrollbarTick>(), thinTicks(ticks, 0))
    }

    @Test
    fun aTapJumpsOnlyToATickWithinTheSlop() {
        val ticks = listOf(0.1f, 0.5f, 0.52f, 0.9f)
        assertEquals(2, nearestTick(ticks, 0.53f, 0.02f))
        assertEquals(0, nearestTick(ticks, 0.11f, 0.02f))
        assertNull(nearestTick(ticks, 0.7f, 0.02f))
        assertNull(nearestTick(emptyList(), 0.5f, 1f))
    }

    @Test
    fun barShowsOnScrollableListsWithMarkersOrLength() {
        assertFalse(chatScrollbarVisible(false, 10, 100))
        assertFalse(chatScrollbarVisible(true, 1, 10))
        assertTrue(chatScrollbarVisible(true, 2, 10))
        assertTrue(chatScrollbarVisible(true, 0, 30))
    }

    // ---- Scrollbar -----------------------------------------------------------------------

    private fun renderBar(markers: List<TimelineMarker>, jumps: MutableList<String>): () -> LazyListState {
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                Box(Modifier.height(400.dp)) {
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(200) { Text("row $it", Modifier.height(40.dp)) }
                    }
                    ChatScrollbar(list, markers, { it.index }, { jumps += it }, Modifier.align(Alignment.TopEnd))
                }
            }
        }
        return { list }
    }

    @Test
    fun scrollbarStaysInItsLaneAndOutOfTheAccessibilityTree() {
        renderBar(listOf(TimelineMarker("a", TimelineMarkerKind.USER, 0), TimelineMarker("b", TimelineMarkerKind.EDIT, 50)), mutableListOf())
        compose.onNodeWithTag("chatScrollbar")
            .assertWidthIsEqualTo(CHAT_SCROLLBAR_LANE)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.ScrollBy))
        assertTrue(compose.onNodeWithTag("chatScrollbar").fetchSemanticsNode().children.isEmpty())
    }

    @Test
    fun scrollbarJumpsToANearbyTickIgnoresFarTapsAndScrollsOnDrag() {
        val jumps = mutableListOf<String>()
        val list =
            renderBar(
                listOf(
                    TimelineMarker("first", TimelineMarkerKind.USER, 0),
                    TimelineMarker("middle", TimelineMarkerKind.ERROR, 100),
                    TimelineMarker("last", TimelineMarkerKind.QUESTION, 199),
                ),
                jumps,
            )
        val bar = compose.onNodeWithTag("chatScrollbar")
        bar.performTouchInput { click(Offset(centerX, height * 100.5f / 200)) }
        bar.performTouchInput { click(Offset(centerX, height * 0.8f)) }
        compose.waitForIdle()
        assertEquals(listOf("middle"), jumps)
        bar.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(list().firstVisibleItemIndex > 100)
    }

    // ---- History -------------------------------------------------------------------------

    private val entries =
        listOf(
            ChatHistoryEntry("u", TimelineMarkerKind.USER, "Build the tablet column", null),
            ChatHistoryEntry("q", TimelineMarkerKind.QUESTION, "Inspector at 1180 dp?"),
            ChatHistoryEntry("e", TimelineMarkerKind.EDIT, null),
        )

    @Test
    fun historyRowsShowTextOrTheirKindAndOpenTheirItem() {
        val opened = mutableListOf<String>()
        var closed = false
        compose.setContent {
            MaterialTheme { ChatHistoryPane(entries, onOpen = { opened += it }, onClose = { closed = true }) }
        }
        compose.onAllNodesWithTag("chatHistoryRow").assertCountEquals(3)
        compose.onNodeWithText("Build the tablet column").assertExists()
        val question = context.getString(R.string.remote_panel_marker_question)
        compose.onNodeWithText("$question: Inspector at 1180 dp?").performClick()
        compose.onNodeWithText(context.getString(R.string.remote_panel_marker_edit)).performClick()
        assertEquals(listOf("q", "e"), opened)
        // The pane stays open beside the chat; only its close button closes it.
        assertFalse(closed)
        compose.onNodeWithTag("chatHistoryClose").performClick()
        assertTrue(closed)
    }

    @Test
    fun emptyHistoryExplainsWhatAppearsThere() {
        compose.setContent { MaterialTheme { ChatHistoryPane(emptyList(), onOpen = {}, onClose = {}) } }
        compose.onNodeWithTag("chatHistoryEmpty").assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_chat_history_empty)).assertExists()
        compose.onNodeWithTag("chatHistoryList").assertDoesNotExist()
    }
}
