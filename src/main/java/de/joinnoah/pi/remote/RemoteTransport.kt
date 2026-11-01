package de.joinnoah.pi.remote

import android.os.Build
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

/**
 * A `remote.stream.v1` connection acknowledges what it decrypted with `remote.ack` once this much
 * frame text arrived since the last ack, and [ACK_INTERVAL_MILLIS] passed since it.
 */
internal const val ACK_BYTES = 32 * 1024

/** At most five acks per second. */
internal const val ACK_INTERVAL_MILLIS = 200L

/** One trailing ack follows the last unacknowledged frame after this long without another frame. */
internal const val ACK_IDLE_MILLIS = 1_000L

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
     * socket no longer takes through [Listener.failed]. A `session.focus` payload also starts the
     * connection's `remote.ack`s: the repository sends it only to a host that advertised
     * `remote.stream.v1`, and the host takes acks only after it.
     */
    fun send(payload: JsonObject)

    /** Calls [beforePossibleWrite] before any write can occur, after caller cancellation checks. */
    suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        currentCoroutineContext().ensureActive()
        beforePossibleWrite()
        send(payload)
    }

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
 *
 * Once the app sent `session.focus` on a connection, the reader thread counts the text of every
 * frame it decrypts and acknowledges the highest sequence with `remote.ack` when [ACK_BYTES]
 * arrived and [ACK_INTERVAL_MILLIS] passed since the last ack; a timer on [sender] adds one trailing
 * ack [ACK_IDLE_MILLIS] after the last unacknowledged frame. The first ack follows the focus at once.
 * Acks never wait for [scope], so a busy main thread does not hold back the Mac's delivery window.
 */
