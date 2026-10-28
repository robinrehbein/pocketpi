package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A snapshot older than this, or any snapshot while the Mac is away, gets the "updated … ago" line. */
private const val TICKETS_STALE_MILLIS = 10 * 60_000L

private val ticketSectionOrder = listOf(TicketSection.ACCEPTANCE, TicketSection.WORKING, TicketSection.TODO)

@Composable
internal fun TicketsScreen(key: RemoteNavKey.Tickets, model: TicketsViewModel, navigator: RemoteNavigator) {
    val state by model.state.collectAsStateWithLifecycle()
    val usable = canUseTickets(state)
    val loadedHere = state.tickets.routeId == key.routeId
    // Reads once the capability is known, also after a reconnect or a restored page.
    LaunchedEffect(usable, loadedHere, state.tickets.loading, state.tickets.failure) {
        if (usable && (!loadedHere || (state.tickets.projects.isEmpty() && !state.tickets.loading && state.tickets.failure == null)))
            model.reload(false)
    }
    TicketsContent(
        state = if (loadedHere) state else state.copy(tickets = TicketsState(routeId = key.routeId, loading = usable)),
        now = System.currentTimeMillis(),
        onBack = navigator::back,
        onReload = { model.reload(true) },
        onRetry = { model.reload(true) },
        onOpen = { projectId, ref -> navigator.openTicket(key.routeId, projectId, ref) },
    )
}

@Composable
internal fun TicketDetailScreen(key: RemoteNavKey.Ticket, model: TicketsViewModel, navigator: RemoteNavigator) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usable = canUseTickets(state)
    val asked = state.tickets.routeId == key.routeId && state.tickets.detailRef == key.ref
    // A restored page, or a reconnect that lost the request, asks again.
    LaunchedEffect(usable, asked) {
        if (usable && !asked) model.open(key.projectId, key.ref)
    }
    TicketDetailContent(
        state = state,
        projectId = key.projectId,
        ref = key.ref,
        onBack = navigator::back,
        onRetry = { model.open(key.projectId, key.ref) },
        onOpenChat = { sessionId -> navigator.open(RemoteNavKey.Chat(key.routeId, key.projectId, sessionId)) },
        onOpenLink = { openHttpsLink(context, it) },
        // The accept, comment, status and start sheets arrive with B6.
        onAccept = ::sheetComesWithB6,
        onComment = ::sheetComesWithB6,
        onMove = ::sheetComesWithB6,
        onStart = ::sheetComesWithB6,
    )
}

private fun sheetComesWithB6() = Unit

// ---- Tickets screen -------------------------------------------------------------------------

