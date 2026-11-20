package de.joinnoah.pi.remote

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** The base keys a hardware keyboard reaches; they need no capability beyond the emulator's own. */
private fun emulatorBaseKey(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_ESCAPE -> "Escape"
    KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
    KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    else -> null
}

/** The editing keys of [SESSION_EMULATOR_KEYBOARD_CAPABILITY]. */
private fun emulatorEditingKey(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_MOVE_HOME -> "MoveHome"
    KeyEvent.KEYCODE_MOVE_END -> "MoveEnd"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    else -> null
}

/**
 * The letter or digit key at [keyCode], by its position as the host names it (KeyA, Digit1), so a
 * chord means the same on every layout. The number pad is left out: its keys are not Digit keys.
 */
private fun emulatorCharacterKey(keyCode: Int): String? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> "Key${'A' + (keyCode - KeyEvent.KEYCODE_A)}"
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Digit${keyCode - KeyEvent.KEYCODE_0}"
    else -> null
}

/** AltGr types characters (@, € and { on a German layout); it is never the Alt of a chord. */
private fun KeyEvent.altGr(): Boolean = metaState and KeyEvent.META_ALT_RIGHT_ON != 0

/** The chord's modifiers in the host's canonical order (Shift, Control, Alt, Meta). */
private fun KeyEvent.emulatorModifiers(): List<String> = buildList {
    if (isShiftPressed) add("Shift")
    if (isCtrlPressed) add("Control")
    if (isAltPressed && !altGr()) add("Alt")
    if (isMetaPressed) add("Meta")
}

/**
 * The key input a hardware [event] makes, or null for one that types text or has no wire form.
 * Without [keyboard] only the plain base keys go, as before the keyboard capability.
 */
internal fun emulatorKeyInput(event: KeyEvent, keyboard: Boolean): EmulatorInput.Key? {
    val modifiers = event.emulatorModifiers()
    val named = emulatorBaseKey(event.keyCode) ?: emulatorEditingKey(event.keyCode)?.takeIf { keyboard }
    if (named != null) return when {
        modifiers.isEmpty() -> EmulatorInput.Key(named)
        keyboard -> EmulatorInput.Key(named, modifiers)
        // A chord the host cannot take does not go as its bare key: Shift+Tab is not Tab.
        else -> null
    }
    if (!keyboard || modifiers.none { it != "Shift" }) return null
    val key = emulatorCharacterKey(event.keyCode) ?: return null
    return EmulatorInput.Key(key, modifiers).takeIf { emulatorKeyValid(it.key, it.modifiers) }
}

/**
 * Turns hardware key events into emulator inputs: named keys and chords through
 * [emulatorKeyInput], typed characters as text. A dead key (the ´ of a German layout) is kept
 * and joined with the next character, as Android's own text fields do.
 */
internal class EmulatorHardwareKeys {
    private var accent = 0

    /** Forgets a pending dead key: focus or control went elsewhere before its letter came. */
    fun reset() { accent = 0 }

    /**
     * Sends what [event] makes through [send] (with whether it is an auto-repeat) and returns
     * whether the pane keeps the event. Both halves of a named key (Enter, Tab, Escape, the
     * arrows, the editing keys, with or without modifiers) always stay in the pane, sent or not,
     * so Android cannot turn Escape's release into Back and focus traversal cannot carry the
     * focus off the screen. A first press of a character or a letter chord that [send] refuses
     * falls through, so a key this connection no longer takes reaches the app; a refused repeat
     * stays, as dropping repeats while one is in flight is how a held key keeps pace. A character
     * the connection cannot send (non-ASCII text without [keyboard]) is left alone.
     */
    fun key(event: KeyEvent, keyboard: Boolean, send: (EmulatorInput, Boolean) -> Boolean): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down && event.action != KeyEvent.ACTION_UP) return false
        val repeat = event.repeatCount > 0
        val named = emulatorBaseKey(event.keyCode) != null || emulatorEditingKey(event.keyCode) != null
        emulatorKeyInput(event, keyboard)?.let { if (!down) return true; accent = 0; return send(it, repeat) || repeat || named }
        if (named) { if (down) accent = 0; return true }
        // Control, left Alt or Meta with a key that makes no chord: a shortcut, never text.
        if (event.isCtrlPressed || event.isMetaPressed || (event.isAltPressed && !event.altGr())) return false
        val char = event.unicodeChar
        if (char == 0) return false
        if (!down) return true
        if (char and KeyCharacterMap.COMBINING_ACCENT != 0) {
            accent = char and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        val valid = { t: String -> if (keyboard) emulatorKeyboardTextValid(t) else emulatorTextValid(t) }
        // ´ then e is é, which ASCII cannot carry: without the keyboard the letter goes alone.
        val text = emulatorTypedText(accent, char).takeIf(valid) ?: emulatorTypedText(0, char).takeIf(valid)
        accent = 0
        if (text == null) return false
        return send(EmulatorInput.Text(text), repeat) || repeat
    }
}

/**
 * The text of [char] after a pending dead-key [accent]: the composed character where one exists
 * (é, or ´ itself after a space), otherwise the character alone, since a lone combining accent
 * would join whatever the device's field holds before it. NFC, as the host requires.
 */
internal fun emulatorTypedText(accent: Int, char: Int, deadChar: (Int, Int) -> Int = KeyEvent::getDeadChar): String {
    val composed = if (accent != 0) deadChar(accent, char) else 0
    return emulatorNfc(String(Character.toChars(if (composed != 0) composed else char)))
}

/**
 * The emulator screen's hardware keys. While this device [holds] control its focus counts as a
 * remote input's focus ([LocalTerminalInputFocus]), so the app's shortcuts leave its keys alone,
 * as in the terminal, and the named keys stay in the pane; keys go out only while [enabled] (a
 * frame shows, nothing opening or rotating). In a gap between frames Escape, Tab and the arrows
 * are swallowed rather than closing the pane or moving the focus.
 */
@Composable
internal fun rememberEmulatorInputKeys(holds: Boolean, enabled: Boolean, keyboard: Boolean, send: (EmulatorInput, Boolean) -> Boolean): Modifier {
    val state = LocalTerminalInputFocus.current
    val active = LocalTerminalKeyboardActive.current
    val token = remember { Any() }
    val keys = remember { EmulatorHardwareKeys() }
    var focused by remember { mutableStateOf(false) }
    val latestSend by rememberUpdatedState(send)
    val owns = active && holds
    SideEffect { state?.update(token, owns && focused); if (!owns) keys.reset() }
    DisposableEffect(state) { onDispose { state?.update(token, false) } }
    return Modifier.focusProperties { canFocus = active }
        .onFocusChanged { focused = it.hasFocus; if (!it.hasFocus) keys.reset(); state?.update(token, owns && it.hasFocus) }
        .onPreviewKeyEvent { owns && keys.key(it.nativeKeyEvent, keyboard) { input, repeat -> enabled && latestSend(input, repeat) } }
}
