package de.joinnoah.pi.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal class GitPullRequestReviewActions(
    val prepare: () -> Unit,
    val close: () -> Unit,
    val setBase: (String) -> Unit,
    val setTitle: (String) -> Unit,
    val setBody: (String) -> Unit,
    val setDraft: (Boolean) -> Unit,
    val review: () -> Unit,
    val backToEdit: () -> Unit,
    val confirm: () -> Unit,
    val checkRemote: () -> Unit,
    val acknowledgeUnknown: () -> Unit,
)

@Composable
internal fun GitPullRequestReviewPane(state: GitPullRequestReviewState, actions: GitPullRequestReviewActions, modifier: Modifier = Modifier) {
    if (!state.open) return
    BackHandler { actions.close() }
    var confirmUnknown by remember(state.pending) { mutableStateOf(false) }
    if (confirmUnknown && state.pending != null && state.result == GitWriteResult.PullRequest.Unknown) {
        AlertDialog(onDismissRequest = { confirmUnknown = false }, title = { Text(stringResource(R.string.pr_ack_title)) },
            text = { Text(stringResource(R.string.pr_ack_body)) },
            confirmButton = { TextButton(onClick = { confirmUnknown = false; actions.acknowledgeUnknown() }, modifier = Modifier.heightIn(min = 48.dp).testTag("prUnknownConfirm")) { Text(stringResource(R.string.pr_ack_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmUnknown = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pr_cancel)) } })
    }
    Surface(modifier.fillMaxSize().testTag("prReviewPane")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pr_heading), style = MaterialTheme.typography.headlineSmall)
                if (state.loading) Text(stringResource(R.string.pr_loading))
                if (state.invalidated && state.pending == null) Text(stringResource(R.string.pr_invalidated), color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(stringResource(prErrorText(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("prError")) }
                when (val result = state.result) {
                    is GitWriteResult.PullRequest.Available -> {
                        Text(stringResource(if (result.created) R.string.pr_created else R.string.pr_existing, result.number), Modifier.testTag("prSuccess"))
                        Text(stringResource(R.string.pr_base_value, result.base))
                        PrLink(result.url)
                    }
                    is GitWriteResult.PullRequest.Unavailable -> Text(stringResource(prReasonText(result.reason)), Modifier.testTag("prUnavailable"), color = MaterialTheme.colorScheme.error)
                    GitWriteResult.PullRequest.Unknown -> {
                        Text(stringResource(R.string.pr_unknown), Modifier.testTag("prUnknown"), color = MaterialTheme.colorScheme.error)
                        state.pending?.let { PrIntent(it) }
                        OutlinedButton(onClick = actions.checkRemote, enabled = state.canCheckRemote && !state.checking && !state.sending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prCheckRemote")) { Text(stringResource(R.string.pr_check)) }
                        if (state.checking) Text(stringResource(R.string.pr_checking))
                        state.recovery?.let { observation ->
                            Text(stringResource(R.string.pr_status_explanation))
                            Text(stringResource(prStatusText(observation.state)), Modifier.testTag("prRecovery"))
                            observation.pullRequest?.let { pr ->
                                Text(stringResource(R.string.pr_observed, pr.number, pr.base))
                                PrLink(pr.url)
                            }
                        }
                        OutlinedButton(onClick = { confirmUnknown = true }, enabled = state.pending != null && !state.sending && !state.checking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prUnknownAcknowledge")) { Text(stringResource(R.string.pr_ack)) }
                    }
                    null -> {
                        val confirmation = state.confirmation
                        if (confirmation != null) {
                            PrIntent(confirmation)
                            Button(onClick = actions.confirm, enabled = !state.sending && !state.invalidated, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prConfirm")) { Text(stringResource(R.string.pr_confirm)) }
                            OutlinedButton(onClick = actions.backToEdit, enabled = !state.sending, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prBack")) { Text(stringResource(R.string.pr_back)) }
                        } else {
                            state.preparation?.let { preparation ->
                                preparation.head?.let { PrTarget(preparation.branch, it, preparation.pushTarget) }
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }, enabled = !state.loading && !state.sending && !state.invalidated && preparation.pullRequestBases.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prBase")) { Text(stringResource(R.string.pr_base_value, state.draft.base ?: stringResource(R.string.pr_choose_base))) }
                                    GlassDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        preparation.pullRequestBases.forEach { base -> DropdownMenuItem(text = { Text(base) }, onClick = { expanded = false; actions.setBase(base) }, modifier = Modifier.heightIn(min = 48.dp)) }
                                    }
                                }
                                OutlinedTextField(value = state.draft.title, onValueChange = actions.setTitle, label = { Text(stringResource(R.string.pr_title_label)) }, enabled = !state.sending && !state.invalidated, modifier = Modifier.fillMaxWidth().testTag("prTitle"))
                                OutlinedTextField(value = state.draft.body, onValueChange = actions.setBody, label = { Text(stringResource(R.string.pr_body_label)) }, enabled = !state.sending && !state.invalidated, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("prBody"))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prDraft").toggleable(value = state.draft.draft, enabled = !state.sending && !state.invalidated, role = Role.Checkbox, onValueChange = actions.setDraft)) {
                                    Checkbox(checked = state.draft.draft, onCheckedChange = null, enabled = !state.sending && !state.invalidated)
                                    Text(stringResource(R.string.pr_draft_label))
                                }
                                Button(onClick = actions.review, enabled = !state.loading && !state.sending && !state.invalidated && preparation.head != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prReview")) { Text(stringResource(R.string.pr_review)) }
                            }
                        }
                    }
                }
                if (state.invalidated && state.result == null && state.pending == null && !state.loading && !state.sending) OutlinedButton(onClick = actions.prepare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prPrepareAgain")) { Text(stringResource(R.string.pr_prepare)) }
                if (state.sending) Text(stringResource(R.string.pr_sending))
                OutlinedButton(onClick = actions.close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prClose")) { Text(stringResource(R.string.pr_close)) }
            }
        }
    }
}

@Composable private fun PrTarget(branch: String, head: String, target: GitWriteTarget) {
    Text(stringResource(R.string.pr_branch, branch), Modifier.testTag("prBranch"))
    Text(stringResource(R.string.pr_target, target.host, target.repo), Modifier.testTag("prTarget"))
    Text(stringResource(R.string.pr_remote_branch, target.remote, target.branch), Modifier.testTag("prRemoteBranch"))
    Text(stringResource(R.string.pr_head, head), Modifier.testTag("prHead"))
}
@Composable private fun PrIntent(intent: GitPullRequestIntent) {
    PrTarget(intent.branch, intent.head, intent.target)
    Text(stringResource(R.string.pr_base_value, intent.base), Modifier.testTag("prBase"))
    Text(stringResource(R.string.pr_title_value, intent.title), Modifier.testTag("prTitle"))
    Text(stringResource(R.string.pr_body_value, intent.body), Modifier.testTag("prBody"))
    Text(stringResource(if (intent.draft) R.string.pr_draft_value else R.string.pr_ready_value), Modifier.testTag("prDraft"))
}
@Composable private fun PrLink(url: String) {
    val handler = LocalUriHandler.current
    if (validHttpsUrl(url)) OutlinedButton(onClick = { runCatching { handler.openUri(url) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prOpen")) { Text(stringResource(R.string.pr_open)) }
}
private fun prErrorText(error: GitPullRequestReviewError): Int = when (error) {
    GitPullRequestReviewError.UNAVAILABLE -> R.string.pr_error_unavailable
    GitPullRequestReviewError.STORAGE -> R.string.pr_error_storage
    GitPullRequestReviewError.REQUEST -> R.string.pr_error_request
    GitPullRequestReviewError.INVALID_DRAFT -> R.string.pr_error_invalid_draft
}
private fun prStatusText(status: GitPullRequestStatus): Int = when (status) {
    GitPullRequestStatus.FOUND -> R.string.pr_status_found
    GitPullRequestStatus.ABSENT -> R.string.pr_status_absent
    GitPullRequestStatus.DIFFERENT -> R.string.pr_status_different
    GitPullRequestStatus.UNAVAILABLE -> R.string.pr_status_unavailable
}

private fun prReasonText(reason: GitPullRequestReason): Int = when (reason) {
    GitPullRequestReason.GIT_UNAVAILABLE -> R.string.pr_reason_git_unavailable
    GitPullRequestReason.NOT_A_REPOSITORY -> R.string.pr_reason_not_a_repository
    GitPullRequestReason.SESSION_UNSUPPORTED -> R.string.pr_reason_session_unsupported
    GitPullRequestReason.DETACHED_HEAD -> R.string.pr_reason_detached_head
    GitPullRequestReason.UNBORN_BRANCH -> R.string.pr_reason_unborn_branch
    GitPullRequestReason.OPERATION_IN_PROGRESS -> R.string.pr_reason_operation_in_progress
    GitPullRequestReason.PROTECTED_BRANCH -> R.string.pr_reason_protected_branch
    GitPullRequestReason.NO_ORIGIN -> R.string.pr_reason_no_origin
    GitPullRequestReason.UNSUPPORTED_REMOTE -> R.string.pr_reason_unsupported_remote
    GitPullRequestReason.NOT_PUSHED -> R.string.pr_reason_not_pushed
    GitPullRequestReason.GH_UNAVAILABLE -> R.string.pr_reason_gh_unavailable
    GitPullRequestReason.GH_AUTH -> R.string.pr_reason_gh_auth
    GitPullRequestReason.NOT_GITHUB -> R.string.pr_reason_not_github
    GitPullRequestReason.REJECTED -> R.string.pr_reason_rejected
}
