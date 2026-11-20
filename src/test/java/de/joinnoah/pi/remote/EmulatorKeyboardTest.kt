package de.joinnoah.pi.remote

import android.view.KeyEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmulatorKeyboardTest {
    private fun fixture(name: String): JsonObject = Wire.json.parseToJsonElement(javaClass.getResource(name)!!.readText()).jsonObject
    private fun inputOf(command: JsonObject): EmulatorInput = (parseEmulatorLeaseCommand(command) as EmulatorCommand.Input).input

    @Test fun keyboardFixtureNeedsTheCapabilityAndAdbShapesDoNot() {
        val commands = fixture("/emulator-keyboard-v1.json").obj("commands")
        for (entry in commands.getValue("valid").jsonArray) {
            val o = entry.jsonObject
            assertEquals(o, encodeEmulatorCommand(parseEmulatorLeaseCommand(o)))
            val input = inputOf(o)
            assertTrue(o.toString(), emulatorInputNegotiated(input, controls = true, keyboard = true))
            assertFalse(o.toString(), emulatorInputNegotiated(input, controls = true, keyboard = false))
            // The keyboard comes only next to the controls.
            assertFalse(o.toString(), emulatorInputNegotiated(input, controls = false, keyboard = true))
        }
        for (entry in commands.getValue("adb").jsonArray) {
            val o = entry.jsonObject
            assertEquals(o, encodeEmulatorCommand(parseEmulatorLeaseCommand(o)))
            assertTrue(o.toString(), emulatorInputNegotiated(inputOf(o), controls = true, keyboard = false))
        }
        for (entry in commands.getValue("invalid").jsonArray)
            assertThrows(entry.toString(), Exception::class.java) { parseEmulatorLeaseCommand(entry.jsonObject) }
    }

    @Test fun controlsFixtureTextsThatNeedTheKeyboardAreRefusedWithoutIt() {
        val needsKeyboard = fixture("/emulator-controls-v1.json").obj("commands").getValue("needsKeyboard").jsonArray
        assertTrue(needsKeyboard.isNotEmpty())
        for (entry in needsKeyboard) {
            val input = inputOf(entry.jsonObject)
            assertFalse(entry.toString(), emulatorInputNegotiated(input, controls = true, keyboard = false))
            assertTrue(entry.toString(), emulatorInputNegotiated(input, controls = true, keyboard = true))
        }
    }

    @Test fun textIsNormalisedToNfcAndCountedInCodePoints() {
        val decomposed = "Grüße"
        assertFalse(emulatorKeyboardTextValid(decomposed))
        assertEquals("Grüße", emulatorNfc(decomposed))
        assertTrue(emulatorKeyboardTextValid(emulatorNfc(decomposed)))
        // 256 emoji are 512 UTF-16 units but 256 code points.
        assertTrue(emulatorKeyboardTextValid("😀".repeat(256)))
        assertFalse(emulatorKeyboardTextValid("😀".repeat(257)))
        assertFalse(emulatorKeyboardTextValid("a‎b"))
        assertFalse(emulatorKeyboardTextValid("a b"))
        assertFalse(emulatorKeyboardTextValid("\uD800"))
        assertFalse(emulatorKeyboardTextValid(""))
    }

    private fun event(keyCode: Int, meta: Int = 0, action: Int = KeyEvent.ACTION_DOWN) = KeyEvent(0, 0, action, keyCode, 0, meta)
    private fun typed(keys: EmulatorHardwareKeys, keyboard: Boolean, vararg events: KeyEvent): Pair<List<Boolean>, List<EmulatorInput>> {
        val sent = mutableListOf<EmulatorInput>()
        return events.map { keys.key(it, keyboard) { input, _ -> sent.add(input) } } to sent
    }

    @Test fun namedKeysAndChordsMapToTheWire() {
        val keys = EmulatorHardwareKeys()
        val (kept, sent) = typed(keys, true,
            event(KeyEvent.KEYCODE_ENTER), event(KeyEvent.KEYCODE_TAB), event(KeyEvent.KEYCODE_ESCAPE), event(KeyEvent.KEYCODE_DPAD_LEFT),
            event(KeyEvent.KEYCODE_DEL), event(KeyEvent.KEYCODE_FORWARD_DEL), event(KeyEvent.KEYCODE_MOVE_HOME), event(KeyEvent.KEYCODE_MOVE_END),
            event(KeyEvent.KEYCODE_PAGE_UP), event(KeyEvent.KEYCODE_PAGE_DOWN),
            event(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),
            event(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
            event(KeyEvent.KEYCODE_C, KeyEvent.META_META_ON),
            event(KeyEvent.KEYCODE_1, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            event(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON),
        )
        assertTrue(kept.all { it })
        assertEquals(listOf(
            EmulatorInput.Key("Enter"), EmulatorInput.Key("Tab"), EmulatorInput.Key("Escape"), EmulatorInput.Key("ArrowLeft"),
            EmulatorInput.Key("Backspace"), EmulatorInput.Key("Delete"), EmulatorInput.Key("MoveHome"), EmulatorInput.Key("MoveEnd"),
            EmulatorInput.Key("PageUp"), EmulatorInput.Key("PageDown"),
            EmulatorInput.Key("Tab", listOf("Shift")),
            EmulatorInput.Key("KeyA", listOf("Control")),
            EmulatorInput.Key("KeyZ", listOf("Shift", "Control")),
            EmulatorInput.Key("KeyC", listOf("Meta")),
            EmulatorInput.Key("Digit1", listOf("Alt")),
            EmulatorInput.Key("Enter", listOf("Shift", "Control", "Alt", "Meta")),
        ), sent)
        // Every mapped input is one the host accepts.
        sent.forEach { assertTrue(it.toString(), emulatorInputNegotiated(it, controls = true, keyboard = true)) }
        // The release of a sent key stays in the pane but sends nothing, so Escape cannot become Back.
        val (upKept, upSent) = typed(keys, true, event(KeyEvent.KEYCODE_ESCAPE, action = KeyEvent.ACTION_UP))
        assertEquals(listOf(true), upKept); assertTrue(upSent.isEmpty())
    }

    @Test fun printableKeysTypeTextIncludingUmlautsAndDeadKeys() {
        val keys = EmulatorHardwareKeys()
        val (kept, sent) = typed(keys, true, event(KeyEvent.KEYCODE_A), event(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON), event(KeyEvent.KEYCODE_SPACE))
        assertTrue(kept.all { it })
        assertEquals(listOf(EmulatorInput.Text("a"), EmulatorInput.Text("A"), EmulatorInput.Text(" ")), sent)
        // A German layout types ü with one key and é after the dead ´: both arrive as NFC text.
        assertEquals("ü", emulatorTypedText(0, 0xFC))
        // Android's composition table, which Robolectric does not carry.
        val acute = { accent: Int, c: Int -> if (accent == 0x301 && c == 'e'.code) 0xE9 else 0 }
        assertEquals("\u00e9", emulatorTypedText(0x301, 'e'.code, acute))
        // An accent with nothing to compose types the character alone, never a stray combining mark.
        assertEquals("x", emulatorTypedText(0x301, 'x'.code, acute))
    }

    @Test fun aRefusedCharacterFallsThroughButARefusedNamedKeyOrRepeatStays() {
        val keys = EmulatorHardwareKeys()
        val repeats = mutableListOf<Boolean>()
        val refuse = { _: EmulatorInput, repeat: Boolean -> repeats.add(repeat); false }
        // A character or letter chord the client no longer takes (the capability went away) reaches the app.
        assertFalse(keys.key(event(KeyEvent.KEYCODE_X), true, refuse))
        assertFalse(keys.key(event(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON), true, refuse))
        // A refused named key stays, so focus traversal cannot take Tab or an arrow off the screen.
        assertTrue(keys.key(event(KeyEvent.KEYCODE_TAB), true, refuse))
        assertTrue(keys.key(event(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON), true, refuse))
        assertTrue(keys.key(event(KeyEvent.KEYCODE_DPAD_DOWN), true, refuse))
        // A held key's repeat is dropped while one is in flight, and stays in the pane.
        assertTrue(keys.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 3, 0), true, refuse))
        assertTrue(keys.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, 3, 0), true, refuse))
        assertEquals(listOf(false, false, false, false, false, true, true), repeats)
    }

    @Test fun unrepresentableKeysAreIgnored() {
        val keys = EmulatorHardwareKeys()
        val (kept, sent) = typed(keys, true,
            event(KeyEvent.KEYCODE_F1),
            event(KeyEvent.KEYCODE_F1, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_HOME), event(KeyEvent.KEYCODE_BACK), event(KeyEvent.KEYCODE_VOLUME_UP),
            event(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON),
        )
        assertTrue(kept.none { it })
        assertTrue(sent.isEmpty())
    }

    @Test fun withoutTheKeyboardOnlyPlainBaseKeysAndAsciiGo() {
        val keys = EmulatorHardwareKeys()
        val (kept, sent) = typed(keys, false,
            event(KeyEvent.KEYCODE_ENTER), event(KeyEvent.KEYCODE_A), event(KeyEvent.KEYCODE_DPAD_UP),
            // No chords and no editing keys: neither goes, not even as its bare key. The named ones
            // stay in the pane, so Shift+Tab cannot move the focus off the screen; Ctrl+A is a shortcut.
            event(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON), event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON), event(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_FORWARD_DEL), event(KeyEvent.KEYCODE_MOVE_HOME), event(KeyEvent.KEYCODE_PAGE_DOWN),
        )
        assertEquals(listOf(true, true, true, true, true, false, true, true, true), kept)
        assertEquals(listOf(EmulatorInput.Key("Enter"), EmulatorInput.Text("a"), EmulatorInput.Key("ArrowUp")), sent)
        sent.forEach { assertTrue(it.toString(), emulatorInputNegotiated(it, controls = true, keyboard = false)) }
        // Non-ASCII text has no ADB form.
        assertNull(EmulatorInput.Text(emulatorTypedText(0, 0xFC)).takeIf { emulatorTextValid(it.text) })
    }
}
