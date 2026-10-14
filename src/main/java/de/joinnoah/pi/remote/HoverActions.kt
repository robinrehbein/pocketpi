package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * A compact row of icon buttons for [actions] while [visible]. It lies over the element instead
 * of beside it, so coming and going never moves the layout.
 */
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
