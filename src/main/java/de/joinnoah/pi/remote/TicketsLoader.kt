package de.joinnoah.pi.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The `tickets.*` requests. Implementations throw [RemoteRequestException] on errors. */
internal interface TicketSource {
    suspend fun ticketProjects(): List<TicketProject>

    suspend fun ticketsPage(projectId: String, cursor: String?): TicketsPage

    suspend fun ticketDetail(projectId: String, ref: String): TicketDetail

    suspend fun reloadTickets(projectId: String): Long

    suspend fun commentTicket(projectId: String, ref: String, text: String)

    suspend fun moveTicket(projectId: String, ref: String, state: String, comment: String?): Boolean

    suspend fun startTicket(projectId: String, ref: String, note: String?): String

    suspend fun ticketStartStatus(startId: String): TicketStart
}

/** Why the last tickets read or write failed; mapped like [ChangesFailure]. */
enum class TicketsFailure { UNSUPPORTED, OFFLINE, BUSY, FORBIDDEN, NOT_FOUND, FAILED }

/** An acceptance the user queued; the host hears of it at [deadline] unless it is undone. */
data class PendingAccept(val projectId: String, val ref: String, val comment: String, val deadline: Long)

/**
 * Tickets of the shown Mac.
 *
 * [failure] is the last failed read; the data of earlier reads stays. [writeFailure] is the last
 * failed comment, move, accept or start. [outcomeUnknown] is set with it when the request may have
 * reached the host (it timed out, the connection ended after sending, or the host said
 * `outcome_unknown`, or its answer could not be read): the app never retries such a write, reads the
 * ticket again, and the UI says the result is unknown. [writeRef] names the ticket both are about.
 * [pageFailures] holds the last failed list read per project, so one failed project of several
 * shows its failure instead of waiting for a page that is not coming.
 */
data class TicketsState(
    val routeId: String? = null,
    val projects: List<TicketProject> = emptyList(),
    val pages: Map<String, TicketsPage> = emptyMap(),
    val loading: Boolean = false,
    val failure: TicketsFailure? = null,
    val detail: TicketDetail? = null,
    val detailLoading: Boolean = false,
    /** The ref the last [TicketsLoader.loadDetail] asked for; [detail] and [detailFailure] answer it. */
    val detailRef: String? = null,
    val pendingAccept: PendingAccept? = null,
    val start: TicketStart? = null,
    val writeFailure: TicketsFailure? = null,
    /** The last failed [TicketsLoader.loadDetail]; separate from the list's [failure]. */
    val detailFailure: TicketsFailure? = null,
    val outcomeUnknown: Boolean = false,
    /** The ticket [writeFailure] and [outcomeUnknown] are about. */
    val writeRef: String? = null,
    val pageFailures: Map<String, TicketsFailure> = emptyMap(),
)

/** Tickets in the acceptance section of all projects, newest change first. */
fun acceptanceItems(state: TicketsState): List<Pair<TicketProject, Ticket>> =
    state.projects
        .flatMap { project ->
            state.pages[project.projectId]?.items.orEmpty()
                .filter { it.section == TicketSection.ACCEPTANCE }
                .map { project to it }
        }
        .sortedByDescending { it.second.updatedAt }

/** How long an accept can be undone. */
internal const val TICKET_ACCEPT_UNDO_MILLIS = 5_000L

/** A non-forced refresh within this time of the last one is skipped. */
internal const val TICKETS_FRESH_MILLIS = 30_000L

internal const val TICKET_START_POLL_MILLIS = 1_000L
internal const val TICKET_START_MAX_MILLIS = 10 * 60_000L
private const val MAX_TICKET_PAGES = 10
private const val ACCEPT_STATE = "Done"

/**
 * Loads [RemoteState.tickets]. Replies of an older read, or of another Mac, are dropped by version
 * counters and the route check in [write].
 */
