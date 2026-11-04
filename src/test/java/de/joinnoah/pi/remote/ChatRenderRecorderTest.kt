package de.joinnoah.pi.remote

import android.content.Intent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ChatRenderRecorderTest {
    private fun ChatRenderRecorder.report(
        options: ChatRenderingDiagnostics.Options = ChatRenderingDiagnostics.Options(),
    ): JsonObject = Json.parseToJsonElement(export("Test model", 35, 123, 2.5f, 1.25f, options)).jsonObject

    private fun JsonObject.frames() = getValue("frames").jsonArray
    private fun JsonElement.rows() = jsonObject.getValue("rows").jsonArray
    private fun slot(key: Any, index: Int = 0, type: String = "bubble") =
        ChatRenderRecorder.Slot(key, index, -12, 48, type)

    @Test fun injectedClockAndAllReportFieldsAreExportedAsTypedJson() {
        var now = 900L
        val recorder = ChatRenderRecorder { now }
        recorder.position("key", ChatRenderRecorder.Bounds(-1, 2, 300, 400))
        recorder.record(-20, 800, true, false, 17, listOf(slot("key", 6)))
        now = 937L
        recorder.record(0, 900, false, true, 18, emptyList(), scrolling = true, canScrollForward = true)
        val report = recorder.report(ChatRenderingDiagnostics.Options(recording = true, disableGlass = true, disableMotion = true))
        assertEquals(setOf("format", "deviceModel", "sdk", "versionCode", "density", "fontScale", "glassDisabled", "motionDisabled", "lastDrawAgeMs", "frames"), report.keys)
        assertEquals("pocketpi-chat-render-v1", report.getValue("format").jsonPrimitive.content)
        assertEquals("Test model", report.getValue("deviceModel").jsonPrimitive.content)
        assertEquals(35, report.getValue("sdk").jsonPrimitive.int)
        assertEquals(123, report.getValue("versionCode").jsonPrimitive.int)
        assertEquals(2.5f, report.getValue("density").jsonPrimitive.float, 0f)
        assertEquals(1.25f, report.getValue("fontScale").jsonPrimitive.float, 0f)
        assertTrue(report.getValue("glassDisabled").jsonPrimitive.boolean)
        assertTrue(report.getValue("motionDisabled").jsonPrimitive.boolean)
        val first = report.frames()[0].jsonObject
        assertEquals(setOf("elapsedMs", "viewportStart", "viewportEnd", "following", "thinkingText", "scrolling", "canScrollForward", "itemCount", "rows"), first.keys)
        assertEquals(0L, first.getValue("elapsedMs").jsonPrimitive.long)
        assertEquals(37L, report.frames()[1].jsonObject.getValue("elapsedMs").jsonPrimitive.long)
        assertEquals(-20, first.getValue("viewportStart").jsonPrimitive.int)
        assertEquals(800, first.getValue("viewportEnd").jsonPrimitive.int)
        assertEquals(17, first.getValue("itemCount").jsonPrimitive.int)
        assertTrue(first.getValue("following").jsonPrimitive.boolean)
        assertFalse(first.getValue("thinkingText").jsonPrimitive.boolean)
        val second = report.frames()[1].jsonObject
        assertFalse(second.getValue("following").jsonPrimitive.boolean)
        assertTrue(second.getValue("thinkingText").jsonPrimitive.boolean)
        assertTrue(second.getValue("scrolling").jsonPrimitive.boolean)
        assertTrue(second.getValue("canScrollForward").jsonPrimitive.boolean)
        assertEquals(0L, report.getValue("lastDrawAgeMs").jsonPrimitive.long)
        val row = first.rows().single().jsonObject
        assertEquals(setOf("token", "index", "offset", "size", "type", "windowBounds"), row.keys)
        assertEquals(6, row.getValue("index").jsonPrimitive.int)
        assertEquals(-12, row.getValue("offset").jsonPrimitive.int)
        assertEquals(48, row.getValue("size").jsonPrimitive.int)
        assertEquals(listOf(-1, 2, 300, 400), row.getValue("windowBounds").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(recorder.report().getValue("glassDisabled").jsonPrimitive.boolean)
        assertFalse(recorder.report().getValue("motionDisabled").jsonPrimitive.boolean)
    }

    @Test fun retainsLatest24FramesAndFirst32RowsPerFrame() {
        var now = 0L
        val recorder = ChatRenderRecorder { now++ }
        repeat(30) { frame -> recorder.record(frame, frame + 1, false, false, 40, (0..39).map { slot("key-$it", it) }) }
        val frames = recorder.report().frames()
        assertEquals(24, frames.size)
        assertEquals(6, frames.first().jsonObject.getValue("viewportStart").jsonPrimitive.int)
        assertEquals(29, frames.last().jsonObject.getValue("viewportStart").jsonPrimitive.int)
        assertEquals(23L, frames.last().jsonObject.getValue("elapsedMs").jsonPrimitive.long)
        frames.forEach { frame ->
            assertEquals((0..31).toList(), frame.rows().map { it.jsonObject.getValue("index").jsonPrimitive.int })
        }
    }

    @Test fun rawKeysTextPathsAndUnknownContentTypesNeverAppearInReport() {
        val recorder = ChatRenderRecorder { 0L }
        val secrets = listOf("secret-message-body", "/Users/private/session/file.txt", "private-session-key")
        val opaqueKey = object { override fun toString() = "secret-object-text" }
        val types = listOf("bubble-user", "bubble", "thinking", "activity", "subagent", "secret-content-type")
        val keys = secrets + opaqueKey
        recorder.record(0, 100, false, false, 6, types.mapIndexed { index, type -> slot(keys[index % keys.size], index, type) })
        val text = recorder.export("Actual device model", 35, 1, 1f, 1f, ChatRenderingDiagnostics.Options())
        (secrets + listOf("secret-object-text", "secret-content-type")).forEach { assertFalse("Leaked $it", text.contains(it)) }
        assertEquals(types.take(5) + "other", Json.parseToJsonElement(text).jsonObject.frames().single().rows().map { it.jsonObject.getValue("type").jsonPrimitive.content })
    }

    @Test fun tokensAreStableWithinExportAndAssignedAnewAfterFrameEviction() {
        val recorder = ChatRenderRecorder { 0L }
        recorder.record(0, 100, false, false, 2, listOf(slot("a"), slot("b")))
        recorder.record(0, 100, false, false, 2, listOf(slot("b"), slot("a")))
        fun tokens(frame: JsonElement) = frame.rows().map { it.jsonObject.getValue("token").jsonPrimitive.int }
        val initial = recorder.report().frames()
        assertEquals(tokens(initial[0]).reversed(), tokens(initial[1]))
        assertEquals(2, tokens(initial[0]).distinct().size)
        val previousB = tokens(initial[0])[1]
        repeat(24) { recorder.record(0, 100, false, false, 1, listOf(slot("b"))) }
        val fresh = recorder.report().frames()
        assertEquals(1, tokens(fresh.first()).single())
        assertNotEquals(previousB, tokens(fresh.first()).single())
        assertTrue(fresh.all { tokens(it) == listOf(1) })
    }

    @Test fun boundsAreSnapshotsForMatchingVisibleRowsOnly() {
        val recorder = ChatRenderRecorder { 0L }
        recorder.position("visible", ChatRenderRecorder.Bounds(1, 2, 3, 4))
        recorder.position("hidden", ChatRenderRecorder.Bounds(90, 91, 92, 93))
        recorder.record(0, 100, false, false, 3, listOf(slot("visible"), slot("missing")))
        recorder.position("visible", ChatRenderRecorder.Bounds(5, 6, 7, 8))
        recorder.record(0, 100, false, false, 3, listOf(slot("visible")))
        val frames = recorder.report().frames()
        assertEquals(listOf(1, 2, 3, 4), frames[0].rows()[0].jsonObject.getValue("windowBounds").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(frames[0].rows()[1].jsonObject.containsKey("windowBounds"))
        assertEquals(listOf(5, 6, 7, 8), frames[1].rows()[0].jsonObject.getValue("windowBounds").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(2, frames[0].rows().size)
    }

    @Test fun clearRemovesFramesAndPreviouslyRecordedPositions() {
        val recorder = ChatRenderRecorder { 500L }
        recorder.position("key", ChatRenderRecorder.Bounds(1, 2, 3, 4))
        recorder.record(0, 100, false, false, 1, listOf(slot("key")))
        recorder.clear()
        assertTrue(recorder.report().frames().isEmpty())
        recorder.record(0, 100, false, false, 1, listOf(slot("key")))
        assertFalse(recorder.report().frames().single().rows().single().jsonObject.containsKey("windowBounds"))
    }

    @Test fun extremeNumericGeometryStillProducesValidJsonUnder256KiB() {
        var now = 0L
        val recorder = ChatRenderRecorder { now.also { now += Long.MAX_VALUE / 24 } }
        repeat(32) { recorder.position("key-$it", ChatRenderRecorder.Bounds(Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)) }
        repeat(24) {
            recorder.record(Int.MIN_VALUE, Int.MAX_VALUE, true, true, Int.MAX_VALUE,
                (0 until 32).map { index -> ChatRenderRecorder.Slot("key-$index", Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, "bubble-user") })
        }
        val text = recorder.export("A real device model", Int.MAX_VALUE, Int.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, ChatRenderingDiagnostics.Options(true, true, true))
        assertTrue("Report exceeds 256 KiB", text.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        val frames = Json.parseToJsonElement(text).jsonObject.frames()
        assertEquals(24, frames.size)
        assertTrue(frames.all { it.rows().size == 32 })
        assertEquals(Int.MIN_VALUE, frames.last().rows().last().jsonObject.getValue("index").jsonPrimitive.int)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRenderShareIntentTest {
    @Test fun shareIntentOnlyOffersPlainTextWithoutUploadDestinationOrAttachments() {
        val report = "{\"format\":\"pocketpi-chat-render-v1\",\"frames\":[]}"
        val intent = chatRenderShareIntent(report)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(report, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras!!.keySet())
        assertNull(intent.data)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        intent.clipData?.let { clip -> repeat(clip.itemCount) { assertNull(clip.getItemAt(it).uri) } }
    }
}
