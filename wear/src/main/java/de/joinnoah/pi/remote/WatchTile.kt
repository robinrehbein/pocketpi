package de.joinnoah.pi.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The status tile's snapshot: kept from the app's state and pushes, stored encrypted like the other
 * watch stores because it holds session titles, and refreshed over a short-lived connection of its
 * own when the tile is shown and the snapshot is older than [WATCH_GLANCE_REFRESH_AFTER_MILLIS].
 * Unlike the phone widget it asks the Mac itself, but only when the system asks to draw the tile.
 */
internal class WatchGlanceStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: RemoteRepository,
    private val pairings: PairingStorage,
    private val file: EncryptedFileStore = EncryptedFileStore(context, "watch-glance.enc", "pi-remote-watch-glance-v1"),
    private val redraw: () -> Unit = { TileService.getUpdater(context).requestUpdate(WatchTileService::class.java) },
) {
    private val mutex = Mutex()
    private var loaded = false
    private val forgotten = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var refreshing: Job? = null
    private val mutable = MutableStateFlow(WatchGlance())
    val glance: StateFlow<WatchGlance> = mutable.asStateFlow()

    suspend fun load(): WatchGlance = mutex.withLock { ensureLoaded(); mutable.value }

    fun observe(state: StateFlow<RemoteState>) {
        scope.launch {
            delay(HOME_UPDATE_INTERVAL_MS)
            homeUpdates(state).collect { inputs ->
                val routes = inputs.hostRoutes.toSet() - forgotten
                launchUpdate { mergeHomeSnapshot(it.home, inputs, routes.isNotEmpty(), routes, System.currentTimeMillis()).let(it::withHome) }
            }
        }
        scope.launch {
            state.distinctUntilChanged { a, b -> a.selection == b.selection && a.pullRequest == b.pullRequest && a.session === b.session &&
                a.connected == b.connected && a.stale == b.stale && a.preview == b.preview && a.loading == b.loading }
                .collect { current -> launchUpdate { it.withOpenSession(current, System.currentTimeMillis()) } }
        }
    }

    fun onPush(payload: PushPayload) = launchUpdate { it.withPush(payload, System.currentTimeMillis()) }

    fun forgetRoute(routeId: String) {
        forgotten += routeId
        launchUpdate { it.withoutRoute(routeId) }
    }

    /** Starts one bounded refresh of every paired Mac unless one runs or the snapshot is recent. */
    fun refreshIfStale(glance: WatchGlance) {
        if (!watchGlanceNeedsRefresh(glance, System.currentTimeMillis(), refreshing?.isActive == true)) return
        refreshing = scope.launch {
            val hosts = try { withContext(Dispatchers.IO) { pairings.load() } } catch (swallowed: Exception) {
                Diagnostics.caught("watchGlance.pairings", swallowed); return@launch
            }.filter { it.routeId !in forgotten }
            val routes = hosts.map { it.routeId }.toSet()
            hosts.forEach { host ->
                try {
                    val next = withTimeoutOrNull(WATCH_GLANCE_REFRESH_TIMEOUT_MILLIS) {
                        val previous = load()
                        usingPushConnection(repository, host, wait = false) { it.readWatchGlance(host.routeId, routes, previous, System.currentTimeMillis()) }
                    } ?: return@forEach
                    update { current -> if (host.routeId in forgotten) current else current.withRoute(host.routeId, next) }
                } catch (e: CancellationException) {
                    throw e
                } catch (swallowed: Exception) {
                    // The tile keeps its last rows and their time.
                    Diagnostics.caught("watchGlance.refresh", swallowed)
                }
            }
        }
    }

    private fun launchUpdate(change: (WatchGlance) -> WatchGlance) {
        scope.launch {
            try { update(change) } catch (e: CancellationException) { throw e } catch (swallowed: Exception) {
                Diagnostics.caught("watchGlance.update", swallowed)
            }
        }
    }

    private suspend fun update(change: (WatchGlance) -> WatchGlance) {
        val changed = mutex.withLock {
            ensureLoaded()
            val previous = mutable.value
            val next = change(previous)
            if (next == previous) false else {
                withContext(Dispatchers.IO) { file.write(next.json().toString().toByteArray()) }
                mutable.value = next
                true
            }
        }
        if (changed) try { redraw() } catch (swallowed: Exception) { Diagnostics.caught("watchGlance.redraw", swallowed) }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = withContext(Dispatchers.IO) {
            try { file.read()?.let { watchGlance(Wire.parse(Wire.utf8(it), 1024 * 1024)) } } catch (_: Exception) { null }
        } ?: WatchGlance()
        val routes = try { withContext(Dispatchers.IO) { pairings.load() }.map { it.routeId }.toSet() - forgotten } catch (_: Exception) { null }
        mutable.value = if (routes == null) stored else stored.home.let { home ->
            stored.withHome(home.copy(paired = routes.isNotEmpty(), sessions = home.sessions.filter { it.routeId in routes },
                notified = home.notified.filter { it.routeId in routes }))
        }
        loaded = true
    }
}

