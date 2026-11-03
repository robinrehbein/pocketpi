package de.joinnoah.pi.remote

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

internal data class EmulatorFrame(val frameId: Long, val metadata: EmulatorFrameEvent, val png: ByteArray)
internal data class EmulatorFrameReceipt(val frameId: Long, val chunkIndex: Long, val frame: EmulatorFrame?)

/** Retains one bounded frame; invalid or out-of-order data terminates the stream. */
internal class EmulatorFrameAssembler(
    private val emulatorId: String, private val incarnation: String, private val streamId: String,
    private val generation: Long, private val revision: Long, private val now: () -> Long,
    private val scope: CoroutineScope,
) {
    private var closed = false
    private var nextFrame = 1L
    private var nextChunk = 0L
    private var first: EmulatorFrameEvent? = null
    private var buffer: ByteArray? = null
    private var offset = 0
    private var started = 0L
    private var expiry: Job? = null
    private val lifetime = scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    val retainedBytes: Int @Synchronized get() = buffer?.size ?: 0

    @Synchronized fun accept(payload: JsonObject): EmulatorFrameReceipt = try {
        check(!closed && scope.isActive)
        val chunk = parseEmulatorFrame(payload)
        require(chunk.emulatorId == emulatorId && chunk.incarnation == incarnation && chunk.streamId == streamId && chunk.generation == generation && chunk.geometryRevision == revision)
        require(chunk.frameId == nextFrame && chunk.chunkIndex == nextChunk)
        require(kotlin.math.abs(chunk.width * chunk.displayHeight - chunk.height * chunk.displayWidth) <= maxOf(chunk.displayWidth, chunk.displayHeight))
        if (nextChunk == 0L) {
            first = chunk.copy(data = "")
            buffer = ByteArray(chunk.totalBytes.toInt())
        } else {
            require(now() - started in 0..5000L)
            require(chunk.copy(chunkIndex = 0, data = "") == first)
        }
        val bytes = Base64.getUrlDecoder().decode(chunk.data)
        val target = requireNotNull(buffer)
        require(offset + bytes.size <= target.size)
        bytes.copyInto(target, offset); offset += bytes.size; nextChunk++; started = now()
        expiry?.cancel()
        val complete = if (nextChunk == chunk.chunkCount) {
            require(offset == target.size && validPng(target, chunk.width, chunk.height))
            EmulatorFrame(nextFrame, chunk, target).also { nextFrame++; nextChunk = 0; offset = 0; first = null; buffer = null }
        } else null
        if (complete == null) expiry = scope.launch(start = CoroutineStart.UNDISPATCHED) { delay(5000); close() }
        EmulatorFrameReceipt(chunk.frameId, chunk.chunkIndex, complete)
    } catch (e: Exception) { close(); throw IllegalArgumentException("Emulator frame stream stopped", e) }

    @Synchronized fun close() { closed = true; expiry?.cancel(); expiry = null; lifetime?.dispose(); first = null; buffer = null; offset = 0 }
}

private fun validPng(bytes: ByteArray, width: Long, height: Long): Boolean {
    if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return false
    if (!bytes.copyOfRange(8, 16).contentEquals(byteArrayOf(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52))) return false
    fun read(index: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[index + i].toLong() and 255) }
    return read(16) == width && read(20) == height
}
