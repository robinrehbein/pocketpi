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

/** The Send button: the draft and then Enter as one ordered batch, so a typed command runs. */
internal fun terminalInputForSend(draft: String, structured: Boolean = false): List<TerminalInput>? =
    terminalInputForKey(draft, "enter", structured)

/** Insert without Enter: only the draft, under the same paste rules as Send. */
internal fun terminalInputForInsert(draft: String): List<TerminalInput>? =
    if (draft.isEmpty()) null else terminalInputForDraft(draft)?.let(::listOf)

/** What the pane offers once its attachment ended. */
internal enum class TerminalEndKind {
    /** An ordinary end (exit, close, idle, taken over elsewhere): a status line; the header can reattach. */
    INFO,

    /** A failure that attaching again can fix. */
    RETRY,

    /** A refusal that attaching again cannot change. */
    FAILURE,
}

/** [text] is a string resource; with [exitCode] it takes the code as its argument. */
internal data class TerminalEnd(val text: Int, val kind: TerminalEndKind, val exitCode: Int? = null) {
    val canReattach: Boolean get() = kind != TerminalEndKind.FAILURE
}

/** The user's view of a terminal that ended with [reason] (the host's reason or the app's own). */
internal fun terminalEnd(reason: String, exitCode: Int?): TerminalEnd = when (reason) {
    "exit" -> if (exitCode != null) TerminalEnd(R.string.remote_terminal_exit_code, TerminalEndKind.INFO, exitCode)
        else TerminalEnd(R.string.remote_terminal_exit, TerminalEndKind.INFO)
    "revoked", "ownership" -> TerminalEnd(R.string.remote_terminal_revoked, TerminalEndKind.INFO)
    "idle" -> TerminalEnd(R.string.remote_terminal_idle, TerminalEndKind.INFO)
    "detached", "left" -> TerminalEnd(R.string.remote_terminal_detached, TerminalEndKind.INFO)
    "offline" -> TerminalEnd(R.string.remote_view_disconnected, TerminalEndKind.RETRY)
    "slow" -> TerminalEnd(R.string.remote_terminal_slow, TerminalEndKind.RETRY)
    "overflow" -> TerminalEnd(R.string.remote_terminal_overflow, TerminalEndKind.RETRY)
    "protocol", "sequence", "resize_failed" -> TerminalEnd(R.string.remote_terminal_connection_problem, TerminalEndKind.RETRY)
    "input_failed" -> TerminalEnd(R.string.remote_terminal_input_failed, TerminalEndKind.RETRY)
    "failed" -> TerminalEnd(R.string.remote_terminal_failed, TerminalEndKind.RETRY)
    "forbidden" -> TerminalEnd(R.string.remote_terminal_forbidden, TerminalEndKind.FAILURE)
    "unsupported" -> TerminalEnd(R.string.remote_terminal_unsupported, TerminalEndKind.FAILURE)
    else -> TerminalEnd(R.string.remote_terminal_stopped, TerminalEndKind.RETRY)
}

/** Opening the terminal attaches when no pane is open, or again when the open pane's terminal ended. */
internal fun terminalOpenAttaches(open: Boolean, view: TerminalView?): Boolean =
    !open || view?.ended?.let { terminalEnd(it, view.exitCode).canReattach } == true

/** Sent to the renderer before a new attachment's output: a full reset (RIS) clears screen and scrollback. */
internal const val TERMINAL_RESET = "\u001bc"

/** The attachment whose output the renderer shows. */
internal data class TerminalRenderedAttachment(val sessionId: String, val generation: Long)

/**
 * The renderer's unwritten output once [view] arrives, and the attachment it now shows. A new
 * attachment drops what an older one left unwritten and starts on a reset screen, so its replayed
 * tail never mixes with the old output.
 */
internal fun terminalPendingWrites(
    rendered: TerminalRenderedAttachment?,
    view: TerminalView?,
    pending: List<String>,
): Pair<TerminalRenderedAttachment?, List<String>> {
    if (view == null || view.attaching) return null to pending
    val generation = view.generation
    if (view.holder != null || generation == null) return rendered to pending
    val current = TerminalRenderedAttachment(view.sessionId, generation)
    return if (current == rendered) rendered to pending else current to listOf(TERMINAL_RESET)
}

/** What the pane keeps once [terminalAttachmentUpdate] saw a view. */
internal data class TerminalAttachmentUpdate(
    val attachment: TerminalRenderedAttachment?,
    val output: List<String>,
    val input: List<TerminalInput>,
    /** Queued input was dropped: the pane says some input was not sent. */
    val inputDropped: Boolean,
)

/**
 * The pane's renderer output and queued input once [view] arrives. Both belong to the attachment
 * they were made for: a new attachment (or none, while attaching) drops the old one's unwritten
 * output, as [terminalPendingWrites] does, and its queued input too. Input is never replayed into
 * another attachment, also when the pane never saw the old one end (its activity was stopped
 * while the connection dropped and the terminal reattached).
 */
internal fun terminalAttachmentUpdate(
    rendered: TerminalRenderedAttachment?,
    view: TerminalView?,
    output: List<String>,
    input: List<TerminalInput>,
): TerminalAttachmentUpdate {
    val (attachment, pending) = terminalPendingWrites(rendered, view, output)
    return if (attachment == rendered) TerminalAttachmentUpdate(rendered, output, input, inputDropped = false)
    else TerminalAttachmentUpdate(attachment, pending, emptyList(), inputDropped = input.isNotEmpty())
}

/** Where a pane laid over the chat goes; beside the chat the inspector draws it. */
internal enum class PaneOverlayPlacement { INSPECTOR, SHOWN, KEPT_HIDDEN }

/**
 * Over the chat the terminal shows only as the active tab, like the browser, so a pane opened
 * after it is not covered; otherwise it stays composed but unplaced, keeping its renderer and
 * attachment.
 */
internal fun terminalOverlayPlacement(panesBeside: Boolean, inspectorShown: Boolean, active: Boolean): PaneOverlayPlacement = when {
    panesBeside && inspectorShown -> PaneOverlayPlacement.INSPECTOR
    !panesBeside && active -> PaneOverlayPlacement.SHOWN
    else -> PaneOverlayPlacement.KEPT_HIDDEN
}
