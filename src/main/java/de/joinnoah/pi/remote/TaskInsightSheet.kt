package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskInsightPill(tasks: SessionTasks, onClick: () -> Unit) {
    if (tasks.items.isEmpty() || tasks.freshness !in setOf(TaskFreshness.LIVE, TaskFreshness.STALE)) return
    val completed = tasks.items.count { it.status == TaskStatus.COMPLETED }
    val active = tasks.items.count { it.status == TaskStatus.IN_PROGRESS }
    val stale = tasks.freshness == TaskFreshness.STALE
    val description = stringResource(R.string.task_insight_description, completed, tasks.items.size, active) +
        if (stale) ", " + stringResource(R.string.task_insight_stale) else ""
    val colors = if (active > 0) AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) else AssistChipDefaults.assistChipColors()
    AssistChip(
        onClick = onClick,
        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("taskInsightPill").semantics { contentDescription = description },
        label = { Text(stringResource(R.string.task_insight_count, completed, tasks.items.size)) },
        leadingIcon = { Text(if (stale) "⚠" else if (active > 0) "◉" else "○") },
        colors = colors,
    )
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
                            Text(
                                when (task.status) {
                                    TaskStatus.PENDING -> "○"
                                    TaskStatus.IN_PROGRESS -> "◉"
                                    TaskStatus.COMPLETED -> "✓"
                                },
                                modifier = Modifier.padding(end = 12.dp),
                                color = if (task.status == TaskStatus.IN_PROGRESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text(task.text, style = MaterialTheme.typography.bodyLarge)
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