/** The tile to the right of the watch face: sessions that need the user or are working, and their checks. */
class WatchTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) = SuspendToFutureAdapter.launchFuture {
        val store = (application as WearApplication).glance
        val glance = store.load()
        store.refreshIfStale(glance)
        val layout = watchTileLayout(this@WatchTileService, watchTileContent(this@WatchTileService, glance, System.currentTimeMillis()), requestParams.deviceConfiguration)
        // No freshness interval: the tile never refreshes on a schedule of its own.
        TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout)).build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/** What the tile says, before it is laid out: a title, its time, then rows or a hint that opens the app. */
internal data class WatchTileContent(val title: String, val updated: String?, val rows: List<WatchTileChip>, val hint: String?)
internal data class WatchTileChip(val row: WatchTileRow, val title: String, val detail: String)

internal fun watchTileContent(context: Context, glance: WatchGlance, now: Long): WatchTileContent {
    val localized = RemoteNotifications.localized(context)
    val resources = localized.resources
    if (!glance.home.paired)
        return WatchTileContent(resources.getString(R.string.remote_widget_not_paired), null, emptyList(), resources.getString(R.string.remote_widget_pair_hint))
    val waiting = glance.home.waitingCount()
    val rows = watchTileRows(glance, now).map { row ->
        WatchTileChip(row, row.title.ifBlank { resources.getString(R.string.remote_widget_untitled) }, watchTileRowDetail(row, resources, now))
    }
    return WatchTileContent(
        if (waiting == 0) resources.getString(R.string.remote_widget_none_waiting)
        else resources.getQuantityString(R.plurals.remote_widget_waiting, waiting, waiting),
        glance.home.refreshedAt?.let { widgetFreshness(localized, it, now) },
        rows,
        if (rows.isEmpty()) resources.getString(R.string.remote_widget_empty) else null,
    )
}

internal fun watchTileLayout(context: Context, content: WatchTileContent, device: DeviceParameters): LayoutElementBuilders.LayoutElement {
    fun caption(text: String) = Text.Builder(context, text).setTypography(Typography.TYPOGRAPHY_CAPTION1).setMaxLines(1).build()
    val layout = PrimaryLayout.Builder(device).setPrimaryLabelTextContent(caption(content.title))
    content.updated?.let { layout.setSecondaryLabelTextContent(caption(it)) }
    content.hint?.let { hint ->
        return layout.setContent(Text.Builder(context, hint).setTypography(Typography.TYPOGRAPHY_BODY2).setMaxLines(3)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable(context, "app", WatchTileTarget.intentExtras(null))).build()).build()).build()
    }
    val column = LayoutElementBuilders.Column.Builder()
    content.rows.forEachIndexed { index, chip ->
        if (index > 0) column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
        val row = chip.row
        column.addContent(Chip.Builder(context, clickable(context, "session:${row.routeId}:${row.sessionId}", WatchTileTarget.intentExtras(row)), device)
            .setPrimaryLabelContent(chip.title).setSecondaryLabelContent(chip.detail).setContentDescription("${chip.title}, ${chip.detail}").build())
    }
    return layout.setContent(column.build()).build()
}

private fun clickable(context: Context, id: String, extras: Map<String, String>): ModifiersBuilders.Clickable {
    val activity = ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName).setClassName(WatchTileActivity::class.java.name)
    extras.forEach { (key, value) -> activity.addKeyToExtraMapping(key, ActionBuilders.AndroidStringExtra.Builder().setValue(value).build()) }
    return ModifiersBuilders.Clickable.Builder().setId(id)
        .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()).build()
}

/** What a tap on the tile opens: a session through the notification path, which the app validates, or the app. */
internal object WatchTileTarget {
    private const val ROUTE = "tileRouteId"
    private const val SESSION = "tileSessionId"
    fun intentExtras(row: WatchTileRow?): Map<String, String> = if (row == null) emptyMap() else mapOf(ROUTE to row.routeId, SESSION to row.sessionId)
    fun target(extras: Bundle?): NotificationTarget? {
        val route = extras?.getString(ROUTE)?.takeIf { isOpaqueId(it as String?) } ?: return null
        val session = extras.getString(SESSION)?.takeIf { isOpaqueId(it as String?) } ?: return null
        return NotificationTarget(route, session)
    }
    fun forward(context: Context, extras: Bundle?): Intent = target(extras)?.let { WearNotifications.intent(context, it) }
        ?: Intent(context, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Turns a tile tap into the app's own launch, since a tile cannot set an intent's action or flags. */
class WatchTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(WatchTileTarget.forward(this, intent?.extras))
        finish()
    }
}
