package de.joinnoah.pi.remote

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the platform's KeyEvent in metaCountsAsCtrl.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeymapTest {
    private fun ctrl(key: Key, shift: Boolean = false, context: KeyContext = KeyContext.CHAT) =
        resolveShortcut(key, ctrl = true, alt = false, shift = shift, context = context)

    private fun alt(key: Key, context: KeyContext = KeyContext.GLOBAL) =
        resolveShortcut(key, ctrl = false, alt = true, shift = false, context = context)

    private fun plain(key: Key, context: KeyContext = KeyContext.CHAT) =
        resolveShortcut(key, ctrl = false, alt = false, shift = false, context = context)

    @Test
    fun theExistingShortcutsKeepTheirKeys() {
        assertEquals(Shortcut.SEARCH, ctrl(Key.K, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.SEARCH, ctrl(Key.F, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.NEW_SESSION, ctrl(Key.N, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.TOGGLE_LIST, ctrl(Key.B, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.NEXT_SESSION, ctrl(Key.DirectionDown, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.PREVIOUS_SESSION, ctrl(Key.DirectionUp, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.NEXT_SESSION, alt(Key.DirectionDown))
        assertEquals(Shortcut.PREVIOUS_SESSION, alt(Key.DirectionUp))
        assertEquals(Shortcut.PREVIOUS_PROJECT, alt(Key.PageUp))
        assertEquals(Shortcut.NEXT_PROJECT, alt(Key.PageDown))
    }

    @Test
    fun theNewShortcutsHaveTheirKeys() {
        assertEquals(Shortcut.FOCUS_COMPOSER, ctrl(Key.L))
        assertEquals(Shortcut.STOP, ctrl(Key.Period))
        assertEquals(Shortcut.CHANGES, ctrl(Key.G, shift = true))
        assertEquals(Shortcut.FILES, ctrl(Key.E, shift = true))
        assertEquals(Shortcut.JOBS, ctrl(Key.J, shift = true))
        assertEquals(Shortcut.HISTORY, ctrl(Key.H))
        assertEquals(Shortcut.SETTINGS, ctrl(Key.Comma, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.HELP, ctrl(Key.Slash, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.EXPAND_INSPECTOR, ctrl(Key.B, shift = true))
        assertEquals(Shortcut.TOGGLE_INSPECTOR, ctrl(Key.Backslash))
        assertEquals(Shortcut.REFRESH, ctrl(Key.R, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.RENAME, ctrl(Key.R, shift = true))
        assertEquals(Shortcut.FORK, ctrl(Key.N, shift = true))
        assertEquals(Shortcut.ESCAPE, plain(Key.Escape))
        assertEquals(Shortcut.OTHER_BUSY_ACTION, alt(Key.Enter, context = KeyContext.COMPOSER))
        assertEquals(Shortcut.OTHER_BUSY_ACTION, alt(Key.NumPadEnter, context = KeyContext.COMPOSER))
    }

    @Test
    fun everyFKeyHasACtrlAlias() {
        // The Book Cover Keyboard needs Fn for them.
        assertEquals(Shortcut.REFRESH, plain(Key.F5, context = KeyContext.GLOBAL))
        assertEquals(Shortcut.RENAME, plain(Key.F2))
        val fKeys = setOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
        for (binding in KEYMAP.filter { it.key in fKeys })
            assertTrue(
                "${binding.shortcut} needs a Ctrl chord",
                KEYMAP.any { it.shortcut == binding.shortcut && it.ctrl && it.key !in fKeys },
            )
    }

    @Test
    fun metaCountsAsCtrl() {
        val down = android.view.KeyEvent(
            0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_K, 0,
            android.view.KeyEvent.META_META_ON or android.view.KeyEvent.META_META_LEFT_ON,
        )
        assertEquals(Shortcut.SEARCH, resolveShortcut(androidx.compose.ui.input.key.KeyEvent(down), KeyContext.GLOBAL))
    }

    @Test
    fun modifiersMustMatchExactly() {
        assertNull(plain(Key.K, KeyContext.GLOBAL))
        assertNull(plain(Key.DirectionUp, KeyContext.GLOBAL))
        assertNull(resolveShortcut(Key.DirectionUp, ctrl = true, alt = false, shift = true, context = KeyContext.CHAT))
        assertNull(plain(Key.N))
        assertNull(plain(Key.B))
        assertNull(alt(Key.N))
        // Shift chords are their own shortcuts, not the plain ones.
        assertNull(ctrl(Key.K, shift = true))
        assertEquals(Shortcut.FORK, ctrl(Key.N, shift = true))
        assertNull(plain(Key.PageDown, KeyContext.GLOBAL))
        assertNull(resolveShortcut(Key.PageDown, ctrl = true, alt = true, shift = false, context = KeyContext.GLOBAL))
        assertNull(resolveShortcut(Key.PageDown, ctrl = false, alt = true, shift = true, context = KeyContext.GLOBAL))
        // Enter alone stays with the composer's own handling, Ctrl+Tab and Ctrl+digits stay free.
        assertNull(plain(Key.Enter, KeyContext.COMPOSER))
        assertNull(ctrl(Key.Tab, context = KeyContext.COMPOSER))
        for (digit in listOf(Key.One, Key.Two, Key.Nine)) assertNull(ctrl(digit, context = KeyContext.COMPOSER))
    }

    @Test
    fun aBindingAppliesInItsContextAndTheNarrowerOnes() {
        // Chat keys need a chat; global ones work everywhere.
        assertNull(ctrl(Key.L, context = KeyContext.GLOBAL))
        assertNull(plain(Key.Escape, KeyContext.GLOBAL))
        assertEquals(Shortcut.SEARCH, ctrl(Key.K, context = KeyContext.COMPOSER))
        assertEquals(Shortcut.STOP, ctrl(Key.Period, context = KeyContext.COMPOSER))
        // Alt+Enter only means something inside the composer.
        assertNull(alt(Key.Enter, context = KeyContext.CHAT))
    }

    @Test
    fun onlyEscFKeysAndPageKeysGoWithoutAModifier() {
        val allowed = setOf(Key.Escape, Key.F2, Key.F5, Key.PageUp, Key.PageDown)
        for (binding in KEYMAP.filter { !it.ctrl && !it.alt })
            assertTrue("${binding.shortcut} on ${binding.key}", binding.key in allowed)
    }

    @Test
    fun noTwoBindingsShareAChordInOneContext() {
        val chords = KEYMAP.map { listOf(it.key, it.ctrl, it.alt, it.shift) }
        assertEquals(chords.size, chords.toSet().size)
    }

    @Test
    fun everyShortcutHasAKeyAndALabel() {
        for (shortcut in Shortcut.entries) {
            val bindings = KEYMAP.filter { it.shortcut == shortcut }
            assertTrue("$shortcut has no key", bindings.isNotEmpty())
            assertEquals("$shortcut has one label", 1, bindings.map { it.labelRes }.toSet().size)
        }
    }

    @Test
    fun typedTextIsAPlainPrintableCharacter() {
        assertEquals("a", typedText('a'.code, ctrl = false, alt = false, meta = false))
        assertEquals("/", typedText('/'.code, ctrl = false, alt = false, meta = false))
        assertEquals("ü", typedText('ü'.code, ctrl = false, alt = false, meta = false))
        assertNull(typedText('a'.code, ctrl = true, alt = false, meta = false))
        assertNull(typedText('a'.code, ctrl = false, alt = true, meta = false))
        assertNull(typedText('a'.code, ctrl = false, alt = false, meta = true))
        assertNull(typedText(0, ctrl = false, alt = false, meta = false))
        assertNull(typedText('\n'.code, ctrl = false, alt = false, meta = false))
        assertNull(typedText('\t'.code, ctrl = false, alt = false, meta = false))
        // A dead key's accent waits for the next key.
        assertNull(typedText('´'.code or android.view.KeyCharacterMap.COMBINING_ACCENT, ctrl = false, alt = false, meta = false))
    }

    @Test
    fun altGrTypesItsCharacterButLeftAltDoesNot() {
        val right = android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_RIGHT_ON
        val left = android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON
        assertTrue(altGrOnly(right))
        assertFalse(altGrOnly(left))
        assertFalse(altGrOnly(left or android.view.KeyEvent.META_ALT_RIGHT_ON))
        assertFalse(altGrOnly(0))
        // German QWERTZ: AltGr+Q is @, AltGr+E is the euro sign.
        assertEquals("@", typedText('@'.code, ctrl = false, alt = true, meta = false, altGr = true))
        assertEquals("\u20ac", typedText(0x20ac, ctrl = false, alt = true, meta = false, altGr = true))
        assertNull(typedText('@'.code, ctrl = true, alt = true, meta = false, altGr = true))
        assertNull(typedText('q'.code, ctrl = false, alt = true, meta = false, altGr = false))
    }

    @Test
    fun aListThatLeftTheScreenNoLongerHoldsItsRows() {
        val targets = ShortcutTargets()
        val sessions = RemoteNavKey.Sessions("host", "project")
        val other = RemoteNavKey.Sessions("host", "other")
        targets.listed(sessions, listOf("a", "b"))
        // Another project's late dispose leaves this listing alone.
        targets.unlisted(other)
        assertEquals(sessions, targets.listedFor)
        targets.unlisted(sessions)
        assertNull(targets.listedFor)
        assertEquals(emptyList<String>(), targets.listedSessionIds)
    }

    @Test
    fun searchRequestsCountAndListedSessionsBelongToTheirProject() {
        val targets = ShortcutTargets()
        targets.requestSearch()
        targets.requestSearch()
        assertEquals(2, targets.searchRequests)
        assertEquals(0, targets.searchHandled)
        val sessions = RemoteNavKey.Sessions("host", "project")
        targets.listed(sessions, listOf("a", "b"))
        assertEquals(sessions, targets.listedFor)
        assertEquals(listOf("a", "b"), targets.listedSessionIds)
    }

    @Test
    fun aChatTargetLeavesOnlyWithItsOwnRegistration() {
        val targets = ShortcutTargets()
        val chat = RemoteNavKey.Chat("host", "project", "s1")
        val first = ChatKeyTarget()
        val second = ChatKeyTarget()
        targets.register(chat, first)
        targets.register(chat, second)
        // The leaving screen's late dispose must not drop the new one.
        targets.unregister(chat, first)
        assertEquals(second, targets.chat(chat))
        targets.unregister(chat, second)
        assertNull(targets.chat(chat))
    }
}
