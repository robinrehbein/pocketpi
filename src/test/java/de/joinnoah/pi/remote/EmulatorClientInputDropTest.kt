package de.joinnoah.pi.remote

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric decodes the frame's PNG, which input needs before it is allowed.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmulatorClientInputDropTest {
    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
    private fun frame(stream: String, generation: Int): JsonObject {
        val bytes = png()
        return Wire.objectOf("type" to "host.event", "kind" to "emulator.frame", "emulatorId" to "e", "incarnation" to "i", "streamId" to stream,
            "generation" to generation, "geometryRevision" to 1, "frameId" to 1, "chunkIndex" to 0, "chunkCount" to 1, "totalBytes" to bytes.size,
            "width" to 2, "height" to 3, "displayWidth" to 2, "displayHeight" to 3, "rotation" to 0, "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aDropWithAnInputInFlightStaysAReaderOnResume() {
        val scope = TestScope(); var connection = 1L; var generation = 1; var owner: JsonObject? = null
        val self = Wire.objectOf("kind" to "device", "id" to "connection")
        val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
        val calls = mutableListOf<Pair<Long, String>>(); val input = CompletableDeferred<JsonObject>()
        val client = EmulatorClient(scope.backgroundScope, { _, c -> c == connection }, { c, type, fields ->
            calls.add(c to type); val map = fields.toMap(); val o = owner?.let { arrayOf("owner" to it) } ?: emptyArray()
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to kotlinx.serialization.json.JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to generation, "geometry" to geometry, *o)
                "emulator.status" -> Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to generation, "geometry" to geometry, *o)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to generation, "participants" to kotlinx.serialization.json.JsonArray(emptyList()))
                "emulator.watch" -> Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to map["generation"], "streamId" to "stream${calls.count { it.second == "emulator.watch" }}")
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to map["streamId"])
                "emulator.frame.ack" -> Wire.objectOf("kind" to "accepted")
                "emulator.acquire" -> { generation++; owner = self
                    Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to generation, "geometry" to geometry, "owner" to self, "leaseId" to "lease", "nextSequence" to 0) }
                "emulator.input" -> input.await()
                else -> error("unexpected $type")
            }
        }, {}, { "d" }, { true }, {})
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        client.frame(frame("stream2", 2)); scope.runCurrent()
        assertNotNull(client.view?.image)
        assertTrue(client.input(EmulatorInput.Tap(0, 0))); scope.runCurrent()
        assertTrue(requireNotNull(client.view).inputPending)
        // Whether the tap landed is unknown: the resume must not take control again on its own.
        client.disconnected(); connection = 2; generation++; owner = null
        assertEquals("uncertain", client.view?.notice)
        assertTrue(client.resumeIfDisconnected("session", 2)); scope.runCurrent()
        assertEquals(0, calls.count { it.first == 2L && it.second == "emulator.acquire" })
        assertFalse(requireNotNull(client.view).ownsControl)
    }
}
