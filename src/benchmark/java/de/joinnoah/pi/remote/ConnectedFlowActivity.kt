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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val EXTRA_PROFILE = "profile"
internal const val EXTRA_PREPARE = "prepare"
internal const val EXTRA_COMMAND = "command"

/**
 * Benchmark-only entry to the normal app UI on the fake relay. It hosts [RemoteApp] with the
 * application's own repository and settings, exactly as MainActivity does, and adds three extras:
 *
 * - `profile`: `lan`, `lte` or `bad`; the fake relay's latency from now on in this process.
 * - `prepare`: instead of the app, pair with the fixture Mac through the real pairing path, open
 *   the fixture chat, leave a draft there and return to the projects. The pairing, draft and
 *   navigation snapshot then sit in the app's real stores, as after a normal visit.
 * - `command`: `drop` fails the socket (the repository reconnects); `stream` streams the fixture
 *   answer into the open chat. Send it again with FLAG_ACTIVITY_SINGLE_TOP to reach this activity.
 */
class ConnectedFlowActivity : ComponentActivity() {
    private val app
        get() = application as RemoteApplication

    private var prepared by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent)
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
            "drop" -> relay.drop()
            "stream" -> app.repository.state.value.selection.sessionId?.let(relay::stream)
        }
    }

    private suspend fun prepare(): Boolean {
        val relay = checkNotNull(ConnectedFlowRelay.transport)
        val repository = app.repository
        return try {
            val fixture = relay.fixture()
            val projects = RemoteSelection(fixture.routeId)
            if (repository.pair(fixture.pairingCode(System.currentTimeMillis() + 120_000)) != projects) return false
            if (repository.activate(fixture.prepare, ActivationMode.USER_OPEN) != fixture.prepare) return false
            repository.draft(fixture.prepareDraft)
            if (repository.activate(projects, ActivationMode.USER_OPEN) != projects) return false
            repository.flush()
            // The navigation snapshot is written one second after the last screen change.
            delay(1_500)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    @androidx.compose.runtime.Composable
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
