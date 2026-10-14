package de.joinnoah.pi.remote

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The benchmark APK's application talks to [FakeRelayTransport] instead of the relay, so the
 * connected-flow benchmarks run the app's own repository with its lifecycle and network wiring.
 * The fixture is read off the main thread on first use, not during Application.onCreate.
 */
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport {
    val assets = context.applicationContext.assets
    ConnectedFlowRelay.scope = scope
    return FakeRelayTransport(scope, {
        RelayFixture.parse(assets.open(CONNECTED_FLOW_FIXTURE).bufferedReader().use { it.readText() })
    }).also { ConnectedFlowRelay.transport = it }
}

/** The process's fake relay, for [ConnectedFlowActivity] to select profiles and send commands. */
internal object ConnectedFlowRelay {
    @Volatile var transport: FakeRelayTransport? = null
    /** The application's scope, which outlives every activity. */
    @Volatile var scope: CoroutineScope? = null
    private var freshness: FlowFreshness? = null

    /**
     * The process's [FlowFreshness], created on first use from the main thread. It follows the
     * same ProcessLifecycleOwner events that RemoteApplication hands the repository, registered
     * after the application's own observer, so a resume mark comes right after the repository
     * picked its resume tier.
     */
    fun freshness(repository: RemoteRepository): FlowFreshness? {
        freshness?.let { return it }
        val relay = transport ?: return null
        val scope = scope ?: return null
        return FlowFreshness(relay).also { tracker ->
            freshness = tracker
            scope.launch { combine(repository.state, relay.replies) { state, _ -> state }.collect(tracker::observe) }
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = tracker.foreground(true)

                    override fun onStop(owner: LifecycleOwner) = tracker.foreground(false)
                }
            )
        }
    }
}
