package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
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

internal fun sortedProjectChats(chats: List<ProjectChatSummary>): List<ProjectChatSummary> =
    chats.sortedWith(
        compareBy<ProjectChatSummary> {
            if (!it.verified) 2
            else when (it.session.text("status")) {
                "waiting" -> 0
                "running" -> 1
                else -> 2
            }
        }.thenByDescending { it.session["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L }
    ).take(12)

/** How many running and how many recent quick chats a Mac's Projects page shows until "Show all". */
internal const val QUICK_CHAT_LIMIT = 3

/** The quick chats of a Projects page, grouped by what they need; see [quickChats]. */
internal data class QuickChats(
    val waiting: List<ProjectChatSummary>,
    val running: List<ProjectChatSummary>,
    val recent: List<ProjectChatSummary>,
    /** Whether the running group holds more chats than [QUICK_CHAT_LIMIT], so its "Show all" has more to show. */
    val runningExpandable: Boolean,
    /** Whether the recent group holds more chats than [QUICK_CHAT_LIMIT], so its "Show all" has more to show. */
    val recentExpandable: Boolean,
)

/**
 * The quick chats of a Mac's Projects page: every waiting chat, and the running and the recent ones up to
 * [QUICK_CHAT_LIMIT] each, unless their group is shown in full ([showRunningAll], [showRecentAll]). A chat
 * counts as waiting or running only on a connected Mac and once verified; all others are recent.
 */
internal fun quickChats(
    chats: List<ProjectChatSummary>,
    connected: Boolean,
    showRunningAll: Boolean,
    showRecentAll: Boolean,
): QuickChats {
    val waiting = chats.filter { it.verified && connected && it.session.text("status") == "waiting" }
    val running = chats.filter { it.verified && connected && it.session.text("status") == "running" }
    val recent =
        chats.filter {
            !it.verified || !connected || it.session.text("status") !in setOf("waiting", "running")
        }
    return QuickChats(
        waiting = waiting,
        running = running.take(if (showRunningAll) Int.MAX_VALUE else QUICK_CHAT_LIMIT),
        recent = recent.take(if (showRecentAll) Int.MAX_VALUE else QUICK_CHAT_LIMIT),
        runningExpandable = running.size > QUICK_CHAT_LIMIT,
        recentExpandable = recent.size > QUICK_CHAT_LIMIT,
    )
}

/** How many of a project's sessions wait for an answer and how many run, by its latest list. */
data class SessionCounts(val waiting: Int = 0, val running: Int = 0)

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
    entry.sessions.map { sessionListItem(it, connected && entry.verified, loading = false, stale = false) }

/**
 * The row of [session]. [stale]: the connection is lost while the state on screen is kept (see
 * [RemoteState.stale]); the row keeps the status the Mac last reported instead of going offline,
 * marked [SessionListItem.lastKnown]. That lasts as long as [stale] does, also once the reconnect
 * has given up and the connection reads "Offline".
 */
internal fun sessionListItem(
    session: JsonObject,
    connected: Boolean,
    loading: Boolean,
    stale: Boolean = false,
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
            availability != SessionAvailability.OFFLINE && !loading,
        lastKnown = !connected && availability != SessionAvailability.OFFLINE,
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
): List<SessionListItem> {
    val items =
        sessions.map { session ->
            val item = sessionListItem(session, connected, loading, stale)
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

internal fun canCloseSession(
    item: SessionListItem,
    connected: Boolean,
    loading: Boolean,
    capabilities: Set<String>,
): Boolean =
    hostOwned(item, capabilities) &&
        item.availability != SessionAvailability.OFFLINE &&
        connected &&
        !loading &&
        SESSION_CLOSE_CAPABILITY in capabilities

/** The marks of [routeId]'s sessions by session ID, for [visibleSessionListItems]. */
internal fun sessionMarksById(marks: Map<Pair<String, String>, SessionMark>, routeId: String?): Map<String, SessionMark> =
    if (routeId == null) emptyMap()
    else marks.entries.filter { it.key.first == routeId }.associate { it.key.second to it.value }
