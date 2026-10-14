package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole navigation at phone and tablet widths. Every test opens session s1 through a
 * notification deep link, the way a notification, widget row or shortcut does.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwoPaneNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private fun session(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("status", "idle")
        put("origin", "rpc")
    }

    private val sessions = listOf(session("s1", "First session"), session("s2", "Second session"), session("s3", "Third session"))

    private fun project(id: String, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private val projects = listOf(project("project", "noah-monorepo"), project("other", "pocketpi"))

    init {
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = projects,
                project = projects[0],
                sessions = sessions,
                session = sessions[0],
                messages =
                    listOf(
                        buildJsonObject {
                            put("id", "tool-1")
                            put("role", "tool")
                            put("text", "tool output line")
                            put("state", "complete")
                            put("toolName", "read")
                            put("toolCallId", "call-1")
                        }
                    ),
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    private val notification = RemoteNotification("host", "s1", 1)

    @Composable
    private fun App(hardwareKeyboard: Boolean = false) {
        val base = LocalConfiguration.current
        val configuration =
            Configuration(base).apply {
                if (hardwareKeyboard) {
                    keyboard = Configuration.KEYBOARD_QWERTY
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
                } else {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            }
        focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            MaterialTheme { RemoteNavigation(repository, settings, false, {}, notification) }
        }
    }

    private lateinit var focusManager: androidx.compose.ui.focus.FocusManager

    /** Takes keyboard focus away from everything, as a tap outside a field does. */
    private fun clearFocus() {
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
    }

    private fun render(hardwareKeyboard: Boolean = false) {
        compose.setContent { App(hardwareKeyboard) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun assertChatShown() = compose.onNodeWithTag("composerField").assertIsDisplayed()

    private fun openTool() {
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.waitForIdle()
    }

    // ---- Phone ---------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneKeepsOneFullScreenDestination() {
        render()
        assertChatShown()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        compose.onNodeWithTag("toggleSessionList").assertDoesNotExist()
        compose.onNodeWithText("Second session").assertDoesNotExist()
        openTool()
        // The tool detail covers the chat, as before.
        compose.onNodeWithTag("toolDetail").assertIsDisplayed()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        back()
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        back()
        // Back from the chat shows the sessions full screen, with no empty chat pane.
        compose.onNodeWithText("Second session").assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertDoesNotExist()
        compose.onNodeWithTag("chatPlaceholder").assertDoesNotExist()
        compose.onNodeWithTag("sessionSearch").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneWithoutHardwareKeyboardNeverSendsOnEnter() {
        render(hardwareKeyboard = false)
        compose.onNodeWithTag("composerField").performTextInput("hello")
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(0, repository.prompts)
    }

    /** The index-for-key lookup of the list page in view: the lazy list keyed with its page title. */
    private fun pageIndexForKey(): (Any) -> Int =
        compose.onAllNodes(hasScrollToKeyAction()).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.IndexForKey] }
            .single { it("page-title") >= 0 }

    /** The chip is the list's first item and keyed, whether it says something or not. */
    private fun assertConnectionChipLeads(text: String?) {
        val indexForKey = pageIndexForKey()
        assertEquals(0, indexForKey(CONNECTION_STATUS_KEY))
        assertEquals(1, indexForKey("page-title"))
        if (text == null) compose.onNodeWithTag("connectionStatusChip").assertDoesNotExist()
        else compose.onNode(hasTestTag("connectionStatusChip") and hasText(text)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun everyListPageLeadsWithTheKeyedConnectionChipAndStaleRowsKeepTheirStatus() {
        render()
        back()
        assertConnectionChipLeads(null)
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(connected = false, stale = true, connection = R.string.remote_reconnecting)
        }
        compose.waitForIdle()
        val reconnecting = label(R.string.remote_reconnecting)
        assertConnectionChipLeads(reconnecting)
        // The rows keep the status the Mac last reported, and say it is the last-known one.
        compose.onNodeWithText("Second session").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(
                compose.activity.getString(R.string.remote_status_last_known, label(R.string.remote_status_idle))
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(compose.onAllNodesWithText(label(R.string.remote_status_idle), substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText(label(R.string.remote_status_offline), substring = true).fetchSemanticsNodes().isEmpty())
        compose.onAllNodesWithContentDescription(label(R.string.remote_settings)).onFirst().performClick()
        compose.waitForIdle()
        assertConnectionChipLeads(reconnecting)
        back()
        back()
        compose.onNodeWithText("pocketpi").assertIsDisplayed()
        assertConnectionChipLeads(reconnecting)
        back()
        assertConnectionChipLeads(reconnecting)
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(connected = false, stale = false, connection = R.string.remote_offline)
        }
        compose.waitForIdle()
        // No Mac chosen and nothing under way: nothing to say, and the title keeps its index.
        assertConnectionChipLeads(null)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun theConnectionChipStaysBelowTheHeaderWhileTheListScrolls() {
        val many = (1..40).map { session("many-$it", "Session %02d".format(it)) }
        repository.state.value =
            repository.state.value.copy(
                sessions = many,
                connected = false,
                stale = true,
                connection = R.string.remote_waiting_for_network,
            )
        render()
        back()
        val chip = compose.onNode(hasTestTag("connectionStatusChip") and hasText(label(R.string.remote_waiting_for_network)))
        val atTop = chip.assertIsDisplayed().getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("navigationPill").getUnclippedBoundsInRoot()
        assertTrue("The chip at ${atTop.top} starts under the header ending at ${header.bottom}", atTop.top >= header.bottom)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("many-40")
        compose.waitForIdle()
        compose.onNodeWithText("Session 40").assertIsDisplayed()
        val scrolled = chip.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The scrolled chip at ${scrolled.top} slid under the header ending at ${header.bottom}", scrolled.top >= header.bottom)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aScrolledListKeepsItsPlaceWhileTheConnectionChipComesAndGoes() {
        val many = (1..40).map { session("many-$it", "Session %02d".format(it)) }
        repository.state.value = repository.state.value.copy(sessions = many)
        render()
        back()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("many-20")
        compose.waitForIdle()
        fun top() = compose.onNodeWithText("Session 20").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val before = top()
        for (line in listOf(R.string.remote_reconnecting, R.string.remote_offline, R.string.remote_connected)) {
            compose.runOnIdle {
                repository.state.value =
                    repository.state.value.copy(
                        connected = line == R.string.remote_connected,
                        stale = line != R.string.remote_connected,
                        connection = line,
                    )
            }
            compose.waitForIdle()
            // The sticky chip changes only its own height; the rows in view stay where they were.
            assertEquals("Session 20 moved with ${label(line)}", before.value, top().value, 0.5f)
        }
    }

    // ---- Two panes -----------------------------------------------------------------------

    /** The connection chips on screen; [pane] names the pane the only one is in. */
    private fun assertOneConnectionChipIn(pane: String) {
        compose.onAllNodesWithTag("connectionStatusChip").assertCountEquals(1)
        compose.onNode(hasTestTag("connectionStatusChip") and hasAnyAncestor(hasTestTag(pane))).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun onlyOnePaneShowsTheConnection() {
        render()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(connected = false, stale = true, connection = R.string.remote_reconnecting)
        }
        compose.waitForIdle()
        // The chat beside the list says it above its composer; the list stays quiet.
        assertOneConnectionChipIn("chatPane")
        back()
        // The projects column beside a project's sessions: the sessions say it.
        compose.onNodeWithTag("projectsPane").assertIsDisplayed()
        assertOneConnectionChipIn("sessionListPane")
        back()
        assertOneConnectionChipIn("projectsPane")
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun deepLinkSelectsTheSessionBesideTheList() {
        render()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        compose.onNode(hasTestTag("composerField") and hasAnyAncestor(hasTestTag("chatPane"))).assertIsDisplayed()
        compose.onNode(isSelected() and hasAnyAncestor(hasTestTag("sessionListPane"))).assertIsDisplayed()
        compose.onNode(isSelected() and hasAnyAncestor(hasTestTag("sessionListPane"))).assertExists()
        compose.onNodeWithText("Second session").assertIsDisplayed()
        compose.onNodeWithTag("sessionSearch").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun selectingASessionReplacesTheChatWithoutAFullScreenRoute() {
        render()
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s2") to ActivationMode.USER_OPEN, repository.activations.last())
        // Still both panes, and back goes to the list with an empty chat pane, not to s1.
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertChatShown()
        back()
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        back()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun collapsedListComesBackOnBack() {
        render()
        compose.onNodeWithContentDescription(label(R.string.remote_session_list_hide)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertChatShown()
        back()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun collapsedBackClosesToolFirstThenExpandsThenLeavesTheChat() {
        render()
        compose.onNodeWithTag("toggleSessionList").performClick()
        compose.waitForIdle()
        openTool()
        back()
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertChatShown()
        back()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertChatShown()
        back()
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun belowTheInspectorWidthToolDetailsCoverTheChatPane() {
        render()
        openTool()
        compose.onNodeWithTag("toolDetail").assertIsDisplayed()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("chatPane"))).assertExists()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun inspectorOpensOnDemandBesideTheChat() {
        render()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        openTool()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        // The chat stays usable beside it.
        assertChatShown()
        back()
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w753dp-h1205dp")
    fun aTabletInPortraitShowsTheSessionListBesideTheChat() {
        render()
        compose.onNodeWithTag("projectStrip").assertWidthIsEqualTo(PROJECT_STRIP_WIDTH)
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
        assertTrue(widthOf("chatPane") >= CHAT_PANE_MIN_WIDTH)
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun aTabletInLandscapeOpensTheInspectorBesideTheChat() {
        render()
        compose.onNodeWithTag("projectStrip").assertWidthIsEqualTo(PROJECT_STRIP_WIDTH)
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(320.dp)
        assertTrue(widthOf("chatPane") >= CHAT_PANE_MIN_WIDTH)
        compose.onNodeWithContentDescription("Open noah-monorepo").assertIsSelected()
        compose.onNodeWithContentDescription("Open pocketpi").assertIsNotSelected()
        // TalkBack reads only "Open <name>", not the initials.
        compose.onNodeWithText("NM", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("noah-monorepo") and hasTestTag("projectStripLabel")).assertExists()
        compose.onNodeWithTag("pageColumn").assertDoesNotExist()
        openTool()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun fullScreenPagesStayInACentredColumn() {
        render()
        // The two panes use the whole window.
        compose.onNodeWithTag("pageColumn").assertDoesNotExist()
        compose.onNode(hasContentDescription(label(R.string.remote_settings)) and hasAnyAncestor(hasTestTag("sessionListPane")))
            .performClick()
        compose.waitForIdle()
        val page = compose.onNodeWithTag("pageColumn")
        page.assertWidthIsEqualTo(720.dp)
        assertEquals(280.dp, page.getUnclippedBoundsInRoot().left)
    }

    @Test
    @Config(qualifiers = "w1700dp-h1000dp")
    fun inspectorStaysOpenOnVeryWideWindows() {
        render()
        compose.onNodeWithTag("inspectorPlaceholder").assertIsDisplayed()
        openTool()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        back()
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        compose.onNodeWithTag("inspectorPlaceholder").assertIsDisplayed()
        assertChatShown()
    }

    // ---- Inspector tabs ------------------------------------------------------------------

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /** Selects a tab; a tap on its centre would hit its close button. */
    private fun selectTab(tab: String) {
        compose.onNodeWithTag("inspectorTab_$tab").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun assertInInspector(tag: String) =
        compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()

    /** The chat's own width beside the inspector, which shares the chat's pane. */
    private fun chatBesideInspector(): Dp = widthOf("chatPane") - widthOf("inspectorPane") - PANE_DIVIDER_WIDTH

    /**
     * Changes open beside the chat with the list out of the way, Files joins them as a second tab
     * and the list comes back with the last one.
     */
    private fun changesAndFilesOpenBesideTheChat() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY, FILES_CAPABILITY))
        render()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        click("chatAction_changes")
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertInInspector("changesPane")
        assertChatShown()
        assertTrue("chat ${chatBesideInspector()}", chatBesideInspector() >= CHAT_PANE_MIN_WIDTH)
        assertTrue("inspector ${widthOf("inspectorPane")}", widthOf("inspectorPane") >= SIDE_INSPECTOR_MIN_WIDTH)
        // Files open as a second tab and keep the changes.
        click("chatActionsMore")
        click("chatActionMenu_files")
        assertInInspector("filesPane")
        compose.onNodeWithTag("inspectorTab_files").assertIsSelected()
        compose.onNodeWithTag("inspectorTab_changes").assertIsNotSelected()
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        assertTrue(repository.state.value.changes != null)
        selectTab("changes")
        assertInInspector("changesPane")
        // Closing a tab shows the one before it; the last one takes the pane and brings the list back.
        click("inspectorTabClose_changes")
        assertInInspector("filesPane")
        assertNull(repository.state.value.changes)
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        click("inspectorTabClose_files")
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w753dp-h1205dp")
    fun aPortraitTabletOpensChangesBesideTheChat() = changesAndFilesOpenBesideTheChat()

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun aPortraitTabletAtTheSmallerZoomOpensChangesBesideTheChat() = changesAndFilesOpenBesideTheChat()

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aLandscapeTabletOpensChangesBesideTheChat() = changesAndFilesOpenBesideTheChat()

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aToolClickSwitchesToTheToolTabAndBackClosesIt() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render()
        click("chatAction_changes")
        openTool()
        compose.onNodeWithTag("inspectorTab_tool").assertIsSelected()
        assertInInspector("toolDetail")
        compose.onNodeWithTag("inspectorTab_changes").assertExists()
        // Back (and Esc) closes the active tab, which hands the pane to the one before it.
        back()
        compose.onNodeWithTag("inspectorTab_tool").assertDoesNotExist()
        assertInInspector("changesPane")
        back()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun jobsOpenInTheInspectorBesideTheChat() {
        render()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = JobsState("s1", loaded = true, listOpen = true))
        }
        compose.waitForIdle()
        assertInInspector("jobsScreen")
        compose.onNodeWithTag("inspectorTab_jobs").assertIsSelected()
        // The chat stays usable beside the jobs, and the list made way for them.
        assertChatShown()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        click("inspectorTabClose_jobs")
        assertEquals(false, repository.state.value.jobs?.listOpen)
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aListTheUserHidOnItsOwnStaysHiddenWhenTheInspectorCloses() {
        render()
        openTool()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        // Shown and hidden again by hand: the state is the user's now.
        click("toggleSessionList")
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertInInspector("toolDetail")
        click("toggleSessionList")
        back()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun aPortraitListShownByHandLetsTheInspectorCoverTheChat() {
        render()
        openTool()
        assertInInspector("toolDetail")
        // The list and the inspector do not fit beside the chat: the tool covers it, as on a phone.
        click("toggleSessionList")
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("chatPane"))).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-keysexposed-qwerty")
    fun keysOpenWidenAndHideTheInspector() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render(hardwareKeyboard = true)
        val shift = android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        windowKey(android.view.KeyEvent.KEYCODE_G, ctrlMeta or shift)
        assertInInspector("changesPane")
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(400.dp)
        // Ctrl+Shift+B widens it past its usual cap and leaves the chat its minimum.
        windowKey(android.view.KeyEvent.KEYCODE_B, ctrlMeta or shift)
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(1280.dp - PROJECT_STRIP_RESERVE - CHAT_PANE_MIN_WIDTH - PANE_DIVIDER_WIDTH)
        assertEquals(CHAT_PANE_MIN_WIDTH, chatBesideInspector())
        compose.onNodeWithTag("chatStrip").assertDoesNotExist()
        windowKey(android.view.KeyEvent.KEYCODE_B, ctrlMeta or shift)
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(400.dp)
        // Ctrl+\ hides the pane and its tabs stay; the list comes back meanwhile.
        windowKey(android.view.KeyEvent.KEYCODE_BACKSLASH, ctrlMeta)
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertTrue(repository.state.value.changes != null)
        windowKey(android.view.KeyEvent.KEYCODE_BACKSLASH, ctrlMeta)
        assertInInspector("changesPane")
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        // The shown tab's key closes it.
        windowKey(android.view.KeyEvent.KEYCODE_G, ctrlMeta or shift)
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        assertNull(repository.state.value.changes)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun aWidenedPortraitInspectorLeavesTheChatAStrip() {
        render()
        openTool()
        click("inspectorExpand")
        compose.onNodeWithTag("chatStrip").assertIsDisplayed()
        compose.onNodeWithTag("chatStripStatus").assertExists()
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(800.dp - PROJECT_STRIP_RESERVE - CHAT_STRIP_WIDTH - PANE_DIVIDER_WIDTH)
        // The chat behind the strip is out of TalkBack's reach.
        compose.onNodeWithTag("composerField").assertDoesNotExist()
        click("chatStripShow")
        compose.onNodeWithTag("chatStrip").assertDoesNotExist()
        assertChatShown()
        assertInInspector("toolDetail")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-keysexposed-qwerty")
    fun hiddenPortraitTabsStayOffTheChat() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render(hardwareKeyboard = true)
        val shift = android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        windowKey(android.view.KeyEvent.KEYCODE_G, ctrlMeta or shift)
        windowKey(android.view.KeyEvent.KEYCODE_H, ctrlMeta)
        compose.onNodeWithTag("inspectorTab_history").assertIsSelected()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        // Ctrl+\ hides the tabs and the list comes back; nothing of them covers the chat meanwhile.
        windowKey(android.view.KeyEvent.KEYCODE_BACKSLASH, ctrlMeta)
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        compose.onNodeWithTag("chatHistorySheet").assertDoesNotExist()
        compose.onNodeWithTag("chatHistoryPane").assertDoesNotExist()
        assertChatShown()
        assertTrue(repository.state.value.changes != null)
        // A hidden tab's key shows it again instead of closing it, and the list makes way at once.
        windowKey(android.view.KeyEvent.KEYCODE_G, ctrlMeta or shift)
        assertInInspector("changesPane")
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertTrue(repository.state.value.changes != null)
        compose.onNodeWithTag("inspectorTab_history").assertExists()
        // Hidden and shown again with Ctrl+\, the active tab comes back beside the chat.
        windowKey(android.view.KeyEvent.KEYCODE_BACKSLASH, ctrlMeta)
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        windowKey(android.view.KeyEvent.KEYCODE_BACKSLASH, ctrlMeta)
        assertInInspector("changesPane")
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun theHistoryActionSwitchesToTheHistoryTabBehindAnother() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render()
        fun history() {
            if (compose.onAllNodesWithTag("chatAction_history").fetchSemanticsNodes().isEmpty()) {
                click("chatActionsMore")
                click("chatActionMenu_history")
            } else click("chatAction_history")
        }
        history()
        assertInInspector("chatHistoryPane")
        click("chatAction_changes")
        assertInInspector("changesPane")
        // History behind Changes: the action brings it to the front, and only then closes it.
        history()
        assertInInspector("chatHistoryPane")
        history()
        compose.onNodeWithTag("inspectorTab_history").assertDoesNotExist()
        assertInInspector("changesPane")
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun askingAboutAJobBehindTheStripBringsTheChatBack() {
        val job = BackgroundJob("one", "echo one", "/project", false, JobStatus.RUNNING, null, null, 1L, null, 0L, false)
        render()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    jobs =
                        JobsState(
                            "s1", listOf(job), loaded = true,
                            view = JobView("one", job, lines = listOf(JobOutputLine.Text("error: boom")), loaded = true),
                        )
                )
        }
        compose.waitForIdle()
        assertInInspector("jobAsk")
        click("inspectorExpand")
        compose.onNodeWithTag("chatStrip").assertIsDisplayed()
        click("jobAsk")
        // The prompt went into the draft, and the chat that holds it shows again beside the jobs.
        assertTrue(repository.state.value.draft, repository.state.value.draft.contains("error: boom"))
        compose.onNodeWithTag("chatStrip").assertDoesNotExist()
        assertChatShown()
        compose.onNodeWithTag("composerField").assertIsFocused()
        assertInInspector("jobAsk")
    }

    // ---- Projects column -----------------------------------------------------------------

    private fun navigationPillIn(tag: String) =
        compose.onNode(hasTestTag("navigationPill") and hasAnyAncestor(hasTestTag(tag)))

    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun aSideCutoutIndentsOnlyTheColumnAtTheScreenEdge() {
        render()
        back()
        fun left(text: String, pane: String) =
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(pane))).getUnclippedBoundsInRoot().left -
                compose.onNodeWithTag(pane).getUnclippedBoundsInRoot().left
        val projectBefore = left("pocketpi", "projectsPane")
        val sessionBefore = left("Second session", "sessionListPane")
        val cutout = 48
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            ViewCompat.dispatchApplyWindowInsets(
                decor,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutout, 0, 0, 0))
                    .build(),
            )
        }
        compose.waitForIdle()
        val inset = with(compose.density) { cutout.toDp() }
        // The projects column at the screen edge moves clear of the cutout; the list beside it does not.
        assertEquals((projectBefore + inset).value, left("pocketpi", "projectsPane").value, 0.5f)
        assertEquals(sessionBefore.value, left("Second session", "sessionListPane").value, 0.5f)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun backWidensTheStripThenLeavesTheProjectThenTheHost() {
        render()
        compose.onNodeWithTag("projectStrip").assertIsDisplayed()
        compose.onNodeWithTag("projectsPane").assertDoesNotExist()
        back()
        // The sessions: the full projects column with the project selected, then the list.
        compose.onNodeWithTag("projectsPane").assertWidthIsEqualTo(240.dp)
        compose.onNodeWithTag("projectRow-project").assertIsSelected()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        navigationPillIn("sessionListPane").assertDoesNotExist()
        navigationPillIn("projectsPane").assertIsDisplayed()
        compose.onNode(hasTestTag("headerActionsPill") and hasAnyAncestor(hasTestTag("sessionListPane"))).assertDoesNotExist()
        back()
        // The projects alone.
        compose.onNodeWithTag("projectsPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("projectsPane").assertIsDisplayed()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        compose.onNodeWithTag("pageColumn").assertDoesNotExist()
        compose.onNode(isSelected()).assertDoesNotExist()
        compose.onNodeWithTag("projectRow-project").assertIsNotSelected()
        back()
        // The hosts, a page of their own.
        compose.onNodeWithTag("projectsPane").assertDoesNotExist()
        compose.onNodeWithTag("pageColumn").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun theStripSwitchesTheProjectAndClosesTheChat() {
        render()
        compose.onNodeWithContentDescription("Open pocketpi").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "other") to ActivationMode.USER_OPEN, repository.activations.last())
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("projectsPane").assertWidthIsEqualTo(280.dp)
        compose.onNodeWithTag("projectRow-other").assertIsSelected()
        back()
        compose.onNodeWithTag("projectsPlaceholder").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun theSelectedAvatarClosesTheChat() {
        render()
        compose.onNodeWithContentDescription("Open noah-monorepo").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project") to ActivationMode.USER_OPEN, repository.activations.last())
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("projectsPane").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun theFullColumnSwitchesTheProject() {
        render()
        back()
        compose.onNodeWithTag("projectRow-other").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "other") to ActivationMode.USER_OPEN, repository.activations.last())
        compose.onNodeWithTag("projectRow-other").assertIsSelected()
        compose.onNodeWithTag("projectRow-project").assertIsNotSelected()
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneShowsTheProjectsFullScreen() {
        render()
        compose.onNodeWithTag("projectStrip").assertDoesNotExist()
        compose.onNodeWithTag("projectsPane").assertDoesNotExist()
        back()
        back()
        compose.onNodeWithTag("projectRow-project").assertIsDisplayed()
        compose.onNode(hasTestTag("projectRow-project") and hasAnyAncestor(hasTestTag("pageColumn"))).assertExists()
        compose.onNodeWithTag("projectsPane").assertDoesNotExist()
        compose.onNodeWithTag("projectsPlaceholder").assertDoesNotExist()
        compose.onNode(isSelected()).assertDoesNotExist()
        // A page of its own has nothing to select.
        compose.onNodeWithTag("projectRow-project").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun aCancelledBackGestureKeepsTheChatAndACommittedOneClosesIt() {
        render()
        val dispatcher = compose.activity.onBackPressedDispatcher
        val stripEdge = PROJECT_STRIP_RESERVE
        // Halfway, the strip has grown halfway to the 240 dp column and the list moved with it.
        val halfway = PROJECT_STRIP_RESERVE + (240.dp - PROJECT_STRIP_WIDTH) / 2
        fun listLeft() = compose.onNodeWithTag("sessionListPane").getUnclippedBoundsInRoot().left
        fun assertNear(expected: Dp, actual: Dp) =
            assertTrue("expected about $expected, was $actual", (actual - expected).value in -1f..1f)
        assertNear(stripEdge, listLeft())
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        // The scene's own handler has the gesture: its preview is on screen.
        assertChatShown()
        assertNear(halfway, listLeft())
        compose.onNodeWithTag("projectStrip").assertWidthIsEqualTo(PROJECT_STRIP_WIDTH)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertChatShown()
        assertNear(stripEdge, listLeft())
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // The column goes on growing from where the gesture let go instead of starting over.
        compose.onNodeWithTag("chatPlaceholder").assertExists()
        assertTrue(listLeft() >= halfway - 1.dp)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("projectsPane").assertWidthIsEqualTo(240.dp)
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertNear(240.dp + PANE_DIVIDER_WIDTH, listLeft())
    }

    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun theStripScrollsTheSelectedProjectIntoView() {
        val many = (1..30).map { project("p$it", "Project $it") } + projects[0]
        repository.state.value = repository.state.value.copy(projects = many)
        render()
        compose.onNodeWithTag("projectAvatar-project").assertIsDisplayed().assertIsSelected()
    }

    // ---- Resizes and recreation ----------------------------------------------------------

    @Test
    @Config(qualifiers = "w1700dp-h1000dp")
    fun resizingKeepsTheSelectedSessionAndDraft() {
        val width = mutableStateOf<Dp>(1300.dp)
        compose.setContent {
            Box(Modifier.width(width.value).fillMaxHeight()) { App() }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").performTextInput("half typed")
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        // Split-screen down to phone width, then back up.
        width.value = 500.dp
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertChatShown()
        compose.onNodeWithText("half typed").assertIsDisplayed()
        width.value = 900.dp
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        compose.onNodeWithText("half typed").assertIsDisplayed()
        compose.onNode(isSelected() and hasAnyAncestor(hasTestTag("sessionListPane"))).assertExists()
        assertEquals("half typed", repository.state.value.draft)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun recreationKeepsTheSelectionAndTheCollapsedList() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App() }
        compose.waitForIdle()
        compose.onNodeWithTag("toggleSessionList").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertChatShown()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        assertEquals(RemoteSelection("host", "project", "s1"), repository.activations.last().first)
    }

    // ---- Resizable dividers -------------------------------------------------------------

    private fun widthOf(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.right - it.left }

    /** Drags the divider [tag] by [distance] in small steps; the touch slop at the start is lost. */
    private fun drag(tag: String, distance: Dp) {
        compose.onNodeWithTag(tag).performTouchInput {
            val step = distance.toPx() / 10
            down(center)
            repeat(10) { moveBy(Offset(step, 0f)) }
            up()
        }
        compose.waitForIdle()
    }

    /** One gesture: out [first], then back by [second] (negative), without lifting the finger. */
    private fun dragThereAndBack(tag: String, first: Dp, second: Dp) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(first.toPx() / 10, 0f)) }
            repeat(10) { moveBy(Offset(second.toPx() / 10, 0f)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun accessibilityAction(tag: String, label: String) {
        val action =
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions].single {
                it.label == label
            }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun draggingTheListDividerWidensTheListAcrossRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App() }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
        drag("sessionListDivider", 120.dp)
        val dragged = widthOf("sessionListPane")
        assertTrue("list is $dragged", dragged > 320.dp)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(dragged)
        // The chat keeps its minimum beside the strip however far the divider goes: 900 - 65 - 401.
        drag("sessionListDivider", 600.dp)
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(434.dp)
        assertTrue(widthOf("chatPane") >= CHAT_PANE_MIN_WIDTH)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun draggingPastTheLimitAndBackFollowsTheFingerAgain() {
        render()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
        // 280 + 300 - 150 = 430 less the first 48 dp or so of touch slop; a drag that added its
        // deltas to the clamped 434 dp would end near 284 dp instead.
        dragThereAndBack("sessionListDivider", 300.dp, (-150).dp)
        val width = widthOf("sessionListPane")
        assertTrue("list is $width", width > 340.dp && width < 434.dp)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun theListDividerIsAccessible() {
        render()
        val divider = compose.onNodeWithTag("sessionListDivider")
        divider.assertWidthIsEqualTo(24.dp)
        divider.assert(hasContentDescription(label(R.string.remote_pane_resize_list)))
        accessibilityAction("sessionListDivider", label(R.string.remote_pane_wider))
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(320.dp)
        accessibilityAction("sessionListDivider", label(R.string.remote_pane_narrower))
        accessibilityAction("sessionListDivider", label(R.string.remote_pane_narrower))
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(240.dp)
        accessibilityAction("sessionListDivider", label(R.string.remote_pane_reset_width))
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun doubleTapOnTheDividerResetsTheList() {
        render()
        drag("sessionListDivider", 120.dp)
        assertTrue(widthOf("sessionListPane") > 320.dp)
        compose.onNodeWithTag("sessionListDivider").performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun aCollapsedListHasNoDivider() {
        render()
        compose.onNodeWithTag("toggleSessionList").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListDivider").assertDoesNotExist()
        // Below the inspector width there is no inspector divider either.
        openTool()
        compose.onNodeWithTag("inspectorDivider").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun rightToLeftDraggingTowardsTheChatWidensTheList() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() }
        }
        compose.waitForIdle()
        // The list is on the right; the chat is to its left.
        drag("sessionListDivider", (-120).dp)
        assertTrue(widthOf("sessionListPane") > 320.dp)
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun draggingTheInspectorDividerWidensTheInspector() {
        render()
        compose.onNodeWithTag("inspectorDivider").assertDoesNotExist()
        openTool()
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(400.dp)
        compose.onNodeWithTag("inspectorDivider")
            .assert(hasContentDescription(label(R.string.remote_pane_resize_inspector)))
        drag("inspectorDivider", (-120).dp)
        assertTrue(widthOf("inspectorPane") > 440.dp)
        // The open tool and the chat stay.
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        assertChatShown()
        accessibilityAction("inspectorDivider", label(R.string.remote_pane_reset_width))
        compose.onNodeWithTag("inspectorPane").assertWidthIsEqualTo(400.dp)
    }

    @Test
    @Config(qualifiers = "w1700dp-h1000dp")
    fun aNewLayoutBucketResetsTheDraggedWidths() {
        val window = mutableStateOf<Dp>(1300.dp)
        compose.setContent {
            Box(Modifier.width(window.value).fillMaxHeight()) { App() }
        }
        compose.waitForIdle()
        accessibilityAction("sessionListDivider", label(R.string.remote_pane_wider))
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(400.dp)
        // Within the bucket the dragged width stays.
        window.value = 1400.dp
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(400.dp)
        window.value = 900.dp
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(280.dp)
        window.value = 1300.dp
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertWidthIsEqualTo(360.dp)
    }

    // ---- Hardware keyboard ---------------------------------------------------------------

    private fun focusComposerAndType(text: String) {
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performTextInput(text)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun enterSendsAndShiftEnterAddsALine() {
        render(hardwareKeyboard = true)
        focusComposerAndType("hello")
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        // Typed text reaches the repository after the composer's pause.
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
        assertEquals("hello\n", repository.state.value.draft)
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun withTheSettingOffEnterNoLongerSendsButCtrlEnterDoes() {
        settings.setEnterSends(false)
        render(hardwareKeyboard = true)
        focusComposerAndType("hello")
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(0, repository.prompts)
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        assertEquals(1, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun ctrlKFocusesTheSessionSearchEvenFromACollapsedList() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("toggleSessionList").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        compose.onNodeWithTag("sessionSearch").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun ctrlNCreatesASessionInTheProject() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.N) } }
        compose.waitForIdle()
        assertEquals(1, repository.createdSessions)
        // Like the button, it does nothing while disconnected.
        repository.state.value = repository.state.value.copy(connected = false)
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.N) } }
        compose.waitForIdle()
        assertEquals(1, repository.createdSessions)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun ctrlBTogglesTheListOnlyBesideAChat() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        // The list is gone, so the chat's composer still has focus for the second press.
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        // Without a chat there is nothing to give the room to.
        back()
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        compose.onNodeWithTag("sessionSearch").performClick()
        compose.onNodeWithTag("sessionSearch").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun ctrlAndAltArrowsSwitchSessions() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s1"), repository.activations.last().first)
        // No session above the first: nothing happens.
        val count = repository.activations.size
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun altPageDownSwitchesTheProject() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.PageDown) } }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "other") to ActivationMode.USER_OPEN, repository.activations.last())
        compose.onNodeWithTag("chatPlaceholder").assertIsDisplayed()
        // No project after the last one: nothing happens.
        val count = repository.activations.size
        compose.onNodeWithTag("sessionSearch").performClick()
        compose.onNodeWithTag("sessionSearch").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.PageDown) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        compose.onNodeWithTag("sessionSearch").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.PageUp) } }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project") to ActivationMode.USER_OPEN, repository.activations.last())
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun shortcutsNeedAHardwareKeyboard() {
        render(hardwareKeyboard = false)
        compose.onNodeWithTag("composerField").performClick()
        val count = repository.activations.size
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        assertTrue(repository.activations.none { it.first.sessionId == "s2" })
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.N) } }
        compose.waitForIdle()
        assertEquals(0, repository.createdSessions)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneWithHardwareKeyboardSendsOnEnterLikeATablet() {
        render(hardwareKeyboard = true)
        focusComposerAndType("hello")
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        // Typed text reaches the repository after the composer's pause.
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
        assertEquals("hello\n", repository.state.value.draft)
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun anOpenDialogKeepsTheShortcutsFromTheScreenBehind() {
        repository.state.value =
            repository.state.value.copy(
                session = buildJsonObject {
                    put("id", "s1")
                    put("title", "First session")
                    put("status", "idle")
                    put("origin", "tui")
                }
            )
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("tuiInfo").performClick()
        compose.waitForIdle()
        val close = label(R.string.remote_tui_info_close)
        compose.onNodeWithText(close).assertIsDisplayed()
        val count = repository.activations.size
        compose.onNodeWithText(close).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        compose.onNodeWithText(close).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun shortcutsNeverTakeFocusAlsoWithTouchExploration() {
        val accessibility =
            compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        org.robolectric.Shadows.shadowOf(accessibility).setTouchExplorationEnabled(true)
        render(hardwareKeyboard = true)
        compose.onNode(isFocused()).assertDoesNotExist()
        // With nothing focused the window reports the keys, and the shortcuts run from there.
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        // The next chat does not take focus either.
        compose.onNode(isFocused()).assertDoesNotExist()
        // Leaving the composer does not pull focus back to the layout either.
        compose.onNodeWithTag("composerField").performClick()
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.runOnUiThread { compose.activity.currentFocus?.clearFocus() }
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionSearch").assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp")
    fun withNothingFocusedTheWindowStillRunsTheShortcuts() {
        render(hardwareKeyboard = true)
        // A chat opens with the composer focused; without a field focused, keys still work.
        compose.onNodeWithTag("composerField").assertIsFocused()
        clearFocus()
        compose.onNodeWithTag("composerField").assertIsNotFocused()
        val down = android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        // Through the window's input pipeline, which reports keys nothing handled to the
        // unhandled-key listeners, as a real Book Cover Keyboard press does.
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(decor)!!
            root.javaClass.getMethod("dispatchInputEvent", android.view.InputEvent::class.java).invoke(root, down)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
    }

    // ---- Hardware keyboard in a split-screen window ----------------------------------------
    // A 50:50 split next to a browser is about 640 dp wide: the phone layout, with the keyboard.

    /** Sends a key through the window's input pipeline, as a real keyboard does. */
    private fun windowKey(keyCode: Int, meta: Int = 0, time: Long = 0) {
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(decor)!!
            val dispatch = root.javaClass.getMethod("dispatchInputEvent", android.view.InputEvent::class.java)
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                dispatch.invoke(root, android.view.KeyEvent(time, time, action, keyCode, 0, meta))
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun composerKeys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        compose.onNodeWithTag("composerField").performKeyInput(block)
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun splitScreenFocusesTheComposerAndEnterSends() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        // The chat opens ready for typing.
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.onNodeWithTag("composerField").performTextInput("hello")
        composerKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals("hello\n", repository.state.value.draft)
        assertEquals(0, repository.prompts)
        composerKeys { pressKey(Key.Enter) }
        assertEquals(1, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun splitScreenCtrlDownOpensTheNextSessionAndTypingLandsInItsComposer() {
        render(hardwareKeyboard = true)
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        compose.onNodeWithTag("composerField").assertIsFocused()
        // With nothing focused, a typed character still goes into the composer, which takes focus.
        clearFocus()
        windowKey(android.view.KeyEvent.KEYCODE_H)
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.onNodeWithTag("composerField").assert(hasText("h"))
        // Up goes back; Ctrl and the arrow are not typed.
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionUp) } }
        assertEquals(RemoteSelection("host", "project", "s1"), repository.activations.last().first)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun aSlashTypedWithNothingFocusedOpensTheSlashSuggestions() {
        render(hardwareKeyboard = true)
        clearFocus()
        windowKey(android.view.KeyEvent.KEYCODE_SLASH)
        compose.onNodeWithTag("composerField").assert(hasText("/"))
        compose.onNodeWithText("/new").assertIsDisplayed()
        // Esc closes the suggestions first and keeps the text.
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithText("/new").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assert(hasText("/"))
        compose.onNodeWithTag("composerField").assertIsFocused()
        // The next Esc leaves the composer and keeps the draft; the chat stays.
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 4_000)
        compose.onNodeWithTag("composerField").assertIsNotFocused()
        compose.onNodeWithTag("composerField").assert(hasText("/"))
        assertChatShown()
        // Enter on the parked focus neither moves it to Back nor presses that.
        windowKey(android.view.KeyEvent.KEYCODE_ENTER)
        windowKey(android.view.KeyEvent.KEYCODE_ENTER)
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun splitScreenCtrlKOpensTheSessionsAndFocusesTheirSearch() {
        render(hardwareKeyboard = true)
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        compose.onNodeWithTag("composerField").assertDoesNotExist()
        compose.onNodeWithTag("sessionSearch").assertIsFocused()
        compose.onNodeWithTag("sessionSearch").performTextInput("Third")
        compose.waitForIdle()
        compose.onNodeWithText("Second session").assertDoesNotExist()
        compose.onNodeWithText("Third session").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun splitScreenCtrlNCreatesASessionAndAltPageDownSwitchesTheProject() {
        render(hardwareKeyboard = true)
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.N) } }
        assertEquals(1, repository.createdSessions)
        compose.onNodeWithTag("composerField").assertIsFocused()
        composerKeys { withKeyDown(Key.AltLeft) { pressKey(Key.PageDown) } }
        assertEquals(RemoteSelection("host", "other") to ActivationMode.USER_OPEN, repository.activations.last())
        compose.onNodeWithTag("composerField").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun escInARunningChatNeverLeavesItAndEscEscStops() {
        repository.state.value = repository.state.value.copy(status = "running")
        render(hardwareKeyboard = true)
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        assertChatShown()
        compose.onNodeWithTag("escapeStopHint").assertIsDisplayed()
        assertEquals(0, repository.aborts)
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_400)
        assertEquals(1, repository.aborts)
        compose.onNodeWithTag("escapeStopHint").assertDoesNotExist()
        assertChatShown()
        // Late: only the hint again.
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 5_000)
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 7_000)
        assertEquals(1, repository.aborts)
        // Ctrl+. stops at once.
        windowKey(android.view.KeyEvent.KEYCODE_PERIOD, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        assertEquals(2, repository.aborts)
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun escInAnIdleChatIsUsedUpWithoutLeavingIt() {
        render(hardwareKeyboard = true)
        clearFocus()
        val count = repository.activations.size
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE)
        assertChatShown()
        assertEquals(count, repository.activations.size)
        compose.onNodeWithTag("escapeStopHint").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun escClosesChangesOpenedWithCtrlShiftG() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render(hardwareKeyboard = true)
        composerKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } } }
        compose.onNodeWithTag("changesPane").assertIsDisplayed()
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun keysNeverReachTheComposerHiddenUnderChanges() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performTextInput("abc")
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        composerKeys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } } }
        compose.onNodeWithTag("changesPane").assertIsDisplayed()
        // Changes covers the chat here (and hides the composer from the semantics): neither
        // Enter nor a letter reaches the composer.
        windowKey(android.view.KeyEvent.KEYCODE_ENTER)
        windowKey(android.view.KeyEvent.KEYCODE_X)
        // Ctrl+L does not focus a composer nobody sees.
        windowKey(android.view.KeyEvent.KEYCODE_L, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        windowKey(android.view.KeyEvent.KEYCODE_ENTER)
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
        assertEquals("abc", repository.state.value.draft)
        // Closing Changes gives the composer its focus back.
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.onNodeWithTag("composerField").assert(hasText("abc"))
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun slashSuggestionsEscClosedComeBackWhenTheSlashIsTypedAgain() {
        render(hardwareKeyboard = true)
        clearFocus()
        windowKey(android.view.KeyEvent.KEYCODE_SLASH)
        compose.onNodeWithText("/new").assertIsDisplayed()
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithText("/new").assertDoesNotExist()
        compose.onNodeWithTag("composerField").performTextReplacement("")
        compose.onNodeWithTag("composerField").performTextInput("/")
        compose.waitForIdle()
        compose.onNodeWithText("/new").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun aSlashThatMatchesNoCommandLeavesTheComposerOnTheFirstEsc() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performTextInput("/zzz")
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertIsFocused()
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithTag("composerField").assertIsNotFocused()
        compose.onNodeWithTag("composerField").assert(hasText("/zzz"))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-keysexposed-qwerty")
    fun phoneCtrlUpDownWalkTheCurrentSessionsAfterCtrlN() {
        val offline = buildJsonObject {
            put("id", "gone")
            put("title", "Gone session")
            put("status", "offline")
            put("origin", "rpc")
        }
        settings.setHideOfflineSessions(true)
        repository.create = { routeId, projectId ->
            val created = RemoteSelection(routeId, projectId, "created")
            // The host lists the new session first.
            repository.state.value =
                repository.state.value.copy(
                    selection = created,
                    sessions = listOf(session("created", "Created session"), offline) + sessions,
                )
            created
        }
        render(hardwareKeyboard = true)
        back()
        compose.onNodeWithText("Second session").assertIsDisplayed()
        val ctrl = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        windowKey(android.view.KeyEvent.KEYCODE_N, ctrl)
        assertEquals(1, repository.createdSessions)
        assertChatShown()
        // The new chat is first: nothing above it, and below it the next session shown (the
        // offline one is hidden), not one of the list as it was before the create.
        val count = repository.activations.size
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_UP, ctrl)
        assertEquals(count, repository.activations.size)
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN, ctrl)
        assertEquals(RemoteSelection("host", "project", "s1"), repository.activations.last().first)
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_UP, ctrl)
        assertEquals(RemoteSelection("host", "project", "created"), repository.activations.last().first)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun escClosesTheToolDetailBeforeAnythingElse() {
        render(hardwareKeyboard = true)
        openTool()
        compose.onNodeWithTag("toolDetail").assertIsDisplayed()
        windowKey(android.view.KeyEvent.KEYCODE_ESCAPE, time = 1_000)
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        assertChatShown()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun aCreatedSessionFocusesItsComposerAlsoWithoutAKeyboard() {
        render(hardwareKeyboard = false)
        compose.onNodeWithTag("composerField").assertIsNotFocused()
        back()
        compose.onNodeWithContentDescription(label(R.string.remote_new_session)).performClick()
        compose.waitForIdle()
        assertEquals(1, repository.createdSessions)
        compose.onNodeWithTag("composerField").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneSessionsSearchIconRevealsAFilteringSearchField() {
        render()
        back()
        compose.onNodeWithTag("sessionSearch").assertDoesNotExist()
        compose.onNodeWithTag("sessionSearchToggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionSearch").assertIsFocused()
        compose.onNodeWithTag("sessionSearch").performTextInput("second")
        compose.waitForIdle()
        compose.onNodeWithText("First session").assertDoesNotExist()
        compose.onNodeWithText("Second session").assertIsDisplayed()
        // Closing the search shows every session again.
        compose.onNodeWithTag("sessionSearchToggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessionSearch").assertDoesNotExist()
        compose.onNodeWithText("First session").assertIsDisplayed()
    }

    // ---- Discoverability, completion, focus and scrolling ----------------------------------

    private val ctrlMeta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON

    private fun openSettings() {
        back()
        compose.onAllNodesWithContentDescription(label(R.string.remote_settings)).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp")
    fun aPhoneWithoutAKeyboardHasNoKeyboardSettings() {
        render(hardwareKeyboard = false)
        openSettings()
        compose.onNodeWithText(label(R.string.remote_swipe_title)).assertExists()
        compose.onNodeWithTag("keyboardSettings").assertDoesNotExist()
        compose.onNodeWithTag("enterSendsSwitch").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-keysexposed-qwerty")
    fun aPhoneWithAKeyboardListsEveryShortcutInSettings() {
        render(hardwareKeyboard = true)
        windowKey(android.view.KeyEvent.KEYCODE_COMMA, ctrlMeta)
        compose.onNodeWithTag("keyboardSettings").assertExists()
        compose.onNodeWithTag("enterSendsSwitch").assertExists()
        // Generated from the keymap: Ctrl+N and Ctrl+B are there now, and the chat's own keys.
        for (keys in listOf("Ctrl+N", "Ctrl+B", "Ctrl+K, Ctrl+F", "Ctrl+.", "F6, Ctrl+Alt+→", "Ctrl+End"))
            compose.onNode(hasText(keys) and hasAnyAncestor(hasTestTag("keyboardShortcuts"))).assertExists()
        // The help no longer claims the keys need the two-pane layout.
        val help = compose.activity.getString(R.string.remote_keyboard_help, "Ctrl+/")
        compose.onNodeWithText(help).assertExists()
        assertFalse(help.contains("two-pane"))
    }

    @Test
    @Config(qualifiers = "w900dp-h3000dp")
    fun theTwoPaneLayoutShowsTheKeyboardSettingsWithoutAKeyboard() {
        render(hardwareKeyboard = false)
        openSettings()
        compose.onNodeWithTag("keyboardSettings").assertExists()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun ctrlSlashListsTheKeysOfWhereTheUserIs() {
        render(hardwareKeyboard = true)
        windowKey(android.view.KeyEvent.KEYCODE_SLASH, ctrlMeta)
        compose.onNodeWithTag("shortcutHelp").assertExists()
        // In a chat: the chat's and the composer's keys too.
        compose.onNode(hasText("Ctrl+.") and hasAnyAncestor(hasTestTag("shortcutHelp"))).assertExists()
        compose.onNode(hasText("Tab") and hasAnyAncestor(hasTestTag("shortcutHelp"))).assertExists()
        compose.onNodeWithTag("shortcutHelpClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcutHelp").assertDoesNotExist()
        assertChatShown()
        // On the session list only the keys that work there.
        back()
        windowKey(android.view.KeyEvent.KEYCODE_SLASH, ctrlMeta)
        compose.onNode(hasText("Ctrl+N") and hasAnyAncestor(hasTestTag("shortcutHelp"))).assertExists()
        compose.onNode(hasText("Ctrl+.") and hasAnyAncestor(hasTestTag("shortcutHelp"))).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun enterWithAPartialSlashCommandPicksItInsteadOfSendingIt() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performTextInput("/ne")
        compose.waitForIdle()
        compose.onNodeWithTag("slashHighlight").assert(hasText("/new"))
        composerKeys { pressKey(Key.Enter) }
        compose.onNodeWithTag("composerField").assert(hasText("/new"))
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
        assertEquals(0, repository.createdSessions)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun arrowsMoveTheSlashHighlightAndTabPicksIt() {
        repository.state.value =
            repository.state.value.copy(
                capabilities = setOf(COMMANDS_CAPABILITY),
                commands = listOf(RemoteCommand("review", "Review the diff", "prompt"), RemoteCommand("refactor", null, "skill")),
            )
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performTextInput("/re")
        compose.waitForIdle()
        compose.onNodeWithTag("slashHighlight").assert(hasText("/review"))
        composerKeys { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("slashHighlight").assert(hasText("/refactor"))
        // Past the last one the highlight wraps to the first.
        composerKeys { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("slashHighlight").assert(hasText("/review"))
        composerKeys { pressKey(Key.DirectionUp) }
        composerKeys { pressKey(Key.Tab) }
        compose.onNodeWithTag("composerField").assert(hasText("/refactor"))
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun upInAnEmptyComposerRecallsThisSessionsLastMessage() {
        repository.state.value =
            repository.state.value.copy(
                messages =
                    listOf(
                        buildJsonObject {
                            put("id", "u1")
                            put("role", "user")
                            put("text", "first question")
                        },
                        buildJsonObject {
                            put("id", "u2")
                            put("role", "user")
                            put("text", "second question")
                        },
                    )
            )
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").assertIsFocused()
        composerKeys { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("composerField").assert(hasText("second question"))
        composerKeys { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("composerField").assert(hasText("first question"))
        composerKeys { pressKey(Key.DirectionDown) }
        composerKeys { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("composerField").assert(hasText(""))
        assertEquals(0, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun aRecalledSlashCommandKeepsUpForTheHistory() {
        repository.state.value =
            repository.state.value.copy(
                capabilities = setOf(COMMANDS_CAPABILITY),
                commands = listOf(RemoteCommand("review", "Review the diff", "prompt")),
                messages =
                    listOf(
                        buildJsonObject {
                            put("id", "u1")
                            put("role", "user")
                            put("text", "first question")
                        },
                        buildJsonObject {
                            put("id", "u2")
                            put("role", "user")
                            put("text", "/review")
                        },
                    ),
            )
        render(hardwareKeyboard = true)
        composerKeys { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("composerField").assert(hasText("/review"))
        // The recalled command opens the suggestions, but Up still walks the history.
        composerKeys { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("composerField").assert(hasText("first question"))
        assertEquals(0, repository.prompts)
    }

    private fun longChat() {
        repository.state.value =
            repository.state.value.copy(
                messages =
                    (1..80).map {
                        buildJsonObject {
                            put("id", "m$it")
                            put("role", "assistant")
                            put("text", "Line $it of the chat")
                        }
                    }
            )
    }

    private fun scrollToLatestShown() =
        compose.onAllNodesWithContentDescription(label(R.string.remote_scroll_to_bottom)).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun pageKeysScrollTheChatWhileTheComposerKeepsFocus() {
        longChat()
        render(hardwareKeyboard = true)
        compose.onNodeWithText("Line 80 of the chat").assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertIsFocused()
        composerKeys { pressKey(Key.PageUp) }
        compose.onNodeWithText("Line 80 of the chat").assertIsNotDisplayed()
        compose.onNodeWithTag("composerField").assertIsFocused()
        // Reading back stops following new output, as a scroll by hand does.
        assertTrue(scrollToLatestShown())
        repeat(3) { composerKeys { pressKey(Key.PageDown) } }
        compose.onNodeWithText("Line 80 of the chat").assertIsDisplayed()
        assertFalse(scrollToLatestShown())
        assertEquals(0, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun ctrlHomeGoesToTheStartAndCtrlEndFollowsTheChatAgain() {
        longChat()
        render(hardwareKeyboard = true)
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        compose.onNodeWithText("Line 1 of the chat").assertIsDisplayed()
        compose.onNodeWithText("Line 80 of the chat").assertDoesNotExist()
        assertTrue(scrollToLatestShown())
        // With nothing typed the key goes to the chat; the composer keeps focus.
        compose.onNodeWithTag("composerField").assertIsFocused()
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        compose.onNodeWithText("Line 80 of the chat").assertIsDisplayed()
        assertFalse(scrollToLatestShown())
        // Following again: new output scrolls into view.
        repository.state.value =
            repository.state.value.copy(
                messages =
                    repository.state.value.messages +
                        buildJsonObject {
                            put("id", "m81")
                            put("role", "assistant")
                            put("text", "Line 81 of the chat")
                        }
            )
        compose.waitForIdle()
        compose.onNodeWithText("Line 81 of the chat").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp-keysexposed-qwerty")
    fun ctrlHomeWithADraftReachesTheChatOnlyAfterTheFieldLeftIt() {
        longChat()
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").performTextInput("first line\nsecond line")
        compose.waitForIdle()
        // Sent to the field alone, the key is the field's: the layout leaves it alone.
        composerKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        compose.onNodeWithText("Line 80 of the chat").assertIsDisplayed()
        assertFalse(scrollToLatestShown())
        // Through the window, the field ignores Ctrl+Home, so the chat goes to its start.
        windowKey(android.view.KeyEvent.KEYCODE_MOVE_HOME, ctrlMeta)
        compose.onNodeWithText("Line 1 of the chat").assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertIsFocused()
        compose.onNodeWithTag("composerField").assert(hasText("first line\nsecond line"))
        assertEquals(0, repository.prompts)
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-keysexposed-qwerty")
    fun f6CyclesThePanesAndComesBackToTheRowItLeft() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").assertIsFocused()
        val inList = hasAnyAncestor(hasTestTag("sessionListPane"))
        // From the chat F6 wraps around to the project strip, then goes on to the sessions.
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("projectStripList"))).assertExists()
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNode(isFocused() and inList).assertExists()
        compose.onNodeWithTag("composerField").assertIsNotFocused()
        // Down moves to the next row, which shows its focus ring.
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val row = compose.onNode(isFocused() and inList).fetchSemanticsNode()
        compose.onNode(isFocused() and inList).assert(SemanticsMatcher.expectValue(FocusRingShown, true))
        // Ctrl+Alt+Right goes on to the chat's composer, Shift+F6 back to the same row.
        windowKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, ctrlMeta or android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON)
        compose.onNodeWithTag("composerField").assertIsFocused()
        windowKey(android.view.KeyEvent.KEYCODE_F6, android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON)
        assertEquals(row.id, compose.onNode(isFocused() and inList).fetchSemanticsNode().id)
    }

    @Test
    @Config(qualifiers = "w1205dp-h753dp-keysexposed-qwerty")
    fun f6ReachesTheProjectStripBesideAChat() {
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("composerField").assertIsFocused()
        val inStrip = hasAnyAncestor(hasTestTag("projectStripList"))
        // From the chat F6 wraps around to the projects, then goes on to the sessions.
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNode(isFocused() and inStrip).assertExists()
        compose.onNode(isFocused() and inStrip).assert(SemanticsMatcher.expectValue(FocusRingShown, true))
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("sessionListPane"))).assertExists()
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNodeWithTag("composerField").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w1700dp-h1000dp-keysexposed-qwerty")
    fun f6StillReachesTheSideInspectorAfterTheJobsClose() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(BACKGROUND_JOBS_CAPABILITY))
        render(hardwareKeyboard = true)
        openTool()
        compose.onNode(hasTestTag("toolDetail") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        // The jobs join the inspector as a tab of their own, then close again.
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(jobs = JobsState("s1", loaded = true, listOpen = true))
        }
        compose.waitForIdle()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(jobs = null) }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").requestFocusAndWait()
        windowKey(android.view.KeyEvent.KEYCODE_F6)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("inspectorPane"))).assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.requestFocusAndWait() {
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-keysexposed-qwerty")
    fun hoveringAHeaderButtonNamesItAndItsShortcut() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY))
        render(hardwareKeyboard = true)
        compose.onNodeWithTag("chatAction_changes").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_changes_open) + " · Ctrl+Shift+G").assertExists()
    }

    // ---- Code editor ---------------------------------------------------------------------

    private fun openEditor(text: String) {
        val file = OpenFile("src/Main.kt", loading = false, version = "v1", size = 6, content = "hello\n")
        repository.state.value =
            repository.state.value.copy(
                files = FilesState("s1", loading = false, file = file, editor = FileEditor(file.path, "v1", file.content, text))
            )
        compose.waitForIdle()
    }

    private fun ctrl(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun anOpenEditorKeepsTheShortcutKeys() {
        render(hardwareKeyboard = true)
        openEditor("hello\n")
        // With focus in the editor, its keys are its own.
        val count = repository.activations.size
        ctrl("filesEditorText", Key.DirectionDown)
        assertEquals(count, repository.activations.size)
        // No second tap: on a focused field it opens the cursor handle's popup.
        compose.onNodeWithTag("filesEditorText").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorFindQuery").assertIsDisplayed()
        compose.onNodeWithTag("sessionSearch").assertIsNotFocused()
        compose.onNodeWithTag("filesEditorFindQuery").performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        // With focus elsewhere (the session list; the editor covers the chat here), the layout's
        // shortcuts work while the editor stays open.
        ctrl("sessionSearch", Key.DirectionDown)
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        ctrl("sessionSearch", Key.N)
        assertEquals(1, repository.createdSessions)
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun aShortcutOutsideTheEditorAsksBeforeItDropsAnUnsavedEdit() {
        render(hardwareKeyboard = true)
        openEditor("changed\n")
        val count = repository.activations.size
        ctrl("sessionSearch", Key.DirectionDown)
        assertEquals(count, repository.activations.size)
        compose.onNodeWithTag("filesEditorKeep").performClick()
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        assertEquals("changed\n", repository.state.value.files?.editor?.text)
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun aDiscardPromptOnlyDropsTheEditItAskedAbout() {
        render(hardwareKeyboard = true)
        openEditor("changed\n")
        val count = repository.activations.size
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorDiscard").assertIsDisplayed()
        // Another file's edit took its place: Discard drops neither it nor runs the navigation.
        val other = OpenFile("src/Other.kt", loading = false, version = "v1", size = 6, content = "other\n")
        repository.state.value =
            repository.state.value.copy(
                files = FilesState("s1", loading = false, file = other, editor = FileEditor(other.path, "v1", other.content, "mine\n"))
            )
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        assertEquals("mine\n", repository.state.value.files?.editor?.text)
        // Another session ends the prompt without an answer.
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorDiscard").assertIsDisplayed()
        repository.state.value = repository.state.value.copy(selection = RemoteSelection("host", "project", "s3"))
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorDiscard").assertDoesNotExist()
        assertEquals("mine\n", repository.state.value.files?.editor?.text)
    }

    @Test
    @Config(qualifiers = "w740dp-h1200dp")
    fun anotherSessionAsksBeforeItDropsAnUnsavedEdit() {
        render(hardwareKeyboard = true)
        openEditor("changed\n")
        val count = repository.activations.size
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        compose.onNodeWithTag("filesEditorKeep").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filesEditorDiscard").assertDoesNotExist()
        assertEquals(count, repository.activations.size)
        assertEquals("changed\n", repository.state.value.files?.editor?.text)
        compose.onNodeWithText("Second session").performClick()
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        assertNull(repository.state.value.files?.editor)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-keysexposed-qwerty")
    fun anUnsavedEditBehindAnotherTabStillAsksBeforeAnotherSession() {
        repository.state.value = repository.state.value.copy(capabilities = setOf(GIT_CAPABILITY, FILES_CAPABILITY))
        render(hardwareKeyboard = true)
        openEditor("changed\n")
        assertInInspector("filesPane")
        click("chatAction_changes")
        assertInInspector("changesPane")
        compose.onNodeWithTag("inspectorTab_files").assertIsNotSelected()
        // The list shown by hand fits beside the chat and the inspector here.
        click("toggleSessionList")
        compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        assertInInspector("changesPane")
        val count = repository.activations.size
        compose.onNodeWithText("Second session").performClick()
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        compose.onNodeWithTag("filesEditorKeep").performClick()
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        assertEquals("changed\n", repository.state.value.files?.editor?.text)
        compose.onNodeWithText("Second session").performClick()
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "s2"), repository.activations.last().first)
        assertNull(repository.state.value.files?.editor)
    }
}
