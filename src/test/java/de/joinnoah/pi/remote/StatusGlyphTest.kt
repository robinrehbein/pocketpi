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
    fun compactionUsesPiCoreBrailleFramesEvery80Milliseconds() {
        val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".map { it.code - 0x2800 }
        frames.forEachIndexed { index, cells ->
            assertEquals(cells, compactionDotCells(index))
            assertEquals(index, statusGlyphFrame(StatusGlyphKind.COMPACTING, index * 80L))
            assertEquals(index, statusGlyphFrame(StatusGlyphKind.COMPACTING, index * 80L + 79))
        }
        assertEquals(0, statusGlyphFrame(StatusGlyphKind.COMPACTING, 800))
        assertEquals(0, statusGlyphFrame(StatusGlyphKind.COMPACTING, -1))
        assertEquals(frames.first(), compactionDotCells(10))
    }

    @Test
    fun completedWorkUsesExactlyTopLeftAndBottomRightQuarters() {
        assertEquals(0b1001, WORK_COMPLETED_CELLS)
        assertEquals(2, Integer.bitCount(WORK_COMPLETED_CELLS))
        assertEquals(0, statusGlyphFrame(StatusGlyphKind.WORK_COMPLETED, Long.MAX_VALUE))
    }

    @Test
    fun staticGlyphsNeverAdvance() {
        for (kind in StatusGlyphKind.entries - setOf(StatusGlyphKind.TASK_RUNNING, StatusGlyphKind.TOOL_RUNNING, StatusGlyphKind.SESSION_RUNNING, StatusGlyphKind.COMPACTING)) {
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
