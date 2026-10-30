package de.joinnoah.pi.remote

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalKeyboardTest {
    @Test fun hardwareChordsDoNotConsumeAltGrOrF6() {
        val sent = mutableListOf<TerminalInput.Chord>()
        val ctrlC = NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_C, 0, NativeKeyEvent.META_CTRL_ON)
        assertTrue(terminalHardwareChord(ctrlC, sent::add))
        assertEquals(listOf(TerminalInput.Chord("c", ctrl = true)), sent)
        val altGr = NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_E, 0, NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_ALT_ON)
        assertFalse(terminalHardwareChord(altGr, sent::add))
        assertTrue(terminalHardwareChord(altGr, sent::add, allowCtrlAlt = true))
        assertEquals(TerminalInput.Chord("e", ctrl = true, alt = true), sent.last())
        val rightAlt = NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_E, 0, NativeKeyEvent.META_ALT_ON or NativeKeyEvent.META_ALT_RIGHT_ON)
        assertFalse(terminalHardwareChord(rightAlt, sent::add, allowCtrlAlt = true))
        val shifted = NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_A, 0, NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_SHIFT_ON)
        assertTrue(terminalHardwareChord(shifted, sent::add))
        assertEquals(TerminalInput.Chord("a", ctrl = true, shift = true, shifted = "A"), sent.last())
        assertFalse(terminalHardwareChord(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_F6), sent::add))
        assertTrue(terminalHardwareChord(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_F1), sent::add))
        assertEquals("f1", sent.last().name)
    }
    @Test fun focusedTerminalKeepsEscapeBeforeChatNavigation() = runTest {
        val chat = RemoteNavKey.Chat("host", "project", "session")
        val repository = NavigationFakeRepository()
        val stack = chat.selection().keys().toMutableList<NavKey>()
        val navigator = RemoteNavigator(repository, stack, backgroundScope)
        val target = ChatKeyTarget()
        var closed = 0
        target.addPane { closed++ }
        val targets = ShortcutTargets().apply { register(chat, target) }
        val token = Any()
        navigator.terminalInputFocus.update(token, true)
        for (action in listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP)) {
            assertFalse(navigator.key(KeyEvent(NativeKeyEvent(action, NativeKeyEvent.KEYCODE_ESCAPE)), null, targets, unhandled = false))
        }
        assertEquals(0, closed)
        navigator.terminalInputFocus.update(token, false)
        assertTrue(navigator.key(KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ESCAPE)), null, targets, unhandled = false))
        assertEquals(1, closed)
    }

    @Test fun oldTerminalDisposalDoesNotStealNewTerminalFocus() {
        val state = TerminalInputFocus()
        val old = Any()
        val current = Any()
        state.update(old, true)
        state.update(current, true)
        state.update(old, false)
        assertTrue(state.focused)
        state.update(current, false)
        assertFalse(state.focused)
    }
    @Test fun terminalEscapeReleaseIsConsumedWithoutSendingAnotherKey() {
        val sent = mutableListOf<String>()
        for (action in listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP)) {
            assertTrue(terminalHardwareKey(NativeKeyEvent(action, NativeKeyEvent.KEYCODE_ESCAPE), true, sent::add))
        }
        assertEquals(listOf("escape"), sent)
    }

    @Test fun terminalKeepsBackspaceLocalUntilDraftIsEmpty() {
        val sent = mutableListOf<String>()
        val event = NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DEL)
        assertFalse(terminalHardwareKey(event, false, sent::add))
        assertTrue(terminalHardwareKey(event, true, sent::add))
        assertEquals(listOf("backspace"), sent)
    }

    @Test fun ctrlShortcutsStayOutOfAppNavigationWhileTerminalHasFocus() = runTest {
        val chat = RemoteNavKey.Chat("host", "project", "session")
        val navigator = RemoteNavigator(NavigationFakeRepository(), chat.selection().keys().toMutableList<NavKey>(), backgroundScope)
        val targets = ShortcutTargets()
        val owner = Any()
        navigator.terminalInputFocus.update(owner, true)
        val ctrlK = KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_K, 0, NativeKeyEvent.META_CTRL_ON))
        assertFalse(navigator.key(ctrlK, null, targets, unhandled = false))
        assertFalse(navigator.key(ctrlK, null, targets, unhandled = true))
        assertNull(targets.palette)
        val f6 = KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_F6))
        assertTrue(navigator.key(f6, null, targets, unhandled = false))
        navigator.terminalInputFocus.update(owner, false)
        assertTrue(navigator.key(ctrlK, null, targets, unhandled = false))
        assertNotNull(targets.palette)
    }

}
