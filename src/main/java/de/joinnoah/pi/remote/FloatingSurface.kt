package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * How opaque the floating header pills are. The list shows through faintly; text on them stays
 * above 4.5:1 in both schemes even over the darkest or lightest content (see FloatingSurfaceTest).
 */
internal const val FLOATING_HEADER_ALPHA = 0.88f

/** The slightly translucent surface of the floating header pills, in light and dark theme. */
@Composable
internal fun floatingHeaderColor(): Color =
    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = FLOATING_HEADER_ALPHA)

@Composable
internal fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    color: Color = floatingHeaderColor(),
    // contentColorFor only knows the opaque scheme colors, so a translucent one is looked up as such.
    contentColor: Color = contentColorFor(color.copy(alpha = 1f)),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .dropShadow(
                shape,
                Shadow(
                    radius = 24.dp,
                    color = Color.Black.copy(alpha = 0.10f),
                    offset = DpOffset(0.dp, 10.dp),
                ),
            )
            .dropShadow(
                shape,
                Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(alpha = 0.07f),
                    offset = DpOffset(0.dp, 3.dp),
                ),
            )
    ) {
        Surface(shape = shape, color = color, contentColor = contentColor, shadowElevation = 0.dp, content = content)
    }
}
