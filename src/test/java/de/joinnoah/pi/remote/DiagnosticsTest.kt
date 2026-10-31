package de.joinnoah.pi.remote

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {
    private var time = 0L

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() {
        Diagnostics.clear()
        Diagnostics.clock = { time }
    }

    @After
    fun tearDown() {
        Diagnostics.clear()
        ShadowTrace.reset()
    }

    @Test
    fun ringBufferKeepsTheNewest512Entries() {
        repeat(Diagnostics.CAPACITY + 88) {
            time = it.toLong()
            Diagnostics.record(Diagnostics.Kind.RECOVERY, "recover.fresh_foreground", durationMs = it.toLong())
        }

        val entries = Diagnostics.report().entries
        assertEquals(512, entries.size)
        assertEquals(599L, entries.first().durationMs)
        assertEquals(88L, entries.last().durationMs)
    }

    @Test
    fun connectionHistoryKeepsTheLastTwenty() {
        repeat(25) { Diagnostics.connectStarted("foreground.device").finish("ok") }

        assertEquals(20, Diagnostics.report().connects.size)
    }

    @Test
    fun percentilesUseTheNearestRank() {
        val values = (1L..100L).toList()
        assertEquals(50L, Diagnostics.percentile(values, 50))
        assertEquals(95L, Diagnostics.percentile(values, 95))
        assertEquals(7L, Diagnostics.percentile(listOf(7L), 95))
        assertEquals(0L, Diagnostics.percentile(emptyList(), 50))
        assertEquals(20L, Diagnostics.percentile(listOf(10L, 20L, 30L), 50))
        assertEquals(30L, Diagnostics.percentile(listOf(10L, 20L, 30L), 95))
    }

    @Test
    fun requestStatsGroupLatencyAndOutcomesPerType() {
        for (ms in 1L..20L) Diagnostics.request("sessions.list", ms * 10, 64, "ok")
        Diagnostics.request("sessions.list", 30_000, 64, "timeout")
        Diagnostics.request("sessions.list", 15, 64, "error.not_found")
        // Abandoned waits stay out of the latencies and the failures.
        Diagnostics.request("sessions.list", 25_000, 64, "cancelled")
        Diagnostics.request("sessions.list", 9_000, 64, "lost")
        Diagnostics.request("projects.list", 40, 32, "ok")
        Diagnostics.request("projects.list", 35, 32, "retry.v1")

        val stats = Diagnostics.report().requests
        val sessions = stats.first { it.type == "sessions.list" }
        assertEquals(24, sessions.count)
        assertEquals(100L, sessions.p50)
        assertEquals(190L, sessions.p95)
        assertEquals(1, sessions.timeouts)
        assertEquals(2, sessions.failures)
        val projects = stats.first { it.type == "projects.list" }
        assertEquals(40L, projects.p95)
        assertEquals(0, projects.failures)
        assertEquals(listOf("sessions.list", "projects.list"), stats.map { it.type })
    }

    @Test
    fun connectBreakdownTimesEachWaitingPhase() {
        val trace = Diagnostics.connectStarted("foreground.device")
        trace.phase(HandshakePhase.OPENING)
        time = 120
        trace.phase(HandshakePhase.CHALLENGE)
        time = 460
        trace.phase(HandshakePhase.READY)
        time = 540
        trace.phase(HandshakePhase.AUTHENTICATING)
        time = 820
        trace.phase(HandshakePhase.ACTIVE)
        trace.finish("ok")

        val connect = Diagnostics.report().connects.single()
        assertEquals(820L, connect.durationMs)
        assertEquals("ok", connect.outcome)
        assertEquals(
            listOf(
                HandshakePhase.OPENING to 120L,
                HandshakePhase.CHALLENGE to 340L,
                HandshakePhase.READY to 80L,
                HandshakePhase.AUTHENTICATING to 280L,
            ),
            connect.breakdown(),
        )
        assertTrue(Diagnostics.export().contains("foreground.device ok 820ms: opening 120ms, challenge 340ms"))
    }

    @Test
    fun exportNeverHoldsSecretsIdsOrMessageText() = runTest {
        val host =
            PairedHost(
                "Qm7xK2pLw9RtZb4nVc8sJd",
                "https://relay.test",
                "Fz3wT8qYv1NmLk6pXh0aRe",
                "Hk2Lq9Zx7Vb4Nw1Mc8Ty5Rp3Js6Dg0Fa2Ue9Wi4Ko7Q",
                "Robins MacBook",
            )
        val sessionId = "6f1c2e9a-5b7d-4c3e-9a1f-2b8c7d6e5f40"
        val prompt = "Please refactor the payment module"
        val secrets = listOf(host.routeId, host.deviceId, host.secret, sessionId, prompt, "payment", "Robins")

        // Call sites pass code constants; anything else is replaced before it is stored.
        Diagnostics.record(Diagnostics.Kind.REQUEST, host.secret, outcome = host.routeId)
        Diagnostics.request(prompt, 10, 10, sessionId)
        Diagnostics.caught(host.deviceId, IllegalStateException(host.secret + " " + prompt))
        Diagnostics.connectStarted(sessionId).finish(prompt)
        Diagnostics.record(Diagnostics.Kind.RECOVERY, "session.$sessionId", outcome = "error.${host.secret}")
        Diagnostics.record(Diagnostics.Kind.RECOVERY, "route:${host.routeId}")
        // A real handshake with this host, which times out waiting for the Mac.
        connectAndStall(host)

        val report = Diagnostics.export()
        for (secret in secrets) assertFalse(secret, report.contains(secret))
        assertTrue(report.contains("redacted"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("foreground.device timeout.challenge"))
    }

    @Test
    fun roundTripSummaryKeepsTheLast64SamplesAndTheHostsRelayTime() {
        assertNull(Diagnostics.link().rtt)
        for (ms in 1L..100L) Diagnostics.rtt(ms * 10, if (ms == 100L) null else 40)
        Diagnostics.rtt(5, 70)

        val rtt = checkNotNull(Diagnostics.link().rtt)
        assertEquals(64, rtt.count)
        // The ring holds 380..1000 (step 10) and 5: 64 values, nearest rank.
        assertEquals(5L, rtt.last)
        assertEquals(70L, rtt.relayMs)
        assertEquals(680L, rtt.p50)
        assertEquals(970L, rtt.p95)
    }

    @Test
    fun countersAndCloseCodesAreKept() {
        Diagnostics.count(Diagnostics.Counter.RECONNECTS)
        Diagnostics.count(Diagnostics.Counter.RECONNECTS)
        Diagnostics.count(Diagnostics.Counter.PING_FAILURES)
        Diagnostics.closeCode(1012)
        Diagnostics.closeCode(1013)
        Diagnostics.closeCode(1013)
        for (code in 4000..4030) Diagnostics.closeCode(code)

        val link = Diagnostics.link()
        assertEquals(2L, link.count(Diagnostics.Counter.RECONNECTS))
        assertEquals(1L, link.count(Diagnostics.Counter.PING_FAILURES))
        assertEquals(0L, link.count(Diagnostics.Counter.ACKS_SENT))
        assertEquals(1L, link.closeCodes[1012])
        assertEquals(2L, link.flowCloses)
        // Past 15 distinct codes the rest count together under 0.
        assertEquals(Diagnostics.CLOSE_CODES, link.closeCodes.size)
        assertEquals(18L, link.closeCodes[0])
        val export = Diagnostics.export()
        assertTrue(export.contains("reconnects=2 okhttpPingFailures=1"))
        assertTrue(export.contains("flowCloses=2"))
    }

    @Test
    fun onlyOkHttpsPongTimeoutIsAPingFailure() {
        val ping = java.net.SocketTimeoutException("sent ping but didn't receive pong within 20000ms (after 3 successful ping/pongs)")
        assertTrue(Diagnostics.isPingFailure(ping))
        assertFalse(Diagnostics.isPingFailure(java.net.SocketTimeoutException("timeout")))
        assertFalse(Diagnostics.isPingFailure(java.io.IOException("sent ping but didn't receive pong")))
    }

    @Test
    fun anOkHttpPingFailureOfALiveSocketIsCounted() = runTest {
        val host = PairedHost("Qm7xK2pLw9RtZb4nVc8sJd", "https://relay.test", "Fz3wT8qYv1NmLk6pXh0aRe", "Hk2Lq9Zx7Vb4Nw1Mc8Ty5Rp3Js6Dg0Fa2Ue9Wi4Ko7Q", "Mac")
        val socket = Socket()
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
        transport.listener = NoListener
        transport.connect(host)
        socket.open()
        runCurrent()

        socket.incoming.onFailure(
            socket,
            java.net.SocketTimeoutException("sent ping but didn't receive pong within 20000ms (after 0 successful ping/pongs)"),
            null,
        )
        runCurrent()

        assertEquals(1L, Diagnostics.link().count(Diagnostics.Counter.PING_FAILURES))
        assertTrue(Diagnostics.export().contains("failure.SocketTimeoutException"))
    }

    private object NoListener : RemoteTransport.Listener {
        override fun ready(capabilities: Set<String>) {}

        override fun approval() {}

        override suspend fun persistPairing(host: PairedHost) {}

        override fun paired(host: PairedHost) {}

        override fun message(payload: JsonObject) {}

        override fun failed(reconnect: Boolean, error: Int) {}
    }

    private fun store(scope: TestScope, name: String = "diagnostics-v1.json", maxBytes: Int = DiagnosticsStore.MAX_BYTES) =
        DiagnosticsStore(
            File(folder.root, name),
            scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler),
            wallClock = { 1_700_000_000_000 + time },
            maxBytes = maxBytes,
        )

    private fun recordARun() {
        time = 1_000
        val trace = Diagnostics.connectStarted("foreground.device")
        trace.phase(HandshakePhase.OPENING)
        time = 1_200
        trace.extensions("permessage-deflate; client_max_window_bits")
        trace.phase(HandshakePhase.ACTIVE)
        trace.finish("ok")
        time = 2_000
        Diagnostics.record(Diagnostics.Kind.FAILURE, "connection.lost", outcome = "closed.1012")
        Diagnostics.record(Diagnostics.Kind.REQUEST, "sessions.list", 40, 12, "ok")
        Diagnostics.record(Diagnostics.Kind.OFFLINE, "reconnected", 3_500)
        Diagnostics.rtt(80, 25)
        Diagnostics.rtt(120)
        Diagnostics.count(Diagnostics.Counter.RECONNECTS)
        Diagnostics.count(Diagnostics.Counter.RESUMES_REPLAYED)
        Diagnostics.closeCode(1012)
        time = 3_000
    }

    @Test
    fun theStoreSavesASummaryThatTheNextRunLoadsWithWallClockTimes() = runTest {
        recordARun()
        val first = store(this)
        first.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(File(folder.root, "diagnostics-v1.json").isFile)

        Diagnostics.clear()
        val second = store(this)
        val previous = checkNotNull(second.load())

        assertEquals(1_700_000_000_000 + 3_000, previous.savedAt)
        // Requests are not kept; failures, recoveries and offline entries are, newest first.
        assertEquals(listOf("reconnected", "connection.lost"), previous.entries.map { it.label })
        assertEquals(1_700_000_000_000 + 2_000, previous.entries.last().at)
        val connect = previous.connects.single()
        assertEquals(1_700_000_000_000 + 1_000, connect.at)
        assertEquals("ok", connect.outcome)
        assertEquals(listOf("permessage-deflate"), connect.extensions)
        assertEquals(listOf(HandshakePhase.OPENING, HandshakePhase.ACTIVE), connect.phases.map { it.first })
        val rtt = checkNotNull(previous.link.rtt)
        assertEquals(2, rtt.count)
        assertEquals(120L, rtt.last)
        assertEquals(25L, rtt.relayMs)
        assertEquals(1L, previous.link.count(Diagnostics.Counter.RECONNECTS))
        assertEquals(1L, previous.link.count(Diagnostics.Counter.RESUMES_REPLAYED))
        assertEquals(mapOf(1012 to 1L), previous.link.closeCodes)
    }

    @Test
    fun startLoadsThePreviousRunIntoTheReportAndTheExport() = runTest {
        recordARun()
        val first = store(this)
        first.start()
        runCurrent()
        first.flush()
        runCurrent()
        Diagnostics.clear()

        store(this).start()
        runCurrent()

        val previous = checkNotNull(Diagnostics.report().previous)
        assertEquals("connection.lost", previous.entries.last().label)
        val export = Diagnostics.export()
        assertTrue(export.contains("Before restart"))
        assertTrue(export.contains("connection.lost closed.1012"))
        assertTrue(export.contains("rtt n=2"))
    }

    @Test
    fun theStoreWritesOnlyWhileSomethingChangedAndFlushSavesAtOnce() = runTest {
        val file = File(folder.root, "diagnostics-v1.json")
        val store = store(this)
        store.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        // Nothing recorded yet: an idle process must not write, or it would replace the previous run.
        assertTrue(!file.exists())
        store.flush()
        runCurrent()
        assertTrue(!file.exists())
        Diagnostics.count(Diagnostics.Counter.ACKS_SENT)
        store.flush()
        runCurrent()
        val written = file.lastModified()
        assertTrue(file.isFile)

        file.setLastModified(written - 60_000)
        val marker = file.lastModified()
        advanceTimeBy(90_000)
        runCurrent()
        // Unchanged since the last save: the file is left alone.
        assertEquals(marker, file.lastModified())

        Diagnostics.count(Diagnostics.Counter.ACKS_SENT)
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(file.lastModified() != marker)
        assertEquals(2L, checkNotNull(store(this).load()).link.count(Diagnostics.Counter.ACKS_SENT))
    }

    @Test
    fun aSaveThatKeepsFailingIsRecordedOnceAndRetriedQuietly() = runTest {
        // The parent folder does not exist, so every write throws the same exception.
        val store = store(this, name = "missing/diagnostics-v1.json")
        store.start()
        runCurrent()
        repeat(5) {
            Diagnostics.count(Diagnostics.Counter.ACKS_SENT)
            advanceTimeBy(30_001)
            runCurrent()
        }
        assertEquals(1, Diagnostics.report().entries.count { it.label == "diagnostics.save" })
        assertTrue(!File(folder.root, "missing/diagnostics-v1.json").exists())

        // Once the fault clears, the next tick saves and a later failure would be recorded again.
        File(folder.root, "missing").mkdirs()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(File(folder.root, "missing/diagnostics-v1.json").isFile)
    }

    @Test
    fun theFileNeverExceedsItsCapAndKeepsTheNewestEntries() = runTest {
        time = 10
        repeat(64) { Diagnostics.record(Diagnostics.Kind.FAILURE, "connection.lost", outcome = "closed.1006") }
        repeat(20) { Diagnostics.connectStarted("foreground.device").finish("ok") }
        val small = store(this, maxBytes = 3_000)
        val run = Diagnostics.persistable(1_700_000_000_000)

        val text = small.encode(run)

        assertTrue(text.toByteArray().size <= 3_000)
        val decoded = checkNotNull(small.decode(text))
        assertTrue(decoded.entries.isNotEmpty())
        assertTrue(decoded.entries.size < 64 || decoded.connects.size < 20)
        // The full-size store keeps all of them well below 64 KiB.
        val big = store(this)
        val full = big.encode(run)
        assertTrue(full.toByteArray().size < DiagnosticsStore.MAX_BYTES)
        assertEquals(64, checkNotNull(big.decode(full)).entries.size)
        assertEquals(20, big.decode(full)!!.connects.size)
    }

    @Test
    fun anOversizedOrMalformedFileLoadsAsNothing() = runTest {
        val file = File(folder.root, "diagnostics-v1.json")
        val store = store(this, maxBytes = 200)
        file.writeText("x".repeat(201))
        assertNull(store.load())
        file.writeText("{not json")
        assertNull(store(this).load())
        file.writeText("""{"version":2,"savedAt":5}""")
        assertNull(store(this).load())
        file.delete()
        assertNull(store(this).load())
    }

    @Test
    fun whatTheFileHoldsIsRedactedAgainOnLoad() = runTest {
        val secret = "Qm7xK2pLw9RtZb4nVc8sJd"
        val file = File(folder.root, "diagnostics-v1.json")
        file.writeText(
            """{"version":1,"savedAt":5000,
              "connects":[{"at":4000,"label":"$secret","phases":[["OPENING",5],["NOPE",1]],"outcome":"Please refactor the payment module","extensions":["permessage-deflate","$secret"]}],
              "entries":[{"at":4500,"kind":"FAILURE","label":"connection.lost","outcome":"$secret"},{"at":1,"kind":"BOGUS","label":"x"}],
              "counters":{"reconnects":3,"unknown":9},"closeCodes":{"1012":2,"abc":1,"99999":1},
              "rtt":{"count":500,"p50":1,"p95":2,"last":3}}"""
        )

        val previous = checkNotNull(store(this).load())

        val connect = previous.connects.single()
        assertEquals("redacted", connect.label)
        assertEquals("redacted", connect.outcome)
        assertEquals(listOf(HandshakePhase.OPENING), connect.phases.map { it.first })
        assertEquals(listOf("permessage-deflate"), connect.extensions)
        assertEquals("redacted", previous.entries.single().outcome)
        assertEquals(mapOf(Diagnostics.Counter.RECONNECTS to 3L), previous.link.counters)
        assertEquals(mapOf(1012 to 2L), previous.link.closeCodes)
        assertEquals(Diagnostics.RTT_SAMPLES, previous.link.rtt?.count)
    }

    private fun TestScope.connectAndStall(host: PairedHost) {
        val socket = Socket()
        val transport = SecureRemoteTransport(backgroundScope, socket) { "Test device" }
        transport.listener =
            object : RemoteTransport.Listener {
                override fun ready(capabilities: Set<String>) {}

                override fun approval() {}

                override suspend fun persistPairing(host: PairedHost) {}

                override fun paired(host: PairedHost) {}

                override fun message(payload: JsonObject) {}

                override fun failed(reconnect: Boolean, error: Int) {}
            }
        transport.connect(host)
        socket.open()
        runCurrent()
        advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS + 1)
        runCurrent()
    }

    @Test
    fun safeTokensKeepCodeWordsAndRedactTheRest() {
        for (word in listOf(
            "sessions.list", "session.configuration.get", "timeout.challenge", "closed.1012",
            "SocketTimeoutException", "IOException", "AEADBadTagException", "repo.activate.2",
            "error.not_found", "activate.restore.force", "ok:projects.hit:sessions.miss:chat.hit",
        )) assertEquals(word, Diagnostics.safeToken(word))
        for (word in listOf(
            "Qm7xK2pLw9RtZb4nVc8sJd", "a1B2c3D4e5", "3f2a9c1b77e0", "with space", "6f1c2e9a-5b7d", "",
            "x".repeat(97),
        )) assertEquals(word, "redacted", Diagnostics.safeToken(word))
    }

    @Test
    fun caughtKeepsOnlyTheExceptionClass() {
        Diagnostics.caught("repo.receive", IllegalArgumentException("secret text"))

        val entry = Diagnostics.report().entries.single()
        assertEquals(Diagnostics.Kind.CAUGHT, entry.kind)
        assertEquals("repo.receive", entry.label)
        assertEquals("IllegalArgumentException", entry.outcome)
    }

    @Test
    fun framesAreSummarisedApartFromTheEntries() {
        for (size in 1..10) Diagnostics.frame(size * 1_000_000L, size * 100)

        val report = Diagnostics.report()
        assertTrue(report.entries.isEmpty())
        val frames = report.frames!!
        assertEquals(10, frames.count)
        assertEquals(5_000L, frames.p50Micros)
        assertEquals(10_000L, frames.p95Micros)
        assertEquals(1_000L, frames.maxBytes)
    }

    @Test
    @Config(sdk = [28])
    fun asyncTraceSectionsAreSkippedBelowApi29() {
        ShadowTrace.setEnabled(true)
        val cookie = Diagnostics.beginAsync("PocketPi.activate")

        assertTrue(ShadowTrace.getCurrentAsyncSections().isEmpty())
        Diagnostics.endAsync("PocketPi.activate", cookie)
        assertTrue(ShadowTrace.getPreviousAsyncSections().isEmpty())
    }

    @Test
    fun asyncTraceSectionsAreIssuedFromApi29() {
        ShadowTrace.setEnabled(true)
        val cookie = Diagnostics.beginAsync("PocketPi.req.sessions.list")

        assertTrue(
            ShadowTrace.getCurrentAsyncSections()
                .any { it.sectionName == "PocketPi.req.sessions.list" && it.cookie == cookie }
        )
        Diagnostics.endAsync("PocketPi.req.sessions.list", cookie)
        assertTrue(ShadowTrace.getCurrentAsyncSections().isEmpty())
        assertTrue(ShadowTrace.getPreviousAsyncSections().any { it.cookie == cookie })
    }

    @Test
    fun synchronousTraceSectionsWrapTheBlock() {
        ShadowTrace.setEnabled(true)

        val value = Diagnostics.trace("PocketPi.publishTimeline") {
            assertTrue(ShadowTrace.getCurrentSections().contains("PocketPi.publishTimeline"))
            42
        }

        assertEquals(42, value)
        assertTrue(ShadowTrace.getCurrentSections().isEmpty())
        assertTrue(ShadowTrace.getPreviousSections().contains("PocketPi.publishTimeline"))
    }

    @Test
    fun mainThreadCheckRunsOnlyInDebugOnARealDevice() {
        assertFalse(mainThreadCheckEnabled(debug = false, robolectric = false, hasMainLooper = true))
        assertFalse(mainThreadCheckEnabled(debug = true, robolectric = true, hasMainLooper = true))
        assertFalse(mainThreadCheckEnabled(debug = true, robolectric = false, hasMainLooper = false))
        assertTrue(mainThreadCheckEnabled(debug = true, robolectric = false, hasMainLooper = true))
    }

    @Test
    fun mainThreadCheckIsInertUnderRobolectric() {
        var failure: Throwable? = null
        val thread = Thread { failure = runCatching { checkMainThread() }.exceptionOrNull() }
        thread.start()
        thread.join()

        assertEquals(null, failure)
    }

    private class Socket : WebSocket, WebSocket.Factory {
        lateinit var incoming: WebSocketListener
        lateinit var requestValue: Request

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requestValue = request
            incoming = listener
            return this
        }

        override fun request() = requestValue

        override fun queueSize() = 0L

        override fun send(text: String) = true

        override fun send(bytes: ByteString) = false

        override fun close(code: Int, reason: String?) = true

        override fun cancel() {}

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
    }
}