@Composable
internal fun TicketsContent(
    state: RemoteState,
    now: Long,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (projectId: String, ref: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tickets = state.tickets
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val project = tickets.projects.firstOrNull { it.projectId == chosen } ?: tickets.projects.firstOrNull()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PageHeader(
                title = stringResource(R.string.remote_tickets_title),
                titleInPill = true,
                onBack = onBack,
                modifier = Modifier.statusBarsPadding(),
                actions = {
                    IconButton(
                        onClick = onReload,
                        enabled = !tickets.loading,
                        modifier = Modifier.size(48.dp).testTag("ticketsReload"),
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.remote_tickets_reload))
                    }
                },
                connection = connectionStatusLine(state),
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).testTag("ticketsList"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tickets.projects.size > 1)
                item("chooser") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tickets.projects.forEach { candidate ->
                            FilterChip(
                                selected = candidate.projectId == project?.projectId,
                                onClick = { chosen = candidate.projectId },
                                label = { Text(candidate.name) },
                            )
                        }
                    }
                }
            else if (project != null)
                item("name") {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            ticketsBody(state, project, now, onRetry, onOpen)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ticketsBody(
    state: RemoteState,
    project: TicketProject?,
    now: Long,
    onRetry: () -> Unit,
    onOpen: (String, String) -> Unit,
) {
    val tickets = state.tickets
    val page = project?.let { tickets.pages[it.projectId] }
    when {
        project == null && tickets.failure != null ->
            item("failure") { FailureBlock(tickets.failure, onRetry) }
        project == null && tickets.loading -> item("skeleton") { TicketsSkeleton() }
        // Nothing was asked yet; the screen asks, and the skeleton stands in for the answer.
        project == null && state.connected && canUseTickets(state) && tickets.projects.isEmpty() && !tickets.loading &&
            tickets.routeId == null -> item("skeleton") { TicketsSkeleton() }
        project == null ->
            item("none") {
                EmptyState(
                    Icons.Default.ConfirmationNumber,
                    stringResource(R.string.remote_tickets_no_projects_title),
                    stringResource(R.string.remote_tickets_no_projects_body),
                )
            }
        !project.available ->
            item("unavailable") { ReasonBlock(project.reason) }
        page == null && tickets.failure != null -> item("failure") { FailureBlock(tickets.failure, onRetry) }
        page == null -> item("skeleton") { TicketsSkeleton() }
        !page.available -> item("unavailable") { ReasonBlock(page.reason) }
        else -> {
            val syncedAt = page.syncedAt
            if (syncedAt != null && (!state.connected || now - syncedAt > TICKETS_STALE_MILLIS))
                item("stale") {
                    val minutes = ((now - syncedAt) / 60_000L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    Text(
                        stringResource(R.string.remote_tickets_stale, minutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ticketsStale"),
                    )
                }
            val bySection = page.items.groupBy { it.section }
            if (page.items.isEmpty())
                item("empty") {
                    EmptyState(
                        Icons.Default.ConfirmationNumber,
                        stringResource(R.string.remote_tickets_empty_title),
                        stringResource(R.string.remote_tickets_empty_body),
                    )
                }
            ticketSectionOrder.forEach { section ->
                val items = bySection[section].orEmpty()
                if (items.isNotEmpty())
                    item("section:${section.wire}") {
                        Section(stringResource(sectionTitle(section))) {
                            items.forEachIndexed { index, ticket ->
                                if (index > 0) HorizontalDivider()
                                TicketRow(ticket) { onOpen(project.projectId, ticket.ref) }
                            }
                        }
                    }
            }
        }
    }
}

@StringRes
private fun sectionTitle(section: TicketSection): Int =
    when (section) {
        TicketSection.ACCEPTANCE -> R.string.remote_tickets_section_acceptance
        TicketSection.WORKING -> R.string.remote_tickets_section_working
        TicketSection.TODO -> R.string.remote_tickets_section_todo
    }

@Composable
private fun TicketRow(ticket: Ticket, onClick: () -> Unit) {
    val caption = ticketCaption(ticket)
    Column(
        Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(ticket.ref, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ticket.title, style = MaterialTheme.typography.bodyLarge)
        if (caption != null)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "2 chats · 1 working" for linked chats, "PR open" for a task in review, otherwise nothing. */
@Composable
private fun ticketCaption(ticket: Ticket): String? {
    val chats = ticket.sessions.size
    if (chats > 0) {
        val working = ticket.sessions.count { it.status == SessionLinkStatus.RUNNING }
        val count = pluralStringResource(R.plurals.remote_tickets_chats_caption, chats, chats)
        return if (working == 0) count
        else stringResource(
            R.string.remote_tickets_caption_join, count,
            pluralStringResource(R.plurals.remote_tickets_chats_working, working, working),
        )
    }
    return if (ticket.prOpen) stringResource(R.string.remote_tickets_pr_open) else null
}

@Composable
private fun TicketsSkeleton() {
    val label = stringResource(R.string.remote_tickets_loading)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) { index ->
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkeletonBar(Modifier.fillMaxWidth(0.3f))
                SkeletonBar(Modifier.fillMaxWidth(if (index == 1) 0.6f else 0.9f))
                SkeletonBar(Modifier.fillMaxWidth(0.4f))
            }
        }
    }
}

@Composable
private fun ReasonBlock(reason: TicketReason?) {
    Text(
        stringResource(reasonText(reason)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp).testTag("ticketsReason"),
    )
}

@StringRes
private fun reasonText(reason: TicketReason?): Int =
    when (reason) {
        TicketReason.NOT_CONFIGURED -> R.string.remote_tickets_reason_not_configured
        TicketReason.MISSING_BOT_KEY -> R.string.remote_tickets_reason_missing_bot_key
        TicketReason.PLANE_UNREACHABLE -> R.string.remote_tickets_reason_plane_unreachable
        TicketReason.PLANE_REJECTED -> R.string.remote_tickets_reason_plane_rejected
        null -> R.string.remote_tickets_reason_unknown
    }

@StringRes
private fun failureText(failure: TicketsFailure): Int =
    when (failure) {
        TicketsFailure.OFFLINE -> R.string.remote_tickets_failure_offline
        TicketsFailure.UNSUPPORTED -> R.string.remote_tickets_failure_unsupported
        TicketsFailure.BUSY -> R.string.remote_tickets_failure_busy
        TicketsFailure.FORBIDDEN -> R.string.remote_tickets_failure_forbidden
        TicketsFailure.NOT_FOUND -> R.string.remote_tickets_failure_not_found
        TicketsFailure.FAILED -> R.string.remote_tickets_failure_failed
    }

@Composable
private fun FailureBlock(failure: TicketsFailure, onRetry: () -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(failureText(failure)), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.remote_tickets_retry)) }
    }
}

