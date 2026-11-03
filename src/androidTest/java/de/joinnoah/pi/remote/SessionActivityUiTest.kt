package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionActivityUiTest {
    @get:Rule val compose = createComposeRule()

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getString(id)

    private val working get() = string(R.string.remote_chat_activity_working)

    @Test
    fun activeTaskLabelShowsAndIdleOrOfflineLineHides() {
        var line by mutableStateOf<SessionActivityLine?>(
            SessionActivityLine(R.string.remote_chat_activity_working, "Running first"),
        )
        compose.setContent { MaterialTheme { ChatCurrentActivity(line) } }
        compose.onNodeWithTag("chatCurrentActivity").assertIsDisplayed()
        compose.onNodeWithText("$working · Running first").assertExists()
        compose.runOnIdle { line = null }
        compose.onNodeWithTag("chatCurrentActivity").assertDoesNotExist()
        compose.onNodeWithText("$working · Running first").assertDoesNotExist()
    }

    @Test
    fun toolLabelReplacesTheTaskLineWhenTheContextSwitches() {
        var line by mutableStateOf<SessionActivityLine?>(SessionActivityLine(R.string.remote_chat_activity_working, "Running first"))
        compose.setContent { MaterialTheme { ChatCurrentActivity(line) } }
        compose.onNodeWithText("$working · Running first").assertExists()
        compose.runOnIdle { line = SessionActivityLine(R.string.remote_tool_read, "src/Main.kt") }
        compose.onNodeWithText("$working · Running first").assertDoesNotExist()
        compose.onNodeWithText("${string(R.string.remote_tool_read)} · src/Main.kt").assertExists()
    }

    @Test
    fun longActivityTextRemainsOneLineAndAccessible() {
        val longTask = "A very long running description ".repeat(30)
        compose.setContent { MaterialTheme { ChatCurrentActivity(SessionActivityLine(R.string.remote_chat_activity_working, longTask)) } }
        compose.onNodeWithTag("activityMarquee").assertTextEquals("$working · $longTask")
    }

    @Test
    fun overflowMovesTowardEndInLtr() = checkDirection(LayoutDirection.Ltr)

    @Test
    fun overflowMovesTowardEndInRtl() = checkDirection(LayoutDirection.Rtl)

    private fun checkDirection(direction: LayoutDirection) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme { ActivityMarquee("A long activity description ".repeat(12), Modifier.width(120.dp), true, true) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val start = compose.onNodeWithTag("activityMarqueeText", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        compose.mainClock.advanceTimeBy(2000)
        val moved = compose.onNodeWithTag("activityMarqueeText", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue("$direction: $start -> $moved", if (direction == LayoutDirection.Ltr) moved < start else moved > start)
    }

    @Test
    fun overflowPausesAtEndThenMovesBackTowardStart() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { ActivityMarquee("A long activity description ".repeat(4), Modifier.width(120.dp), true, true) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val textNode = compose.onNodeWithTag("activityMarqueeText", useUnmergedTree = true)
        fun x() = textNode.fetchSemanticsNode().positionInRoot.x
        val start = x()
        val textWidth = textNode.fetchSemanticsNode().size.width
        val viewportWidth = compose.onNodeWithTag("activityMarquee").fetchSemanticsNode().size.width
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val duration = activityMarqueeDurationMs(textWidth - viewportWidth, density)
        assertTrue(duration > 1000)

        compose.mainClock.advanceTimeBy(1300)
        compose.mainClock.advanceTimeBy(duration.toLong() + 100)
        val end = x()
        assertTrue(end < start)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(end, x(), 1f)
        compose.mainClock.advanceTimeBy(1200)
        val returning = x()
        assertTrue("Expected return towards $start: $end -> $returning", returning > end && returning < start)
        compose.mainClock.advanceTimeBy(duration.toLong() + 100)
        assertEquals(start, x(), 1f)
    }

    @Test
    fun motionAndLifecycleChangesResetAndKeepFullText() {
        var text by mutableStateOf("Long activity description ".repeat(12))
        var width by mutableStateOf(120.dp)
        var motion by mutableStateOf(true)
        var active by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { ActivityMarquee(text, Modifier.width(width), motion, active) } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        fun x() = compose.onNodeWithTag("activityMarqueeText", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val start = x()
        compose.mainClock.advanceTimeBy(2000)
        assertTrue(x() < start)
        compose.runOnIdle { text = "Different long description ".repeat(12) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(start, x(), 1f)
        compose.mainClock.advanceTimeBy(2000)
        assertTrue(x() < start)
        compose.runOnIdle { width = 140.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(start, x(), 1f)
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle { motion = false }
        compose.mainClock.advanceTimeByFrame()
        val stopped = x()
        compose.mainClock.advanceTimeBy(3000)
        assertEquals(stopped, x(), 1f)
        compose.onNodeWithTag("activityMarquee").assertTextEquals(text)
        compose.runOnIdle { motion = true; active = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3000)
        assertEquals(start, x(), 1f)
        compose.runOnIdle { active = true }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(start, x(), 1f)
        compose.mainClock.advanceTimeBy(2000)
        assertTrue(x() < start)
    }
}
