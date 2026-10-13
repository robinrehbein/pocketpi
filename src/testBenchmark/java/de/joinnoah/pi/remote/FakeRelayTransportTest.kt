package de.joinnoah.pi.remote

import android.app.Application
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
    )

    private fun TestScope.harness(profile: String = "lte"): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeRelayTransport(backgroundScope, { fixture }, dispatcher) { testScheduler.currentTime }
        transport.profile = profile
        val pairings = Pairings()
        val drafts = Drafts()
        val navigation = Navigation()
        val repository = DefaultRemoteRepository(
            pairings, drafts, transport, backgroundScope, dispatcher,
            now = { testScheduler.currentTime },
            navigationStorage = navigation,
            jitter = { 0.5 },
        )
        // RemoteApplication's lifecycle and network wiring; recovery needs both.
        repository.setValidatedNetwork("network")
        repository.setForeground(true)
        return Harness(transport, repository, pairings, drafts, navigation)
    }

    @Test
    fun pairingPrepareConnectsSnapshotsAndStoresLikeAVisit() = runTest {
        val harness = harness()
        val repository = harness.repository
        val projects = RemoteSelection(fixture.routeId)
        assertEquals(projects, repository.pair(fixture.pairingCode(Long.MAX_VALUE)))
        assertEquals(listOf(fixture.routeId), harness.pairings.hosts.map { it.routeId })
        assertEquals(fixture.hostName, harness.pairings.hosts.single().name)
        assertEquals(fixture.projects.map { it.text("id") }, repository.state.value.projects.map { it.text("id") })

        assertEquals(fixture.prepare, repository.activate(fixture.prepare, ActivationMode.USER_OPEN))
        repository.draft(fixture.prepareDraft)
        assertEquals(projects, repository.activate(projects, ActivationMode.USER_OPEN))
        repository.flush()
        advanceTimeBy(2_000)
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
        // sessions.list, sessions.open and session.snapshot each wait an LTE round trip.
        assertTrue(testScheduler.currentTime - started >= 3 * 100)
        val opened = repository.state.value
        assertEquals(fixture.snapshotPageSize, opened.messages.size)
        assertEquals(session.tail, opened.messages.last().text("text"))
        assertNotNull(opened.nextCursor)
        assertEquals("idle", opened.status)

        val stream = transport.stream(chat.sessionId!!)
        advanceTimeBy(fixture.stream.intervalMs * 10 + 1)
        assertEquals("running", repository.state.value.status)
        assertEquals("streaming", repository.state.value.messages.last().text("state"))
        stream.join()
        val streamed = repository.state.value
        assertEquals("idle", streamed.status)
        assertEquals(fixture.stream.complete, streamed.messages.last().text("text"))
        val answer = streamed.messages[streamed.messages.size - 2]
        assertEquals("complete", answer.text("state"))
        assertEquals((0 until fixture.stream.chunks).joinToString("") { fixture.stream.chunk(it) }, answer.text("text"))

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
    }
}
