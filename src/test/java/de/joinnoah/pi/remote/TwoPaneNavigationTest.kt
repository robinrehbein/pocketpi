package de.joinnoah.pi.remote

import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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

    init {
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
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
        compose.onNode(isSelected()).assertIsDisplayed()
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
    fun shortcutsNeedAHardwareKeyboard() {
        render(hardwareKeyboard = false)
        compose.onNodeWithTag("composerField").performClick()
        val count = repository.activations.size
        compose.onNodeWithTag("composerField").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertEquals(count, repository.activations.size)
        assertTrue(repository.activations.none { it.first.sessionId == "s2" })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun phoneWithHardwareKeyboardKeepsEnterAsANewline() {
        render(hardwareKeyboard = true)
        focusComposerAndType("hello")
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
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
}
