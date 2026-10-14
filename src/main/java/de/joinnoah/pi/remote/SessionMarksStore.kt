package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What PocketPi remembers about one session on this phone, not on the Mac: whether it is pinned,
 * when the user last saw its chat at the bottom, and when it finished while the user was not
 * looking. Stored apart from the other local stores, keyed by host and session.
 */
internal data class SessionMark(
    val pinned: Boolean = false,
    /** When the chat was first seen at its bottom; null until then. */
    val seenAt: Long? = null,
    /** The sessions.list `updatedAt` that [seenAt] saw; a newer one makes the session unread. */
    val seenUpdatedAt: Long? = null,
    /** When a running session turned idle while it was not open; cleared once the chat is seen. */
    val finishedAt: Long? = null,
) {
    /** Nothing left to remember, so the store forgets the session. */
    val isEmpty: Boolean get() = !pinned && seenAt == null && seenUpdatedAt == null && finishedAt == null
}

/**
 * Whether a session reads as unread: it changed since the user last saw it, or it finished while
 * the user was away. An open session is never unread, and a session with no mark has nothing to
 * compare with, so it is not unread either.
 */
internal fun sessionUnread(mark: SessionMark?, updatedAt: Long?, open: Boolean): Boolean {
    if (open || mark == null) return false
    if (mark.finishedAt != null) return true
    val seen = mark.seenUpdatedAt ?: return false
    return updatedAt != null && updatedAt > seen
}

/**
 * Whether the repository selected the chat of [sessionId] and loaded its session, as opposed to
 * the cached preview a chat shows first (empty selection, session from the cache).
 */
internal fun chatSelected(state: RemoteState, sessionId: String): Boolean =
    state.session != null && state.selection.sessionId == sessionId

/**
 * The `updatedAt` of the chat's session as the project's list has it now. The list refreshes
 * while the chat runs; the session snapshot taken at selection does not, so it only answers while
 * the list has no row for the session.
 */
internal fun liveUpdatedAt(state: RemoteState, sessionId: String): Long? {
    val row = state.sessions.firstOrNull { it.optionalText("id") == sessionId } ?: state.session
    return row?.get("updatedAt")?.jsonPrimitive?.longOrNull
}

/** A session's status and update time at one observation of the app's state. */
internal data class ObservedSession(val status: String, val updatedAt: Long?)

/**
 * The sessions that ran and turned idle between two observations, while they were not [open].
 * [previous] holds the statuses of the last observation: a session it did not list has no
 * transition yet, so the first observation of a host reports nothing.
 */
internal fun finishedSessions(
    previous: Map<Pair<String, String>, String>,
    current: Map<Pair<String, String>, ObservedSession>,
    open: Pair<String, String>?,
): List<Pair<String, String>> =
    current.mapNotNull { (key, now) ->
        key.takeIf {
            previous[key] == "running" && now.status == "idle" && key != open
        }
    }

