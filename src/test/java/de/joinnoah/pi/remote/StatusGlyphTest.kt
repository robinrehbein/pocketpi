package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusGlyphTest {
    @Test
    fun taskAndToolFramesChangeEvery400Milliseconds() {
        for (kind in listOf(StatusGlyphKind.TASK_RUNNING, StatusGlyphKind.TOOL_RUNNING)) {
            assertEquals(0, statusGlyphFrame(kind, 0))
            assertEquals(0, statusGlyphFrame(kind, 399))
            assertEquals(1, statusGlyphFrame(kind, 400))
            assertEquals(1, statusGlyphFrame(kind, 799))
            assertEquals(0, statusGlyphFrame(kind, 800))
            assertEquals(0, statusGlyphFrame(kind, -1))
            assertEquals(((Long.MAX_VALUE / 400) % 2).toInt(), statusGlyphFrame(kind, Long.MAX_VALUE))
        }
    }

    @Test
    fun snakeVisitsEightFramesAndWraps() {
        for (frame in 0..7) {
            assertEquals(frame, statusGlyphFrame(StatusGlyphKind.SESSION_RUNNING, frame * 100L))
            assertEquals(frame, statusGlyphFrame(StatusGlyphKind.SESSION_RUNNING, frame * 100L + 99))
        }
        assertEquals(0, statusGlyphFrame(StatusGlyphKind.SESSION_RUNNING, 800))
    }

    @Test
    fun staticGlyphsNeverAdvance() {
        for (kind in StatusGlyphKind.entries - setOf(StatusGlyphKind.TASK_RUNNING, StatusGlyphKind.TOOL_RUNNING, StatusGlyphKind.SESSION_RUNNING)) {
            assertEquals(0, statusGlyphFrame(kind, 12345))
        }
    }

    @Test
    fun staticSessionGlyphIsDistinctFromSnakeFrames() {
        val snake = (0..7).map { sessionRunningCells(it, animated = true) }
        assertEquals(listOf(4, 5, 1, 3, 2, 10, 8, 12), snake)
        for (frame in 0..7) assertEquals(SESSION_RUNNING_STATIC_CELLS, sessionRunningCells(frame, animated = false))
        assertEquals(false, SESSION_RUNNING_STATIC_CELLS in snake)
        assertEquals(3, Integer.bitCount(SESSION_RUNNING_STATIC_CELLS))
    }
}
