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

    // diffAddedContent()/diffRemovedContent() are @Composable (they read MaterialTheme.colorScheme
    // to pick light or dark), so this mirrors their values by `dark` the same way this file
    // mirrors remoteColorScheme(dark) above, rather than requiring a compose test rule here.
    // TouchedFilesSummary paints them on the floating header pill, so they need the same contrast
    // check as the pill's other text colors.
    @Test
    fun diffColorsKeepTextContrastOverTheFloatingHeaderPill() {
        for (dark in listOf(false, true)) {
            val scheme = remoteColorScheme(dark)
            val pill = scheme.surfaceContainer.copy(alpha = FLOATING_HEADER_ALPHA)
            val addedContent = if (dark) Color(0xFFA8E3BA) else Color(0xFF1B5E33)
            val removedContent = if (dark) Color(0xFFFFB4AE) else Color(0xFF8C1D18)
            for (backdrop in listOf(Color.Black, Color.White)) {
                val shown = pill.compositeOver(backdrop)
                for (text in listOf(addedContent, removedContent)) {
                    val ratio = contrast(shown, text)
                    assertTrue("dark=$dark backdrop=$backdrop ratio=$ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
