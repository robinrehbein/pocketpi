package de.joinnoah.pi.remote

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutHelpTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun groups(): List<KeyboardShortcutGroup> {
        // Built but not created: the override needs no window or repository.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val data = mutableListOf<KeyboardShortcutGroup>()
        activity.onProvideKeyboardShortcuts(data, null, -1)
        return data
    }

    @Test
    fun theSystemHelperListsEveryGroupWithItsKeys() {
        val groups = groups()
        assertEquals(listOf("Navigation", "Chat", "Message", "Panes"), groups.map { it.label.toString() })
        val navigation = groups[0].items
        val search = navigation.filter { it.label == "Search sessions" }
        assertEquals(listOf(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F), search.map { it.keycode })
        assertTrue(search.all { it.modifiers == KeyEvent.META_CTRL_ON })
        // Ctrl+N and Ctrl+B, which the old Settings list left out.
        assertTrue(navigation.any { it.label == "New session" && it.keycode == KeyEvent.KEYCODE_N })
        assertTrue(groups[3].items.any { it.keycode == KeyEvent.KEYCODE_B && it.modifiers == KeyEvent.META_CTRL_ON })
        val changes = groups[3].items.single { it.label == "Changes" }
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, changes.modifiers)
        assertTrue(groups[2].items.any { it.keycode == KeyEvent.KEYCODE_ENTER && it.modifiers == KeyEvent.META_ALT_ON })
        // The inspector's keys have their actions now.
        val labels = groups.flatMap { group -> group.items.map { it.label.toString() } }
        assertTrue(labels.contains(context.getString(R.string.remote_keymap_toggle_inspector)))
        assertTrue(labels.contains(context.getString(R.string.remote_keymap_expand_inspector)))
    }

    @Test
    @Config(qualifiers = "de")
    fun theSystemHelperSpeaksTheAppsLanguage() {
        val groups = groups()
        assertEquals(listOf("Navigation", "Chat", "Nachricht", "Bereiche"), groups.map { it.label.toString() })
        assertTrue(groups[0].items.any { it.label == "Sitzungen durchsuchen" })
        assertTrue(groups[0].items.any { it.label == "Nächster Bereich" && it.keycode == KeyEvent.KEYCODE_F6 })
    }

    @Test
    fun chordsNameTheirKeys() {
        val resources = context.resources
        assertEquals("Ctrl+Shift+G", chordText(KeyChord(Key.G, ctrl = true, shift = true), resources))
        assertEquals("Alt+Page Down", chordText(KeyChord(Key.PageDown, alt = true), resources))
        assertEquals("Ctrl+Alt+→", chordText(KeyChord(Key.DirectionRight, ctrl = true, alt = true), resources))
        assertEquals("Shift+F6", chordText(KeyChord(Key.F6, shift = true), resources))
        assertEquals("Ctrl+/", chordText(KeyChord(Key.Slash, ctrl = true), resources))
        assertEquals("Ctrl+End", chordText(KeyChord(Key.MoveEnd, ctrl = true), resources))
    }

    @Test
    @Config(qualifiers = "de")
    fun germanChordsUseTheGermanKeyCaps() {
        val resources = context.resources
        assertEquals("Strg+Umschalt+G", chordText(KeyChord(Key.G, ctrl = true, shift = true), resources))
        assertEquals("Alt+Bild auf", chordText(KeyChord(Key.PageUp, alt = true), resources))
        assertEquals("Strg+Pos1", chordText(KeyChord(Key.MoveHome, ctrl = true), resources))
    }

    @Test
    @Config(qualifiers = "de")
    fun characterKeysAreNamedAfterTheAttachedKeyboardsLayout() {
        val resources = context.resources
        // A German layout puts ß on the key that sends the slash key code, and swaps Y and Z.
        val german = mapOf(KeyEvent.KEYCODE_SLASH to 'ß', KeyEvent.KEYCODE_Z to 'z', KeyEvent.KEYCODE_PERIOD to '.')
        assertEquals("Strg+ß", chordText(KeyChord(Key.Slash, ctrl = true), resources) { german[it] })
        assertEquals("Strg+Z", chordText(KeyChord(Key.Z, ctrl = true), resources) { german[it] })
        // Keys without a character keep their names, whatever the layout says.
        assertEquals("Strg+Pos1", chordText(KeyChord(Key.MoveHome, ctrl = true), resources) { 'x' })
        assertEquals("F6", chordText(KeyChord(Key.F6), resources) { 'x' })
        // Without a layout label the fixed names stay.
        assertEquals("Strg+/", chordText(KeyChord(Key.Slash, ctrl = true), resources) { null })
    }

    @Test
    fun ctrlWithWhateverTypesASlashOpensTheHelp() {
        assertEquals(Shortcut.HELP, typedShortcut('/'.code, ctrl = true, alt = false))
        assertEquals(null, typedShortcut('/'.code, ctrl = false, alt = false))
        assertEquals(null, typedShortcut('/'.code, ctrl = true, alt = true))
        assertEquals(null, typedShortcut('7'.code, ctrl = true, alt = false))
    }

    @Test
    fun theListsFollowWhereTheUserIs() {
        val global = shortcutEntries(KeyContext.GLOBAL)
        val chat = shortcutEntries(KeyContext.COMPOSER)
        // Outside a chat only the keys that work there.
        assertTrue(global.none { it.context != KeyContext.GLOBAL })
        assertTrue(global.any { it.labelRes == R.string.remote_keymap_new_session })
        assertTrue(global.none { it.labelRes == R.string.remote_keymap_stop })
        // In a chat also the chat's and the composer's own keys.
        assertTrue(chat.any { it.labelRes == R.string.remote_keymap_stop })
        assertTrue(chat.any { it.labelRes == R.string.remote_keymap_page_down })
        assertTrue(chat.any { it.labelRes == R.string.remote_shortcut_complete })
        // One line per action, with all its keys.
        assertEquals(1, chat.count { it.labelRes == R.string.remote_keymap_search })
        assertEquals(2, chat.single { it.labelRes == R.string.remote_keymap_search }.chords.size)
        // Settings shows everything.
        assertEquals(shortcutEntries(null).size, chat.size)
    }

    @Test
    fun tooltipsNameTheCtrlChordOverAnFKey() {
        assertEquals(KeyChord(Key.R, ctrl = true, shift = true), tooltipChord(Shortcut.RENAME))
        assertEquals(KeyChord(Key.G, ctrl = true, shift = true), tooltipChord(Shortcut.CHANGES))
        assertEquals(KeyChord(Key.Escape), tooltipChord(Shortcut.ESCAPE))
    }
}
