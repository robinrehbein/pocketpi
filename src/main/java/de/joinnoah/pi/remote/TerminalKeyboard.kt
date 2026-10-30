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
internal fun rememberTerminalInputKeys(draftEmpty: Boolean, send: (String) -> Unit): Modifier {
    val state = LocalTerminalInputFocus.current
    val active = LocalTerminalKeyboardActive.current
    val token = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    SideEffect { state?.update(token, active && focused) }
    DisposableEffect(state) { onDispose { state?.update(token, false) } }
    return Modifier.focusProperties { canFocus = active }
        .onFocusChanged { focused = it.hasFocus; state?.update(token, active && it.hasFocus) }
        .onPreviewKeyEvent { active && terminalHardwareKey(it.nativeKeyEvent, draftEmpty, send) }
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
