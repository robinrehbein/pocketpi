package de.joinnoah.pi.remote

import android.content.Context
import androidx.core.content.LocusIdCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
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
    private val file: EncryptedFileStore = context.stores.home,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val shortcutMutex = Mutex()

    // Persisted across process restarts: setDynamicShortcuts is launcher rate-limited, and every
    // background cold start (a push, a worker) would otherwise call it once just to find nothing
    // changed since the last, still-warm publish.
    private val shortcutPrefs =
        context.getSharedPreferences(PUBLISHED_SHORTCUTS_PREFS, Context.MODE_PRIVATE)
    // A new version may launch differently (LaunchIntents), so its first publish replaces every
    // shortcut once, pinned ones included, instead of trusting the signature of an older version.
    @Volatile private var shortcutsCurrent =
        shortcutPrefs.getInt(PUBLISHED_SHORTCUTS_VERSION_KEY, 0) == BuildConfig.VERSION_CODE
    private var publishedShortcuts: List<String>? =
        shortcutPrefs.getString(PUBLISHED_SHORTCUTS_KEY, null)
            ?.takeIf { shortcutsCurrent }
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

    fun observe(state: StateFlow<RemoteState>) {
        scope.launch {
            // The stored snapshot already shows the last rows: let the app's start settle first.
            delay(HOME_UPDATE_INTERVAL_MS)
            homeUpdates(state).collect { onState(it) }
        }
    }

    /** Folds the app's state into the snapshot, and marks sessions it shows closed. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun onState(current: HomeInputs) {
        try {
            // Pairing the same Mac again makes it known again.
            forgotten.removeAll(current.hostRoutes.toSet())
            update { previous ->
                val routes =
                    (if (current.hostRoutes.isNotEmpty()) current.hostRoutes.toSet()
                    else storedRoutes()) - forgotten
                pairedRoutes = routes
                val newlyClosed = closedHomeSessions(previous, current)
                closed += newlyClosed
                mergeHomeSnapshot(
                        previous,
                        current,
                        routes.isNotEmpty(),
                        routes,
                        System.currentTimeMillis(),
                    )
                    // Otherwise its shortcut would come back once [closed] has disabled it.
                    .withoutNotified(newlyClosed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The widget keeps its last content if storage fails.
        }
    }

    /** A pairing was removed: forget its sessions and disable its shortcuts. */
    fun forgetRoute(routeId: String) {
        forgotten += routeId
        scope.launch {
            try {
                update(force = true) { previous ->
                    previous.copy(
                        sessions = previous.sessions.filter { it.routeId != routeId },
                        notified = previous.notified.filter { it.routeId != routeId },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /**
     * A completion or question arrived. Its notification is a conversation, so the session keeps
     * a long-lived shortcut for a while (see [shortcutChoice]).
     */
    fun onPush(payload: PushPayload) {
        val status = if (payload.event == PushEvent.QUESTION) "waiting" else "idle"
        launchUpdate {
            val now = System.currentTimeMillis()
            it.withPush(payload.routeId, payload.sessionId, status, now)
                .withNotified(payload.routeId, payload.sessionId, now)
        }
    }

    /** A notification named its session, which also names the session's shortcut. */
    fun onNotifiedName(routeId: String, sessionId: String, title: String?, projectName: String?) {
        launchUpdate { it.withNotifiedName(routeId, sessionId, title, projectName) }
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

    /**
     * Redraws the widget and publishes the shortcuts even when nothing changed. Run after an app
     * update ([PackageReplacedReceiver]), so the first publish of the new version moves every
     * shortcut to its launch intents before the user taps one.
     */
    suspend fun refresh() {
        try {
            update(force = true) { it }
        } catch (e: CancellationException) {
            throw e
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
                    notified = stored.notified.filter { it.routeId in routes },
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
    @androidx.annotation.VisibleForTesting
    internal suspend fun publishShortcuts(snapshot: HomeSnapshot) =
        shortcutMutex.withLock {
            try {
                // One set for the ranked and the notified sessions: setDynamicShortcuts replaces
                // every dynamic shortcut, so a shortcut pushed on its own would be wiped here.
                val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                val chosen = shortcutChoice(snapshot, limit, System.currentTimeMillis(), closed)
                val chosenIds = chosen.map { shortcutId(it.routeId, it.sessionId) }
                val existing =
                    ShortcutManagerCompat.getShortcuts(
                            context,
                            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                                ShortcutManagerCompat.FLAG_MATCH_PINNED,
                        )
                        .associate { it.id to it.isEnabled }
                val routes = pairedRoutes ?: storedRoutes()
                // Notified sessions count through [chosen] alone: one past its time, or beyond the
                // limit, has nothing to enable it with.
                val present = snapshot.sessions.map { shortcutId(it.routeId, it.sessionId) }.toSet()
                val plan = shortcutPlan(chosenIds, existing, present, closed, routes - forgotten)
                val localized = RemoteNotifications.localized(context)
                fun info(session: HomeSession, rank: Int) =
                    run {
                        val title = session.title.ifBlank {
                            localized.getString(R.string.remote_widget_untitled)
                        }
                        val id = shortcutId(session.routeId, session.sessionId)
                        ShortcutInfoCompat.Builder(context, id)
                            .setShortLabel(title.take(24))
                            .setLongLabel(
                                if (session.projectName.isBlank()) title.take(48)
                                else "$title · ${session.projectName}".take(48)
                            )
                            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                            .setIntent(LaunchIntents.session(context, session.routeId, session.sessionId))
                            .setRank(rank)
                            // Long-lived, so the session's notifications show as conversations,
                            // and the system keeps the shortcut while one of them is showing.
                            .setLongLived(true)
                            .setLocusId(LocusIdCompat(id))
                            // A Direct Share target: the share sheet offers "PocketPi: <session>"
                            // (the share-target of res/xml/shortcuts.xml, which opens ShareActivity).
                            .setCategories(setOf(SHARE_TARGET_CATEGORY))
                            .build()
                    }
                val shortcuts = chosen.mapIndexed { rank, session -> info(session, rank) }
                if (plan.enable.isNotEmpty()) {
                    ShortcutManagerCompat.enableShortcuts(
                        context,
                        (chosen + snapshot.sessions)
                            .distinctBy { it.routeId to it.sessionId }
                            .filter { shortcutId(it.routeId, it.sessionId) in plan.enable }
                            .map { info(it, 0) },
                    )
                }
                // Title changes matter; status changes don't, since it isn't shown.
                val signature = shortcutSignature(chosen, snapshot)
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
                // Last, so a failed pinned update cannot hold up the bookkeeping above. Until it
                // succeeds the version stays unrecorded and the next publish retries it.
                if (!shortcutsCurrent && publishedShortcuts == signature && relaunchPinned(chosenIds)) {
                    shortcutsCurrent = true
                    shortcutPrefs.edit { putInt(PUBLISHED_SHORTCUTS_VERSION_KEY, BuildConfig.VERSION_CODE) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The launcher rate-limits shortcut updates; the next change retries.
            }
        }

    /**
     * Points pinned shortcuts that were not just republished at the current launch intent. A
     * pinned shortcut stays on the home screen after it leaves the dynamic set, and only
     * updateShortcuts reaches it; disabled ones are included, in case their session comes back.
     * The update keeps each shortcut's icon and long label, which are left unset here.
     */
    private fun relaunchPinned(republished: List<String>): Boolean =
        try {
            val updates =
                ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                    .filter { it.id !in republished }
                    .mapNotNull { pinned ->
                        val (route, session) = shortcutTarget(pinned.id) ?: return@mapNotNull null
                        ShortcutInfoCompat.Builder(context, pinned.id)
                            .setShortLabel(pinned.shortLabel)
                            .setIntent(LaunchIntents.session(context, route, session))
                            .build()
                    }
            updates.isEmpty() || ShortcutManagerCompat.updateShortcuts(context, updates)
        } catch (_: Exception) {
            // Rate-limited or rejected; the next publish tries again.
            false
        }
}

/** The longest the widget and the shortcuts trail the app's state, also while pi streams. */
internal const val HOME_UPDATE_INTERVAL_MS = 750L

/** How often a connected app folds its unchanged state in again, which refreshes the widget's time. */
internal const val HOME_REFRESH_INTERVAL_MS = 60_000L

/**
 * What the home surfaces fold in from [state]: a change of [HomeInputs] at once, then at most one
 * every [intervalMs], always ending with the latest. A debounce on the whole state, which every
 * streamed delta resets, would hold back a status change until the run is over.
 *
 * While the app is connected to the selected host, the same inputs come again every
 * [refreshIntervalMs], so the widget's "Updated" time follows the host's confirmation about once
 * a minute instead of stopping at the last change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun homeUpdates(
    state: Flow<RemoteState>,
    intervalMs: Long = HOME_UPDATE_INTERVAL_MS,
    refreshIntervalMs: Long = HOME_REFRESH_INTERVAL_MS,
): Flow<HomeInputs> =
    flow {
            var last: Pair<RemoteState, HomeInputs>? = null
            state.collect { current ->
                val inputs = homeInputs(current, last)
                last = current to inputs
                emit(inputs)
            }
        }
        .distinctUntilChanged()
        .transformLatest { inputs ->
            emit(inputs)
            while (inputs.connectedToSelection) {
                delay(refreshIntervalMs)
                emit(inputs)
            }
        }
        .conflate()
        .transform {
            emit(it)
            delay(intervalMs)
        }

/** The route and session of a [shortcutId]. */
internal fun shortcutTarget(id: String): Pair<String, String>? {
    if (!id.startsWith("session:")) return null
    val rest = id.removePrefix("session:")
    // Nullable on purpose: the strict push-id check, whose route IDs never contain the colon.
    val route = rest.substringBefore(':', "").ifEmpty { null }.takeIf(::isOpaqueId) ?: return null
    val session = rest.substringAfter(':').takeIf(::isOpaqueId) ?: return null
    return route to session
}

/** The share-target category of res/xml/shortcuts.xml that session shortcuts carry. */
internal const val SHARE_TARGET_CATEGORY = "de.joinnoah.pi.remote.category.SHARE_TARGET"

internal const val PUBLISHED_SHORTCUTS_PREFS = "pocket_pi_shortcuts"
internal const val PUBLISHED_SHORTCUTS_KEY = "signature"
/** The versionCode whose launch intents all shortcuts carry. */
internal const val PUBLISHED_SHORTCUTS_VERSION_KEY = "version"