class SecureRemoteTransport(
    private val scope: CoroutineScope,
    private val http: WebSocket.Factory = foregroundHttp,
    /** Names this transport's connections in [Diagnostics], such as `foreground` or `background`. */
    private val diagnosticsName: String = "foreground",
    /** Seals and sends an active connection's payloads; it must run one task at a time, in order. */
    private val sender: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** A monotonic clock in milliseconds that paces the acks; injectable for tests. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val deviceName: () -> String = { (Build.MANUFACTURER + " " + Build.MODEL).take(100) },
) : RemoteTransport {
    override lateinit var listener: RemoteTransport.Listener
    // Test-only race barrier: cancellation after handler registration, before queue admission.
    internal var beforeGuardedEnqueueForTest: (() -> Unit)? = null

    private class QueuedFrame(
        var payload: JsonObject,
        var cost: Int,
        val control: Boolean,
        val caller: CoroutineContext? = null,
        val callerJob: Job? = null,
        val beforeWrite: (() -> Unit)? = null,
        val continuation: CancellableContinuation<Unit>? = null,
        val gate: AtomicInteger = AtomicInteger(0),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
        val invalid: Exception? = null,
    )

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
        private val queueLock = Any()
        private val pending = ArrayDeque<QueuedFrame>()
        private val queueBudget = OutboundQueueBudget()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val budgetLock = Any()
        private val frameBudget = OutboundFrameBudget()
        private var senderJob: Job? = null

        /** Guards the ack state below, which the reader thread, [send] and the trailing timer share. */
        private val ackLock = Any()
        /** Set when the app queued `session.focus` on this connection; acks follow from then on. */
        private var acking = false
        /** The sequence of the last frame decrypted, or -1. */
        private var receivedSequence = -1L
        /** The sequence the last ack named, or -1. */
        private var ackedSequence = -1L
        /** Frame text received since the last ack, in characters. */
        private var unackedChars = 0L
        private var lastAckAt = 0L
        private var lastFrameAt = 0L
        private var trailingAck: Job? = null
        private var ackPending = false

        /**
         * On the reader thread, after frame [sequence] of [size] characters was decrypted: an ack
         * goes out when one is due, and otherwise the trailing ack waits for it.
         */
        fun received(sequence: Long, size: Int) {
            synchronized(ackLock) {
                // end() may have closed the queue since the reader checked.
                if (closed.get()) return
                receivedSequence = sequence
                if (!acking) return
                unackedChars += size
                lastFrameAt = clock()
                if (unackedChars >= ACK_BYTES && lastFrameAt - lastAckAt >= ACK_INTERVAL_MILLIS) ackLocked(lastFrameAt)
                else scheduleTrailingAckLocked()
            }
        }

        /** Starts acking with an ack of what arrived so far; the caller queued `session.focus` first. */
        fun startAcks() {
            synchronized(ackLock) {
                if (acking) return
                acking = true
                ackLocked(clock())
            }
        }

        /**
         * Queues the ack of the highest decrypted frame. Under [ackLock], so acks enter the queue in
         * ascending order, as the host requires.
         */
        private fun ackLocked(now: Long) {
            if (receivedSequence < 0 || receivedSequence <= ackedSequence) return
            if (!queueAck(remoteAckPayload(receivedSequence))) {
                ackPending = true
                return
            }
            ackPending = false
            ackedSequence = receivedSequence
            unackedChars = 0
            lastAckAt = now
            Diagnostics.count(Diagnostics.Counter.ACKS_SENT)
        }

        private fun scheduleTrailingAckLocked() {
            if (trailingAck != null) return
            trailingAck =
                scope.launch(sender) {
                    var wait = ACK_IDLE_MILLIS
                    while (wait > 0) {
                        delay(wait)
                        wait =
                            synchronized(ackLock) {
                                val idle = clock() - lastFrameAt
                                val next =
                                    when {
                                        closed.get() || receivedSequence <= ackedSequence -> 0L
                                        // A frame came meanwhile: the ack waits for the quiet after it.
                                        idle < ACK_IDLE_MILLIS -> ACK_IDLE_MILLIS - idle
                                        else -> {
                                            ackLocked(clock())
                                            0L
                                        }
                                    }
                                if (next == 0L) trailingAck = null
                                next
                            }
                    }
                }
        }

        /** Runs [task] on [scope] after every task handed over before it, unless the connection ended. */
        fun post(task: () -> Unit) {
            lane.trySend { if (epoch == generation) task() }
        }

        private fun frameCost(payload: JsonObject): Int =
            OutboundFrameBudget.encryptedFrameUpperBound(payload.toString().toByteArray(Charsets.UTF_8).size)

        private fun queueFrame(frame: QueuedFrame): Boolean = synchronized(queueLock) {
            if (closed.get() || !queueBudget.tryReserve(frame.cost, frame.control)) return@synchronized false
            pending.addLast(frame)
            wake.trySend(Unit)
            true
        }

        private fun queueAck(payload: JsonObject): Boolean = synchronized(queueLock) {
            if (closed.get()) return@synchronized false
            val cost = frameCost(payload)
            val tail = pending.lastOrNull()
            if (tail != null && tail.control && tail.payload.optionalText("type") == "remote.ack") {
                queueBudget.release(tail.cost, true)
                if (!queueBudget.tryReserve(cost, true)) {
                    check(queueBudget.tryReserve(tail.cost, true))
                    return@synchronized false
                }
                tail.payload = payload
                tail.cost = cost
                return@synchronized true
            }
            if (!queueBudget.tryReserve(cost, true)) return@synchronized false
            pending.addLast(QueuedFrame(payload, cost, true))
            wake.trySend(Unit)
            true
        }

        fun queue(payload: JsonObject) {
            val control = payload.optionalText("type") == "session.editor.presence"
            val frame = try { QueuedFrame(payload, frameCost(payload), control) }
            catch (error: IllegalArgumentException) {
                QueuedFrame(Wire.objectOf("type" to "invalid"), 1024, control, invalid = error)
            }
            check(queueFrame(frame))
        }

        suspend fun queueGuarded(payload: JsonObject, beforeWrite: () -> Unit) {
            val context = currentCoroutineContext()
            context.ensureActive()
            suspendCancellableCoroutine<Unit> { continuation ->
                val frame = QueuedFrame(payload, frameCost(payload), payload.optionalText("type") == "session.editor.presence", context.minusKey(Job), context[Job], beforeWrite, continuation)
                continuation.invokeOnCancellation {
                    if (frame.gate.compareAndSet(0, 2)) {
                        frame.cancelled.complete(Unit)
                        val removed = synchronized(queueLock) {
                            if (pending.remove(frame)) { queueBudget.release(frame.cost, frame.control); true } else false
                        }
                        if (removed) retryAck()
                    }
                }
                beforeGuardedEnqueueForTest?.invoke()
                if (!queueFrame(frame)) {
                    if (frame.gate.compareAndSet(0, 2)) frame.cancelled.complete(Unit)
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Outbound queue full or closed"))
                } else if (frame.gate.get() == 2 || (!continuation.isActive && frame.gate.compareAndSet(0, 2))) {
                    frame.cancelled.complete(Unit)
                    val removed = synchronized(queueLock) {
                        if (pending.remove(frame)) { queueBudget.release(frame.cost, frame.control); true } else false
                    }
                    if (removed) retryAck()
                }
            }
        }

        private fun retryAck() {
            synchronized(ackLock) { if (ackPending && !closed.get()) ackLocked(clock()) }
        }

        fun sendImmediate(payload: JsonObject, encrypted: Boolean): Boolean = synchronized(budgetLock) {
            val text = if (encrypted) {
                val estimated = frameCost(payload)
                check(frameBudget.delayMillis(clock(), estimated) == 0L)
                checkNotNull(channel).seal(payload).toString().also { check(it.toByteArray(Charsets.UTF_8).size <= estimated) }
            } else payload.toString().also { check(frameBudget.delayMillis(clock(), it.toByteArray(Charsets.UTF_8).size) == 0L) }
            frameBudget.record(clock(), text.toByteArray(Charsets.UTF_8).size)
            checkNotNull(socket).send(text)
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
            senderJob = scope.launch(sender) {
                for (ignored in wake) {
                    while (true) {
                        val frame = synchronized(queueLock) { pending.removeFirstOrNull() } ?: break
                        try {
                            while (!closed.get() && frame.gate.get() != 2) {
                                val wait = synchronized(budgetLock) { frameBudget.delayMillis(clock(), frame.cost) }
                                if (wait <= 0) break
                                withTimeoutOrNull(wait) { frame.cancelled.await() }
                            }
                            if (closed.get() || unreadable || channel?.isClosed != false || frame.gate.get() == 2) {
                                frame.continuation?.cancel()
                                continue
                            }
                            var writeStarted = false
                            try {
                                val send = {
                                    if (frame.continuation != null) {
                                        if (!frame.gate.compareAndSet(0, 1) || frame.callerJob?.isActive == false || closed.get() || !active || epoch != generation) throw CancellationException("Guarded send cancelled")
                                        frame.beforeWrite?.invoke()
                                    }
                                    writeStarted = true
                                    frame.invalid?.let { throw it }
                                    sendImmediate(frame.payload, true)
                                }
                                val sent = if (frame.caller != null) withContext(frame.caller) { send() } else send()
                                if (!sent) { unreadable = true; onSendFailure(null); throw IllegalStateException("Socket refused send") }
                                frame.continuation?.takeIf { it.isActive }?.resume(Unit)
                            } catch (e: CancellationException) {
                                frame.continuation?.cancel(e)
                            } catch (e: Exception) {
                                if (writeStarted && !unreadable) { unreadable = true; onSendFailure(e) }
                                frame.continuation?.takeIf { it.isActive }?.resumeWithException(e)
                            }
                        } catch (e: CancellationException) {
                            frame.continuation?.cancel(e)
                            throw e
                        } finally {
                            synchronized(queueLock) { queueBudget.release(frame.cost, frame.control) }
                            retryAck()
                        }
                    }
                }
            }
        }

        fun end() {
            closed.set(true)
            senderJob?.cancel()
            synchronized(ackLock) { trailingAck?.cancel() }
            channel?.close()
            lane.close()
            synchronized(queueLock) {
                while (pending.isNotEmpty()) {
                    val frame = pending.removeFirst()
                    queueBudget.release(frame.cost, frame.control)
                    if (frame.gate.compareAndSet(0, 2)) frame.cancelled.complete(Unit)
                    frame.continuation?.cancel()
                }
            }
            wake.close()
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
        if ((payload["type"] as? JsonPrimitive)?.content == "session.focus") current.startAcks()
    }

    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        val current = checkNotNull(connection)
        check(current.active)
        current.queueGuarded(payload, beforePossibleWrite)
        if ((payload["type"] as? JsonPrimitive)?.content == "session.focus") current.startAcks()
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
            if (connection.sendImmediate(payload, true)) return true
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
                            if (!connection.sendImmediate(hello, false)) { lost(); return@post }
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
                                val frame = Wire.parse(text)
                                open.open(frame).also { connection.received(frame.long("seq"), text.length) }
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
                        // OkHttp's ping task fails the socket from its writer thread; note it before the lane runs.
                        if (Diagnostics.isPingFailure(t)) Diagnostics.count(Diagnostics.Counter.PING_FAILURES)
                        connection.post {
                            ended("failure." + t.javaClass.simpleName.ifEmpty { "Throwable" }, null)
                        }
                    }
                },
            )
    }
}
