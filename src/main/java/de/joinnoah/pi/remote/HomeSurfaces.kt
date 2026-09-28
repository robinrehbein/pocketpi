package de.joinnoah.pi.remote

import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the home-screen widget and the launcher shortcuts in step with the app. The snapshot is
 * stored encrypted like the other local stores, because it holds session titles.
 */
internal class HomeSurfaces(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pairings: PairingStorage,
) {
    private val file = EncryptedFileStore(context, "home-sessions.enc", "pi-remote-home-v1")
    private val mutex = Mutex()
    private var loaded = false
    private val shortcutMutex = Mutex()

    // Persisted across process restarts: setDynamicShortcuts is launcher rate-limited, and every
    // background cold start (a push, a worker) would otherwise call it once just to find nothing
    // changed since the last, still-warm publish.
    private val shortcutPrefs =
        context.getSharedPreferences(PUBLISHED_SHORTCUTS_PREFS, Context.MODE_PRIVATE)
    private var publishedShortcuts: List<String>? =
        shortcutPrefs.getString(PUBLISHED_SHORTCUTS_KEY, null)
            ?.let { if (it.isEmpty()) emptyList() else it.split(SHORTCUT_SIGNATURE_DELIMITER) }

    /** Shortcut IDs of sessions known to be closed, waiting to be disabled. */
    private val closed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Hosts unpaired in this process, so their sessions don't come back from state that is not yet
     * updated. Only an optimisation: every start reconciles against the stored pairings.
     */
    private val forgotten: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The paired hosts, from the stored pairings at start and from the app's state later. */
    @Volatile private var pairedRoutes: Set<String>? = null

    /** The first publish after start always runs, to reconcile shortcuts with the pairings. */
    @Volatile private var reconciled = false

    fun isForgotten(routeId: String): Boolean = routeId in forgotten
    private val mutable = MutableStateFlow(HomeSnapshot())
    val snapshot: StateFlow<HomeSnapshot> = mutable.asStateFlow()

    suspend fun load(): HomeSnapshot =
        mutex.withLock {
            ensureLoaded()
            mutable.value
        }

    @OptIn(FlowPreview::class)
    fun observe(state: StateFlow<RemoteState>) {
        scope.launch {
            state.debounce(750).collect { current ->
                try {
                    // Pairing the same Mac again makes it known again.
                    forgotten.removeAll(current.hosts.map { it.routeId }.toSet())
                    update { previous ->
                        val routes =
                            (if (current.hosts.isNotEmpty()) current.hosts.map { it.routeId }.toSet()
                            else storedRoutes()) - forgotten
                        pairedRoutes = routes
                        closed += closedHomeSessions(previous, current)
                        mergeHomeSnapshot(
                            previous,
                            current,
                            routes.isNotEmpty(),
                            routes,
                            System.currentTimeMillis(),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The widget keeps its last content if storage fails.
                }
            }
        }
    }

    /** A pairing was removed: forget its sessions and disable its shortcuts. */
    fun forgetRoute(routeId: String) {
        forgotten += routeId
        scope.launch {
            try {
                update(force = true) { previous ->
                    previous.copy(sessions = previous.sessions.filter { it.routeId != routeId })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun onPush(payload: PushPayload) {
        val status = if (payload.event == PushEvent.QUESTION) "waiting" else "idle"
        launchUpdate { it.withStatus(payload.routeId, payload.sessionId, status) }
    }

    fun onAnswered(routeId: String, sessionId: String) {
        launchUpdate { it.withStatus(routeId, sessionId, "running") }
    }

    /** Tells the launcher which session the user just opened, for its own ranking. */
    fun reportOpened(routeId: String, sessionId: String) {
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(routeId, sessionId))
        } catch (_: Exception) {}
    }

    private fun launchUpdate(change: (HomeSnapshot) -> HomeSnapshot) {
        scope.launch {
            try {
                update { change(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private suspend fun storedRoutes(): Set<String> =
        withContext(Dispatchers.IO) { pairings.load() }.map { it.routeId }.toSet()

    /** Loads the snapshot and drops hosts that were unpaired while the app was not running. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored =
            withContext(Dispatchers.IO) {
                try {
                    file.read()?.let { homeSnapshot(Wire.parse(Wire.utf8(it), 1024 * 1024)) }
                } catch (_: Exception) {
                    null
                }
            } ?: HomeSnapshot()
        val routes =
            try {
                storedRoutes()
            } catch (_: Exception) {
                null
            }
        if (routes != null) pairedRoutes = routes - forgotten
        mutable.value =
            if (routes == null) stored
            else
                stored.copy(
                    paired = routes.isNotEmpty(),
                    sessions = stored.sessions.filter { it.routeId in routes },
                )
        loaded = true
    }

    private suspend fun update(
        force: Boolean = false,
        change: suspend (HomeSnapshot) -> HomeSnapshot,
    ) {
        val next =
            mutex.withLock {
                ensureLoaded()
                val previous = mutable.value
                val next = change(previous)
                if (next != previous) {
                    withContext(Dispatchers.IO) { file.write(next.json().toString().toByteArray()) }
                    mutable.value = next
                } else if (!force && closed.isEmpty() && reconciled) return
                next
            }
        try {
            PocketPiWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        withContext(Dispatchers.IO) { publishShortcuts(next) }
    }

    /** Runs off the main thread: ShortcutManager calls are binder calls. */
    private suspend fun publishShortcuts(snapshot: HomeSnapshot) =
        shortcutMutex.withLock {
            try {
                val limit =
                    minOf(MAX_SHORTCUTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
                val chosen = shortcutSessions(snapshot, limit)
                val chosenIds = chosen.map { shortcutId(it.routeId, it.sessionId) }
                val existing =
                    ShortcutManagerCompat.getShortcuts(
                            context,
                            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                                ShortcutManagerCompat.FLAG_MATCH_PINNED,
                        )
                        .associate { it.id to it.isEnabled }
                val routes = pairedRoutes ?: storedRoutes()
                val present = snapshot.sessions.map { shortcutId(it.routeId, it.sessionId) }.toSet()
                val plan = shortcutPlan(chosenIds, existing, present, closed, routes - forgotten)
                val localized = RemoteNotifications.localized(context)
                fun info(session: HomeSession, rank: Int) =
                    run {
                        val title = session.title.ifBlank {
                            localized.getString(R.string.remote_widget_untitled)
                        }
                        ShortcutInfoCompat.Builder(context, shortcutId(session.routeId, session.sessionId))
                            .setShortLabel(title.take(24))
                            .setLongLabel(
                                if (session.projectName.isBlank()) title.take(48)
                                else "$title · ${session.projectName}".take(48)
                            )
                            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                            .setIntent(sessionIntent(context, session.routeId, session.sessionId))
                            .setRank(rank)
                            .build()
                    }
                val shortcuts = chosen.mapIndexed { rank, session -> info(session, rank) }
                if (plan.enable.isNotEmpty()) {
                    ShortcutManagerCompat.enableShortcuts(
                        context,
                        snapshot.sessions
                            .filter { shortcutId(it.routeId, it.sessionId) in plan.enable }
                            .map { info(it, 0) },
                    )
                }
                // Title changes matter; status changes don't, since it isn't shown.
                val signature =
                    chosen.map { shortcutSignatureEntry(it.routeId, it.sessionId, it.title, it.projectName) }
                if (signature != publishedShortcuts || plan.enable.isNotEmpty()) {
                    if (ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) {
                        publishedShortcuts = signature
                        shortcutPrefs.edit {
                            putString(PUBLISHED_SHORTCUTS_KEY, signature.joinToString(SHORTCUT_SIGNATURE_DELIMITER))
                        }
                    }
                }
                // Pinned shortcuts stay on the home screen; disable the ones of closed sessions.
                if (plan.disable.isNotEmpty()) {
                    ShortcutManagerCompat.removeDynamicShortcuts(context, plan.disable)
                    ShortcutManagerCompat.disableShortcuts(
                        context,
                        plan.disable,
                        localized.getString(R.string.remote_shortcut_closed),
                    )
                }
                // Keep only what may still need disabling: enabled shortcuts that aren't chosen.
                closed.retainAll(
                    existing.filter { (id, enabled) -> enabled && id !in chosenIds }.keys -
                        plan.disable.toSet()
                )
                reconciled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The launcher rate-limits shortcut updates; the next change retries.
            }
        }

}

internal const val PUBLISHED_SHORTCUTS_PREFS = "pocket_pi_shortcuts"
internal const val PUBLISHED_SHORTCUTS_KEY = "signature"
