package de.joinnoah.pi.remote

import android.os.Build
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*

/** The transport gives up on a handshake that has not authenticated after this long. */
internal const val HANDSHAKE_TIMEOUT_MILLIS = 20_000L

/**
 * The Mac must answer the hello with its challenge this long after the relay accepted the socket;
 * the attempt then fails as retryable, like a handshake timeout. [HANDSHAKE_TIMEOUT_MILLIS] still
 * bounds the whole handshake.
 */
internal const val CHALLENGE_TIMEOUT_MILLIS = 10_000L

/** A pairing the user has not approved on the Mac this long after it started fails. */
internal const val PAIRING_TIMEOUT_MILLIS = 120_000L

/**
 * Where a connection's handshake stands. Each value names what the transport is waiting for:
 * [OPENING] the socket to the relay, [CHALLENGE] the Mac's answer to the hello, [READY] the Mac's
 * encrypted ready, [AUTHENTICATING] the device's sign-in, and for a pairing [APPROVAL] the user on
 * the Mac and [PERSISTING] the new keys on this device. [PAIRED] and [ACTIVE] are the ends.
 */
enum class HandshakePhase {
    OPENING,
    CHALLENGE,
    READY,
    AUTHENTICATING,
    APPROVAL,
    PERSISTING,
    PAIRED,
    ACTIVE,
}

interface RemoteTransport {
    interface Listener {
        fun ready(capabilities: Set<String> = emptySet())

        fun approval()

        suspend fun persistPairing(host: PairedHost)

        fun paired(host: PairedHost)

        /**
         * An established connection received a frame. Handed to the transport's scope at receipt,
         * before the socket's reader thread decrypts and parses the frame; [message] follows with
         * its payload, or [broken] and [failed] when it cannot be read.
         */
        fun inbound() {}

        fun message(payload: JsonObject)

        fun failed(reconnect: Boolean, error: Int)

        /** The relay closed the socket with [code]; delivered just before [failed]. */
        fun closed(code: Int) {}

        /**
         * A frame this app cannot read (undecryptable, out of sequence, too large or malformed)
         * ended the connection; delivered just before [failed].
         */
        fun broken() {}

        /** The handshake reached [phase]: the transport now waits for what the phase names. */
        fun progress(phase: HandshakePhase) {}
    }

    var listener: Listener

    fun connect(host: PairedHost)

    fun pair(value: JsonObject)

    /**
     * Queues [payload] for the active connection and returns; it is sealed and sent off the
     * caller's thread, in call order. Throws only when no connection is active. A payload that cannot
     * be sealed ends the connection through [Listener.broken] and [Listener.failed], one the
     * socket no longer takes through [Listener.failed].
     */
    fun send(payload: JsonObject)

    fun close()
}

/** The foreground relay socket's client, derived from [baseHttpClient]. */
internal val foregroundHttp: OkHttpClient by lazy {
    baseHttpClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
}

/**
 * The socket's reader thread (OkHttp calls `onMessage` there, one frame at a time) validates the
 * challenge, creates the [SecureChannel], and decrypts and parses every encrypted frame. Each
 * decoded result goes to the connection's ordering lane, one consumer on [scope] that runs tasks in
 * the order they were handed over; the handshake, phases and every [RemoteTransport.Listener] call
 * run there. `onClosed` and `onFailure` hand their task to the same lane, so they are handled after
 * every frame already decoded, even when OkHttp calls them from its writer thread. [send] hands
 * payloads to [sender], which seals and sends them in call order.
 */
