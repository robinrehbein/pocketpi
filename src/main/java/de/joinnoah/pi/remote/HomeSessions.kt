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
    /**
     * When the app last heard from a host about these rows: a trusted merge of the app's state,
     * which runs about once a minute while the app stays connected, or a push about a known
     * session. The widget shows it as "Updated 14:02". It is the last news from any host, so with
     * two paired Macs, or rows from other projects, it also covers rows that this news did not
     * re-confirm.
     */
    val refreshedAt: Long? = null,
    /** Sessions a notification was posted for recently, newest first. */
    val notified: List<NotifiedSession> = emptyList(),
)

/**
 * A session PocketPi posted a notification for. Its launcher shortcut stays published for a while,
 * since a notification shows as a conversation only while its session's long-lived shortcut exists,
 * also when the session is not among the ones the app knows.
 */
internal data class NotifiedSession(
    val routeId: String,
    val sessionId: String,
    /** The session's title and project as the notification named them; empty while unknown. */
    val title: String = "",
    val projectName: String = "",
    val at: Long,
)

internal const val MAX_HOME_SESSIONS = 40
internal const val MAX_WIDGET_ROWS = 3
/** Rows per column of the three-column tablet layout, which is tall enough for more. */
internal const val MAX_LARGE_WIDGET_ROWS = 6
internal const val MAX_SHORTCUTS = 4
/** At most this many notified sessions keep a shortcut, on top of the [MAX_SHORTCUTS] ranked ones. */
internal const val MAX_NOTIFIED_SESSIONS = 8
/** How long a notified session keeps its shortcut. */
internal const val NOTIFIED_SHORTCUT_MILLIS = 3 * 24 * 60 * 60_000L

private fun HomeSession.key() = routeId to sessionId

private fun attention(status: String) =
    when (status) {
        "waiting" -> 0
        "running" -> 1
        else -> 2
    }

internal data class WidgetLayout(val columns: Int, val rows: Int)

// Fixed paddings plus text lines that grow with the user's font scale. A row's own 6 dp vertical
// padding makes its tap target 48 dp at the default font size, and doubles as the gap below the
// header, so the widget itself adds only 8 dp above the header and 2 dp below the last row.
private const val WIDGET_HEADER_FIXED_DP = 10f
private const val WIDGET_HEADER_TEXT_DP = 21f
internal const val WIDGET_ROW_PADDING_DP = 6
private const val WIDGET_ROW_FIXED_DP = 2f * WIDGET_ROW_PADDING_DP
private const val WIDGET_ROW_TEXT_DP = 36f
internal const val WIDGET_TWO_COLUMNS_DP = 400
internal const val WIDGET_THREE_COLUMNS_DP = 600

/** Height of one session row, 48 dp at the default font size. */
internal fun widgetRowDp(fontScale: Float) = WIDGET_ROW_FIXED_DP + WIDGET_ROW_TEXT_DP * fontScale

internal fun widgetHeaderDp(fontScale: Float) =
    WIDGET_HEADER_FIXED_DP + WIDGET_HEADER_TEXT_DP * fontScale

/**
 * How many session rows fit a widget of this size at this font scale: as many as the height holds
 * (1 to 3), and two columns once it is tablet-wide, for up to six sessions. The large tablet size
 * has three columns of up to six rows each.
 */
