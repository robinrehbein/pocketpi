package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingSurfaceTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun translucentHeaderPillsKeepTextContrastOverAnyBackdrop() {
        assertTrue(FLOATING_HEADER_ALPHA < 1f)
        for (dark in listOf(false, true)) {
            val scheme = remoteColorScheme(dark)
            val pill = scheme.surfaceContainer.copy(alpha = FLOATING_HEADER_ALPHA)
            for (backdrop in listOf(Color.Black, Color.White)) {
                val shown = pill.compositeOver(backdrop)
                for (text in listOf(scheme.onSurface, scheme.onSurfaceVariant)) {
                    val ratio = contrast(shown, text)
                    assertTrue("dark=$dark backdrop=$backdrop ratio=$ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
