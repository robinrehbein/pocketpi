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
