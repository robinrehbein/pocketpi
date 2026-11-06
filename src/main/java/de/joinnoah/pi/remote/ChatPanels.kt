package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Date

// Standalone chat panels. The chat screen decides where each one goes; every panel hides itself
// when it has nothing to show.

// ---- Touched files ------------------------------------------------------------------------

/**
 * Git-style summary beside the composer: how many files the chat changed (or, when it changed none,
 * read) and, when its edits carry diffs, the lines they added (green) and removed (red).
 */
@Composable
internal fun TouchedFilesSummary(files: TouchedFiles, lines: TouchedLineCounts?, onClick: () -> Unit) {
    if (files.total == 0) return
    val changed = files.changed.size
    val read = files.read.size
    val description =
        listOfNotNull(
                if (changed > 0) pluralStringResource(R.plurals.remote_panel_files_changed_description, changed, changed)
                else null,
                if (read > 0) pluralStringResource(R.plurals.remote_panel_files_read_description, read, read) else null,
                lines?.let { pluralStringResource(R.plurals.remote_panel_lines_added, it.added, it.added) },
                lines?.let { pluralStringResource(R.plurals.remote_panel_lines_removed, it.removed, it.removed) },
            )
            .joinToString(", ")
    InsightPillRow(
        Modifier.testTag("touchedFilesSummary").semantics { contentDescription = description },
        onClick = onClick,
    ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            // Changed files lead; a chat that only read files says so instead of looking like edits.
            Text(
                if (changed > 0) pluralStringResource(R.plurals.remote_panel_files_changed, changed, changed)
                else pluralStringResource(R.plurals.remote_panel_files_read, read, read),
                style = MaterialTheme.typography.labelLarge,
            )
            if (lines != null) {
                Text(
                    addedLinesText(lines.added),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = diffAddedContent(),
                )
                Text(
                    removedLinesText(lines.removed),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    color = diffRemovedContent(),
                )
            }
    }
}

/** Keeps the start and the end of a long path and elides the middle. */
internal fun middleEllipsis(text: String, maxChars: Int = 40): String {
    if (text.length <= maxChars || maxChars < 5) return text
    val keep = maxChars - 1
    val head = keep / 2
    return text.take(head) + "…" + text.takeLast(keep - head)
}

@Composable
internal fun TouchedFilesSheet(
    files: TouchedFiles,
    onDismiss: () -> Unit,
    onOpen: (itemId: String) -> Unit,
    onViewChanges: (() -> Unit)? = null,
) {
    InsightSheet(title = stringResource(R.string.remote_panel_files_title), onDismiss = onDismiss) {
        if (onViewChanges != null) {
            TextButton(onClick = { onDismiss(); onViewChanges() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.insight_files_view_changes))
            }
        }
        TouchedFilesList(
            files,
            onOpen = { onOpen(it); onDismiss() },
            Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("touchedFilesSheet"),
            onOpenedInFiles = onDismiss,
            showTitle = false,
        )
    }
}

