package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The sheets of a ticket page; at most one is open. */
internal enum class TicketSheet { ACCEPT, COMMENT, STATUS, START }

/** Targets of the status sheet: Done goes through accept, and the agent moves a ticket to Review. */
internal fun statusTargets(ticket: Ticket): List<String> =
    ticket.moveTargets.filter { it != "Done" && it != "Review" }

/** The start sheet's preview of the branch the host will create. */
internal fun branchPreview(ref: String): String = "task/${ref.lowercase()}-…"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun SheetField(label: String, value: String, onChange: (String) -> Unit, maxChars: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(maxChars)) },
        label = { Text(label) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("ticketSheetField"),
    )
}

@Composable
private fun SheetConfirm(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("ticketSheetConfirm")) {
        Text(text)
    }
}

/** Confirms setting [ref] to Done; the comment is prefilled and editable. */
@Composable
internal fun AcceptSheet(ref: String, onConfirm: (comment: String) -> Unit, onDismiss: () -> Unit) {
    val prefill = stringResource(R.string.remote_tickets_accept_comment)
    var comment by rememberSaveable { mutableStateOf(prefill) }
    SheetFrame(stringResource(R.string.remote_tickets_accept_title, ref), onDismiss) {
        SheetField(stringResource(R.string.remote_tickets_comment_label), comment, { comment = it }, MAX_TICKET_MOVE_COMMENT_CHARS)
        SheetConfirm(stringResource(R.string.remote_ticket_accept), comment.isNotBlank()) { onConfirm(comment.trim()) }
    }
}

@Composable
internal fun CommentSheet(ref: String, onSend: (text: String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SheetFrame(stringResource(R.string.remote_tickets_comment_title, ref), onDismiss) {
        SheetField(stringResource(R.string.remote_tickets_comment_label), text, { text = it }, MAX_TICKET_COMMENT_CHARS)
        SheetConfirm(stringResource(R.string.remote_tickets_send), text.isNotBlank()) { onSend(text.trim()) }
    }
}

/** The moves the host allows, minus Done; Cancelled is secondary and needs a comment. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusSheet(ticket: Ticket, onMove: (state: String, comment: String?) -> Unit, onDismiss: () -> Unit) {
    val targets = statusTargets(ticket)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    val needsComment = chosen == CANCELLED_STATE
    SheetFrame(stringResource(R.string.remote_tickets_status_title, ticket.ref), onDismiss) {
        Text(
            stringResource(R.string.remote_tickets_status_current, ticket.state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (targets.isEmpty()) Text(stringResource(R.string.remote_tickets_status_none))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { target ->
                val secondary = target == CANCELLED_STATE
                FilterChip(
                    selected = chosen == target,
                    onClick = { chosen = target },
                    label = { Text(target) },
                    colors =
                        if (secondary)
                            FilterChipDefaults.filterChipColors(
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            )
                        else
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                )
            }
        }
        if (needsComment) {
            Text(
                stringResource(R.string.remote_tickets_status_comment_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SheetField(stringResource(R.string.remote_tickets_comment_label), comment, { comment = it }, MAX_TICKET_MOVE_COMMENT_CHARS)
        }
        SheetConfirm(
            stringResource(R.string.remote_ticket_status),
            chosen != null && (!needsComment || comment.isNotBlank()),
        ) { chosen?.let { onMove(it, if (needsComment) comment.trim() else null) } }
    }
}

private const val CANCELLED_STATE = "Cancelled"

private val startSteps = listOf(TicketStartStep.WORKTREE, TicketStartStep.MOVE, TicketStartStep.SESSION, TicketStartStep.PROMPT)

@StringRes
private fun stepLabel(step: TicketStartStep): Int =
    when (step) {
        TicketStartStep.WORKTREE -> R.string.remote_tickets_step_worktree
        TicketStartStep.MOVE -> R.string.remote_tickets_step_move
        TicketStartStep.SESSION -> R.string.remote_tickets_step_session
        TicketStartStep.PROMPT -> R.string.remote_tickets_step_prompt
        TicketStartStep.DONE -> R.string.remote_tickets_step_prompt
    }

/**
 * What a failed start says. The wire has no error for a failed In Progress move: the host reports
 * `session_failed` with step `move`, so the wording follows the step first.
 */
@StringRes
internal fun startFailureText(start: TicketStart): Int =
    when {
        start.error == TicketStartError.TIMEOUT -> R.string.remote_tickets_start_failed_timeout
        start.error == TicketStartError.NOT_SUPPORTED -> R.string.remote_tickets_start_failed_unsupported
        start.step == TicketStartStep.WORKTREE -> R.string.remote_tickets_start_failed_worktree
        start.step == TicketStartStep.MOVE -> R.string.remote_tickets_start_failed_move
        start.step == TicketStartStep.SESSION -> R.string.remote_tickets_start_failed_session
        start.step == TicketStartStep.PROMPT -> R.string.remote_tickets_start_failed_prompt
        else -> R.string.remote_tickets_start_failed
    }

/**
 * Shows what starting does, takes an optional note, and follows the job in [TicketsState.start].
 * A job that was finished before the sheet opened is not this sheet's and never opens its chat.
 */
@Composable
internal fun StartSheet(
    ref: String,
    tickets: TicketsState,
    onStart: (note: String?) -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val initial = remember { tickets.start }
    val job = tickets.start?.takeIf { it != initial || initial.state == TicketStartState.RUNNING }
    val following = submitted || job != null
    val failedToSend = submitted && job == null && (tickets.writeFailure != null || tickets.outcomeUnknown)

    LaunchedEffect(job?.startId, job?.state) {
        val sessionId = job?.sessionId
        if (job?.state == TicketStartState.SUCCEEDED && sessionId != null) {
            onOpenChat(sessionId)
            onDismiss()
        }
    }

    SheetFrame(stringResource(R.string.remote_tickets_start_title, ref), onDismiss) {
        if (!following) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.remote_tickets_start_branch, branchPreview(ref)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.remote_tickets_start_moves), style = MaterialTheme.typography.bodyLarge)
            }
            SheetField(stringResource(R.string.remote_tickets_start_note), note, { note = it }, MAX_TICKET_NOTE_CHARS)
            SheetConfirm(stringResource(R.string.remote_tickets_start_go), true) {
                submitted = true
                onStart(note.trim().ifEmpty { null })
            }
        } else if (failedToSend) {
            TicketWriteMessage(tickets)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_tickets_start_close))
            }
        } else {
            StartProgress(job, onOpenChat, onDismiss)
            if (tickets.outcomeUnknown) TicketWriteMessage(tickets)
        }
    }
}

