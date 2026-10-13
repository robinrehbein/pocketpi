package de.joinnoah.pi.remote

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
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
        assertEquals(240.dp, paneLayout(720.dp, TALL).listWidth)
        assertEquals(240.dp, paneLayout(720.dp, TALL).projectsWidth)
        assertEquals(240.dp, paneLayout(749.dp, TALL).listWidth)
        assertEquals(280.dp, paneLayout(750.dp, TALL).listWidth)
        assertEquals(240.dp, paneLayout(750.dp, TALL).projectsWidth)
        assertEquals(280.dp, paneLayout(959.dp, TALL).listWidth)
        assertEquals(360.dp, paneLayout(960.dp, TALL).listWidth)
        assertEquals(280.dp, paneLayout(960.dp, TALL).projectsWidth)
        // The inspector opens beside the chat once the chat's minimum and the narrowest portrait
        // inspector fit next to the strip: 65 + 400 + 1 + 280 dp.
        assertEquals(InspectorMode.OVERLAY, paneLayout(745.dp, TALL).inspector)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(746.dp, TALL).inspector)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(750.dp, TALL).inspector)
        assertEquals(320.dp, paneLayout(750.dp, TALL).inspectorWidth)
        assertEquals(SIDE_INSPECTOR_MIN_WIDTH, paneLayout(959.dp, TALL).inspectorMinWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1179.dp, TALL).inspector)
        assertEquals(360.dp, paneLayout(1179.dp, TALL).inspectorWidth)
        assertEquals(INSPECTOR_PANE_MIN_WIDTH, paneLayout(1179.dp, TALL).inspectorMinWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1180.dp, TALL).inspector)
        assertEquals(320.dp, paneLayout(1180.dp, TALL).listWidth)
        assertEquals(360.dp, paneLayout(1180.dp, TALL).inspectorWidth)
        assertEquals(280.dp, paneLayout(1180.dp, TALL).projectsWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1279.dp, TALL).inspector)
        assertEquals(360.dp, paneLayout(1280.dp, TALL).listWidth)
        assertEquals(400.dp, paneLayout(1280.dp, TALL).inspectorWidth)
        assertEquals(InspectorMode.ON_DEMAND, paneLayout(1599.dp, TALL).inspector)
        assertEquals(InspectorMode.PERSISTENT, paneLayout(1600.dp, TALL).inspector)
        assertEquals(360.dp, paneLayout(1600.dp, TALL).listWidth)
        assertEquals(480.dp, paneLayout(1600.dp, TALL).inspectorWidth)
        assertEquals(280.dp, paneLayout(1600.dp, TALL).projectsWidth)
    }

    @Test
    fun theProjectsColumnIsAlwaysTheBottomOfItsHost() {
        val chat = RemoteSelection("host", "project", "s1").keys()
        assertEquals(ColumnPanes(1, null, null), columnPanes(chat.take(2)))
        assertEquals(ColumnPanes(1, 2, null), columnPanes(chat.take(3)))
        assertEquals(ColumnPanes(1, 2, 3), columnPanes(chat))
        // Sessions without their host's Projects below show no projects column.
        assertEquals(ColumnPanes(null, 1, null), columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Sessions("host", "project"))))
        assertEquals(
            ColumnPanes(null, 1, null),
            columnPanes(listOf(RemoteNavKey.Projects("other"), RemoteNavKey.Sessions("host", "project"))),
        )
    }

    @Test
    fun aStackedChildChatStillPairsWithTheSessions() {
        val keys =
            RemoteSelection("host", "project", "parent").keys() +
                RemoteNavKey.Chat("host", "project", "child")
        assertEquals(ColumnPanes(1, 2, 4), columnPanes(keys))
    }

    @Test
    fun otherDestinationsStayFullScreen() {
        assertNull(columnPanes(listOf(RemoteNavKey.Hosts)))
        assertNull(
            columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host")))
        )
        assertNull(columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.Providers("host"))))
        assertNull(columnPanes(RemoteSelection("host", "project", "s1").keys() + RemoteNavKey.Settings))
        // A chat without its project's Sessions below it has nothing to pair with.
        assertNull(columnPanes(listOf(RemoteNavKey.Hosts, RemoteNavKey.Chat("host", "project", "s1"))))
        assertNull(
            columnPanes(listOf(RemoteNavKey.Sessions("host", "other"), RemoteNavKey.Chat("host", "project", "s1")))
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
    fun canCreateSessionFollowsTheFab() {
        val sessions = RemoteNavKey.Sessions("host", "project")
        val ready = RemoteState(connected = true, selection = RemoteSelection("host", "project"))
        assertTrue(canCreateSession(ready, sessions))
        assertFalse(canCreateSession(ready.copy(loading = true), sessions))
        assertFalse(canCreateSession(ready.copy(connected = false), sessions))
        assertFalse(canCreateSession(ready.copy(selection = RemoteSelection("host", "other")), sessions))
    }

    @Test
    fun adjacentIdStopsAtTheEnds() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", adjacentId(ids, "a", 1))
        assertEquals("a", adjacentId(ids, "b", -1))
        assertNull(adjacentId(ids, "c", 1))
        assertNull(adjacentId(ids, "a", -1))
        assertEquals("a", adjacentId(ids, null, 1))
        assertEquals("c", adjacentId(ids, null, -1))
        assertNull(adjacentId(emptyList(), null, 1))
    }

    @Test
    fun projectInitialsTakeTwoWords() {
        assertEquals("NM", projectInitials("noah-monorepo"))
        assertEquals("PP", projectInitials("PocketPi"))
        assertEquals("PO", projectInitials("pocketpi"))
        assertEquals("MA", projectInitials("my_app v2"))
        assertEquals("X", projectInitials("--x--"))
        assertEquals("ÄB", projectInitials("äpfel birnen"))
        // Decomposed accents, as macOS file names spell them, count as part of their letter.
        assertEquals("ÜB", projectInitials("U\u0308ber"))
        assertEquals("ÄP", projectInitials("A\u0308pfel"))
        assertEquals("ÄB", projectInitials("a\u0308pfel bi\u0308rnen"))
        assertEquals("ÄB", projectInitials("a\u0308B"))
        // A mark without a precomposed letter stays with its letter.
        assertEquals("Q\u0308R", projectInitials("q\u0308rs"))
        assertEquals("?", projectInitials("   "))
        assertEquals("?", projectInitials(""))
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
    }

    @Test
    fun listWidthStaysWithinItsBoundsAndLeavesTheChatRoom() {
        val persistent = paneLayout(1600.dp, TALL)
        assertEquals(240.dp, listPaneWidth(100.dp, 1600.dp, persistent))
        assertEquals(480.dp, listPaneWidth(900.dp, 1600.dp, persistent))
        // 400 dp of chat and the divider line.
        assertEquals(319.dp, listPaneWidth(500.dp, 720.dp, paneLayout(720.dp, TALL)))
        // Also the narrowest inspector pane and its divider, where it opens beside the list.
        val beside = PaneLayout(true, InspectorMode.ON_DEMAND, inspectorBesideList = true)
        assertEquals(398.dp, listPaneWidth(500.dp, 1100.dp, beside))
        // Where it does not, the list collapses for it instead and keeps its room.
        assertEquals(319.dp, listPaneWidth(500.dp, 720.dp, beside.copy(inspectorBesideList = false)))
    }

    /** The chat's width beside the strip, the default list and, with [inspector], the inspector. */
    private fun chatWidth(window: Int, height: Int = 800, inspector: Boolean = false, listCollapsed: Boolean = false): Dp {
        val layout = paneLayout(window.dp, height.dp)
        val room = window.dp - PROJECT_STRIP_RESERVE
        val chatPane = if (listCollapsed) room else room - listPaneWidth(layout.listWidth, room, layout) - 1.dp
        if (!inspector) return chatPane
        return chatPane - inspectorPaneWidth(layout.inspectorWidth, chatPane, layout.inspectorMinWidth) - 1.dp
    }

    @Test
    fun aGalaxyTabS11KeepsTheChatReadableBesideTheStrip() {
        // Portrait: strip 65, list 281, chat 407.
        assertEquals(280.dp, paneLayout(753.dp, 1205.dp).listWidth)
        assertEquals(240.dp, paneLayout(753.dp, 1205.dp).projectsWidth)
        assertEquals(407.dp, chatWidth(753, 1205))
        assertEquals(454.dp, chatWidth(800, 1280))
        // Landscape: strip 65, list 321, chat and inspector 819.
        val landscape = paneLayout(1205.dp, 753.dp)
        assertEquals(InspectorMode.ON_DEMAND, landscape.inspector)
        assertEquals(819.dp, chatWidth(1205, 753))
        assertEquals(360.dp, inspectorPaneWidth(landscape.inspectorWidth, chatWidth(1205, 753)))
        // Without a chat, the full projects column, the list and an empty pane.
        assertEquals(231.dp, 753.dp - 240.dp - 1.dp - 280.dp - 1.dp)
    }

    @Test
    fun aGalaxyTabS11OpensTheInspectorBesideTheChatInBothOrientations() {
        for ((width, height) in listOf(753 to 1205, 800 to 1280, 1205 to 753, 1280 to 800)) {
            val layout = paneLayout(width.dp, height.dp)
            assertEquals("mode at $width", InspectorMode.ON_DEMAND, layout.inspector)
            // Beside the list the chat would be cramped, so the list makes way for the inspector.
            assertTrue("auto collapse at $width", layout.autoCollapseList)
            assertTrue("cramped at $width", chatWidth(width, height, inspector = true) < COMFORTABLE_CHAT_WIDTH)
            val room = width.dp - PROJECT_STRIP_RESERVE
            val inspector = inspectorPaneWidth(layout.inspectorWidth, room, layout.inspectorMinWidth)
            assertTrue("inspector at $width is $inspector", inspector >= layout.inspectorMinWidth)
            val chat = chatWidth(width, height, inspector = true, listCollapsed = true)
            assertTrue("chat at $width is $chat", chat >= CHAT_PANE_MIN_WIDTH)
        }
        // Portrait: 287 dp of inspector at the larger display zoom, the 320 dp default at the smaller one.
        assertEquals(287.dp, 753.dp - PROJECT_STRIP_RESERVE - chatWidth(753, 1205, inspector = true, listCollapsed = true) - 1.dp)
        assertEquals(414.dp, chatWidth(800, 1280, inspector = true, listCollapsed = true))
        // Landscape keeps the inspector beside the list too, when the user shows the list again.
        assertTrue(paneLayout(1205.dp, 753.dp).inspectorBesideList)
        assertTrue(paneLayout(1280.dp, 800.dp).inspectorBesideList)
        assertFalse(paneLayout(800.dp, 1280.dp).inspectorBesideList)
        // DeX keeps the list: the chat stays comfortable beside it.
        assertFalse(paneLayout(1600.dp, 1000.dp).autoCollapseList)
    }

    @Test
    fun inspectorWidthStaysWithinItsBoundsAndLeavesTheChatRoom() {
        assertEquals(378.dp, inspectorPaneWidth(900.dp, 779.dp))
        assertEquals(300.dp, inspectorPaneWidth(100.dp, 779.dp))
        assertEquals(720.dp, inspectorPaneWidth(900.dp, 2000.dp))
        // A portrait minimum lets it get narrower.
        assertEquals(287.dp, inspectorPaneWidth(320.dp, 688.dp, SIDE_INSPECTOR_MIN_WIDTH))
        assertEquals(280.dp, inspectorPaneWidth(100.dp, 688.dp, SIDE_INSPECTOR_MIN_WIDTH))
    }

    @Test
    fun anExpandedInspectorLeavesTheChatItsMinimumOrAStrip() {
        // 1280 dp landscape with the list collapsed: 1215 dp for both, 814 dp of inspector past the cap.
        assertFalse(chatStripped(1215.dp))
        assertEquals(814.dp, expandedInspectorWidth(1215.dp))
        assertTrue(expandedInspectorWidth(1215.dp) > INSPECTOR_PANE_MAX_WIDTH)
        // Below 1200 dp of room the chat would leave the inspector under 800 dp: it becomes a strip.
        assertTrue(chatStripped(1140.dp))
        assertEquals(1140.dp - 49.dp, expandedInspectorWidth(1140.dp))
        assertEquals(735.dp - 49.dp, expandedInspectorWidth(735.dp))
        assertFalse(chatStripped(1201.dp))
        assertTrue(chatStripped(1200.dp))
    }

    @Test
    fun theListCollapsesForTheInspectorAndComesBackUnlessTheUserToggledIt() {
        val state = TwoPaneState()
        state.autoCollapseList()
        assertTrue(state.listCollapsed)
        assertTrue(state.autoCollapsed)
        state.restoreList()
        assertFalse(state.listCollapsed)
        // A list the user hid stays hidden when the inspector closes.
        state.listCollapsed = true
        state.autoCollapseList()
        assertFalse(state.autoCollapsed)
        state.restoreList()
        assertTrue(state.listCollapsed)
        // Showing the list by hand while the inspector is open makes the state the user's.
        state.listCollapsed = false
        state.autoCollapseList()
        state.listCollapsed = false
        state.autoCollapseList()
        state.listCollapsed = true
        state.restoreList()
        assertTrue(state.listCollapsed)
    }

    @Test
    fun theAutoCollapseAndTheExpandedInspectorSurviveRecreation() {
        val state = TwoPaneState()
        state.autoCollapseList()
        state.inspectorExpanded = true
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val restored = TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(state) }!!)!!
        assertTrue(restored.listCollapsed)
        assertTrue(restored.autoCollapsed)
        assertTrue(restored.inspectorExpanded)
        restored.restoreList()
        assertFalse(restored.listCollapsed)
        // State an older version saved has neither.
        val old = TwoPaneState.Saver.restore(listOf(true, -1f, -1f, ""))!!
        assertTrue(old.listCollapsed)
        assertFalse(old.autoCollapsed)
        assertFalse(old.inspectorExpanded)
    }

    @Test
    fun defaultsSurviveTheClampsAndTheChatKeepsItsMinimum() {
        for (window in 720..2560) {
            val width = window.dp
            val layout = paneLayout(width, TALL)
            val side = layout.inspector != InspectorMode.OVERLAY
            // The project strip takes its share first.
            val available = width - PROJECT_STRIP_RESERVE
            val list = listPaneWidth(layout.listWidth, available, layout)
            assertEquals("list at $window", layout.listWidth, list)
            val minimum = layout.inspectorMinWidth
            if (side && layout.inspectorBesideList)
                assertEquals(
                    "inspector at $window",
                    layout.inspectorWidth,
                    inspectorPaneWidth(layout.inspectorWidth, available - list - 1.dp, minimum),
                )
            // An inspector that does not fit beside the list opens with the list collapsed.
            if (side) assertTrue("collapse at $window", layout.inspectorBesideList || layout.autoCollapseList)
            if (side && !layout.inspectorBesideList)
                assertTrue("inspector at $window", inspectorPaneWidth(layout.inspectorWidth, available, minimum) >= minimum)
            // The widest both panes can be dragged still leaves the chat its minimum.
            val widestList = listPaneWidth(10_000.dp, available, layout)
            val chatPane = if (side && !layout.inspectorBesideList) available else available - widestList - 1.dp
            val chat = if (side) chatPane - inspectorPaneWidth(10_000.dp, chatPane, minimum) - 1.dp else chatPane
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
                    if (key == RemoteNavKey.Hosts) emptyMap() else navKeyMetadata(key),
                ) {}
            }
        fun scene(entries: List<androidx.navigation3.runtime.NavEntry<androidx.navigation3.runtime.NavKey>>, collapsed: Boolean) =
            with(ProjectColumnsSceneStrategy(paneLayout(900.dp, TALL), TwoPaneState(), collapsed)) {
                androidx.navigation3.scene.SceneStrategyScope<androidx.navigation3.runtime.NavKey>().calculateScene(entries)
            }!!
        // Collapsed: no preview of a popped chat; the scene's back handler expands the list. The
        // strip stays beside the chat.
        assertEquals(emptyList<Any>(), scene(entries, collapsed = true).previousEntries)
        assertEquals(listOf(entries[1], entries[3]), scene(entries, collapsed = true).entries)
        assertEquals(entries.dropLast(1), scene(entries, collapsed = false).previousEntries)
        assertEquals(entries.drop(1), scene(entries, collapsed = false).entries)
        // Without a chat there is nothing to collapse: back leaves the sessions as usual.
        val sessionsOnly = scene(entries.dropLast(1), collapsed = true)
        assertEquals(entries.take(2), sessionsOnly.previousEntries)
        assertEquals(entries.subList(1, 3), sessionsOnly.entries)
        // The projects alone go back to the hosts, a page of its own.
        val projectsOnly = scene(entries.take(2), collapsed = false)
        assertEquals(entries.take(1), projectsOnly.previousEntries)
        assertEquals(listOf(entries[1]), projectsOnly.entries)
        // One scene for every level of a host.
        assertEquals(projectsOnly.key, scene(entries, collapsed = false).key)
        // Hosts stay a page of their own.
        assertNull(
            with(ProjectColumnsSceneStrategy(paneLayout(900.dp, TALL), TwoPaneState(), false)) {
                androidx.navigation3.scene.SceneStrategyScope<androidx.navigation3.runtime.NavKey>().calculateScene(entries.take(1))
            }
        )
    }
}