// ---- Ticket detail --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TicketDetailContent(
    state: RemoteState,
    projectId: String,
    ref: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
    onOpenLink: (String) -> Boolean,
    onAccept: () -> Unit,
    onComment: () -> Unit,
    onMove: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = ticketDetailView(state, ref)
    val ticket = (view as? TicketDetailView.Ready)?.detail?.ticket
    val project = state.tickets.projects.firstOrNull { it.projectId == projectId }
    // The host builds the URL from its configured Plane origin; the app opens it only as https.
    val planeUrl = ticket?.url?.takeIf(::validHttpsUrl)
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PageHeader(
                title = ref,
                titleInPill = true,
                onBack = onBack,
                modifier = Modifier.statusBarsPadding(),
                actions =
                    if (planeUrl == null) null
                    else {
                        {
                            IconButton(onClick = { onOpenLink(planeUrl) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    stringResource(R.string.remote_ticket_open_plane),
                                )
                            }
                        }
                    },
                connection = connectionStatusLine(state),
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (view) {
                TicketDetailView.Loading -> TicketsSkeleton()
                is TicketDetailView.Unavailable -> ReasonBlock(view.reason)
                is TicketDetailView.Failed -> FailureBlock(view.failure, onRetry)
                is TicketDetailView.Ready ->
                    TicketBody(
                        view.detail, view.detail.ticket!!, project, state.connected,
                        onOpenChat, onAccept, onComment, onMove, onStart,
                    )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketBody(
    detail: TicketDetail,
    ticket: Ticket,
    project: TicketProject?,
    connected: Boolean,
    onOpenChat: (String) -> Unit,
    onAccept: () -> Unit,
    onComment: () -> Unit,
    onMove: () -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (project != null)
            Text(
                stringResource(R.string.remote_ticket_project_ref, project.name, ticket.ref),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Text(
            ticket.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(ticket.state)
            priorityLabel(ticket.priority)?.let { Pill(stringResource(it)) }
            ticket.type?.let { Pill(it) }
        }
    }

    TicketActions(ticket, project, connected, onAccept, onComment, onMove, onStart, onOpenChat)

    if (ticket.sessions.isNotEmpty())
        Section(stringResource(R.string.remote_ticket_chats)) {
            ticket.sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                val title = session.title.ifBlank { stringResource(R.string.remote_ticket_chat_untitled) }
                val status = stringResource(sessionStatusLabel(session.status))
                Column(
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Button) { onOpenChat(session.id) }
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

    Section(stringResource(R.string.remote_ticket_description)) {
        if (detail.description.isBlank())
            Text(
                stringResource(R.string.remote_ticket_description_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else MarkdownText(detail.description)
        if (detail.descriptionTruncated)
            Text(
                stringResource(R.string.remote_ticket_description_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }

    Section(stringResource(R.string.remote_ticket_comments)) {
        if (detail.comments.isEmpty())
            Text(
                stringResource(R.string.remote_ticket_comments_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        detail.comments.sortedByDescending { it.createdAt }.forEachIndexed { index, comment ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.remote_ticket_comment_meta,
                        comment.author,
                        DateUtils.formatDateTime(
                            context, comment.createdAt,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MarkdownText(comment.text)
            }
        }
        if (detail.commentsTruncated)
            Text(
                stringResource(R.string.remote_ticket_comments_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/**
 * The buttons the host allows for this ticket. Writing needs a writable project and a connected
 * Mac; otherwise one line says why and no write button shows. Linked chats stay reachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketActions(
    ticket: Ticket,
    project: TicketProject?,
    connected: Boolean,
    onAccept: () -> Unit,
    onComment: () -> Unit,
    onMove: () -> Unit,
    onStart: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val writable = project?.writable == true
    val accept = writable && TicketAction.ACCEPT in ticket.actions
    val start = writable && TicketAction.START in ticket.actions
    val comment = writable && TicketAction.COMMENT in ticket.actions
    val move = writable && TicketAction.MOVE in ticket.actions
    val chat = ticket.sessions.firstOrNull { it.status == SessionLinkStatus.WAITING }
        ?: ticket.sessions.firstOrNull { it.status == SessionLinkStatus.RUNNING }
        ?: ticket.sessions.firstOrNull()
    val primary = accept || start
    if (primary || comment || move || (chat != null))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (accept) Button(onClick = onAccept, enabled = connected) { Text(stringResource(R.string.remote_ticket_accept)) }
                else if (start) Button(onClick = onStart, enabled = connected) { Text(stringResource(R.string.remote_ticket_start)) }
                else if (chat != null) Button(onClick = { onOpenChat(chat.id) }) { Text(stringResource(R.string.remote_ticket_open_chat)) }
                if (comment)
                    OutlinedButton(onClick = onComment, enabled = connected) { Text(stringResource(R.string.remote_ticket_comment)) }
                if (move)
                    OutlinedButton(onClick = onMove, enabled = connected) { Text(stringResource(R.string.remote_ticket_status)) }
            }
            if ((primary || comment || move) && !connected)
                Text(
                    stringResource(R.string.remote_ticket_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    if (!writable && ticket.actions.isNotEmpty() && project?.writeBlock != null)
        Text(
            stringResource(
                when (project.writeBlock) {
                    TicketWriteBlock.WRITES_OFF -> R.string.remote_ticket_write_block_writes_off
                    TicketWriteBlock.MISSING_USER_KEY -> R.string.remote_ticket_write_block_missing_user_key
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ticketWriteBlock"),
        )
}

@Composable
private fun Pill(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@StringRes
private fun priorityLabel(priority: TicketPriority): Int? =
    when (priority) {
        TicketPriority.URGENT -> R.string.remote_ticket_priority_urgent
        TicketPriority.HIGH -> R.string.remote_ticket_priority_high
        TicketPriority.MEDIUM -> R.string.remote_ticket_priority_medium
        TicketPriority.LOW -> R.string.remote_ticket_priority_low
        TicketPriority.NONE -> null
    }

@StringRes
private fun sessionStatusLabel(status: SessionLinkStatus): Int =
    when (status) {
        SessionLinkStatus.RUNNING -> R.string.remote_status_working
        SessionLinkStatus.WAITING -> R.string.remote_status_needs_you
        SessionLinkStatus.IDLE -> R.string.remote_status_ready
        SessionLinkStatus.OFFLINE -> R.string.remote_status_offline
    }
