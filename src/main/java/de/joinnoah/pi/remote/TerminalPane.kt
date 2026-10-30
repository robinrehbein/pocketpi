package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun TerminalPane(view: TerminalView?, model: ChatViewModel, onClose: () -> Unit, onUnsentChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(80 to 24) }
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<Int?>(null) }
    var confirmTakeover by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var chooseCharacter by remember { mutableStateOf(false) }
    var allowCtrlAlt by remember(view?.sessionId, view?.generation) { mutableStateOf(false) }
    var character by remember { mutableStateOf("") }
    var shiftedCharacter by remember { mutableStateOf("") }
    val output = remember { mutableStateListOf<String>() }
    val input = remember { mutableStateListOf<TerminalInput>() }
    var seenRejections by remember(view?.sessionId, view?.generation) { mutableStateOf(0L) }
    SideEffect { onUnsentChanged(draft.isNotEmpty() || input.isNotEmpty()) }
    val renderer = remember(context) {
        createTerminalWebView(context, object : TerminalRendererCallbacks {
            override fun onReady() { ready = true }
            override fun onWritten() { writing = false }
            override fun onSize(cols: Int, rows: Int) { size = terminalDimensions(cols, rows) }
            override fun onFailure() { failed = true }
        })
    }
    DisposableEffect(renderer) { onDispose { model.detachTerminal(); renderer.dispose() } }
    LaunchedEffect(failed) { if (failed) model.detachTerminal() }
    LaunchedEffect(view?.generation, view?.holder, view?.ended, size) {
        if (view != null && !view.attaching && view.holder == null && view.ended == null && view.generation != null)
            model.resizeTerminal(size.first, size.second)
    }
    LaunchedEffect(view?.output, ready) {
        if (ready && view?.output?.isNotEmpty() == true) {
            val next = model.takeTerminalOutput()
            if (output.sumOf { it.toByteArray().size } + next.sumOf { it.toByteArray().size } > 256 * 1024 || output.size + next.size > 256) {
                output.clear()
                notice = R.string.remote_terminal_overflow
                model.detachTerminal()
            } else output.addAll(next)
        }
    }
    LaunchedEffect(ready, writing, output.size) {
        if (ready && !writing && output.isNotEmpty()) {
            writing = true
            if (renderer.write(output.first())) output.removeAt(0) else { writing = false; failed = true }
        }
    }
    LaunchedEffect(view?.inputPending, view?.inputRejected, view?.generation, view?.ended, view?.holder, input.size) {
        if (view?.ended != null || view?.holder != null) {
            if (input.isNotEmpty()) { input.clear(); notice = R.string.remote_terminal_unsent }
        } else if (view != null && !view.attaching && view.generation != null) {
            val live = model.terminalViewNow()
            if (live == null || live.sessionId != view.sessionId || live.generation != view.generation || live.ended != null || live.holder != null) {
                if (input.isNotEmpty()) { input.clear(); notice = R.string.remote_terminal_unsent }
                return@LaunchedEffect
            }
            when (terminalQueueAction(seenRejections, live.inputRejected, live.inputPending, input.isNotEmpty())) {
                TerminalQueueAction.CLEAR_REJECTED -> {
                    seenRejections = live.inputRejected
                    input.clear()
                    notice = R.string.remote_terminal_key_unsupported
                }
                TerminalQueueAction.SEND -> if (model.sendTerminalInput(input.first())) input.removeAt(0)
                    else { input.clear(); notice = R.string.remote_terminal_rejected }
                TerminalQueueAction.WAIT -> Unit
            }
        }
    }
    fun enqueueAll(batch: List<TerminalInput>, consumesDraft: Boolean = false): Boolean {
        val result = terminalPrepareEnqueue(TerminalDraftQueue(draft, input.toList()), view, model.terminalViewNow(), seenRejections, batch, consumesDraft)
        when (result.decision) {
            TerminalEnqueueDecision.BUSY -> { notice = R.string.remote_terminal_draft_busy; return false }
            TerminalEnqueueDecision.UNAVAILABLE, TerminalEnqueueDecision.INVALID -> { notice = R.string.remote_terminal_rejected; return false }
            TerminalEnqueueDecision.ACCEPTED -> Unit
        }
        input.clear()
        input.addAll(result.state.queue)
        draft = result.state.draft
        onUnsentChanged(true)
        return true
    }
    fun enqueueKey(name: String) {
        val batch = terminalInputForKey(draft, name, structured = view?.keysEnabled == true)
        if (batch == null) notice = R.string.remote_terminal_rejected
        else if (enqueueAll(batch, consumesDraft = draft.isNotEmpty())) notice = null
    }
    fun enqueueChord(chord: TerminalInput.Chord) {
        val batch = terminalInputForChord(draft, chord)
        if (batch == null) notice = R.string.remote_terminal_rejected
        else if (enqueueAll(batch, consumesDraft = chord.name == "enter" && draft.isNotEmpty())) notice = null
    }
    fun sendTouchKey(name: String) {
        val chord = if (view?.keysEnabled == true) terminalTouchChord(name, ctrl, alt, shift) else null
        if (chord != null) enqueueChord(chord) else enqueueKey(name)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.remote_terminal_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(stringResource(R.string.remote_terminal_close)) }
            }
            when {
                failed -> Text(stringResource(R.string.remote_terminal_unavailable))
                view == null || view.attaching -> Text(stringResource(R.string.remote_terminal_attaching))
                view.holder != null -> {
                    Text(stringResource(if (view.holder == "mac") R.string.remote_terminal_held_mac else R.string.remote_terminal_held_phone))
                    TextButton(onClick = { confirmTakeover = true }) { Text(stringResource(R.string.remote_terminal_takeover)) }
                }
                view.ended != null -> Text(stringResource(terminalEndLabel(view.ended)))
                view.inputPending || input.isNotEmpty() -> Text(stringResource(R.string.remote_terminal_sending))
            }
            notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            AndroidView(factory = { renderer.view }, modifier = Modifier.weight(1f).fillMaxWidth())
            if (view?.keysEnabled == true) Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("Alt") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = shift, onClick = { shift = !shift }, label = { Text("Shift") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = allowCtrlAlt, onClick = { allowCtrlAlt = !allowCtrlAlt }, label = { Text(stringResource(R.string.remote_terminal_ctrl_alt_shortcuts)) })
                TextButton(onClick = { chooseCharacter = true }) { Text(stringResource(R.string.remote_terminal_character)) }
            }
            if (view?.keysEnabled == true) Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                    "Ctrl+C" to TerminalInput.Chord("c", ctrl = true),
                    "Ctrl+O" to TerminalInput.Chord("o", ctrl = true),
                    "Ctrl+T" to TerminalInput.Chord("t", ctrl = true),
                    "Alt+Enter" to TerminalInput.Chord("enter", alt = true),
                    "Alt+P" to TerminalInput.Chord("p", alt = true),
                ).forEach { (label, chord) -> TextButton(onClick = { enqueueChord(chord) }) { Text(label) } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf("escape" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓", "left" to "←", "right" to "→", "backspace" to "⌫", "enter" to "Enter") + if (view?.keysEnabled == true)
                    listOf("shift_tab" to "Shift Tab", "home" to "Home", "end" to "End", "page_up" to "PgUp", "page_down" to "PgDn", "delete" to "Del") + (1..12).map { "f$it" to "F$it" } else emptyList()).forEach { (name, label) ->
                    TextButton(onClick = { sendTouchKey(name) }) { Text(label) }
                }
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it; onUnsentChanged(it.isNotEmpty() || input.isNotEmpty()) }, modifier = Modifier.fillMaxWidth().then(rememberTerminalInputKeys(draft.isEmpty(), view?.keysEnabled == true, allowCtrlAlt, ::enqueueKey, ::enqueueChord)), label = { Text(stringResource(R.string.remote_terminal_input)) },
                trailingIcon = { TextButton(onClick = {
                    val value = terminalInputForDraft(draft)
                    if (value == null) notice = R.string.remote_terminal_rejected
                    else if (enqueueAll(listOf(value), consumesDraft = true)) notice = null
                }) { Text(stringResource(R.string.remote_terminal_send)) } },
            )
        }
    }
    if (chooseCharacter && view?.keysEnabled == true) AlertDialog(
        onDismissRequest = { chooseCharacter = false },
        title = { Text(stringResource(R.string.remote_terminal_character)) },
        text = { Column {
            OutlinedTextField(value = character, onValueChange = { character = it }, singleLine = true, label = { Text(stringResource(R.string.remote_terminal_character_hint)) })
            if (shift) OutlinedTextField(value = shiftedCharacter, onValueChange = { shiftedCharacter = it }, singleLine = true, label = { Text(stringResource(R.string.remote_terminal_shifted_character)) })
        } },
        confirmButton = { TextButton(onClick = {
            enqueueChord(TerminalInput.Chord(character, ctrl, alt, shift, shifted = shiftedCharacter.takeIf { shift && it.isNotEmpty() }))
            chooseCharacter = false
            character = ""
            shiftedCharacter = ""
        }, enabled = character.length == 1 && character[0].code in 32..126 && (!(ctrl || alt || shift) || character[0] !in 'A'..'Z') &&
            (!shift || shiftedCharacter.isEmpty() || (shiftedCharacter.length == 1 && shiftedCharacter[0].code in 32..126))) { Text(stringResource(R.string.remote_terminal_send)) } },
        dismissButton = { TextButton(onClick = { chooseCharacter = false }) { Text(stringResource(R.string.remote_terminal_cancel)) } },
    )
    if (confirmTakeover && view?.holder != null && view.generation != null) AlertDialog(
        onDismissRequest = { confirmTakeover = false },
        title = { Text(stringResource(R.string.remote_terminal_takeover)) },
        text = { Text(stringResource(R.string.remote_terminal_takeover_confirm)) },
        confirmButton = { TextButton(onClick = { confirmTakeover = false; if (!model.attachTerminal(size.first, size.second, view.generation)) notice = R.string.remote_terminal_rejected }) { Text(stringResource(R.string.remote_terminal_takeover)) } },
        dismissButton = { TextButton(onClick = { confirmTakeover = false }) { Text(stringResource(R.string.remote_terminal_cancel)) } },
    )
}

private fun terminalEndLabel(reason: String): Int = when (reason) {
    "exit" -> R.string.remote_terminal_exit
    "revoked" -> R.string.remote_terminal_revoked
    "offline" -> R.string.remote_terminal_offline
    "idle" -> R.string.remote_terminal_idle
    "detached" -> R.string.remote_terminal_detached
    else -> R.string.remote_terminal_stopped
}
