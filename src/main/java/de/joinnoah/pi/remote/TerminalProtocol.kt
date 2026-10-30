package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject

/** Only offered on the capabilities.v2 route; no terminal UI is enabled by this declaration. */
internal const val SESSION_TERMINAL_CAPABILITY = "session.terminal.v1"
private const val MAX_SAFE_COUNT = 9_007_199_254_740_991L
private val DETACH_REASONS = setOf("detached", "revoked", "slow", "exit", "forbidden", "unsupported", "offline", "idle")

internal sealed interface TerminalResult {
    data class Attached(val sessionId: String, val generation: Long, val cols: Int, val rows: Int) : TerminalResult
    data class Held(val sessionId: String, val generation: Long, val holder: String) : TerminalResult
}
internal sealed interface TerminalEvent {
    val sessionId: String
    val generation: Long
    data class Data(override val sessionId: String, override val generation: Long, val seq: Long, val text: String) : TerminalEvent
    data class Detached(override val sessionId: String, override val generation: Long, val reason: String, val exitCode: Int?) : TerminalEvent
}

private fun terminalId(value: String): String {
    require(value.isNotEmpty() && Wire.fitsUtf8(value, 256) && value.none { it.code <= 32 || it.code == 127 })
    return value
}
private fun JsonObject.terminalCount(key: String): Long = long(key).also { require(it in 0..MAX_SAFE_COUNT) }
private fun scalarText(value: String, maxBytes: Int): Boolean {
    if (value.isEmpty() || !Wire.fitsUtf8(value, maxBytes)) return false
    var i = 0
    while (i < value.length) {
        val ch = value[i++]
        if (ch.isHighSurrogate()) {
            if (i == value.length || !value[i].isLowSurrogate()) return false
            i++
        } else if (ch.isLowSurrogate()) return false
    }
    return true
}

internal fun parseTerminalResult(data: JsonObject, expectedSession: String): TerminalResult {
    val kind = data.text("kind")
    when (kind) {
        "terminal.attached" -> Wire.keys(data, setOf("kind", "sessionId", "attachmentGeneration", "cols", "rows"))
        "terminal.held" -> Wire.keys(data, setOf("kind", "sessionId", "attachmentGeneration", "holder"))
        else -> error("Unknown terminal result")
    }
    val session = terminalId(data.text("sessionId"))
    require(session == expectedSession)
    val generation = data.terminalCount("attachmentGeneration")
    return if (kind == "terminal.attached") {
        val cols = data.long("cols").also { require(it in 20..500) }.toInt()
        val rows = data.long("rows").also { require(it in 5..300) }.toInt()
        TerminalResult.Attached(session, generation, cols, rows)
    } else {
        val holder = data.text("holder").also { require(it in setOf("mac", "phone")) }
        TerminalResult.Held(session, generation, holder)
    }
}

internal fun parseTerminalEvent(payload: JsonObject): TerminalEvent {
    require(payload.text("type") == "host.event")
    val kind = payload.text("kind")
    val base = setOf("type", "kind", "sessionId", "attachmentGeneration")
    when (kind) {
        "session.terminal.data" -> Wire.keys(payload, base + setOf("seq", "text"))
        "session.terminal.detached" -> Wire.keys(payload, base + "reason", setOf("exitCode"))
        else -> error("Unknown terminal event")
    }
    val session = terminalId(payload.text("sessionId"))
    val generation = payload.terminalCount("attachmentGeneration")
    return if (kind == "session.terminal.data") {
        val text = payload.text("text").also { require(scalarText(it, 16_384)) }
        TerminalEvent.Data(session, generation, payload.terminalCount("seq"), text)
    } else {
        val reason = payload.text("reason").also { require(it in DETACH_REASONS) }
        val exitCode = if ("exitCode" in payload) {
            require(reason == "exit")
            payload.long("exitCode").also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
        } else null
        TerminalEvent.Detached(session, generation, reason, exitCode)
    }
}

/** Connection-local state; callers deliver accepted data directly and must never buffer input for replay. */
internal class TerminalAttachment(private val connection: String, private val session: String, private val generation: Long) {
    var active = true
        private set
    private var nextOutput = 0L
    private var nextInput = 0L

    fun receive(connection: String, event: TerminalEvent): Boolean {
        if (!active || connection != this.connection || event.sessionId != session || event.generation != generation) return false
        if (event is TerminalEvent.Detached) { active = false; return true }
        event as TerminalEvent.Data
        // Output is ordered and lossless. A gap or replay invalidates the terminal rather than rendering a corrupt stream.
        if (event.seq != nextOutput || nextOutput == MAX_SAFE_COUNT) { active = false; return false }
        nextOutput++
        return true
    }

    /** Reserves once; a failed or uncertain send must not be resent automatically. */
    fun nextInputSequence(connection: String): Long? {
        if (!active || connection != this.connection || nextInput > MAX_SAFE_COUNT) return null
        return nextInput++
    }

    fun disconnect(connection: String) { if (connection == this.connection) active = false }
}

/** Bounded output is drained by the eventual terminal renderer in order. */
data class TerminalView(
    val sessionId: String,
    val attaching: Boolean = true,
    val inputPending: Boolean = false,
    val generation: Long? = null,
    val holder: String? = null,
    val output: List<String> = emptyList(),
    val ended: String? = null,
    val exitCode: Int? = null,
)

sealed interface TerminalInput {
    data class Text(val text: String) : TerminalInput
    data class Paste(val text: String) : TerminalInput
    data class Key(val name: String, val repeat: Int = 1) : TerminalInput
}

internal fun terminalInputField(input: TerminalInput): Pair<String, Any?>? = when (input) {
    is TerminalInput.Text -> input.text.takeIf { scalarText(it, 4096) && it.none { c -> c.code < 32 || c.code in 127..159 } }?.let { "text" to it }
    is TerminalInput.Paste -> input.text.takeIf { scalarText(it, 16384) && it.none { c -> (c.code < 32 && c != '\n') || c.code in 127..159 } }?.let { "paste" to it }
    is TerminalInput.Key -> {
        val keys = setOf("enter", "escape", "up", "down", "left", "right", "tab", "shift_tab", "home", "end", "page_up", "page_down", "backspace", "delete")
        val repeatable = keys - setOf("enter", "escape", "home", "end")
        if (input.name !in keys || input.repeat !in 1..16 || (input.repeat > 1 && input.name !in repeatable)) null
        else "key" to Wire.objectOf("name" to input.name, "repeat" to input.repeat)
    }
}
