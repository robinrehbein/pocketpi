package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class SessionAvailability {
    IDLE,
    RUNNING,
    WAITING,
    OFFLINE,
}

internal const val SESSION_CLOSE_CAPABILITY = "session.close.v1"

internal data class SessionListItem(
    val id: String,
    val title: String,
    val parentSessionId: String?,
    val depth: Int,
    val hasChildren: Boolean,
    val availability: SessionAvailability,
    val preview: String?,
    val updatedAt: Long?,
    val continuesAsCopy: Boolean,
    val daemonOwned: Boolean,
    val liveMacTui: Boolean,
    val childCount: Int = 0,
    /**
     * [availability] is the status the Mac last reported before the connection was lost (see
     * [RemoteState.stale]), not a live one: the row shows it muted and says so.
     */
    val lastKnown: Boolean = false,
    /** Pinned on this phone (see [SessionMark]); pinned rows list first at the top level. */
    val pinned: Boolean = false,
    /** Changed or finished since the user last looked, and not open (see [sessionUnread]). */
    val unread: Boolean = false,
    /** The ticket that lists this chat (see [ticketRefForSession]); not part of the session JSON. */
    val ticketRef: String? = null,
    /** A pi the host runs in a terminal of its own (see [isHostWorker]); it may be offline until it reports. */
    val hostWorker: Boolean = false,
)

data class ProjectChatSummary(
    val projectId: String,
    val projectName: String,
    val session: JsonObject,
    val verified: Boolean = false,
)

/**
 * The main sessions of [chats]: a subagent's row gives way to its top-level session, which takes on
 * the subagent's activity while it is busy itself no more (waiting before running). A subagent whose
 * main session is not among [chats] stays, so its work does not vanish from the page.
 *
 * For display only: the borrowed status must not reach stored rows, which status events patch and
 * other views (widget, activity board, restored lists) read as the session's own.
 */
internal fun mainSessionChats(chats: List<ProjectChatSummary>): List<ProjectChatSummary> {
    fun id(chat: ProjectChatSummary) = chat.session.optionalText("id")
    fun parent(chat: ProjectChatSummary) = chat.session.optionalText("parentSessionId")?.takeIf { it.isNotBlank() }
    val byId = chats.associateBy { id(it) }
    /** The top-level session listed for [chat], or null when its chain leaves the list. */
    fun root(chat: ProjectChatSummary): ProjectChatSummary? {
        var current = chat
        val seen = mutableSetOf<String?>()
        while (true) {
            val parentId = parent(current) ?: return current
            if (!seen.add(parentId)) return null
            current = byId[parentId] ?: return null
        }
    }
    val rank = mapOf("waiting" to 2, "running" to 1)
    val lifted = HashMap<String?, String>()
    for (chat in chats) {
        if (parent(chat) == null) continue
        val status = chat.session.optionalText("status")?.takeIf { it in rank } ?: continue
        val top = root(chat) ?: continue
        val best = lifted[id(top)] ?: top.session.optionalText("status")
        if ((rank[status] ?: 0) > (rank[best] ?: 0)) lifted[id(top)] = status
    }
    return chats.mapNotNull { chat ->
        when {
            parent(chat) != null -> chat.takeIf { root(it) == null }
            else -> lifted[id(chat)]?.let { status ->
                chat.copy(session = JsonObject(chat.session + ("status" to JsonPrimitive(status))))
            } ?: chat
        }
    }
}

/**
 * The featured chats: waiting, then running, then open idle top-level chats (the ready ones), then the
 * rest (unverified, history and idle subagent rows), each by recency, twelve of them. Ready chats
 * keep their place ahead of just-closed history rows, but they compete for the twelve with the
 * waiting and running chats, so a busy project can feature fewer ready chats than it has. The main sessions of the busy subagents among them come along beyond
 * the twelve, so [mainSessionChats] can show each such subagent as the session it works for.
 */
