package de.joinnoah.pi.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Async trace sections; the flow benchmarks read them with TraceSectionMetric. */
internal interface FlowTrace {
    fun begin(name: String): Int

    fun end(name: String, cookie: Int)

    /** Perfetto through [Diagnostics], which leaves them out below API 29 and on the JVM. */
    object Platform : FlowTrace {
        override fun begin(name: String) = Diagnostics.beginAsync(name)

        override fun end(name: String, cookie: Int) = Diagnostics.endAsync(name, cookie)
    }
}

/**
 * Benchmark-only: tells the connected-flow benchmarks when the app has the open screen from the
 * Mac again after it came back to the foreground or lost its socket, and traces how long that took.
 *
 * A [mark] starts a wait. The screen is fresh again once the fake relay delivered, after the mark,
 * a reply to every read that shows it (`projects.list`; `sessions.list` of the open project;
 * `session.snapshot` of the open chat, newest page) and the repository is connected, not loading
 * and no longer stale. Whether the app probes the socket or reconnects, both read all of them
 * again. The wait ends at the reply that completed the set, so the repository applies that last
 * reply within the same main-thread turn or the next one.
 *
 * Sections (async, so they may span the whole recovery):
 * - `ConnectedFlow.resumeToFresh`: from the process's return to the foreground to fresh.
 * - `ConnectedFlow.dropToFresh`: from a dropped socket to fresh.
 * - `ConnectedFlow.dropToConnected`: from a dropped socket to the next connection reaching ready.
 *
 * Main thread only: [foreground] comes from ProcessLifecycleOwner, [observe] from a collector of
 * the repository state and [FakeRelayTransport.replies].
 */
internal class FlowFreshness(
    private val relay: FakeRelayTransport,
    private val trace: FlowTrace = FlowTrace.Platform,
) {
    enum class Cause(val section: String) {
        RESUME("ConnectedFlow.resumeToFresh"),
        DROP("ConnectedFlow.dropToFresh"),
    }

    /** [mark]: marks so far in this process; [fresh]: the reads after the newest one are in. */
    data class Status(val mark: Int, val fresh: Boolean)

    private val _status = MutableStateFlow(Status(0, fresh = true))
    val status: StateFlow<Status> get() = _status

    private var pending: Cause? = null
    private var cookie = 0
    private var markedAt = 0L
    private var connectionsAtMark = 0
    /** The open `dropToConnected` section's cookie, until a new connection is ready. */
    private var connecting: Int? = null
    private var stopped = false
    private var state: RemoteState? = null

    /**
     * The process went to the background ([value] false) or came back: a return after a stop
     * marks [Cause.RESUME]. The first start of the process is no resume.
     */
    fun foreground(value: Boolean) {
        if (!value) {
            stopped = true
            return
        }
        if (!stopped) return
        stopped = false
        mark(Cause.RESUME)
    }

    /**
     * Starts a wait for fresh data, for example just before [FakeRelayTransport.drop]. A wait still
     * open ends here, so its sections never stay open; the benchmarks wait for fresh before they
     * mark again.
     */
    fun mark(cause: Cause) {
        pending?.let { trace.end(it.section, cookie) }
        connecting?.let { trace.end(DROP_TO_CONNECTED, it) }
        pending = cause
        cookie = trace.begin(cause.section)
        connecting = if (cause == Cause.DROP) trace.begin(DROP_TO_CONNECTED) else null
        markedAt = relay.replies.value.sequence
        connectionsAtMark = relay.connections
        _status.value = Status(_status.value.mark + 1, fresh = false)
        state?.let(::observe)
    }

    /** The repository's [state] changed, or the relay delivered a reply. */
    fun observe(state: RemoteState) {
        this.state = state
        val cause = pending ?: return
        connecting?.let {
            if (state.connected && relay.connections > connectionsAtMark) {
                trace.end(DROP_TO_CONNECTED, it)
                connecting = null
            }
        }
        if (!fresh(state)) return
        connecting?.let { trace.end(DROP_TO_CONNECTED, it) }
        connecting = null
        trace.end(cause.section, cookie)
        pending = null
        _status.value = _status.value.copy(fresh = true)
    }

    private fun fresh(state: RemoteState): Boolean {
        if (!state.connected || state.loading || state.stale) return false
        val selection = state.selection
        val reads = listOfNotNull(
            "projects.list".takeIf { selection.routeId != null },
            selection.projectId?.let { "sessions.list:$it" },
            selection.sessionId?.let { "session.snapshot:$it" },
        )
        val last = relay.replies.value.last
        return reads.all { (last[it] ?: 0L) > markedAt }
    }

    companion object {
        const val DROP_TO_CONNECTED = "ConnectedFlow.dropToConnected"
    }
}