internal class TicketsLoader(
    private val scope: CoroutineScope,
    private val source: TicketSource,
    private val current: () -> RemoteState,
    private val now: () -> Long = System::currentTimeMillis,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
) {
    private var listJob: Job? = null
    private var listVersion = 0L
    private var detailVersion = 0L
    private var lastLoadedAt: Pair<String, Long>? = null
    private var acceptJob: Job? = null
    private var startJob: Job? = null
    private var startVersion = 0L
    /** The ticket of the start job [startJob] follows. */
    private var startRef: String? = null
    /** The tickets of the writes sent and not answered yet, oldest first. */
    private val writingRefs = mutableListOf<String>()

    private fun routeId() = current().host?.routeId

    private fun usableRoute(): String? = routeId()?.takeIf { canUseTickets(current()) }

    private fun write(routeId: String, block: (TicketsState) -> TicketsState) =
        update { state ->
            if (state.tickets.routeId != routeId || state.host?.routeId != routeId) state
            else state.copy(tickets = block(state.tickets))
        }

    /** Reads the projects and their tickets; [force] has the host read Plane again first. */
    fun refresh(force: Boolean) {
        val routeId = usableRoute() ?: return
        val loaded = lastLoadedAt
        if (!force && listJob?.isActive != true && loaded != null && loaded.first == routeId &&
            now() - loaded.second in 0 until TICKETS_FRESH_MILLIS
        ) return
        listJob?.cancel()
        val version = ++listVersion
        update { state ->
            val tickets = state.tickets
            state.copy(
                tickets =
                    if (tickets.routeId == routeId) tickets.copy(loading = true, failure = null, pageFailures = emptyMap())
                    else TicketsState(routeId = routeId, loading = true)
            )
        }
        listJob = scope.launch {
            try {
                val projects = source.ticketProjects()
                if (version != listVersion) return@launch
                write(routeId) { it.copy(projects = projects) }
                var firstFailure: Exception? = null
                var loadedAny = false
                for (project in projects) {
                    if (!project.available) continue
                    if (force) {
                        // The host coalesces reloads; a refused one still leaves its snapshot to read.
                        try {
                            source.reloadTickets(project.projectId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Diagnostics.caught("tickets.reload", e)
                        }
                        if (version != listVersion) return@launch
                    }
                    try {
                        val page = readPages(project.projectId)
                        if (version != listVersion) return@launch
                        loadedAny = true
                        write(routeId) { it.copy(pages = it.pages + (project.projectId to page)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diagnostics.caught("tickets.list", e)
                        if (firstFailure == null) firstFailure = e
                        val failure = failure(e)
                        write(routeId) { it.copy(pageFailures = it.pageFailures + (project.projectId to failure)) }
                    }
                }
                if (version != listVersion) return@launch
                val failed = firstFailure?.takeIf { !loadedAny }
                // A failed project is read again on the next refresh, however soon it comes.
                if (firstFailure == null) lastLoadedAt = routeId to now()
                write(routeId) { it.copy(loading = false, failure = failed?.let(::failure)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("tickets.projects", e)
                if (version == listVersion) write(routeId) { it.copy(loading = false, failure = failure(e)) }
            }
        }
    }

    /** All pages of a project, merged into one; stops after [MAX_TICKET_PAGES]. */
    private suspend fun readPages(projectId: String): TicketsPage {
        var page = source.ticketsPage(projectId, null)
        var merged = page
        var pages = 1
        while (page.available && page.nextCursor != null && pages < MAX_TICKET_PAGES) {
            page = source.ticketsPage(projectId, page.nextCursor)
            pages++
            merged = merged.copy(items = merged.items + page.items, nextCursor = page.nextCursor)
        }
        return merged
    }

    fun loadDetail(projectId: String, ref: String) {
        val routeId = usableRoute() ?: return
        val version = ++detailVersion
        update { state ->
            val tickets = state.tickets
            state.copy(
                tickets =
                    if (tickets.routeId != routeId) TicketsState(routeId = routeId, detailLoading = true, detailRef = ref)
                    else tickets.copy(
                        detail = tickets.detail?.takeIf { it.ticket?.ref == ref },
                        detailLoading = true,
                        detailRef = ref,
                        detailFailure = null,
                    )
            )
        }
        scope.launch {
            try {
                val detail = source.ticketDetail(projectId, ref)
                if (version == detailVersion) write(routeId) { it.copy(detail = detail, detailLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("tickets.get", e)
                if (version == detailVersion) write(routeId) { it.copy(detailLoading = false, detailFailure = failure(e)) }
            }
        }
    }

    private fun writable(routeId: String, projectId: String) =
        current().tickets.takeIf { it.routeId == routeId }?.projects
            ?.any { it.projectId == projectId && it.writable } == true

    /**
     * Accepts [ref] (moves it to Done with [comment]) after [TICKET_ACCEPT_UNDO_MILLIS], unless
     * [undo] or [connectionLost] comes first. A second accept while one waits is ignored.
     */
    fun queueAccept(projectId: String, ref: String, comment: String) {
        val routeId = usableRoute() ?: return
        if (current().tickets.pendingAccept != null || comment.isEmpty() ||
            comment.length > MAX_TICKET_MOVE_COMMENT_CHARS || !writable(routeId, projectId)
        ) return
        if (!valid(routeId, ref) { requireWrite(projectId, ref); requireTicketMove(ACCEPT_STATE, comment) }) return
        val deadline = now() + TICKET_ACCEPT_UNDO_MILLIS
        write(routeId) { it.copy(pendingAccept = PendingAccept(projectId, ref, comment, deadline), writeFailure = null, outcomeUnknown = false) }
        acceptJob = scope.launch {
            delay(TICKET_ACCEPT_UNDO_MILLIS)
            val pending = current().tickets.pendingAccept
            if (pending == null || pending.deadline != deadline || usableRoute() != routeId) {
                return@launch
            }
            write(routeId) { it.copy(pendingAccept = null) }
            // Its own coroutine: losing the connection must not cancel a request already sent.
            scope.launch { send(routeId, projectId, ref) { source.moveTicket(projectId, ref, ACCEPT_STATE, comment) } }
        }
    }

    /** The ids every write carries, checked as the host checks them. */
    private fun requireWrite(projectId: String, ref: String) {
        requireTicketProjectId(projectId)
        requireTicketRef(ref)
    }

    /**
     * Makes the host's checks of a write before it is sent, so an exception the write throws later
     * always came after sending. A write that fails them is never sent and reported as failed.
     */
    private fun valid(routeId: String, ref: String, check: () -> Unit): Boolean =
        try {
            check()
            true
        } catch (e: IllegalArgumentException) {
            Diagnostics.caught("tickets.write.invalid", e)
            write(routeId) { it.copy(writeFailure = TicketsFailure.FAILED, writeRef = ref, outcomeUnknown = false) }
            false
        }

    /** Takes the queued accept back; nothing has been sent. */
    fun undo() {
        acceptJob?.cancel()
        acceptJob = null
        val routeId = current().tickets.routeId ?: return
        write(routeId) { it.copy(pendingAccept = null) }
    }

    /** Forgets the last write's failure or unknown outcome once the user has seen it. */
    fun dismissNotice() {
        val routeId = current().tickets.routeId ?: return
        write(routeId) { it.copy(writeFailure = null, outcomeUnknown = false, writeRef = null) }
    }

    fun comment(projectId: String, ref: String, text: String) {
        val routeId = usableRoute() ?: return
        if (!writable(routeId, projectId)) return
        if (!valid(routeId, ref) { requireWrite(projectId, ref); requireTicketComment(text) }) return
        write(routeId) { it.copy(writeFailure = null, outcomeUnknown = false, writeRef = null) }
        scope.launch { send(routeId, projectId, ref) { source.commentTicket(projectId, ref, text) } }
    }

    fun move(projectId: String, ref: String, state: String, comment: String?) {
        val routeId = usableRoute() ?: return
        if (!writable(routeId, projectId)) return
        if (!valid(routeId, ref) { requireWrite(projectId, ref); requireTicketMove(state, comment) }) return
        write(routeId) { it.copy(writeFailure = null, outcomeUnknown = false, writeRef = null) }
        scope.launch { send(routeId, projectId, ref) { source.moveTicket(projectId, ref, state, comment) } }
    }

    /** One write, never retried; afterwards the ticket is read again whatever happened. */
    private suspend fun send(routeId: String, projectId: String, ref: String, request: suspend () -> Any?) {
        writingRefs += ref
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("tickets.write", e)
            write(routeId) { it.copy(writeFailure = failure(e), outcomeUnknown = outcomeUnknown(e), writeRef = ref) }
        } finally {
            writingRefs.remove(ref)
        }
        reread(routeId, projectId, ref)
    }

    private fun reread(routeId: String, projectId: String, ref: String) {
        if (usableRoute() != routeId) return
        val version = listVersion
        scope.launch {
            try {
                val page = readPages(projectId)
                if (version != listVersion) return@launch
                write(routeId) { it.copy(pages = it.pages + (projectId to page), pageFailures = it.pageFailures - projectId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("tickets.reread", e)
            }
        }
        if (current().tickets.detail?.ticket?.ref == ref) loadDetail(projectId, ref)
    }

    /**
     * Starts the start job of [ref] and polls its status every [TICKET_START_POLL_MILLIS] until it
     * is not running, for at most [TICKET_START_MAX_MILLIS]. While a job runs, a second start is
     * refused as [TicketsFailure.BUSY] and the running job is kept.
     */
    fun start(projectId: String, ref: String, note: String?) {
        val routeId = usableRoute() ?: return
        if (!writable(routeId, projectId)) return
        if (current().tickets.start?.state == TicketStartState.RUNNING) {
            write(routeId) { it.copy(writeFailure = TicketsFailure.BUSY, writeRef = ref, outcomeUnknown = false) }
            return
        }
        if (!valid(routeId, ref) { requireWrite(projectId, ref); requireTicketNote(note) }) return
        val version = ++startVersion
        write(routeId) { it.copy(start = null, writeFailure = null, outcomeUnknown = false, writeRef = null) }
        startJob?.cancel()
        startRef = ref
        startJob = scope.launch {
            val startId =
                try {
                    source.startTicket(projectId, ref, note)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("tickets.start", e)
                    if (version == startVersion)
                        write(routeId) { it.copy(writeFailure = failure(e), outcomeUnknown = outcomeUnknown(e), writeRef = ref) }
                    reread(routeId, projectId, ref)
                    return@launch
                }
            if (version != startVersion) return@launch
            write(routeId) {
                it.copy(
                    start = TicketStart(
                        startId, TicketStartState.RUNNING, TicketStartStep.WORKTREE, false, null, null, null,
                        projectId, ref,
                    )
                )
            }
            val startedAt = now()
            while (now() - startedAt < TICKET_START_MAX_MILLIS) {
                delay(TICKET_START_POLL_MILLIS)
                if (version != startVersion) return@launch
                try {
                    val status = source.ticketStartStatus(startId)
                    if (version != startVersion) return@launch
                    write(routeId) { it.copy(start = status.copy(projectId = projectId, ref = ref)) }
                    if (status.state != TicketStartState.RUNNING) {
                        reread(routeId, projectId, ref)
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("tickets.start.status", e)
                    // A read that timed out is asked again; any other answer ends the polling.
                    if (e.requestKind == RemoteRequestException.Kind.TIMEOUT) continue
                    // The job's outcome is not known any more, but a new start must be possible.
                    if (version == startVersion)
                        write(routeId) {
                            it.copy(
                                start = it.start?.copy(state = TicketStartState.FAILED),
                                writeFailure = failure(e),
                                outcomeUnknown = true,
                                writeRef = ref,
                            )
                        }
                    reread(routeId, projectId, ref)
                    return@launch
                }
            }
            if (version != startVersion) return@launch
            write(routeId) {
                it.copy(
                    start = it.start?.copy(state = TicketStartState.FAILED, error = TicketStartError.TIMEOUT),
                    outcomeUnknown = true,
                    writeRef = ref,
                )
            }
            reread(routeId, projectId, ref)
        }
    }

    /**
     * The connection ended or the Mac changed: a queued accept is dropped unsent (and the notice says
     * so), polling stops. A detail read it cut off is forgotten, so the ticket page asks again once
     * tickets can be used.
     */
    fun connectionLost() {
        acceptJob?.cancel()
        acceptJob = null
        listJob?.cancel()
        listVersion++
        detailVersion++
        val polling = startJob?.isActive == true
        // A write in flight is cut off with its answer; the next connection reads the tickets again.
        val writing = writingRefs.lastOrNull()
        val unknownRef = startRef.takeIf { polling } ?: writing
        lastLoadedAt = null
        startJob?.cancel()
        startJob = null
        startVersion++
        update { state ->
            val tickets = state.tickets
            val dropped = tickets.pendingAccept
            if (tickets.routeId == null) state
            else state.copy(
                tickets = tickets.copy(
                    loading = false,
                    detailLoading = false,
                    // A detail already shown stays; one still loading is asked for again.
                    detailRef =
                        if (tickets.detailLoading && tickets.detail?.ticket?.ref != tickets.detailRef) null
                        else tickets.detailRef,
                    pendingAccept = null,
                    // The job may still run on the Mac; what it did is read again after reconnecting.
                    start = if (polling) null else tickets.start,
                    // An unknown outcome wins over the dropped accept: that write is never retried.
                    outcomeUnknown =
                        if (unknownRef != null) true else if (dropped != null) false else tickets.outcomeUnknown,
                    writeFailure =
                        if (unknownRef == null && dropped != null) TicketsFailure.OFFLINE else tickets.writeFailure,
                    writeRef = unknownRef ?: dropped?.ref ?: tickets.writeRef,
                )
            )
        }
    }

    /**
     * Whether the request may have reached the host, so its outcome is not known. Writes are checked
     * before sending ([valid]), so anything but a request error came after sending: most likely an
     * answer that could not be read.
     */
    private fun outcomeUnknown(e: Exception): Boolean =
        e !is RemoteRequestException ||
        e.requestKind == RemoteRequestException.Kind.TIMEOUT ||
            e.requestKind == RemoteRequestException.Kind.CONNECTION_LOST ||
            (e as? RemoteRequestException)?.reason == "outcome_unknown"

    private fun failure(e: Exception): TicketsFailure =
        if (e.isUnsupported) TicketsFailure.UNSUPPORTED
        else when (e.hostCode) {
            "not_found" -> TicketsFailure.NOT_FOUND
            "forbidden" -> TicketsFailure.FORBIDDEN
            "busy" -> TicketsFailure.BUSY
            "offline" -> TicketsFailure.OFFLINE
            else ->
                if (!current().connected || e.requestKind == RemoteRequestException.Kind.CONNECTION_LOST)
                    TicketsFailure.OFFLINE
                else TicketsFailure.FAILED
        }
}
