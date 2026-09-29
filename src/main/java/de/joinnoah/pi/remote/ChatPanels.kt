package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

// Standalone chat panels. The chat screen decides where each one goes; every panel hides itself
// when it has nothing to show.

// ---- Touched files ------------------------------------------------------------------------

/**
 * Git-style summary beside the composer: how many files the chat touched and, when its edits
 * carry diffs, the lines they added (green) and removed (red).
 */
@Composable
internal fun TouchedFilesSummary(files: TouchedFiles, lines: TouchedLineCounts?, onClick: () -> Unit) {
    if (files.total == 0) return
    val description =
        listOfNotNull(
                pluralStringResource(R.plurals.remote_panel_files_touched, files.total, files.total),
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
            Text(
                pluralStringResource(R.plurals.remote_panel_files_count, files.total, files.total),
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
internal fun TouchedFilesSheet(files: TouchedFiles, onDismiss: () -> Unit, onOpen: (itemId: String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag("touchedFilesSheet").navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
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
                    TouchedFileRow(file) {
                        onOpen(file.latestItemId)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchedFileRow(file: TouchedFile, onClick: () -> Unit) {
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
    }
}

// ---- Subagent strip -----------------------------------------------------------------------

internal fun subagentStateLabel(state: String): Int =
    when (state) {
        "queued" -> R.string.remote_subagent_queued
        "running" -> R.string.remote_subagent_running
        "succeeded" -> R.string.remote_subagent_succeeded
        "failed" -> R.string.remote_subagent_failed
        else -> R.string.remote_subagent_cancelled
    }

@Composable
internal fun SubagentStrip(
    strip: SubagentStrip,
    canAbort: (SubagentStripEntry) -> Boolean,
    onOpen: (SubagentStripEntry) -> Unit,
    onAbort: (SubagentStripEntry) -> Unit,
) {
    if (strip.entries.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary =
        if (strip.running > 0)
            pluralStringResource(R.plurals.remote_panel_subagents_running, strip.running, strip.running)
        else pluralStringResource(R.plurals.remote_panel_subagents_queued, strip.entries.size, strip.entries.size)
    Box {
        FloatingSurface(shape = CircleShape) {
            Row(
                Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    .testTag("subagentStrip")
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(
                    sessionStatusColor(
                        if (strip.running > 0) SessionAvailability.RUNNING else SessionAvailability.IDLE
                    )
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.remote_panel_subagents_collapse
                            else R.string.remote_panel_subagents_expand
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).heightIn(max = 280.dp),
        ) {
            for (entry in strip.entries)
                SubagentStripRow(
                    entry,
                    canAbort(entry),
                    onOpen = onOpen,
                    onAbort = onAbort,
                )
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
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (openable) Modifier.clickable(role = Role.Button) { onOpen(entry) } else Modifier)
            .testTag("subagentStripEntry")
            .padding(start = 28.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.agent ?: entry.title,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(subagentStateLabel(entry.state)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.activity?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.abortable && allowAbort)
            IconButton(onClick = { onAbort(entry) }) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = stringResource(R.string.remote_panel_subagent_abort),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
    }
}

// ---- Timeline rail ------------------------------------------------------------------------

private val RAIL_INSET = 8.dp

/** How far from a marker a tap on the rail still jumps to it. */
private val RAIL_TAP_SLOP = 24.dp

/** With fewer markers the rail says nothing a glance at the list would not. */
internal const val MIN_TIMELINE_MARKERS = 2

internal fun timelineRailVisible(markers: List<TimelineMarker>): Boolean = markers.size >= MIN_TIMELINE_MARKERS

/** The marker closest to [fraction] (0..1 along the rail). */
internal fun nearestMarker(markers: List<TimelineMarker>, fraction: Float): TimelineMarker? =
    markers.minByOrNull { abs(it.position - fraction) }

internal fun timelineMarkerLabel(kind: TimelineMarkerKind): Int =
    when (kind) {
        TimelineMarkerKind.ERROR -> R.string.remote_panel_marker_error
        TimelineMarkerKind.QUESTION -> R.string.remote_panel_marker_question
        TimelineMarkerKind.EDIT -> R.string.remote_panel_marker_edit
        TimelineMarkerKind.PLAN -> R.string.remote_panel_marker_plan
    }

private fun timelineMarkerIcon(kind: TimelineMarkerKind): ImageVector =
    when (kind) {
        TimelineMarkerKind.ERROR -> Icons.Default.Warning
        TimelineMarkerKind.QUESTION -> Icons.Default.QuestionMark
        TimelineMarkerKind.EDIT -> Icons.Default.Edit
        TimelineMarkerKind.PLAN -> Icons.AutoMirrored.Filled.FormatListBulleted
    }

/** A marker's badge: the kind's icon on its colour, so the kinds differ by more than colour. */
@Composable
private fun TimelineMarkerBadge(kind: TimelineMarkerKind, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when (kind) {
            TimelineMarkerKind.ERROR -> colors.error to colors.onError
            TimelineMarkerKind.QUESTION -> colors.tertiary to colors.onTertiary
            TimelineMarkerKind.EDIT -> colors.primary to colors.onPrimary
            TimelineMarkerKind.PLAN -> colors.secondary to colors.onSecondary
        }
    Box(modifier.size(16.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(timelineMarkerIcon(kind), contentDescription = null, tint = content, modifier = Modifier.size(11.dp))
    }
}

@Composable
internal fun TimelineRail(
    markers: List<TimelineMarker>,
    onJump: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollableState? = null,
    reverseLayout: Boolean = false,
) {
    if (!timelineRailVisible(markers)) return
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val jumpLabels =
        mapOf(
            TimelineMarkerKind.ERROR to stringResource(R.string.remote_panel_jump_error),
            TimelineMarkerKind.QUESTION to stringResource(R.string.remote_panel_jump_question),
            TimelineMarkerKind.EDIT to stringResource(R.string.remote_panel_jump_edit),
            TimelineMarkerKind.PLAN to stringResource(R.string.remote_panel_jump_plan),
        )
    val markerLabels = TimelineMarkerKind.entries.associateWith { stringResource(timelineMarkerLabel(it)) }
    val railDescription = stringResource(R.string.remote_panel_timeline)
    val latest = TimelineMarkerKind.entries.mapNotNull { kind -> markers.lastOrNull { it.kind == kind } }
    val currentMarkers by rememberUpdatedState(markers)
    val currentJump by rememberUpdatedState(onJump)
    val inset = with(LocalDensity.current) { RAIL_INSET.toPx() }
    val tapSlop = with(LocalDensity.current) { RAIL_TAP_SLOP.toPx() }
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, reverseLayout)
    // Where a long press opened the legend, or null while it is closed.
    var legendAt by remember { mutableStateOf<Float?>(null) }
    Box(
        modifier
            .width(48.dp)
            .testTag("timelineRail")
            .semantics {
                contentDescription = railDescription
                customActions =
                    latest.map { marker ->
                        CustomAccessibilityAction(jumpLabels.getValue(marker.kind)) {
                            currentJump(marker.itemId)
                            true
                        }
                    }
            }
            // The rail lies over the list's right edge: a drag there scrolls the list, only a
            // tap close to a marker jumps to it, and a long press explains the markers.
            .then(
                if (scrollState != null)
                    Modifier.scrollable(scrollState, Orientation.Vertical, reverseDirection = reverse)
                else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset -> legendAt = offset.y },
                    onTap = { offset ->
                        val span = (size.height - 2 * inset).coerceAtLeast(0f)
                        val fraction = if (span <= 0f) 0f else ((offset.y - inset) / span).coerceIn(0f, 1f)
                        val marker = nearestMarker(currentMarkers, fraction) ?: return@detectTapGestures
                        val y = inset + marker.position.coerceIn(0f, 1f) * span
                        if (abs(offset.y - y) <= tapSlop) currentJump(marker.itemId)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(12.dp).fillMaxHeight()) {
            val trackWidth = 2.dp.toPx()
            drawRoundRect(
                trackColor,
                topLeft = Offset((size.width - trackWidth) / 2, inset),
                size = Size(trackWidth, (size.height - 2 * inset).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
        }
        Layout(
            content = {
                for (marker in markers) {
                    val label = markerLabels.getValue(marker.kind)
                    TimelineMarkerBadge(
                        marker.kind,
                        Modifier.testTag("timelineMarker").semantics {
                            contentDescription = label
                            onClick(label = jumpLabels.getValue(marker.kind)) {
                                currentJump(marker.itemId)
                                true
                            }
                        },
                    )
                }
            },
            modifier = Modifier.matchParentSize(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val span = (constraints.maxHeight - 2 * inset).coerceAtLeast(0f)
                placeables.forEachIndexed { index, placeable ->
                    val y = inset + markers[index].position.coerceIn(0f, 1f) * span
                    placeable.place(
                        (constraints.maxWidth - placeable.width) / 2,
                        (y - placeable.height / 2f).roundToInt(),
                    )
                }
            }
        }
        legendAt?.let { y ->
            TimelineLegend(
                offset = IntOffset(-with(LocalDensity.current) { 48.dp.roundToPx() }, y.roundToInt()),
                labels = markerLabels,
                onDismiss = { legendAt = null },
            )
        }
    }
}

/** The four marker kinds, opened beside the rail by a long press. */
@Composable
private fun TimelineLegend(offset: IntOffset, labels: Map<TimelineMarkerKind, String>, onDismiss: () -> Unit) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            modifier = Modifier.testTag("timelineLegend").clickable(onClick = onDismiss),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.remote_panel_timeline_legend),
                    style = MaterialTheme.typography.labelLarge,
                )
                for (kind in TimelineMarkerKind.entries)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TimelineMarkerBadge(kind)
                        Text(labels.getValue(kind), style = MaterialTheme.typography.bodyMedium)
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
        shape = RoundedCornerShape(12.dp),
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
