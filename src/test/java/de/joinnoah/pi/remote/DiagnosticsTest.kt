package de.joinnoah.pi.remote

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {
    private var time = 0L

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
