package de.joinnoah.pi.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class RemoteTransportTest {
    private class Socket : WebSocket, WebSocket.Factory {
        lateinit var incoming: WebSocketListener
        lateinit var requestValue: Request
        val sent = mutableListOf<String>()
        var cancelled = false

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requestValue = request
            incoming = listener
            return this
        }

        override fun request() = requestValue

        override fun queueSize() = 0L

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun send(bytes: ByteString) = false

        override fun close(code: Int, reason: String?): Boolean {
            incoming.onClosed(this, code, reason.orEmpty())
            return true
        }

        override fun cancel() {
            cancelled = true
        }

        fun open() {
            incoming.onOpen(
                this,
                Response.Builder()
                    .request(requestValue)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build(),
            )
        }

        fun receive(value: JsonObject) {
            incoming.onMessage(this, value.toString())
        }
    }

    private class Listener(private val persistence: suspend (PairedHost) -> Unit) :
        RemoteTransport.Listener {
        var paired = false
        var failed = false
        var failureCount = 0
        var capabilities: Set<String>? = null

        override fun ready(capabilities: Set<String>) {
            this.capabilities = capabilities
        }

        override fun approval() {}

        override suspend fun persistPairing(host: PairedHost) = persistence(host)

        override fun paired(host: PairedHost) {
            paired = true
        }

        override fun message(payload: JsonObject) {}

        override fun failed(reconnect: Boolean, error: Int) {
            failed = true
            failureCount++
        }
    }

    private fun TestScope.pairUntilReady(
        transport: SecureRemoteTransport,
        socket: Socket,
        expiresAt: Long = System.currentTimeMillis() + 120000,
    ): SecureChannel {
        val secret = Wire.random(32)
        transport.pair(
            Wire.objectOf(
                "relay" to "https://relay.test",
                "routeId" to Wire.random(),
                "pairId" to Wire.random(),
                "secret" to secret,
                "expiresAt" to expiresAt,
            )
        )
        socket.open()
        runCurrent()
        val hello = Wire.parse(socket.sent.single())
        val nonce = Wire.random(32)
        socket.receive(
            Wire.objectOf(
                "v" to 1,
                "type" to "challenge",
                "connectionId" to hello.text("connectionId"),
                "serverNonce" to nonce,
            )
        )
        runCurrent()
        val server =
            SecureChannel(
                secret,
                JsonObject(hello + ("serverNonce" to JsonPrimitive(nonce))),
                false,
            )
        socket.receive(server.seal(Wire.objectOf("type" to "ready", "mode" to "pair")))
        runCurrent()
        assertEquals("pair.request", server.open(Wire.parse(socket.sent.last())).text("type"))
        return server
    }

    private fun TestScope.approve(
        transport: SecureRemoteTransport,
        socket: Socket,
        expiresAt: Long = System.currentTimeMillis() + 120000,
    ): SecureChannel {
        val server = pairUntilReady(transport, socket, expiresAt)
        socket.receive(
            server.seal(
                Wire.objectOf(
                    "type" to "pair.approved",
                    "deviceId" to Wire.random(),
                    "deviceSecret" to Wire.random(32),
                    "hostName" to "Host",
                )
            )
        )
        runCurrent()
        return server
    }

    private fun TestScope.authenticate(capabilities: List<String>): Listener {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
        val deviceKey = Wire.random(32)
        transport.connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), deviceKey, "Host"))
        socket.open()
        runCurrent()
        val hello = Wire.parse(socket.sent.single())
        val nonce = Wire.random(32)
        socket.receive(Wire.objectOf("v" to 1, "type" to "challenge",
            "connectionId" to hello.text("connectionId"), "serverNonce" to nonce))
        runCurrent()
        val server = SecureChannel(deviceKey, JsonObject(hello + ("serverNonce" to JsonPrimitive(nonce))), false)
        socket.receive(server.seal(Wire.objectOf("type" to "ready", "mode" to "device")))
        runCurrent()
        assertEquals("device.authenticate", server.open(Wire.parse(socket.sent.last())).text("type"))
        socket.receive(server.seal(Wire.objectOf("type" to "device.authenticated",
            "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)))))
        runCurrent()
        return listener
    }

    @Test
    fun authenticationAcceptsUpToSixteenCapabilities() = runTest {
        for (count in listOf(8, 9, 16)) {
            val capabilities = (1..count).map { "session.feature.$it" }
            val listener = authenticate(capabilities)
            assertEquals(capabilities.toSet(), listener.capabilities)
            assertFalse(listener.failed)
        }
    }

    @Test
    fun authenticationRejectsSeventeenCapabilities() = runTest {
        val listener = authenticate((1..17).map { "session.feature.$it" })
        assertNull(listener.capabilities)
        assertTrue(listener.failed)
    }

    @Test
    fun authenticationRejectsOversizedCapabilityName() = runTest {
        val listener = authenticate(listOf("x".repeat(65)))
        assertNull(listener.capabilities)
        assertTrue(listener.failed)
    }

    @Test
    fun pairingAcknowledgementWaitsForDurableStorage() = runTest {
        val socket = Socket()
        val stored = CompletableDeferred<Unit>()
        var durable = false
        val listener = Listener {
            stored.await()
            durable = true
        }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        val server = approve(transport, socket)
        assertEquals(2, socket.sent.size)
        assertFalse(listener.paired)
        stored.complete(Unit)
        runCurrent()
        assertTrue(durable)
        assertEquals("pair.ack", server.open(Wire.parse(socket.sent.last())).text("type"))
        assertTrue(listener.paired)
    }

    @Test
    fun failedPairingStorageNeverAcknowledges() = runTest {
        val socket = Socket()
        val listener = Listener { error("Disk unavailable") }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        approve(transport, socket)
        assertEquals(2, socket.sent.size)
        assertTrue(listener.failed)
        assertFalse(listener.paired)
    }

    @Test
    fun changedTransportGenerationCannotAcknowledgeAfterPersistence() = runTest {
        val socket = Socket()
        val stored = CompletableDeferred<Unit>()
        val listener = Listener { stored.await() }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        approve(transport, socket)
        transport.close()
        stored.complete(Unit)
        runCurrent()
        assertEquals(2, socket.sent.size)
        assertFalse(listener.paired)
    }

    @Test
    fun hostReadyCanAdvancePairingWithoutDeviceClockExpiryCheck() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }

        approve(transport, socket, expiresAt = 1)

        assertTrue(listener.paired)
        assertFalse(listener.failed)
    }

    @Test
    fun pairingAttemptTimesOutAfter120Seconds() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        pairUntilReady(transport, socket, expiresAt = 1)

        advanceTimeBy(119999)
        runCurrent()
        assertFalse(listener.failed)
        assertFalse(socket.cancelled)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(listener.failed)
        assertTrue(socket.cancelled)
    }

    @Test
    fun pairingWithoutHostReadyFailsAfter20Seconds() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        transport.pair(
            Wire.objectOf(
                "relay" to "https://relay.test",
                "routeId" to Wire.random(),
                "pairId" to Wire.random(),
                "secret" to Wire.random(32),
                "expiresAt" to 1,
            )
        )

        advanceTimeBy(19999)
        runCurrent()
        assertFalse(listener.failed)
        assertFalse(socket.cancelled)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(listener.failed)
        assertTrue(socket.cancelled)

        socket.open()
        runCurrent()
        assertTrue(socket.sent.isEmpty())

        advanceTimeBy(100000)
        runCurrent()
        assertEquals(1, listener.failureCount)
    }
}
