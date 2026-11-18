package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulatorGestureTest {
    private fun image(width: Long, height: Long, displayWidth: Long, displayHeight: Long, rotation: Long) =
        EmulatorImage(ByteArray(0), width, height, displayWidth, displayHeight, rotation, 1, 1)

    @Test fun aTallScreenInAWidePaneIsPillarboxed() {
        // 100x200 shown in a 400x200 pane: scale 1, 150 px bars left and right.
        val portrait = image(100, 200, 1080, 2160, 0)
        assertNull(emulatorImagePoint(400f, 200f, portrait, 149f, 100f))
        assertNull(emulatorImagePoint(400f, 200f, portrait, 250f, 100f))
        assertEquals(0L to 0L, emulatorImagePoint(400f, 200f, portrait, 150f, 0f))
        assertEquals(540L to 1080L, emulatorImagePoint(400f, 200f, portrait, 200f, 100f))
        assertEquals(1079L to 2159L, emulatorImagePoint(400f, 200f, portrait, 249.99f, 199.99f))
    }

    @Test fun aWideScreenInATallPaneIsLetterboxed() {
        // A half-size capture: the display point is in display pixels, not the image's.
        val landscape = image(1200, 540, 2400, 1080, 1)
        // 1200x540 in a 600x600 pane: scale 0.5, image 600x270, bars of 165 px above and below.
        assertNull(emulatorImagePoint(600f, 600f, landscape, 300f, 164f))
        assertNull(emulatorImagePoint(600f, 600f, landscape, 300f, 435f))
        assertEquals(1200L to 540L, emulatorImagePoint(600f, 600f, landscape, 300f, 300f))
        assertEquals(0L to 0L, emulatorImagePoint(600f, 600f, landscape, 0f, 165f))
    }

    @Test fun everyRotationTheProtocolReportsMapsInTheRotatedDisplaySize() {
        // The Host reports width and height in the current rotation (1 and 3 swap them) and
        // captures the screen in it, so the mapping only scales, whatever the rotation.
        for (rotation in 0L..3L) {
            val (w, h) = if (rotation % 2 == 0L) 1080L to 2400L else 2400L to 1080L
            val shown = image(w / 2, h / 2, w, h, rotation)
            val paneW = 1000f; val paneH = 1000f
            val scale = minOf(paneW / shown.width, paneH / shown.height)
            val left = (paneW - shown.width * scale) / 2f; val top = (paneH - shown.height * scale) / 2f
            // A quarter in from the top left corner of the image, a tenth of a pixel further against rounding.
            val x = left + shown.width * scale / 4f + 0.1f; val y = top + shown.height * scale / 4f + 0.1f
            assertEquals("rotation $rotation", w / 4 to h / 4, emulatorImagePoint(paneW, paneH, shown, x, y))
            assertNull(emulatorImagePoint(paneW, paneH, shown, left - 1f, top + 1f))
        }
    }

    @Test fun aLongPressIsASwipeThatDoesNotMove() {
        assertEquals(EmulatorInput.Swipe(5, 6, 5, 6, EMULATOR_LONG_PRESS_MS), emulatorGesture(5L to 6L, 9L to 9L, moved = false, longPress = true, durationMs = 2_000))
        assertEquals(EmulatorInput.Tap(5, 6), emulatorGesture(5L to 6L, 5L to 6L, moved = false, longPress = false, durationMs = 100))
        assertEquals(EmulatorInput.Swipe(5, 6, 50, 60, 1000), emulatorGesture(5L to 6L, 50L to 60L, moved = true, longPress = false, durationMs = 5_000))
        assertEquals(EmulatorInput.Swipe(5, 6, 50, 60, 50), emulatorGesture(5L to 6L, 50L to 60L, moved = true, longPress = false, durationMs = 10))
        // The long press duration is one the Host's swipe accepts.
        assert(EMULATOR_LONG_PRESS_MS in 50..1000)
    }
}
