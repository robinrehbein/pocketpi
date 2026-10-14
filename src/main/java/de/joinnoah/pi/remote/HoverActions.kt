package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** One action of a hovered element's row; [tag] names its button for tests (`hover_<tag>`). */
@Immutable
internal class HoverAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val tag: String,
    val onClick: () -> Unit,
)

/**
 * Reports into [source] while a mouse, a touchpad or a hovering S Pen is over this element; read
 * it with `collectIsHoveredAsState` and show a [HoverActionRow]. Touch never hovers, so whatever
 * the row offers must also be reachable another way (a menu, an accessibility action).
 */
internal fun Modifier.hoverActions(source: MutableInteractionSource): Modifier = hoverable(source)

/**
 * [HoverActionRow] over the top end corner of the box it sits in while [source] reports a hover.
 * It takes no part in the box's size, so coming and going never moves the layout. A row wider
 * than the box reaches past it: toward the start when [growTowardStart], as for a bubble at the
 * end of the chat, and otherwise toward the end. Give [source] a hover area that covers that
 * part too, such as the whole line of a message, so a pointer on its way there keeps the row.
 */
@Composable
internal fun BoxScope.HoverActionOverlay(
    source: MutableInteractionSource,
    actions: List<HoverAction>,
    growTowardStart: Boolean,
) {
    val hovered by source.collectIsHoveredAsState()
    if (!hovered || actions.isEmpty()) return
    Box(Modifier.matchParentSize()) {
        // While the pointer is on the row, the row itself holds the hover too.
        HoverActionRow(true, actions, Modifier.overTopEnd(growTowardStart).hoverActions(source))
    }
}

/** Measures the element at its own width and places it at the top end, reporting no size. */
internal fun Modifier.overTopEnd(growTowardStart: Boolean): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val end = constraints.maxWidth - placeable.width
        val x = if (growTowardStart) end else end.coerceAtLeast(0)
        layout(0, 0) { placeable.placeRelative(x, 0) }
    }

/** A compact row of icon buttons for [actions] while [visible]. */
@Composable
internal fun HoverActionRow(visible: Boolean, actions: List<HoverAction>, modifier: Modifier = Modifier) {
    if (!visible || actions.isEmpty()) return
    val label = stringResource(R.string.remote_hover_actions)
    FloatingSurface(
        modifier.testTag("hoverActions").semantics { contentDescription = label },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row {
            for (action in actions) {
                val actionLabel = stringResource(action.label)
                ShortcutTooltip(actionLabel) {
                    IconButton(
                        onClick = action.onClick,
                        modifier = Modifier.size(36.dp).testTag("hover_${action.tag}"),
                    ) {
                        Icon(action.icon, actionLabel, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
