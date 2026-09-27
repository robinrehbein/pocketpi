package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// What the home-screen widget and the launcher shortcuts show. It is derived from the app's own
// state and from push events only, so neither surface ever polls the network.

internal data class HomeSession(
    val routeId: String,
    val projectId: String,
    val projectName: String,
    val sessionId: String,
    val title: String,
    /** Last known host status: idle, running, waiting or offline. */
    val status: String,
    val updatedAt: Long? = null,
    /** When the user last opened the session in the app. */
    val openedAt: Long? = null,
)

internal data class HomeSnapshot(
    val paired: Boolean = false,
    val sessions: List<HomeSession> = emptyList(),
)

internal const val MAX_HOME_SESSIONS = 40
internal const val MAX_WIDGET_ROWS = 3
internal const val MAX_SHORTCUTS = 4

private fun HomeSession.key() = routeId to sessionId

private fun attention(status: String) =
    when (status) {
        "waiting" -> 0
        "running" -> 1
        else -> 2
    }

internal data class WidgetLayout(val columns: Int, val rows: Int)

// Fixed paddings plus text lines that grow with the user's font scale.
private const val WIDGET_HEADER_FIXED_DP = 26f
private const val WIDGET_HEADER_TEXT_DP = 21f
private const val WIDGET_ROW_FIXED_DP = 4f
private const val WIDGET_ROW_TEXT_DP = 36f
internal const val WIDGET_TWO_COLUMNS_DP = 400

/** Height of one session row, about 40 dp at the default font size. */
internal fun widgetRowDp(fontScale: Float) = WIDGET_ROW_FIXED_DP + WIDGET_ROW_TEXT_DP * fontScale

internal fun widgetHeaderDp(fontScale: Float) =
    WIDGET_HEADER_FIXED_DP + WIDGET_HEADER_TEXT_DP * fontScale

/**
 * How many session rows fit a widget of this size at this font scale: as many as the height holds
 * (1 to 3), and two columns once it is tablet-wide, for up to six sessions.
 */
internal fun widgetLayout(widthDp: Float, heightDp: Float, fontScale: Float = 1f): WidgetLayout {
    val scale = fontScale.coerceIn(0.5f, 3f)
    val rows =
        ((heightDp - widgetHeaderDp(scale)) / widgetRowDp(scale)).toInt().coerceIn(1, MAX_WIDGET_ROWS)
    val columns = if (widthDp >= WIDGET_TWO_COLUMNS_DP) 2 else 1
    return WidgetLayout(columns, rows)
}

internal fun HomeSnapshot.waitingCount(): Int = sessions.count { it.status == "waiting" }

/** Sessions that are waiting for the user or running; waiting ones first. */
internal fun widgetSessions(snapshot: HomeSnapshot, limit: Int = MAX_WIDGET_ROWS): List<HomeSession> =
    snapshot.sessions
        .filter { it.status == "waiting" || it.status == "running" }
        .sortedWith(
            compareBy<HomeSession> { attention(it.status) }
                .thenByDescending { maxOf(it.updatedAt ?: 0L, it.openedAt ?: 0L) }
        )
        .take(limit.coerceAtLeast(0))

/** Launcher shortcuts: waiting sessions first, then the most recently used ones. */
internal fun shortcutSessions(snapshot: HomeSnapshot, limit: Int = MAX_SHORTCUTS): List<HomeSession> =
    snapshot.sessions
        .sortedWith(
            compareBy<HomeSession> { if (it.status == "waiting") 0 else 1 }
                .thenByDescending { it.openedAt ?: 0L }
                .thenByDescending { it.updatedAt ?: 0L }
                .thenBy { it.title }
        )
        .take(limit.coerceAtLeast(0))

private fun session(
    routeId: String,
    projectId: String,
    projectName: String,
    value: JsonObject,
    previous: HomeSession?,
): HomeSession? =
    try {
        val id = value.text("id")
        if (!isOpaqueId(id) || value.optionalText("origin") == "history") null
        else
            HomeSession(
                routeId,
                projectId,
                projectName.ifBlank { previous?.projectName.orEmpty() },
                id,
                value.text("title").take(200),
                value.optionalText("status") ?: "offline",
                value["updatedAt"]?.jsonPrimitive?.longOrNull ?: previous?.updatedAt,
                previous?.openedAt,
            )
    } catch (_: Exception) {
        null
    }

/**
 * Folds the app's current state into the home snapshot. Only a connected, settled state is trusted
 * to add, update or remove sessions; an offline app keeps the last known rows.
 *
 * - the open project's full session list replaces what is known about that project, so closed
 *   sessions disappear;
 * - verified project chats update rows across projects, and a row that was waiting or running but
 *   is no longer among its project's most active chats is no longer shown as active;
 * - the open chat counts as used now.
 */
