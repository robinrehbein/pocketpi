package de.joinnoah.pi.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal class GitCommitReviewActions(
    val close: () -> Unit,
    val toggle: (String) -> Unit,
    val setMessage: (String) -> Unit,
    val review: () -> Unit,
    val backToEdit: () -> Unit,
    val confirm: () -> Unit,
    val acknowledgeUnknown: () -> Unit = {},
)

/** The host snapshot and confirmation are owned by the controller, not by this view. */
@Composable
internal fun GitCommitReviewPane(
    state: GitCommitReviewState,
    actions: GitCommitReviewActions,
    modifier: Modifier = Modifier,
) {
    if (!state.open) return
    BackHandler { actions.close() }
    var confirmUnknown by remember(state.result) { mutableStateOf(false) }
    if (confirmUnknown && state.result == GitWriteResult.Commit.Unknown) {
        AlertDialog(
            onDismissRequest = { confirmUnknown = false },
            title = { Text(stringResource(R.string.remote_commit_ack_title)) },
            text = { Text(stringResource(R.string.remote_commit_ack_body)) },
            confirmButton = { TextButton(onClick = { confirmUnknown = false; actions.acknowledgeUnknown() }, modifier = Modifier.testTag("commitUnknownConfirm")) { Text(stringResource(R.string.remote_commit_ack_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmUnknown = false }) { Text(stringResource(R.string.remote_commit_cancel)) } },
        )
    }
    Surface(modifier.fillMaxSize().testTag("commitReviewPane")) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.remote_commit_title), style = MaterialTheme.typography.headlineSmall)
            if (state.loading) Text(stringResource(R.string.remote_commit_loading), Modifier.testTag("commitLoading"))
            if (state.invalidated) Text(stringResource(R.string.remote_commit_invalidated), Modifier.testTag("commitInvalidated"), color = MaterialTheme.colorScheme.error)
            state.error?.let { error -> Text(stringResource(commitErrorText(error)), Modifier.testTag("commitError"), color = MaterialTheme.colorScheme.error) }
            val result = state.result
            when (result) {
                is GitWriteResult.Commit.Committed -> {
                    Text(stringResource(R.string.remote_commit_success, result.branch, result.commit), Modifier.testTag("commitSuccess"))
                    if (!result.indexUpdated) Text(stringResource(R.string.remote_commit_index_warning), Modifier.testTag("commitIndexWarning"))
                }
                GitWriteResult.Commit.Unknown -> {
                    Text(stringResource(R.string.remote_commit_unknown), Modifier.testTag("commitUnknown"), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { confirmUnknown = true }, modifier = Modifier.testTag("commitUnknownAcknowledge")) { Text(stringResource(R.string.remote_commit_ack)) }
                }
                is GitWriteResult.Commit.Refused -> Text(stringResource(R.string.remote_commit_refused, stringResource(commitReasonText(result.reason))), Modifier.testTag("commitRefused"))
                null -> Unit
            }
            val snapshot = state.snapshot
            val confirmation = state.confirmation
            if (result == null && confirmation != null) {
                Text(stringResource(R.string.remote_commit_branch, confirmation.branch), Modifier.testTag("commitBranch"))
                confirmation.paths.forEach { Text(it) }
                Text(confirmation.message, Modifier.testTag("commitConfirmedMessage"))
                Text(stringResource(R.string.remote_commit_hooks))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.backToEdit, enabled = !state.sending && !state.invalidated, modifier = Modifier.testTag("commitBack")) { Text(stringResource(R.string.remote_commit_back)) }
                    Button(onClick = actions.confirm, enabled = !state.sending && !state.invalidated, modifier = Modifier.testTag("commitConfirm")) { Text(stringResource(R.string.remote_commit_confirm)) }
                }
            } else if (result == null && snapshot != null) {
                snapshot.branch?.let { Text(stringResource(R.string.remote_commit_branch, it), Modifier.testTag("commitBranch")) }
                Text(stringResource(R.string.remote_commit_choose))
                if (snapshot.truncated) Text(stringResource(R.string.remote_commit_truncated), Modifier.testTag("commitTruncated"))
                snapshot.files.forEach { file ->
                    val enabled = file.omitted == null && !file.submodule && !state.sending && !state.invalidated && !state.loading
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = file.path in state.selected, enabled = enabled, role = Role.Checkbox) { actions.toggle(file.path) }.testTag("commitFile:${file.path}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = file.path in state.selected, onCheckedChange = null, enabled = enabled)
                        Column(Modifier.weight(1f)) {
                            Text(file.path)
                            if (file.submodule) Text(stringResource(R.string.remote_commit_submodule), style = MaterialTheme.typography.bodySmall)
                            else if (file.omitted != null) Text(stringResource(R.string.remote_commit_omitted), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = state.message, onValueChange = actions.setMessage, enabled = !state.sending && !state.invalidated, label = { Text(stringResource(R.string.remote_commit_message)) }, modifier = Modifier.fillMaxWidth().testTag("commitMessage"))
                Text(stringResource(R.string.remote_commit_hooks))
                Button(onClick = actions.review, enabled = state.selected.isNotEmpty() && state.message.isNotBlank() && !state.loading && !state.sending && !state.invalidated, modifier = Modifier.testTag("commitReview")) { Text(stringResource(R.string.remote_commit_review)) }
            }
            if (state.sending) Text(stringResource(R.string.remote_commit_sending), Modifier.testTag("commitSending"))
            OutlinedButton(onClick = actions.close, modifier = Modifier.testTag("commitClose")) { Text(stringResource(R.string.remote_commit_close)) }
        }
    }
}

private fun commitReasonText(reason: GitCommitReason): Int = when (reason) {
    GitCommitReason.GIT_UNAVAILABLE -> R.string.remote_commit_reason_git_unavailable
    GitCommitReason.NOT_A_REPOSITORY -> R.string.remote_commit_reason_not_a_repository
    GitCommitReason.SESSION_UNSUPPORTED -> R.string.remote_commit_reason_session_unsupported
    GitCommitReason.DETACHED_HEAD -> R.string.remote_commit_reason_detached_head
    GitCommitReason.UNBORN_BRANCH -> R.string.remote_commit_reason_unborn_branch
    GitCommitReason.OPERATION_IN_PROGRESS -> R.string.remote_commit_reason_operation_in_progress
    GitCommitReason.PROTECTED_BRANCH -> R.string.remote_commit_reason_protected_branch
    GitCommitReason.LFS_UNSUPPORTED -> R.string.remote_commit_reason_lfs_unsupported
    GitCommitReason.NOTHING_TO_COMMIT -> R.string.remote_commit_reason_nothing_to_commit
    GitCommitReason.IDENTITY_MISSING -> R.string.remote_commit_reason_identity_missing
    GitCommitReason.SIGNING_FAILED -> R.string.remote_commit_reason_signing_failed
    GitCommitReason.LOCAL_TRANSPORT_CONFIG -> R.string.remote_commit_reason_local_transport_config
}

private fun commitErrorText(error: GitCommitReviewError): Int = when (error) {
    GitCommitReviewError.INVALID_MESSAGE -> R.string.remote_commit_error_invalid_message
    GitCommitReviewError.STORAGE -> R.string.remote_commit_error_storage
    GitCommitReviewError.REQUEST -> R.string.remote_commit_error_request
    GitCommitReviewError.UNAVAILABLE -> R.string.remote_commit_error_unavailable
}