@Composable
private fun StartProgress(job: TicketStart?, onOpenChat: (String) -> Unit, onDismiss: () -> Unit) {
    val failed = job?.state == TicketStartState.FAILED
    val finished = job?.state == TicketStartState.SUCCEEDED
    // Before the host answered, the job is at its first step.
    val current = if (job == null) 0 else startSteps.indexOf(job.step).let { if (it < 0) startSteps.size else it }
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("ticketStartProgress"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        startSteps.forEachIndexed { index, step ->
            val done = finished || index < current
            val active = index == current && !finished
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        done -> Icon(Icons.Default.Check, contentDescription = null)
                        active && failed -> Icon(Icons.Default.Close, contentDescription = null)
                        active -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    stringResource(stepLabel(step)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // An existing worktree is reused; the sheet says so as soon as the host does.
    if (job?.worktreeReused == true)
        Text(stringResource(R.string.remote_tickets_fact_reused), style = MaterialTheme.typography.bodyMedium)
    if (job != null && (failed || finished)) {
        if (failed) Text(stringResource(startFailureText(job)), style = MaterialTheme.typography.bodyLarge)
        // What already happened, so a retry or a look at the ticket starts from the facts.
        when (job.moved) {
            true -> Text(stringResource(R.string.remote_tickets_fact_moved), style = MaterialTheme.typography.bodyMedium)
            false -> Text(stringResource(R.string.remote_tickets_fact_not_moved), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
        val sessionId = job.sessionId
        if (failed && sessionId != null)
            Button(onClick = { onOpenChat(sessionId); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.remote_tickets_start_open_chat))
            }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.remote_tickets_start_close))
        }
    }
}

// ---- Undo bar and write notice ------------------------------------------------------------------

/** The bar for the 5 s window of a queued accept; undo takes it back before anything is sent. */
@Composable
internal fun TicketUndoBar(pending: PendingAccept?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    if (pending == null) return
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().testTag("ticketUndoBar")
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.remote_tickets_accepting, pending.ref),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = onUndo) {
                Text(stringResource(R.string.remote_tickets_undo), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@StringRes
private fun writeFailureText(failure: TicketsFailure): Int =
    when (failure) {
        TicketsFailure.OFFLINE -> R.string.remote_tickets_failure_offline
        TicketsFailure.UNSUPPORTED -> R.string.remote_tickets_failure_unsupported
        TicketsFailure.BUSY -> R.string.remote_tickets_failure_busy
        TicketsFailure.FORBIDDEN -> R.string.remote_tickets_failure_forbidden
        TicketsFailure.NOT_FOUND -> R.string.remote_tickets_failure_not_found
        TicketsFailure.FAILED -> R.string.remote_tickets_write_failed
    }

/** Whether the last write left something to tell: an unknown outcome or a failure. */
internal fun hasWriteNotice(tickets: TicketsState): Boolean = tickets.outcomeUnknown || tickets.writeFailure != null

/** Any unknown outcome wins over the reason: a timeout carries none and the write is never retried. */
@Composable
private fun TicketWriteMessage(tickets: TicketsState) {
    val failure = tickets.writeFailure
    Text(
        stringResource(
            when {
                tickets.outcomeUnknown -> R.string.remote_tickets_outcome_unknown
                failure != null -> writeFailureText(failure)
                else -> R.string.remote_tickets_write_failed
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The result of the last write, with a dismiss; nothing when the last write went through. */
@Composable
internal fun TicketWriteNotice(tickets: TicketsState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!hasWriteNotice(tickets)) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag("ticketWriteNotice").semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) { TicketWriteMessage(tickets) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_tickets_dismiss)) }
        }
    }
}
