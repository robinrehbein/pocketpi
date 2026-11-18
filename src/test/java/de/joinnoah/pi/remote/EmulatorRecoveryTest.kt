package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream recovery, the single queued input and the uncertain-input rules of [EmulatorClient]. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmulatorRecoveryTest {
    private class Host {
        var generation = 1
        var owner: JsonObject? = null
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val inputs = mutableListOf<CompletableDeferred<JsonObject>>()
        var watchFailure: (() -> Exception)? = null
        var statusFailure: (() -> Exception)? = null
        fun count(type: String) = calls.count { it.first == type }
        fun inputsSent() = calls.filter { it.first == "emulator.input" }.map { it.second["input"].toString() }
    }
    private val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
    private val self = Wire.objectOf("kind" to "device", "id" to "connection")

    private fun client(scope: TestScope, host: Host, closed: MutableList<Long>): EmulatorClient =
        EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, type, fields ->
            val map = fields.toMap(); host.calls.add(type to map)
            val owner = host.owner?.let { arrayOf("owner" to it) } ?: emptyArray()
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to host.generation, "geometry" to geometry, *owner)
                "emulator.status" -> host.statusFailure?.let { throw it() } ?: Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to host.generation, "geometry" to geometry, *owner)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to host.generation, "participants" to JsonArray(emptyList()))
                "emulator.watch" -> { host.watchFailure?.let { throw it() }
                    Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to map["generation"], "streamId" to "stream${host.count("emulator.watch")}") }
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to map["streamId"])
                "emulator.frame.ack" -> Wire.objectOf("kind" to "accepted")
                "emulator.acquire" -> { host.generation++; host.owner = self
                    Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to host.generation, "geometry" to geometry, "owner" to self, "leaseId" to "lease", "nextSequence" to 0) }
                "emulator.release" -> { host.owner = null; Wire.objectOf("kind" to "emulator.released", "emulatorId" to "e", "generation" to host.generation) }
                "emulator.detach" -> Wire.objectOf("kind" to "emulator.detached", "emulatorId" to "e", "generation" to host.generation)
                "emulator.input" -> CompletableDeferred<JsonObject>().also { host.inputs.add(it) }.await()
                else -> error("unexpected $type")
            }
        }, {}, { "d" }, { true }, { closed.add(it) })

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 2, 0, 0, 0, 3, 8, 6, 0, 0, 0, 0, 0, 0, 0,
    )
    private fun frame(host: Host, frameId: Int = 1) = Wire.objectOf("type" to "host.event", "kind" to "emulator.frame", "emulatorId" to "e", "incarnation" to "i",
        "streamId" to "stream${host.count("emulator.watch")}", "generation" to host.generation, "geometryRevision" to 1, "frameId" to frameId, "chunkIndex" to 0,
        "chunkCount" to 1, "totalBytes" to png.size, "width" to 2, "height" to 3, "displayWidth" to 2, "displayHeight" to 3, "rotation" to 0,
        "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(png))
    private fun ack(host: Host, sequence: Int) = Wire.objectOf("kind" to "emulator.input.ack", "emulatorId" to "e", "generation" to host.generation, "sequence" to sequence, "status" to "accepted")

    private fun TestScope.opened(host: Host, closed: MutableList<Long>): EmulatorClient {
        val client = client(this, host, closed)
        assertTrue(client.open("session", 1)); runCurrent()
        assertTrue(client.selectProfile("p")); runCurrent()
        return client
    }
    private fun TestScope.controlling(host: Host, closed: MutableList<Long>): EmulatorClient {
        val client = opened(host, closed)
        assertTrue(client.acquire()); runCurrent()
        client.frame(frame(host)); runCurrent()
        assertNotNull(client.view?.image)
        return client
    }

    @Test fun aFrameDeadlineEndsOnlyTheStreamRewatchesUpToTheBoundThenOffersRetry() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.opened(host, closed)
        assertEquals(1, host.count("emulator.watch"))
        for (attempt in 1..EMULATOR_STREAM_RETRIES) {
            scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
            assertEquals(EmulatorNotice.STREAM_RECOVERING, client.view?.notice)
            scope.testScheduler.advanceTimeBy(EMULATOR_STREAM_BACKOFF_MS * attempt + 10); scope.runCurrent()
            assertEquals(1 + attempt, host.count("emulator.watch"))
        }
        // The last bounded attempt also times out: no more automatic watches, a manual retry instead.
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(EmulatorNotice.STREAM_FAILED, client.view?.notice)
        scope.testScheduler.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals(1 + EMULATOR_STREAM_RETRIES, host.count("emulator.watch"))
        assertEquals(emptyList<Long>(), closed)
        assertEquals("e", client.view?.emulatorId)
        assertTrue(client.retry()); scope.runCurrent()
        assertEquals(2 + EMULATOR_STREAM_RETRIES, host.count("emulator.watch"))
        assertNull(client.view?.notice)
        // A frame shows the stream is back and resets the count.
        client.frame(frame(host)); scope.runCurrent()
        assertNotNull(client.view?.image)
        assertEquals(emptyList<Long>(), closed)
        assertEquals(0, host.count("emulator.acquire"))
    }

    @Test fun aRefusedRefreshDuringRecoveryCountsAsAFailedAttemptNotAsARequestFailure() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.opened(host, closed)
        host.statusFailure = { RemoteRequestException("forbidden") }
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(EmulatorNotice.STREAM_RECOVERING, client.view?.notice)
        for (attempt in 1 until EMULATOR_STREAM_RETRIES) {
            scope.testScheduler.advanceTimeBy(EMULATOR_STREAM_BACKOFF_MS * attempt + 10); scope.runCurrent()
            assertEquals("attempt $attempt", EmulatorNotice.STREAM_RECOVERING, client.view?.notice)
        }
        scope.testScheduler.advanceTimeBy(EMULATOR_STREAM_BACKOFF_MS * EMULATOR_STREAM_RETRIES + 10); scope.runCurrent()
        assertEquals(EmulatorNotice.STREAM_FAILED, client.view?.notice)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun aRefusedRefreshNeverReplacesTheUncertainNotice() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.controlling(host, closed)
        assertTrue(client.input(EmulatorInput.Tap(1, 1))); scope.runCurrent()
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view?.notice)
        host.statusFailure = { RemoteRequestException("forbidden") }
        assertTrue(client.refresh()); scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view?.notice)
    }

    @Test fun aMalformedOrMismatchedFrameEndsOnlyTheStream() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.opened(host, closed)
        client.frame(JsonObject(frame(host) + ("displayWidth" to kotlinx.serialization.json.JsonPrimitive(3)))); scope.runCurrent()
        assertEquals(EmulatorNotice.STREAM_RECOVERING, client.view?.notice)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun aTransportFailureOfTheWatchStillClosesTheConnection() {
        val scope = TestScope(); val host = Host().apply { watchFailure = { RemoteRequestException.connectionLost() } }; val closed = mutableListOf<Long>()
        scope.opened(host, closed)
        assertEquals(listOf(1L), closed)
    }

    @Test fun aStreamFailureWithAnInputInFlightIsUncertainNeverReplayedAndNeverReacquired() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.controlling(host, closed)
        assertTrue(client.input(EmulatorInput.Tap(1, 1))); scope.runCurrent()
        assertTrue(requireNotNull(client.view).inputPending)
        scope.testScheduler.advanceTimeBy(5_600); scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view?.notice)
        assertFalse(requireNotNull(client.view).ownsControl)
        assertEquals(1, host.count("emulator.release"))
        // The late reply of the abandoned input changes nothing and sends nothing.
        host.inputs.single().complete(ack(host, 0)); scope.runCurrent()
        scope.testScheduler.advanceTimeBy(EMULATOR_STREAM_BACKOFF_MS + 10); scope.runCurrent()
        client.frame(frame(host)); scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view?.notice)
        assertFalse(client.input(EmulatorInput.Tap(1, 1)))
        // Retry re-opens as a reader: no acquire, no input.
        assertTrue(client.retry()); scope.runCurrent()
        assertEquals(2, host.count("emulator.open"))
        assertNull(client.view?.notice)
        assertEquals(1, host.count("emulator.acquire"))
        assertEquals(1, host.count("emulator.input"))
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun aNewerInputReplacesTheQueuedOneAndOnlyOneIsSentAfterTheAck() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.controlling(host, closed)
        assertTrue(client.input(EmulatorInput.Tap(0, 0))); scope.runCurrent()
        assertTrue(client.input(EmulatorInput.Tap(1, 1)))
        assertTrue(client.input(EmulatorInput.Key("Home"))); scope.runCurrent()
        assertEquals(1, host.count("emulator.input"))
        host.inputs[0].complete(ack(host, 0)); scope.runCurrent()
        assertEquals(2, host.count("emulator.input"))
        assertTrue(host.inputsSent().last().contains("Home"))
        assertTrue(requireNotNull(client.view).inputPending)
        host.inputs[1].complete(ack(host, 1)); scope.runCurrent()
        assertEquals(2, host.count("emulator.input"))
        assertFalse(requireNotNull(client.view).inputPending)
    }

    @Test fun aQueuedInputIsDroppedWhenTheGenerationChanged() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.controlling(host, closed)
        assertTrue(client.input(EmulatorInput.Tap(0, 0))); scope.runCurrent()
        assertTrue(client.input(EmulatorInput.Tap(1, 1)))
        // The emulator moved on to a new generation while the first input was in flight.
        host.generation++
        assertTrue(client.refresh()); scope.runCurrent()
        host.inputs[0].complete(Wire.objectOf("kind" to "emulator.input.ack", "emulatorId" to "e", "generation" to host.generation - 1, "sequence" to 0, "status" to "accepted"))
        scope.runCurrent()
        assertEquals(1, host.count("emulator.input"))
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun hidingKeepsTheLeaseAndClosingDetaches() {
        val scope = TestScope(); val host = Host(); val closed = mutableListOf<Long>()
        val client = scope.controlling(host, closed)
        val unwatched = host.count("emulator.unwatch")
        client.setVisible(false); scope.runCurrent()
        assertTrue(requireNotNull(client.view).ownsControl)
        assertEquals(0, host.count("emulator.release") + host.count("emulator.detach"))
        assertEquals(unwatched + 1, host.count("emulator.unwatch"))
        client.setVisible(true); scope.runCurrent()
        assertEquals(3, host.count("emulator.watch"))
        client.close(); scope.runCurrent()
        assertNull(client.view)
        assertEquals(1, host.count("emulator.detach"))
        assertEquals(emptyList<Long>(), closed)
    }
}