internal fun mergeHomeSnapshot(
    previous: HomeSnapshot,
    state: RemoteState,
    paired: Boolean,
    pairedRoutes: Set<String>,
    now: Long,
): HomeSnapshot {
    val rows = linkedMapOf<Pair<String, String>, HomeSession>()
    previous.sessions.filter { it.routeId in pairedRoutes }.forEach { rows[it.key()] = it }
    val routeId = state.selection.routeId
    val trusted = state.connected && !state.loading && routeId != null && state.host?.routeId == routeId
    if (trusted) {
        val route = routeId!!
        val verified = state.projectChats.filter { it.verified }
        verified.groupBy { it.projectId }.forEach { (projectId, chats) ->
            val present = chats.mapNotNull { it.session.optionalText("id") }.toSet()
            rows.values
                .filter {
                    it.routeId == route && it.projectId == projectId &&
                        it.sessionId !in present && attention(it.status) < 2
                }
                .forEach { rows[it.key()] = it.copy(status = "idle") }
        }
        verified.forEach { chat ->
            val id = chat.session.optionalText("id") ?: return@forEach
            session(route, chat.projectId, chat.projectName, chat.session, rows[route to id])
                ?.let { rows[it.key()] = it }
        }
        val project = state.project
        val projectId = state.selection.projectId
        if (project != null && projectId != null && project.optionalText("id") == projectId) {
            val name = project.optionalText("name").orEmpty()
            val listed = state.sessions.mapNotNull { value ->
                session(route, projectId, name, value, value.optionalText("id")?.let { rows[route to it] })
            }
            // Only a list fetched on this connection may remove rows; a cached one only updates.
            if (state.sessionsFresh)
                rows.values.filter { it.routeId == route && it.projectId == projectId }
                    .forEach { rows.remove(it.key()) }
            listed.forEach { rows[it.key()] = it }
        }
    }
    val open = state.selection.sessionId
    if (routeId != null && open != null) {
        rows[routeId to open]?.let {
            // A minute's resolution keeps an open chat from rewriting the snapshot constantly.
            if (now - (it.openedAt ?: 0L) >= 60_000) rows[it.key()] = it.copy(openedAt = now)
        }
    }
    val sessions =
        rows.values
            .sortedWith(
                compareBy<HomeSession> { attention(it.status) }
                    .thenByDescending { maxOf(it.updatedAt ?: 0L, it.openedAt ?: 0L) }
            )
            .take(MAX_HOME_SESSIONS)
    return HomeSnapshot(paired, sessions)
}

internal fun shortcutId(routeId: String, sessionId: String) = "session:$routeId:$sessionId"

internal fun routeShortcutPrefix(routeId: String) = "session:$routeId:"

/**
 * Sessions the host positively no longer lists as live: known rows of the open project that are
 * missing from its full, trusted session list. Falling out of the snapshot's size cap or out of the
 * project chats' top rows is not evidence.
 */
internal fun closedHomeSessions(previous: HomeSnapshot, state: RemoteState): Set<String> {
    val routeId = state.selection.routeId ?: return emptySet()
    val projectId = state.selection.projectId ?: return emptySet()
    val trusted =
        state.connected && !state.loading && state.sessionsFresh && state.host?.routeId == routeId &&
            state.project?.optionalText("id") == projectId
    if (!trusted) return emptySet()
    val live =
        state.sessions
            .filter { it.optionalText("origin") != "history" }
            .mapNotNull { it.optionalText("id") }
            .toSet()
    return previous.sessions
        .filter { it.routeId == routeId && it.projectId == projectId && it.sessionId !in live }
        .map { shortcutId(it.routeId, it.sessionId) }
        .toSet()
}

/** The host a shortcut belongs to, or null for an ID this app did not create. */
internal fun shortcutRoute(id: String): String? =
    id.split(':').takeIf { it.size == 3 && it[0] == "session" && isOpaqueId(it[1]) }?.get(1)

internal data class ShortcutPlan(
    /** Disabled shortcuts whose session is known again; enabled before anything is updated. */
    val enable: List<String>,
    /** Shortcuts of closed sessions or unpaired hosts to disable. */
    val disable: List<String>,
)

/**
 * What to change besides publishing [chosen]. [existing] are the app's current dynamic and pinned
 * shortcuts with their enabled state, [present] the shortcut IDs of every session in the home
 * snapshot, [closed] those with positive evidence that the session is closed and [pairedRoutes]
 * the hosts that are still paired.
 */
internal fun shortcutPlan(
    chosen: List<String>,
    existing: Map<String, Boolean>,
    present: Set<String>,
    closed: Set<String>,
    pairedRoutes: Set<String>,
): ShortcutPlan {
    fun unpaired(id: String) = shortcutRoute(id)?.let { it !in pairedRoutes } ?: false
    return ShortcutPlan(
        enable =
            existing
                .filter { (id, enabled) ->
                    !enabled && (id in chosen || id in present) && id !in closed && !unpaired(id)
                }
                .keys
                .sorted(),
        disable =
            existing
                .filter { (id, enabled) ->
                    enabled && id !in chosen && (id in closed || unpaired(id))
                }
                .keys
                .sorted(),
    )
}

/** A push says what happened to one session before the app has reconnected. */
internal fun HomeSnapshot.withStatus(routeId: String, sessionId: String, status: String): HomeSnapshot =
    copy(
        sessions = sessions.map {
            if (it.routeId == routeId && it.sessionId == sessionId) it.copy(status = status) else it
        }
    )

internal fun HomeSnapshot.json(): JsonObject =
    Wire.objectOf(
        "version" to 1,
        "paired" to paired,
        "sessions" to JsonArray(
            sessions.map {
                Wire.objectOf(
                    "routeId" to it.routeId,
                    "projectId" to it.projectId,
                    "projectName" to it.projectName,
                    "sessionId" to it.sessionId,
                    "title" to it.title,
                    "status" to it.status,
                    "updatedAt" to it.updatedAt,
                    "openedAt" to it.openedAt,
                )
            }
        ),
    )

internal fun homeSnapshot(value: JsonObject): HomeSnapshot {
    require(value.long("version") == 1L)
    return HomeSnapshot(
        value.flag("paired"),
        value.getValue("sessions").jsonArray.take(MAX_HOME_SESSIONS).map { element ->
            val item = element.jsonObject
            HomeSession(
                item.text("routeId"),
                item.text("projectId"),
                item.text("projectName"),
                item.text("sessionId").also { require(isOpaqueId(it)) },
                item.text("title"),
                item.text("status"),
                item["updatedAt"]?.jsonPrimitive?.longOrNull,
                item["openedAt"]?.jsonPrimitive?.longOrNull,
            )
        },
    )
}
