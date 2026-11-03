package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    @Test
    fun aUniformImageStaysTheSame() {
        val pixels = IntArray(12 * 8) { 0xFF336699.toInt() }
        boxBlur(pixels, 12, 8, 3)
        assertTrue(pixels.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun aBrightPixelSpreadsToItsNeighboursAndFadesAway() {
        val width = 21
        val height = 21
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[10 * width + 10] = 0xFFFFFFFF.toInt()
        boxBlur(pixels, width, height, 2)
        fun red(x: Int, y: Int) = (pixels[y * width + x] shr 16) and 0xFF
        assertTrue(red(10, 10) in 1 until 255)
        assertTrue(red(11, 10) > 0)
        assertTrue(red(10, 10) >= red(12, 10))
        assertEquals(0, red(0, 0))
        // Alpha stays opaque everywhere.
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun aZeroRadiusLeavesThePixelsAlone() {
        val pixels = intArrayOf(1, 2, 3, 4)
        boxBlur(pixels, 2, 2, 0)
        assertEquals(listOf(1, 2, 3, 4), pixels.toList())
    }
}
