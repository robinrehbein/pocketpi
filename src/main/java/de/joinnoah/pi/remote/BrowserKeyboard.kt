package de.joinnoah.pi.remote

import android.view.KeyEvent

/** Maps hardware input to viewport text or DOM key events. Only held keys may be released. */
internal fun browserHardwareInput(event: KeyEvent, enabled: Boolean, pressedCodes: Set<String>, unicode: Int = event.unicodeChar): BrowserInput? {
    if (!enabled || event.keyCode == KeyEvent.KEYCODE_F6 || event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return null
    val named = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter" to "Enter"
        KeyEvent.KEYCODE_TAB -> "Tab" to "Tab"
        KeyEvent.KEYCODE_ESCAPE -> "Escape" to "Escape"
        KeyEvent.KEYCODE_DEL -> "Backspace" to "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete" to "Delete"
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp" to "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown" to "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft" to "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight" to "ArrowRight"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home" to "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End" to "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp" to "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown" to "PageDown"
        else -> null
    }
    val altGr = event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
    val printable = Character.isValidCodePoint(unicode) && !Character.isISOControl(unicode) && unicode != 0
    val letter = event.keyCode - KeyEvent.KEYCODE_A
    val digit = event.keyCode - KeyEvent.KEYCODE_0
    val pair = named ?: when {
        letter in 0..25 -> {
            val character = ('a'.code + letter).toChar()
            (if (event.isShiftPressed) character.uppercase() else character.toString()) to "Key${character.uppercase()}"
        }
        digit in 0..9 -> digit.toString() to "Digit$digit"
        else -> null
    }
    if (event.action == KeyEvent.ACTION_UP && (pair == null || pair.second !in pressedCodes)) return null
    if (event.action == KeyEvent.ACTION_DOWN && named == null && printable && ((!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) || altGr)) {
        return BrowserInput.Text(String(Character.toChars(unicode)))
    }
    pair ?: return null
    return BrowserInput.Key(if (event.action == KeyEvent.ACTION_DOWN) "down" else "up", pair.first, pair.second,
        event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
}
