package de.joinnoah.pi.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val EXTRA_PROFILE = "profile"
internal const val EXTRA_PREPARE = "prepare"
internal const val EXTRA_COMMAND = "command"
internal const val EXTRA_STREAM_INTERVAL = "streamIntervalMs"

/**
 * Benchmark-only entry to the normal app UI on the fake relay. It hosts [RemoteApp] with the
 * application's own repository and settings, exactly as MainActivity does, and adds three extras:
 *
 * - `profile`: `lan`, `lte` or `bad`; the fake relay's latency from now on in this process.
 * - `prepare`: instead of the app, pair with the fixture Mac through the real pairing path (or
 *   reconnect when it is paired), open the fixture chat, leave a draft there and return to the
 *   projects, always on the `lan` profile. It reports `connected-flow-prepared` only once the
 *   navigation snapshot on disk holds that chat and the refreshed quick chats, so the pairing,
 *   draft and snapshot sit in the app's real stores as after a normal visit, whatever the profile.
 * - `command`: `drop` fails the socket (the repository reconnects); `stream` streams the fixture
 *   answer into the open chat (`streamIntervalMs` replaces the fixture's chunk interval). Send it
 *   again with FLAG_ACTIVITY_SINGLE_TOP to reach this activity.
 *   A drop starts a [FlowFreshness] mark, as does every return of the process to the foreground.
 *
 * Over the app it lays 1 dp nodes whose content descriptions the benchmarks wait for:
 * `connected-flow-<macs|projects|sessions|chat>-<settled|busy>` (settled: connected, not loading,
 * and no request waiting for the fake relay); once a chat finished opening,
 * `connected-flow-cache-<hit|miss>` (whether that open found the chat in the chat cache); and
 * `connected-flow-mark-<n>-<fresh|pending>`, the newest [FlowFreshness] mark and whether the app
 * has read the open screen again since.
 */
class ConnectedFlowActivity : ComponentActivity() {
    private val app
        get() = application as RemoteApplication

    private var prepared by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the first command, so a drop sent with the launch is marked too.
        ConnectedFlowRelay.freshness(app.repository)
        apply(intent)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PREPARE, false)) {
            // The navigator would restore and activate its own selection meanwhile, so the
            // prepare pass runs without the app's UI.
            setContent { PrepareStatus() }
            lifecycleScope.launch { prepared = prepare() }
            return
        }
        setContent {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                CompositionLocalProvider(LocalPromptPredictions provides app.predictions) {
                    RemoteApp(app.repository, app.settings, app.pushConfigured, app::enablePush, disablePush = app::disablePush)
                }
                FlowStatus(Modifier.align(Alignment.BottomStart))
            }
        }
    }

    /**
     * The status nodes described above. They recompose only when the derived status changes, a
     * few times per open, so they add next to nothing to the measured frames.
     */
    @Composable
    private fun FlowStatus(modifier: Modifier) {
        val statuses by remember {
            val inFlight = ConnectedFlowRelay.transport?.inFlight ?: flowOf(0)
            val freshness = ConnectedFlowRelay.freshness(app.repository)?.status ?: flowOf(null)
            combine(app.repository.state, inFlight, freshness) { state, requests, fresh ->
                val selection = state.selection
                val screen = when {
                    selection.routeId == null -> "macs"
                    selection.projectId == null -> "projects"
                    selection.sessionId == null -> "sessions"
                    else -> "chat"
                }
                val idle = state.connected && !state.loading
                StatusNodes(screen, idle && requests == 0, screen == "chat" && idle, fresh)
            }
                .distinctUntilChanged()
                .map { status ->
                    listOfNotNull(
                        "connected-flow-${status.screen}-" + if (status.settled) "settled" else "busy",
                        ConnectedFlowPreparation.lastChatCache()
                            ?.takeIf { status.chatOpened }
                            ?.let { "connected-flow-cache-$it" },
                        status.fresh?.let {
                            "connected-flow-mark-${it.mark}-" + if (it.fresh) "fresh" else "pending"
                        },
                    )
                }
        }.collectAsState(initial = emptyList())
        Box(modifier) {
            statuses.forEach { status ->
                Box(Modifier.size(1.dp).semantics { contentDescription = status })
            }
        }
    }

    private data class StatusNodes(
        val screen: String,
        val settled: Boolean,
        val chatOpened: Boolean,
        val fresh: FlowFreshness.Status?,
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent)
    }

    override fun onResume() {
        super.onResume()
        // As MainActivity: a running session stays live in the background (LiveRunService).
        LiveRun.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) LiveRun.onPause(this)
    }

    override fun onStop() {
        // As MainActivity: drafts reach the disk when the app leaves the screen.
        lifecycleScope.launch {
            try {
                app.repository.flush()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                app.repository.reportError(R.string.remote_storage_error)
            }
        }
        super.onStop()
    }

    private fun apply(intent: Intent) {
        val relay = ConnectedFlowRelay.transport ?: return
        intent.getStringExtra(EXTRA_PROFILE)?.let { relay.profile = it }
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "drop" -> {
                ConnectedFlowRelay.freshness(app.repository)?.mark(FlowFreshness.Cause.DROP)
                relay.drop()
            }
            "stream" -> {
                val interval = intent.getLongExtra(EXTRA_STREAM_INTERVAL, 0L).takeIf { it > 0 }
                app.repository.state.value.selection.sessionId?.let { relay.stream(it, interval) }
            }
        }
    }

    private suspend fun prepare(): Boolean {
        val relay = checkNotNull(ConnectedFlowRelay.transport)
        val repository = app.repository
        // Always LAN: the stored state then does not depend on the profile under test.
        relay.profile = "lan"
        return try {
            val fixture = relay.fixture()
            if (!ConnectedFlowPreparation.visit(repository, fixture, System.currentTimeMillis())) return false
            // The quick-chat refresh the visit started rewrites the navigation snapshot one
            // second after each reply. Wait until the snapshot on disk is the final one.
            val store = NavigationSnapshotStore(applicationContext)
            suspend fun settled(): Boolean {
                if (relay.inFlight.value != 0) return false
                val stored = withContext(Dispatchers.IO) {
                    runCatching { store.load()[fixture.routeId] }.getOrNull()
                }
                return ConnectedFlowPreparation.settled(fixture, repository.state.value, stored)
            }
            withTimeoutOrNull(60_000) {
                while (!settled()) delay(100)
                true
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    @Composable
    private fun PrepareStatus() {
        val status = when (prepared) {
            null -> "preparing"
            true -> "prepared"
            false -> "failed"
        }
        MaterialTheme {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Text(
                    stringResource(
                        when (prepared) {
                            null -> R.string.connected_flow_preparing
                            true -> R.string.connected_flow_prepared
                            false -> R.string.connected_flow_prepare_failed
                        }
                    ),
                    modifier = Modifier.semantics { contentDescription = "connected-flow-$status" },
                )
            }
        }
    }
}