class SecureRemoteTransport(
    private val scope: CoroutineScope,
    private val http: WebSocket.Factory = foregroundHttp,
    /** Names this transport's connections in [Diagnostics], such as `foreground` or `background`. */
    private val diagnosticsName: String = "foreground",
    /** Seals and sends an active connection's payloads; it must run one task at a time, in order. */
    private val sender: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val deviceName: () -> String = { (Build.MANUFACTURER + " " + Build.MODEL).take(100) },
) : RemoteTransport {
    override lateinit var listener: RemoteTransport.Listener

    /**
     * One socket and what the reader thread and the sender share with [scope]. Everything else
     * about the connection stays confined to [scope].
     */
    private inner class Connection(val epoch: Long) {
        /** Set by [close]; the reader thread and the sender drop this connection's work unparsed. */
        val closed = AtomicBoolean(false)

        /** Set on the reader thread or the sender once a frame failed; later frames are dropped. */
        @Volatile var unreadable = false

        /** Created on the reader thread from the challenge. */
        @Volatile var channel: SecureChannel? = null

        @Volatile var socket: WebSocket? = null

        /**
         * Set on [scope] once the handshake is done. Until then the handshake seals its own frames
         * on the lane, so [send] refuses payloads that could overtake them.
         */
        var active = false

        /** The close code the relay sent, set on the reader thread before the socket closes. */
        @Volatile var closingCode: Int? = null

        private val lane = Channel<() -> Unit>(Channel.UNLIMITED)
        private val outgoing = Channel<JsonObject>(Channel.UNLIMITED)

        /** Runs [task] on [scope] after every task handed over before it, unless the connection ended. */
        fun post(task: () -> Unit) {
            lane.trySend { if (epoch == generation) task() }
        }

        fun queue(payload: JsonObject) {
            check(outgoing.trySend(payload).isSuccess)
        }

        /**
         * Starts the lane and the sender. A lane task that throws goes to [onTaskFailure], so no
         * listener exception stalls the tasks behind it; a payload that cannot be sealed goes to
         * [onSendFailure] with its exception, one the socket refuses with null.
         */
        fun start(onTaskFailure: (Exception) -> Unit, onSendFailure: (Exception?) -> Unit) {
            scope.launch {
                for (task in lane) {
                    try {
                        task()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onTaskFailure(e)
                    }
                    // One frame per dispatch, as when every frame was its own coroutine.
                    yield()
                }
            }
            scope.launch(sender) {
                for (payload in outgoing) {
                    val open = channel
                    // Dropped without sealing: the connection closed, or a failure already ended it.
                    if (closed.get() || unreadable || open == null || open.isClosed) continue
                    try {
                        val sent = checkNotNull(socket).send(open.seal(payload).toString())
                        if (!sent) {
                            unreadable = true
                            onSendFailure(null)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unreadable = true
                        onSendFailure(e)
                    }
                }
            }
        }

        fun end() {
            closed.set(true)
            channel?.close()
            lane.close()
            outgoing.close()
            socket?.cancel()
        }
    }

    private var connection: Connection? = null
    private var generation = 0L
    private var handshakeTimeout: Job? = null
    private var challengeTimeout: Job? = null
    private var pairingTimeout: Job? = null
    private var connectTrace: Diagnostics.ConnectTrace? = null

    override fun connect(host: PairedHost) {
        close()
        start(host.relay, host.routeId, host.deviceId, host.secret)
    }

    override fun pair(value: JsonObject) {
        close()
        start(
            value.text("relay"),
            value.text("routeId"),
            value.text("pairId"),
            value.text("secret"),
            value,
        )
    }

    override fun close() {
        // A replaced or abandoned attempt ends here; one that already failed keeps its cause.
        connectTrace?.finish("closed.local")
        connectTrace = null
        generation++
        handshakeTimeout?.cancel()
        challengeTimeout?.cancel()
        pairingTimeout?.cancel()
        connection?.end()
        connection = null
    }

    override fun send(payload: JsonObject) {
        val current = checkNotNull(connection)
        check(current.active)
        current.queue(payload)
    }

    private fun start(
        relay: String,
        routeId: String,
        keyId: String,
        secret: String,
        pairing: JsonObject? = null,
    ) {
        val epoch = ++generation
        val connection = Connection(epoch)
        this.connection = connection
        val hello =
            Wire.objectOf(
                "v" to 1,
                "type" to "hello",
                "mode" to if (pairing == null) "device" else "pair",
                "routeId" to routeId,
                "keyId" to keyId,
                "connectionId" to Wire.random(),
                "clientNonce" to Wire.random(32),
            )
        val trace = Diagnostics.connectStarted(diagnosticsName + "." + hello.text("mode"))
        connectTrace = trace
        var phase = HandshakePhase.OPENING
        fun advance(next: HandshakePhase) {
            phase = next
            trace.phase(next)
            listener.progress(next)
        }
        advance(HandshakePhase.OPENING)
        var approvedHost: PairedHost? = null
        // Set once the Mac denied this device, even with a reply this app cannot read.
        var denied = false
        fun broke(e: Exception, site: String = "transport.message") {
            if (epoch != generation) return
            Diagnostics.caught(site, e)
            trace.end("protocol." + phase.name.lowercase())
            val error =
                if (phase == HandshakePhase.PERSISTING) R.string.remote_storage_error
                else R.string.remote_connection_error
            close()
            listener.broken()
            // A frame that fails to decrypt, arrives out of sequence or breaks the handshake
            // ends this connection, and a paired device tries again. A pairing code is
            // single-use, a storage error needs the user, and a denial stays one.
            listener.failed(
                pairing == null && !denied && phase != HandshakePhase.PERSISTING,
                error,
            )
        }
        // The socket closed with [code], failed, or no longer takes frames.
        fun ended(cause: String, code: Int?) {
            if (epoch != generation) return
            val approved = approvedHost
            if (approved == null) trace.end(cause)
            close()
            if (approved != null) listener.paired(approved)
            else {
                if (code != null) listener.closed(code)
                listener.failed(pairing == null, R.string.remote_connection_error)
            }
        }
        // OkHttp refuses frames once the socket is closing or failed: a lost connection, not a
        // broken one. The relay's close code, when one arrived, still tells the repository why.
        fun lost() {
            connection.closingCode?.let { ended("closed.$it", it) } ?: ended("failure.send", null)
        }
        fun timedOut(cause: String) {
            if (epoch != generation) return
            trace.finish(cause)
            close()
            // A paired device retries, since a sleeping Mac never answers the hello; a
            // pairing code is single-use, so a stalled pairing stops.
            listener.failed(pairing == null, R.string.remote_connection_error)
        }
        // Handshake frames are sealed here, on the lane: they are few and small, and the pairing
        // acknowledgement must leave before the close that follows it. Returns false once a
        // refused frame ended the connection as lost; a seal failure throws into [broke].
        fun sendNow(payload: JsonObject): Boolean {
            val frame = checkNotNull(connection.channel).seal(payload)
            if (checkNotNull(connection.socket).send(frame.toString())) return true
            lost()
            return false
        }
        fun handshake(webSocket: WebSocket, payload: JsonObject) {
            when (phase) {
                HandshakePhase.READY -> {
                    require(
                        payload.text("type") == "ready" &&
                            payload.text("mode") == hello.text("mode")
                    )
                    if (pairing != null) {
                        handshakeTimeout?.cancel()
                        if (!sendNow(Wire.objectOf("type" to "pair.request", "deviceName" to deviceName()))) return
                        advance(HandshakePhase.APPROVAL)
                        listener.approval()
                    } else {
                        if (!sendNow(Wire.objectOf("type" to "device.authenticate"))) return
                        advance(HandshakePhase.AUTHENTICATING)
                    }
                }
                HandshakePhase.APPROVAL -> {
                    if (payload.text("type") == "pair.denied") {
                        trace.finish("pair.denied")
                        close()
                        listener.failed(false, R.string.remote_denied)
                        return
                    }
                    require(payload.text("type") == "pair.approved")
                    Wire.decode(payload.text("deviceId"), 16)
                    Wire.decode(payload.text("deviceSecret"), 32)
                    val host =
                        PairedHost(
                            routeId,
                            relay,
                            payload.text("deviceId"),
                            payload.text("deviceSecret"),
                            payload.text("hostName"),
                        )
                    advance(HandshakePhase.PERSISTING)
                    // Off the lane, so a frame or a close during the write is still handled.
                    scope.launch {
                        try {
                            listener.persistPairing(host)
                            if (epoch != generation) return@launch
                            if (!sendNow(Wire.objectOf("type" to "pair.ack"))) return@launch
                            approvedHost = host
                            advance(HandshakePhase.PAIRED)
                            trace.finish("paired")
                            handshakeTimeout?.cancel()
                            pairingTimeout?.cancel()
                            webSocket.close(1000, "paired")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            broke(e)
                        }
                    }
                }
                HandshakePhase.AUTHENTICATING -> {
                    if (payload.text("type") == "device.denied") {
                        denied = true
                        // Strict: exactly `type` and a known `reason`; anything
                        // else throws into the generic connection error.
                        Wire.keys(payload, setOf("type", "reason"))
                        val message =
                            when (payload.text("reason")) {
                                "revoked" -> R.string.remote_device_revoked
                                "paused" -> R.string.remote_device_paused
                                else -> error("Unknown denial reason")
                            }
                        trace.finish("denied." + payload.text("reason"))
                        close()
                        // No automatic reconnect: the user has to act first.
                        listener.failed(false, message)
                        return
                    }
                    require(payload.text("type") == "device.authenticated")
                    connection.active = true
                    advance(HandshakePhase.ACTIVE)
                    trace.finish("ok")
                    handshakeTimeout?.cancel()
                    listener.ready(
                        (payload["capabilities"] as? JsonArray)
                            ?.also { require(it.size <= 16) }
                            ?.map {
                                it.jsonPrimitive.content.also { capability ->
                                    require(capability.toByteArray().size <= 64)
                                }
                            }
                            ?.toSet() ?: emptySet()
                    )
                }
                else -> error("Unexpected handshake")
            }
        }
        val request =
            Request.Builder()
                .url(relay.replaceFirst("https://", "wss://") + "/v1/peer/" + routeId)
                .build()
        handshakeTimeout = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MILLIS)
            timedOut("timeout." + phase.name.lowercase())
        }
        if (pairing != null) {
            pairingTimeout = scope.launch {
                delay(PAIRING_TIMEOUT_MILLIS)
                if (epoch == generation) {
                    trace.finish("pairing.timeout")
                    close()
                    listener.failed(false, R.string.remote_pairing_timeout)
                }
            }
        }
        connection.start(
            // A handshake step or a listener that throws breaks the connection.
            onTaskFailure = { broke(it) },
            onSendFailure = { error ->
                connection.post { if (error != null) broke(error, "transport.send") else lost() }
            },
        )
        connection.socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val extensions = response.header("Sec-WebSocket-Extensions")
                        connection.post {
                            trace.extensions(extensions)
                            // The relay is reached; from here on only the Mac can answer.
                            advance(HandshakePhase.CHALLENGE)
                            webSocket.send(hello.toString())
                            challengeTimeout = scope.launch {
                                delay(CHALLENGE_TIMEOUT_MILLIS)
                                // The reader thread accepts the challenge before its lane task
                                // moves the phase on; a challenge it already took was in time.
                                if (phase == HandshakePhase.CHALLENGE && connection.channel == null) {
                                    timedOut("timeout.challenge")
                                }
                            }
                        }
                    }

                    // On OkHttp's reader thread for this socket, one frame at a time.
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (connection.closed.get() || connection.unreadable) return
                        val open = connection.channel
                        if (open == null) {
                            try {
                                val frame = Wire.parse(text)
                                Wire.keys(frame, setOf("v", "type", "connectionId", "serverNonce"))
                                require(
                                    frame.long("v") == 1L &&
                                        frame.text("type") == "challenge" &&
                                        frame.text("connectionId") == hello.text("connectionId")
                                )
                                connection.channel =
                                    SecureChannel(
                                        secret,
                                        JsonObject(hello + ("serverNonce" to frame.getValue("serverNonce"))),
                                    )
                            } catch (e: Exception) {
                                connection.unreadable = true
                                connection.post { broke(e) }
                                return
                            }
                            connection.post {
                                challengeTimeout?.cancel()
                                advance(HandshakePhase.READY)
                            }
                            return
                        }
                        // A failed frame or seal closed the channel; it reported itself.
                        if (open.isClosed) return
                        connection.post { if (phase == HandshakePhase.ACTIVE) listener.inbound() }
                        val payload =
                            try {
                                open.open(Wire.parse(text))
                            } catch (e: Exception) {
                                connection.unreadable = true
                                // Only a failure on the live connection is recorded; one racing a
                                // close is that close, not a broken frame.
                                connection.post { broke(e) }
                                return
                            }
                        // A throw here, from the handshake or the listener, reaches broke() through
                        // the lane's guard.
                        connection.post {
                            if (phase == HandshakePhase.ACTIVE) listener.message(payload)
                            else handshake(webSocket, payload)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        connection.closingCode = code
                        webSocket.close(code, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connection.post { ended("closed.$code", code) }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        connection.post {
                            ended("failure." + t.javaClass.simpleName.ifEmpty { "Throwable" }, null)
                        }
                    }
                },
            )
    }
}
