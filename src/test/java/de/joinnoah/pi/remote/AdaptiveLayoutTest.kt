package de.joinnoah.pi.remote

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    private companion object {
        /** A window high enough for two panes, so the width alone picks the bucket. */
        val TALL = 800.dp
    }

    @Test
    fun lowWindowsKeepThePhoneLayoutAtAnyWidth() {
        // A phone in landscape.
        assertEquals(PaneLayout.Phone, paneLayout(780.dp, 360.dp))
        assertEquals(PaneLayout.Phone, paneLayout(1600.dp, 479.dp))
        assertTrue(paneLayout(800.dp, 480.dp).twoPane)
        // A Galaxy Tab S11 in landscape and portrait.
        assertTrue(paneLayout(1205.dp, 753.dp).twoPane)
        assertTrue(paneLayout(753.dp, 1205.dp).twoPane)
    }

    @Test
    fun phoneWidthsKeepTheSinglePaneLayout() {
        for (width in listOf(320.dp, 411.dp, 600.dp, 719.dp)) assertEquals(PaneLayout.Phone, paneLayout(width, TALL))
    }

    @Test
    fun widthClassesFollowTheThresholds() {
        assertTrue(paneLayout(720.dp, TALL).twoPane)
        assertEquals(InspectorMode.OVERLAY, paneLayout(720.dp, TALL).inspector)
        assertEquals(300.dp, paneLayout(720.dp, TALL).listWidth)
        assertEquals(300.dp, paneLayout(959.dp, TALL).listWidth)
        assertEquals(360.dp, paneLayout(960.dp, TALL).listWidth)
        assertEquals(InspectorMode.OVERLAY, paneLayout(1099.dp, TALL).inspector)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1100.dp, TALL).inspector)
        assertEquals(320.dp, paneLayout(1100.dp, TALL).listWidth)
        assertEquals(360.dp, paneLayout(1100.dp, TALL).inspectorWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1279.dp, TALL).inspector)
        assertEquals(360.dp, paneLayout(1280.dp, TALL).listWidth)
        assertEquals(400.dp, paneLayout(1280.dp, TALL).inspectorWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1599.dp, TALL).inspector)
        assertEquals(InspectorMode.PERSISTENT, paneLayout(1600.dp, TALL).inspector)
        assertEquals(360.dp, paneLayout(1600.dp, TALL).listWidth)
        assertEquals(480.dp, paneLayout(1600.dp, TALL).inspectorWidth)
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
        val wide = paneLayout(900.dp, TALL)
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
        assertEquals(KeyboardShortcut.NEW_SESSION, keyboardShortcut(Key.N, ctrl = true, alt = false, shift = false))
        assertEquals(KeyboardShortcut.TOGGLE_LIST, keyboardShortcut(Key.B, ctrl = true, alt = false, shift = false))
        assertNull(keyboardShortcut(Key.N, ctrl = false, alt = false, shift = false))
        assertNull(keyboardShortcut(Key.B, ctrl = false, alt = false, shift = false))
        assertNull(keyboardShortcut(Key.N, ctrl = false, alt = true, shift = false))
        assertNull(keyboardShortcut(Key.N, ctrl = true, alt = false, shift = true))
    }

    @Test
    fun canCreateSessionFollowsTheFab() {
        val sessions = RemoteNavKey.Sessions("host", "project")
        val ready = RemoteState(connected = true, selection = RemoteSelection("host", "project"))
        assertTrue(canCreateSession(ready, sessions))
        assertFalse(canCreateSession(ready.copy(loading = true), sessions))
        assertFalse(canCreateSession(ready.copy(connected = false), sessions))
        assertFalse(canCreateSession(ready.copy(selection = RemoteSelection("host", "other")), sessions))
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

    @Test
    fun listWidthStaysWithinItsBoundsAndLeavesTheChatRoom() {
        assertEquals(240.dp, listPaneWidth(100.dp, 1600.dp, InspectorMode.PERSISTENT))
        assertEquals(480.dp, listPaneWidth(900.dp, 1600.dp, InspectorMode.PERSISTENT))
        // 400 dp of chat and the divider line.
        assertEquals(319.dp, listPaneWidth(500.dp, 720.dp, InspectorMode.OVERLAY))
        // Also the narrowest inspector pane and its divider.
        assertEquals(398.dp, listPaneWidth(500.dp, 1100.dp, InspectorMode.ON_DEMAND))
    }

    @Test
    fun inspectorWidthStaysWithinItsBoundsAndLeavesTheChatRoom() {
        assertEquals(378.dp, inspectorPaneWidth(900.dp, 779.dp))
        assertEquals(300.dp, inspectorPaneWidth(100.dp, 779.dp))
        assertEquals(720.dp, inspectorPaneWidth(900.dp, 2000.dp))
    }

    @Test
    fun defaultsSurviveTheClampsAndTheChatKeepsItsMinimum() {
        for (window in 720..2560) {
            val width = window.dp
            val layout = paneLayout(width, TALL)
            val side = layout.inspector != InspectorMode.OVERLAY
            val list = listPaneWidth(layout.listWidth, width, layout.inspector)
            assertEquals("list at $window", layout.listWidth, list)
            if (side) assertEquals("inspector at $window", layout.inspectorWidth, inspectorPaneWidth(layout.inspectorWidth, width - list - 1.dp))
            // The widest both panes can be dragged still leaves the chat its minimum.
            val widestList = listPaneWidth(10_000.dp, width, layout.inspector)
            val chatPane = width - widestList - 1.dp
            val chat = if (side) chatPane - inspectorPaneWidth(10_000.dp, chatPane) - 1.dp else chatPane
            assertTrue("chat at $window is $chat", chat >= CHAT_PANE_MIN_WIDTH)
        }
    }

    @Test
    fun draggedWidthsSurviveRecreation() {
        val layout = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        state.adopt(layout)
        state.resizeList(layout, 420.dp)
        state.resizeInspector(layout, 500.dp)
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val restored = TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(state) }!!)!!
        // Adopting the same bucket after recreation keeps them.
        restored.adopt(layout)
        assertEquals(420.dp, restored.listWidth(layout))
        assertEquals(500.dp, restored.inspectorWidth(layout))
        // Untouched widths come back as the defaults.
        val fresh = TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(TwoPaneState()) }!!)!!
        assertEquals(layout.listWidth, fresh.listWidth(layout))
        assertEquals(layout.inspectorWidth, fresh.inspectorWidth(layout))
    }

    @Test
    fun draggedWidthsResetOnlyWhenTheBucketChanges() {
        val wide = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        state.adopt(wide)
        state.resizeList(wide, 420.dp)
        // Another width in the same bucket is the same layout.
        state.adopt(paneLayout(1500.dp, TALL))
        assertEquals(420.dp, state.listWidth(wide))
        // Another bucket shows its own default at once and drops the dragged width.
        val narrow = paneLayout(900.dp, TALL)
        assertEquals(narrow.listWidth, state.listWidth(narrow))
        state.adopt(narrow)
        state.adopt(wide)
        assertEquals(wide.listWidth, state.listWidth(wide))
        state.resizeList(wide, 420.dp)
        state.resizeList(wide, null)
        assertEquals(wide.listWidth, state.listWidth(wide))
    }

    @Test
    fun backPreviewWithACollapsedListKeepsTheChat() {
        val keys = RemoteSelection("host", "project", "s1").keys()
        val entries =
            keys.map { key ->
                androidx.navigation3.runtime.NavEntry<androidx.navigation3.runtime.NavKey>(
                    key,
                    key.toString(),
                    if (key is RemoteNavKey.Sessions || key is RemoteNavKey.Chat) navKeyMetadata(key) else emptyMap(),
                ) {}
            }
        fun scene(collapsed: Boolean) =
            with(ListDetailSceneStrategy(paneLayout(900.dp, TALL), TwoPaneState(), collapsed)) {
                androidx.navigation3.scene.SceneStrategyScope<androidx.navigation3.runtime.NavKey>().calculateScene(entries)
            }!!
        // Collapsed: no preview of a popped chat; the scene's back handler expands the list.
        assertEquals(emptyList<Any>(), scene(collapsed = true).previousEntries)
        assertEquals(listOf(entries[3]), scene(collapsed = true).entries)
        assertEquals(entries.dropLast(1), scene(collapsed = false).previousEntries)
        assertEquals(entries.takeLast(2), scene(collapsed = false).entries)
        // Without a chat there is nothing to collapse: back leaves the sessions as usual.
        val sessionsOnly =
            with(ListDetailSceneStrategy(paneLayout(900.dp, TALL), TwoPaneState(), true)) {
                androidx.navigation3.scene.SceneStrategyScope<androidx.navigation3.runtime.NavKey>()
                    .calculateScene(entries.dropLast(1))
            }!!
        assertEquals(entries.dropLast(2), sessionsOnly.previousEntries)
    }
}
