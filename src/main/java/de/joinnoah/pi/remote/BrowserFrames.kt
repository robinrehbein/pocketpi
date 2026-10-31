package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal const val BROWSER_FRAME_MAX_BYTES = 1048576
internal const val BROWSER_FRAME_CHUNK_BYTES = 49152
internal const val BROWSER_FRAME_MAX_PIXELS = 8388608
private const val FRAME_SAFE_INTEGER = 9007199254740991L

internal data class BrowserFrameMetadata(val width: Int, val height: Int, val cssWidth: Int, val cssHeight: Int, val deviceScale: Double, val scrollX: Double, val scrollY: Double)
internal data class BrowserFrameChunk(val browserId: String, val tabId: String, val streamId: String, val generation: Long, val viewportRevision: Long, val frameId: Long, val chunkIndex: Int, val chunkCount: Int, val totalBytes: Int, val metadata: BrowserFrameMetadata, val bytes: ByteArray)
internal data class BrowserFrame(val frameId: Long, val metadata: BrowserFrameMetadata, val jpeg: ByteArray)
internal data class BrowserFrameReceipt(val frameId: Long, val chunkIndex: Int, val frame: BrowserFrame?)

private fun JsonObject.frameString(key: String): String {
    val p = getValue(key).jsonPrimitive
    require(p.isString)
    return p.content
}
private fun JsonObject.frameId(key: String): String = frameString(key).also { value ->
    require(value.isNotEmpty() && Wire.fitsUtf8(value, 256))
    var index = 0
    while (index < value.length) {
        val c = value[index++]
        require(!c.isWhitespace() && !Character.isSpaceChar(c) && c.code !in 0..31 && c.code !in 127..159 && c != '\uFEFF')
        if (c.isHighSurrogate()) { require(index < value.length && value[index++].isLowSurrogate()) }
        else require(!c.isLowSurrogate())
    }
}
private fun JsonObject.frameNumber(key: String, min: Double, max: Double): Double {
    val p = getValue(key).jsonPrimitive
    require(!p.isString && p !is JsonNull)
    val value = p.content.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid frame number")
    require(value.isFinite() && value in min..max)
    return value
}
private fun JsonObject.frameCount(key: String, min: Long = 0, max: Long = FRAME_SAFE_INTEGER): Long =
    frameNumber(key, min.toDouble(), max.toDouble()).also { require(it % 1.0 == 0.0) }.toLong()

/** Strict wire validation precedes decoding/allocation. JPEG decoding is a separate bounded step. */
internal fun parseBrowserFrame(o: JsonObject): BrowserFrameChunk = frameValidation {
    Wire.keys(o, setOf("type", "kind", "browserId", "tabId", "streamId", "generation", "viewportRevision", "frameId", "chunkIndex", "chunkCount", "totalBytes", "width", "height", "cssWidth", "cssHeight", "deviceScale", "scrollX", "scrollY", "data"))
    require(o.frameString("type") == "host.event" && o.frameString("kind") == "browser.frame")
    val browser = o.frameId("browserId"); val tab = o.frameId("tabId"); val stream = o.frameId("streamId")
    val generation = o.frameCount("generation"); val revision = o.frameCount("viewportRevision")
    val frame = o.frameCount("frameId", 1)
    val total = o.frameCount("totalBytes", 1, BROWSER_FRAME_MAX_BYTES.toLong()).toInt()
    val count = o.frameCount("chunkCount", 1, 22).toInt()
    val index = o.frameCount("chunkIndex", 0, (count - 1).toLong()).toInt()
    require(count == (total + BROWSER_FRAME_CHUNK_BYTES - 1) / BROWSER_FRAME_CHUNK_BYTES)
    val width = o.frameCount("width", 1, 6144).toInt(); val height = o.frameCount("height", 1, 6144).toInt()
    require(width.toLong() * height <= BROWSER_FRAME_MAX_PIXELS)
    val metadata = BrowserFrameMetadata(width, height, o.frameCount("cssWidth", 80, 2048).toInt(), o.frameCount("cssHeight", 80, 2048).toInt(), o.frameNumber("deviceScale", 0.5, 3.0), o.frameNumber("scrollX", -10000000.0, 10000000.0), o.frameNumber("scrollY", -10000000.0, 10000000.0))
    val expectedBytes = minOf(BROWSER_FRAME_CHUNK_BYTES, total - index * BROWSER_FRAME_CHUNK_BYTES)
    val data = o.frameString("data")
    require(data.length == 4 * ((expectedBytes + 2) / 3))
    val bytes = Base64.getDecoder().decode(data)
    require(bytes.size == expectedBytes && Base64.getEncoder().encodeToString(bytes) == data)
    BrowserFrameChunk(browser, tab, stream, generation, revision, frame, index, count, total, metadata, bytes)
}
private inline fun <T> frameValidation(block: () -> T): T = try { block() } catch (e: Exception) {
    throw IllegalArgumentException("Invalid browser frame protocol", e)
}
internal fun validateBrowserStreamCommand(o: JsonObject): Unit = frameValidation {
    val base = setOf("type", "requestId", "sessionId", "browserId")
    when (o.frameString("type")) {
        "browser.watch" -> {
            Wire.keys(o, base + setOf("tabId", "generation", "viewportRevision"), setOf("recovery"))
            if ("recovery" in o) o.flag("recovery")
            o.frameId("tabId"); o.frameCount("generation"); o.frameCount("viewportRevision")
        }
        "browser.frame.ack" -> {
            Wire.keys(o, base + setOf("streamId", "frameId", "chunkIndex"))
            o.frameId("streamId"); o.frameCount("frameId", 1); o.frameCount("chunkIndex", 0, 21)
        }
        "browser.unwatch" -> { Wire.keys(o, base + "streamId"); o.frameId("streamId") }
        else -> throw IllegalArgumentException("Unknown browser stream command")
    }
    o.frameId("requestId"); o.frameId("sessionId"); o.frameId("browserId"); Unit
}
internal data class BrowserWaiting(val browserId: String, val tabId: String, val streamId: String, val generation: Long, val viewportRevision: Long)
internal fun parseBrowserWaiting(o: JsonObject): BrowserWaiting = frameValidation {
    Wire.keys(o, setOf("type", "kind", "browserId", "tabId", "streamId", "generation", "viewportRevision", "reason"))
    require(o.frameString("type") == "host.event" && o.frameString("kind") == "browser.frame.waiting" && o.frameString("reason") == "moving")
    BrowserWaiting(o.frameId("browserId"), o.frameId("tabId"), o.frameId("streamId"), o.frameCount("generation"), o.frameCount("viewportRevision"))
}
internal fun validateBrowserStreamResult(o: JsonObject): Unit = frameValidation {
    when (o.frameString("kind")) {
        "browser.watching" -> { Wire.keys(o, setOf("kind", "browserId", "streamId", "generation", "viewportRevision")); o.frameCount("generation"); o.frameCount("viewportRevision") }
        "browser.unwatched" -> Wire.keys(o, setOf("kind", "browserId", "streamId"))
        else -> throw IllegalArgumentException("Unknown browser stream result")
    }
    o.frameId("browserId"); o.frameId("streamId"); Unit
}