/** The per-session marks, read and written through a shared preferences file. */
internal class SessionMarksStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val mutable = MutableStateFlow(load())

    /** Every mark by (routeId, sessionId). Changes on the main thread only. */
    val marks: StateFlow<Map<Pair<String, String>, SessionMark>> = mutable.asStateFlow()

    private val visible = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The chat on screen right now: its screen is composed and the app is resumed. A session that
     * is selected but not shown (the board or another page covers it, the app is in the
     * background) is not visible, so a run that ends meanwhile counts as finished.
     */
    val visibleChat: StateFlow<Pair<String, String>?> = visible.asStateFlow()

    fun chatShown(routeId: String, sessionId: String) {
        visible.value = routeId to sessionId
    }

    /** The chat left the screen; another chat that already took its place stays visible. */
    fun chatHidden(routeId: String, sessionId: String) {
        if (visible.value == routeId to sessionId) visible.value = null
    }

    fun mark(routeId: String, sessionId: String): SessionMark? = mutable.value[routeId to sessionId]

    fun setPinned(routeId: String, sessionId: String, pinned: Boolean) =
        change(routeId, sessionId) { it.copy(pinned = pinned) }

    /**
     * The chat was seen at its bottom: the session reads as read until the next change. Seeing a
     * session again at the same update changes nothing, so a streaming chat does not write.
     */
    fun markSeen(routeId: String, sessionId: String, updatedAt: Long?, now: Long) =
        change(routeId, sessionId) {
            val seenUpdatedAt = updatedAt ?: it.seenUpdatedAt
            if (it.seenAt != null && it.seenUpdatedAt == seenUpdatedAt && it.finishedAt == null) it
            else it.copy(seenAt = it.seenAt ?: now, seenUpdatedAt = seenUpdatedAt, finishedAt = null)
        }

    fun markFinished(routeId: String, sessionId: String, now: Long) =
        change(routeId, sessionId) { it.copy(finishedAt = now) }

    /** Drops the marks of routes that are no longer paired, once the paired hosts are loaded. */
    fun prune(hostRoutes: Set<String>, hostsLoaded: Boolean) {
        if (!hostsLoaded) return
        val current = mutable.value
        val gone = current.keys.filter { it.first !in hostRoutes }
        if (gone.isEmpty()) return
        prefs.edit { gone.forEach { remove(storageKey(it)) } }
        mutable.value = current - gone.toSet()
    }

    /**
     * Records the sessions of [state] that turned idle while they were not open, and prunes the
     * marks once the hosts are loaded. [now] gives the time of an observation. The collection runs
     * in [scope], like the home surfaces' observer, so a finished run counts while the app is in
     * the background as well.
     */
    fun observe(
        state: StateFlow<RemoteState>,
        scope: CoroutineScope,
        now: () -> Long = System::currentTimeMillis,
    ) {
        scope.launch {
            var previous: Map<Pair<String, String>, String> = emptyMap()
            state
                // Streaming emits many states a second that leave the rows alone: compare the
                // references the rows are built from before building them.
                .distinctUntilChanged { old, new ->
                    old.sessions === new.sessions &&
                        old.projectSessions === new.projectSessions &&
                        old.host?.routeId == new.host?.routeId &&
                        old.hosts === new.hosts &&
                        old.hostsLoaded == new.hostsLoaded
                }
                .map { it.observedRows() }
                .distinctUntilChanged()
                .collect { rows ->
                    val statuses = rows.sessions
                    finishedSessions(previous, statuses, visible.value).forEach { (route, session) ->
                        markFinished(route, session, now())
                    }
                    previous = statuses.mapValues { it.value.status }
                    prune(rows.hostRoutes, rows.hostsLoaded)
                }
        }
    }

    private fun change(routeId: String, sessionId: String, update: (SessionMark) -> SessionMark) {
        val key = routeId to sessionId
        val current = mutable.value
        val previous = current[key] ?: SessionMark()
        val next = update(previous)
        if (next == previous) return
        var marks = if (next.isEmpty) current - key else current + (key to next)
        // Every viewed session leaves a mark and the Mac's closed sessions never report back:
        // when a host's marks outgrow the cap, the ones that changed longest ago go first.
        val forgotten = if (key !in current) oldestUnpinned(marks, routeId) else emptyList()
        marks = marks - forgotten.toSet()
        prefs.edit {
            if (next.isEmpty) remove(storageKey(key)) else putString(storageKey(key), encode(next))
            forgotten.forEach { remove(storageKey(it)) }
        }
        mutable.value = marks
    }

    private fun oldestUnpinned(
        marks: Map<Pair<String, String>, SessionMark>,
        routeId: String,
    ): List<Pair<String, String>> {
        val own = marks.entries.filter { it.key.first == routeId && !it.value.pinned && it.value.finishedAt == null }
        if (own.size <= MAX_PLAIN_MARKS_PER_ROUTE) return emptyList()
        return own.sortedBy { it.value.seenUpdatedAt ?: it.value.seenAt ?: 0L }.take(own.size - MAX_PLAIN_MARKS_PER_ROUTE).map { it.key }
    }

    private fun load(): Map<Pair<String, String>, SessionMark> =
        try {
            prefs.all.mapNotNull { (storageKey, value) ->
                val key = parseStorageKey(storageKey) ?: return@mapNotNull null
                val mark = (value as? String)?.let(::decode) ?: return@mapNotNull null
                key to mark
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }

    private data class Observed(
        val hostRoutes: Set<String>,
        val hostsLoaded: Boolean,
        val sessions: Map<Pair<String, String>, ObservedSession>,
    )

    /** The host's rows as the app shows them now: its projects' lists and the selected project's. */
    private fun RemoteState.observedRows(): Observed {
        // The last known rows also count while the connection is lost: a run that ends during a
        // reconnect shows as a change once the Mac lists it again.
        val route = host?.routeId
        val rows = linkedMapOf<Pair<String, String>, ObservedSession>()
        if (route != null) {
            projectSessions.values.forEach { project -> project.sessions.forEach { rows.observe(route, it) } }
            sessions.forEach { rows.observe(route, it) }
        }
        return Observed(
            hostRoutes = hosts.mapTo(mutableSetOf()) { it.routeId },
            hostsLoaded = hostsLoaded,
            sessions = rows,
        )
    }

    private fun MutableMap<Pair<String, String>, ObservedSession>.observe(route: String, session: JsonObject) {
        val id = session.optionalText("id") ?: return
        this[route to id] =
            ObservedSession(session.text("status"), session["updatedAt"]?.jsonPrimitive?.longOrNull)
    }

    companion object {
        const val PREFS_NAME = "session-marks"

        /** Marks per host that only remember a look (not pinned, not finished) before the oldest go. */
        const val MAX_PLAIN_MARKS_PER_ROUTE = 300

        // A unit separator cannot occur in the opaque IDs of hosts and sessions.
        private const val SEPARATOR = "\u001F"

        private fun storageKey(key: Pair<String, String>) = key.first + SEPARATOR + key.second

        private fun parseStorageKey(value: String): Pair<String, String>? {
            val parts = value.split(SEPARATOR)
            return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
        }

        /** `pinned,seenAt,seenUpdatedAt,finishedAt`, with empty fields for unset values. */
        private fun encode(mark: SessionMark): String =
            listOf(
                if (mark.pinned) "1" else "0",
                mark.seenAt?.toString().orEmpty(),
                mark.seenUpdatedAt?.toString().orEmpty(),
                mark.finishedAt?.toString().orEmpty(),
            ).joinToString(",")

        private fun decode(value: String): SessionMark? {
            val parts = value.split(",")
            if (parts.size != 4) return null
            return SessionMark(
                pinned = parts[0] == "1",
                seenAt = parts[1].toLongOrNull(),
                seenUpdatedAt = parts[2].toLongOrNull(),
                finishedAt = parts[3].toLongOrNull(),
            )
        }
    }
}

/** The session marks of the app, read by the lists, the board and the palette. */
internal val LocalSessionMarks = staticCompositionLocalOf<SessionMarksStore?> { null }
