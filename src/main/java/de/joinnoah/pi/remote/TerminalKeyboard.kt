package de.joinnoah.pi.remote

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** A late focus-loss event from an old terminal cannot clear the new terminal's focus. */
internal class TerminalInputFocus {
    private var owner by mutableStateOf<Any?>(null)
    val focused: Boolean get() = owner != null

    fun update(token: Any, focused: Boolean) {
        if (focused) owner = token else if (owner === token) owner = null
    }
}

internal val LocalTerminalInputFocus = staticCompositionLocalOf<TerminalInputFocus?> { null }

/** Retained inspector contents must not keep hardware input while hidden. */
internal val LocalTerminalKeyboardActive = staticCompositionLocalOf { true }

@Composable
internal fun rememberTerminalInputKeys(draftEmpty: Boolean, keysEnabled: Boolean, allowCtrlAlt: Boolean, send: (String) -> Unit, sendChord: (TerminalInput.Chord) -> Unit): Modifier {
    val state = LocalTerminalInputFocus.current
    val active = LocalTerminalKeyboardActive.current
    val token = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    SideEffect { state?.update(token, active && focused) }
    DisposableEffect(state) { onDispose { state?.update(token, false) } }
    return Modifier.focusProperties { canFocus = active }
        .onFocusChanged { focused = it.hasFocus; state?.update(token, active && it.hasFocus) }
        .onPreviewKeyEvent { active && ((keysEnabled && terminalHardwareChord(it.nativeKeyEvent, sendChord, allowCtrlAlt)) || terminalHardwareKey(it.nativeKeyEvent, draftEmpty, send)) }
}

/** Both halves of Escape belong to the terminal, so Android cannot turn its release into Back. */
internal fun terminalHardwareKey(event: KeyEvent, draftEmpty: Boolean, send: (String) -> Unit): Boolean {
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    if (event.action == KeyEvent.ACTION_UP) return event.keyCode == KeyEvent.KEYCODE_ESCAPE
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val name = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_ESCAPE -> "escape"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
        KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) "shift_tab" else "tab"
        KeyEvent.KEYCODE_MOVE_HOME -> "home"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_PAGE_UP -> "page_up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_DEL -> if (draftEmpty) "backspace" else null
        else -> null
    } ?: return false
    send(name)
    return true
}

/** Structured keys never steal Android Meta or AltGr text input; F6 remains pane navigation. */
internal fun terminalHardwareChord(event: KeyEvent, send: (TerminalInput.Chord) -> Unit, allowCtrlAlt: Boolean = false): Boolean {
    if (event.isMetaPressed || (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0) || (!allowCtrlAlt && event.isCtrlPressed && event.isAltPressed) || event.keyCode == KeyEvent.KEYCODE_F6) return false
    val modified = event.isCtrlPressed || event.isAltPressed || event.isShiftPressed
    val function = event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
    if (!modified && !function) return false
    val named = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_ESCAPE -> "escape"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_MOVE_HOME -> "home"
        KeyEvent.KEYCODE_MOVE_END -> "end"
        KeyEvent.KEYCODE_PAGE_UP -> "page_up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
        KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        KeyEvent.KEYCODE_DEL -> "backspace"
        else -> if (function) "f${event.keyCode - KeyEvent.KEYCODE_F1 + 1}" else null
    }
    // Shifted ordinary text belongs to the IME; Ctrl/Alt printable keys use the base key.
    if (named == null && !event.isCtrlPressed && !event.isAltPressed) return false
    val base = if (named == null) event.getUnicodeChar(0).takeIf { it in 32..126 }?.toChar()?.lowercaseChar()?.toString() else named
    val name = base ?: return false
    if (event.action == KeyEvent.ACTION_UP) return true
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val shifted = if (named == null && event.isShiftPressed)
        event.getUnicodeChar(KeyEvent.META_SHIFT_ON).takeIf { it in 32..126 }?.toChar()?.toString() else null
    send(TerminalInput.Chord(name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, shifted = shifted))
    return true
}
