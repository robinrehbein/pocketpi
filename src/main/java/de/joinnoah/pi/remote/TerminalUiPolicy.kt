package de.joinnoah.pi.remote

internal enum class TerminalQueueAction { CLEAR_REJECTED, SEND, WAIT }

internal data class TerminalDraftQueue(val draft: String, val queue: List<TerminalInput>)
internal enum class TerminalEnqueueDecision { ACCEPTED, BUSY, UNAVAILABLE, INVALID }
internal data class TerminalEnqueueResult(val decision: TerminalEnqueueDecision, val state: TerminalDraftQueue)

/** Draft removal and queue reservation are one decision against the current attachment. */
internal fun terminalPrepareEnqueue(
    state: TerminalDraftQueue,
    rendered: TerminalView?,
    live: TerminalView?,
    seenRejections: Long,
    batch: List<TerminalInput>,
    consumesDraft: Boolean,
): TerminalEnqueueResult {
    fun refuse(reason: TerminalEnqueueDecision) = TerminalEnqueueResult(reason, state)
    if (rendered == null || live == null || rendered.sessionId != live.sessionId || rendered.generation == null ||
        rendered.generation != live.generation || live.attaching || live.ended != null || live.holder != null
    ) return refuse(TerminalEnqueueDecision.UNAVAILABLE)
    if (consumesDraft && (live.inputPending || state.queue.isNotEmpty() || live.inputRejected != seenRejections))
        return refuse(TerminalEnqueueDecision.BUSY)
    val queue = terminalQueueAfterEnqueue(state.queue, batch) ?: return refuse(TerminalEnqueueDecision.INVALID)
    return TerminalEnqueueResult(TerminalEnqueueDecision.ACCEPTED, TerminalDraftQueue(if (consumesDraft) "" else state.draft, queue))
}

/** A refusal wins over dispatch, including when an acknowledgement arrives with a queued Enter. */
internal fun terminalQueueAction(seenRejections: Long, rejections: Long, pending: Boolean, queued: Boolean): TerminalQueueAction =
    when {
        rejections != seenRejections -> TerminalQueueAction.CLEAR_REJECTED
        !pending && queued -> TerminalQueueAction.SEND
        else -> TerminalQueueAction.WAIT
    }

/** Protocol dimensions are independent of the physical screen size. */
internal fun terminalDimensions(cols: Int, rows: Int): Pair<Int, Int> = cols.coerceIn(20, 500) to rows.coerceIn(5, 300)

/** Translate platform paste line endings and tabs; escape sequences and other controls are forbidden. */
internal fun normalizeTerminalPaste(text: String): String? {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
    return normalized.takeIf { terminalInputField(TerminalInput.Paste(it)) != null }
}

private fun terminalInputBytes(input: TerminalInput): Int = when (input) {
    is TerminalInput.Text -> input.text.toByteArray(Charsets.UTF_8).size
    is TerminalInput.Paste -> input.text.toByteArray(Charsets.UTF_8).size
    is TerminalInput.Key -> 16
    is TerminalInput.Chord -> 32
}

internal fun terminalQueueCanAccept(queue: List<TerminalInput>, input: TerminalInput): Boolean =
    queue.size < 64 && terminalInputField(input) != null && queue.sumOf(::terminalInputBytes) + terminalInputBytes(input) <= 16384

/** Reserve an entire draft plus key together so no command is partially queued. */
internal fun terminalInputForKey(draft: String, key: String, structured: Boolean = false): List<TerminalInput>? {
    val prefix = if (draft.isEmpty()) emptyList() else listOf(terminalInputForDraft(draft) ?: return null)
    val input = if (structured) TerminalInput.Chord(if (key == "shift_tab") "tab" else key, shift = key == "shift_tab") else TerminalInput.Key(key)
    return prefix + input
}

internal fun terminalInputForChord(draft: String, chord: TerminalInput.Chord): List<TerminalInput>? {
    if (terminalInputField(chord) == null) return null
    val prefix = if (chord.name == "enter" && draft.isNotEmpty()) listOf(terminalInputForDraft(draft) ?: return null) else emptyList()
    return prefix + chord
}

/** Plain input also works in dialogs that have bracketed paste disabled. */
internal fun terminalInputForDraft(draft: String): TerminalInput? {
    val normalized = normalizeTerminalPaste(draft) ?: return null
    val text = TerminalInput.Text(normalized)
    return if (terminalInputField(text) != null) text else TerminalInput.Paste(normalized)
}

private val repeatableTerminalNames = setOf("up", "down", "left", "right", "tab", "shift_tab", "page_up", "page_down", "backspace", "delete")
private val repeatableChordNames = repeatableTerminalNames + setOf("home", "end")

/** Builds the whole replacement before the UI changes its queue. Only entries still queued merge. */
internal fun terminalQueueAfterEnqueue(queue: List<TerminalInput>, batch: List<TerminalInput>): List<TerminalInput>? {
    val pending = queue.toMutableList()
    for (input in batch) {
        if (terminalInputField(input) == null) return null
        var remainder = input
        val last = pending.lastOrNull()
        when {
            last is TerminalInput.Key && input is TerminalInput.Key && input.name in repeatableTerminalNames && last.name == input.name -> {
                val count = minOf(16 - last.repeat, input.repeat)
                if (count > 0) {
                    pending[pending.lastIndex] = last.copy(repeat = last.repeat + count)
                    if (count == input.repeat) continue
                    remainder = input.copy(repeat = input.repeat - count)
                }
            }
            last is TerminalInput.Chord && input is TerminalInput.Chord && input.name in repeatableChordNames &&
                last.copy(repeat = input.repeat) == input -> {
                val count = minOf(16 - last.repeat, input.repeat)
                if (count > 0) {
                    pending[pending.lastIndex] = last.copy(repeat = last.repeat + count)
                    if (count == input.repeat) continue
                    remainder = input.copy(repeat = input.repeat - count)
                }
            }
        }
        if (!terminalQueueCanAccept(pending, remainder)) return null
        pending.add(remainder)
    }
    return pending
}

internal fun terminalQueueCanAcceptAll(queue: List<TerminalInput>, batch: List<TerminalInput>): Boolean =
    terminalQueueAfterEnqueue(queue, batch) != null

/** The Shift Tab touch key denotes Tab+Shift even when other modifier chips are selected. */
internal fun terminalTouchChord(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean): TerminalInput.Chord? =
    if (ctrl || alt || shift || name.startsWith("f") || name == "shift_tab")
        TerminalInput.Chord(if (name == "shift_tab") "tab" else name, ctrl, alt, shift || name == "shift_tab")
    else null
