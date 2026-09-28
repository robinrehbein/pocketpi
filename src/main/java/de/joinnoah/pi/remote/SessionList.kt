package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable
internal fun SessionListRow(
    item: SessionListItem,
    enabled: Boolean,
    closeAvailable: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onRename: (() -> Unit)? = null,
    childrenExpanded: Boolean = true,
    onToggleChildren: (() -> Unit)? = null,
    swipe: Pair<SwipeAction, SwipeAction> = SwipeAction.CLOSE to SwipeAction.RENAME,
    /** The chat beside the list in the two-pane layout shows this session. */
    selected: Boolean = false,
) {
    fun available(action: SwipeAction): SwipeAction =
        when (action) {
            SwipeAction.CLOSE -> if (closeAvailable) action else SwipeAction.NONE
            SwipeAction.RENAME -> if (onRename != null) action else SwipeAction.NONE
            SwipeAction.NONE -> SwipeAction.NONE
        }
    val endToStart = available(swipe.first)
    val startToEnd = available(swipe.second)
    val title = item.title.ifBlank { stringResource(R.string.remote_new_session) }
    val subagentLabel = stringResource(R.string.remote_subagent)
    val status =
        stringResource(
            when (item.availability) {
                SessionAvailability.IDLE -> R.string.remote_status_idle
                SessionAvailability.RUNNING -> R.string.remote_status_running
                SessionAvailability.WAITING -> R.string.remote_status_waiting
                SessionAvailability.OFFLINE -> R.string.remote_status_offline
            }
        )
    val statusColor = sessionStatusColor(item.availability)
    val copyExplanation = stringResource(R.string.remote_history_note)
    val closeLabel = stringResource(R.string.remote_session_close)
    val renameLabel = stringResource(R.string.remote_rename_session)
    val macTuiLabel = stringResource(R.string.remote_mac_tui_session)
    val metaLabels =
        listOfNotNull(
            subagentLabel.takeIf { item.parentSessionId != null },
            if (item.childCount > 0)
                pluralStringResource(R.plurals.remote_sessions_subagent_count, item.childCount, item.childCount)
            else null,
            stringResource(R.string.remote_fork_session).takeIf { item.continuesAsCopy },
        )
    val rowDescription =
        listOfNotNull(
            subagentLabel.takeIf { item.parentSessionId != null },
            title,
            macTuiLabel.takeIf { item.liveMacTui },
            item.preview,
            status,
        ).joinToString(", ")
    SnapBackSwipeBox(
        enableStartToEnd = startToEnd != SwipeAction.NONE,
        enableEndToStart = endToStart != SwipeAction.NONE,
        onSwipe = { direction ->
            val action =
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> endToStart
                    SwipeToDismissBoxValue.StartToEnd -> startToEnd
                    SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
                }
            when (action) {
                SwipeAction.CLOSE -> onClose()
                SwipeAction.RENAME -> onRename?.invoke()
                SwipeAction.NONE -> Unit
            }
        },
        background = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.EndToStart -> SessionSwipeBackground(endToStart, atEnd = true)
                SwipeToDismissBoxValue.StartToEnd -> SessionSwipeBackground(startToEnd, atEnd = false)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            if (selected)
                                Modifier.background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(16.dp),
                                )
                            else Modifier
                        )
                        .semantics {
                            if (selected) this.selected = true
                            contentDescription = rowDescription
                            if (item.continuesAsCopy) stateDescription = copyExplanation
                            customActions = buildList {
                                if (onRename != null) add(CustomAccessibilityAction(renameLabel) {
                                    onRename()
                                    true
                                })
                                if (closeAvailable) add(CustomAccessibilityAction(closeLabel) {
                                    onClose()
                                    true
                                })
                            }
                        }
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .padding(
                            start = 4.dp + (item.depth.coerceAtMost(4) * 28).dp,
                            top = 12.dp,
                            end = 4.dp,
                            bottom = 12.dp,
                        ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(44.dp).clearAndSetSemantics {}) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.remote_design_pi_mark),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .padding(2.dp)
                            .background(statusColor, CircleShape)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.liveMacTui)
                            Icon(
                                Icons.Default.LaptopMac,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        item.updatedAt?.let {
                            val context = LocalContext.current
                            Text(
                                (if (DateUtils.isToday(it)) DateFormat.getTimeFormat(context)
                                    else DateFormat.getDateFormat(context))
                                    .format(Date(it)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item.preview?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SessionStatusLabel(status, item.availability, statusColor)
                        if (metaLabels.isNotEmpty())
                            Text(
                                metaLabels.joinToString(separator = " · ", prefix = "· "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                    }
                }
            }
            if (onToggleChildren != null) {
                val toggleLabel = stringResource(
                    if (childrenExpanded) R.string.remote_subagents_hide
                    else R.string.remote_subagents_show
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onToggleChildren) {
                        Text(toggleLabel)
                        Icon(
                            if (childrenExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStatusLabel(
    status: String,
    availability: SessionAvailability,
    statusColor: Color,
) {
    when (availability) {
        SessionAvailability.WAITING ->
            Text(
                status,
                Modifier.background(waitingContainerColor(), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = onWaitingContainerColor(),
            )
        SessionAvailability.OFFLINE ->
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
    }
}

@Composable
private fun SessionSwipeBackground(action: SwipeAction, atEnd: Boolean) {
    when (action) {
        SwipeAction.CLOSE ->
            SwipeActionBackground(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.remote_swipe_action_close),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                atEnd = atEnd,
            )
        SwipeAction.RENAME ->
            SwipeActionBackground(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.remote_swipe_action_rename),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                atEnd = atEnd,
            )
        SwipeAction.NONE -> Unit
    }
}
