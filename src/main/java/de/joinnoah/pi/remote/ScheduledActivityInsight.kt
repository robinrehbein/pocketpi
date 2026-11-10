package de.joinnoah.pi.remote

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import java.util.Date

// Presentation only. These types deliberately do not define a host or wire contract.
internal enum class ScheduledActivityKind { WAKEUP, LOOP, GOAL }
internal enum class ScheduledActivityPhase { AFTER_TURN, SCHEDULED, CONTINUING, HOLDING, PAUSED }
internal data class ScheduledActivityItem(
    val id: String,
    val kind: ScheduledActivityKind,
    val phase: ScheduledActivityPhase,
    val purpose: String? = null,
    val dueAt: Long? = null,
    val delayMs: Long? = null,
)
internal data class ScheduledActivityPresentation(
    val items: List<ScheduledActivityItem> = emptyList(),
    val stale: Boolean = false,
    val stoppingIds: Set<String> = emptySet(),
    val stopFailedIds: Set<String> = emptySet(),
)

internal enum class ScheduledActivityLabel {
    EMPTY, STALE, STOPPING, MULTIPLE, CHECK_IN, WAITING_FOR_STATUS, AFTER_TURN, CONTINUING, HOLDING, PAUSED,
}
internal data class ScheduledActivityLabelSelection(
    val category: ScheduledActivityLabel,
    val minutes: Long? = null,
)

internal fun scheduledActivityLabel(
    presentation: ScheduledActivityPresentation,
    now: Long,
): ScheduledActivityLabelSelection {
    val category = when {
        presentation.items.isEmpty() -> ScheduledActivityLabel.EMPTY
        presentation.stale -> ScheduledActivityLabel.STALE
        presentation.items.any { it.id in presentation.stoppingIds } -> ScheduledActivityLabel.STOPPING
        presentation.items.size > 1 -> ScheduledActivityLabel.MULTIPLE
        else -> {
            val item = presentation.items.single()
            when (item.phase) {
                ScheduledActivityPhase.AFTER_TURN -> ScheduledActivityLabel.AFTER_TURN
                ScheduledActivityPhase.CONTINUING -> ScheduledActivityLabel.CONTINUING
                ScheduledActivityPhase.HOLDING -> ScheduledActivityLabel.HOLDING
                ScheduledActivityPhase.PAUSED -> ScheduledActivityLabel.PAUSED
                ScheduledActivityPhase.SCHEDULED -> {
                    val dueAt = item.dueAt
                    if (dueAt != null && dueAt > now) {
                        // Round up without adding to the timestamp (or depending on a live clock).
                        val remaining = dueAt.toDouble() - now.toDouble()
                        return ScheduledActivityLabelSelection(
                            ScheduledActivityLabel.CHECK_IN,
                            kotlin.math.ceil(remaining / 60_000.0).toLong().coerceAtLeast(1),
                        )
                    }
                    ScheduledActivityLabel.WAITING_FOR_STATUS
                }
            }
        }
    }
    return ScheduledActivityLabelSelection(category)
}

internal enum class ScheduledActivityStopState { AVAILABLE, PENDING, FAILED, DISABLED }
internal fun scheduledActivityStopState(
    presentation: ScheduledActivityPresentation,
    id: String,
): ScheduledActivityStopState = when {
    presentation.stale -> ScheduledActivityStopState.DISABLED
    id in presentation.stoppingIds -> ScheduledActivityStopState.PENDING
    id in presentation.stopFailedIds -> ScheduledActivityStopState.FAILED
    else -> ScheduledActivityStopState.AVAILABLE
}

// One shared, subscription-bound clock for all visible pills/sheets; never a timer per row.
// No clock is read during static initialization. No countdown is inferred from delayMs:
// a delay without an authoritative anchor cannot establish a due time.
private val scheduledMinuteTicks by lazy {
    flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(60_000L - Math.floorMod(now, 60_000L))
        }
    }.shareIn(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
        replay = 1,
    )
}

@Composable
private fun scheduledActivityNow(enabled: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(initialValue = System.currentTimeMillis(), enabled, lifecycle) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            scheduledMinuteTicks.collect { value = it }
        }
    }
    return now
}

