package de.joinnoah.pi.remote

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserKeyboardTest {
    private fun key(action: Int, code: Int, meta: Int = 0) = KeyEvent(0, 0, action, code, 0, meta)

    @Test fun printableEnglishGermanAndAltGrBecomeTextOnlyOnDown() {
        assertEquals(BrowserInput.Text("a"), browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), true, emptySet(), 'a'.code))
        assertEquals(BrowserInput.Text("ä"), browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), true, emptySet(), 'ä'.code))
        assertEquals(BrowserInput.Text("@"), browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_RIGHT_ON), true, emptySet(), '@'.code))
        assertNull(browserHardwareInput(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A), true, emptySet(), 'a'.code))
    }
    @Test fun chordsUseDomCodeAndReleaseRequiresPriorDown() {
        val down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        val up = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        assertEquals(BrowserInput.Key("down", "a", "KeyA", ctrl = true), browserHardwareInput(down, true, emptySet(), 'a'.code))
        assertNull(browserHardwareInput(up, true, emptySet(), 'a'.code))
        assertEquals(BrowserInput.Key("up", "a", "KeyA", ctrl = true), browserHardwareInput(up, true, setOf("KeyA"), 'a'.code))
        assertEquals(BrowserInput.Key("down", "ArrowUp", "ArrowUp"), browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP), true, emptySet(), 0))
    }
    @Test fun hiddenOrLocalPaneShortcutNeverProducesInput() {
        assertNull(browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), false, emptySet(), 'a'.code))
        assertNull(browserHardwareInput(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F6), true, emptySet(), 0))
    }
}
