package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class BrowserFramesTest {
    private val scope = TestScope()
    private fun chunk(index: Int = 0, frame: Long = 1, total: Int = 3): JsonObject = buildJsonObject {
        put("type", "host.event"); put("kind", "browser.frame")
        put("browserId", "browser"); put("tabId", "tab"); put("streamId", "stream")
        put("generation", 2); put("viewportRevision", 3); put("frameId", frame)
        put("chunkIndex", index); put("chunkCount", (total + 49151) / 49152); put("totalBytes", total)
        put("width", 390); put("height", 844); put("cssWidth", 390); put("cssHeight", 844)
        put("deviceScale", 1); put("scrollX", 0); put("scrollY", 0)
        put("data", Base64.getEncoder().encodeToString(ByteArray(minOf(49152, total - index * 49152)) { 1 }))
    }
    private fun changed(o: JsonObject, key: String, value: JsonElement) = JsonObject(o + (key to value))
    private fun assembler(now: () -> Long = { 0L }) = BrowserFrameAssembler("browser", "tab", "stream", 2, 3, now, scope.backgroundScope)
    private fun rejected(block: () -> Unit) { try { block(); fail("accepted malformed frame") } catch (_: IllegalArgumentException) {} }

    @Test fun orderedChunksRetainOnlyOneBoundedFrameAndAckEveryChunk() {
        val receiver = assembler()
        val first = receiver.accept(chunk(total = 49153))
        assertNull(first.frame)
        assertEquals(0, first.chunkIndex)
        val last = receiver.accept(chunk(index = 1, total = 49153))
        assertEquals(49153, last.frame!!.jpeg.size)
        assertEquals(1, last.chunkIndex)
        assertEquals(0, receiver.retainedBytes)
        assertEquals(2L, receiver.accept(chunk(frame = 2)).frame!!.frameId)
    }
    @Test fun rejectsWrongStreamDuplicateMissingAndMixedMetadataAndRemainsStopped() {
        for (bad in listOf(
            changed(chunk(), "streamId", JsonPrimitive("other")),
            changed(chunk(), "frameId", JsonPrimitive(2)),
            changed(chunk(), "generation", JsonPrimitive(4)),
            changed(chunk(), "viewportRevision", JsonPrimitive(4)),
        )) {
            val receiver = assembler()
            rejected { receiver.accept(bad) }
            rejected { receiver.accept(chunk()) }
        }
        val receiver = assembler()
        receiver.accept(chunk(total = 49153))
        rejected { receiver.accept(changed(chunk(index = 1, total = 49153), "scrollY", JsonPrimitive(1))) }
        assertEquals(0, receiver.retainedBytes)
        val duplicate = assembler()
        duplicate.accept(chunk())
        rejected { duplicate.accept(chunk()) }
    }
    @Test fun expiryAndExplicitCloseDropPartialBytes() {
        var now = 0L
        val receiver = assembler { now }
        receiver.accept(chunk(total = 49153))
        now = 5001
        rejected { receiver.accept(chunk(index = 1, total = 49153)) }
        assertEquals(0, receiver.retainedBytes)
        val closed = assembler()
        closed.accept(chunk(total = 49153)); closed.close()
        assertEquals(0, closed.retainedBytes)
        rejected { closed.accept(chunk(index = 1, total = 49153)) }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun inactivePartialFrameExpiresWithoutAnotherChunk() {
        val receiver = assembler()
        receiver.accept(chunk(total = 49153))
        scope.advanceTimeBy(5001); scope.runCurrent()
        assertEquals(0, receiver.retainedBytes)
        rejected { receiver.accept(chunk(index = 1, total = 49153)) }
    }
    @Test fun deadlineTracksChunkInactivityRatherThanWholeJpegDuration() {
        var now = 0L
        val receiver = assembler { now }
        receiver.accept(chunk(total = 98305))
        now = 4000; receiver.accept(chunk(index = 1, total = 98305))
        now = 8000; assertNotNull(receiver.accept(chunk(index = 2, total = 98305)).frame)
    }
    @Test fun scopeCompletionCannotLeaveBytesAllocatedByAnInFlightChunk() {
        val receiver = assembler()
        val reading = CountDownLatch(1); val resume = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val base = chunk(total = 49153)
        val delayed = JsonObject(object : Map<String, JsonElement> by base {
            override fun get(key: String): JsonElement? {
                if (key == "data") { reading.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
                return base[key]
            }
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val receiving = workers.submit { receiver.accept(delayed) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val closing = workers.submit { stopping.countDown(); scope.backgroundScope.cancel() }
            assertTrue(stopping.await(5, TimeUnit.SECONDS))
            try { closing.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* close waits for receive */ }
            resume.countDown()
            receiving.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS)
            assertEquals(0, receiver.retainedBytes)
        } finally { resume.countDown(); workers.shutdownNow(); receiver.close() }
    }
    @Test fun strictParserRejectsOversizePixelsNoncanonicalBase64AndUnknownFields() {
        for (bad in listOf(
            changed(chunk(), "totalBytes", JsonPrimitive(1048577)),
            changed(changed(chunk(), "width", JsonPrimitive(6144)), "height", JsonPrimitive(6144)),
            changed(chunk(), "data", JsonPrimitive("AQE=")),
            changed(chunk(), "data", JsonPrimitive("AQEB\n")),
            changed(chunk(), "extra", JsonPrimitive(true)),
            changed(chunk(), "frameId", JsonPrimitive("1")),
        )) rejected { parseBrowserFrame(bad) }
    }
    @Test fun commonHostFixtureRemainsAcceptedOrRejectedExactly() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/browser-stream-v1.json")!!.readText()).jsonObject
        for (item in fixture.getValue("valid").jsonArray) {
            val payload = item.jsonObject.getValue("payload").jsonObject
            when (payload.optionalText("type")) {
                "host.event" -> parseBrowserFrame(payload)
                "browser.watch", "browser.frame.ack", "browser.unwatch" -> validateBrowserStreamCommand(payload)
                else -> validateBrowserStreamResult(payload.getValue("data").jsonObject)
            }
        }
        for (item in fixture.getValue("invalid").jsonArray) {
            val payload = item.jsonObject.getValue("payload").jsonObject
            rejected {
                when (payload.optionalText("type")) {
                    "host.event" -> parseBrowserFrame(payload)
                    "browser.watch", "browser.frame.ack", "browser.unwatch" -> validateBrowserStreamCommand(payload)
                    else -> validateBrowserStreamResult(payload.getValue("data").jsonObject)
                }
            }
        }
    }
}
