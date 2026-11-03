package de.joinnoah.pi.remote

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session badge sits on a contour in surfaceContainerHighest; this checks the opaque status
 * colors against that color for the WCAG 1.4.11 non-text minimum (3:1) in every built-in scheme.
 * It says nothing about antialiased edge pixels. Dynamic (wallpaper) schemes cannot be enumerated
 * here; the tone check below is an approximation, not a guarantee for device-generated schemes.
 */
class SessionBadgeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun statusColors(scheme: ColorScheme): Map<String, Color> {
        val dark = scheme.surface.luminance() < 0.5f
        return mapOf(
            "idle" to if (dark) IdleDark else IdleLight,
            "running" to scheme.primary,
            "waiting" to if (dark) WaitingDark else WaitingLight,
            "offline/lastKnown" to scheme.outline,
        )
    }

    @Test
    fun badgeColorsReachThreeToOneOnTheHaloInEveryBuiltInScheme() {
        for (dark in listOf(false, true)) for (synthwave in listOf(false, true)) {
            val scheme = remoteColorScheme(dark, synthwave)
            statusColors(scheme).forEach { (name, color) ->
                val ratio = contrast(color, scheme.surfaceContainerHighest)
                assertTrue("$name dark=$dark synthwave=$synthwave: $ratio", ratio >= 3.0)
            }
        }
    }

    /** Material 3 dynamic tones as greys (L* = tone): surfaceContainerHighest N90/N22, primary 40/80, outline N50/N60. */
    @Test
    fun badgeColorsReachThreeToOneOnDynamicToneApproximation() {
        fun tone(t: Double): Color {
            val f = (t + 16) / 116
            val y = if (t > 8) f * f * f else t / 903.3
            val s = if (y <= 0.0031308) 12.92 * y else 1.055 * Math.pow(y, 1 / 2.4) - 0.055
            return Color(s.toFloat(), s.toFloat(), s.toFloat())
        }
        val light = mapOf("idle" to IdleLight, "running" to tone(40.0), "waiting" to WaitingLight, "outline" to tone(50.0))
        val dark = mapOf("idle" to IdleDark, "running" to tone(80.0), "waiting" to WaitingDark, "outline" to tone(60.0))
        light.forEach { (n, c) -> assertTrue("light $n", contrast(c, tone(90.0)) >= 3.0) }
        dark.forEach { (n, c) -> assertTrue("dark $n", contrast(c, tone(22.0)) >= 3.0) }
    }
}
