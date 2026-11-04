package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.ui.unit.Dp
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
    /** Opens the chat with its rewind menu on the latest message; null where it cannot fork. */
    onForkLatest: (() -> Unit)? = null,
    /** Pins or unpins the session on this phone; null where the menu offers no pin entry. */
    onTogglePin: (() -> Unit)? = null,
    /** Marks the session read; the menu offers it only on an unread row. */
    onMarkRead: (() -> Unit)? = null,
    compactionFrame: State<Int>? = null,
) {
    fun available(action: SwipeAction): SwipeAction =
        when (action) {
            SwipeAction.CLOSE -> if (closeAvailable) action else SwipeAction.NONE
            SwipeAction.RENAME -> if (onRename != null) action else SwipeAction.NONE
            SwipeAction.NONE -> SwipeAction.NONE
        }
    val endToStart = available(swipe.first)
    val regionChild = rememberRegionChild()
    val startToEnd = available(swipe.second)
    val title = item.title.ifBlank { stringResource(R.string.remote_new_session) }
    val subagentLabel = stringResource(R.string.remote_subagent)
    val reported =
        stringResource(
            if (item.compacting) R.string.remote_status_compacting
            else when (item.availability) {
                SessionAvailability.IDLE -> R.string.remote_status_idle
                SessionAvailability.RUNNING -> R.string.remote_status_running
                SessionAvailability.WAITING -> R.string.remote_status_waiting
                SessionAvailability.OFFLINE -> R.string.remote_status_offline
            }
        )
    // A last-known status is shown muted and named as such: nothing on it is live.
    val status = if (item.lastKnown) stringResource(R.string.remote_status_last_known, reported) else reported
    val statusColor =
        if (item.lastKnown) MaterialTheme.colorScheme.outline else sessionStatusColor(item.availability)
    val copyExplanation = stringResource(R.string.remote_history_note)
    val closeLabel = stringResource(R.string.remote_session_close)
    val renameLabel = stringResource(R.string.remote_rename_session)
    val macTuiLabel = stringResource(R.string.remote_mac_tui_session)
    val workerLabel = stringResource(R.string.remote_mac_worker_session)
    val showActionsLabel = stringResource(R.string.remote_menu_show_actions)
    val copyId = rememberCopyToClipboard(stringResource(R.string.remote_menu_copy_session_id))
    val pinnedLabel = stringResource(R.string.remote_activity_pinned)
    val unreadLabel = stringResource(R.string.remote_activity_unread)
    // A right-click, the S Pen button, Shift+F10 and a long press all open the same menu, so
    // rename and close stay reachable with swipe actions turned off or a mouse in hand.
    val menuAnchor = rememberMenuAnchor()
    val menuItems = {
        sessionMenuActions(
            canRename = onRename != null,
            canClose = closeAvailable,
            canFork = onForkLatest != null,
            canPin = onTogglePin != null,
            pinned = item.pinned,
            canMarkRead = onMarkRead != null && item.unread,
        ).map { action ->
            MenuItem(action.label, "session_${action.name.lowercase()}") {
                when (action) {
                    SessionMenuAction.OPEN -> onClick()
                    SessionMenuAction.RENAME -> onRename?.invoke()
                    SessionMenuAction.FORK_LATEST -> onForkLatest?.invoke()
                    SessionMenuAction.PIN,
                    SessionMenuAction.UNPIN -> onTogglePin?.invoke()
                    SessionMenuAction.MARK_READ -> onMarkRead?.invoke()
                    SessionMenuAction.CLOSE -> onClose()
                    SessionMenuAction.COPY_ID -> copyId(item.id)
                }
            }
        }
    }
    val metaLabels =
        listOfNotNull(
            subagentLabel.takeIf { item.parentSessionId != null },
            if (item.childCount > 0)
                pluralStringResource(R.plurals.remote_sessions_subagent_count, item.childCount, item.childCount)
            else null,
            stringResource(R.string.remote_fork_session).takeIf { item.continuesAsCopy },
            // Shown even before the worker's first report, while it is still offline.
            workerLabel.takeIf { item.hostWorker },
        )
    val ticketLabel = item.ticketRef?.let { stringResource(R.string.remote_ticket_chip, it) }
    val rowDescription =
        listOfNotNull(
            subagentLabel.takeIf { item.parentSessionId != null },
            title,
            ticketLabel,
            macTuiLabel.takeIf { item.liveMacTui },
            workerLabel.takeIf { item.hostWorker },
            pinnedLabel.takeIf { item.pinned },
            unreadLabel.takeIf { item.unread },
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
                        // The press ripple follows the rounded selection instead of a square.
                        .clip(MaterialTheme.shapes.medium)
                        .then(
                            if (selected)
                                Modifier.background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.shapes.medium,
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
                        .focusRing(MaterialTheme.shapes.medium)
                        .then(regionChild)
                        .contextMenu(menuAnchor, enabled) { menuItems() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = { menuAnchor.open() },
                            onLongClickLabel = showActionsLabel,
                        )
                        .padding(
                            start = 4.dp + subagentIndent(item.depth),
                            top = 12.dp,
                            end = 4.dp,
                            bottom = 12.dp,
                        ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(44.dp).clearAndSetSemantics {}) {
                    GlassAvatar(size = 44.dp) {
                        // A subagent shows the robot mark; only the host's parentSessionId makes a
                        // row one, nothing here guesses it from the title or origin.
                        if (item.parentSessionId != null)
                            Icon(
                                Icons.Outlined.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else
                            Text(
                                stringResource(R.string.remote_design_pi_mark),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                    }
                    // No backing disc. The badge straddles the avatar's edge, so half of it would sit
                    // on the row background (secondaryContainer when selected). A thin contour in the
                    // avatar's own color, following the badge's shape, keeps the badge on one known
                    // color: the status colors are picked to reach 3:1 against surfaceContainerHighest.
                    val halo = MaterialTheme.colorScheme.surfaceContainerHighest
                    val badge = sessionBadgeGlyph(item.availability, item.lastKnown)
                    if (badge != null) {
                        StatusGlyph(
                            kind = badge,
                            color = statusColor,
                            modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).badgeHalo(halo),
                        )
                    } else {
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .padding(3.dp - BadgeHaloWidth)
                                .size(10.dp + BadgeHaloWidth * 2)
                                .background(halo, CircleShape)
                                .padding(BadgeHaloWidth)
                                .background(statusColor, CircleShape)
                        )
                    }
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
                        if (item.pinned)
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (item.unread)
                            Box(
                                Modifier.size(8.dp)
                                    .align(Alignment.CenterVertically)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
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
                        if (item.compacting) {
                            BrailleSpinner(compactionFrame, statusColor)
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else SessionStatusLabel(
                            status,
                            if (item.lastKnown) SessionAvailability.OFFLINE else item.availability,
                            statusColor,
                        )
                        if (metaLabels.isNotEmpty() || item.ticketRef != null)
                            Text(
                                (listOfNotNull(item.ticketRef) + metaLabels).joinToString(separator = " · ", prefix = "· "),
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

/**
 * How far a subagent's row moves in: 16dp per level, at most two levels, so a deep tree keeps
 * room for its titles on a phone.
 */
internal fun subagentIndent(depth: Int): Dp = 16.dp * depth.coerceIn(0, 2)

internal val BadgeHaloWidth = 1.5.dp

/**
 * Draws the content's silhouette, offset in eight directions and tinted [color], under the content:
 * a contour that follows the glyph's own shape instead of a backing disc.
 */
private fun Modifier.badgeHalo(color: Color, width: Dp = BadgeHaloWidth): Modifier =
    drawWithContent {
        val w = width.toPx()
        val d = w * 0.7071f
        val offsets = listOf(w to 0f, -w to 0f, 0f to w, 0f to -w, d to d, d to -d, -d to d, -d to -d)
        val paint = Paint().apply { colorFilter = ColorFilter.tint(color, BlendMode.SrcIn) }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(-w, -w, size.width + w, size.height + w), paint)
            offsets.forEach { (dx, dy) -> translate(dx, dy) { this@drawWithContent.drawContent() } }
            canvas.restore()
        }
        drawContent()
    }

/**
 * The glyph on a session's avatar, or null for a plain status dot. Only a fresh report gets a
 * glyph: a last-known RUNNING or WAITING must not look live, so it falls back to the muted dot.
 */
internal fun sessionBadgeGlyph(availability: SessionAvailability, lastKnown: Boolean): StatusGlyphKind? =
    when {
        lastKnown -> null
        availability == SessionAvailability.RUNNING -> StatusGlyphKind.SESSION_RUNNING
        availability == SessionAvailability.WAITING -> StatusGlyphKind.NEEDS_INPUT
        else -> null
    }
