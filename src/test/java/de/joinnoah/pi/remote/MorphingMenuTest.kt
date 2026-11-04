package de.joinnoah.pi.remote

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MorphingMenuTest {
    private val window = IntSize(400, 800)
    @Test fun downPinsEndAndUpPinsStart() {
        val anchor = Rect(272f, 24f, 368f, 72f)
        assertEquals(IntOffset(48, 24), morphMenuPosition(anchor, 320, 500, window, MorphMenuDirection.DownFromEnd, false))
        assertEquals(IntOffset(272, 24), morphMenuPosition(anchor, 96, 48, window, MorphMenuDirection.DownFromEnd, false))
        assertEquals(IntOffset(32, 300), morphMenuPosition(Rect(32f, 752f, 64f, 784f), 320, 484, window, MorphMenuDirection.UpFromStart, false))
    }
    @Test fun rtlMirrorsLogicalPinnedEdgeAndShortWindowsClamp() {
        val anchor = Rect(16f, 24f, 112f, 72f)
        assertEquals(IntOffset(16, 24), morphMenuPosition(anchor, 320, 500, window, MorphMenuDirection.DownFromEnd, true))
        assertEquals(IntOffset.Zero, morphMenuPosition(anchor, 320, 500, IntSize(200, 100), MorphMenuDirection.DownFromEnd, false))
    }
    @Test fun attachmentGlyphCentreStaysPinnedWithA32PixelPaintedAnchorAnd48PixelControl() {
        val anchor = Rect(40f, 720f, 72f, 752f)
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val frame = morphMenuAnchorFrame(anchor, 48f, progress)
            val width = (32 + (240 - 32) * progress).toInt()
            val height = (32 + (300 - 32) * progress).toInt()
            val position = morphMenuPosition(frame, width, height, window, MorphMenuDirection.UpFromStart, false)
            val inset = 8f * (1f - progress)
            val glyphX = position.x - inset + 24f
            val glyphY = position.y + height + inset - 24f
            assertEquals("x at $progress", anchor.center.x, glyphX, 1f)
            assertEquals("y at $progress", anchor.center.y, glyphY, 1f)
        }
    }

    @Test fun openingRowsRemainInactiveUntilTheTransitionSettles() {
        val state = MorphMenuState()
        assertFalse(state.contentInteractive)
        state.expanded = true
        assertFalse("An opening menu must not expose active rows", state.contentInteractive)
        state.dismiss()
        assertFalse(state.contentInteractive)
    }

    @Test fun expandingAnotherStateDismissesThePreviousOwner() {
        val coordinator = MorphMenuCoordinator()
        val first = MorphMenuState(coordinator)
        val second = MorphMenuState(coordinator)
        first.expanded = true
        second.expanded = true
        assertFalse(first.expanded)
        assertTrue(second.expanded)
        second.dispose()
        first.expanded = true
        assertTrue(first.expanded)
        assertFalse(second.expanded)
    }
    @Test fun quickActionUsesRecencyNotFrequencyAndNeverClose() {
        val available = setOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.FILES, ChatAction.CLOSE)
        assertEquals(ChatAction.CHANGES, initialChatQuickAction(available, emptyMap()))
        val usage = mapOf(ChatAction.CHANGES to ActionUsage(100.0, 1), ChatAction.FILES to ActionUsage(1.0, 2), ChatAction.CLOSE to ActionUsage(1.0, 3))
        assertEquals(ChatAction.FILES, initialChatQuickAction(available, usage))
        assertEquals(ChatAction.CHANGES, initialChatQuickAction(available - ChatAction.FILES, usage))
        assertNull(initialChatQuickAction(setOf(ChatAction.CLOSE), usage))
    }
}
