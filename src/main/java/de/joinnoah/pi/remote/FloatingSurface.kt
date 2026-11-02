package de.joinnoah.pi.remote

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/*
 * Floating elements (header pills, chips, the composer, floating buttons, popovers) are frosted
 * glass: the content behind them is blurred and shows through a translucent tint of the surface.
 *
 * The blurred content is what the scrolling lists draw: each list behind a floating element marks
 * itself with [glassSource]. A floating element that is not over a source, or sits inside one (a
 * sticky list item, a hover row in a message), shows its tint alone. Where blur is not available
 * (before Android 12, in a dialog, sheet or popup, or without [ProvideGlass]) the tint is as opaque as the pills were before
 * glass, so text keeps its contrast over the sharp content.
 */

/**
 * How opaque a neutral floating surface is without blur. The content shows through faintly; text
 * on it stays above 4.5:1 in both schemes even over the darkest or lightest content (see
 * FloatingSurfaceTest).
 */
internal const val FLOATING_HEADER_ALPHA = 0.88f

/**
 * How opaque the tint of a neutral floating surface is over blurred content. Blur averages the
 * content behind, so a lighter tint keeps text above 4.5:1 (see FloatingSurfaceTest for the model).
 */
internal const val GLASS_TINT_ALPHA = 0.72f

/**
 * How opaque the tint of an accent floating surface (a primary button, a selected or warning
 * pill) is over blurred content: tinted glass that keeps its colour and its label's contrast.
 */
internal const val GLASS_ACCENT_ALPHA = 0.88f

/** The blur radius of the frosted backdrop. */
private val GLASS_BLUR_RADIUS = 24.dp

/**
 * The shared source of the frosted backdrops and the window it belongs to. Dialogs, sheets and
 * popups inherit it but draw in windows of their own, over content of their own, so floating
 * surfaces there draw their tint alone (see [GlassBackdrop.blursIn]).
 */
internal class GlassBackdrop(val state: HazeState, private val rootView: View) {
    /** Whether a floating surface in the window of [view] blurs this backdrop. */
    fun blursIn(view: View): Boolean = view.rootView === rootView
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Provides the glass backdrop to [content]: lists mark themselves with [glassSource] inside it. */
@Composable
internal fun ProvideGlass(content: @Composable () -> Unit) {
    val state = rememberHazeState()
    val rootView = LocalView.current.rootView
    val backdrop = remember(state, rootView) { GlassBackdrop(state, rootView) }
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, content = content)
}

/**
 * Marks content that floating elements over it blur, such as a scrolling list under the floating
 * header. Put it on content that does not hold the floating elements themselves.
 */
internal fun Modifier.glassSource(): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop != null && backdrop.blursIn(LocalView.current)) Modifier.hazeSource(backdrop.state) else Modifier
}

/** Whether [color] is one of the scheme's neutral surface colours rather than an accent. */
internal fun ColorScheme.isNeutralSurface(color: Color): Boolean =
    color == surface || color == background || color == surfaceBright || color == surfaceDim ||
        color == surfaceContainerLowest || color == surfaceContainerLow || color == surfaceContainer ||
        color == surfaceContainerHigh || color == surfaceContainerHighest

/**
 * The tint a floating surface of [color] shows. A colour that is already translucent is kept as
 * the caller chose it; an opaque neutral or accent colour gets the glass alpha for it.
 */
internal fun glassTint(color: Color, scheme: ColorScheme, blur: Boolean): Color {
    if (color.alpha < 1f) return color
    val neutral = scheme.isNeutralSurface(color)
    val alpha = when {
        neutral && blur -> GLASS_TINT_ALPHA
        neutral -> FLOATING_HEADER_ALPHA
        blur -> GLASS_ACCENT_ALPHA
        else -> 1f
    }
    return color.copy(alpha = alpha)
}

/**
 * A floating element: a frosted glass surface of [color]'s tint with a soft shadow and a hairline
 * rim. Pass the opaque scheme colour; the glass alpha is applied here. [blur] off draws the tint
 * alone, for a surface inside the content it would blur (see [glassSource]).
 */
@Composable
internal fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    // contentColorFor only knows the opaque scheme colors, so a translucent one is looked up as such.
    contentColor: Color = contentColorFor(color.copy(alpha = 1f)),
    blur: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val state = LocalGlassBackdrop.current
        ?.takeIf { blur && it.blursIn(view) && HazeBlurDefaults.isBlurEnabledByDefault() }
        ?.state
    val blurred = state != null
    val tint = glassTint(color, scheme, blurred)
    val backdrop = scheme.background
    val style = remember(backdrop) {
        HazeBlurStyle {
            blurRadius(GLASS_BLUR_RADIUS)
            // Behind the blurred content, so the transparent parts of a list never show its sharp
            // drawing through the glass.
            backgroundColor(backdrop)
            noiseFactor(0.06f)
        }
    }
    Box(
        modifier
            // Keep the shadow outside the surface's rounded clipping layer.
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .clip(shape)
            .then(if (state != null) Modifier.hazeBlur(HazeInput.Sources(state), style) else Modifier)
    ) {
        Surface(
            shape = shape,
            color = tint,
            contentColor = contentColor,
            shadowElevation = 0.dp,
            border = BorderStroke(GLASS_RIM_WIDTH, glassRimColor(scheme)),
            content = content,
        )
    }
}

/** The hairline rim that lifts glass off content of its own colour. */
private val GLASS_RIM_WIDTH = 0.5.dp

private fun glassRimColor(scheme: ColorScheme): Color =
    if (scheme.surface.luminance() < 0.5f) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.6f)
