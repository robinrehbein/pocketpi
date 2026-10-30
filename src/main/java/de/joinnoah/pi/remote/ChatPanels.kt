package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
    FloatingSurface(shape = CircleShape) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onClick)
                .testTag("touchedFilesSummary")
                .semantics { contentDescription = description }
                .minimumInteractiveComponentSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
        "queued" -> R.string.remote_subagent_queued
        "running" -> R.string.remote_subagent_running
        "succeeded" -> R.string.remote_subagent_succeeded
        "failed" -> R.string.remote_subagent_failed
        "ended" -> R.string.remote_subagent_ended
        "offline" -> R.string.remote_status_offline
        else -> R.string.remote_subagent_cancelled
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
) {
    var expanded by expandedState
    if (!subagentChipShown(strip, expanded)) return
    val queued = strip.entries.count { it.state == "queued" }
    val summary =
        when {
            strip.running > 0 ->
                pluralStringResource(R.plurals.remote_panel_subagents_running, strip.running, strip.running)
            queued > 0 -> pluralStringResource(R.plurals.remote_panel_subagents_queued, queued, queued)
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
                    onOpen = { expanded = false; onOpen(it) }, onAbort = onAbort)
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
) {
    InsightSheet(title = stringResource(R.string.remote_subagent_popover_title), onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("subagentInsightSheet")) {
            items(strip.entries) { entry ->
                SubagentStripRow(entry, canAbort(entry), onOpen = { onDismiss(); onOpen(it) }, onAbort = onAbort)
            }
        }
    }
}

/**
 * The subagent chip shows while an agent runs or waits; an open overview stays until closed, even
 * once they all finish.
 */
internal fun subagentChipShown(strip: SubagentStrip, expanded: Boolean): Boolean =
    strip.entries.isNotEmpty() && (expanded || strip.running > 0 || strip.entries.any { it.state == "queued" })

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
        FloatingSurface(
            shape = CircleShape,
            color = if (expanded) MaterialTheme.colorScheme.primaryContainer else floatingHeaderColor(),
        ) {
            Row(
                Modifier.clickable(role = Role.Button) { onExpandedChange(!expanded) }
                    .testTag(chipTag)
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription =
                        if (expanded) collapseDescription else expandDescription,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded) {
            val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(margin) { SubagentPopoverPosition(margin) },
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag(popoverTag),
                ) {
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

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun SubagentStripRow(
    entry: SubagentStripEntry,
    allowAbort: Boolean,
    onOpen: (SubagentStripEntry) -> Unit,
    onAbort: (SubagentStripEntry) -> Unit,
) {
    val openable = entry.openable && entry.sessionId != null
    Column(Modifier.fillMaxWidth().testTag("subagentStripEntry").padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Icon(Icons.Default.Terminal, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.agent ?: entry.title, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(sessionStatusColor(when (entry.state) {
                        "running" -> SessionAvailability.RUNNING
                        "offline" -> SessionAvailability.OFFLINE
                        else -> SessionAvailability.IDLE
                    }))
                    Text(stringResource(subagentStateLabel(entry.state)), style = MaterialTheme.typography.labelMedium)
                }
                entry.activity?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (openable || (entry.abortable && allowAbort)) {
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (openable) TextButton(onClick = { onOpen(entry) }) {
                    Text(stringResource(R.string.remote_subagent_open_details))
                }
                if (entry.abortable && allowAbort) TextButton(onClick = { onAbort(entry) }) {
                    Icon(Icons.Default.Stop, stringResource(R.string.remote_panel_subagent_abort), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.remote_subagent_stop_label), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---- Error filter -------------------------------------------------------------------------

@Composable
internal fun ErrorFilterChip(count: Int, selected: Boolean, onToggle: () -> Unit) {
    if (count == 0 && !selected) return
    FloatingSurface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.errorContainer else floatingHeaderColor(),
    ) {
        Row(
            Modifier.selectable(selected = selected, role = Role.Button, onClick = onToggle)
                .testTag("errorFilterChip")
                .minimumInteractiveComponentSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
}

// ---- Compaction ---------------------------------------------------------------------------

@Composable
internal fun CompactionBanner(compaction: SessionCompaction?, nowMillis: Long) {
    if (compaction == null || !compactionVisible(compaction, nowMillis)) return
    val reason =
        when (compaction.reason) {
            "manual" -> R.string.remote_panel_compaction_manual
            "threshold" -> R.string.remote_panel_compaction_threshold
            "overflow" -> R.string.remote_panel_compaction_overflow
            else -> null
        }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("compactionBanner").semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column {
                Text(stringResource(R.string.remote_panel_compacting), style = MaterialTheme.typography.labelLarge)
                reason?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---- Child session picker -----------------------------------------------------------------

@Composable
internal fun ChildSessionPickerSheet(
    candidates: List<SessionListItem>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag("childSessionPicker").navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.remote_panel_child_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.remote_panel_child_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(candidates, key = { it.id }) { session ->
                val status =
                    stringResource(
                        when (session.availability) {
                            SessionAvailability.IDLE -> R.string.remote_status_idle
                            SessionAvailability.RUNNING -> R.string.remote_status_running
                            SessionAvailability.WAITING -> R.string.remote_status_waiting
                            SessionAvailability.OFFLINE -> R.string.remote_status_offline
                        }
                    )
                val updated =
                    session.updatedAt?.let {
                        (if (DateUtils.isToday(it)) DateFormat.getTimeFormat(context)
                            else DateFormat.getDateFormat(context))
                            .format(Date(it))
                    }
                ListItem(
                    headlineContent = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(status, updated).joinToString(" · "), maxLines = 1)
                    },
                    leadingContent = { StatusDot(sessionStatusColor(session.availability)) },
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
