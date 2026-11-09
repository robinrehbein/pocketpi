package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// "Review all" on the Projects page's Ready group: the finished chats one after another, each as a
// card with Close (or Hide, where the phone can never close it), Keep and Open. The queue is a
// snapshot taken when the review starts, so a refresh does not reorder it; every step looks the
// chat up again and skips one that left the group meanwhile.

/** How long a new card ignores taps on its primary button. */
internal const val READY_REVIEW_ARM_MILLIS = 450L

/** A chat of a review queue, by its project and session ID. */
internal data class ReadyReviewEntry(val projectId: String, val sessionId: String)

/** The queue "Review all" walks: [QuickChats.readyQueue] as IDs, in its order. */
internal fun readyReviewQueue(readyQueue: List<ProjectChatSummary>): List<ReadyReviewEntry> =
    readyQueue.mapNotNull { chat -> chat.session.optionalText("id")?.let { ReadyReviewEntry(chat.projectId, it) } }

/** The card a review shows: the chat at [index] of its queue. */
internal data class ReadyReviewStep(val index: Int, val chat: ProjectChatSummary)

/**
 * The first chat of [queue] at or after [position] that is still ready: among [mainChats] (the
 * output of [mainSessionChats]), live, idle, open and top-level (the Ready group's rule), not hidden
 * or pinned by [marks] and not [closing]. Null when none is left: the review is done. A chat that
 * started working or waiting again, was closed or hidden elsewhere, or left the featured chats is
 * skipped; one that becomes ready again before the review reaches it is shown.
 */
internal fun readyReviewStep(
    queue: List<ReadyReviewEntry>,
    position: Int,
    mainChats: List<ProjectChatSummary>,
    connected: Boolean,
    marks: Map<String, SessionMark>,
    closing: Set<String>,
): ReadyReviewStep? {
    if (!connected) return null
    val byEntry = mainChats.associateBy { ReadyReviewEntry(it.projectId, it.session.optionalText("id").orEmpty()) }
    for (index in position.coerceAtLeast(0) until queue.size) {
        val entry = queue[index]
        val chat = byEntry[entry] ?: continue
        val ready =
            chat.verified &&
                isReadyRow(
                    chat.session.optionalText("status"),
                    chat.session.optionalText("origin"),
                    chat.session.optionalText("parentSessionId"),
                ) &&
                !projectChatHidden(chat, marks) &&
                marks[entry.sessionId]?.pinned != true &&
                entry.sessionId !in closing
        if (ready) return ReadyReviewStep(index, chat)
    }
    return null
}

/** What a review card offers besides Keep and Open; the same verb the chat's row offers. */
internal enum class ReadyReviewVerb {
    /** The host can close the chat ([closableMainChat]); the button is enabled while [closableProjectChat] holds. */
    CLOSE,

    /** The phone can never close the chat, so it can hide it ([canHideSession]). */
    HIDE,
    NONE,
}

/** The verb of [chat], whose list row is [item] (built as its row builds it, with the marks). */
internal fun readyReviewVerb(state: RemoteState, chat: ProjectChatSummary, item: SessionListItem): ReadyReviewVerb =
    when {
        // Without the host's close support the card could only show a Close that never enables.
        closableMainChat(state, chat) && SESSION_CLOSE_CAPABILITY in state.capabilities -> ReadyReviewVerb.CLOSE
        canHideSession(item, state.capabilities) -> ReadyReviewVerb.HIDE
        else -> ReadyReviewVerb.NONE
    }

/** A chat hidden during a review, kept so the sheet can undo it ([SessionMarksStore.undoHide]). */
internal data class ReadyReviewHide(
    val entry: ReadyReviewEntry,
    val title: String,
    val previous: SessionMark?,
    val applied: SessionMark?,
)

/**
 * A review in progress: its [queue] snapshot, the [position] of the next card to look for, and the
 * chats it hid, latest last. [lastClosedTitle] names the chat the last Close was sent for, so a
 * refusal that arrives after the next card shows can say which chat it is about. [errorAtStart] is the page's error when it began, so the sheet shows only
 * a newer one (such as a refused close), which the page would show under the sheet.
 */
internal data class ReadyReview(
    val queue: List<ReadyReviewEntry>,
    val position: Int = 0,
    val hides: List<ReadyReviewHide> = emptyList(),
    val errorAtStart: Int? = null,
    val lastClosedTitle: String? = null,
)

/**
 * The review card in a sheet. [item] is the chat's list row, [projectName] its project, [old] whether
 * it wears the "Old" chip. [terminal]: Close ends a pi the user started in a terminal on the Mac, so
 * the card asks once more before it sends. [lastHidden] is the title of the chat hidden last in this
 * review, offered for Undo; [error] a message to show, such as a refused close.
 */