internal fun widgetLayout(widthDp: Float, heightDp: Float, fontScale: Float = 1f): WidgetLayout {
    val scale = fontScale.coerceIn(0.5f, 3f)
    val columns =
        when {
            widthDp >= WIDGET_THREE_COLUMNS_DP -> 3
            widthDp >= WIDGET_TWO_COLUMNS_DP -> 2
            else -> 1
        }
    val maxRows = if (columns == 3) MAX_LARGE_WIDGET_ROWS else MAX_WIDGET_ROWS
    val rows = ((heightDp - widgetHeaderDp(scale)) / widgetRowDp(scale)).toInt().coerceIn(1, maxRows)
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

/**
 * Every launcher shortcut to publish, in rank order: the [shortcutSessions] first, which the
 * launcher shows, then the sessions notified within [NOTIFIED_SHORTCUT_MILLIS], newest first, so
 * their notifications stay conversations. [limit] is the launcher's per-activity limit; shortcuts
 * of [closed] sessions are left out.
 */
internal fun shortcutChoice(
    snapshot: HomeSnapshot,
    limit: Int,
    now: Long,
    closed: Set<String> = emptySet(),
): List<HomeSession> {
    val top = shortcutSessions(snapshot, minOf(MAX_SHORTCUTS, limit))
    val chosen = top.map { it.key() }.toMutableSet()
    val known = snapshot.sessions.associateBy { it.key() }
    val notified =
        snapshot.notified
            .asSequence()
            .filter { now - it.at in 0 until NOTIFIED_SHORTCUT_MILLIS }
            .sortedByDescending { it.at }
            .filter { shortcutId(it.routeId, it.sessionId) !in closed }
            .filter { chosen.add(it.routeId to it.sessionId) }
            .map {
                known[it.routeId to it.sessionId]
                    ?: HomeSession(it.routeId, "", it.projectName, it.sessionId, it.title, "idle", it.at)
            }
            .take((limit - top.size).coerceAtLeast(0))
            .toList()
    return top + notified
}

/** Records a notification for a session; it keeps the title an earlier one named. */
internal fun HomeSnapshot.withNotified(routeId: String, sessionId: String, now: Long): HomeSnapshot {
    val previous = notified.find { it.routeId == routeId && it.sessionId == sessionId }
    val entry =
        NotifiedSession(routeId, sessionId, previous?.title.orEmpty(), previous?.projectName.orEmpty(), now)
    return copy(
        notified =
            (listOf(entry) + notified.filter { it !== previous })
                .filter { now - it.at in 0 until NOTIFIED_SHORTCUT_MILLIS }
                .take(MAX_NOTIFIED_SESSIONS)
    )
}

/** The title and project a notification named a notified session by, once they were read. */
internal fun HomeSnapshot.withNotifiedName(
    routeId: String,
    sessionId: String,
    title: String?,
    projectName: String?,
): HomeSnapshot =
    copy(
        notified =
            notified.map {
                if (it.routeId != routeId || it.sessionId != sessionId) it
                else
                    it.copy(
                        title = title?.trim()?.take(200)?.ifEmpty { null } ?: it.title,
                        projectName = projectName?.trim()?.take(200)?.ifEmpty { null } ?: it.projectName,
                    )
            }
    )

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
 * What [mergeHomeSnapshot] and [closedHomeSessions] read of the app's state, and nothing else. The
 * home surfaces update when this changes, so a streamed reply, a draft or a spinner never rewrites
 * the snapshot, while a status or title change still reaches the widget in the middle of a run.
 * Session and project objects keep only the fields the merge reads.
 */
internal data class HomeInputs(
    /** The paired hosts in the app's state; empty until they are loaded. */
    val hostRoutes: List<String> = emptyList(),
    val selection: RemoteSelection = RemoteSelection(),
    /** Connected to the selected host and settled, so its lists may add and update rows. */
    val connectedToSelection: Boolean = false,
    val project: JsonObject? = null,
    val sessions: List<JsonObject> = emptyList(),
    /** [sessions] came from a sessions.list on the current connection, not from a cache. */
    val sessionsFresh: Boolean = false,
    val projectChats: List<ProjectChatSummary> = emptyList(),
)

private val HOME_SESSION_FIELDS = setOf("id", "origin", "title", "status", "updatedAt")
private val HOME_PROJECT_FIELDS = setOf("id", "name")

private fun JsonObject.only(fields: Set<String>) =
    if (keys.all { it in fields }) this else JsonObject(filterKeys { it in fields })

/**
 * The [HomeInputs] of [state]. [previous] is the last state and its inputs: lists that are still
 * the same instances are reused instead of stripped again, so a streamed delta, which replaces
 * neither the sessions nor the project chats, costs no copies.
 */
internal fun homeInputs(state: RemoteState, previous: Pair<RemoteState, HomeInputs>? = null): HomeInputs {
    val routeId = state.selection.routeId
    val (last, lastInputs) = previous ?: (null to null)
    return HomeInputs(
        hostRoutes =
            if (lastInputs != null && state.hosts === last?.hosts) lastInputs.hostRoutes
            else state.hosts.map { it.routeId },
        selection = state.selection,
        connectedToSelection =
            state.connected && !state.loading && routeId != null && state.host?.routeId == routeId,
        project =
            if (lastInputs != null && state.project === last?.project) lastInputs.project
            else state.project?.only(HOME_PROJECT_FIELDS),
        sessions =
            if (lastInputs != null && state.sessions === last?.sessions) lastInputs.sessions
            else state.sessions.map { it.only(HOME_SESSION_FIELDS) },
        sessionsFresh = state.sessionsFresh,
        projectChats =
            if (lastInputs != null && state.projectChats === last?.projectChats) lastInputs.projectChats
            else state.projectChats.map { it.copy(session = it.session.only(HOME_SESSION_FIELDS)) },
    )
}

/**
 * How old the freshness time or an open chat's openedAt may get before a merge rewrites it. A
 * little under the minute of [HOME_REFRESH_INTERVAL_MS], so the connected app's tick refreshes the
 * time each minute despite jitter.
 */
internal const val HOME_TIME_RESOLUTION_MS = 50_000L

/** [time] is unset, at least [HOME_TIME_RESOLUTION_MS] old, or in the future of a clock set back. */
private fun outdated(time: Long?, now: Long) = time == null || now - time !in 0 until HOME_TIME_RESOLUTION_MS

internal fun mergeHomeSnapshot(
    previous: HomeSnapshot,
    state: RemoteState,
    paired: Boolean,
    pairedRoutes: Set<String>,
    now: Long,
): HomeSnapshot = mergeHomeSnapshot(previous, homeInputs(state), paired, pairedRoutes, now)

/**
 * Folds the app's current state into the home snapshot. Only a connected, settled state is trusted
 * to add, update or remove sessions; an offline app keeps the last known rows.
 *
 * - the open project's full session list replaces what is known about that project, so closed
 *   sessions disappear;
 * - verified project chats update rows across projects, and a row that was waiting or running but
 *   is no longer among its project's most active chats is no longer shown as active;
 * - the open chat counts as used now.
 *
 * A trusted state also marks the rows as refreshed now: whenever they changed, and otherwise once
 * [HOME_TIME_RESOLUTION_MS] passed, so a connected app does not rewrite the snapshot constantly.
 */
internal fun mergeHomeSnapshot(
    previous: HomeSnapshot,
    state: HomeInputs,
    paired: Boolean,
    pairedRoutes: Set<String>,
    now: Long,
): HomeSnapshot {
    val rows = linkedMapOf<Pair<String, String>, HomeSession>()
    previous.sessions.filter { it.routeId in pairedRoutes }.forEach { rows[it.key()] = it }
    val routeId = state.selection.routeId
    val trusted = state.connectedToSelection
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
            if (outdated(it.openedAt, now)) rows[it.key()] = it.copy(openedAt = now)
        }
    }
    val sessions =
        rows.values
            .sortedWith(
                compareBy<HomeSession> { attention(it.status) }
                    .thenByDescending { maxOf(it.updatedAt ?: 0L, it.openedAt ?: 0L) }
            )
            .take(MAX_HOME_SESSIONS)
    val refreshedAt =
        if (trusted && (sessions != previous.sessions || outdated(previous.refreshedAt, now))) now
        else previous.refreshedAt
    return HomeSnapshot(paired, sessions, refreshedAt, previous.notified.filter { it.routeId in pairedRoutes })
}

