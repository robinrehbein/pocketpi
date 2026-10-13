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
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            MaterialTheme { RemoteNavigation(repository, settings, false, {}, notification) }
        }
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

    // ---- Two panes -----------------------------------------------------------------------

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
    @Config(qualifiers = "w900dp-h1200dp")
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
    @Config(qualifiers = "w900dp-h1200dp")
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
    fun phoneWithHardwareKeyboardKeepsEnterAsANewline() {
        render(hardwareKeyboard = true)
        focusComposerAndType("hello")
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        // Typed text reaches the repository after the composer's pause.
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(0, repository.prompts)
        assertEquals("hello\n", repository.state.value.draft)
        // Ctrl+Enter does not send on a phone either.
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        compose.waitForIdle()
        assertEquals(0, repository.prompts)
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
        compose.onNode(isFocused()).assertDoesNotExist()
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
    @Config(qualifiers = "w900dp-h1200dp")
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
    @Config(qualifiers = "w900dp-h1200dp")
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
    @Config(qualifiers = "w900dp-h1200dp")
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
    @Config(qualifiers = "w900dp-h1200dp")
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
}
