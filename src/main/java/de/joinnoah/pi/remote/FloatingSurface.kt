package de.joinnoah.pi.remote

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * (before Android 12, in a dialog, sheet or popup, or without [ProvideGlass]) the tint is as
 * opaque as the pills were before glass, so text keeps its contrast over the sharp content.
 *
 * A page's content scrolls under its floating header rather than stopping below it, so the header
 * pills have something to blur: a Scaffold page pads its content's top itself, see
 * [underFloatingHeader].
 */

/**
 * How opaque a neutral floating surface is without blur. The content shows through faintly; text
 * on it stays above 4.5:1 in both schemes even over the darkest or lightest content (see
 * FloatingSurfaceTest).
 */
internal const val FLOATING_HEADER_ALPHA = 0.88f

/**
 * How opaque the tint of a neutral floating surface is over blurred content: lighter, as the blur
 * already softens what shows through, and still above 4.5:1 for text over black or white content
 * (see FloatingSurfaceTest).
 */
internal const val GLASS_TINT_ALPHA = 0.84f

/**
 * How opaque the tint of an accent floating surface (a primary button, a selected or warning
 * pill) is over blurred content: tinted glass that keeps its colour and its label above 4.5:1 over
 * black or white content.
 */
internal const val GLASS_ACCENT_ALPHA = 0.88f

/** The blur radius of the frosted backdrop. */
private val GLASS_BLUR_RADIUS = 24.dp

/**
 * The shared source of the frosted backdrops and the window it belongs to. Dialogs, sheets and
 * popups inherit it but draw in windows of their own, over content of their own, so floating
 * surfaces there draw their tint alone (see [GlassBackdrop.blursIn]).
 */
internal class GlassBackdrop(val state: HazeState, val background: Color, private val rootView: View) {
    /** Whether a floating surface in the window of [view] blurs this backdrop. */
    fun blursIn(view: View): Boolean = view.rootView === rootView
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * Provides a glass backdrop to [content]: lists mark themselves with [glassSource] inside it, and
 * floating surfaces inside it blur those lists alone. Each page and each pane over a page has its
 * own, so a pill never blurs a page beside or hidden behind its own. [background] is what the
 * lists are drawn on; it fills the transparent parts of the blurred lists.
 */
@Composable
internal fun ProvideGlass(
    background: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    val state = rememberHazeState()
    val rootView = LocalView.current.rootView
    val backdrop = remember(state, background, rootView) { GlassBackdrop(state, background, rootView) }
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
    val glass = LocalGlassBackdrop.current
        ?.takeIf { blur && it.blursIn(view) && HazeBlurDefaults.isBlurEnabledByDefault() }
    val state = glass?.state
    val tint = glassTint(color, scheme, blur = state != null)
    val backdrop = glass?.background ?: scheme.background
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

/**
 * A banner that floats over the chat (in its header) as tinted glass, or sits [floating] = false
 * inside a sheet as a plain surface of the same colour.
 */
@Composable
internal fun BannerSurface(
    floating: Boolean,
    modifier: Modifier,
    color: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    if (floating) FloatingSurface(modifier, shape, color, contentColor, content = content)
    else Surface(modifier, shape = shape, color = color, contentColor = contentColor, content = content)
}

/**
 * Material's snackbar as a floating glass bar of the inverse surface. The action keeps the bar's
 * text colour: inversePrimary would fall below 4.5:1 on the translucent bar over light content.
 */
@Composable
internal fun GlassSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val visuals = data.visuals
    FloatingSurface(
        // As wide as the composer at most, rather than the whole chat of a wide window.
        modifier.padding(12.dp).widthIn(max = PROSE_READING_WIDTH + 32.dp),
        shape = MaterialTheme.shapes.small,
        color = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = if (visuals.withDismissAction || visuals.actionLabel != null) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                visuals.message,
                Modifier.weight(1f, fill = false).padding(vertical = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction) {
                    Text(label, color = scheme.inverseOnSurface, fontWeight = FontWeight.Bold)
                }
            }
            if (visuals.withDismissAction)
                IconButton(onClick = data::dismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.remote_snackbar_dismiss))
                }
        }
    }
}

/**
 * The Scaffold content padding of a page whose content scrolls under its floating header: the top
 * keeps only the status bar, which stays clear as on the other pages, and the content pads the
 * rest of the header itself with [floatingHeaderOverlap].
 */
@Composable
internal fun PaddingValues.underFloatingHeader(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}

/** How far the floating header reaches below the status bar, for content under it to pad. */
@Composable
internal fun PaddingValues.floatingHeaderOverlap(): Dp =
    (calculateTopPadding() - WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).coerceAtLeast(0.dp)

/** [this] without its bottom, for a list that pads its own bottom to scroll under a floating bottom pill. */
@Composable
internal fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

/**
 * A round avatar in the glass look: a soft top-lit fill instead of a flat disc, the glass rim and a
 * small shadow. It sits in a list rather than over one, so it blurs nothing; [content] is centred.
 */
@Composable
internal fun GlassAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    // Lit from above: a lighter top fading into the container colour the flat avatars had.
    val fill =
        if (dark) Brush.verticalGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainer))
        else Brush.verticalGradient(listOf(scheme.surfaceContainerLowest, scheme.surfaceContainerHigh))
    Box(
        modifier
            .size(size)
            .shadow(
                elevation = 2.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.14f),
            )
            .clip(CircleShape)
            .background(fill)
            .border(GLASS_RIM_WIDTH, glassRimColor(scheme), CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
