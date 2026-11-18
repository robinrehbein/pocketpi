package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The terminal of a host worker in the shared remote view scaffold: header, notices, the
 * renderer in its viewport, the key strip and the draft field.
 *
 * Hardware Esc is the terminal's key: it never closes the pane (Back does).
 *
 * Test tags: those of [RemoteViewPane] with `terminal` (`terminalPane`, `terminalClose`,
 * `terminalReload` for reattaching), `terminalShortcuts`, those of [RemoteControlBar] while the
 * terminal is held elsewhere (`terminalAcquire` takes it over), `terminalViewport` and
 * `terminalViewportBusy` while input is on its way, `terminalKey_<name>`, `terminalInput`,
 * `terminalInputSend`, `terminalInsert`, and `terminalEnded`, `terminalNotice` on the notices.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var showShortcuts by remember { mutableStateOf(false) }
    var allowCtrlAlt by remember(view?.sessionId, view?.generation) { mutableStateOf(false) }
    var character by remember { mutableStateOf("") }
    var shiftedCharacter by remember { mutableStateOf("") }
    // Unwritten renderer output, of the attachment in renderedAttachment only (see terminalPendingWrites).
    val output = remember { mutableStateListOf<String>() }
    var renderedAttachment by remember { mutableStateOf<TerminalRenderedAttachment?>(null) }
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
    LaunchedEffect(view?.output, ready, view?.sessionId, view?.generation, view?.attaching, view?.holder) {
        // Output and queued input belong to one attachment: a new one drops both (input is never replayed).
        val update = terminalAttachmentUpdate(renderedAttachment, view, output.toList(), input.toList())
        if (update.attachment != renderedAttachment) {
            renderedAttachment = update.attachment
            output.clear()
            output.addAll(update.output)
            input.clear()
            if (update.inputDropped) notice = R.string.remote_terminal_unsent
        }
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
            // Input of another attachment waits until the output effect above dropped it.
            if (renderedAttachment != TerminalRenderedAttachment(view.sessionId, view.generation)) return@LaunchedEffect
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
    // Send runs the command: the draft and Enter in one batch, the path the Enter key takes too,
    // whatever modifier chips are selected.
    fun sendWithEnter() {
        val batch = terminalInputForSend(draft, structured = view?.keysEnabled == true)
        if (batch == null) notice = R.string.remote_terminal_rejected
        else if (enqueueAll(batch, consumesDraft = draft.isNotEmpty())) notice = null
    }
    fun insertDraft() {
        val batch = terminalInputForInsert(draft)
        if (batch == null) notice = R.string.remote_terminal_rejected
        else if (enqueueAll(batch, consumesDraft = true)) notice = null
    }
    fun reattach() {
        notice = if (model.attachTerminal(size.first, size.second)) null else R.string.remote_terminal_reattach_failed
    }

    val live = view != null && !view.attaching && view.generation != null && view.holder == null && view.ended == null && !failed
    val end = view?.ended?.let { terminalEnd(it, view.exitCode) }
    val ctrlLabel = stringResource(R.string.remote_view_key_ctrl)
    val altLabel = stringResource(R.string.remote_view_key_alt)
    val shiftLabel = stringResource(R.string.remote_view_key_shift)
    val reattachLabel = stringResource(R.string.remote_terminal_reattach)
    val inputPending = view != null && view.holder == null && (view.inputPending || input.isNotEmpty())
    val activeModifiers = if (view?.keysEnabled == true) listOfNotNull(ctrlLabel.takeIf { ctrl }, altLabel.takeIf { alt }, shiftLabel.takeIf { shift }) else emptyList()
    val notices = buildList<RemoteViewNotice> {
        if (failed) add(RemoteViewNotice.Failure(stringResource(R.string.remote_terminal_unavailable), "terminalUnavailable"))
        else if (end != null) {
            val text = if (end.exitCode != null) stringResource(end.text, end.exitCode) else stringResource(end.text)
            add(when (end.kind) {
                TerminalEndKind.INFO -> RemoteViewNotice.Info(text, "terminalEnded")
                TerminalEndKind.RETRY -> RemoteViewNotice.Failure(text, "terminalEnded", reattachLabel, ::reattach)
                TerminalEndKind.FAILURE -> RemoteViewNotice.Failure(text, "terminalEnded")
            })
        }
        notice?.let { add(RemoteViewNotice.Failure(stringResource(it), "terminalNotice")) }
    }
    val keys = listOf(
        RemoteKey("escape", stringResource(R.string.remote_view_key_escape), stringResource(R.string.remote_view_key_escape_description)),
        RemoteKey("tab", stringResource(R.string.remote_view_key_tab)),
        RemoteKey("enter", stringResource(R.string.remote_view_key_enter)),
    ) + remoteNavigationKeys(RemoteNavigationIds.TERMINAL)
    // The draft stays editable whatever happens to the terminal; its placeholder says why it
    // cannot be sent now, and Send and Insert are disabled.
    val inputLabel = when {
        failed || end?.canReattach == false -> stringResource(R.string.remote_terminal_input_closed)
        end != null -> stringResource(R.string.remote_terminal_input_ended)
        view?.holder != null -> stringResource(R.string.remote_terminal_input_held)
        else -> stringResource(R.string.remote_terminal_input)
    }
    RemoteViewPane(
        title = stringResource(R.string.remote_terminal_title),
        tag = "terminal",
        onClose = onClose,
        subtitle = activeModifiers.takeIf { it.isNotEmpty() }?.joinToString("+"),
        // Once the terminal ended, the header's reload button attaches again.
        onReload = if (!failed && end?.canReattach == true) ::reattach else null,
        reloadLabel = reattachLabel,
        escapeCloses = false,
        actions = if (view?.keysEnabled == true) listOf(
            PaneAction(stringResource(R.string.remote_terminal_shortcuts), Icons.Default.Keyboard, "terminalShortcuts", onClick = { showShortcuts = true }),
        ) else emptyList(),
        notices = notices,
    ) {
        if (view?.holder != null)
            RemoteControlBar(
                RemoteControlState.HELD, "terminal",
                Modifier.remoteViewShed(RemoteViewShed.CONTROL),
                holder = stringResource(if (view.holder == "mac") R.string.remote_view_holder_mac else R.string.remote_view_holder_phone),
                // Taking over interrupts whoever types there: it asks first.
                onTake = { confirmTakeover = true },
            )
        val screenLabel = stringResource(R.string.remote_terminal_screen)
        RemoteViewport(
            "terminalViewport",
            Modifier.weight(1f).fillMaxWidth(),
            placeholder = if (!failed && (view == null || view.attaching)) RemoteViewPlaceholder(stringResource(R.string.remote_view_opening)) else null,
            color = TerminalTheme.Background,
            contentColor = TerminalTheme.Foreground,
            busy = inputPending,
        ) {
            // Always composed: removing the view would end the renderer.
            AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxSize().semantics { contentDescription = screenLabel })
        }
        RemoteKeyStrip(keys, onKey = { sendTouchKey(it.id) }, tag = "terminal", enabled = live, sheds = true)
        RemoteInputBar(
            value = draft,
            onValueChange = { draft = it; onUnsentChanged(it.isNotEmpty() || input.isNotEmpty()) },
            onSend = ::sendWithEnter,
            tag = "terminalInput",
            fieldModifier = rememberTerminalInputKeys(draft.isEmpty(), view?.keysEnabled == true, allowCtrlAlt, ::enqueueKey, ::enqueueChord),
            label = inputLabel,
            // An empty draft still sends Enter.
            sendEnabled = live,
            sendLabel = stringResource(R.string.remote_terminal_send_enter),
            actions = {
                IconButton(onClick = ::insertDraft, modifier = Modifier.size(48.dp).testTag("terminalInsert"), enabled = live && draft.isNotEmpty()) {
                    Icon(Icons.Default.TextFields, stringResource(R.string.remote_terminal_insert))
                }
            },
        )
    }
    // A pane kept composed behind another tab shows none of its dialogs.
    val shown = LocalTerminalKeyboardActive.current
    if (shown && showShortcuts && view?.keysEnabled == true) GlassSheet(
        onDismissRequest = { showShortcuts = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val enterLabel = stringResource(R.string.remote_view_key_enter)
        val tabLabel = stringResource(R.string.remote_view_key_tab)
        val chords = listOf(
            TerminalInput.Chord("c", ctrl = true) to "$ctrlLabel+C",
            TerminalInput.Chord("o", ctrl = true) to "$ctrlLabel+O",
            TerminalInput.Chord("t", ctrl = true) to "$ctrlLabel+T",
            TerminalInput.Chord("enter", alt = true) to "$altLabel+$enterLabel",
            TerminalInput.Chord("p", alt = true) to "$altLabel+P",
        )
        val stripPadding = PaddingValues(vertical = 4.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("terminalShortcutsSheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.remote_terminal_shortcuts))
            RemoteKeyStrip(
                listOf(
                    RemoteKey("ctrl", ctrlLabel, selected = ctrl),
                    RemoteKey("alt", altLabel, selected = alt),
                    RemoteKey("shift", shiftLabel, selected = shift),
                    RemoteKey("ctrl_alt", stringResource(R.string.remote_terminal_ctrl_alt_shortcuts), selected = allowCtrlAlt),
                ),
                onKey = { key ->
                    when (key.id) {
                        "ctrl" -> ctrl = !ctrl
                        "alt" -> alt = !alt
                        "shift" -> shift = !shift
                        "ctrl_alt" -> allowCtrlAlt = !allowCtrlAlt
                    }
                },
                tag = "terminalModifier",
                contentPadding = stripPadding,
            )
            RemoteKeyStrip(
                chords.mapIndexed { index, (_, label) -> RemoteKey("chord$index", label) } +
                    RemoteKey("character", stringResource(R.string.remote_terminal_character)),
                onKey = { key ->
                    if (key.id == "character") { showShortcuts = false; chooseCharacter = true }
                    else enqueueChord(chords[key.id.removePrefix("chord").toInt()].first)
                },
                tag = "terminalShortcut",
                contentPadding = stripPadding,
            )
            RemoteKeyStrip(
                listOf(
                    RemoteKey("shift_tab", "$shiftLabel+$tabLabel"),
                    RemoteKey("home", stringResource(R.string.remote_view_key_home)),
                    RemoteKey("end", stringResource(R.string.remote_view_key_end)),
                    RemoteKey("page_up", stringResource(R.string.remote_view_key_page_up), stringResource(R.string.remote_view_key_page_up_description)),
                    RemoteKey("page_down", stringResource(R.string.remote_view_key_page_down), stringResource(R.string.remote_view_key_page_down_description)),
                    RemoteKey("delete", stringResource(R.string.remote_view_key_delete), stringResource(R.string.remote_view_key_delete_description)),
                ) + (1..12).map { RemoteKey("f$it", "F$it") },
                onKey = { sendTouchKey(it.id) },
                tag = "terminalExtra",
                contentPadding = stripPadding,
            )
            SheetSecondaryButton(onClick = { showShortcuts = false }, modifier = Modifier.testTag("terminalHideShortcuts")) {
                Text(stringResource(R.string.remote_terminal_hide_shortcuts))
            }
        }
    }
    if (shown && chooseCharacter && view?.keysEnabled == true) AlertDialog(
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
            (!shift || shiftedCharacter.isEmpty() || (shiftedCharacter.length == 1 && shiftedCharacter[0].code in 32..126))) { Text(stringResource(R.string.remote_view_send)) } },
        dismissButton = { TextButton(onClick = { chooseCharacter = false }) { Text(stringResource(R.string.remote_cancel)) } },
    )
    if (shown && confirmTakeover && view?.holder != null && view.generation != null) AlertDialog(
        onDismissRequest = { confirmTakeover = false },
        title = { Text(stringResource(R.string.remote_terminal_takeover)) },
        text = { Text(stringResource(R.string.remote_terminal_takeover_confirm)) },
        confirmButton = { TextButton(onClick = { confirmTakeover = false; if (!model.attachTerminal(size.first, size.second, view.generation)) notice = R.string.remote_terminal_rejected }) { Text(stringResource(R.string.remote_terminal_takeover)) } },
        dismissButton = { TextButton(onClick = { confirmTakeover = false }) { Text(stringResource(R.string.remote_cancel)) } },
    )
}