internal fun shortcutId(routeId: String, sessionId: String) = "session:$routeId:$sessionId"

internal fun routeShortcutPrefix(routeId: String) = "session:$routeId:"

/** Separates entries of the persisted, published-shortcut-list signature (see [HomeSurfaces]). */
internal const val SHORTCUT_SIGNATURE_DELIMITER = "\u001F"

/**
 * One entry of that signature: `id|title|projectName`. The host controls [title] and
 * [projectName], so both are stripped of the field separator and the entry separator first —
 * otherwise a crafted title could shift a later field into an earlier one, or split what should
 * be one list entry into several once the signature round-trips through storage.
 */
internal fun shortcutSignatureEntry(
    routeId: String,
    sessionId: String,
    title: String,
    projectName: String,
): String {
    fun sanitized(value: String) = value.replace(SHORTCUT_SIGNATURE_DELIMITER, " ").replace("|", " ")
    return "${shortcutId(routeId, sessionId)}|${sanitized(title)}|${sanitized(projectName)}"
}

/**
 * Sessions the host positively no longer lists as live: known rows of the open project that are
 * missing from its full, trusted session list. Falling out of the snapshot's size cap or out of the
 * project chats' top rows is not evidence.
 */
internal fun closedHomeSessions(previous: HomeSnapshot, state: RemoteState): Set<String> =
    closedHomeSessions(previous, homeInputs(state))

internal fun closedHomeSessions(previous: HomeSnapshot, state: HomeInputs): Set<String> {
    val routeId = state.selection.routeId ?: return emptySet()
    val projectId = state.selection.projectId ?: return emptySet()
    val trusted =
        state.connectedToSelection && state.sessionsFresh &&
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

/** [withStatus] from a push, which is news from the host when the widget knows the session. */
internal fun HomeSnapshot.withPush(
    routeId: String,
    sessionId: String,
    status: String,
    now: Long,
): HomeSnapshot =
    if (sessions.none { it.routeId == routeId && it.sessionId == sessionId }) this
    else withStatus(routeId, sessionId, status).copy(refreshedAt = now)

internal fun HomeSnapshot.json(): JsonObject =
    Wire.objectOf(
        "version" to 1,
        "paired" to paired,
        // Optional, so a version without it still reads the snapshot.
        "refreshedAt" to refreshedAt,
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
        // Optional too; a version without it drops it on its next write.
        "notified" to JsonArray(
            notified.map {
                Wire.objectOf(
                    "routeId" to it.routeId,
                    "sessionId" to it.sessionId,
                    "title" to it.title,
                    "projectName" to it.projectName,
                    "at" to it.at,
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
        value["refreshedAt"]?.jsonPrimitive?.longOrNull,
        (value["notified"] as? JsonArray).orEmpty().take(MAX_NOTIFIED_SESSIONS).mapNotNull { element ->
            try {
                val item = element.jsonObject
                // Nullable on purpose: the strict push-id check, whose IDs never contain a colon.
                val route: String? = item.text("routeId")
                val session: String? = item.text("sessionId")
                require(isOpaqueId(route) && isOpaqueId(session))
                NotifiedSession(
                    route!!,
                    session!!,
                    item.text("title"),
                    item.text("projectName"),
                    item.long("at"),
                )
            } catch (_: Exception) {
                null
            }
        },
    )
}
