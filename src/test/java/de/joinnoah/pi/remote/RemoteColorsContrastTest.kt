package de.joinnoah.pi.remote

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast ratio of two colours, from 1 to 21. */
private fun contrast(a: Color, b: Color): Double {
    val lighter = maxOf(a.luminance(), b.luminance()).toDouble()
    val darker = minOf(a.luminance(), b.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}

class RemoteColorsContrastTest {
    /** Dark and light, each in the default and the Synthwave palette. */
    private val schemes: List<Pair<String, ColorScheme>> =
        listOf(
            "default dark" to remoteColorScheme(dark = true),
            "default light" to remoteColorScheme(dark = false),
            "Synthwave dark" to remoteColorScheme(dark = true, synthwave = true),
            "Synthwave light" to remoteColorScheme(dark = false, synthwave = true),
        )

    private fun assertReadable(what: String, foreground: Color, background: Color) {
        val ratio = contrast(foreground, background)
        assertTrue("$what is ${"%.2f".format(ratio)}:1, below 4.5:1", ratio >= 4.5)
    }

    @Test fun linksInOutgoingBubblesReadOnTheirContainer() {
        for ((name, scheme) in schemes)
            assertReadable("$name link on the outgoing bubble", scheme.onPrimaryContainer, scheme.primaryContainer)
    }

    @Test fun linksInIncomingBubblesReadOnTheirSurface() {
        for ((name, scheme) in schemes)
            assertReadable("$name link on the incoming bubble", scheme.primary, scheme.surfaceContainer)
    }

    @Test fun lightTertiaryTextReadsOnTheLightSurfaces() {
        for ((name, scheme) in schemes.filter { !it.second.isDark() })
            for (surface in listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHighest))
                assertReadable("$name tertiary text", scheme.tertiary, surface)
    }

    @Test fun codeBlocksStandOutFromTheBubbleAroundThem() {
        for ((name, scheme) in schemes) {
            assertReadable("$name code text", scheme.onSurface, scheme.surfaceContainerHighest)
            assertTrue("$name code block must differ from its bubble", scheme.surfaceContainerHighest != scheme.surfaceContainer)
        }
    }

    private fun ColorScheme.isDark(): Boolean = surface.luminance() < 0.5f
}
