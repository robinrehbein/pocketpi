package de.joinnoah.pi.remote

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun phoneWidthsKeepTheSinglePaneLayout() {
        for (width in listOf(320.dp, 411.dp, 600.dp, 799.dp)) assertEquals(PaneLayout.Phone, paneLayout(width))
    }

    @Test
    fun widthClassesFollowTheThresholds() {
        assertTrue(paneLayout(800.dp).twoPane)
        assertEquals(InspectorMode.OVERLAY, paneLayout(800.dp).inspector)
        assertEquals(InspectorMode.OVERLAY, paneLayout(1279.dp).inspector)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1280.dp).inspector)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1599.dp).inspector)
        assertEquals(InspectorMode.PERSISTENT, paneLayout(1600.dp).inspector)
        assertEquals(320.dp, paneLayout(800.dp).listWidth)
        assertEquals(360.dp, paneLayout(1100.dp).listWidth)
    }

    @Test
    fun aChatPairsWithTheSessionsOfItsProject() {
        val keys = RemoteSelection("host", "project", "s1").keys()
        assertEquals(ListDetailPanes(2, 3), listDetailPanes(keys))
        assertEquals(ListDetailPanes(2, null), listDetailPanes(keys.dropLast(1)))
    }

    @Test
    fun aStackedChildChatStillPairsWithTheSessions() {
        val keys =
            RemoteSelection("host", "project", "parent").keys() +
                RemoteNavKey.Chat("host", "project", "child")
        assertEquals(ListDetailPanes(2, 4), listDetailPanes(keys))
    }

    @Test
    fun otherDestinationsStayFullScreen() {
        assertNull(listDetailPanes(listOf(RemoteNavKey.Hosts)))
        assertNull(listDetailPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))))
        assertNull(
            listDetailPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host")))
        )
        assertNull(listDetailPanes(RemoteSelection("host", "project", "s1").keys() + RemoteNavKey.Settings))
        // A chat without its project's Sessions below it has nothing to pair with.
        assertNull(listDetailPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Chat("host", "project", "s1"))))
        assertNull(
            listDetailPanes(listOf(RemoteNavKey.Sessions("host", "other"), RemoteNavKey.Chat("host", "project", "s1")))
        )
    }

    @Test
    fun backExpandsACollapsedListBeforeLeavingTheChat() {
        val chat = RemoteNavKey.Chat("host", "project", "s1")
        val wide = paneLayout(900.dp)
        assertEquals(BackStep.EXPAND_LIST, twoPaneBackStep(wide, listCollapsed = true, top = chat))
        assertEquals(BackStep.NAVIGATE, twoPaneBackStep(wide, listCollapsed = false, top = chat))
        assertEquals(BackStep.NAVIGATE, twoPaneBackStep(wide, listCollapsed = true, top = RemoteNavKey.Settings))
        // The phone never collapses anything.
        assertEquals(BackStep.NAVIGATE, twoPaneBackStep(PaneLayout.Phone, listCollapsed = true, top = chat))
    }

    @Test
    fun shortcutsNeedTheirModifiers() {
        assertEquals(KeyboardShortcut.SEARCH, keyboardShortcut(Key.K, ctrl = true, alt = false, shift = false))
        assertEquals(KeyboardShortcut.SEARCH, keyboardShortcut(Key.F, ctrl = true, alt = false, shift = false))
        assertNull(keyboardShortcut(Key.K, ctrl = false, alt = false, shift = false))
        assertEquals(
            KeyboardShortcut.NEXT_SESSION,
            keyboardShortcut(Key.DirectionDown, ctrl = true, alt = false, shift = false),
        )
        assertEquals(
            KeyboardShortcut.PREVIOUS_SESSION,
            keyboardShortcut(Key.DirectionUp, ctrl = false, alt = true, shift = false),
        )
        assertNull(keyboardShortcut(Key.DirectionUp, ctrl = false, alt = false, shift = false))
        assertNull(keyboardShortcut(Key.DirectionUp, ctrl = true, alt = false, shift = true))
    }

    @Test
    fun adjacentSessionStopsAtTheEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", adjacentSession(ids, "a", 1))
        assertEquals("a", adjacentSession(ids, "b", -1))
        assertNull(adjacentSession(ids, "c", 1))
        assertNull(adjacentSession(ids, "a", -1))
        assertEquals("a", adjacentSession(ids, null, 1))
        assertEquals("c", adjacentSession(ids, null, -1))
        assertNull(adjacentSession(emptyList(), null, 1))
    }

    @Test
    fun enterFollowsTheSetting() {
        fun enter(ctrl: Boolean = false, shift: Boolean = false, hardware: Boolean = true, on: Boolean = true) =
            composerEnter(Key.Enter, keyDown = true, ctrl = ctrl, shift = shift, alt = false, hardwareKeyboard = hardware, enterSends = on)
        assertEquals(ComposerEnter.SEND, enter())
        assertEquals(ComposerEnter.NEWLINE, enter(shift = true))
        assertEquals(ComposerEnter.SEND, enter(ctrl = true))
        assertEquals(ComposerEnter.DEFAULT, enter(on = false))
        assertEquals(ComposerEnter.DEFAULT, enter(shift = true, on = false))
        assertEquals(ComposerEnter.SEND, enter(ctrl = true, on = false))
        // Without a hardware keyboard nothing changes.
        assertEquals(ComposerEnter.DEFAULT, enter(hardware = false))
        assertEquals(ComposerEnter.DEFAULT, enter(ctrl = true, hardware = false))
        assertEquals(
            ComposerEnter.DEFAULT,
            composerEnter(Key.A, keyDown = true, ctrl = false, shift = false, alt = false, hardwareKeyboard = true, enterSends = true),
        )
        assertEquals(
            ComposerEnter.DEFAULT,
            composerEnter(Key.Enter, keyDown = false, ctrl = false, shift = false, alt = false, hardwareKeyboard = true, enterSends = true),
        )
    }

    @Test
    fun searchMatchesTitleAndPreview() {
        fun item(id: String, title: String, preview: String? = null) =
            SessionListItem(id, title, null, 0, false, SessionAvailability.IDLE, preview, null, false, false, false)
        val items = listOf(item("1", "Fix login"), item("2", "Docs", preview = "Update the LOGIN guide"), item("3", "Other"))
        assertEquals(listOf("1", "2"), filterSessions(items, " login ").map { it.id })
        assertEquals(items, filterSessions(items, "  "))
    }

    @Test
    fun theCollapsedListSurvivesRecreation() {
        val state = TwoPaneState()
        state.listCollapsed = true
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(TwoPaneState.Saver) { scope.save(state) }
        assertTrue(TwoPaneState.Saver.restore(saved!!)!!.listCollapsed)
        state.requestSearch()
        assertFalse(state.listCollapsed)
        assertEquals(1, state.searchRequests)
    }
}
