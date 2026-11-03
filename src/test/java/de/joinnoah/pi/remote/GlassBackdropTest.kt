package de.joinnoah.pi.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBackdropTest {
    @Test fun snapshotIsDownscaledAndBounded() {
        val size = snapshotSize(4000, 2000)
        assertEquals(512, size.width)
        assertEquals(256, size.height)
        assertTrue(snapshotSize(1, 1).width > 0)
    }

    @Test fun blurTracksDensityAndDownscaling() {
        assertEquals(7, snapshotBlurRadius(2f, 400, 100))
        assertEquals(14, snapshotBlurRadius(4f, 400, 100))
        assertEquals(7, snapshotBlurRadius(4f, 800, 100))
    }

    @Test fun offsetTracksCurrentRootAndSurfacePositions() {
        val size = IntSize(400, 800)
        assertEquals(IntOffset(-20, -30), snapshotOffset(IntOffset(100, 200), Offset(120f, 230f), size, size))
        // Moving both windows together leaves their relative offset unchanged.
        assertEquals(IntOffset(-20, -30), snapshotOffset(IntOffset(150, 250), Offset(170f, 280f), size, size))
        // A root-only move changes the aligned destination.
        assertEquals(IntOffset(30, 20), snapshotOffset(IntOffset(150, 250), Offset(120f, 230f), size, size))
        assertNull(snapshotOffset(IntOffset.Zero, Offset.Zero, size, IntSize(401, 800)))
        assertNull(snapshotOffset(IntOffset.Zero, Offset.Zero, IntSize.Zero, IntSize.Zero))
    }

    @Test fun boxBlurKeepsUniformPixels() {
        val pixels = IntArray(16) { 0xff345678.toInt() }
        boxBlur(pixels, 4, 4, 3)
        assertTrue(pixels.all { it == 0xff345678.toInt() })
    }
}