@Composable
private fun scheduledActivityLabelText(selection: ScheduledActivityLabelSelection, count: Int): String =
    when (selection.category) {
        ScheduledActivityLabel.EMPTY -> stringResource(R.string.scheduled_activity_empty)
        ScheduledActivityLabel.STALE -> stringResource(R.string.scheduled_activity_stale)
        ScheduledActivityLabel.STOPPING -> stringResource(R.string.scheduled_activity_stopping)
        ScheduledActivityLabel.MULTIPLE -> stringResource(R.string.scheduled_activity_count, count)
        ScheduledActivityLabel.CHECK_IN -> stringResource(R.string.scheduled_activity_check_in, selection.minutes ?: 1)
        ScheduledActivityLabel.WAITING_FOR_STATUS -> stringResource(R.string.scheduled_activity_waiting_status)
        ScheduledActivityLabel.AFTER_TURN -> stringResource(R.string.scheduled_activity_after_turn)
        ScheduledActivityLabel.CONTINUING -> stringResource(R.string.scheduled_activity_continuing)
        ScheduledActivityLabel.HOLDING -> stringResource(R.string.scheduled_activity_holding)
        ScheduledActivityLabel.PAUSED -> stringResource(R.string.scheduled_activity_paused)
    }

@Composable
internal fun ScheduledActivityPill(presentation: ScheduledActivityPresentation, onClick: () -> Unit) {
    if (presentation.items.isEmpty()) return
    val now = scheduledActivityNow(!presentation.stale && presentation.items.any { it.phase == ScheduledActivityPhase.SCHEDULED })
    val label = scheduledActivityLabelText(scheduledActivityLabel(presentation, now), presentation.items.size)
    InsightPillRow(Modifier.testTag("scheduledActivityPill").semantics { contentDescription = label }, onClick = onClick) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
internal fun ScheduledActivitySheet(
    presentation: ScheduledActivityPresentation,
    sessionTitle: String?,
    onStop: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = scheduledActivityNow(!presentation.stale && presentation.items.any { it.phase == ScheduledActivityPhase.SCHEDULED })
    val context = LocalContext.current
    InsightSheet(title = stringResource(R.string.scheduled_activity_title), subtitle = sessionTitle, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("scheduledActivitySheet")) {
            Text(stringResource(R.string.scheduled_activity_stop_note), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp))
            if (presentation.stale) {
                Text(stringResource(R.string.scheduled_activity_stale), color = MaterialTheme.colorScheme.error)
            }
            if (presentation.items.isEmpty()) Text(stringResource(R.string.scheduled_activity_empty))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(presentation.items, key = { it.id }) { item ->
                    val purpose = item.purpose?.takeIf { it.isNotBlank() } ?: stringResource(when (item.kind) {
                        ScheduledActivityKind.WAKEUP -> R.string.scheduled_activity_wakeup
                        ScheduledActivityKind.LOOP -> R.string.scheduled_activity_loop
                        ScheduledActivityKind.GOAL -> R.string.scheduled_activity_goal
                    })
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(purpose, style = MaterialTheme.typography.bodyLarge)
                        Text(scheduledActivityLabelText(scheduledActivityLabel(
                            ScheduledActivityPresentation(listOf(item), stale = presentation.stale), now,
                        ), 1), style = MaterialTheme.typography.bodyMedium)
                        item.dueAt?.let { dueAt ->
                            // Include the date to distinguish tomorrow's check from today's.
                            val date = Date(dueAt)
                            val absolute = DateFormat.getDateFormat(context).format(date) + " " +
                                DateFormat.getTimeFormat(context).format(date)
                            Text(stringResource(R.string.scheduled_activity_next_time, absolute),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        val stopState = scheduledActivityStopState(presentation, item.id)
                        if (stopState == ScheduledActivityStopState.FAILED) {
                            Text(stringResource(R.string.scheduled_activity_stop_failed),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        val stopLabel = stringResource(when (stopState) {
                            ScheduledActivityStopState.PENDING -> R.string.scheduled_activity_stopping
                            ScheduledActivityStopState.FAILED -> R.string.scheduled_activity_retry_stop
                            else -> R.string.scheduled_activity_stop
                        })
                        val stopDescription = stringResource(R.string.scheduled_activity_stop_description, stopLabel, purpose)
                        TextButton(
                            onClick = { onStop(item.id) },
                            enabled = stopState == ScheduledActivityStopState.AVAILABLE || stopState == ScheduledActivityStopState.FAILED,
                            modifier = Modifier.testTag("scheduledActivityStop:${item.id}").semantics { contentDescription = stopDescription },
                        ) { Text(stopLabel) }
                    }
                }
            }
        }
    }
}
