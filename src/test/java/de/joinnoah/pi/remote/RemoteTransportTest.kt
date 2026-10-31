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
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sendThreads = java.util.Collections.synchronizedSet(mutableSetOf<Thread>())
        var cancelled = false

        /** False once OkHttp would refuse frames: the socket is closing or failed. */
        @Volatile var accepting = true

        /** Whether [close] answers at once with `onClosed`, as when the relay echoes the close. */
        var echoClose = true

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requestValue = request
            incoming = listener
            return this
        }

        override fun request() = requestValue

        override fun queueSize() = 0L

        override fun send(text: String): Boolean {
            if (!accepting) return false
            sendThreads += Thread.currentThread()
            sent += text
            return true
        }

        override fun send(bytes: ByteString) = false

        override fun close(code: Int, reason: String?): Boolean {
            accepting = false
            if (echoClose) incoming.onClosed(this, code, reason.orEmpty())
            return true
        }

        override fun cancel() {
            cancelled = true
        }

        fun open(extensions: String? = null) {
            incoming.onOpen(
                this,
                Response.Builder()
                    .request(requestValue)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .apply { if (extensions != null) header("Sec-WebSocket-Extensions", extensions) }
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
        val phases = mutableListOf<HandshakePhase>()

        override fun progress(phase: HandshakePhase) {
            phases += phase
        }

        override fun ready(capabilities: Set<String>) {
            this.capabilities = capabilities
        }

        override fun approval() {}

        override suspend fun persistPairing(host: PairedHost) = persistence(host)

        override fun paired(host: PairedHost) {
            paired = true
        }

        /** What reached the listener once the connection was active, in order. */
        val events = mutableListOf<String>()
        val messages = mutableListOf<JsonObject>()
        val messageThreads = mutableSetOf<Thread>()

        override fun inbound() {
            events += "inbound"
        }

        /** Thrown from [message] once set, as a listener with a bug would. */
        var messageFailure: Exception? = null

        override fun message(payload: JsonObject) {
            messageFailure?.let { throw it }
            events += "message"
            messages += payload
            messageThreads += Thread.currentThread()
        }

        var brokenCount = 0

        override fun broken() {
            brokenCount++
        }

        val closedCodes = mutableListOf<Int>()

        override fun closed(code: Int) {
            closedCodes += code
        }

        val reconnects = mutableListOf<Boolean>()
        val errors = mutableListOf<Int>()

        override fun failed(reconnect: Boolean, error: Int) {
            events += "failed"
            errors += error
            failed = true
            failureCount++
            reconnects += reconnect
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

    private fun TestScope.authenticate(capabilities: List<String>, socket: Socket = Socket()): Listener =
        authenticated(capabilities, socket).first

    /** An established device connection: its listener and the Mac's end of the channel. */
    private fun TestScope.authenticated(
        capabilities: List<String> = emptyList(),
        socket: Socket = Socket(),
        listener: Listener = Listener { },
        // Seals on the test's scheduler, so runCurrent() also sends.
        sender: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        created: (SecureRemoteTransport) -> Unit = {},
    ): Pair<Listener, SecureChannel> {
        val transport = SecureRemoteTransport(backgroundScope, socket, sender = sender) { "Test device" }
            .also { it.listener = listener }
            .also(created)
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
        return listener to server
    }

    private fun TestScope.authenticateReplying(reply: JsonObject): Listener {
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
        socket.receive(server.seal(reply))
        runCurrent()
        return listener
    }

    /** A dispatcher that runs nothing until the test says so, to look between a send and its seal. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(queued) { queued.addLast(block) }
        }

        fun runAll() {
            while (true) (synchronized(queued) { queued.removeFirstOrNull() } ?: return).run()
        }
    }

    private fun decodedFrames() = Diagnostics.report().frames?.count ?: 0

    private fun caughtEntries() = Diagnostics.report().entries.filter { it.kind == Diagnostics.Kind.CAUGHT }

    @Test
    fun framesDecodeOnTheReaderThreadAndReachTheScopeInOrder() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        val count = 200
        val frames = (0 until count).map { server.seal(Wire.objectOf("type" to "event", "n" to it)).toString() }
        Diagnostics.clear()
        try {
            // OkHttp's reader thread for this socket.
            val reader = Thread { frames.forEach { socket.incoming.onMessage(socket, it) } }
            reader.start()
            reader.join(10_000)

            // Every frame was decrypted and parsed on the reader thread; none reached the listener yet.
            assertEquals(count, decodedFrames())
            assertTrue(listener.events.isEmpty())

            runCurrent()
            assertEquals((0 until count).toList(), listener.messages.map { it.long("n").toInt() })
            assertEquals(List(count) { listOf("inbound", "message") }.flatten(), listener.events)
            // Delivered on the scope's (the test's) thread.
            assertEquals(setOf(Thread.currentThread()), listener.messageThreads)
            assertFalse(listener.failed)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun inboundIsHandedOverBeforeTheFrameIsDecoded() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        assertEquals(HandshakePhase.ACTIVE, listener.phases.last())
        // The handshake frames themselves never count as inbound.
        assertTrue(listener.events.isEmpty())

        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 1)))
        runCurrent()
        assertEquals(listOf("inbound", "message"), listener.events)

        // A frame that cannot be decoded still reported its receipt first.
        val broken = server.seal(Wire.objectOf("type" to "event", "n" to 2))
        socket.receive(JsonObject(broken + ("ciphertext" to JsonPrimitive(Wire.random(48)))))
        runCurrent()
        assertEquals(listOf("inbound", "message", "inbound", "failed"), listener.events)
        Diagnostics.clear()
    }

    @Test
    fun aCloseAfterFramesDeliversTheFramesFirst() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 1)))
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 2)))
        socket.incoming.onClosed(socket, 1012, "restart")
        assertFalse(listener.failed)

        runCurrent()
        assertEquals(listOf("inbound", "message", "inbound", "message", "failed"), listener.events)
        assertEquals(listOf(1012), listener.closedCodes)
        assertEquals(listOf(true), listener.reconnects)
        Diagnostics.clear()
    }

    @Test
    fun aFailureFromAnotherThreadIsHandledAfterEveryFrameAlreadyHandedOver() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        val count = 400
        // Large enough that the reader is usually in the middle of one when the failure arrives.
        val frames =
            (0 until count).map {
                server.seal(Wire.objectOf("type" to "event", "n" to it, "pad" to "x".repeat(8_000))).toString()
            }
        val handedOver = java.util.concurrent.atomic.AtomicInteger()
        val handedOverAtFailure = java.util.concurrent.atomic.AtomicInteger(-1)
        val reader = Thread {
            for (frame in frames) {
                socket.incoming.onMessage(socket, frame)
                handedOver.incrementAndGet()
            }
        }
        // OkHttp's writer queue fails the socket on a missed pong while the reader still decodes.
        val writer = Thread {
            while (handedOver.get() < count / 4) Thread.yield()
            handedOverAtFailure.set(handedOver.get())
            socket.incoming.onFailure(socket, java.net.SocketTimeoutException("ping"), null)
        }
        reader.start()
        writer.start()
        val deadline = System.currentTimeMillis() + 20_000
        while ((reader.isAlive || writer.isAlive) && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(1)
        }
        runCurrent()

        val delivered = listener.messages.map { it.long("n").toInt() }
        // Every frame handed over before the failure arrived, in order, and only then the failure.
        assertTrue(handedOverAtFailure.get() >= count / 4)
        assertTrue("${delivered.size} < ${handedOverAtFailure.get()}", delivered.size >= handedOverAtFailure.get())
        assertEquals((0 until delivered.size).toList(), delivered)
        assertEquals("failed", listener.events.last())
        assertEquals(1, listener.failureCount)
        assertEquals(0, listener.brokenCount)
        Diagnostics.clear()
    }

    @Test
    fun anUnreadableFrameDropsTheFramesBehindItUnparsed() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        val broken = server.seal(Wire.objectOf("type" to "event", "n" to 1))
        val behind = (2..3).map { server.seal(Wire.objectOf("type" to "event", "n" to it)) }
        Diagnostics.clear()
        socket.receive(JsonObject(broken + ("ciphertext" to JsonPrimitive(Wire.random(48)))))
        behind.forEach(socket::receive)
        assertEquals(0, decodedFrames())

        runCurrent()
        assertEquals(listOf("inbound", "failed"), listener.events)
        assertEquals(1, listener.brokenCount)
        assertTrue(listener.messages.isEmpty())
        // The unreadable frame is recorded once; the frames behind it add nothing.
        assertEquals(listOf("transport.message"), caughtEntries().map { it.label })
        Diagnostics.clear()
    }

    @Test
    fun aLocalCloseDropsDecodedFramesAndParsesNoMore() = runTest {
        val socket = Socket()
        var transport: SecureRemoteTransport? = null
        val (listener, server) = authenticated(socket = socket, created = { transport = it })
        repeat(3) { socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to it))) }
        val late = server.seal(Wire.objectOf("type" to "event", "n" to 3))
        Diagnostics.clear()

        // A host switch or a move to the background closes while decoded frames wait for the scope.
        checkNotNull(transport).close()
        // A frame the reader thread gets after the close is not parsed, valid or not.
        socket.receive(late)
        socket.incoming.onMessage(socket, "not json")
        runCurrent()
        assertEquals(0, decodedFrames())
        assertTrue(listener.events.isEmpty())
        assertEquals(0, listener.brokenCount)
        assertFalse(listener.failed)
        assertTrue(caughtEntries().isEmpty())
        Diagnostics.clear()
    }

    @Test
    fun payloadsAreSealedAndSentOnTheSenderInCallOrder() = runTest {
        val socket = Socket()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            var transport: SecureRemoteTransport? = null
            val (listener, server) =
                authenticated(socket = socket, sender = executor.asCoroutineDispatcher(), created = { transport = it })
            val before = socket.sent.size
            val count = 100
            repeat(count) { checkNotNull(transport).send(Wire.objectOf("type" to "steer", "n" to it)) }
            val deadline = System.currentTimeMillis() + 10_000
            while (socket.sent.size < before + count && System.currentTimeMillis() < deadline) Thread.sleep(1)

            val payloads = socket.sent.drop(before).map { server.open(Wire.parse(it)) }
            assertEquals((0 until count).toList(), payloads.map { it.long("n").toInt() })
            // The handshake sealed its own frames on the scope; every payload left from the sender.
            val senderThreads = socket.sendThreads - Thread.currentThread()
            assertEquals(1, senderThreads.size)
            assertFalse(listener.failed)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun sendReturnsBeforeSealingAndQueuedPayloadsDieWithALocalClose() = runTest {
        val socket = Socket()
        val sender = HeldDispatcher()
        var transport: SecureRemoteTransport? = null
        val (listener, server) = authenticated(socket = socket, sender = sender, created = { transport = it })
        sender.runAll()
        val before = socket.sent.size

        checkNotNull(transport).send(Wire.objectOf("type" to "steer", "n" to 1))
        assertEquals(before, socket.sent.size)
        sender.runAll()
        runCurrent()
        assertEquals(1L, server.open(Wire.parse(socket.sent.last())).long("n"))

        checkNotNull(transport).send(Wire.objectOf("type" to "steer", "n" to 2))
        checkNotNull(transport).close()
        sender.runAll()
        runCurrent()
        assertEquals(before + 1, socket.sent.size)
        assertFalse(listener.failed)
        assertEquals(0, listener.brokenCount)
    }

    @Test
    fun sendBeforeTheHandshakeEndsIsRefused() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }.also { it.listener = listener }
        transport.connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
        socket.open()
        runCurrent()
        assertThrows(IllegalStateException::class.java) { transport.send(Wire.objectOf("type" to "steer")) }
    }

    @Test
    fun aSealFailureBreaksTheConnection() = runTest {
        val socket = Socket()
        var transport: SecureRemoteTransport? = null
        val (listener, _) = authenticated(socket = socket, created = { transport = it })
        val before = socket.sent.size
        Diagnostics.clear()

        checkNotNull(transport).send(Wire.objectOf("type" to "steer", "text" to "x".repeat(Wire.MAX_PLAINTEXT)))
        runCurrent()
        assertEquals(before, socket.sent.size)
        assertEquals(1, listener.brokenCount)
        assertEquals(listOf(true), listener.reconnects)
        assertEquals(listOf("transport.send"), caughtEntries().map { it.label })
        assertTrue(socket.cancelled)
        Diagnostics.clear()
    }

    @Test
    fun aRefusedSendIsALostConnectionNotABrokenOne() = runTest {
        Diagnostics.clear()
        try {
            val socket = Socket()
            var transport: SecureRemoteTransport? = null
            val (listener, _) = authenticated(socket = socket, created = { transport = it })
            socket.accepting = false

            checkNotNull(transport).send(Wire.objectOf("type" to "steer"))
            runCurrent()
            assertEquals(listOf(true), listener.reconnects)
            assertEquals(0, listener.brokenCount)
            assertTrue(listener.closedCodes.isEmpty())
            assertEquals(
                listOf("failure.send"),
                Diagnostics.report().entries.filter { it.kind == Diagnostics.Kind.FAILURE }.map { it.outcome },
            )
            assertTrue(caughtEntries().isEmpty())
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aSendRefusedWhileTheRelayClosesReportsTheRelaysCode() = runTest {
        val socket = Socket()
        var transport: SecureRemoteTransport? = null
        val (listener, _) = authenticated(socket = socket, created = { transport = it })
        // The relay's close frame arrived; OkHttp answers it and refuses frames until onClosed.
        socket.echoClose = false
        socket.incoming.onClosing(socket, 1012, "Relay restart")

        checkNotNull(transport).send(Wire.objectOf("type" to "steer"))
        runCurrent()
        assertEquals(listOf(1012), listener.closedCodes)
        assertEquals(listOf(true), listener.reconnects)
        assertEquals(0, listener.brokenCount)

        // OkHttp's own onClosed afterwards adds nothing.
        socket.incoming.onClosed(socket, 1012, "Relay restart")
        runCurrent()
        assertEquals(1, listener.failureCount)
        Diagnostics.clear()
    }

    /** A device connection that took the challenge; returns the Mac's end of the channel. */
    private fun TestScope.untilReady(transport: SecureRemoteTransport, socket: Socket): SecureChannel {
        val deviceKey = Wire.random(32)
        transport.connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), deviceKey, "Host"))
        socket.open()
        runCurrent()
        val hello = Wire.parse(socket.sent.single())
        val nonce = Wire.random(32)
        socket.receive(Wire.objectOf("v" to 1, "type" to "challenge",
            "connectionId" to hello.text("connectionId"), "serverNonce" to nonce))
        runCurrent()
        return SecureChannel(deviceKey, JsonObject(hello + ("serverNonce" to JsonPrimitive(nonce))), false)
    }

    @Test
    fun aRefusedHandshakeFrameIsALostConnectionNotABrokenOne() = runTest {
        Diagnostics.clear()
        try {
            val socket = Socket()
            val listener = Listener { }
            val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
            val server = untilReady(transport, socket)
            socket.accepting = false

            socket.receive(server.seal(Wire.objectOf("type" to "ready", "mode" to "device")))
            runCurrent()
            assertEquals(listOf(true), listener.reconnects)
            assertEquals(0, listener.brokenCount)
            assertTrue(listener.closedCodes.isEmpty())
            assertEquals(HandshakePhase.READY, listener.phases.last())
            assertEquals("failure.send", Diagnostics.report().connects.single().outcome)
            assertTrue(caughtEntries().isEmpty())
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aHandshakeFrameRefusedWhileTheRelayClosesReportsTheRelaysCode() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
        val server = untilReady(transport, socket)
        // The relay forwards ready, then restarts; OkHttp refuses device.authenticate.
        socket.echoClose = false
        socket.receive(server.seal(Wire.objectOf("type" to "ready", "mode" to "device")))
        socket.incoming.onClosing(socket, 1012, "Relay restart")
        runCurrent()
        assertEquals(listOf(1012), listener.closedCodes)
        assertEquals(listOf(true), listener.reconnects)
        assertEquals(0, listener.brokenCount)

        socket.incoming.onClosed(socket, 1012, "Relay restart")
        runCurrent()
        assertEquals(1, listener.failureCount)
        Diagnostics.clear()
    }

    @Test
    fun aRefusedPairingAcknowledgementIsNotAStorageError() = runTest {
        val socket = Socket()
        val stored = CompletableDeferred<Unit>()
        val listener = Listener { stored.await() }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
        approve(transport, socket)
        socket.echoClose = false
        socket.incoming.onClosing(socket, 1001, "Going away")
        stored.complete(Unit)
        runCurrent()
        assertEquals(2, socket.sent.size)
        assertFalse(listener.paired)
        assertEquals(0, listener.brokenCount)
        assertEquals(listOf(1001), listener.closedCodes)
        assertEquals(listOf(R.string.remote_connection_error), listener.errors)
        assertEquals(listOf(false), listener.reconnects)
        Diagnostics.clear()
    }

    @Test
    fun aListenerThatThrowsBreaksTheConnectionInsteadOfStallingIt() = runTest {
        Diagnostics.clear()
        try {
            val socket = Socket()
            val (listener, server) = authenticated(socket = socket)
            listener.messageFailure = IllegalStateException("listener bug")
            socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 1)))
            socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 2)))
            runCurrent()
            // The exception never reached the scope (runTest would fail); the connection broke once.
            assertEquals(listOf("inbound", "failed"), listener.events)
            assertEquals(1, listener.brokenCount)
            assertEquals(listOf(true), listener.reconnects)
            assertEquals(listOf("transport.message"), caughtEntries().map { it.label })
            assertTrue(socket.cancelled)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun noChallengeWithinTenSecondsFailsAsRetryable() = runTest {
        val socket = Socket()
        val listener = Listener { }
        SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
            .connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
        socket.open()
        runCurrent()
        advanceTimeBy(CHALLENGE_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertFalse(listener.failed)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true), listener.reconnects)
        assertEquals(listOf(R.string.remote_connection_error), listener.errors)
        assertTrue(socket.cancelled)
        Diagnostics.clear()
    }

    @Test
    fun aChallengeInTimeLeavesTheRestToTheHandshakeTimeout() = runTest {
        Diagnostics.clear()
        Diagnostics.clock = { testScheduler.currentTime }
        try {
            val socket = Socket()
            val listener = Listener { }
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
                .connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
            socket.open()
            runCurrent()
            advanceTimeBy(CHALLENGE_TIMEOUT_MILLIS - 1)
            val hello = Wire.parse(socket.sent.single())
            socket.receive(
                Wire.objectOf(
                    "v" to 1,
                    "type" to "challenge",
                    "connectionId" to hello.text("connectionId"),
                    "serverNonce" to Wire.random(32),
                )
            )
            runCurrent()
            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS - CHALLENGE_TIMEOUT_MILLIS)
            runCurrent()
            assertFalse(listener.failed)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(true), listener.reconnects)
            assertEquals("timeout.ready", Diagnostics.report().connects.single().outcome)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aChallengeTakenByTheReaderBeforeTheDeadlineRunsIsInTime() = runTest {
        Diagnostics.clear()
        Diagnostics.clock = { testScheduler.currentTime }
        try {
            val socket = Socket()
            val listener = Listener { }
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
                .connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
            socket.open()
            runCurrent()
            // The clock reaches the deadline, but its timer has not run yet: a busy main thread.
            advanceTimeBy(CHALLENGE_TIMEOUT_MILLIS)
            val hello = Wire.parse(socket.sent.single())
            // The reader takes the challenge; its lane task queues behind the due timer.
            socket.receive(
                Wire.objectOf(
                    "v" to 1,
                    "type" to "challenge",
                    "connectionId" to hello.text("connectionId"),
                    "serverNonce" to Wire.random(32),
                )
            )
            runCurrent()
            assertFalse(listener.failed)
            assertEquals(HandshakePhase.READY, listener.phases.last())

            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS - CHALLENGE_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(listOf(true), listener.reconnects)
            assertEquals("timeout.ready", Diagnostics.report().connects.single().outcome)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun theNegotiatedExtensionsAreRecorded() = runTest {
        Diagnostics.clear()
        try {
            for (header in listOf("permessage-deflate", "permessage-deflate; server_no_context_takeover, x weird", null)) {
                val socket = Socket()
                val listener = Listener { }
                SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                    .also { it.listener = listener }
                    .connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
                socket.open(header)
                runCurrent()
            }

            assertEquals(
                // Newest first.
                listOf(emptyList(), listOf("permessage-deflate", "redacted"), listOf("permessage-deflate")),
                Diagnostics.report().connects.map { it.extensions },
            )
            assertTrue(Diagnostics.export().contains("ext=permessage-deflate"))
            assertFalse(Diagnostics.export().contains("server_no_context_takeover"))
        } finally {
            Diagnostics.clear()
        }
    }

    private val deviceAccess =
        Wire.json.parseToJsonElement(javaClass.getResource("/device-access-v1.json")!!.readText()).jsonObject

    @Test
    fun deniedDeviceStopsWithoutReconnectForEveryValidFixtureEntry() = runTest {
        val expected =
            mapOf("revoked" to R.string.remote_device_revoked, "paused" to R.string.remote_device_paused)
        val entries = deviceAccess.getValue("wireValid").jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        for (entry in entries) {
            val payload = entry.getValue("payload").jsonObject
            val listener = authenticateReplying(payload)
            assertEquals(entry.text("name"), listOf(false), listener.reconnects)
            assertEquals(entry.text("name"), listOf(expected.getValue(payload.text("reason"))), listener.errors)
            assertNull(listener.capabilities)
        }
    }

    @Test
    fun malformedDeniedDeviceStaysTheGenericConnectionError() = runTest {
        val entries = deviceAccess.getValue("wireInvalid").jsonArray.map { it.jsonObject }
        assertTrue(entries.isNotEmpty())
        for (entry in entries) {
            val listener = authenticateReplying(entry.getValue("payload").jsonObject)
            assertEquals(entry.text("name"), listOf(R.string.remote_connection_error), listener.errors)
            assertEquals(entry.text("name"), listOf(false), listener.reconnects)
            assertNull(listener.capabilities)
        }
    }

    @Test
    fun stalledDeviceHandshakeTimesOutAsRetryable() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
        transport.connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
        socket.open()
        advanceTimeBy(20001)
        runCurrent()
        assertEquals(listOf(true), listener.reconnects)
    }

    @Test
    fun deviceHandshakeReportsEachPhaseInOrder() = runTest {
        val listener = authenticate(listOf("session.feature"))

        assertEquals(
            listOf(
                HandshakePhase.OPENING,
                HandshakePhase.CHALLENGE,
                HandshakePhase.READY,
                HandshakePhase.AUTHENTICATING,
                HandshakePhase.ACTIVE,
            ),
            listener.phases,
        )
    }

    @Test
    fun pairingReportsApprovalAndPersistencePhases() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }

        approve(transport, socket)

        assertEquals(
            listOf(
                HandshakePhase.OPENING,
                HandshakePhase.CHALLENGE,
                HandshakePhase.READY,
                HandshakePhase.APPROVAL,
                HandshakePhase.PERSISTING,
                HandshakePhase.PAIRED,
            ),
            listener.phases,
        )
    }

    @Test
    fun aMissingChallengeRecordsTheSilentPhaseAsItsCause() = runTest {
        Diagnostics.clear()
        Diagnostics.clock = { testScheduler.currentTime }
        try {
            val socket = Socket()
            val listener = Listener { }
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
                .connect(PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host"))
            advanceTimeBy(300)
            socket.open()
            runCurrent()
            advanceTimeBy(CHALLENGE_TIMEOUT_MILLIS)
            runCurrent()

            assertEquals(listOf(HandshakePhase.OPENING, HandshakePhase.CHALLENGE), listener.phases)
            val connect = Diagnostics.report().connects.single()
            assertEquals("timeout.challenge", connect.outcome)
            assertEquals(300 + CHALLENGE_TIMEOUT_MILLIS, connect.durationMs)
            assertEquals(
                listOf(HandshakePhase.OPENING to 300L, HandshakePhase.CHALLENGE to CHALLENGE_TIMEOUT_MILLIS),
                connect.breakdown(),
            )
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun droppedEstablishedConnectionKeepsItsCloseCodeAndExceptionClass() = runTest {
        Diagnostics.clear()
        try {
            val failing = Socket()
            authenticate(emptyList(), failing)
            failing.incoming.onFailure(failing, java.net.SocketException("reset by 10.0.0.2"), null)
            runCurrent()
            val closing = Socket()
            authenticate(emptyList(), closing)
            closing.incoming.onClosed(closing, 1012, "restart")
            runCurrent()

            val report = Diagnostics.report()
            assertEquals(listOf("ok", "ok"), report.connects.map { it.outcome })
            assertEquals(
                listOf("closed.1012", "failure.SocketException"),
                report.entries.filter { it.kind == Diagnostics.Kind.FAILURE }.map { it.outcome },
            )
            assertTrue(report.entries.none { "10.0.0.2" in it.outcome || "restart" in it.outcome })
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun waitingForTheMacShowsFiveSecondsAfterTheRelayAnswersAndClearsOnTheChallenge() = runTest {
        val deviceKey = Wire.random(32)
        val host = PairedHost(Wire.random(), "https://relay.test", Wire.random(), deviceKey, "Host")
        val socket = Socket()
        val repository =
            DefaultRemoteRepository(
                object : PairingStorage {
                    override fun load() = listOf(host)

                    override fun save(hosts: List<PairedHost>) {}
                },
                object : DraftStorage {
                    override fun load() = emptyMap<DraftKey, StoredDraft>()

                    override fun save(drafts: Map<DraftKey, StoredDraft>) {}
                },
                SecureRemoteTransport(backgroundScope, socket) { "Test device" },
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                now = { testScheduler.currentTime },
            )
        backgroundScope.launch { repository.activate(RemoteSelection(host.routeId)) }
        runCurrent()
        assertEquals(R.string.remote_connecting, repository.state.value.connection)

        // Before the relay answers, a slow network is not the Mac's fault.
        advanceTimeBy(HOST_WAIT_HINT_MILLIS + 1)
        assertEquals(R.string.remote_connecting, repository.state.value.connection)

        socket.open()
        runCurrent()
        advanceTimeBy(HOST_WAIT_HINT_MILLIS - 1)
        runCurrent()
        assertEquals(R.string.remote_connecting, repository.state.value.connection)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(R.string.remote_waiting_for_mac, repository.state.value.connection)

        val hello = Wire.parse(socket.sent.single())
        socket.receive(
            Wire.objectOf(
                "v" to 1,
                "type" to "challenge",
                "connectionId" to hello.text("connectionId"),
                "serverNonce" to Wire.random(32),
            )
        )
        runCurrent()
        assertEquals(R.string.remote_connecting, repository.state.value.connection)
        // Still inside the transport's 20 s handshake window.
        advanceTimeBy(HOST_WAIT_HINT_MILLIS + 1)
        runCurrent()
        assertEquals(R.string.remote_connecting, repository.state.value.connection)
    }

    @Test
    fun anotherActivationOnTheSameHostKeepsWaitingForTheMac() = runTest {
        val host = PairedHost(Wire.random(), "https://relay.test", Wire.random(), Wire.random(32), "Host")
        val socket = Socket()
        val repository =
            DefaultRemoteRepository(
                object : PairingStorage {
                    override fun load() = listOf(host)

                    override fun save(hosts: List<PairedHost>) {}
                },
                object : DraftStorage {
                    override fun load() = emptyMap<DraftKey, StoredDraft>()

                    override fun save(drafts: Map<DraftKey, StoredDraft>) {}
                },
                SecureRemoteTransport(backgroundScope, socket) { "Test device" },
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                now = { testScheduler.currentTime },
            )
        backgroundScope.launch { repository.activate(RemoteSelection(host.routeId)) }
        runCurrent()
        socket.open()
        runCurrent()
        advanceTimeBy(HOST_WAIT_HINT_MILLIS + 1)
        runCurrent()
        assertEquals(R.string.remote_waiting_for_mac, repository.state.value.connection)

        // Opening a session joins the attempt in flight, which reports no new phase.
        backgroundScope.launch {
            repository.activate(RemoteSelection(host.routeId, "project", "session"), ActivationMode.USER_OPEN)
        }
        runCurrent()
        assertEquals(1, socket.sent.size)
        assertEquals(R.string.remote_waiting_for_mac, repository.state.value.connection)
    }

    @Test
    fun stalledPairingHandshakeTimesOutWithoutRetry() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
            .also { it.listener = listener }
        transport.pair(
            Wire.objectOf(
                "relay" to "https://relay.test",
                "routeId" to Wire.random(),
                "pairId" to Wire.random(),
                "secret" to Wire.random(32),
                "expiresAt" to System.currentTimeMillis() + 120000,
            )
        )
        socket.open()
        advanceTimeBy(20001)
        runCurrent()
        assertEquals(listOf(false), listener.reconnects)
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
        assertEquals(listOf(R.string.remote_storage_error), listener.errors)
        assertEquals(listOf(false), listener.reconnects)
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
        // An expired code says so instead of blaming the network, and a pairing never retries.
        assertEquals(listOf(R.string.remote_pairing_timeout), listener.errors)
        assertEquals(listOf(false), listener.reconnects)
    }

    @Test
    fun undecryptableFrameOnAnEstablishedConnectionReconnects() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        val frame = server.seal(Wire.objectOf("type" to "event", "kind" to "future.kind"))
        socket.receive(JsonObject(frame + ("ciphertext" to JsonPrimitive(Wire.random(48)))))
        runCurrent()
        assertEquals(listOf(R.string.remote_connection_error), listener.errors)
        assertEquals(listOf(true), listener.reconnects)
        // Reported as broken, so the repository counts it against the backoff.
        assertEquals(1, listener.brokenCount)
        assertTrue(socket.cancelled)
    }

    @Test
    fun replayedFrameOnAnEstablishedConnectionReconnects() = runTest {
        val socket = Socket()
        val (listener, server) = authenticated(socket = socket)
        val frame = server.seal(Wire.objectOf("type" to "event", "kind" to "future.kind"))
        socket.receive(frame)
        runCurrent()
        assertFalse(listener.failed)
        socket.receive(frame)
        runCurrent()
        assertEquals(listOf(true), listener.reconnects)
        assertEquals(1, listener.brokenCount)
    }

    @Test
    fun malformedPairingFrameFailsWithoutRetry() = runTest {
        val socket = Socket()
        val listener = Listener { }
        val transport =
            SecureRemoteTransport(backgroundScope, socket) { "Test device" }
                .also { it.listener = listener }
        pairUntilReady(transport, socket)
        socket.receive(Wire.objectOf("v" to 1, "nonce" to Wire.random(12), "data" to Wire.random(32)))
        runCurrent()
        assertEquals(listOf(R.string.remote_connection_error), listener.errors)
        assertEquals(listOf(false), listener.reconnects)
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
