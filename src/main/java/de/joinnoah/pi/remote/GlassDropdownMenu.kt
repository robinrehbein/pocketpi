package de.joinnoah.pi.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Material positioning, focus, dismissal and scrolling with a snapshot of the parent app. */
@Composable
internal fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberAppBackdrop(expanded)
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.clip(shape)
            .snapshotBackdrop(backdrop, scheme.surfaceContainer.copy(alpha = GLASS_TINT_ALPHA), scheme.surfaceContainer),
        offset = offset,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(GLASS_RIM_WIDTH, glassRimColor(scheme)),
        content = content,
    )
}
