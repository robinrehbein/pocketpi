package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behaviour tests: the open job view's list ends pinned to the last line, however tall the items
 * are. They pass with the old `Int.MAX_VALUE` offset as well as the current `Int.MAX_VALUE / 2`,
 * so they pin the scrolled-to-end behaviour, not the offset's overflow fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// Legacy graphics lays out text on one line; the tall items need real wrapping.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JobOutputScrollTest {
    @get:Rule val compose = createComposeRule()

    private val tall = "word ".repeat(JOB_LINE_CHUNK_CHARS / 5)

    private fun render(lines: List<String>) {
        val view = JobView("job", null, lines = lines.map(JobOutputLine::Text), loaded = true)
        compose.setContent {
            MaterialTheme {
                BackgroundJobsScreen(JobsState("s", listOpen = true, view = view), JobsActions({}, {}, {}, {}, {}))
            }
        }
        compose.waitForIdle()
    }

    private fun assertAtEnd() {
        val range = compose.onNodeWithTag("jobOutput").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("scrolled back", range.value() > 0f)
        assertEquals(range.maxValue(), range.value())
    }

    @Test
    fun showsTheEndOfASingleItemTallerThanTheScreen() {
        render(listOf(tall))
        assertAtEnd()
    }

    @Test
    fun showsAShortLastItemAfterTallOnes() {
        render(listOf(tall, tall, tall, "end"))
        compose.onNodeWithText("end").assertIsDisplayed()
        assertAtEnd()
    }
}