@Composable
internal fun ReadyReviewSheet(
    item: SessionListItem,
    projectName: String,
    index: Int,
    total: Int,
    now: Long,
    old: Boolean,
    verb: ReadyReviewVerb,
    closeEnabled: Boolean,
    terminal: Boolean,
    lastHidden: String?,
    error: String?,
    onClose: () -> Unit,
    onHide: () -> Unit,
    onKeep: () -> Unit,
    onOpen: () -> Unit,
    onUndoHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The terminal question belongs to one card; the next card starts without it.
    var confirmingTerminal by remember(item.id) { mutableStateOf(false) }
    // The primary button keeps its place from card to card, so a double tap would act on the next
    // chat unseen. It ignores taps for a moment after a new card shows.
    var armed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        delay(READY_REVIEW_ARM_MILLIS)
        armed = true
    }
    GlassSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("readyReviewSheet")) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(
                stringResource(R.string.remote_ready_review_title),
                subtitle = stringResource(R.string.remote_ready_review_progress, index + 1, total),
                padding = PaddingValues(start = 4.dp),
            )
            ReadyReviewCard(item, projectName, now, old)
            if (error != null)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("readyReviewError").semantics { liveRegion = LiveRegionMode.Polite },
                )
            if (confirmingTerminal) {
                Text(
                    stringResource(R.string.remote_session_close_terminal_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                SheetPrimaryButton(
                    onClick = { if (armed) onClose() },
                    enabled = closeEnabled,
                    modifier = Modifier.testTag("readyReviewConfirmClose"),
                ) { Text(stringResource(R.string.remote_ready_review_close)) }
                SheetSecondaryButton(onClick = { confirmingTerminal = false }, modifier = Modifier.testTag("readyReviewCancelClose")) {
                    Text(stringResource(R.string.remote_cancel))
                }
            } else {
                when (verb) {
                    ReadyReviewVerb.CLOSE ->
                        SheetPrimaryButton(
                            onClick = { if (armed) { if (terminal) confirmingTerminal = true else onClose() } },
                            enabled = closeEnabled,
                            modifier = Modifier.testTag("readyReviewClose"),
                        ) { Text(stringResource(R.string.remote_ready_review_close)) }
                    ReadyReviewVerb.HIDE ->
                        SheetPrimaryButton(onClick = { if (armed) onHide() }, modifier = Modifier.testTag("readyReviewHide")) {
                            Text(stringResource(R.string.remote_menu_hide))
                        }
                    ReadyReviewVerb.NONE -> Unit
                }
                // Keep sits apart from Close, as a glass pill under the accent one, so a quick tap does
                // not close by mistake.
                SheetSecondaryButton(onClick = onKeep, modifier = Modifier.padding(top = 4.dp).testTag("readyReviewKeep")) {
                    Text(stringResource(R.string.remote_ready_review_keep))
                }
                TextButton(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("readyReviewOpen"),
                ) { Text(stringResource(R.string.remote_ready_review_open)) }
            }
            if (lastHidden != null)
                Row(
                    Modifier.fillMaxWidth().testTag("readyReviewHidden"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.remote_session_hidden_snackbar_a11y, lastHidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onUndoHide,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("readyReviewUndo"),
                    ) { Text(stringResource(R.string.remote_undo)) }
                }
        }
    }
}

/**
 * What the card says about the chat, read by TalkBack as one node that is announced when the next
 * card replaces it: the review's only announcement per step.
 */
