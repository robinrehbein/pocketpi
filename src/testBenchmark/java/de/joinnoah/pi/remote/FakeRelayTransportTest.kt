package de.joinnoah.pi.remote

import android.app.Application
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class FakeRelayTransportTest {
    // The asset the benchmark APK ships, so a broken fixture fails here and not on a device.
    private val fixture = RelayFixture.parse(
        RuntimeEnvironment.getApplication().assets.open(CONNECTED_FLOW_FIXTURE)
            .bufferedReader().use { it.readText() }
    )

    private class Pairings : PairingStorage {
        var hosts = emptyList<PairedHost>()
        override fun load() = hosts
        override fun save(hosts: List<PairedHost>) { this.hosts = hosts }
    }

    private class Drafts : DraftStorage {
        var values = emptyMap<DraftKey, StoredDraft>()
        override fun load() = values
        override fun save(drafts: Map<DraftKey, StoredDraft>) { values = drafts.toMap() }
    }

    private class Navigation : NavigationSnapshotStorage {
        var snapshots = emptyMap<String, NavigationSnapshot>()
        override fun load() = snapshots
        override fun save(snapshots: Map<String, NavigationSnapshot>) { this.snapshots = snapshots.toMap() }
    }

    private class Harness(
        val transport: FakeRelayTransport,
        val repository: DefaultRemoteRepository,
        val pairings: Pairings,
        val drafts: Drafts,
        val navigation: Navigation,
        private val process: Job,
    ) {
        /** Ends this "process": nothing it started runs or writes any more. */
        fun kill() = process.cancel()
    }

    /** One app process; pass an earlier harness's stores to start it as a cold start of that app. */
    private fun TestScope.harness(
        profile: String = "lte",
        pairings: Pairings = Pairings(),
        drafts: Drafts = Drafts(),
        navigation: Navigation = Navigation(),
    ): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val process = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + process)
        val transport = FakeRelayTransport(scope, { fixture }, dispatcher) { testScheduler.currentTime }
        transport.profile = profile
        val repository = DefaultRemoteRepository(
            pairings, drafts, transport, scope, dispatcher,
            now = { testScheduler.currentTime },
            navigationStorage = navigation,
            jitter = { 0.5 },
        )
        // RemoteApplication's lifecycle and network wiring; recovery needs both.
        repository.setValidatedNetwork("network")
        repository.setForeground(true)
        return Harness(transport, repository, pairings, drafts, navigation, process)
    }

    /** A cold start of [previous]'s app: same stores, new repository and relay connection. */
    private fun TestScope.restart(previous: Harness, profile: String = "lte"): Harness {
        previous.kill()
        return harness(profile, previous.pairings, previous.drafts, previous.navigation)
    }

    /** The newest chat open's cache result, as ConnectedFlowActivity reports it to the benchmarks. */
    private fun chatCache() = ConnectedFlowPreparation.lastChatCache()

    @Test
    fun pairingPrepareConnectsSnapshotsAndStoresLikeAVisit() = runTest {
        val harness = harness()
        val repository = harness.repository
        assertTrue(ConnectedFlowPreparation.visit(repository, fixture, System.currentTimeMillis()))
        assertEquals(listOf(fixture.routeId), harness.pairings.hosts.map { it.routeId })
        assertEquals(fixture.hostName, harness.pairings.hosts.single().name)
        assertEquals(fixture.projects.map { it.text("id") }, repository.state.value.projects.map { it.text("id") })
        advanceTimeBy(5_000)
        assertEquals(0, harness.transport.inFlight.value)
        assertTrue(
            ConnectedFlowPreparation.settled(fixture, repository.state.value, harness.navigation.snapshots[fixture.routeId])
        )
        val draftKey = DraftKey(fixture.routeId, fixture.prepare.sessionId!!)
        assertEquals(fixture.prepareDraft, harness.drafts.values.getValue(draftKey).text)
        val snapshot = harness.navigation.snapshots.getValue(fixture.routeId)
        assertEquals(fixture.projects.size, snapshot.projects.size)
        assertTrue(harness.transport.isConnected)
        // Everything the repository asked while pairing, listing and opening has a fixture reply.
        assertEquals(emptySet<String>(), harness.transport.unsupported)
    }

    @Test
    fun connectSnapshotAndUpsertScriptRunThroughTheRepository() = runTest {
        val harness = harness()
        val repository = harness.repository
        val transport = harness.transport
        repository.pair(fixture.pairingCode(Long.MAX_VALUE))
        val chat = RemoteSelection(fixture.routeId, "project-pocketpi", "session-uncached")
        val session = checkNotNull(fixture.session("session-uncached"))
        val started = testScheduler.currentTime
        assertEquals(chat, repository.activate(chat, ActivationMode.USER_OPEN))
        // sessions.list waits an LTE round trip, then sessions.open another; a live chat's
        // session.snapshot goes out right behind the open, so it shares that round trip.
        assertTrue(testScheduler.currentTime - started >= 2 * 100)
        val opened = repository.state.value
        assertEquals(fixture.snapshotPageSize, opened.messages.size)
        assertEquals(session.tail, opened.messages.last().text("text"))
        assertNotNull(opened.nextCursor)
        assertEquals("idle", opened.status)

        val script = fixture.stream
        val stream = transport.stream(chat.sessionId!!)
        advanceTimeBy(script.intervalMs * 10 + 1)
        assertEquals("running", repository.state.value.status)
        assertEquals("streaming", repository.state.value.messages.last().text("state"))
        stream.join()
        val streamed = repository.state.value
        assertEquals("idle", streamed.status)
        // First answer, the edit tool call and its result, second answer, completion marker.
        val tail = streamed.messages.takeLast(5)
        assertEquals(fixture.stream.complete, tail[4].text("text"))
        assertEquals(listOf("assistant", "assistant", "tool", "assistant"), tail.take(4).map { it.text("role") })
        assertTrue(tail.take(4).all { it.text("state") == "complete" })
        assertEquals(script.text(0 until script.toolAt), tail[0].text("text"))
        assertEquals(tail[1].array("parts").single().text("id"), tail[2].text("toolCallId"))
        assertEquals(script.text(script.toolAt until script.chunks), tail[3].text("text"))
        // The Timeline projects the streamed edit like any other.
        val projected = conversationItems(streamed.messages)
        assertTrue(touchedFiles(projected).changed.any { it.path == "example-stream.kt" })

        // A dropped socket reconnects, and the fresh snapshot already holds the streamed answer.
        transport.drop()
        assertFalse(repository.state.value.connected)
        // Everything runs in backgroundScope, which advanceUntilIdle leaves alone.
        advanceTimeBy(10_000)
        val recovered = repository.state.value
        assertTrue(recovered.connected)
        assertEquals(2, transport.connections)
        assertEquals(chat, recovered.selection)
        assertEquals(fixture.stream.complete, recovered.messages.last().text("text"))
        assertEquals(emptySet<String>(), transport.unsupported)
    }

    /** Records [FlowTrace] sections with their durations on the test clock. */
    private class Sections(private val clock: () -> Long) : FlowTrace {
        private val open = mutableMapOf<Int, Pair<String, Long>>()
        private var next = 0
        val ended = mutableListOf<Pair<String, Long>>()

        override fun begin(name: String) = (++next).also { open[it] = name to clock() }

        override fun end(name: String, cookie: Int) {
            val (begun, at) = checkNotNull(open.remove(cookie))
            assertEquals(begun, name)
            ended += name to clock() - at
        }

        fun duration(name: String) = ended.single { it.first == name }.second

        val pending: Collection<String> get() = open.values.map { it.first }
    }

    /**
     * [harness] paired and settled on the uncached chat, with a [FlowFreshness] that follows its
     * repository and relay as ConnectedFlowRelay wires it in the benchmark APK.
     */
    private fun TestScope.flowChat(harness: Harness, sections: Sections): FlowFreshness {
        val freshness = FlowFreshness(harness.transport, sections)
        backgroundScope.launch {
            combine(harness.repository.state, harness.transport.replies) { state, _ -> state }.collect(freshness::observe)
        }
        return freshness
    }

    private suspend fun TestScope.openFlowChat(harness: Harness): RemoteSelection {
        harness.repository.pair(fixture.pairingCode(Long.MAX_VALUE))
        val chat = RemoteSelection(fixture.routeId, "project-pocketpi", "session-uncached")
        assertEquals(chat, harness.repository.activate(chat, ActivationMode.USER_OPEN))
        advanceTimeBy(5_000)
        assertEquals(0, harness.transport.inFlight.value)
        return chat
    }

    @Test
    fun dropCommandIsFreshOnceTheReconnectReadTheChatAgain() = runTest {
        val harness = harness()
        val sections = Sections { testScheduler.currentTime }
        val freshness = flowChat(harness, sections)
        val chat = openFlowChat(harness)
        assertEquals(FlowFreshness.Status(0, fresh = true), freshness.status.value)

        // As ConnectedFlowActivity's `drop` command: mark, then fail the socket.
        freshness.mark(FlowFreshness.Cause.DROP)
        harness.transport.drop()
        testScheduler.runCurrent()
        assertFalse(harness.repository.state.value.connected)
        assertTrue(harness.repository.state.value.stale)
        assertEquals(FlowFreshness.Status(1, fresh = false), freshness.status.value)
        advanceTimeBy(10_000)

        assertEquals(FlowFreshness.Status(1, fresh = true), freshness.status.value)
        assertEquals(2, harness.transport.connections)
        assertEquals(chat, harness.repository.state.value.selection)
        assertEquals(emptyList<String>(), sections.pending.toList())
        // The quiet reconnect waits its first backoff (1 s at this jitter), then four handshake
        // round trips; the restore reads projects, sessions and the snapshot together, in one more.
        val connected = sections.duration(FlowFreshness.DROP_TO_CONNECTED)
        val fresh = sections.duration(FlowFreshness.Cause.DROP.section)
        assertTrue("connected after $connected ms", connected in 1_000L + 4 * 100..1_000L + 4 * 200)
        assertTrue("fresh after $fresh ms", fresh - connected in 1L * 100..1L * 200 + 100)
    }

    @Test
    fun dropWithoutASocketStaysPending() = runTest {
        val harness = harness()
        val sections = Sections { testScheduler.currentTime }
        val freshness = flowChat(harness, sections)
        openFlowChat(harness)
        harness.repository.setNetwork(null, validated = false, path = null)
        harness.transport.drop()
        testScheduler.runCurrent()
        // No network, no reconnect: a mark now never turns fresh, so a benchmark fails instead
        // of measuring nothing.
        freshness.mark(FlowFreshness.Cause.DROP)
        harness.transport.drop()
        advanceTimeBy(60_000)
        assertEquals(FlowFreshness.Status(1, fresh = false), freshness.status.value)
        assertEquals(
            listOf(FlowFreshness.DROP_TO_CONNECTED, FlowFreshness.Cause.DROP.section).sorted(),
            sections.pending.sorted(),
        )
    }

    @Test
    fun resumeIsMarkedOnlyAfterAStopAndFreshOnceTheProbeReadTheChat() = runTest {
        val harness = harness()
        val repository = harness.repository
        val sections = Sections { testScheduler.currentTime }
        val freshness = flowChat(harness, sections)
        // The process's first start is no resume.
        freshness.foreground(true)
        openFlowChat(harness)
        assertEquals(0, freshness.status.value.mark)

        // As ProcessLifecycleOwner reports it: the application's observer first, then this one.
        repository.setForeground(false)
        freshness.foreground(false)
        advanceTimeBy(20_000)
        repository.setForeground(true)
        freshness.foreground(true)
        assertEquals(FlowFreshness.Status(1, fresh = false), freshness.status.value)
        advanceTimeBy(10_000)

        assertEquals(FlowFreshness.Status(1, fresh = true), freshness.status.value)
        // Away for less than a minute: the socket was probed, not replaced.
        assertEquals(1, harness.transport.connections)
        val resumed = sections.duration(FlowFreshness.Cause.RESUME.section)
        assertTrue("fresh after $resumed ms", resumed in 3L * 100..3L * 200 + 100)
    }

    @Test
    fun resumeAfterAMinuteIsFreshOnceTheQuietReconnectReadTheChat() = runTest {
        val harness = harness()
        val repository = harness.repository
        val sections = Sections { testScheduler.currentTime }
        val freshness = flowChat(harness, sections)
        val chat = openFlowChat(harness)

        repository.setForeground(false)
        freshness.foreground(false)
        advanceTimeBy(65_000)
        repository.setForeground(true)
        freshness.foreground(true)
        testScheduler.runCurrent()
        assertFalse(freshness.status.value.fresh)
        advanceTimeBy(10_000)

        assertEquals(FlowFreshness.Status(1, fresh = true), freshness.status.value)
        assertEquals(2, harness.transport.connections)
        assertEquals(chat, repository.state.value.selection)
        // Four handshake round trips and the restore's reads together in one more, without a backoff.
        val resumed = sections.duration(FlowFreshness.Cause.RESUME.section)
        assertTrue("fresh after $resumed ms", resumed in 5L * 100..5L * 200 + 100)
    }

    @Test
    fun repliesCountOnlyNewestPageSuccessesOnTheLiveConnection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeRelayTransport(backgroundScope, { fixture }, dispatcher) { testScheduler.currentTime }
        val delivered = mutableListOf<String>()
        transport.listener = object : RemoteTransport.Listener {
            override fun ready(capabilities: Set<String>) {}
            override fun approval() {}
            override suspend fun persistPairing(host: PairedHost) {}
            override fun paired(host: PairedHost) {}
            override fun message(payload: JsonObject) {
                delivered += payload.text("requestId")
            }
            override fun failed(reconnect: Boolean, error: Int) {}
        }
        val host = PairedHost(fixture.routeId, "relay", fixture.deviceId, fixture.deviceSecret, fixture.hostName)
        val key = "session.snapshot:session-uncached"
        var requests = 0
        fun snapshot(sessionId: String = "session-uncached", cursor: String? = null): String {
            val id = "request-${++requests}"
            transport.send(
                Wire.objectOf("type" to "session.snapshot", "requestId" to id, "sessionId" to sessionId, "cursor" to cursor)
            )
            return id
        }
        transport.connect(host)
        advanceTimeBy(5_000)
        assertTrue(transport.isConnected)

        val newest = snapshot()
        advanceTimeBy(5_000)
        assertEquals(listOf(newest), delivered)
        assertEquals(1L, transport.replies.value.sequence)
        assertEquals(mapOf(key to 1L), transport.replies.value.last)

        // An older page is answered, but the screen's newest page was not read again.
        val older = snapshot(cursor = "before:10")
        // A failed read is answered too, and counts for nothing.
        val missing = snapshot(sessionId = "session-missing")
        advanceTimeBy(5_000)
        assertEquals(listOf(newest, older, missing), delivered)
        assertEquals(1L, transport.replies.value.sequence)
        assertEquals(mapOf(key to 1L), transport.replies.value.last)

        // A reply still in flight when the socket drops is neither delivered nor counted.
        snapshot()
        transport.drop()
        assertFalse(transport.isConnected)
        advanceTimeBy(5_000)
        assertEquals(3, delivered.size)
        assertEquals(1L, transport.replies.value.sequence)
        assertEquals(0, transport.inFlight.value)

        // Only the next connection's newest-page reply advances the sequence.
        transport.connect(host)
        advanceTimeBy(5_000)
        val again = snapshot()
        advanceTimeBy(5_000)
        assertEquals(again, delivered.last())
        assertEquals(2L, transport.replies.value.sequence)
        assertEquals(mapOf(key to 2L), transport.replies.value.last)
    }

    @Test
    fun streamCommandLeavesFreshnessAlone() = runTest {
        val harness = harness()
        val sections = Sections { testScheduler.currentTime }
        val freshness = flowChat(harness, sections)
        val chat = openFlowChat(harness)
        harness.transport.stream(chat.sessionId!!).join()
        assertEquals(FlowFreshness.Status(0, fresh = true), freshness.status.value)
        assertEquals(emptyList<Pair<String, Long>>(), sections.ended)
        assertEquals(fixture.stream.complete, harness.repository.state.value.messages.last().text("text"))
    }

    @Test
    fun dropDuringAnOpenReconnectsWithoutHanging() = runTest {
        val harness = harness(profile = "bad")
        harness.repository.pair(fixture.pairingCode(Long.MAX_VALUE))
        val chat = RemoteSelection(fixture.routeId, "project-pocketpi", "session-short")
        val opening = backgroundScope.async { harness.repository.activate(chat, ActivationMode.USER_OPEN) }
        testScheduler.runCurrent()
        harness.transport.drop()
        advanceTimeBy(30_000)
        // The open either landed on the reconnected socket or fell back; it never hangs.
        assertTrue(opening.isCompleted)
        assertTrue(harness.repository.state.value.connected)
    }

    @Test
    fun anotherPreparePassKeepsTheUncachedChatOutOfTheNextColdStartsCache() = runTest {
        val project = RemoteSelection(fixture.routeId, "project-pocketpi")
        val uncached = RemoteSelection(fixture.routeId, "project-pocketpi", "session-uncached")
        // A cold start: restore the Mac's projects, open the project, then open the uncached chat.
        suspend fun Harness.openUncached(): String? {
            assertEquals(RemoteSelection(fixture.routeId), repository.activate(RemoteSelection(fixture.routeId)))
            assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
            assertEquals(uncached, repository.activate(uncached, ActivationMode.USER_OPEN))
            return chatCache()
        }
        Diagnostics.clear()
        val prepared = harness()
        assertTrue(ConnectedFlowPreparation.visit(prepared.repository, fixture, System.currentTimeMillis()))
        advanceTimeBy(5_000)

        val first = restart(prepared)
        assertEquals("miss", first.openUncached())
        // Benchmark iterations end on the chat; the snapshot then names it as the last chat.
        advanceTimeBy(5_000)
        assertEquals("session-uncached", first.navigation.snapshots.getValue(fixture.routeId).sessionId)

        // Without another prepare pass, the next cold start restores that chat into the cache.
        val unprepared = restart(first)
        assertEquals("hit", unprepared.openUncached())

        val again = restart(unprepared)
        assertTrue(ConnectedFlowPreparation.visit(again.repository, fixture, System.currentTimeMillis()))
        advanceTimeBy(5_000)
        assertTrue(
            ConnectedFlowPreparation.settled(fixture, again.repository.state.value, again.navigation.snapshots[fixture.routeId])
        )
        assertEquals("miss", restart(again).openUncached())
    }

    @Test
    fun profilesMatchTheirNominalRoundTrips() {
        val random = Random(fixture.seed)
        val lan = List(2_000) { fixture.profile("lan").sample(random) }
        val lte = List(2_000) { fixture.profile("lte").sample(random) }
        val bad = List(10_000) { fixture.profile("bad").sample(random) }
        assertTrue(lan.all { it in 25..35 })
        assertTrue(lte.all { it in 100..200 })
        assertEquals(150.0, lte.average(), 5.0)
        val stalls = bad.count { it >= 5_000 }
        assertTrue("stalls: $stalls", stalls in 100..300)
        assertTrue(bad.filter { it < 5_000 }.all { it in 300..500 })
        assertEquals(fixture.profile("lte"), fixture.profile(null))
        assertEquals(fixture.profile("lte"), fixture.profile("unknown"))
    }

    @Test
    fun fixtureTranscriptsAndPairingCodeAreValid() {
        assertEquals(fixture.routeId, Wire.pairing(fixture.pairingCode(Long.MAX_VALUE)).text("routeId"))
        for (session in fixture.sessions) {
            val messages = fixture.transcript(session)
            assertEquals(session.turns * 4 + 1, messages.size)
            assertEquals(session.tail, messages.last().text("text"))
            assertEquals(messages.size, messages.map { it.text("id") }.toSet().size)
        }
        val projected = conversationItems(fixture.transcript(checkNotNull(fixture.session("session-cached"))))
        assertEquals(48, projected.filterIsInstance<ConversationItem.Activity>().size)
        assertEquals(48, touchedFiles(projected).changed.size)
        // 20 upserts per second, and each streamed answer closes the code blocks it opens.
        val script = fixture.stream
        assertEquals(50, script.intervalMs)
        assertEquals(120, script.chunks)
        for (answer in listOf(0 until script.toolAt, script.toolAt until script.chunks)) {
            assertEquals(0, Regex("```").findAll(script.text(answer)).count() % 2)
        }
    }
}