/** The touched files as an inspector tab beside the chat; a row jumps to its card and stays. */
@Composable
internal fun TouchedFilesPane(files: TouchedFiles, onOpen: (itemId: String) -> Unit, onClose: () -> Unit) {
    PaneBackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize()) {
        TouchedFilesList(
            files,
            onOpen,
            Modifier.fillMaxSize().testTag("touchedFilesPane").windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
private fun TouchedFilesList(
    files: TouchedFiles,
    onOpen: (itemId: String) -> Unit,
    modifier: Modifier,
    onOpenedInFiles: () -> Unit = {},
    showTitle: Boolean = true,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (showTitle) item {
            Text(
                stringResource(R.string.remote_panel_files_title),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        for ((title, list) in
            listOf(
                R.string.remote_panel_files_changed to files.changed,
                R.string.remote_panel_files_read to files.read,
            )) {
            if (list.isEmpty()) continue
            item(key = "header-$title") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(title),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        list.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list, key = { "$title-${it.path}" }) { file ->
                TouchedFileRow(file, onOpenedInFiles) { onOpen(file.latestItemId) }
            }
        }
    }
}

@Composable
private fun TouchedFileRow(file: TouchedFile, onOpenedInFiles: () -> Unit, onClick: () -> Unit) {
    val trimmed = file.path.trimEnd('/')
    val name = trimmed.substringAfterLast('/').ifEmpty { file.path }
    val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
    val uses = pluralStringResource(R.plurals.remote_panel_file_uses, file.count, file.count)
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("touchedFileRow")
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (parent.isNotEmpty())
                Text(
                    middleEllipsis(parent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
        }
        Box(
            Modifier.clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics { contentDescription = uses }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                file.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        // The row jumps to the message; the icon opens the file itself.
        OpenInFilesButton(file.path, null, onDone = onOpenedInFiles)
    }
}

// ---- Subagent strip -----------------------------------------------------------------------

internal fun subagentStateLabel(state: String): Int =
    when (state) {
        "waiting" -> R.string.remote_status_waiting
        "queued" -> R.string.remote_subagent_queued
        "running" -> R.string.remote_subagent_running
        "succeeded" -> R.string.remote_subagent_succeeded
        "failed" -> R.string.remote_subagent_failed
        "ended" -> R.string.remote_subagent_ended
        "offline" -> R.string.remote_status_offline
        "cancelled" -> R.string.remote_subagent_cancelled
        // An unknown state claims nothing about the outcome.
        else -> R.string.remote_subagent_ended
    }

/** Glyph per subagent state, or null for a neutral dot (ended, offline, unknown). */
internal fun subagentStateGlyph(state: String): StatusGlyphKind? =
    when (state) {
        "waiting" -> StatusGlyphKind.NEEDS_INPUT
        "running" -> StatusGlyphKind.SESSION_RUNNING
        "queued" -> StatusGlyphKind.WAITING
        "succeeded" -> StatusGlyphKind.DONE
        "failed" -> StatusGlyphKind.FAILED
        "cancelled" -> StatusGlyphKind.STOPPED
        else -> null
    }

@Composable
internal fun subagentStateColor(state: String): Color =
    when (state) {
        "waiting" -> waitingColor()
        "running" -> sessionStatusColor(SessionAvailability.RUNNING)
        "succeeded" -> idleColor()
        "failed" -> MaterialTheme.colorScheme.error
        "offline" -> sessionStatusColor(SessionAvailability.OFFLINE)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
internal fun SubagentStrip(
    strip: SubagentStrip,
    canAbort: (SubagentStripEntry) -> Boolean,
    onOpen: (SubagentStripEntry) -> Unit,
    onAbort: (SubagentStripEntry) -> Unit,
    sessionId: String? = null,
    expandedState: MutableState<Boolean> = rememberSaveable(sessionId) { mutableStateOf(false) },
    onOpenSummary: (() -> Unit)? = null,
    activity: StripActivity? = null,
) {
    var expanded by expandedState
    if (!subagentChipShown(strip, expanded)) return
    val counts = subagentChipCounts(strip)
    val needsYou = stringResource(R.string.remote_status_waiting)
    val running = pluralStringResource(R.plurals.remote_panel_subagents_running, counts.running, counts.running)
    val cachedRunning =
        pluralStringResource(R.plurals.remote_panel_subagents_running, counts.cachedRunning, counts.cachedRunning)
    val summary =
        when {
            // Needing input leads; a running count beside it stays true and is kept.
            counts.waiting > 0 -> if (counts.running > 0) "$needsYou · $running" else needsYou
            counts.running > 0 -> running
            counts.queued > 0 -> pluralStringResource(R.plurals.remote_panel_subagents_queued, counts.queued, counts.queued)
            // Only cached states: say so, never as a current request for input.
            counts.cachedWaiting > 0 -> stringResource(R.string.remote_status_last_known, needsYou)
            counts.cachedRunning > 0 -> stringResource(R.string.remote_status_last_known, cachedRunning)
            counts.cachedQueued > 0 ->
                stringResource(
                    R.string.remote_status_last_known,
                    pluralStringResource(R.plurals.remote_panel_subagents_queued, counts.cachedQueued, counts.cachedQueued),
                )
            else -> stringResource(R.string.remote_subagent_popover_title)
        }
    StatusPopover(
        summary = summary,
        icon = Icons.Outlined.SmartToy,
        expanded = if (onOpenSummary == null) expanded else false,
        onExpandedChange = { if (onOpenSummary != null) onOpenSummary() else expanded = it },
        expandDescription = stringResource(R.string.remote_panel_subagents_expand),
        collapseDescription = stringResource(R.string.remote_panel_subagents_collapse),
        title = stringResource(R.string.remote_subagent_popover_title),
        closeDescription = stringResource(R.string.remote_subagent_popover_close),
        chipTag = "subagentStrip",
        popoverTag = "subagentPopover",
    ) {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(strip.entries) { entry ->
                SubagentStripRow(entry, canAbort(entry),
                    onOpen = { expanded = false; onOpen(it) }, onAbort = onAbort, activity = stripRowActivity(activity, entry))
            }
        }
    }
}

@Composable
internal fun SubagentInsightSheet(
    strip: SubagentStrip,
    canAbort: (SubagentStripEntry) -> Boolean,
    onOpen: (SubagentStripEntry) -> Unit,
    onAbort: (SubagentStripEntry) -> Unit,
    onDismiss: () -> Unit,
    activity: StripActivity? = null,
) {
    InsightSheet(title = stringResource(R.string.remote_subagent_popover_title), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("subagentInsightSheet")) {
            items(strip.entries) { entry ->
                SubagentStripRow(entry, canAbort(entry), onOpen = { onDismiss(); onOpen(it) }, onAbort = onAbort,
                    activity = stripRowActivity(activity, entry))
            }
        }
    }
}

/**
 * The subagent chip shows while an agent runs or waits; an open overview stays until closed, even
 * once they all finish.
 */
internal fun subagentChipShown(strip: SubagentStrip, expanded: Boolean): Boolean =
    strip.entries.isNotEmpty() && (expanded || strip.running > 0 ||
        strip.entries.any { it.state == "queued" || it.state == "waiting" || (it.lastKnown && it.state == "running") })

internal data class SubagentChipCounts(
    val waiting: Int,
    val running: Int,
    val queued: Int,
    val cachedWaiting: Int,
    val cachedRunning: Int,
    val cachedQueued: Int = 0,
)

/** Current counts exclude last-known entries; those are counted apart and labelled as such. */
internal fun subagentChipCounts(strip: SubagentStrip): SubagentChipCounts {
    fun count(state: String, lastKnown: Boolean) = strip.entries.count { it.state == state && it.lastKnown == lastKnown }
    return SubagentChipCounts(
        waiting = count("waiting", false),
        running = strip.running,
        queued = count("queued", false),
        cachedWaiting = count("waiting", true),
        cachedRunning = count("running", true),
        cachedQueued = count("queued", true),
    )
}

/**
 * Shared status chip and anchored, dismissible overview used above the composer, in the touched
 * files pill's style.
 */
@Composable
internal fun StatusPopover(
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    expandDescription: String,
    collapseDescription: String,
    title: String,
    closeDescription: String,
    chipTag: String,
    popoverTag: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box {
        InsightPillRow(
            modifier = Modifier.testTag(chipTag),
            onClick = { onExpandedChange(!expanded) },
            color = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ) {
                // Expanded, the icons take the container's content color like the text.
                val iconTint = if (expanded) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    summary,
                    // A weighted child gets zero width in the horizontally scrolling insights row.
                    modifier = Modifier.widthIn(max = 200.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription =
                        if (expanded) collapseDescription else expandDescription,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
        }
        val backdrop = rememberAppBackdrop(expanded)
        if (expanded) {
            val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(margin) { SubagentPopoverPosition(margin) },
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                val scheme = MaterialTheme.colorScheme
                val shape = MaterialTheme.shapes.medium
                Surface(
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag(popoverTag),
                    shape = shape,
                    color = Color.Transparent,
                    contentColor = scheme.onSurface,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(GLASS_RIM_WIDTH, glassRimColor(scheme)),
                ) {
                    Box(Modifier.snapshotBackdrop(backdrop, scheme.surfaceContainerHigh.copy(alpha = GLASS_TINT_ALPHA), scheme.surfaceContainerHigh)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            actions()
                            IconButton(onClick = { onExpandedChange(false) }) {
                                Icon(Icons.Default.Close, closeDescription)
                            }
                        }
                        content()
                    }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun SubagentStripRow(
    entry: SubagentStripEntry,
    allowAbort: Boolean,
    onOpen: (SubagentStripEntry) -> Unit,
    onAbort: (SubagentStripEntry) -> Unit,
    /** Negotiated: the exact child session's typed state; null keeps the legacy glyph. */
    activity: StripRowActivity? = null,
) {
    val openable = entry.openable && entry.sessionId != null
    Column(Modifier.fillMaxWidth().testTag("subagentStripEntry").padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The same glass π avatar as the sessions and the chat's subagent card.
            // Decorative: the name beside it says which agent it is.
            GlassAvatar(size = 44.dp, modifier = Modifier.clearAndSetSemantics {}) {
                Text("π", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.agent ?: sessionTitleLabel(entry.title, entry.titleAttachment), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // A last-known state is muted and named as such, like the session list.
                    val live = activity?.live
                    val color = when {
                        entry.lastKnown -> MaterialTheme.colorScheme.outline
                        live != null -> activityPhaseColor(live.phase, live.thinkingLevel)
                        // Negotiated, running, no fresh typed view of this exact child: muted.
                        activity != null -> MaterialTheme.colorScheme.outline
                        else -> subagentStateColor(entry.state)
                    }
                    val glyph = when {
                        entry.lastKnown -> null
                        live != null -> activityGlyphKind(live.phase)
                        else -> subagentStateGlyph(entry.state)
                    }
                    if (glyph != null) {
                        // Last-known entries show a muted dot. Legacy: a live running child animates.
                        // Negotiated: only the child's own fresh typed view animates.
                        // The label beside it carries the meaning; the shape does not rely on color.
                        StatusGlyph(kind = glyph, color = color, modifier = Modifier.size(16.dp).testTag("subagentGlyph_$glyph"),
                            animate = activity == null || live != null)
                    } else {
                        StatusDot(color)
                    }
                    val label = stringResource(
                        when (live?.phase) {
                            ActivityPhase.Pending -> R.string.remote_subagent_phase_pending
                            ActivityPhase.Thinking -> R.string.remote_subagent_phase_thinking
                            ActivityPhase.Writing -> R.string.remote_subagent_phase_writing
                            ActivityPhase.Tool -> R.string.remote_subagent_phase_tool
                            null -> subagentStateLabel(entry.state)
                        }
                    )
                    Text(
                        if (entry.lastKnown) stringResource(R.string.remote_status_last_known, label) else label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                entry.activity?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (openable || (entry.abortable && allowAbort)) {
            // Glass pills under the text, as the sheets' other actions, instead of a divided button row.
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (openable) FloatingSurface(shape = CircleShape, blur = false) {
                    TextButton(onClick = { onOpen(entry) }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.remote_subagent_open_details))
                    }
                }
                if (entry.abortable && allowAbort) FloatingSurface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    blur = false,
                ) {
                    TextButton(onClick = { onAbort(entry) }, Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Stop, stringResource(R.string.remote_panel_subagent_abort), tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.remote_subagent_stop_label), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

// ---- Error filter -------------------------------------------------------------------------

@Composable
internal fun ErrorFilterChip(count: Int, selected: Boolean, onToggle: () -> Unit) {
    if (count == 0 && !selected) return
    InsightPillRow(
        modifier = Modifier.testTag("errorFilterChip"),
        selected = selected, onClick = onToggle,
        color = if (selected) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.remote_panel_errors_filter, count),
                style = MaterialTheme.typography.labelLarge,
            )
    }
}

// ---- Compaction ---------------------------------------------------------------------------

@Composable
internal fun CompactionBanner(compaction: SessionCompaction?, nowMillis: Long, floating: Boolean = true) {
    if (compaction == null || !compactionVisible(compaction, nowMillis)) return
    val reason =
        when (compaction.reason) {
            "manual" -> R.string.remote_panel_compaction_manual
            "threshold" -> R.string.remote_panel_compaction_threshold
            "overflow" -> R.string.remote_panel_compaction_overflow
            else -> null
        }
    BannerSurface(
        floating = floating,
        modifier = Modifier.fillMaxWidth().testTag("compactionBanner").semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Pi core's dotted compaction loader. Decorative: the text says it.
            // StatusGlyph holds a static frame while system animations are off.
            CompactionGlyph(MaterialTheme.colorScheme.onSecondaryContainer)
            Column {
                Text(stringResource(R.string.remote_panel_compacting), style = MaterialTheme.typography.labelLarge)
                reason?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * The "Context full" offer in the chat's banner slot (below an error or a running compaction, which
 * never show together with it). It names the fill level and leads with the actions of [offer]:
 * "Compact now", and "New chat with summary" in a host worker whose host offers it. "Not now" dismisses the
 * level; TalkBack users reach it as a custom action on the heading too. [announce] makes the banner a
 * polite live region, which the caller grants once per offer level.
 */
@Composable
internal fun ContextOfferBanner(
    offer: ContextOffer,
    announce: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
    floating: Boolean = true,
    onHandover: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val dismissLabel = stringResource(R.string.remote_context_offer_dismiss)
    val compact = ContextOfferAction.COMPACT in offer.actions
    val handover = ContextOfferAction.HANDOVER in offer.actions
    val body =
        when {
            compact && handover -> R.string.remote_context_full_body_both
            handover -> R.string.remote_context_full_body_handover
            else -> R.string.remote_context_full_body_compact
        }
    BannerSurface(
        floating = floating,
        modifier = Modifier.fillMaxWidth().testTag("contextOfferBanner")
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(
                Modifier.padding(end = 8.dp).testTag("contextOfferText").semantics(mergeDescendants = true) {
                    heading()
                    customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
                },
            ) {
                Text(
                    stringResource(R.string.remote_context_full_title, offer.percent),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                // The banner's own text colour: primary would fall below 4.5:1 on the translucent glass.
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("contextOfferDismiss"),
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSecondaryContainer),
                ) { Text(dismissLabel) }
                // One tap starts it: the old chat stays as it is, so nothing needs confirming.
                if (handover)
                    TextButton(
                        onClick = onHandover,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("contextOfferHandover"),
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSecondaryContainer),
                    ) { Text(stringResource(R.string.remote_handover_action)) }
                if (compact)
                    Button(
                        onClick = onCompact,
                        // Labelled "Compact now" only: the heading already reads out the fill.
                        modifier = Modifier.heightIn(min = 48.dp).testTag("contextOfferCompact"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.onSecondaryContainer,
                            contentColor = scheme.secondaryContainer,
                        ),
                    ) { Text(stringResource(R.string.remote_compact_now_action)) }
            }
        }
    }
}

/**
 * The open chat's handover in the banner slot: its progress while it runs (a polite live region, so
 * TalkBack reads "Summarizing chat…" and then "Opening new chat…"), or its outcome when the phone did
 * not move on by itself: a failure with what can be done next, or, in the old chat opened again
 * after a `done`, the way to the new chat. [requesting] shows the first step before the host's
 * first event. [onCompact] is set where "Compact now" can start, for a chat too long to summarize.
 */
@Composable
internal fun HandoverBanner(
    handover: SessionHandover?,
    requesting: Boolean,
    onRetry: () -> Unit,
    onCompact: (() -> Unit)?,
    onOpenTarget: (String) -> Unit,
    onDismiss: () -> Unit,
    floating: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val running = handover?.running == true || requesting
    val failed = handover?.state == HandoverState.FAILED
    val target = handover?.targetSessionId?.takeIf { handover.state == HandoverState.DONE }
    if (!running && !failed && target == null) return
    val container = if (failed) scheme.errorContainer else scheme.secondaryContainer
    val content = if (failed) scheme.onErrorContainer else scheme.onSecondaryContainer
    BannerSurface(
        floating = floating,
        modifier = Modifier.fillMaxWidth().testTag("handoverBanner").semantics { liveRegion = LiveRegionMode.Polite },
        color = container,
        contentColor = content,
    ) {
        if (running) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("handoverProgress").semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactionGlyph(content)
                Text(
                    stringResource(
                        if (handover?.state == HandoverState.SWITCHING) R.string.remote_handover_switching
                        else R.string.remote_handover_summarizing
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            return@BannerSurface
        }
        val error = handover?.error
        Column(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(Modifier.padding(end = 8.dp).testTag("handoverOutcome").semantics(mergeDescendants = true) { heading() }) {
                Text(
                    stringResource(
                        when {
                            target != null -> R.string.remote_handover_continued
                            error == HandoverError.TIMEOUT -> R.string.remote_handover_timeout_title
                            else -> R.string.remote_handover_failed_title
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (error != null)
                    Text(stringResource(handoverErrorMessage(error)), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("handoverDismiss"),
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                ) { Text(stringResource(R.string.remote_handover_dismiss)) }
                val primary: Pair<Int, () -> Unit>? =
                    when {
                        target != null -> R.string.remote_handover_open_new to { onOpenTarget(target) }
                        error == HandoverError.TOO_LARGE -> onCompact?.let { R.string.remote_compact_now_action to it }
                        error == HandoverError.TIMEOUT -> null
                        else -> R.string.remote_handover_retry to onRetry
                    }
                primary?.let { (label, action) ->
                    Button(
                        onClick = action,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("handoverAction"),
                        colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = container),
                    ) { Text(stringResource(label)) }
                }
            }
        }
    }
}

/** What a failed handover tells the user, by its [error]. */
internal fun handoverErrorMessage(error: HandoverError): Int =
    when (error) {
        HandoverError.BUSY -> R.string.remote_handover_failed_busy
        HandoverError.CHANGED -> R.string.remote_handover_failed_changed
        HandoverError.TOO_LARGE -> R.string.remote_handover_failed_too_large
        HandoverError.SUMMARY_FAILED -> R.string.remote_handover_failed_summary
        HandoverError.CANCELLED -> R.string.remote_handover_failed_cancelled
        HandoverError.OFFLINE -> R.string.remote_handover_failed_offline
        HandoverError.TIMEOUT -> R.string.remote_handover_failed_timeout
    }

/**
 * The new chat of a handover whose summary pi could not write (`seeded: false`): it says so, and
 * leads back to the previous chat, which still has everything.
 */
@Composable
internal fun HandoverUnseededBanner(onOpenPrevious: (() -> Unit)?, floating: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    BannerSurface(
        floating = floating,
        modifier = Modifier.fillMaxWidth().testTag("handoverUnseeded"),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.remote_handover_unseeded),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (onOpenPrevious != null)
                TextButton(
                    onClick = onOpenPrevious,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("handoverUnseededOpenPrevious"),
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSecondaryContainer),
                ) { Text(stringResource(R.string.remote_handover_open_previous)) }
        }
    }
}

/**
 * The "Continue" chip above the composer of a new chat that holds only its summary. It reads the
 * composer's text itself, so a keystroke recomposes only the chip, and hides while text is typed.
 */
@Composable
internal fun HandoverContinueChip(draft: ComposerDraft?, onContinue: () -> Unit) {
    if (draft != null && draft.text.isNotBlank()) return
    val description = stringResource(R.string.remote_handover_continue_description)
    AssistChip(
        onClick = onContinue,
        label = { Text(stringResource(R.string.remote_handover_continue)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("handoverContinue").semantics { contentDescription = description },
    )
}

/**
 * The summary a handover started the new chat with, in place of the user bubble it came as: a card
 * titled "Summary of the previous chat" whose Markdown opens on a tap, with a way back to the
 * previous chat when [onOpenPrevious] is set.
 */
@Composable
internal fun HandoverSummaryCard(text: String, partial: Boolean, onOpenPrevious: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel =
        stringResource(if (expanded) R.string.remote_handover_summary_collapse else R.string.remote_handover_summary_expand)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("handoverSummary"),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                    .testTag("handoverSummaryToggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.remote_handover_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                )
            }
            if (partial)
                Text(
                    stringResource(R.string.remote_handover_summary_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            if (expanded)
                Box(Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp).testTag("handoverSummaryText")) { MarkdownText(text) }
            if (onOpenPrevious != null)
                TextButton(
                    onClick = onOpenPrevious,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("handoverOpenPrevious"),
                ) { Text(stringResource(R.string.remote_handover_open_previous)) }
        }
    }
}

/**
 * The compaction activity mark, shared by the banner and the compact status chip: pi's braille loader
 * at a dimmed but still legible alpha of [base], 16dp, without semantics of its own.
 */
@Composable
internal fun CompactionGlyph(base: Color, modifier: Modifier = Modifier) {
    StatusGlyph(
        kind = StatusGlyphKind.COMPACTING,
        color = base.copy(alpha = COMPACTION_GLYPH_ALPHA),
        modifier = modifier.size(16.dp).testTag("compactionGlyph"),
    )
}

/**
 * Muted compaction color. ChatPanelsTest checks 3:1 against the opaque containers of the
 * built-in schemes; over translucent glass the result depends on the content behind it.
 */
internal const val COMPACTION_GLYPH_ALPHA = 0.78f

// ---- Child session picker -----------------------------------------------------------------

@Composable
internal fun ChildSessionPickerSheet(
    candidates: List<SessionListItem>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    GlassSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag("childSessionPicker").navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                SheetHeader(
                    stringResource(R.string.remote_panel_child_picker_title),
                    subtitle = stringResource(R.string.remote_panel_child_picker_hint),
                )
            }
            itemsIndexed(candidates, key = { _, candidate -> candidate.id }) { index, session ->
                val reported =
                    stringResource(
                        when (session.availability) {
                            SessionAvailability.IDLE -> R.string.remote_status_idle
                            SessionAvailability.RUNNING -> R.string.remote_status_running
                            SessionAvailability.WAITING -> R.string.remote_status_waiting
                            SessionAvailability.OFFLINE -> R.string.remote_status_offline
                        }
                    )
                val status =
                    if (session.lastKnown) stringResource(R.string.remote_status_last_known, reported) else reported
                val updated =
                    session.updatedAt?.let {
                        (if (DateUtils.isToday(it)) DateFormat.getTimeFormat(context)
                            else DateFormat.getDateFormat(context))
                            .format(Date(it))
                    }
                SheetGroupRow(first = index == 0, last = index == candidates.lastIndex) {
                ListItem(
                    colors = sheetListItemColors(),
                    headlineContent = { Text(sessionTitleLabel(session), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(status, updated).joinToString(" · "), maxLines = 1)
                    },
                    leadingContent = {
                        // Same rule as the session list: only a fresh report gets a glyph.
                        val glyph = sessionBadgeGlyph(session.availability, session.lastKnown)
                        val color =
                            if (session.lastKnown) MaterialTheme.colorScheme.outline
                            else sessionStatusColor(session.availability)
                        if (glyph != null) {
                            StatusGlyph(
                                kind = glyph,
                                color = color,
                                modifier = Modifier.size(16.dp).testTag("candidateGlyph_$glyph"),
                            )
                        } else {
                            StatusDot(color)
                        }
                    },
                    modifier =
                        Modifier.clickable(role = Role.Button) {
                                onPick(session.id)
                                onDismiss()
                            }
                            .testTag("childSessionCandidate"),
                )
                }
            }
        }
    }
}

// ---- Usage totals -------------------------------------------------------------------------

@Composable
internal fun UsageSummary(totals: SessionUsageTotals) {
    val locale = LocalConfiguration.current.locales[0]
    val rows =
        listOf(
            R.string.remote_panel_usage_input to formatTokenCount(totals.input, locale),
            R.string.remote_panel_usage_output to formatTokenCount(totals.output, locale),
            R.string.remote_panel_usage_cache_read to formatTokenCount(totals.cacheRead, locale),
            R.string.remote_panel_usage_cache_write to formatTokenCount(totals.cacheWrite, locale),
            R.string.remote_panel_usage_total to formatTokenCount(totals.totalTokens, locale),
            R.string.remote_panel_usage_cost to formatCost(totals.cost, locale),
        )
    Column(Modifier.fillMaxWidth().testTag("usageSummary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.remote_panel_usage_title), style = MaterialTheme.typography.titleSmall)
        for ((label, value) in rows)
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
    }
}


/** Anchors the panel above the pill, clamped inside the visible window. */
internal class SubagentPopoverPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(desiredX.coerceIn(margin, maxX), (anchorBounds.top - popupContentSize.height - margin).coerceIn(margin, maxY))
    }
}
