package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthwaveColorsTest {
    @Test
    fun synthwaveFollowsLightAndDarkAndKeepsTextLegible() {
        for (dark in listOf(false, true)) {
            val scheme = remoteColorScheme(dark, synthwave = true)
            assertTrue((scheme.background.luminance() < 0.5f) == dark)
            val pairs = listOf(
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
            )
            for ((text, background) in pairs) {
                assertTrue("dark=$dark text=$text background=$background", contrast(text, background) >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
