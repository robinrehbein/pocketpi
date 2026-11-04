package de.joinnoah.pi.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration

internal fun taskInsightStatusIcon(stale: Boolean, active: Int, completed: Int, total: Int): ImageVector =
    when {
        stale -> Icons.Default.Warning
        active > 0 -> Icons.Default.PlayCircle
        completed == total && total > 0 -> Icons.Default.CheckCircle
        else -> Icons.Outlined.RadioButtonUnchecked
    }

@Composable
internal fun TaskInsightPill(tasks: SessionTasks, onClick: () -> Unit) {
    if (tasks.items.isEmpty() || tasks.freshness !in setOf(TaskFreshness.LIVE, TaskFreshness.STALE)) return
    val completed = tasks.items.count { it.status == TaskStatus.COMPLETED }
    val active = tasks.items.count { it.status == TaskStatus.IN_PROGRESS }
    val stale = tasks.freshness == TaskFreshness.STALE
    val description = stringResource(R.string.task_insight_description, completed, tasks.items.size, active) +
        if (stale) ", " + stringResource(R.string.task_insight_stale) else ""
    InsightPillRow(
        Modifier.testTag("taskInsightPill").semantics { contentDescription = description },
        onClick = onClick,
    ) {
            Icon(
                taskInsightStatusIcon(stale, active, completed, tasks.items.size),
                contentDescription = null,
                modifier = Modifier.size(18.dp).testTag("taskInsightStatusIcon"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.task_insight_count, completed, tasks.items.size),
                style = MaterialTheme.typography.labelLarge,
            )
    }
}

internal fun taskGlyphColor(status: TaskStatus, neutral: Color, active: Color, success: Color): Color =
    when (status) {
        TaskStatus.PENDING -> neutral
        TaskStatus.IN_PROGRESS -> active
        TaskStatus.COMPLETED -> success
    }

@Composable
internal fun TaskInsightSheet(tasks: SessionTasks, sessionTitle: String?, onDismiss: () -> Unit) {
    val completed = tasks.items.count { it.status == TaskStatus.COMPLETED }
    InsightSheet(title = stringResource(R.string.task_insight_title), subtitle = sessionTitle, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("taskInsightSheet")) {
            when (tasks.freshness) {
                TaskFreshness.STALE -> Text(stringResource(R.string.task_insight_stale), color = MaterialTheme.colorScheme.error)
                TaskFreshness.UNAVAILABLE, TaskFreshness.UNKNOWN -> Text(stringResource(R.string.task_insight_unavailable))
                TaskFreshness.LIVE -> Unit
            }
            if (tasks.items.isEmpty()) {
                if (tasks.freshness == TaskFreshness.LIVE || tasks.freshness == TaskFreshness.STALE) {
                    Text(stringResource(R.string.task_insight_empty), modifier = Modifier.padding(vertical = 16.dp))
                }
            } else {
                Text(stringResource(R.string.task_insight_progress, completed, tasks.items.size), modifier = Modifier.padding(vertical = 12.dp))
                LinearProgressIndicator(
                    progress = { completed.toFloat() / tasks.items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 480.dp).fillMaxWidth()) {
                    items(tasks.items, key = { it.id }) { task ->
                        val status = stringResource(when (task.status) {
                            TaskStatus.PENDING -> R.string.task_insight_pending
                            TaskStatus.IN_PROGRESS -> R.string.task_insight_in_progress
                            TaskStatus.COMPLETED -> R.string.task_insight_completed
                        })
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("taskInsightRow")) {
                            StatusGlyph(
                                kind = when (task.status) {
                                    TaskStatus.PENDING -> StatusGlyphKind.TASK_PENDING
                                    TaskStatus.IN_PROGRESS -> StatusGlyphKind.TASK_RUNNING
                                    TaskStatus.COMPLETED -> StatusGlyphKind.DONE
                                },
                                modifier = Modifier.padding(end = 12.dp),
                                color = taskGlyphColor(
                                    task.status,
                                    neutral = MaterialTheme.colorScheme.onSurfaceVariant,
                                    active = MaterialTheme.colorScheme.primary,
                                    success = idleColor(),
                                ),
                                animate = tasks.freshness == TaskFreshness.LIVE,
                            )
                            Column {
                                Text(
                                    task.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (task.status == TaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (task.status == TaskStatus.COMPLETED) TextDecoration.LineThrough else TextDecoration.None,
                                )
                                Text(stringResource(R.string.task_insight_id_status, task.id, status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (task.status == TaskStatus.IN_PROGRESS && !task.activeForm.isNullOrBlank()) {
                                    Text(task.activeForm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
