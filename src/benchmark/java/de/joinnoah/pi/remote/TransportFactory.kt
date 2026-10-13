package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * The benchmark APK's application talks to [FakeRelayTransport] instead of the relay, so the
 * connected-flow benchmarks run the app's own repository with its lifecycle and network wiring.
 * The fixture is read off the main thread on first use, not during Application.onCreate.
 */
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport {
    val assets = context.applicationContext.assets
    return FakeRelayTransport(scope, {
        RelayFixture.parse(assets.open(CONNECTED_FLOW_FIXTURE).bufferedReader().use { it.readText() })
    }).also { ConnectedFlowRelay.transport = it }
}

/** The process's fake relay, for [ConnectedFlowActivity] to select profiles and send commands. */
internal object ConnectedFlowRelay {
    @Volatile var transport: FakeRelayTransport? = null
}
