package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
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

    /**
     * Over blurred content the tint is lighter. Blur averages what lies behind, so the backdrop is
     * modelled as the page background with up to [BLURRED_EXTREME_SHARE] of black or white mixed in
     * (a large image or a block of bright content under the pill).
     */
    @Test
    fun glassTintKeepsTextContrastOverBlurredContent() {
        for (dark in listOf(false, true)) {
            val scheme = remoteColorScheme(dark)
            val pill = glassTint(scheme.surfaceContainer, scheme, blur = true)
            val diffAdded = if (dark) Color(0xFFA8E3BA) else Color(0xFF1B5E33)
            val diffRemoved = if (dark) Color(0xFFFFB4AE) else Color(0xFF8C1D18)
            val accents =
                listOf(
                    scheme.primary to scheme.onPrimary,
                    scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.errorContainer to scheme.onErrorContainer,
                )
            for (extreme in listOf(Color.Black, Color.White)) {
                val backdrop = extreme.copy(alpha = BLURRED_EXTREME_SHARE).compositeOver(scheme.background)
                val shown = pill.compositeOver(backdrop)
                for (text in listOf(scheme.onSurface, scheme.onSurfaceVariant, diffAdded, diffRemoved)) {
                    val ratio = contrast(shown, text)
                    assertTrue("dark=$dark backdrop=$extreme ratio=$ratio", ratio >= 4.5f)
                }
                for ((container, label) in accents) {
                    val ratio = contrast(glassTint(container, scheme, blur = true).compositeOver(backdrop), label)
                    assertTrue("dark=$dark accent=$container backdrop=$extreme ratio=$ratio", ratio >= 4.5f)
                }
            }
        }
    }

    @Test
    fun glassTintKeepsAccentsAndCallerAlpha() {
        val scheme = remoteColorScheme(dark = false)
        assertEquals(FLOATING_HEADER_ALPHA, glassTint(scheme.surfaceContainer, scheme, blur = false).alpha)
        assertEquals(GLASS_TINT_ALPHA, glassTint(scheme.surface, scheme, blur = true).alpha)
        assertEquals(GLASS_ACCENT_ALPHA, glassTint(scheme.primary, scheme, blur = true).alpha)
        // Without blur an accent stays solid, as it was before glass.
        assertEquals(1f, glassTint(scheme.primary, scheme, blur = false).alpha)
        val chosen = scheme.surfaceContainer.copy(alpha = 0.5f)
        assertEquals(chosen, glassTint(chosen, scheme, blur = true))
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

    private companion object {
        const val BLURRED_EXTREME_SHARE = 0.6f
    }
}
