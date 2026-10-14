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
        state.window = 1300.dp
        state.adopt(layout)
        state.resizeList(layout, 420.dp)
        state.resizeInspector(layout, 500.dp)
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val restored = TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(state) }!!)!!
        restored.window = 1300.dp
        // Adopting the same bucket after recreation keeps them.
        restored.adopt(layout)
        assertEquals(420.dp, restored.listWidth(layout))
        assertEquals(500.dp, restored.inspectorWidth(layout))
        // Untouched widths come back as the defaults.
        val fresh = TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(TwoPaneState()) }!!)!!
        fresh.window = 1300.dp
        assertEquals(layout.listWidth, fresh.listWidth(layout))
        assertEquals(layout.inspectorWidth, fresh.inspectorWidth(layout))
    }

    /** What a cold start reads back: the memos through the preferences' text. */
    private fun restart(state: TwoPaneState, window: Dp): TwoPaneState =
        TwoPaneState(memos = decodePaneMemos(encodePaneMemos(state.memos))).also { it.window = window }

    @Test
    fun draggedWidthsSurviveARoundTripThroughAnotherBucketAndARestart() {
        val wide = paneLayout(1300.dp, TALL)
        val narrow = paneLayout(900.dp, TALL)
        val state = TwoPaneState()
        state.window = 1300.dp
        state.adopt(wide)
        state.resizeList(wide, 420.dp)
        state.resizeInspector(wide, 500.dp)
        // Bucket B shows its own defaults and keeps A's widths aside.
        state.window = 900.dp
        state.adopt(narrow)
        assertEquals(narrow.listWidth, state.listWidth(narrow))
        assertEquals(narrow.inspectorWidth, state.inspectorWidth(narrow))
        // Back in A, they are there again.
        state.window = 1300.dp
        state.adopt(wide)
        assertEquals(420.dp, state.listWidth(wide))
        assertEquals(500.dp, state.inspectorWidth(wide))
        // And after a restart, read back from the store.
        val restarted = restart(state, 1300.dp)
        restarted.adopt(wide)
        assertEquals(420.dp, restarted.listWidth(wide))
        assertEquals(500.dp, restarted.inspectorWidth(wide))
        assertEquals(narrow.listWidth, restarted.listWidth(narrow))
    }

    @Test
    fun aDraggedWidthIsAShareOfTheWindowSoBothLandscapeZoomsUseIt() {
        val small = paneLayout(1205.dp, 753.dp)
        val large = paneLayout(1280.dp, 800.dp)
        assertEquals(small.sizeKey(), large.sizeKey())
        // The portrait buckets of both zooms share one too, apart from landscape.
        assertEquals(paneLayout(753.dp, 1205.dp).sizeKey(), paneLayout(800.dp, 1280.dp).sizeKey())
        assertFalse(paneLayout(753.dp, 1205.dp).sizeKey() == small.sizeKey())
        val state = TwoPaneState()
        state.window = 1205.dp
        state.adopt(small)
        state.resizeList(small, 482.dp)
        assertEquals(482.dp, state.listWidth(small))
        state.window = 1280.dp
        state.adopt(large)
        assertEquals(512.dp, state.listWidth(large))
    }

    @Test
    fun theResetRemovesTheBucketsEntry() {
        val wide = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        state.window = 1300.dp
        state.adopt(wide)
        state.resizeList(wide, 420.dp)
        assertTrue(wide.sizeKey() in state.memos)
        state.resizeList(wide, null)
        assertEquals(wide.listWidth, state.listWidth(wide))
        assertFalse(wide.sizeKey() in state.memos)
        assertFalse(wide.sizeKey() in restart(state, 1300.dp).memos)
        // A reset of one pane keeps the other's width.
        state.resizeList(wide, 420.dp)
        state.resizeInspector(wide, 500.dp)
        state.resizeList(wide, null)
        assertEquals(500.dp, state.inspectorWidth(wide))
        assertEquals(wide.listWidth, state.listWidth(wide))
    }

    @Test
    fun navigationRevealingTheListIsNoChoiceToRemember() {
        val wide = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        state.window = 1300.dp
        state.adopt(wide)
        state.revealList()
        assertFalse(wide.sizeKey() in state.memos)
        // The user hides the list; a project opened brings it back and drops the stored hide.
        state.listCollapsed = true
        assertEquals(true, state.memos[wide.sizeKey()]?.listCollapsed)
        state.resizeList(wide, 420.dp)
        state.revealList()
        assertFalse(state.listCollapsed)
        assertNull(state.memos[wide.sizeKey()]?.listCollapsed)
        // So the width's reset removes the bucket's entry.
        state.resizeList(wide, null)
        assertFalse(wide.sizeKey() in state.memos)
    }

    @Test
    fun storedWidthsNeverPushTheChatBelowItsMinimum() {
        // Widths dragged in the widest window of a bucket come back clamped in its narrowest one.
        for ((wideWindow, narrowWindow) in listOf(1599 to 960, 959 to 750, 2560 to 1600)) {
            val wide = paneLayout(wideWindow.dp, TALL)
            val narrow = paneLayout(narrowWindow.dp, TALL)
            assertEquals(wide.sizeKey(), narrow.sizeKey())
            val state = TwoPaneState()
            state.window = wideWindow.dp
            state.adopt(wide)
            state.resizeList(wide, listPaneWidth(10_000.dp, wideWindow.dp - PROJECT_STRIP_RESERVE, wide))
            state.resizeInspector(wide, INSPECTOR_PANE_MAX_WIDTH)
            val restarted = restart(state, narrowWindow.dp)
            restarted.adopt(narrow)
            val available = narrowWindow.dp - PROJECT_STRIP_RESERVE
            val list = listPaneWidth(restarted.listWidth(narrow), available, narrow)
            val chatPane = if (narrow.inspectorBesideList) available - list - 1.dp else available
            val chat = chatPane - inspectorPaneWidth(restarted.inspectorWidth(narrow), chatPane, narrow.inspectorMinWidth) - 1.dp
            assertTrue("chat at $narrowWindow is $chat", chat >= CHAT_PANE_MIN_WIDTH)
            assertTrue("chat beside the list at $narrowWindow", available - list - 1.dp >= CHAT_PANE_MIN_WIDTH)
        }
    }

    @Test
    fun eachBucketRemembersTheListTheUserHid() {
        val landscape = paneLayout(1280.dp, 800.dp)
        val portrait = paneLayout(800.dp, 1280.dp)
        val state = TwoPaneState()
        state.adopt(landscape)
        state.listCollapsed = true
        state.adopt(portrait)
        // Portrait has no choice of its own yet: the list stays as it is.
        assertTrue(state.listCollapsed)
        state.listCollapsed = false
        state.adopt(landscape)
        assertTrue(state.listCollapsed)
        state.adopt(portrait)
        assertFalse(state.listCollapsed)
        // A list that only made way for the inspector stays out of its way.
        state.autoCollapseList()
        state.adopt(landscape)
        assertTrue(state.listCollapsed)
        assertFalse(state.autoCollapsed)
        // A restart starts with the bucket's choice.
        val restarted = restart(state, 1280.dp)
        restarted.adopt(landscape)
        assertTrue(restarted.listCollapsed)
        val restartedInPortrait = restart(state, 800.dp)
        restartedInPortrait.adopt(portrait)
        assertFalse(restartedInPortrait.listCollapsed)
    }

    @Test
    fun unreadableOrOddMemosAreDropped() {
        assertEquals(emptyMap<String, PaneMemo>(), decodePaneMemos(null))
        assertEquals(emptyMap<String, PaneMemo>(), decodePaneMemos("not json"))
        val decoded = decodePaneMemos("""{"a":{"list":1.5,"inspector":0.3},"b":{"list":-1},"c":{"listCollapsed":true,"extra":1}}""")
        assertEquals(mapOf("a" to PaneMemo(inspector = 0.3f), "c" to PaneMemo(listCollapsed = true)), decoded)
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

    @Test
    fun resetLayoutForgetsWidthsAndHiddenListsButLeavesRoomForTheInspector() {
        val layout = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        assertFalse(state.customized)
        state.window = 1300.dp
        state.adopt(layout)
        state.resizeList(layout, 420.dp)
        state.resizeInspector(layout, 500.dp)
        state.listCollapsed = true
        state.inspectorExpanded = true
        assertTrue(state.customized)
        state.resetLayout()
        assertFalse(state.customized)
        assertTrue(state.memos.isEmpty())
        assertFalse(state.listCollapsed)
        assertFalse(state.inspectorExpanded)
        assertEquals(layout.listWidth, state.listWidth(layout))
        assertEquals(layout.inspectorWidth, state.inspectorWidth(layout))
        // A list that only made way for the open inspector is no change of the user's to undo.
        state.autoCollapseList()
        assertFalse(state.customized)
        state.resetLayout()
        assertTrue(state.listCollapsed)
        state.restoreList()
        assertFalse(state.listCollapsed)
    }

    @Test
    fun projectsColumnKeptBesideAChatIsRememberedPerBucketAndReset() {
        val wide = paneLayout(1300.dp, TALL)
        val narrow = paneLayout(800.dp, TALL)
        val state = TwoPaneState()
        state.adopt(wide)
        state.projectsExpanded = true
        assertTrue(state.customized)
        assertEquals(true, state.memos[wide.sizeKey()]?.projectsExpanded)
        // Another bucket has its own choice, and coming back restores this one.
        state.adopt(narrow)
        assertFalse(state.projectsExpanded)
        state.adopt(wide)
        assertTrue(state.projectsExpanded)
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        assertTrue(TwoPaneState.Saver.restore(with(TwoPaneState.Saver) { scope.save(state) }!!)!!.projectsExpanded)
        state.projectsExpanded = false
        assertTrue(state.memos.isEmpty())
        state.projectsExpanded = true
        state.resetLayout()
        assertFalse(state.projectsExpanded)
        assertFalse(state.customized)
    }

    @Test
    fun fullProjectsColumnDecidesTheInspectorForTheRoomItLeaves() {
        // Tab S11 landscape: beside the strip the inspector opens beside the list.
        val landscape = paneLayout(1205.dp, TALL)
        assertTrue(landscape.inspectorBesideList)
        val kept = checkNotNull(projectsColumnLayout(1205.dp, landscape))
        // Beside the full column it would leave the chat under its minimum: the list makes way.
        assertFalse(kept.inspectorBesideList)
        assertTrue(kept.autoCollapseList)
        assertEquals(landscape.sizeKey(), kept.sizeKey())
        // Once the list has made way, the chat keeps its minimum beside the narrowest inspector.
        val room = 1205.dp - landscape.projectsWidth - PANE_DIVIDER_WIDTH
        assertTrue(room - landscape.inspectorMinWidth - PANE_DIVIDER_WIDTH >= CHAT_PANE_MIN_WIDTH)
        // Where the narrowest list and the chat no longer fit beside it, the strip stays.
        val portrait = paneLayout(800.dp, TALL)
        assertNull(projectsColumnLayout(800.dp, portrait))
        assertNull(projectsColumnLayout(400.dp, PaneLayout.Phone))
        // 280 dp of projects, a divider, 400 dp of chat, a divider and a 300 dp inspector need 982 dp.
        val wide = paneLayout(1300.dp, TALL)
        assertNull(projectsColumnLayout(981.dp, wide))
        assertTrue(projectsColumnLayout(982.dp, wide) != null)
        // Without an inspector beside the chat, the narrowest list is what has to fit: 922 dp.
        val overlay = wide.copy(inspector = InspectorMode.OVERLAY)
        assertNull(projectsColumnLayout(921.dp, overlay))
        assertTrue(projectsColumnLayout(922.dp, overlay) != null)
    }

    @Test
    fun aListShownAgainIsNoLayoutToReset() {
        val layout = paneLayout(1300.dp, TALL)
        val state = TwoPaneState()
        state.adopt(layout)
        state.listCollapsed = true
        assertTrue(state.customized)
        state.listCollapsed = false
        assertFalse(state.customized)
    }
}
