package de.joinnoah.pi.remote

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*

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
    private val deviceName: () -> String = { (Build.MANUFACTURER + " " + Build.MODEL).take(100) },
) : RemoteTransport {
    override lateinit var listener: RemoteTransport.Listener
    private var socket: WebSocket? = null
    private var channel: SecureChannel? = null
    private var generation = 0L
    private var handshakeTimeout: Job? = null
    private var pairingTimeout: Job? = null

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
        var phase = "challenge"
        var approvedHost: PairedHost? = null
        val request =
            Request.Builder()
                .url(relay.replaceFirst("https://", "wss://") + "/v1/peer/" + routeId)
                .build()
        handshakeTimeout = scope.launch {
            delay(20000)
            if (epoch == generation) {
                close()
                // A paired device retries, since a sleeping Mac never answers the hello; a
                // pairing code is single-use, so a stalled pairing stops.
                listener.failed(pairing == null, R.string.remote_connection_error)
            }
        }
        if (pairing != null) {
            pairingTimeout = scope.launch {
                delay(120000)
                if (epoch == generation) {
                    close()
                    listener.failed(false, R.string.remote_denied)
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
                            webSocket.send(hello.toString())
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch {
                            if (epoch != generation) return@launch
                            try {
                                val frame = Wire.parse(text)
                                if (phase == "challenge") {
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
                                    phase = "ready"
                                    return@launch
                                }
                                val payload = checkNotNull(channel).open(frame)
                                when (phase) {
                                    "ready" -> {
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
                                            phase = "approval"
                                            listener.approval()
                                        } else {
                                            send(Wire.objectOf("type" to "device.authenticate"))
                                            phase = "authentication"
                                        }
                                    }
                                    "approval" -> {
                                        if (payload.text("type") == "pair.denied") {
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
                                        phase = "persisting"
                                        listener.persistPairing(host)
                                        if (epoch != generation) return@launch
                                        send(Wire.objectOf("type" to "pair.ack"))
                                        approvedHost = host
                                        phase = "paired"
                                        handshakeTimeout?.cancel()
                                        pairingTimeout?.cancel()
                                        webSocket.close(1000, "paired")
                                    }
                                    "authentication" -> {
                                        if (payload.text("type") == "device.denied") {
                                            // Strict: exactly `type` and a known `reason`; anything
                                            // else throws into the generic connection error.
                                            Wire.keys(payload, setOf("type", "reason"))
                                            val message =
                                                when (payload.text("reason")) {
                                                    "revoked" -> R.string.remote_device_revoked
                                                    "paused" -> R.string.remote_device_paused
                                                    else -> error("Unknown denial reason")
                                                }
                                            close()
                                            // No automatic reconnect: the user has to act first.
                                            listener.failed(false, message)
                                            return@launch
                                        }
                                        require(payload.text("type") == "device.authenticated")
                                        phase = "active"
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
                                    "active" -> listener.message(payload)
                                    else -> error("Unexpected handshake")
                                }
                            } catch (_: Exception) {
                                if (epoch == generation) {
                                    val error =
                                        if (phase == "persisting") R.string.remote_storage_error
                                        else R.string.remote_connection_error
                                    close()
                                    listener.failed(
                                        false,
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
