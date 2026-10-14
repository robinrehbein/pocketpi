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

        override fun message(payload: JsonObject) {
            events += "message"
            messages += payload
            messageThreads += Thread.currentThread()
        }

        var brokenCount = 0

        override fun broken() {
            brokenCount++
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
        // Decodes on the test's scheduler, so runCurrent() also runs the decoding.
        decoder: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        created: (SecureRemoteTransport) -> Unit = {},
    ): Pair<Listener, SecureChannel> {
        val transport = SecureRemoteTransport(backgroundScope, socket, decoder = decoder) { "Test device" }
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

    /** A decoder that runs nothing until the test says so, to look between receipt and decoding. */
    private class HeldDecoder : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(queued) { queued.addLast(block) }
        }

        fun runAll() {
            while (true) (synchronized(queued) { queued.removeFirstOrNull() } ?: return).run()
        }
    }

    @Test
    fun activeFramesDecodeOffTheScopeAndArriveInOrder() = runTest {
        val socket = Socket()
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        val decoderThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val random = java.util.Random(7)
        // One thread, as Dispatchers.Default.limitedParallelism(1), with a random pause per frame.
        val decoder =
            object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    val pause = synchronized(random) { random.nextInt(3).toLong() }
                    executor.execute {
                        Thread.sleep(pause)
                        decoderThread.set(Thread.currentThread())
                        decoded.incrementAndGet()
                        block.run()
                    }
                }
            }
        try {
            val (listener, server) = authenticated(socket = socket, decoder = decoder)
            // The handshake never went through the decoder.
            assertEquals(0, decoded.get())
            val count = 200
            repeat(count) { socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to it))) }
            val deadline = System.currentTimeMillis() + 10_000
            while (listener.messages.size < count && System.currentTimeMillis() < deadline) {
                runCurrent()
                Thread.sleep(1)
            }

            assertEquals((0 until count).toList(), listener.messages.map { it.long("n").toInt() })
            assertEquals(count, decoded.get())
            // Decoded on the decoder's thread, delivered on the scope's (the test's) thread.
            assertNotEquals(Thread.currentThread(), decoderThread.get())
            assertEquals(setOf(Thread.currentThread()), listener.messageThreads)
            assertFalse(listener.failed)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun inboundIsReportedAtReceiptBeforeTheFrameIsDecoded() = runTest {
        val socket = Socket()
        val decoder = HeldDecoder()
        val (listener, server) = authenticated(socket = socket, decoder = decoder)
        // Every handshake frame ran on the scope, none on the decoder.
        assertTrue(decoder.queued.isEmpty())
        assertEquals(HandshakePhase.ACTIVE, listener.phases.last())

        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 1)))
        runCurrent()
        assertEquals(listOf("inbound"), listener.events)
        assertEquals(1, decoder.queued.size)

        decoder.runAll()
        runCurrent()
        assertEquals(listOf("inbound", "message"), listener.events)
        assertEquals(1L, listener.messages.single().long("n"))
    }

    @Test
    fun aCloseWaitsForTheFramesStillBeingDecoded() = runTest {
        val socket = Socket()
        val decoder = HeldDecoder()
        val (listener, server) = authenticated(socket = socket, decoder = decoder)
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 1)))
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 2)))
        runCurrent()
        socket.incoming.onClosed(socket, 1012, "restart")
        runCurrent()
        assertFalse(listener.failed)

        decoder.runAll()
        runCurrent()
        assertEquals(listOf("inbound", "inbound", "message", "message", "failed"), listener.events)
        assertEquals(listOf(true), listener.reconnects)
    }

    private fun caughtEntries() = Diagnostics.report().entries.filter { it.kind == Diagnostics.Kind.CAUGHT }

    @Test
    fun anUnreadableActiveFrameDropsTheFramesQueuedBehindIt() = runTest {
        val socket = Socket()
        val decoder = HeldDecoder()
        val (listener, server) = authenticated(socket = socket, decoder = decoder)
        val broken = server.seal(Wire.objectOf("type" to "event", "n" to 1))
        socket.receive(JsonObject(broken + ("ciphertext" to JsonPrimitive(Wire.random(48)))))
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 2)))
        socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to 3)))
        runCurrent()
        Diagnostics.clear()

        decoder.runAll()
        runCurrent()
        assertEquals(listOf("inbound", "inbound", "inbound", "failed"), listener.events)
        assertEquals(1, listener.brokenCount)
        assertTrue(listener.messages.isEmpty())
        // The unreadable frame is recorded once; the frames behind it add nothing.
        assertEquals(listOf("transport.message"), caughtEntries().map { it.label })
        Diagnostics.clear()
    }

    @Test
    fun aLocalCloseDropsQueuedFramesWithoutRecordingThem() = runTest {
        val socket = Socket()
        val decoder = HeldDecoder()
        var transport: SecureRemoteTransport? = null
        val (listener, server) = authenticated(socket = socket, decoder = decoder, created = { transport = it })
        repeat(3) { socket.receive(server.seal(Wire.objectOf("type" to "event", "n" to it))) }
        runCurrent()
        Diagnostics.clear()

        // A host switch or a move to the background closes while frames wait for the decoder.
        checkNotNull(transport).close()
        decoder.runAll()
        runCurrent()
        assertEquals(listOf("inbound", "inbound", "inbound"), listener.events)
        assertEquals(0, listener.brokenCount)
        assertFalse(listener.failed)
        assertTrue(caughtEntries().isEmpty())
        Diagnostics.clear()
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
    fun stalledHandshakeRecordsTheSilentPhaseAsItsCause() = runTest {
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
            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS)
            runCurrent()

            assertEquals(listOf(HandshakePhase.OPENING, HandshakePhase.CHALLENGE), listener.phases)
            val connect = Diagnostics.report().connects.single()
            assertEquals("timeout.challenge", connect.outcome)
            assertEquals(HANDSHAKE_TIMEOUT_MILLIS, connect.durationMs)
            assertEquals(
                listOf(HandshakePhase.OPENING to 300L, HandshakePhase.CHALLENGE to HANDSHAKE_TIMEOUT_MILLIS - 300),
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
