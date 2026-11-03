package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Which mutations the shown session offers ([canMutateFiles]); all off on the watch. */
data class FileMutationAccess(val create: Boolean = false, val rename: Boolean = false, val delete: Boolean = false) {
    companion object {
        fun of(state: RemoteState) =
            FileMutationAccess(
                canMutateFiles(state, FileMutationKind.CREATE),
                canMutateFiles(state, FileMutationKind.RENAME),
                canMutateFiles(state, FileMutationKind.DELETE),
            )
    }
}

@Composable
private fun operationText(kind: FileMutationKind, path: String, destination: String?): String =
    when (kind) {
        FileMutationKind.CREATE -> stringResource(R.string.remote_files_mutation_op_create, path)
        FileMutationKind.RENAME -> stringResource(R.string.remote_files_mutation_op_rename, path, destination.orEmpty())
        FileMutationKind.DELETE -> stringResource(R.string.remote_files_mutation_op_delete, path)
    }

/**
 * The create, rename or delete prompt. Delete names the exact relative path; dismissing it, Back
 * or Cancel sends nothing, and Delete is enabled only once a fresh read found the file free.
 */
@Composable
internal fun FileMutationPrompt(dialog: FileMutationDialog, actions: FilesActions) {
    val original = if (dialog.kind == FileMutationKind.RENAME) dialog.path.substringAfterLast('/') else ""
    var name by rememberSaveable(dialog.kind, dialog.path) { mutableStateOf(original) }
    val named = dialog.kind == FileMutationKind.DELETE || (validMutationName(name) && name != original)
    val dismiss = { actions.onCancelMutation() }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("filesMutationDialog"),
        title = {
            Text(
                when (dialog.kind) {
                    FileMutationKind.CREATE -> stringResource(R.string.remote_files_create_title)
                    FileMutationKind.RENAME -> stringResource(R.string.remote_files_rename_title, dialog.path)
                    FileMutationKind.DELETE -> stringResource(R.string.remote_files_delete_title)
                }
            )
        },
        text = {
            Column {
                when (dialog.kind) {
                    FileMutationKind.DELETE ->
                        Text(stringResource(R.string.remote_files_delete_text, dialog.path), Modifier.testTag("filesDeletePath"))
                    else -> {
                        if (dialog.kind == FileMutationKind.CREATE) {
                            val folder = dialog.path.ifEmpty { stringResource(R.string.remote_files_mutation_session_folder) }
                            Text(stringResource(R.string.remote_files_mutation_folder, folder), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            enabled = !dialog.sending,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            label = { Text(stringResource(R.string.remote_files_mutation_name)) },
                            isError = name.isNotEmpty() && !validMutationName(name),
                            modifier = Modifier.fillMaxWidth().testTag("filesMutationName"),
                        )
                        if (name.isNotEmpty() && !validMutationName(name))
                            Text(stringResource(R.string.remote_files_mutation_name_invalid), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (dialog.checking || dialog.sending)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("filesMutationChecking"))
                if (dialog.checking) Text(stringResource(R.string.remote_files_mutation_checking), style = MaterialTheme.typography.bodySmall)
                val notice =
                    when {
                        dialog.blocked -> stringResource(R.string.remote_files_mutation_blocked)
                        dialog.failure != null -> stringResource(filesFailureText(dialog.failure))
                        dialog.changed -> stringResource(R.string.remote_files_mutation_changed)
                        dialog.nameTaken -> stringResource(R.string.remote_files_mutation_name_taken)
                        else -> null
                    }
                if (notice != null)
                    Text(notice, Modifier.padding(top = 8.dp).testTag("filesMutationNotice"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onConfirmMutation(name.takeIf { dialog.kind != FileMutationKind.DELETE }) },
                enabled = dialog.confirmable && named,
                modifier = Modifier.testTag("filesMutationConfirm"),
            ) {
                Text(
                    when (dialog.kind) {
                        FileMutationKind.CREATE -> stringResource(R.string.remote_files_create_confirm)
                        FileMutationKind.RENAME -> stringResource(R.string.remote_files_rename)
                        FileMutationKind.DELETE -> stringResource(R.string.remote_files_delete)
                    },
                    color = if (dialog.kind == FileMutationKind.DELETE && dialog.confirmable) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !dialog.sending, modifier = Modifier.testTag("filesMutationCancel")) {
                Text(stringResource(R.string.remote_files_mutation_cancel))
            }
        },
    )
}

/** The last outcome, the journal's failure and every unknown outcome waiting for acknowledgement. */
@Composable
internal fun FileMutationNotices(mutations: FileMutationsState, actions: FilesActions, modifier: Modifier = Modifier) {
    val outcome = mutations.outcome
    if (outcome == null && mutations.recovery.isEmpty() && !mutations.storageFailed) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("filesMutationNotices")) {
        if (mutations.storageFailed)
            Text(stringResource(R.string.remote_files_mutation_storage), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (outcome != null) {
            val operation = operationText(outcome.kind, outcome.path, outcome.destinationPath)
            val text =
                when (outcome.status) {
                    FileMutationStatus.PENDING -> stringResource(R.string.remote_files_mutation_pending, operation)
                    FileMutationStatus.APPLIED -> stringResource(R.string.remote_files_mutation_applied, operation)
                    FileMutationStatus.UNKNOWN -> stringResource(R.string.remote_files_mutation_unknown, operation)
                    FileMutationStatus.FAILED ->
                        stringResource(R.string.remote_files_mutation_failed, stringResource(filesFailureText(outcome.failure ?: FilesFailure.FAILED)))
                    FileMutationStatus.REFUSED ->
                        when (outcome.reason) {
                            FileMutationRefusal.CONFLICT -> stringResource(R.string.remote_files_mutation_refused_conflict, outcome.path)
                            FileMutationRefusal.EXISTS -> stringResource(R.string.remote_files_mutation_refused_exists)
                            FileMutationRefusal.NOT_FOUND -> stringResource(R.string.remote_files_mutation_refused_not_found, outcome.path)
                            FileMutationRefusal.EPOCH -> stringResource(R.string.remote_files_mutation_refused_epoch)
                            FileMutationRefusal.INVALID_PATH, null -> stringResource(R.string.remote_files_mutation_refused_invalid_path, outcome.path)
                        }
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    Modifier.weight(1f).testTag("filesMutationOutcome:${outcome.status.name}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (outcome.status == FileMutationStatus.APPLIED || outcome.status == FileMutationStatus.PENDING) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                if (outcome.status != FileMutationStatus.PENDING)
                    TextButton(onClick = actions.onDismissMutationOutcome, Modifier.testTag("filesMutationDismiss")) {
                        Text(stringResource(R.string.remote_files_mutation_dismiss))
                    }
            }
        }
        if (mutations.recovery.isNotEmpty()) {
            Text(stringResource(R.string.remote_files_mutation_recovery_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.remote_files_mutation_recovery_text), style = MaterialTheme.typography.bodySmall)
            mutations.recovery.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("filesMutationRecovery:${item.operationId}")) {
                    Column(Modifier.weight(1f)) {
                        Text(operationText(item.kind, item.path, item.destinationPath), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        if (item.lost) Text(stringResource(R.string.remote_files_mutation_lost), style = MaterialTheme.typography.bodySmall)
                        if (item.failure != null)
                            Text(stringResource(R.string.remote_files_mutation_acknowledge_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { actions.onAcknowledgeMutation(item.operationId) },
                        enabled = !item.acknowledging,
                        modifier = Modifier.testTag("filesMutationAcknowledge:${item.operationId}"),
                    ) {
                        Text(stringResource(R.string.remote_files_mutation_acknowledge))
                    }
                }
            }
        }
    }
}
