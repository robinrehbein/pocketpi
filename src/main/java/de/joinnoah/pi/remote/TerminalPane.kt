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
    val output = remember { mutableStateListOf<String>() }
    val input = remember { mutableStateListOf<TerminalInput>() }
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
    LaunchedEffect(view?.inputPending, view?.ended, view?.holder, input.size) {
        if (view?.ended != null || view?.holder != null) {
            if (input.isNotEmpty()) { input.clear(); notice = R.string.remote_terminal_unsent }
        } else if (view != null && !view.attaching && view.generation != null && !view.inputPending && input.isNotEmpty()) {
            if (model.sendTerminalInput(input.first())) input.removeAt(0)
            else { input.clear(); notice = R.string.remote_terminal_rejected }
        }
    }
    fun enqueueAll(batch: List<TerminalInput>): Boolean {
        if (view == null || view.attaching || view.ended != null || view.holder != null || view.generation == null ||
            !terminalQueueCanAcceptAll(input, batch)) {
            notice = R.string.remote_terminal_rejected
            return false
        }
        input.addAll(batch)
        onUnsentChanged(true)
        return true
    }
    fun enqueueKey(name: String) {
        val batch = terminalInputForKey(draft, name)
        if (batch == null) notice = R.string.remote_terminal_rejected
        else if (enqueueAll(batch)) { draft = ""; notice = null }
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
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("escape" to "Esc", "tab" to "Tab", "up" to "↑", "down" to "↓", "left" to "←", "right" to "→", "backspace" to "⌫", "enter" to "Enter").forEach { (name, label) ->
                    TextButton(onClick = { enqueueKey(name) }) { Text(label) }
                }
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it; onUnsentChanged(it.isNotEmpty() || input.isNotEmpty()) }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (event.type != KeyEventType.KeyDown || native.isCtrlPressed || native.isAltPressed || native.isMetaPressed) false else {
                        val name = when (native.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> "up"
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                            android.view.KeyEvent.KEYCODE_ESCAPE -> "escape"
                            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
                            android.view.KeyEvent.KEYCODE_TAB -> if (native.isShiftPressed) "shift_tab" else "tab"
                            android.view.KeyEvent.KEYCODE_MOVE_HOME -> "home"
                            android.view.KeyEvent.KEYCODE_MOVE_END -> "end"
                            android.view.KeyEvent.KEYCODE_PAGE_UP -> "page_up"
                            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
                            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
                            android.view.KeyEvent.KEYCODE_DEL -> if (draft.isEmpty()) "backspace" else null
                            else -> null
                        }
                        if (name != null) { enqueueKey(name); true } else false
                    }
                }, label = { Text(stringResource(R.string.remote_terminal_input)) },
                trailingIcon = { TextButton(onClick = {
                    val value = terminalInputForDraft(draft)
                    if (value == null) notice = R.string.remote_terminal_rejected
                    else if (enqueueAll(listOf(value))) { draft = ""; notice = null }
                }) { Text(stringResource(R.string.remote_terminal_send)) } },
            )
        }
    }
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
