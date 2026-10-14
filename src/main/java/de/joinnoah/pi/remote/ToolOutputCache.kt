package de.joinnoah.pi.remote

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Completed tool outputs of this process, least recently used first out, bounded by the bytes they
 * hold. A completed output never changes on the Mac, so a kept one needs no request again. Nothing
 * here is written to disk.
 */
internal class ToolOutputCache(private val capacity: Long = 8L * 1024 * 1024) {
    data class Key(val routeId: String, val sessionId: String, val toolCallId: String)

    private val entries = LinkedHashMap<Key, ToolOutputDownload>(16, 0.75f, true)
    private var size = 0L

    @Synchronized operator fun get(key: Key): ToolOutputDownload? = entries[key]

    /** Keeps [value] when it is a complete output that fits; anything else is ignored. */
    @Synchronized
    operator fun set(key: Key, value: ToolOutputDownload) {
        if (value.text == null || value.failure != null) return
        val cost = value.loadedBytes
        if (cost > capacity) return
        entries.put(key, value)?.let { size -= it.loadedBytes }
        size += cost
        val iterator = entries.entries.iterator()
        while (size > capacity && iterator.hasNext()) {
            size -= iterator.next().value.loadedBytes
            iterator.remove()
        }
    }

    /** Forgets the outputs of [routeId]: its Mac was unpaired. */
    @Synchronized
    fun clearRoute(routeId: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.routeId == routeId) {
                size -= entry.value.loadedBytes
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        size = 0
    }
}

/**
 * A download of one tool output, running or stopped part way: the bytes so far and the offset the
 * next chunk starts at. A stopped download keeps these, so loading the output again resumes there.
 */
internal class ToolOutputProgress(val key: ToolOutputCache.Key, private val toolCallId: String) {
    val text = ToolOutputText()
    var offset = 0L
    var total: Long? = null
    var truncated = false
    var isError = false

    /** What the output shows while it loads: the progress so far, and the text that arrived. */
    fun download() =
        ToolOutputDownload(toolCallId, offset, total, null, truncated, isError, null, text.preview())
}

/**
 * The text of a tool output while its chunks arrive. Chunks split UTF-8 sequences anywhere, so a
 * sequence that is still open waits for the next chunk: the text shown so far never ends in a broken
 * character, and the finished text equals `String(bytes, UTF_8)`.
 */
internal class ToolOutputText {
    private val decoder: CharsetDecoder =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val text = StringBuilder()
    private var carry = ByteArray(0)

    /** The text decoded so far, without an open sequence at its end. */
    fun preview(): String = text.toString()

    fun append(bytes: ByteArray) = decode(bytes, endOfInput = false)

    /** The whole text; a sequence still open now is malformed and reads as replacement characters. */
    fun finish(): String {
        decode(ByteArray(0), endOfInput = true)
        val output = CharBuffer.allocate(4)
        while (decoder.flush(output).isOverflow) {
            output.flip()
            text.append(output)
            output.clear()
        }
        output.flip()
        text.append(output)
        return text.toString()
    }

    private fun decode(bytes: ByteArray, endOfInput: Boolean) {
        // flip() is called as a statement: its return type differs between JDK and Android APIs.
        val input = ByteBuffer.allocate(carry.size + bytes.size)
        input.put(carry)
        input.put(bytes)
        input.flip()
        val output = CharBuffer.allocate(maxOf(16, input.remaining()))
        while (true) {
            val result = decoder.decode(input, output, endOfInput)
            output.flip()
            text.append(output)
            output.clear()
            if (result.isUnderflow) break
        }
        carry = ByteArray(input.remaining()).also { input.get(it) }
    }
}
