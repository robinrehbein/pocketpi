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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.abs

// Standalone chat panels. The chat screen decides where each one goes; every panel hides itself
// when it has nothing to show.

// ---- Touched files ------------------------------------------------------------------------

@Composable
internal fun TouchedFilesChip(files: TouchedFiles, onClick: () -> Unit) {
    if (files.total == 0) return
    val description =
        pluralStringResource(R.plurals.remote_panel_files_touched, files.total, files.total)
    AssistChip(
        onClick = onClick,
        shape = CircleShape,
        border = null,
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        leadingIcon = {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        label = { Text(files.total.toString()) },
        modifier = Modifier.testTag("touchedFilesChip").semantics { contentDescription = description },
    )
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
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("subagentStrip"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            if (expanded)
                for (entry in strip.entries) SubagentStripRow(entry, canAbort(entry), onOpen, onAbort)
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

/** The marker closest to [fraction] (0..1 along the rail). */
internal fun nearestMarker(markers: List<TimelineMarker>, fraction: Float): TimelineMarker? =
    markers.minByOrNull { abs(it.position - fraction) }

@Composable
internal fun TimelineRail(
    markers: List<TimelineMarker>,
    onJump: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollableState? = null,
    reverseLayout: Boolean = false,
) {
    if (markers.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val trackColor = colors.outlineVariant
    fun colorOf(kind: TimelineMarkerKind): Color =
        when (kind) {
            TimelineMarkerKind.ERROR -> colors.error
            TimelineMarkerKind.QUESTION -> colors.tertiary
            TimelineMarkerKind.EDIT -> colors.primary
            TimelineMarkerKind.PLAN -> colors.secondary
        }
    val labels =
        mapOf(
            TimelineMarkerKind.ERROR to stringResource(R.string.remote_panel_jump_error),
            TimelineMarkerKind.QUESTION to stringResource(R.string.remote_panel_jump_question),
            TimelineMarkerKind.EDIT to stringResource(R.string.remote_panel_jump_edit),
            TimelineMarkerKind.PLAN to stringResource(R.string.remote_panel_jump_plan),
        )
    val railDescription = stringResource(R.string.remote_panel_timeline)
    val latest = TimelineMarkerKind.entries.mapNotNull { kind -> markers.lastOrNull { it.kind == kind } }
    val currentMarkers by rememberUpdatedState(markers)
    val currentJump by rememberUpdatedState(onJump)
    val inset = with(LocalDensity.current) { RAIL_INSET.toPx() }
    val tapSlop = with(LocalDensity.current) { RAIL_TAP_SLOP.toPx() }
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, reverseLayout)
    Box(
        modifier
            .width(48.dp)
            .testTag("timelineRail")
            .semantics {
                contentDescription = railDescription
                customActions =
                    latest.map { marker ->
                        CustomAccessibilityAction(labels.getValue(marker.kind)) {
                            currentJump(marker.itemId)
                            true
                        }
                    }
            }
            // The rail lies over the list's right edge: a drag there scrolls the list, and only a
            // tap close to a marker jumps to it.
            .then(
                if (scrollState != null)
                    Modifier.scrollable(scrollState, Orientation.Vertical, reverseDirection = reverse)
                else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val span = (size.height - 2 * inset).coerceAtLeast(0f)
                    val fraction = if (span <= 0f) 0f else ((offset.y - inset) / span).coerceIn(0f, 1f)
                    val marker = nearestMarker(currentMarkers, fraction) ?: return@detectTapGestures
                    val y = inset + marker.position.coerceIn(0f, 1f) * span
                    if (abs(offset.y - y) <= tapSlop) currentJump(marker.itemId)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(12.dp).fillMaxHeight()) {
            val span = size.height - 2 * inset
            val trackWidth = 2.dp.toPx()
            drawRoundRect(
                trackColor,
                topLeft = Offset((size.width - trackWidth) / 2, inset),
                size = Size(trackWidth, span.coerceAtLeast(0f)),
                cornerRadius = CornerRadius(trackWidth / 2),
            )
            val radius = 4.dp.toPx()
            for (marker in markers) {
                val y = inset + marker.position.coerceIn(0f, 1f) * span.coerceAtLeast(0f)
                drawCircle(colorOf(marker.kind), radius, Offset(size.width / 2, y))
            }
        }
    }
}

// ---- Error filter -------------------------------------------------------------------------

@Composable
internal fun ErrorFilterChip(count: Int, selected: Boolean, onToggle: () -> Unit) {
    if (count == 0 && !selected) return
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(stringResource(R.string.remote_panel_errors_filter, count)) },
        leadingIcon = {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        modifier = Modifier.testTag("errorFilterChip"),
    )
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
        modifier = Modifier.fillMaxWidth().testTag("compactionBanner"),
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