/** One incomplete JPEG, exact sequence and a fixed deadline. Invalid input closes this stream. */
internal class BrowserFrameAssembler(private val browserId: String, private val tabId: String, private val streamId: String, private val generation: Long, private val viewportRevision: Long, private val now: () -> Long, private val scope: CoroutineScope) {
    private var closed = false
    private var frameId = 1L
    private var nextChunk = 0
    private var first: BrowserFrameChunk? = null
    private var buffer: ByteArray? = null
    private var offset = 0
    private var started = 0L
    private var expiry: Job? = null
    private val lifetime = scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    val retainedBytes: Int @Synchronized get() = buffer?.size ?: 0

    @Synchronized
    fun accept(payload: JsonObject): BrowserFrameReceipt = try {
        require(!closed && scope.isActive)
        val chunk = parseBrowserFrame(payload)
        require(chunk.browserId == browserId && chunk.tabId == tabId && chunk.streamId == streamId && chunk.generation == generation && chunk.viewportRevision == viewportRevision)
        require(chunk.frameId == frameId && chunk.chunkIndex == nextChunk)
        if (nextChunk == 0) {
            first = chunk.copy(bytes = byteArrayOf())
            buffer = ByteArray(chunk.totalBytes)
        } else {
            val head = requireNotNull(first)
            require(now() - started in 0..5000L)
            require(chunk.metadata == head.metadata && chunk.totalBytes == head.totalBytes && chunk.chunkCount == head.chunkCount)
        }
        val bytes = requireNotNull(buffer)
        require(offset + chunk.bytes.size <= bytes.size)
        chunk.bytes.copyInto(bytes, offset); offset += chunk.bytes.size; nextChunk++
        started = now()
        expiry?.cancel()
        val completed = if (nextChunk == chunk.chunkCount) {
            require(offset == bytes.size)
            BrowserFrame(frameId, chunk.metadata, bytes).also { frameId++; nextChunk = 0; offset = 0; first = null; buffer = null }
        } else null
        if (completed == null) expiry = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(5000)
            close()
        }
        BrowserFrameReceipt(chunk.frameId, chunk.chunkIndex, completed)
    } catch (error: Exception) {
        close()
        throw IllegalArgumentException("Browser frame stream stopped", error)
    }
    @Synchronized
    fun close() { closed = true; expiry?.cancel(); expiry = null; lifetime?.dispose(); first = null; buffer = null; offset = 0 }
}
