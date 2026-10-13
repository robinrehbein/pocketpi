package de.joinnoah.pi.remote

import android.os.SystemClock
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/**
 * Benchmark-only stand-in for the relay and the Mac behind it. The production repository drives
 * it exactly like [SecureRemoteTransport]: replies to `projects.list`, `sessions.list`,
 * `sessions.open` and `session.snapshot` come from [RelayFixture] after a sampled round trip of the
 * selected [LatencyProfile]; anything else is refused as `unsupported`. Like the real transport,
 * every frame arrives as text and is parsed on [scope]; only the decryption is missing.
 *
 * Host-side work (generating transcripts, encoding replies) runs on [host], one thread standing in
 * for the Mac, so it neither blocks the app's main thread nor races the streamed transcript state.
 */
internal class FakeRelayTransport(
    private val scope: CoroutineScope,
    loadFixture: suspend () -> RelayFixture,
    private val host: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : RemoteTransport {
    override lateinit var listener: RemoteTransport.Listener
    private val fixture = scope.async(host, start = CoroutineStart.LAZY) { loadFixture() }
    private var random: Random? = null
    private var generation = 0L
    private var connected: PairedHost? = null
    private var streaming: Job? = null
    // Only touched on [host].
    private val transcripts = mutableMapOf<String, Transcript>()

    /** The latency profile by name (`lan`, `lte`, `bad`); null selects the fixture's default. */
    @Volatile var profile: String? = null

    /** Request types the fixture could not answer, each logged once (see the runbook). */
    val unsupported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Number of connections that reached `ready`; tests and the flow benchmarks read it. */
    var connections = 0
        private set

    val isConnected: Boolean get() = connected != null

    private val _inFlight = MutableStateFlow(0)

    /**
     * Requests sent and not yet answered or dropped. [ConnectedFlowActivity] reports the app as
     * settled only at zero, so a benchmark's setup can wait out late replies before measuring.
     */
    val inFlight: StateFlow<Int> get() = _inFlight

    suspend fun fixture(): RelayFixture = fixture.await()

    private class Transcript(var messages: List<JsonObject>, var revision: Long, var status: String)

    override fun connect(host: PairedHost) {
        close()
        val epoch = generation
        scope.launch {
            val fixture = fixture()
            delay(handshake(fixture))
            if (epoch != generation) return@launch
            if (host.routeId != fixture.routeId) {
                close()
                listener.failed(true, R.string.remote_connection_error)
                return@launch
            }
            connected = host
            connections++
            listener.ready(fixture.capabilities)
        }
    }

    override fun pair(value: JsonObject) {
        close()
        val epoch = generation
        scope.launch {
            val fixture = fixture()
            delay(handshake(fixture))
            if (epoch != generation) return@launch
            if (value.text("routeId") != fixture.routeId) {
                close()
                listener.failed(false, R.string.remote_denied)
                return@launch
            }
            listener.approval()
            val paired = PairedHost(fixture.routeId, value.text("relay"), fixture.deviceId, fixture.deviceSecret, fixture.hostName)
            try {
                listener.persistPairing(paired)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch == generation) {
                    close()
                    listener.failed(false, R.string.remote_storage_error)
                }
                return@launch
            }
            if (epoch != generation) return@launch
            // The real transport reports the pairing once the relay closed the pairing socket.
            close()
            listener.paired(paired)
        }
    }

    override fun send(payload: JsonObject) {
        // The real transport throws without an open channel; the repository relies on that.
        checkNotNull(connected)
        val epoch = generation
        val started = now()
        _inFlight.update { it + 1 }
        scope.launch {
            try {
                val fixture = fixture()
                val latency = latency(fixture)
                val text = withContext(host) {
                    try {
                        reply(fixture, payload)
                    } catch (_: Exception) {
                        failure(payload.text("requestId"), "invalid_request")
                    }.toString()
                }
                delay((latency - (now() - started)).coerceAtLeast(0))
                if (epoch == generation) deliver(text)
            } finally {
                _inFlight.update { it - 1 }
            }
        }
    }

    override fun close() {
        generation++
        connected = null
        streaming?.cancel()
        streaming = null
    }

    /** Drops the connection as a dead socket would; the repository schedules its reconnect. */
    fun drop() {
        if (connected == null) return
        close()
        listener.closed(1006)
        listener.failed(true, R.string.remote_connection_error)
    }

    /**
     * Streams the fixture's scripted answer into [sessionId]: `session.status` running, one
     * `message.upsert` per chunk every [StreamScript.intervalMs], the completed answer, a separate
     * completion marker and `session.status` idle, with consecutive revisions. A later snapshot of
     * the session includes the streamed messages.
     *
     * Unlike request replies, streamed events skip the latency profile and arrive at once. A real
     * relay delivers host frames in order, but here a `session.snapshot` answered at revision R
     * can reach the repository after events R+1 and later. Flow benchmarks that stream should
     * start the stream only once the chat's snapshot is in.
     */
    fun stream(sessionId: String): Job {
        streaming?.cancel()
        val epoch = generation
        return scope.launch {
            val fixture = fixture()
            if (connected == null || fixture.session(sessionId) == null) return@launch
            val script = fixture.stream
            val id = "$sessionId-stream-${now()}"
            var text = ""
            suspend fun event(kind: String, vararg fields: Pair<String, Any?>) {
                if (epoch != generation) throw CancellationException("Connection closed")
                deliver(withContext(host) { event(fixture, sessionId, kind, *fields).toString() })
            }
            event("session.status", "status" to "running")
            repeat(script.chunks) { index ->
                delay(script.intervalMs)
                text += script.chunk(index)
                event("message.upsert", "message" to message(id, "assistant", text, "streaming"))
            }
            event("message.upsert", "message" to message(id, "assistant", text, "complete"))
            event("message.upsert", "message" to message("$id-complete", "assistant", script.complete, "complete"))
            event("session.status", "status" to "idle")
        }.also { streaming = it }
    }

    private fun deliver(text: String) {
        try {
            listener.message(Wire.parse(text))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            close()
            listener.failed(false, R.string.remote_connection_error)
        }
    }

    private fun random(fixture: RelayFixture) = random ?: Random(fixture.seed).also { random = it }

    private fun latency(fixture: RelayFixture) = fixture.profile(profile).sample(random(fixture))

    /** Opening the socket, TLS, the hello/challenge exchange and device authentication. */
    private fun handshake(fixture: RelayFixture) =
        (1..fixture.handshakeRoundTrips).sumOf { latency(fixture) }

    private fun message(id: String, role: String, text: String, state: String) =
        Wire.objectOf("id" to id, "role" to role, "text" to text, "state" to state)

    private fun transcript(fixture: RelayFixture, session: FixtureSession) =
        transcripts.getOrPut(session.id) {
            Transcript(fixture.transcript(session), session.revision, session.session.text("status"))
        }

    private fun event(fixture: RelayFixture, sessionId: String, kind: String, vararg fields: Pair<String, Any?>): JsonObject {
        val session = checkNotNull(fixture.session(sessionId))
        val transcript = transcript(fixture, session)
        transcript.revision++
        when (kind) {
            "session.status" -> transcript.status = fields.toMap().getValue("status") as String
            "message.upsert" -> {
                val message = fields.toMap().getValue("message") as JsonObject
                val index = transcript.messages.indexOfFirst { it.text("id") == message.text("id") }
                transcript.messages =
                    if (index < 0) transcript.messages + message
                    else transcript.messages.toMutableList().apply { set(index, message) }
            }
        }
        return Wire.objectOf(
            "type" to "event", "kind" to kind, "sessionId" to sessionId,
            "revision" to transcript.revision, *fields,
        )
    }

    private fun failure(requestId: String, code: String) = Wire.objectOf(
        "type" to "result", "requestId" to requestId, "ok" to false,
        "error" to Wire.objectOf("code" to code, "message" to "Not in the fake relay fixture"),
    )

    private fun reply(fixture: RelayFixture, request: JsonObject): JsonObject {
        val type = request.text("type")
        val requestId = request.text("requestId")
        fun result(data: JsonObject) =
            Wire.objectOf("type" to "result", "requestId" to requestId, "ok" to true, "data" to data)
        fun failure(code: String) = failure(requestId, code)
        fun session(id: String) = fixture.session(id)?.let { session ->
            val status = transcripts[id]?.status ?: return@let session.session
            JsonObject(session.session + ("status" to JsonPrimitive(status)))
        }
        return when (type) {
            "projects.list" -> result(Wire.objectOf(
                "kind" to "projects",
                "items" to JsonArray(fixture.projects),
                "capabilities" to JsonArray(fixture.routeCapabilities.map(::JsonPrimitive)),
            ))
            "sessions.list" -> {
                val projectId = request.text("projectId")
                val items = fixture.sessions.filter { it.projectId == projectId }.mapNotNull { session(it.id) }
                result(Wire.objectOf("kind" to "sessions", "items" to JsonArray(items)))
            }
            "sessions.open" ->
                session(request.text("sessionId"))
                    ?.let { result(Wire.objectOf("kind" to "session", "session" to it)) }
                    ?: failure("not_found")
            "session.snapshot" -> {
                val session = fixture.session(request.text("sessionId")) ?: return failure("not_found")
                val transcript = transcript(fixture, session)
                // `before:<index>` pages toward older messages, like the host's cursor.
                val end = request.optionalText("cursor")?.removePrefix("before:")?.toIntOrNull()
                    ?.coerceIn(0, transcript.messages.size) ?: transcript.messages.size
                val start = (end - fixture.snapshotPageSize).coerceAtLeast(0)
                result(Wire.objectOf(
                    "kind" to "snapshot",
                    "sessionId" to session.id,
                    "revision" to transcript.revision,
                    "status" to transcript.status,
                    "messages" to JsonArray(transcript.messages.subList(start, end)),
                    "pendingQuestions" to JsonArray(emptyList()),
                    "nextCursor" to if (start > 0) "before:$start" else null,
                ))
            }
            else -> {
                if (unsupported.add(type)) Log.i("FakeRelayTransport", "No fixture reply for $type")
                failure("unsupported")
            }
        }
    }
}
