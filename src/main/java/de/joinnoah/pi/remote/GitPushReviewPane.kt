package de.joinnoah.pi.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal class GitPushReviewActions(
    val prepare: () -> Unit,
    val close: () -> Unit,
    val review: () -> Unit,
    val backToEdit: () -> Unit,
    val confirm: () -> Unit,
    val checkRemote: () -> Unit,
    val acknowledgeUnknown: () -> Unit,
)

@Composable
internal fun GitPushReviewPane(
    state: GitPushReviewState,
    actions: GitPushReviewActions,
    modifier: Modifier = Modifier,
) {
    if (!state.open) return
    BackHandler { actions.close() }
    var confirmUnknown by remember(state.pending) { mutableStateOf(false) }
    if (confirmUnknown && state.pending != null && state.result == GitWriteResult.Push.Unknown) {
        AlertDialog(
            onDismissRequest = { confirmUnknown = false },
            title = { Text(stringResource(R.string.remote_push_ack_title)) },
            text = { Text(stringResource(R.string.remote_push_ack_body)) },
            confirmButton = { TextButton(onClick = { confirmUnknown = false; actions.acknowledgeUnknown() }, modifier = Modifier.testTag("pushUnknownConfirm")) { Text(stringResource(R.string.remote_push_ack_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmUnknown = false }) { Text(stringResource(R.string.remote_push_cancel)) } },
        )
    }
    Surface(modifier.fillMaxSize().testTag("pushReviewPane")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.remote_push_title), style = MaterialTheme.typography.headlineSmall)
                if (state.loading) Text(stringResource(R.string.remote_push_loading), Modifier.testTag("pushLoading"))
                if (state.invalidated && !(state.result == GitWriteResult.Push.Unknown && state.pending != null))
                    Text(stringResource(R.string.remote_push_invalidated), Modifier.testTag("pushInvalidated"), color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(stringResource(pushErrorText(it)), Modifier.testTag("pushError"), color = MaterialTheme.colorScheme.error) }
                val result = state.result
                when (result) {
                    is GitWriteResult.Push.Pushed -> {
                        Text(stringResource(if (result.created) R.string.remote_push_created else R.string.remote_push_success, result.branch), Modifier.testTag("pushSuccess"))
                        Text(stringResource(R.string.remote_push_head, result.commit), Modifier.testTag("pushResultHead"))
                        if (!result.upstreamSet) Text(stringResource(R.string.remote_push_upstream_unchanged), Modifier.testTag("pushUpstreamUnchanged"))
                    }
                    is GitWriteResult.Push.Refused -> Text(stringResource(R.string.remote_push_refused, stringResource(pushReasonText(result.reason))), Modifier.testTag("pushRefused"))
                    GitWriteResult.Push.Unknown -> {
                        Text(stringResource(R.string.remote_push_unknown), Modifier.testTag("pushUnknown"), color = MaterialTheme.colorScheme.error)
                        state.pending?.let { pending -> PushTarget(pending.branch, pending.head, pending.target) }
                        OutlinedButton(onClick = actions.checkRemote, enabled = !state.checking && !state.sending && state.canCheckRemote, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushCheckRemote")) { Text(stringResource(R.string.remote_push_check)) }
                        if (state.checking) Text(stringResource(R.string.remote_push_checking), Modifier.testTag("pushChecking"))
                        state.recovery?.let { observation ->
                            Text(stringResource(R.string.remote_push_status_explanation))
                            Text(stringResource(pushStatusText(observation.state)), Modifier.testTag("pushRecovery"))
                        }
                        OutlinedButton(onClick = { confirmUnknown = true }, enabled = !state.sending && !state.checking && state.pending != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushUnknownAcknowledge")) { Text(stringResource(R.string.remote_push_ack)) }
                    }
                    null -> {
                        val confirmation = state.confirmation
                        if (confirmation != null) {
                            PushTarget(confirmation.branch, confirmation.head, confirmation.target)
                            Button(onClick = actions.confirm, enabled = !state.sending && !state.invalidated, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushConfirm")) { Text(stringResource(R.string.remote_push_confirm)) }
                            OutlinedButton(onClick = actions.backToEdit, enabled = !state.sending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushBack")) { Text(stringResource(R.string.remote_push_back)) }
                        } else {
                            val preparation = state.preparation
                            if (preparation != null && preparation.head != null) {
                                PushTarget(preparation.branch, preparation.head, preparation.pushTarget)
                                Button(onClick = actions.review, enabled = !state.loading && !state.sending && !state.invalidated, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushReview")) { Text(stringResource(R.string.remote_push_review)) }
                            }
                        }
                    }
                }
                if (state.invalidated && result == null && !state.loading && !state.sending)
                    OutlinedButton(onClick = actions.prepare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushPrepareAgain")) { Text(stringResource(R.string.remote_push_prepare)) }
                if (state.sending) Text(stringResource(R.string.remote_push_sending), Modifier.testTag("pushSending"))
                OutlinedButton(onClick = actions.close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pushClose")) { Text(stringResource(R.string.remote_push_close)) }
            }
        }
    }
}

@Composable
private fun PushTarget(branch: String, head: String, target: GitWriteTarget) {
    Text(stringResource(R.string.remote_push_branch, branch), Modifier.testTag("pushBranch"))
    Text(stringResource(R.string.remote_push_target, target.host, target.repo), Modifier.testTag("pushTarget"))
    Text(stringResource(R.string.remote_push_head, head), Modifier.testTag("pushHead"))
}

private fun pushStatusText(status: GitPushStatus): Int = when (status) {
    GitPushStatus.MATCHES -> R.string.remote_push_status_matches
    GitPushStatus.ABSENT -> R.string.remote_push_status_absent
    GitPushStatus.DIFFERENT -> R.string.remote_push_status_different
    GitPushStatus.UNAVAILABLE -> R.string.remote_push_status_unavailable
}
private fun pushErrorText(error: GitPushReviewError): Int = when (error) {
    GitPushReviewError.UNAVAILABLE -> R.string.remote_push_error_unavailable
    GitPushReviewError.STORAGE -> R.string.remote_push_error_storage
    GitPushReviewError.REQUEST -> R.string.remote_push_error
}
private fun pushReasonText(reason: GitPushReason): Int = when (reason) {
    GitPushReason.GIT_UNAVAILABLE -> R.string.remote_push_reason_git_unavailable
    GitPushReason.NOT_A_REPOSITORY -> R.string.remote_push_reason_not_a_repository
    GitPushReason.SESSION_UNSUPPORTED -> R.string.remote_push_reason_session_unsupported
    GitPushReason.DETACHED_HEAD -> R.string.remote_push_reason_detached_head
    GitPushReason.UNBORN_BRANCH -> R.string.remote_push_reason_unborn_branch
    GitPushReason.OPERATION_IN_PROGRESS -> R.string.remote_push_reason_operation_in_progress
    GitPushReason.PROTECTED_BRANCH -> R.string.remote_push_reason_protected_branch
    GitPushReason.NO_ORIGIN -> R.string.remote_push_reason_no_origin
    GitPushReason.UNSUPPORTED_REMOTE -> R.string.remote_push_reason_unsupported_remote
    GitPushReason.LFS_UNSUPPORTED -> R.string.remote_push_reason_lfs_unsupported
    GitPushReason.LFS_SCAN_LIMIT -> R.string.remote_push_reason_lfs_scan_limit
    GitPushReason.LOCAL_TRANSPORT_CONFIG -> R.string.remote_push_reason_local_transport_config
    GitPushReason.UP_TO_DATE -> R.string.remote_push_reason_up_to_date
    GitPushReason.AUTH_FAILED -> R.string.remote_push_reason_auth_failed
    GitPushReason.HOST_KEY -> R.string.remote_push_reason_host_key
    GitPushReason.NETWORK -> R.string.remote_push_reason_network
    GitPushReason.REJECTED_NON_FAST_FORWARD -> R.string.remote_push_reason_rejected_non_fast_forward
    GitPushReason.REJECTED_REMOTE -> R.string.remote_push_reason_rejected_remote
}
