package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EmulatorClientTest {
    private data class Call(val type: String, val reply: CompletableDeferred<JsonObject>)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun openingListsProfilesBeforeStartingAnEmulator() {
        val scope = TestScope()
        val calls = mutableListOf<Call>()
        val client = EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, type, _ ->
            CompletableDeferred<JsonObject>().also { calls.add(Call(type, it)) }.await()
        }, {})
        assertTrue(client.open("session", 1))
        scope.runCurrent()
        assertEquals(listOf("emulator.list"), calls.map { it.type })
        assertTrue(client.view!!.opening)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun lateProfileReplyCannotReopenClosedView() {
        val scope = TestScope()
        val reply = CompletableDeferred<JsonObject>()
        val client = EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, _, _ -> reply.await() }, {})
        client.open("session", 1)
        scope.runCurrent()
        client.close()
        reply.complete(Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(emptyList())))
        scope.runCurrent()
        assertNull(client.view)
    }
    private fun watchingClient(scope: TestScope, recovery: Boolean, closed: MutableList<Long>, watches: MutableList<Map<String, Any?>>): EmulatorClient {
        val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
        return EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, type, fields ->
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to 1, "geometry" to geometry)
                "emulator.status" -> Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to 1, "geometry" to geometry)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to 1, "participants" to kotlinx.serialization.json.JsonArray(emptyList()))
                "emulator.watch" -> { watches.add(fields.toMap()); Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to 1, "streamId" to "stream") }
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to "stream")
                else -> error("unexpected $type")
            }
        }, {}, { "d" }, { true }, { closed.add(it) }, { recovery })
    }
    private fun waiting(streamId: String = "stream") = Wire.objectOf("type" to "host.event", "kind" to "emulator.frame.waiting", "emulatorId" to "e", "incarnation" to "i", "streamId" to streamId, "generation" to 1, "reason" to "capturing")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aRecoveryWatchStaysOpenWhileTheHostSaysItIsCapturing() {
        val scope = TestScope(); val closed = mutableListOf<Long>(); val watches = mutableListOf<Map<String, Any?>>()
        val client = watchingClient(scope, true, closed, watches)
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertEquals("true", watches.single()["recovery"]?.toString())
        repeat(4) { scope.testScheduler.advanceTimeBy(2_000); client.frame(waiting()); scope.runCurrent() }
        assertEquals(emptyList<Long>(), closed)
        // Without a frame or another waiting event, the deadline still fails closed.
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(listOf(1L), closed)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun waitingForAnotherStreamOrWithoutRecoveryDoesNotExtendTheDeadline() {
        for (recovery in listOf(true, false)) {
            val scope = TestScope(); val closed = mutableListOf<Long>(); val watches = mutableListOf<Map<String, Any?>>()
            val client = watchingClient(scope, recovery, closed, watches)
            assertTrue(client.open("session", 1)); scope.runCurrent()
            assertTrue(client.selectProfile("p")); scope.runCurrent()
            assertEquals(if (recovery) "true" else null, watches.single()["recovery"]?.toString())
            scope.testScheduler.advanceTimeBy(4_000)
            client.frame(if (recovery) waiting("other") else waiting()); scope.runCurrent()
            scope.testScheduler.advanceTimeBy(1_600); scope.runCurrent()
            assertEquals(listOf(1L), closed)
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun acquireRewatchesNewGenerationAndWaitsForFrameBeforeInput() {
        val scope = TestScope()
        val calls = mutableListOf<String>()
        val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
        val owner = Wire.objectOf("kind" to "device", "id" to "connection")
        val client = EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, type, fields ->
            calls.add(type)
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to 1, "geometry" to geometry)
                "emulator.status" -> Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to 1, "geometry" to geometry)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to 1, "participants" to kotlinx.serialization.json.JsonArray(emptyList()))
                "emulator.watch" -> Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to fields.first { it.first == "generation" }.second, "streamId" to "stream${calls.count { it == "emulator.watch" }}")
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to fields.first { it.first == "streamId" }.second)
                "emulator.stop" -> Wire.objectOf("kind" to "emulator.stopped", "emulatorId" to "e", "generation" to 3)
                "emulator.acquire" -> Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to 2, "geometry" to geometry, "owner" to owner, "leaseId" to "lease", "nextSequence" to 0)
                else -> error("unexpected $type")
            }
        }, {}, { "d" })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertFalse(client.input(EmulatorInput.Tap(0, 0)))
        assertTrue(client.acquire()); scope.runCurrent()
        assertEquals(2, calls.count { it == "emulator.watch" })
        assertFalse(client.input(EmulatorInput.Tap(0, 0)))
        assertFalse(calls.contains("emulator.input"))
        assertTrue(client.stop()); scope.runCurrent()
        assertNull(client.view)
        assertFalse(calls.contains("emulator.detach"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun reconnectRejoinsReplacementContextBeforeStatusWithoutAcquiring() {
        val scope = TestScope()
        var connection = 1L
        var joined = false
        val calls = mutableListOf<Pair<Long, String>>()
        val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
        val client = EmulatorClient(scope.backgroundScope, { _, c -> c == connection }, { c, type, fields ->
            calls.add(c to type)
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> { if (c == 2L) joined = true; Wire.objectOf("kind" to "emulator.opened", "emulatorId" to if (c == 2L) "e2" else "e", "profileId" to "p", "label" to "Phone", "incarnation" to if (c == 2L) "i2" else "i", "generation" to 1, "geometry" to geometry) }
                "emulator.status" -> { check(c != 2L || joined); Wire.objectOf("kind" to "emulator.status", "emulatorId" to if (c == 2L) "e2" else "e", "profileId" to "p", "label" to "Phone", "incarnation" to if (c == 2L) "i2" else "i", "generation" to 1, "geometry" to geometry) }
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to if (c == 2L) "e2" else "e", "generation" to 1, "participants" to kotlinx.serialization.json.JsonArray(emptyList()))
                "emulator.watch" -> Wire.objectOf("kind" to "emulator.watching", "emulatorId" to if (c == 2L) "e2" else "e", "generation" to fields.first { it.first == "generation" }.second, "streamId" to "stream$c")
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to if (c == 2L) "e2" else "e", "streamId" to fields.first { it.first == "streamId" }.second)
                else -> error("unexpected $type")
            }
        }, {})
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        client.disconnected(); connection = 2
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        val reconnectCalls = calls.filter { it.first == 2L }.map { it.second }
        assertEquals("emulator.open", reconnectCalls.first())
        assertTrue(reconnectCalls.contains("emulator.status"))
        assertFalse(reconnectCalls.contains("emulator.acquire"))
        assertEquals("e2", client.view?.emulatorId)
        assertFalse(requireNotNull(client.view).ownsControl)
        client.state(Wire.objectOf("type" to "host.event", "kind" to "emulator.state", "sessionId" to "session", "emulatorId" to "e2", "profileId" to "p", "label" to "Phone", "incarnation" to "i2", "generation" to 2, "geometry" to geometry, "owner" to Wire.objectOf("kind" to "device", "id" to "connection"), "control" to Wire.objectOf("leaseId" to "handed", "nextSequence" to 0)))
        scope.runCurrent()
        assertTrue(requireNotNull(client.view).ownsControl)
        assertFalse(client.input(EmulatorInput.Tap(0, 0)))
        assertFalse(calls.any { it.second == "emulator.acquire" })
    }
    private val testGeometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
    private val self = Wire.objectOf("kind" to "device", "id" to "connection")
    private fun stateEvent(generation: Int, owner: JsonObject?, leaseId: String? = null) = Wire.objectOf("type" to "host.event", "kind" to "emulator.state", "sessionId" to "session",
        "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to generation, "geometry" to testGeometry,
        *listOfNotNull(owner?.let { "owner" to it }, leaseId?.let { "control" to Wire.objectOf("leaseId" to it, "nextSequence" to 0) }).toTypedArray())
    // A fake Host whose generation, owner and acquire reply the test controls. Calls are (connection, type, fields).
    private class FakeHost {
        var generation = 1; var owner: JsonObject? = null; var acquireReply: CompletableDeferred<JsonObject>? = null; var busyOwner: JsonObject? = null
        val calls = mutableListOf<Triple<Long, String, Map<String, Any?>>>()
        // When set, each watch waits for its reply here, in order.
        var deferWatches = false; val watchReplies = mutableListOf<CompletableDeferred<Unit>>()
        fun count(type: String) = calls.count { it.second == type }
    }
    private fun hostClient(scope: TestScope, host: FakeHost, connection: () -> Long, closed: MutableList<Long> = mutableListOf()): EmulatorClient {
        val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
        return EmulatorClient(scope.backgroundScope, { _, c -> c == connection() }, { c, type, fields ->
            val map = fields.toMap(); host.calls.add(Triple(c, type, map))
            val owner = host.owner?.let { arrayOf("owner" to it) } ?: emptyArray()
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to host.generation, "geometry" to geometry, *owner)
                "emulator.status" -> Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to host.generation, "geometry" to geometry, *owner)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to host.generation, "participants" to kotlinx.serialization.json.JsonArray(emptyList()))
                "emulator.watch" -> { val n = host.count("emulator.watch")
                    if (host.deferWatches) CompletableDeferred<Unit>().also { host.watchReplies.add(it) }.await()
                    Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to map["generation"], "streamId" to "stream$n") }
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to map["streamId"])
                "emulator.acquire" -> host.acquireReply?.await() ?: host.busyOwner?.let { Wire.objectOf("kind" to "emulator.busy", "emulatorId" to "e", "generation" to host.generation, "owner" to it) } ?: run {
                    // The Host's identity of a device is per connection.
                    val me = Wire.objectOf("kind" to "device", "id" to "connection$c"); host.generation++; host.owner = me
                    Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to host.generation, "geometry" to geometry, "owner" to me, "leaseId" to "lease$c", "nextSequence" to 0)
                }
                else -> error("unexpected $type")
            }
        }, {}, { "d" }, { true }, { closed.add(it) })
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun stateEventBeforeTheAcquireReplyKeepsASingleWatchOfTheNewGeneration() {
        // The E2E failure: the Host's state event for the new lease arrived before the acquire reply,
        // both paths watched generation 2, the Host kept the stale watch and the app unwatched it.
        val scope = TestScope(); val host = FakeHost()
        val client = hostClient(scope, host, { 1L })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        val reply = CompletableDeferred<JsonObject>(); host.acquireReply = reply
        assertTrue(client.acquire()); scope.runCurrent()
        host.generation = 2; host.owner = self
        client.state(stateEvent(2, self, "lease")); scope.runCurrent()
        assertEquals(2, host.count("emulator.watch"))
        reply.complete(Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to 2, "geometry" to testGeometry, "owner" to self, "leaseId" to "lease", "nextSequence" to 0))
        scope.runCurrent()
        val watches = host.calls.filter { it.second == "emulator.watch" }.map { it.third["generation"].toString() }
        assertEquals(listOf("1", "2"), watches)
        assertEquals(listOf("stream1"), host.calls.filter { it.second == "emulator.unwatch" }.map { it.third["streamId"].toString().trim('"') })
        assertTrue(requireNotNull(client.view).ownsControl)
        assertEquals(2L, client.view?.generation)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aLeaseLostWithTheConnectionIsTakenBackOnResume() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L
        val client = hostClient(scope, host, { connection })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        assertTrue(requireNotNull(client.view).ownsControl)
        // The Host revokes the lease when the connection closes.
        client.disconnected(); connection = 2; host.generation++; host.owner = null
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        val reconnect = host.calls.filter { it.first == 2L }.map { it.second }
        assertEquals("emulator.open", reconnect.first())
        assertEquals(1, reconnect.count { it == "emulator.acquire" })
        assertTrue(requireNotNull(client.view).ownsControl)
        // A second drop of the same connection reports nothing new and keeps the intent.
        client.disconnected(); client.disconnected(); connection = 3; host.generation++; host.owner = null
        assertTrue(client.resumeIfDisconnected("session", 3)); scope.runCurrent()
        assertEquals(1, host.calls.count { it.first == 3L && it.second == "emulator.acquire" })
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumeStaysAReaderWhenAnotherDeviceTookControl() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L
        val client = hostClient(scope, host, { connection })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        client.disconnected(); connection = 2; host.generation++; host.owner = Wire.objectOf("kind" to "device", "id" to "other")
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        assertEquals(0, host.calls.count { it.first == 2L && it.second == "emulator.acquire" })
        assertFalse(requireNotNull(client.view).ownsControl)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aFailedStreamDoesNotTakeControlBackOnResume() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L; val closed = mutableListOf<Long>()
        val client = hostClient(scope, host, { connection }, closed)
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        // No frame arrives: the deadline fails closed and drops the connection on purpose.
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(listOf(1L), closed)
        client.disconnected(); connection = 2; host.generation++; host.owner = null
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        assertEquals(0, host.calls.count { it.first == 2L && it.second == "emulator.acquire" })
        assertFalse(requireNotNull(client.view).ownsControl)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumeWaitsForTheHostToRevokeTheOldConnectionsLease() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L
        val client = hostClient(scope, host, { connection })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        // A second drop while the resume opens must not lose the intent either.
        client.disconnected(); connection = 2
        assertTrue(client.resumeIfDisconnected("session", 2)); client.disconnected(); connection = 3; scope.runCurrent()
        // The Host has not noticed the drop yet: it still shows the old connection as the owner.
        assertTrue(client.resumeIfDisconnected("session", 3)); scope.runCurrent()
        assertEquals(1, host.count("emulator.acquire"))
        host.generation++; host.owner = null
        client.state(stateEvent(host.generation, null)); scope.runCurrent()
        assertEquals(1, host.calls.count { it.first == 3L && it.second == "emulator.acquire" })
        assertTrue(requireNotNull(client.view).ownsControl)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aBusyReacquireKeepsTheResumeWatch() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L
        val client = hostClient(scope, host, { connection })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        client.disconnected(); connection = 2; host.generation++; host.owner = null
        val other = Wire.objectOf("kind" to "device", "id" to "other"); host.busyOwner = other
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        assertEquals(1, host.calls.count { it.first == 2L && it.second == "emulator.acquire" })
        assertEquals(1, host.calls.count { it.first == 2L && it.second == "emulator.watch" })
        assertEquals(0, host.calls.count { it.first == 2L && it.second == "emulator.unwatch" })
        // The user did not ask for control, so the automatic attempt shows no busy notice.
        assertEquals("disconnected", client.view?.notice)
        assertFalse(requireNotNull(client.view).ownsControl)
        // Someone else has control now: a later free state does not take it back.
        host.owner = null; host.busyOwner = null; host.generation++
        client.state(stateEvent(host.generation, null)); scope.runCurrent()
        assertEquals(1, host.calls.count { it.first == 2L && it.second == "emulator.acquire" })
    }
    private fun unwatched(host: FakeHost) = host.calls.filter { it.second == "emulator.unwatch" }.map { it.third["streamId"].toString().trim('"') }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun hidingAndShowingWhileAWatchIsInFlightSendsTheNextWatchAfterItsReply() {
        val scope = TestScope(); val host = FakeHost().apply { deferWatches = true }
        val client = hostClient(scope, host, { 1L })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertEquals(1, host.count("emulator.watch"))
        client.setVisible(false); client.setVisible(true); scope.runCurrent()
        // A second watch of generation 1 now could be handled before the first and then replaced by it.
        assertEquals(1, host.count("emulator.watch"))
        host.watchReplies[0].complete(Unit); scope.runCurrent()
        assertEquals(2, host.count("emulator.watch"))
        assertEquals(listOf("stream1"), unwatched(host))
        host.watchReplies[1].complete(Unit); scope.runCurrent()
        assertEquals(listOf("stream1"), unwatched(host))
        assertEquals(2, host.count("emulator.watch"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aGeometryChangeWhileAWatchIsInFlightSendsTheNextWatchAfterItsReply() {
        val scope = TestScope(); val host = FakeHost().apply { deferWatches = true }
        val client = hostClient(scope, host, { 1L })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        val rotated = Wire.objectOf("width" to 3, "height" to 2, "rotation" to 1, "revision" to 2)
        client.state(Wire.objectOf("type" to "host.event", "kind" to "emulator.state", "sessionId" to "session", "emulatorId" to "e", "profileId" to "p", "label" to "Phone",
            "incarnation" to "i", "generation" to 1, "geometry" to rotated)); scope.runCurrent()
        assertEquals(1, host.count("emulator.watch"))
        host.watchReplies[0].complete(Unit); scope.runCurrent()
        assertEquals(2, host.count("emulator.watch"))
        assertEquals(listOf("stream1"), unwatched(host))
        host.watchReplies[1].complete(Unit); scope.runCurrent()
        assertEquals(listOf("stream1"), unwatched(host))
        assertEquals(2L, client.view?.geometry?.revision)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aResumeWatchRefusedByTheReacquiresGenerationBumpIsNotFatal() {
        val scope = TestScope(); val host = FakeHost(); var connection = 1L; val closed = mutableListOf<Long>()
        val client = hostClient(scope, host, { connection }, closed)
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        client.disconnected(); connection = 2; host.generation++; host.owner = null
        host.deferWatches = true; val reply = CompletableDeferred<JsonObject>(); host.acquireReply = reply
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        assertEquals(1, host.calls.count { it.first == 2L && it.second == "emulator.acquire" })
        // The Host handled the acquire first, so the resume watch of the old generation is refused.
        host.watchReplies.last().completeExceptionally(IllegalStateException("forbidden")); scope.runCurrent()
        assertEquals(emptyList<Long>(), closed)
        host.generation++; host.deferWatches = false
        reply.complete(Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to host.generation, "geometry" to testGeometry, "owner" to Wire.objectOf("kind" to "device", "id" to "connection2"), "leaseId" to "lease2", "nextSequence" to 0))
        scope.runCurrent()
        assertEquals(emptyList<Long>(), closed)
        assertTrue(requireNotNull(client.view).ownsControl)
        assertEquals(host.generation.toString(), host.calls.last { it.second == "emulator.watch" }.third["generation"].toString())
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aWatchRefusedWithoutAnAcquireStillFailsClosed() {
        val scope = TestScope(); val host = FakeHost().apply { deferWatches = true }; val closed = mutableListOf<Long>()
        val client = hostClient(scope, host, { 1L }, closed)
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        host.watchReplies.single().completeExceptionally(IllegalStateException("forbidden")); scope.runCurrent()
        assertEquals(listOf(1L), closed)
    }
}
