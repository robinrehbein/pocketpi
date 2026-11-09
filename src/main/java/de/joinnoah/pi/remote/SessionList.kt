package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.platform.testTag
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

/**
 * The list's last row when sessions are hidden on this phone: "N hidden sessions" with a button
 * that lists them (muted, with Show again) or folds them away. The state is not persisted.
 */
@Composable
internal fun HiddenSessionsFooter(count: Int, shown: Boolean, onToggle: () -> Unit) {
    val summary = pluralStringResource(R.plurals.remote_sessions_hidden_count, count, count)
    val action = stringResource(if (shown) R.string.remote_sessions_hide_hidden else R.string.remote_sessions_show_hidden)
    val state =
        stringResource(
            if (shown) R.string.remote_sessions_hidden_state_shown
            else R.string.remote_sessions_hidden_state_collapsed
        )
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = "$summary, $action"
                stateDescription = state
            }
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag("hiddenSessionsFooter"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$summary ·",
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            action,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** What a swipe on a session row does once the row's own state is taken into account. */
internal enum class SessionSwipe { CLOSE, RENAME, HIDE, UNHIDE, NONE }

/**
 * The effect of the stored [action] on a row. Close stands in for Hide on a row the phone cannot
 * close but can hide, and for Show again on a hidden row; the stored [SwipeAction] stays as it is.
 */
internal fun effectiveSessionSwipe(
    action: SwipeAction,
    closeAvailable: Boolean,
    canRename: Boolean,
    canHide: Boolean,
    canUnhide: Boolean,
): SessionSwipe =
    when (action) {
        SwipeAction.CLOSE ->
            when {
                canUnhide -> SessionSwipe.UNHIDE
                closeAvailable -> SessionSwipe.CLOSE
                canHide -> SessionSwipe.HIDE
                else -> SessionSwipe.NONE
            }
        SwipeAction.RENAME -> if (canRename) SessionSwipe.RENAME else SessionSwipe.NONE
        SwipeAction.NONE -> SessionSwipe.NONE
    }

@Composable
internal fun SessionListRow(
    item: SessionListItem,
    enabled: Boolean,
    closeAvailable: Boolean,
    /** A close request for this session is in flight: the row says so instead of its status. */
    closing: Boolean = false,
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
    /** When set, the time label reads as the age relative to this time ("5 minutes ago") instead of a clock time or date. */
    now: Long? = null,
    /** Marks a ready chat that has been idle for a day or more with an "Old" chip. */
    old: Boolean = false,
    /** Hides the session on this phone; null where the row offers no Hide (see [canHideSession]). */
    onHide: (() -> Unit)? = null,
    /** Shows a hidden session again; null unless the row is listed as hidden. */
    onUnhide: (() -> Unit)? = null,
    /** The row is hidden on this phone and listed only because hidden rows are shown. */
    hidden: Boolean = false,
    compactionFrame: State<Int>? = null,
    activity: ActivitySnapshot.Active? = null,
    /** What the row paints over its swipe background: the page's background, or the colour of the card it sits in. */
    containerColor: Color = MaterialTheme.colorScheme.background,
    /** A control at the row's end, such as an inline Close; it moves with the row when swiped. */
    trailing: (@Composable () -> Unit)? = null,
) {
    fun available(action: SwipeAction): SessionSwipe =
        effectiveSessionSwipe(action, closeAvailable, onRename != null, onHide != null, onUnhide != null && hidden)
    val endToStart = available(swipe.first)
    val regionChild = rememberRegionChild()
    val startToEnd = available(swipe.second)
    val title = sessionTitleLabel(item)
    val subagentLabel = stringResource(R.string.remote_subagent)
    val liveActivity = activity.takeIf { !closing && !item.lastKnown && !item.compacting && item.availability == SessionAvailability.RUNNING }
    val phaseLabel = when (liveActivity?.phase) {
        ActivityPhase.Pending -> R.string.remote_subagent_phase_pending
        ActivityPhase.Thinking -> R.string.remote_subagent_phase_thinking
        ActivityPhase.Writing -> R.string.remote_subagent_phase_writing
        ActivityPhase.Tool -> R.string.remote_subagent_phase_tool
        null -> null
    }
    val reported =
        stringResource(
            if (phaseLabel != null) phaseLabel
            else if (item.compacting) R.string.remote_status_compacting
            else when (item.availability) {
                SessionAvailability.IDLE -> R.string.remote_status_idle
                SessionAvailability.RUNNING -> R.string.remote_status_running
                SessionAvailability.WAITING -> R.string.remote_status_waiting
                SessionAvailability.OFFLINE -> R.string.remote_status_offline
            }
        )
    // A last-known status is shown muted and named as such: nothing on it is live.
    val status =
        if (closing) stringResource(R.string.remote_session_closing)
        else if (item.lastKnown) stringResource(R.string.remote_status_last_known, reported)
        else reported
    val statusColor =
        if (item.lastKnown) MaterialTheme.colorScheme.outline
        else if (liveActivity != null) activityPhaseColor(liveActivity.phase, liveActivity.thinkingLevel)
        else sessionStatusColor(item.availability)
    val copyExplanation = stringResource(R.string.remote_history_note)
    val closeLabel = stringResource(R.string.remote_session_close)
    val renameLabel = stringResource(R.string.remote_rename_session)
    val macTuiLabel = stringResource(R.string.remote_mac_tui_session)
    val workerLabel = stringResource(R.string.remote_mac_worker_session)
    val showActionsLabel = stringResource(R.string.remote_menu_show_actions)
    val copyId = rememberCopyToClipboard(stringResource(R.string.remote_menu_copy_session_id))
    val pinnedLabel = stringResource(R.string.remote_activity_pinned)
    val unreadLabel = stringResource(R.string.remote_activity_unread)
    val oldLabel = stringResource(R.string.remote_session_old)
    val hiddenLabel = stringResource(R.string.remote_session_hidden_label)
    val hideLabel = stringResource(R.string.remote_menu_hide)
    val unhideLabel = stringResource(R.string.remote_menu_unhide)
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
            canHide = onHide != null,
            hidden = hidden && onUnhide != null,
        ).map { action ->
            MenuItem(action.label, "session_${action.name.lowercase()}") {
                when (action) {
                    SessionMenuAction.OPEN -> onClick()
                    SessionMenuAction.RENAME -> onRename?.invoke()
                    SessionMenuAction.FORK_LATEST -> onForkLatest?.invoke()
                    SessionMenuAction.PIN,
                    SessionMenuAction.UNPIN -> onTogglePin?.invoke()
                    SessionMenuAction.MARK_READ -> onMarkRead?.invoke()
                    SessionMenuAction.HIDE -> onHide?.invoke()
                    SessionMenuAction.UNHIDE -> onUnhide?.invoke()
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
            hiddenLabel.takeIf { hidden },
        )
    val ticketLabel = item.ticketRef?.let { stringResource(R.string.remote_ticket_chip, it) }
    val relativeAge =
        if (now != null)
            item.updatedAt?.let {
                DateUtils.getRelativeTimeSpanString(minOf(it, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
            }
        else null
    val contextFill = contextFill(item.context)
    val contextLabel = contextFillDescription(contextFill)
    val rowDescription =
        listOfNotNull(
            subagentLabel.takeIf { item.parentSessionId != null },
            title,
            ticketLabel,
            macTuiLabel.takeIf { item.liveMacTui },
            workerLabel.takeIf { item.hostWorker },
            pinnedLabel.takeIf { item.pinned },
            hiddenLabel.takeIf { hidden },
            unreadLabel.takeIf { item.unread },
            oldLabel.takeIf { old },
            relativeAge,
            item.preview,
            status,
            contextLabel,
        ).joinToString(", ")
    SnapBackSwipeBox(
        enableStartToEnd = startToEnd != SessionSwipe.NONE,
        enableEndToStart = endToStart != SessionSwipe.NONE,
        onSwipe = { direction ->
            val action =
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> endToStart
                    SwipeToDismissBoxValue.StartToEnd -> startToEnd
                    SwipeToDismissBoxValue.Settled -> SessionSwipe.NONE
                }
            when (action) {
                SessionSwipe.CLOSE -> onClose()
                SessionSwipe.RENAME -> onRename?.invoke()
                SessionSwipe.HIDE -> onHide?.invoke()
                SessionSwipe.UNHIDE -> onUnhide?.invoke()
                SessionSwipe.NONE -> Unit
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
        Column(
            Modifier.background(containerColor)
        ) {
            // The trailing control sits in the swiped content, so it moves with the row.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier =
                        Modifier.weight(1f)
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
                                    if (onHide != null) add(CustomAccessibilityAction(hideLabel) {
                                        onHide()
                                        true
                                    })
                                    if (hidden && onUnhide != null) add(CustomAccessibilityAction(unhideLabel) {
                                        onUnhide()
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
                    Box(
                        Modifier.size(44.dp)
                            .testTag(if (item.compacting) "sessionCompactionAvatar" else "sessionStatusAvatar")
                            .clearAndSetSemantics { if (contextFill != null) contextFillShown = contextFill },
                    ) {
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
                        // The context fill rings the avatar; the badge below is drawn on top of it.
                        if (contextFill != null) ContextRing(contextFill, Modifier.matchParentSize())
                        // No backing disc. The badge straddles the avatar's edge, so half of it would sit
                        // on the row background (secondaryContainer when selected). A thin contour in the
                        // avatar's own color, following the badge's shape, keeps the badge on one known
                        // color: the status colors are picked to reach 3:1 against surfaceContainerHighest.
                        val halo = MaterialTheme.colorScheme.surfaceContainerHighest
                        val badge = liveActivity?.let { activityGlyphKind(it.phase) }
                            ?: sessionBadgeGlyph(item.availability, item.lastKnown)
                        if (item.compacting) {
                            StatusGlyph(
                                kind = StatusGlyphKind.COMPACTING,
                                color = statusColor.copy(alpha = COMPACTION_GLYPH_ALPHA),
                                modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).badgeHalo(halo),
                                frameOverride = compactionFrame,
                            )
                        } else if (badge != null) {
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
                                // The Hidden chip and the row description say it; the muted title is a cue.
                                color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
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
                                    if (relativeAge != null) relativeAge
                                    else
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
                                Text(
                                    status,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else SessionStatusLabel(
                                status,
                                if (closing) SessionAvailability.IDLE
                                else if (item.lastKnown) SessionAvailability.OFFLINE
                                else item.availability,
                                statusColor,
                                Modifier,
                            )
                            if (old)
                                ProjectCountBadge(
                                    oldLabel,
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    "sessionOld-${item.id}",
                                    Modifier,
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
                trailing?.invoke()
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
    modifier: Modifier = Modifier,
) {
    when (availability) {
        SessionAvailability.WAITING ->
            Text(
                status,
                modifier.background(waitingContainerColor(), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = onWaitingContainerColor(),
            )
        SessionAvailability.OFFLINE ->
            Text(
                status,
                modifier,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> Text(status, modifier, style = MaterialTheme.typography.labelMedium, color = statusColor)
    }
}

@Composable
private fun SessionSwipeBackground(action: SessionSwipe, atEnd: Boolean) {
    when (action) {
        SessionSwipe.HIDE ->
            SwipeActionBackground(
                icon = Icons.Outlined.VisibilityOff,
                label = stringResource(R.string.remote_swipe_action_hide),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                atEnd = atEnd,
            )
        SessionSwipe.UNHIDE ->
            SwipeActionBackground(
                icon = Icons.Outlined.Visibility,
                label = stringResource(R.string.remote_swipe_action_unhide),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                atEnd = atEnd,
            )
        SessionSwipe.CLOSE ->
            SwipeActionBackground(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.remote_swipe_action_close),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                atEnd = atEnd,
            )
        SessionSwipe.RENAME ->
            SwipeActionBackground(
                icon = Icons.Default.Edit,
                label = stringResource(R.string.remote_swipe_action_rename),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                atEnd = atEnd,
            )
        SessionSwipe.NONE -> Unit
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

/**
 * The title [item] shows: what the user typed; for a first prompt that was only an attachment, the
 * file it named; else "New session".
 */
@Composable
internal fun sessionTitleLabel(item: SessionListItem): String = sessionTitleLabel(item.title, item.titleAttachment)

/** The label for a session's raw title from the wire, which may carry attachment or quote markers. */
@Composable
internal fun rawSessionTitleLabel(raw: String): String =
    displayTitle(raw).let { sessionTitleLabel(it.text, it.attachment) }

/** The label for a title already run through [displayTitle]: [text] and the [attachment] it carried. */
@Composable
internal fun sessionTitleLabel(text: String, attachment: TitleAttachment?): String {
    if (text.isNotBlank()) return text
    if (attachment == null) return stringResource(R.string.remote_new_session)
    val name = attachment.name
    return when {
        name == null -> stringResource(R.string.remote_session_title_attachment)
        attachment.image -> stringResource(R.string.remote_session_title_image, name)
        else -> stringResource(R.string.remote_session_title_file, name)
    }
}
