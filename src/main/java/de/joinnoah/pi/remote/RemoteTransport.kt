package de.joinnoah.pi.remote

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*

/** The transport gives up on a handshake that has not authenticated after this long. */
internal const val HANDSHAKE_TIMEOUT_MILLIS = 20_000L

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

        fun message(payload: JsonObject)

        fun failed(reconnect: Boolean, error: Int)

        /** The relay closed the socket with [code]; delivered just before [failed]. */
        fun closed(code: Int) {}

        /** The handshake reached [phase]: the transport now waits for what the phase names. */
        fun progress(phase: HandshakePhase) {}
    }

    var listener: Listener

    fun connect(host: PairedHost)

    fun pair(value: JsonObject)

    fun send(payload: JsonObject)

    fun close()
}

class SecureRemoteTransport(
    private val scope: CoroutineScope,
    private val http: WebSocket.Factory =
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    /** Names this transport's connections in [Diagnostics], such as `foreground` or `background`. */
    private val diagnosticsName: String = "foreground",
    private val deviceName: () -> String = { (Build.MANUFACTURER + " " + Build.MODEL).take(100) },
) : RemoteTransport {
    override lateinit var listener: RemoteTransport.Listener
    private var socket: WebSocket? = null
    private var channel: SecureChannel? = null
    private var generation = 0L
    private var handshakeTimeout: Job? = null
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
        pairingTimeout?.cancel()
        channel?.close()
        channel = null
        socket?.cancel()
        socket = null
    }

    override fun send(payload: JsonObject) {
        val encrypted = checkNotNull(channel).seal(payload)
        check(socket?.send(encrypted.toString()) == true)
    }

    private fun start(
        relay: String,
        routeId: String,
        keyId: String,
        secret: String,
        pairing: JsonObject? = null,
    ) {
        val epoch = ++generation
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
        val request =
            Request.Builder()
                .url(relay.replaceFirst("https://", "wss://") + "/v1/peer/" + routeId)
                .build()
        handshakeTimeout = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MILLIS)
            if (epoch == generation) {
                trace.finish("timeout." + phase.name.lowercase())
                close()
                // A paired device retries, since a sleeping Mac never answers the hello; a
                // pairing code is single-use, so a stalled pairing stops.
                listener.failed(pairing == null, R.string.remote_connection_error)
            }
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
        socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch {
                            if (epoch != generation) return@launch
                            // The relay is reached; from here on only the Mac can answer.
                            advance(HandshakePhase.CHALLENGE)
                            webSocket.send(hello.toString())
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch {
                            if (epoch != generation) return@launch
                            try {
                                val frame = Wire.parse(text)
                                if (phase == HandshakePhase.CHALLENGE || phase == HandshakePhase.OPENING) {
                                    Wire.keys(
                                        frame,
                                        setOf("v", "type", "connectionId", "serverNonce"),
                                    )
                                    require(
                                        frame.long("v") == 1L &&
                                            frame.text("type") == "challenge" &&
                                            frame.text("connectionId") == hello.text("connectionId")
                                    )
                                    channel =
                                        SecureChannel(
                                            secret,
                                            JsonObject(
                                                hello +
                                                    ("serverNonce" to frame.getValue("serverNonce"))
                                            ),
                                        )
                                    advance(HandshakePhase.READY)
                                    return@launch
                                }
                                val payload = checkNotNull(channel).open(frame)
                                when (phase) {
                                    HandshakePhase.READY -> {
                                        require(
                                            payload.text("type") == "ready" &&
                                                payload.text("mode") == hello.text("mode")
                                        )
                                        if (pairing != null) {
                                            handshakeTimeout?.cancel()
                                            send(
                                                Wire.objectOf(
                                                    "type" to "pair.request",
                                                    "deviceName" to deviceName(),
                                                )
                                            )
                                            advance(HandshakePhase.APPROVAL)
                                            listener.approval()
                                        } else {
                                            send(Wire.objectOf("type" to "device.authenticate"))
                                            advance(HandshakePhase.AUTHENTICATING)
                                        }
                                    }
                                    HandshakePhase.APPROVAL -> {
                                        if (payload.text("type") == "pair.denied") {
                                            trace.finish("pair.denied")
                                            close()
                                            listener.failed(false, R.string.remote_denied)
                                            return@launch
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
                                        listener.persistPairing(host)
                                        if (epoch != generation) return@launch
                                        send(Wire.objectOf("type" to "pair.ack"))
                                        approvedHost = host
                                        advance(HandshakePhase.PAIRED)
                                        trace.finish("paired")
                                        handshakeTimeout?.cancel()
                                        pairingTimeout?.cancel()
                                        webSocket.close(1000, "paired")
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
                                            return@launch
                                        }
                                        require(payload.text("type") == "device.authenticated")
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
                                    HandshakePhase.ACTIVE -> listener.message(payload)
                                    else -> error("Unexpected handshake")
                                }
                            } catch (e: Exception) {
                                Diagnostics.caught("transport.message", e)
                                if (epoch == generation) {
                                    trace.end("protocol." + phase.name.lowercase())
                                    val error =
                                        if (phase == HandshakePhase.PERSISTING) R.string.remote_storage_error
                                        else R.string.remote_connection_error
                                    close()
                                    // A frame that fails to decrypt, arrives out of sequence or breaks
                                    // the handshake ends this connection, and a paired device tries
                                    // again. A pairing code is single-use, a storage error needs the
                                    // user, and a denial stays one.
                                    listener.failed(
                                        pairing == null && !denied && phase != HandshakePhase.PERSISTING,
                                        error,
                                    )
                                }
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        scope.launch {
                            if (epoch == generation) {
                                val approved = approvedHost
                                if (approved == null) trace.end("closed.$code")
                                close()
                                if (approved != null) listener.paired(approved)
                                else {
                                    listener.closed(code)
                                    listener.failed(
                                        pairing == null,
                                        R.string.remote_connection_error,
                                    )
                                }
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        scope.launch {
                            if (epoch == generation) {
                                val approved = approvedHost
                                if (approved == null) trace.end("failure." + t.javaClass.simpleName.ifEmpty { "Throwable" })
                                close()
                                if (approved != null) listener.paired(approved)
                                else
                                    listener.failed(
                                        pairing == null,
                                        R.string.remote_connection_error,
                                    )
                            }
                        }
                    }
                },
            )
    }
}
