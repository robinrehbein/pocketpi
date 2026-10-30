package de.joinnoah.pi.remote

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
}

internal fun terminalQueueCanAccept(queue: List<TerminalInput>, input: TerminalInput): Boolean =
    queue.size < 64 && terminalInputField(input) != null && queue.sumOf(::terminalInputBytes) + terminalInputBytes(input) <= 16384

/** Reserve an entire draft plus key together so no command is partially queued. */
internal fun terminalInputForKey(draft: String, key: String): List<TerminalInput>? {
    val prefix = if (draft.isEmpty()) emptyList() else listOf(terminalInputForDraft(draft) ?: return null)
    return prefix + TerminalInput.Key(key)
}

/** Plain input also works in dialogs that have bracketed paste disabled. */
internal fun terminalInputForDraft(draft: String): TerminalInput? {
    val normalized = normalizeTerminalPaste(draft) ?: return null
    val text = TerminalInput.Text(normalized)
    return if (terminalInputField(text) != null) text else TerminalInput.Paste(normalized)
}

internal fun terminalQueueCanAcceptAll(queue: List<TerminalInput>, batch: List<TerminalInput>): Boolean {
    val pending = queue.toMutableList()
    for (input in batch) {
        if (!terminalQueueCanAccept(pending, input)) return false
        pending.add(input)
    }
    return true
}