internal fun sortedProjectChats(chats: List<ProjectChatSummary>): List<ProjectChatSummary> {
    val kept = chats.sortedWith(
        compareBy<ProjectChatSummary> {
            if (!it.verified) 3
            else when (it.session.text("status")) {
                "waiting" -> 0
                "running" -> 1
                "idle" ->
                    if (isReadyRow(
                            "idle",
                            it.session.optionalText("origin"),
                            it.session.optionalText("parentSessionId"),
                        )
                    ) 2 else 3
                else -> 3
            }
        }.thenByDescending { it.session["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L }
    ).take(12)
    val byId = chats.associateBy { it.session.optionalText("id") }
    val keptIds = kept.mapTo(HashSet()) { it.session.optionalText("id") }
    val ancestors = LinkedHashMap<String?, ProjectChatSummary>()
    for (chat in kept) {
        if (chat.session.optionalText("status") !in setOf("waiting", "running")) continue
        var parentId = chat.session.optionalText("parentSessionId")?.takeIf { it.isNotBlank() }
        var depth = 0
        while (parentId != null && depth++ < 8) {
            val parent = byId[parentId] ?: break
            if (parentId !in keptIds) ancestors.putIfAbsent(parentId, parent)
            parentId = parent.session.optionalText("parentSessionId")?.takeIf { it.isNotBlank() }
        }
    }
    return kept + ancestors.values
}

/** How many ready, running and recent quick chats a Mac's Projects page shows until "Show all". */
internal const val QUICK_CHAT_LIMIT = 3

/** The quick chats of a Projects page, grouped by what they need; see [quickChats]. */
internal data class QuickChats(
    /** Every waiting chat, never cut. */
    val waiting: List<ProjectChatSummary>,
    /**
     * The open idle chats among the featured ones (at most twelve featured chats across all projects, see
     * [sortedProjectChats]), unread first, cut to [QUICK_CHAT_LIMIT] until shown in full.
     */
    val ready: List<ProjectChatSummary>,
    val running: List<ProjectChatSummary>,
    val recent: List<ProjectChatSummary>,
    /** Whether the ready group holds more chats than [QUICK_CHAT_LIMIT], so its "Show all" has more to show. */
    val readyExpandable: Boolean,
    /** Whether the running group holds more chats than [QUICK_CHAT_LIMIT], so its "Show all" has more to show. */
    val runningExpandable: Boolean,
    /** Whether the recent group holds more chats than [QUICK_CHAT_LIMIT], so its "Show all" has more to show. */
    val recentExpandable: Boolean,
)

/**
 * Whether a row with this status, origin and parent is an open idle top-level session: not a history row (a closed session the
 * Mac lists as a copy to continue) and no subagent. The Ready group and [unreadReadyCount]
 * share this.
 */
private fun isReadyRow(status: String?, origin: String?, parentSessionId: String?): Boolean =
    status == "idle" && origin != "history" && parentSessionId.isNullOrBlank()

/**
 * The quick chats of a Mac's Projects page. On a connected Mac, a verified chat is waiting, running
 * or ready (idle, open, top-level); waiting chats are not cut here, only by the twelve featured chats. Ready, running and recent
 * chats are capped at [QUICK_CHAT_LIMIT] each, unless their group is shown in full ([showReadyAll], [showRunningAll],
 * [showRecentAll]). Everything else is recent: offline, unverified, last-known and history chats,
 * orphaned idle subagents and chats of unknown status. Ready chats list unread ones first (by
 * [marks]; a chat without a mark counts as read), then the most recently updated.
 */
internal fun quickChats(
    chats: List<ProjectChatSummary>,
    connected: Boolean,
    showRunningAll: Boolean,
    showRecentAll: Boolean,
    marks: Map<String, SessionMark> = emptyMap(),
    showReadyAll: Boolean = false,
): QuickChats {
    fun ProjectChatSummary.live() = verified && connected
    fun ProjectChatSummary.status() = session.optionalText("status")
    fun ProjectChatSummary.updatedAt() = session["updatedAt"]?.jsonPrimitive?.longOrNull
    fun ProjectChatSummary.ready() =
        live() && isReadyRow(status(), session.optionalText("origin"), session.optionalText("parentSessionId"))
    val waiting = chats.filter { it.live() && it.status() == "waiting" }
    val running = chats.filter { it.live() && it.status() == "running" }
    val ready =
        chats
            .filter { it.ready() }
            .sortedWith(
                compareByDescending<ProjectChatSummary> {
                    sessionUnread(it.session.optionalText("id")?.let(marks::get), it.updatedAt(), open = false)
                }.thenByDescending { it.updatedAt() ?: 0L }
            )
    val recent = chats.filter { !it.live() || (it.status() !in setOf("waiting", "running") && !it.ready()) }
    return QuickChats(
        waiting = waiting,
        ready = ready.take(if (showReadyAll) Int.MAX_VALUE else QUICK_CHAT_LIMIT),
        readyExpandable = ready.size > QUICK_CHAT_LIMIT,
        running = running.take(if (showRunningAll) Int.MAX_VALUE else QUICK_CHAT_LIMIT),
        recent = recent.take(if (showRecentAll) Int.MAX_VALUE else QUICK_CHAT_LIMIT),
        runningExpandable = running.size > QUICK_CHAT_LIMIT,
        recentExpandable = recent.size > QUICK_CHAT_LIMIT,
    )
}

/** A ready chat idle for this long wears an "Old" chip. */
internal const val OLD_READY_MILLIS = 24 * 60 * 60 * 1000L

/** Whether a chat last updated at [updatedAt] has been idle for a day or more by [now]; a future time is not old. */
internal fun readyIsOld(updatedAt: Long?, now: Long): Boolean = updatedAt != null && now - updatedAt >= OLD_READY_MILLIS

/** How many of a project's sessions wait for an answer and how many run, by its latest list. */
data class SessionCounts(val waiting: Int = 0, val running: Int = 0)

/**
 * How many of [sessions] are ready (open, idle, top-level, the Ready group's rule) and unread by
 * [marks]: their result is one the user has not seen yet. This is the "N new" badge of a project
 * row. It lives apart from [SessionCounts] because unread is phone-local (see [sessionUnread]):
 * the repository counts by the Mac's list alone, so the view applies the marks. A session
 * without a mark counts as read.
 */
internal fun unreadReadyCount(sessions: List<JsonObject>, marks: Map<String, SessionMark>): Int =
    sessions.count {
        isReadyRow(it.optionalText("status"), it.optionalText("origin"), it.optionalText("parentSessionId")) &&
            sessionUnread(
                it.optionalText("id")?.let(marks::get),
                it["updatedAt"]?.jsonPrimitive?.longOrNull,
                open = false,
            )
    }

/**
 * A project's sessions for views across projects, such as an activity board, badges or a
 * session switcher: its whole list from the Mac, not only the chats the Projects page features.
 * Rows restored from a cache are not [verified] and carry no [counts] until the Mac lists the
 * project again. [truncated]: the Mac listed more than [PROJECT_SESSIONS_CAP] sessions.
 */
data class ProjectSessions(
    val projectId: String,
    val projectName: String,
    val sessions: List<JsonObject>,
    val verified: Boolean = false,
    val counts: SessionCounts? = null,
    val truncated: Boolean = false,
)

/** Rows kept per project in [ProjectSessions], so many large projects stay small in memory. */
internal const val PROJECT_SESSIONS_CAP = 200

internal fun sessionCounts(sessions: List<JsonObject>): SessionCounts =
    SessionCounts(
        waiting = sessions.count { it.optionalText("status") == "waiting" },
        running = sessions.count { it.optionalText("status") == "running" },
    )

/**
 * [sessions] of a project as [ProjectSessions]. Over the cap, waiting and running sessions are
 * kept first, then the most recently updated; a [verified] list counts all of its sessions.
 */
internal fun projectSessions(
    projectId: String,
    projectName: String,
    sessions: List<JsonObject>,
    verified: Boolean,
): ProjectSessions {
    val kept =
        if (sessions.size <= PROJECT_SESSIONS_CAP) sessions
        else
            sessions
                .sortedWith(
                    compareBy<JsonObject> {
                        if (it.optionalText("status") in setOf("waiting", "running")) 0 else 1
                    }.thenByDescending { it["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L }
                )
                .take(PROJECT_SESSIONS_CAP)
    return ProjectSessions(
        projectId = projectId,
        projectName = projectName,
        sessions = kept,
        verified = verified,
        counts = if (verified) sessionCounts(sessions) else null,
        truncated = sessions.size > PROJECT_SESSIONS_CAP,
    )
}

/**
 * These sessions with each row passed through [patch], such as a live status or title. The
 * counts follow the rows' changes; a list without counts stays without them.
 */
internal fun ProjectSessions.patched(patch: (JsonObject) -> JsonObject): ProjectSessions {
    val rows = sessions.map(patch)
    if (rows == sessions) return this
    // A truncated list's counts include rows it does not keep, so only the change applies.
    val before = sessionCounts(sessions)
    val after = sessionCounts(rows)
    return copy(
        sessions = rows,
        counts = counts?.let {
            SessionCounts(
                waiting = (it.waiting + after.waiting - before.waiting).coerceAtLeast(0),
                running = (it.running + after.running - before.running).coerceAtLeast(0),
            )
        },
    )
}

/** These sessions as they read once the Mac no longer confirms them, as after a dropped connection. */
internal fun ProjectSessions.unverified(): ProjectSessions = copy(verified = false, counts = null)

/**
 * The rows of [entry] as list items; rows the Mac has not confirmed read as offline. Unlike the
 * open project's list, these rows do not keep a last-known status while the connection is
 * [RemoteState.stale]: a drop makes every entry [unverified], whose rows come from a cache or an
 * earlier connection, so they read offline rather than [SessionListItem.lastKnown], as the
 * Projects page's featured chats do.
 */
internal fun projectSessionItems(entry: ProjectSessions, connected: Boolean): List<SessionListItem> =
    entry.sessions.map { sessionListItem(it, connected && entry.verified, loading = false, stale = false, confirmed = entry.verified) }

/**
 * The row of [session]. [stale]: the connection is lost while the state on screen is kept (see
 * [RemoteState.stale]); the row keeps the status the Mac last reported instead of going offline,
 * marked [SessionListItem.lastKnown]. That lasts as long as [stale] does, also once the reconnect
 * has given up and the connection reads "Offline".
 *
 * [confirmed]: a `sessions.list` reply read on the connection now up stands behind these rows
 * ([RemoteState.sessionsFresh]). While a connection is up but its list has not answered yet (just
 * after a reconnect), the rows are what an earlier connection said: they stay last known too, and
 * none claims to be live.
 */
internal fun sessionListItem(
    session: JsonObject,
    connected: Boolean,
    loading: Boolean,
    stale: Boolean = false,
    // No default: a caller that forgets it would let an earlier connection's rows claim to be live.
    confirmed: Boolean,
): SessionListItem {
    val availability =
        if (!connected && !stale) SessionAvailability.OFFLINE
        else
            when (session.text("status")) {
                "idle" -> SessionAvailability.IDLE
                "running" -> SessionAvailability.RUNNING
                "waiting" -> SessionAvailability.WAITING
                else -> SessionAvailability.OFFLINE
            }
    return SessionListItem(
        id = session.text("id"),
        title = session.text("title"),
        parentSessionId = session.optionalText("parentSessionId")?.takeIf { it.isNotBlank() },
        depth = 0,
        hasChildren = false,
        availability = availability,
        preview = session.optionalText("preview")?.takeIf { it.isNotBlank() },
        updatedAt = session["updatedAt"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 },
        continuesAsCopy = session.text("origin") == "history",
        daemonOwned = session.text("origin") == "rpc",
        liveMacTui = session.text("origin") == "tui" &&
            availability != SessionAvailability.OFFLINE && !loading && confirmed,
        lastKnown = (!connected || !confirmed) && availability != SessionAvailability.OFFLINE,
        hostWorker = isHostWorker(session),
    )
}

/**
 * Whether the "hide offline sessions" [setting] filters the list. Without a connection every row
 * is offline, so it would hide them all; while the Mac reconnects ([stale]) the rows keep their
 * last-known status, and the filter applies to that.
 */
internal fun hidesOfflineSessions(setting: Boolean, connected: Boolean, stale: Boolean): Boolean =
    setting && (connected || stale)

internal fun visibleSessionListItems(
    sessions: List<JsonObject>,
    connected: Boolean,
    loading: Boolean,
    hideOffline: Boolean,
    collapsedIds: Set<String> = emptySet(),
    stale: Boolean = false,
    /** The phone's marks of these sessions by session ID; see [sessionMarksById]. */
    marks: Map<String, SessionMark> = emptyMap(),
    /** The session the chat on screen shows; it is never unread. */
    openSessionId: String? = null,
    /** The ticket ref of a session ID, see [ticketRefForSession]. */
    ticketRef: (String) -> String? = { null },
    /** See [sessionListItem]; pass [RemoteState.sessionsFresh]. No default, on purpose. */
    confirmed: Boolean,
): List<SessionListItem> {
    val items =
        sessions.map { session ->
            val item = sessionListItem(session, connected, loading, stale, confirmed)
            val mark = marks[item.id]
            item.copy(
                ticketRef = ticketRef(item.id),
                pinned = mark?.pinned == true,
                unread = sessionUnread(mark, item.updatedAt, open = item.id == openSessionId),
            )
        }
    val itemsById = items.associateBy(SessionListItem::id)

    fun validParentId(item: SessionListItem): String? {
        val parentId = item.parentSessionId ?: return null
        if (parentId == item.id || parentId !in itemsById) return null

        val visited = mutableSetOf(item.id)
        var ancestorId: String? = parentId
        while (ancestorId != null) {
            if (!visited.add(ancestorId)) return null
            ancestorId = itemsById[ancestorId]?.parentSessionId
        }
        return parentId
    }

    val childrenByParentId = items.groupBy(::validParentId)

    fun isVisible(item: SessionListItem): Boolean =
        !hideOffline ||
            item.availability != SessionAvailability.OFFLINE ||
            childrenByParentId[item.id].orEmpty().any(::isVisible)

    val result = mutableListOf<SessionListItem>()
    fun addVisibleItems(parentId: String?, depth: Int) {
        val siblings = childrenByParentId[parentId].orEmpty()
        // Pinned sessions come first among their siblings, then top-level sessions waiting for an
        // answer; the sort is stable, so the rest keep their order and subagents stay under their
        // parent.
        val ordered =
            siblings.sortedBy {
                when {
                    it.pinned -> 0
                    parentId == null && it.availability == SessionAvailability.WAITING -> 1
                    else -> 2
                }
            }
        ordered.forEach { item ->
            if (isVisible(item)) {
                val visibleChildren = childrenByParentId[item.id].orEmpty().count(::isVisible)
                result += item.copy(
                    depth = depth,
                    hasChildren = visibleChildren > 0,
                    childCount = visibleChildren,
                )
                if (item.id !in collapsedIds) addVisibleItems(item.id, depth + 1)
            }
        }
    }
    addVisibleItems(parentId = null, depth = 0)
    return result
}

/**
 * Close is offered only for a session whose status the current connection confirmed. A
 * [SessionListItem.lastKnown] row may already be closed on the Mac; closing waits until its list
 * answers instead of acting on an unconfirmed status. This claims nothing about the session.
 */
internal fun canCloseSession(
    item: SessionListItem,
    connected: Boolean,
    loading: Boolean,
    capabilities: Set<String>,
): Boolean =
    hostOwned(item, capabilities) &&
        item.availability != SessionAvailability.OFFLINE &&
        !item.lastKnown &&
        connected &&
        !loading &&
        SESSION_CLOSE_CAPABILITY in capabilities

/** The marks of [routeId]'s sessions by session ID, for [visibleSessionListItems]. */
internal fun sessionMarksById(marks: Map<Pair<String, String>, SessionMark>, routeId: String?): Map<String, SessionMark> =
    if (routeId == null) emptyMap()
    else marks.entries.filter { it.key.first == routeId }.associate { it.key.second to it.value }
