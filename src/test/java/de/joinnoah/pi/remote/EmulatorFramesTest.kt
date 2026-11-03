package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EmulatorFramesTest {
    private val scope = TestScope()
    private fun png(width: Int = 2, height: Int = 3): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, width.toByte(), 0, 0, 0, height.toByte(), 8, 6, 0, 0, 0, 0, 0, 0, 0,
    )
    private fun event(bytes: ByteArray = png(), stream: String = "stream", width: Int = 2): JsonObject = buildJsonObject {
        put("type", "host.event"); put("kind", "emulator.frame"); put("emulatorId", "emu")
        put("incarnation", "inc"); put("streamId", stream); put("generation", 2); put("geometryRevision", 3)
        put("frameId", 1); put("chunkIndex", 0); put("chunkCount", 1); put("totalBytes", bytes.size)
        put("width", width); put("height", 3); put("displayWidth", 2); put("displayHeight", 3); put("rotation", 0)
        put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }
    private fun receiver() = EmulatorFrameAssembler("emu", "inc", "stream", 2, 3, { 0L }, scope.backgroundScope)
    @Test fun completePngMustMatchItsAdvertisedDimensions() {
        assertArrayEquals(png(), receiver().accept(event()).frame!!.png)
        try { receiver().accept(event(width = 4)); fail("mismatched IHDR accepted") } catch (_: IllegalArgumentException) { }
        try { receiver().accept(event(bytes = ByteArray(25))); fail("invalid PNG accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsChangedIncarnationAndGeneration() {
        for (field in listOf("incarnation" to JsonPrimitive("new"), "generation" to JsonPrimitive(3))) {
            val changed = JsonObject(event() + field)
            try { receiver().accept(changed); fail("stale context accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectedFrameClosesStream() {
        val receiver = receiver()
        try { receiver.accept(event(bytes = ByteArray(25))) } catch (_: IllegalArgumentException) { }
        try { receiver.accept(event()); fail("closed stream accepted another frame") } catch (_: IllegalArgumentException) { }
    }
    @Test fun acceptsSinglePixelScalingRoundingButRejectsDistortedImage() {
        val rounded = JsonObject(event() + mapOf("displayWidth" to JsonPrimitive(3), "displayHeight" to JsonPrimitive(4)))
        assertNotNull(receiver().accept(rounded).frame)
        val distorted = JsonObject(event() + mapOf("displayWidth" to JsonPrimitive(10), "displayHeight" to JsonPrimitive(3)))
        try { receiver().accept(distorted); fail("distorted frame accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsStaleStreamBeforeAcknowledgement() {
        try { receiver().accept(event(stream = "old")); fail("stale stream accepted") } catch (_: IllegalArgumentException) { }
    }
}