@Composable
internal fun ReadyReviewCard(item: SessionListItem, projectName: String, now: Long, old: Boolean) {
    val title = sessionTitleLabel(item)
    val age =
        item.updatedAt?.let {
            DateUtils.getRelativeTimeSpanString(minOf(it, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
        }
    val oldLabel = stringResource(R.string.remote_session_old)
    val compactingLabel = stringResource(R.string.remote_status_compacting)
    val contextFill = contextFill(item.context)
    val contextLabel = contextFillDescription(contextFill)
    val description =
        listOfNotNull(
            title,
            projectName,
            age,
            oldLabel.takeIf { old },
            compactingLabel.takeIf { item.compacting },
            item.preview,
            contextLabel,
        ).joinToString(", ")
    SheetSection(
        Modifier.testTag("readyReviewCard").clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
            if (contextFill != null) contextFillShown = contextFill
        }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                projectName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (age != null)
                    Text(
                        age,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (item.compacting)
                    Text(
                        compactingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                if (old)
                    ProjectCountBadge(
                        oldLabel,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "readyReviewOld",
                        Modifier,
                    )
            }
            if (item.preview != null)
                Text(
                    item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).testTag("readyReviewPreview"),
                )
            if (contextFill != null) ContextFillBar(contextFill, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The running review: looks the current chat up, shows its card and carries out the verbs. A
 * composable of its own so [RemoteScreen] stays small (R8 miscompiles it when it grows) and only this
 * scope reads [projectsNow]'s minute tick. [showHideUndo] puts the page's Undo snackbar up.
 */
@Composable
internal fun ReadyReviewHost(
    review: ReadyReview,
    onReviewChange: (ReadyReview?) -> Unit,
    key: RemoteNavKey,
    model: DestinationViewModel,
    state: RemoteState,
    projectSelected: Boolean,
    routeMarks: Map<String, SessionMark>,
    sessionMarkStore: SessionMarksStore?,
    sessionCompactionNow: Long,
    projectsNow: State<Long>,
    navigator: RemoteNavigator,
    showHideUndo: (SessionMarksStore, String, String, String, SessionMark?, SessionMark?) -> Unit,
) {
    val page = key as? RemoteNavKey.Projects
    val projectsModel = model as? ProjectsViewModel
    val step =
        if (page == null || projectsModel == null || projectSelected) null
        else readyReviewStep(
            review.queue, review.position, mainSessionChats(state.projectChats), state.connected, routeMarks,
            state.closingSessions,
        )
    fun endReview() {
        onReviewChange(null)
        // The sheet's Undo goes with it, so the page's snackbar offers the last hide once more.
        val last = review.hides.lastOrNull() ?: return
        val store = sessionMarkStore ?: return
        val route = state.host?.routeId ?: return
        showHideUndo(store, route, last.entry.sessionId, last.title, last.previous, last.applied)
    }
    // Nothing left to review (or the connection dropped, which takes every chat out of Ready).
    LaunchedEffect(step == null) { if (step == null) endReview() }
    if (step == null || page == null || projectsModel == null) return
    val chat = step.chat
    val sessionId = chat.session.text("id")
    val nowMillis = projectsNow.value
    val item = projectChatItem(
        chat, routeMarks, state.connected, state.sessionCompactions, sessionCompactionNow,
        { ticketRefForSession(state, it) },
    )
    val itemTitle = sessionTitleLabel(item)
    val next = { onReviewChange(review.copy(position = step.index + 1)) }
    val errorText = state.error?.takeIf { it != review.errorAtStart }?.let { stringResource(it) }
    ReadyReviewSheet(
        item = item,
        projectName = chat.projectName,
        index = step.index,
        total = review.queue.size,
        now = nowMillis,
        old = readyIsOld(chat.session["updatedAt"]?.jsonPrimitive?.longOrNull, nowMillis),
        verb = readyReviewVerb(state, chat, item),
        // Read live, as the close dialog does, so a chat that started running is not closed.
        closeEnabled = closableProjectChat(state, chat.projectId, sessionId),
        terminal = item.liveMacTui && !item.hostWorker,
        lastHidden = review.hides.lastOrNull()?.title,
        error =
            errorText?.let {
                review.lastClosedTitle?.let { title -> stringResource(R.string.remote_ready_review_close_error, title, it) } ?: it
            },
        onClose = {
            // Only a close that was sent marks the chat read and moves on; otherwise the card
            // stays, with Close disabled until the page can close again.
            if (projectsModel.closeChat(chat.projectId, sessionId)) {
                state.host?.routeId?.let {
                    sessionMarkStore?.markSeen(it, sessionId, item.updatedAt, System.currentTimeMillis())
                }
                onReviewChange(review.copy(position = step.index + 1, lastClosedTitle = itemTitle))
            }
        },
        onHide = hide@{
            val store = sessionMarkStore ?: return@hide
            val route = state.host?.routeId ?: return@hide
            val previous = store.hide(route, sessionId, sessionUpdatedAt(chat.session), System.currentTimeMillis())
            val hide = ReadyReviewHide(
                ReadyReviewEntry(chat.projectId, sessionId), itemTitle, previous,
                store.mark(route, sessionId),
            )
            onReviewChange(review.copy(position = step.index + 1, hides = review.hides + hide))
        },
        onKeep = {
            state.host?.routeId?.let {
                sessionMarkStore?.markSeen(it, sessionId, item.updatedAt, System.currentTimeMillis())
            }
            next()
        },
        onOpen = {
            endReview()
            navigator.open(RemoteNavKey.Chat(page.routeId, chat.projectId, sessionId))
        },
        onUndoHide = undo@{
            val last = review.hides.lastOrNull() ?: return@undo
            val route = state.host?.routeId ?: return@undo
            // The chat is back on the page; the review goes on from the card it shows.
            sessionMarkStore?.undoHide(route, last.entry.sessionId, last.previous, last.applied)
            onReviewChange(review.copy(hides = review.hides.dropLast(1)))
        },
        onDismiss = { endReview() },
    )
}
