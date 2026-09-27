package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun HostCard(
    host: PairedHost,
    connected: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(52.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LaptopMac, null, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host.relay, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (connected) R.string.remote_connected else R.string.remote_design_not_connected),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.DeleteOutline, stringResource(R.string.remote_remove))
            }
        }
    }
}

/**
 * A row that can be swiped to trigger an action instead of being removed: once a swipe settles in a
 * direction, [onSwipe] runs exactly once and the row animates back into place.
 */
@Composable
internal fun SnapBackSwipeBox(
    enableStartToEnd: Boolean,
    enableEndToStart: Boolean,
    onSwipe: (SwipeToDismissBoxValue) -> Unit,
    background: @Composable RowScope.(SwipeToDismissBoxValue) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    // Not saveable: a row that always snaps back must never be restored in a dismissed position,
    // which would re-deliver the swipe after rotation or scrolling back.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    val scope = rememberCoroutineScope()
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    // Kept stable so SwipeToDismissBox does not re-deliver the same settled swipe on recomposition.
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember(state, scope) {
        { direction ->
            currentOnSwipe(direction)
            scope.launch { state.reset() }
        }
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = { background(state.dismissDirection) },
        enableDismissFromStartToEnd = enableStartToEnd,
        enableDismissFromEndToStart = enableEndToStart,
        onDismiss = onDismiss,
        content = content,
    )
}

@Composable
internal fun ProjectRow(
    projectId: String,
    name: String,
    unshareEnabled: Boolean,
    onClick: () -> Unit,
    onUnshare: () -> Unit,
) {
    val unshareLabel = stringResource(R.string.remote_project_unshare)
    SnapBackSwipeBox(
        enableStartToEnd = false,
        enableEndToStart = unshareEnabled,
        onSwipe = { direction -> if (direction == SwipeToDismissBoxValue.EndToStart) onUnshare() },
        background = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart)
                SwipeActionBackground(
                    icon = Icons.Default.DeleteOutline,
                    label = unshareLabel,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    atEnd = true,
                )
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .semantics {
                    if (unshareEnabled) customActions = listOf(
                        CustomAccessibilityAction(unshareLabel) {
                            onUnshare()
                            true
                        }
                    )
                }
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Tinted card revealed behind a row while it is swiped. [atEnd] puts the icon and label at the
 * right edge, which a right-to-left swipe uncovers; otherwise they sit at the left edge.
 */
@Composable
internal fun SwipeActionBackground(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    atEnd: Boolean,
) {
    Row(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (atEnd) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}
