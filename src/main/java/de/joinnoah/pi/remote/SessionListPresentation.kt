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

/** The rows of [entry] as list items; rows the Mac has not confirmed read as offline. */
internal fun projectSessionItems(entry: ProjectSessions, connected: Boolean): List<SessionListItem> =
    entry.sessions.map { sessionListItem(it, connected && entry.verified, loading = false) }

internal fun sessionListItem(
    session: JsonObject,
    connected: Boolean,
    loading: Boolean,
): SessionListItem {
    val availability =
        if (!connected) SessionAvailability.OFFLINE
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
    )
}

internal fun visibleSessionListItems(
    sessions: List<JsonObject>,
    connected: Boolean,
    loading: Boolean,
    hideOffline: Boolean,
    collapsedIds: Set<String> = emptySet(),
): List<SessionListItem> {
    val items = sessions.map { sessionListItem(it, connected, loading) }
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
        // Top-level sessions waiting for an answer come first; the sort is stable, so the rest
        // keep their order and subagents stay under their parent.
        val ordered =
            if (parentId == null) siblings.sortedBy { if (it.availability == SessionAvailability.WAITING) 0 else 1 }
            else siblings
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
    item.daemonOwned &&
        item.availability != SessionAvailability.OFFLINE &&
        connected &&
        !loading &&
        SESSION_CLOSE_CAPABILITY in capabilities
