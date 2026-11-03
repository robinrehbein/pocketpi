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
}
