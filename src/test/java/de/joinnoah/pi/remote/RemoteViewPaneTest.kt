package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp")
class RemoteViewPaneTest {
    @get:Rule val compose = createComposeRule()

    private val liveRegion = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test fun headerShowsTitleCloseAndReloadAndReportsClicks() {
        var closed = 0
        var reloaded = 0
        val busy = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RemoteViewPane("Browser", "browser", onClose = { closed++ }, subtitle = "localhost", busy = busy.value, onReload = { reloaded++ }) {
                    Text("content", Modifier.testTag("content"))
                }
            }
        }
        compose.onNodeWithTag("browserPane").assertIsDisplayed()
        compose.onNodeWithTag("browserTitle").assertTextEquals("Browser").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("browserSubtitle").assertTextEquals("localhost")
        compose.onNodeWithTag("browserBack").assertDoesNotExist()
        compose.onNodeWithTag("browserClose").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("browserReload").performClick()
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(1, reloaded)
        }
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("browserReload").assertIsNotEnabled()
        // The content sits under the header, not behind it.
        val header = compose.onNodeWithTag("browserHeaderPill").getUnclippedBoundsInRoot()
        assertTrue(compose.onNodeWithTag("content").getUnclippedBoundsInRoot().top >= header.bottom)
    }

    @Test fun headerWithoutReloadAndWithBack() {
        var back = 0
        var closed = 0
        compose.setContent {
            MaterialTheme { RemoteViewPane("Emulator", "emulator", onClose = { closed++ }, onBack = { back++ }) {} }
        }
        compose.onNodeWithTag("emulatorReload").assertDoesNotExist()
        compose.onNodeWithTag("emulatorBack").performClick()
        compose.onNodeWithTag("emulatorClose").performClick()
        compose.runOnIdle {
            assertEquals(1, back)
            assertEquals(1, closed)
        }
    }

    @Test fun failureNoticeOffersRetryAndIsALiveRegion() {
        var retried = 0
        compose.setContent {
            MaterialTheme {
                RemoteViewPane(
                    "Browser", "browser", onClose = {},
                    notices = listOf(
                        RemoteViewNotice.Info("Another participant has control", "busyNotice"),
                        RemoteViewNotice.Failure("Browser request failed.", "failedNotice", onRetry = { retried++ }),
                        RemoteViewNotice.Failure("Not sent.", "refusedNotice"),
                    ),
                ) {}
            }
        }
        compose.onNodeWithTag("busyNotice").assertTextEquals("Another participant has control").assert(liveRegion)
        compose.onNodeWithText("Browser request failed.").assert(liveRegion)
        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag("failedNotice"))).performClick()
        compose.runOnIdle { assertEquals(1, retried) }
        compose.onNodeWithText("Not sent.").assert(liveRegion)
        compose.onNode(hasText("Try again") and hasAnyAncestor(hasTestTag("refusedNotice"))).assertDoesNotExist()
    }

    @Test fun viewportShowsPlaceholderLabelOverItsContent() {
        var placeholder by mutableStateOf<RemoteViewPlaceholder?>(RemoteViewPlaceholder("Waiting for the first frame…"))
        compose.setContent {
            MaterialTheme {
                RemoteViewPane("Browser", "browser", onClose = {}) {
                    RemoteViewport("browserViewport", Modifier.weight(1f).fillMaxWidth(), placeholder) { Box(Modifier.testTag("frame")) }
                }
            }
        }
        compose.onNodeWithTag("browserViewportPlaceholder").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed().assert(liveRegion)
        // The content stays composed under the placeholder, as a terminal renderer must.
        compose.onNodeWithTag("frame").assertExists()
        compose.runOnIdle { placeholder = null }
        compose.onNodeWithTag("browserViewportPlaceholder").assertDoesNotExist()
        compose.onNodeWithTag("frame").assertExists()
    }

    @Test fun controlBarShowsEachStateAndFiresItsActions() {
        var state by mutableStateOf(RemoteControlState.VIEWING)
        var holder by mutableStateOf<String?>(null)
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                RemoteControlBar(
                    state, "browser", holder = holder,
                    onTake = { events += "take" }, onRelease = { events += "release" },
                    handoffTargets = listOf(RemoteControlTarget("p1", "Mac"), RemoteControlTarget("p2", "Tablet")),
                    onHandOff = { events += "handoff:$it" },
                )
            }
        }
        compose.onNodeWithTag("browserControlState").assertTextEquals("View only").assert(liveRegion)
        compose.onNodeWithTag("browserRelease").assertDoesNotExist()
        compose.onNodeWithTag("browserHandoff").assertDoesNotExist()
        compose.onNodeWithTag("browserAcquire").assertTextEquals("Take control").performClick()

        compose.runOnIdle { state = RemoteControlState.HELD }
        compose.onNodeWithTag("browserControlState").assertTextEquals("Another participant has control")
        compose.runOnIdle { holder = "Mac" }
        compose.onNodeWithTag("browserControlState").assertTextEquals("Control held by Mac")
        compose.onNodeWithTag("browserAcquire").performClick()

        compose.runOnIdle { state = RemoteControlState.CONTROLLING }
        compose.onNodeWithTag("browserControlState").assertTextEquals("You have control")
        compose.onNodeWithTag("browserAcquire").assertDoesNotExist()
        compose.onNodeWithTag("browserRelease").performClick()
        compose.onNodeWithTag("browserHandoff").performClick()
        compose.onNodeWithTag("browserHandoff_p2").assertContentDescriptionEquals("Hand over control to Tablet").performClick()
        compose.onNodeWithTag("browserHandoff_p2").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("take", "take", "release", "handoff:p2"), events) }
    }

    @Test fun controlBarExpressesTheTerminalTakeover() {
        var taken = 0
        val enabled = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RemoteControlBar(RemoteControlState.HELD, "terminal", holder = "the Mac", enabled = enabled.value, onTake = { taken++ })
            }
        }
        // The same holder form and take action as the browser's and the emulator's.
        compose.onNodeWithTag("terminalControlState").assertTextEquals("Control held by the Mac")
        compose.onNodeWithTag("terminalAcquire").assertTextEquals("Take control").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("terminalAcquire").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, taken) }
    }

    @Test fun controlBarStacksItsActionsWhereTheyDoNotFitBesideTheState() {
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        RemoteControlBar(RemoteControlState.HELD, "emulator", holder = "another phone", onTake = {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            val bar = compose.onNodeWithTag("emulatorControlBar").getUnclippedBoundsInRoot()
            val label = compose.onNodeWithTag("emulatorControlState").getUnclippedBoundsInRoot()
            val take = compose.onNodeWithTag("emulatorAcquire").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            assertTrue("fontScale=$scale: action below the state", take.top >= label.bottom - 0.5.dp)
            assertTrue("fontScale=$scale: action inside the pill", take.right <= bar.right && take.bottom <= bar.bottom)
            assertTrue("fontScale=$scale: state inside the pill", label.right <= bar.right)
        }
    }

    @Test fun keyStripClicksDisablesAndToggles() {
        var ctrl by mutableStateOf(false)
        val pressed = mutableListOf<String>()
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        RemoteKeyStrip(
                            listOf(
                                RemoteKey("escape", "Esc", "Escape"),
                                RemoteKey("ctrl", "Ctrl", selected = ctrl),
                                RemoteKey("up", "↑", "Up arrow", enabled = false),
                                RemoteKey("enter", "Enter"),
                            ),
                            onKey = { if (it.id == "ctrl") ctrl = !ctrl else pressed += it.id },
                            tag = "terminal",
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("terminalKey_escape").assertContentDescriptionEquals("Escape").performClick()
        compose.onNodeWithTag("terminalKey_up").assertContentDescriptionEquals("Up arrow").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("escape"), pressed) }
        compose.onNodeWithTag("terminalKey_ctrl").assertIsOff().performClick()
        compose.onNodeWithTag("terminalKey_ctrl").assertIsOn()
        // A key that is not a modifier has no toggle state.
        compose.onNodeWithTag("terminalKey_escape").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            listOf("escape", "ctrl", "up").forEach {
                compose.onNodeWithTag("terminalKey_$it").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
        }
        // A larger font makes the chip larger instead of clipping its label.
        val large = compose.onNodeWithTag("terminalKey_escape").getUnclippedBoundsInRoot()
        compose.runOnIdle { fontScale.value = 1f }
        val normal = compose.onNodeWithTag("terminalKey_escape").getUnclippedBoundsInRoot()
        assertTrue(
            "large=$large normal=$normal",
            large.right - large.left > normal.right - normal.left || large.bottom - large.top > normal.bottom - normal.top,
        )
    }

    @Test fun keyStripDisabledAsAWhole() {
        var pressed = 0
        compose.setContent {
            MaterialTheme { RemoteKeyStrip(listOf(RemoteKey("Home", "Home")), onKey = { pressed++ }, tag = "emulator", enabled = false) }
        }
        compose.onNodeWithTag("emulatorKey_Home").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, pressed) }
    }

    @Test fun inputBarSendsFromTheButtonAndTheKeyboard() {
        var draft by mutableStateOf("")
        val sent = mutableListOf<String>()
        var extra = 0
        compose.setContent {
            MaterialTheme {
                RemoteInputBar(draft, { draft = it }, onSend = { sent += draft; draft = "" }, tag = "terminalInput", label = "Terminal input", maxLines = 1) {
                    androidx.compose.material3.TextButton(onClick = { extra++ }, Modifier.testTag("extra")) { Text("Keys") }
                }
            }
        }
        compose.onNodeWithTag("terminalInput").assertContentDescriptionEquals("Terminal input")
        compose.onNodeWithTag("terminalInputSend").assertIsNotEnabled().assertContentDescriptionEquals("Send")
        // Nothing to send: the keyboard action does nothing either.
        compose.onNodeWithTag("terminalInput").performImeAction()
        compose.onNodeWithTag("terminalInput").performTextInput("ls")
        compose.onNodeWithTag("terminalInputSend").assertIsEnabled().assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("terminalInput").performTextInput("pwd")
        compose.onNodeWithTag("terminalInput").performImeAction()
        compose.onNodeWithTag("extra").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ls", "pwd"), sent)
            assertEquals(1, extra)
        }
    }

    @Test fun inputBarGrowsOnlyToMaxLines() {
        val maxLines = mutableStateOf(1)
        compose.setContent {
            MaterialTheme { RemoteInputBar("one\ntwo\nthree\nfour\nfive", {}, onSend = {}, tag = "input", maxLines = maxLines.value) }
        }
        fun height() = compose.onNodeWithTag("input").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val one = height()
        compose.runOnIdle { maxLines.value = 3 }
        val three = height()
        compose.runOnIdle { maxLines.value = 5 }
        val five = height()
        assertTrue("one=$one three=$three five=$five", one < three && three < five)
    }

    @Test fun hardwareEscDoesNotCloseAPaneThatKeepsItButBackDoes() {
        var closed = 0
        var stopped = 0
        val target = ChatKeyTarget().apply { running = true; stop = { stopped++ } }
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalChatKeyTarget provides target) {
                MaterialTheme {
                    Column {
                        // Something of the chat beside the pane that can hold focus.
                        androidx.compose.material3.TextField("", {}, Modifier.testTag("chatField"))
                        Box(Modifier.weight(1f)) {
                            RemoteViewPane("Terminal", "terminal", onClose = { closed++ }, escapeCloses = false) {
                                RemoteInputBar("", {}, onSend = {}, tag = "terminalInput")
                            }
                        }
                    }
                }
            }
        }
        // Over the chat (a phone): Esc takes the pane's turn, so it neither closes the pane nor
        // reaches the chat behind it, where Esc, Esc would stop pi.
        compose.runOnIdle {
            target.composerCovered = true
            target.escape(1_000L); target.escape(1_100L)
            assertTrue(target.paneOpen)
            assertEquals(0, closed)
            assertEquals(0, stopped)
            target.composerCovered = false
        }
        // Beside the chat with keyboard focus in the pane: the same.
        compose.onNodeWithTag("terminalInput").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            target.escape(2_000L); target.escape(2_100L)
            assertTrue(target.paneOpen)
            assertEquals(0, closed)
            assertEquals(0, stopped)
        }
        compose.onNodeWithTag("chatField").performSemanticsAction(SemanticsActions.RequestFocus)
        // Beside the chat with focus elsewhere: Esc runs the chat's steps and still never closes the pane.
        compose.runOnIdle {
            assertFalse(target.paneOpen)
            target.escape(3_000L); target.escape(3_100L)
            assertEquals(0, closed)
            assertEquals(1, stopped)
            // Back closes it wherever it is.
            dispatcher.onBackPressed()
            assertEquals(1, closed)
        }
    }

    @Test fun escEscReachesTheChatWhileATerminalTabShowsBesideIt() {
        val chat = RemoteNavKey.Chat("host", "project", "session")
        val scope = CoroutineScope(Job())
        val navigator = RemoteNavigator(NavigationFakeRepository(), chat.selection().keys().toMutableList<NavKey>(), scope)
        var closed = 0
        var stopped = 0
        val target = ChatKeyTarget().apply { running = true; stop = { stopped++ } }
        val targets = ShortcutTargets().apply { register(chat, target) }
        compose.setContent {
            CompositionLocalProvider(LocalChatKeyTarget provides target) {
                MaterialTheme {
                    // The inspector beside the chat with the terminal as its active tab.
                    InspectorHost(
                        InspectorTabs().open(InspectorTab.TERMINAL),
                        content = { tab ->
                            if (tab == InspectorTab.TERMINAL) ({ RemoteViewPane("Terminal", "terminal", onClose = { closed++ }, escapeCloses = false) {} })
                            else null
                        },
                        onSelect = {}, onClose = {}, expanded = false, onExpand = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("terminalPane").assertIsDisplayed()
        compose.runOnIdle {
            // Nothing has focus, so the window reports the keys as unhandled.
            for (time in listOf(1_000L, 1_200L))
                for (action in listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP))
                    assertTrue(navigator.key(KeyEvent(NativeKeyEvent(time, time, action, NativeKeyEvent.KEYCODE_ESCAPE, 0)), null, targets, unhandled = true))
            assertEquals("Esc, Esc stops pi", 1, stopped)
            assertEquals("the terminal tab stays open", 0, closed)
        }
        scope.cancel()
    }

    @Test fun hardwareEscClosesAPaneByDefault() {
        var closed = 0
        val target = ChatKeyTarget()
        compose.setContent {
            CompositionLocalProvider(LocalChatKeyTarget provides target) {
                MaterialTheme { RemoteViewPane("Emulator", "emulator", onClose = { closed++ }) {} }
            }
        }
        compose.runOnIdle {
            target.escape(1_000L)
            assertEquals(1, closed)
        }
    }

    @Test fun aPaneIsCompactBelowTheTerminalsThresholdAndItsInputBarIsThenOneLine() {
        var height by mutableStateOf(380.dp)
        var compact: Boolean? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height)) {
                    RemoteViewPane("Browser", "browser", onClose = {}) {
                        compact = LocalRemoteViewCompact.current
                        RemoteInputBar("one\ntwo\nthree", {}, onSend = {}, tag = "input")
                    }
                }
            }
        }
        fun inputHeight() = compose.onNodeWithTag("input").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.runOnIdle { assertEquals(true, compact) }
        val one = inputHeight()
        compose.runOnIdle { height = 600.dp }
        compose.runOnIdle { assertEquals(false, compact) }
        assertTrue(inputHeight() > one)
        assertTrue(remoteViewCompact(399.dp))
        assertTrue(!remoteViewCompact(400.dp))
    }

    @Test fun viewportShowsAThinBusyBarWhileInputIsOnItsWay() {
        var busy by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RemoteViewPane("Terminal", "terminal", onClose = {}) {
                    RemoteViewport("terminalViewport", Modifier.weight(1f).fillMaxWidth(), busy = busy)
                }
            }
        }
        compose.onAllNodesWithTag("terminalViewportBusy").assertCountEquals(0)
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("terminalViewportBusy").assertContentDescriptionEquals("Sending input…").assertHeightIsAtLeast(1.dp)
        val bar = compose.onNodeWithTag("terminalViewportBusy").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("terminalViewport").getUnclippedBoundsInRoot()
        // Along the viewport's top edge (the node's bounds may include its touch area around the 2dp bar).
        val centre = (bar.top + bar.bottom) / 2
        assertTrue("bar=$bar viewport=$viewport", centre >= viewport.top && centre <= viewport.top + 3.dp)
    }

    @Test fun navigationKeysAreTheSameIconChipsInTheSameOrderEverywhere() {
        val pressed = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    RemoteKeyStrip(remoteNavigationKeys(RemoteNavigationIds.TERMINAL), onKey = { pressed += it.id }, tag = "terminal")
                    RemoteKeyStrip(remoteNavigationKeys(), onKey = { pressed += it.id }, tag = "emulator")
                }
            }
        }
        val spoken = listOf("Backspace", "Left arrow", "Up arrow", "Down arrow", "Right arrow")
        listOf("backspace", "left", "up", "down", "right").zip(spoken).forEach { (id, name) ->
            compose.onNodeWithTag("terminalKey_$id").assertContentDescriptionEquals(name).assertWidthIsAtLeast(48.dp).performClick()
        }
        listOf("Backspace", "ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight").zip(spoken).forEach { (id, name) ->
            compose.onNodeWithTag("emulatorKey_$id").assertContentDescriptionEquals(name)
        }
        compose.runOnIdle { assertEquals(listOf("backspace", "left", "up", "down", "right"), pressed) }
        // Left of each other in that order, the same in both strips.
        val left = listOf("left", "up", "down", "right").map { compose.onNodeWithTag("terminalKey_$it").getUnclippedBoundsInRoot().left }
        assertEquals(left.sorted(), left)
    }

    @Test fun tabStripSelectsAndClosesTabsAsChips() {
        val events = mutableListOf<String>()
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                RemoteTabStrip(
                    listOf(RemoteTab("a", "Docs", selected = true, closeLabel = "Close tab Docs"), RemoteTab("b", "Mail", selected = false)),
                    onSelect = { events += "select:$it" }, onClose = { events += "close:$it" }, tag = "browser", enabled = enabled,
                ) { Text("New", Modifier.testTag("trailing")) }
            }
        }
        compose.onNodeWithTag("browserTab_a").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("browserTab_b").assertIsNotSelected().performClick()
        compose.onNodeWithTag("browserTabClose_a").assertContentDescriptionEquals("Close tab Docs").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("browserTabClose_b").assertDoesNotExist()
        compose.onNodeWithTag("trailing").assertExists()
        compose.runOnIdle { assertEquals(listOf("select:b", "close:a"), events); enabled = false }
        compose.onNodeWithTag("browserTab_b").assertIsNotEnabled()
    }

    // Native graphics: the legacy text metrics ignore maxLines, so the one-line draft field would
    // measure four lines tall.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aCompactPaneLeavesTheTerminalItsBudgetWithDraftModifiersAndPendingInput() {
        // The terminal's chrome in its fullest compact state: header with the modifier subtitle and
        // an action, the key strip, a four-line draft with the insert action, input on its way.
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    RemoteViewPane("Terminal", "terminal", onClose = {}, subtitle = "Ctrl+Alt+Shift",
                        actions = listOf(PaneAction("Shortcuts", Icons.Default.Keyboard, "terminalShortcuts"))) {
                        RemoteViewport("terminalViewport", Modifier.weight(1f).fillMaxWidth(), busy = true)
                        RemoteKeyStrip(listOf(RemoteKey("escape", "Esc"), RemoteKey("tab", "Tab"), RemoteKey("enter", "Enter")) + remoteNavigationKeys(RemoteNavigationIds.TERMINAL), onKey = {}, tag = "terminal", sheds = true)
                        RemoteInputBar("first line\nsecond line\nthird line\nfourth line", {}, onSend = {}, tag = "terminalInput") {
                            androidx.compose.material3.IconButton(onClick = {}, Modifier.width(48.dp).height(48.dp)) {}
                        }
                    }
                }
            }
        }
        // On a device the header is 48dp (one row whatever its subtitle), the key strip 48 and the
        // input bar 56: 152dp, plus 24dp of status bar over a pane at the top of the window, leaves
        // 104dp of 280 (the androidTest TerminalPaneCompactUiTest asserts at least 96 on a Pixel 2).
        // Robolectric's fonts and window differ from the device's, so this checks the rules that
        // budget rests on: a header of exactly 48dp, no bar below its minimum (the input bar's is
        // one line), and the viewport floor out of the room above those minimums.
        fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        fun height(tag: String) = bounds(tag).let { it.bottom - it.top }
        val pane = bounds("terminalPane")
        val header = bounds("terminalHeaderPill")
        assertEquals(pane.top, header.top)
        assertEquals(48.dp, height("terminalHeaderPill"))
        val column = bounds("terminalInput").bottom - header.top
        val minimums = height("terminalHeaderPill") + height("terminalKeys") + 56.dp
        val viewport = height("terminalViewport")
        val budget = "pane=$pane column=$column header=${height("terminalHeaderPill")} keys=${height("terminalKeys")} input=${height("terminalInput")} viewport=$viewport"
        println("Compact terminal budget at 280dp: $budget")
        assertTrue(budget, viewport >= minOf(REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT, column - minimums) - 0.5.dp)
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals("Ctrl+Alt+Shift")
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        compose.onNodeWithTag("terminalInput").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aShortPaneKeepsTheInputBarWholeWhileTheViewportShrinks() {
        // Phone landscape with the soft keyboard leaves a pane about 180dp high.
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(180.dp)) {
                    RemoteViewPane("Terminal", "terminal", onClose = {}) {
                        RemoteViewport("terminalViewport", Modifier.weight(1f).fillMaxWidth())
                        RemoteKeyStrip(listOf(RemoteKey("escape", "Esc"), RemoteKey("tab", "Tab")), onKey = {}, tag = "terminal", sheds = true)
                        RemoteInputBar("", {}, onSend = {}, tag = "terminalInput")
                    }
                }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("terminalInput").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        assertTrue("input bar inside the pane", bounds("terminalInput").bottom <= bounds("terminalPane").bottom + 0.5.dp)
        val viewport = bounds("terminalViewport")
        assertTrue("viewport=$viewport", viewport.bottom - viewport.top < REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT)
    }

    @Test fun barsThatDoNotFitGiveWayNoticesBeforeTheKeyStripAndTheOthersKeepTheirHeight() {
        var height by mutableStateOf(300.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height)) {
                    RemoteViewColumn(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("header"))
                        Box(Modifier.fillMaxWidth().height(40.dp).remoteViewShed(RemoteViewShed.FAILURE).testTag("notice"))
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("viewport"))
                        Box(Modifier.fillMaxWidth().height(48.dp).remoteViewShed(RemoteViewShed.KEYS).testTag("keys"))
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("input"))
                    }
                }
            }
        }
        fun height(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        // Everything fits: the viewport gets the rest (300 - 192).
        assertEquals(108.dp, height("viewport"))
        compose.onNodeWithTag("keys").assertIsDisplayed()
        // 192dp of bars in 180: the notice goes, the key strip and the rest keep their heights.
        compose.runOnIdle { height = 180.dp }
        compose.onNodeWithTag("notice").assertIsNotDisplayed()
        compose.onNodeWithTag("keys").assertIsDisplayed()
        assertEquals(28.dp, height("viewport"))
        assertEquals(56.dp, height("input"))
        assertEquals(180.dp, compose.onNodeWithTag("input").getUnclippedBoundsInRoot().bottom)
        // 152dp in 130: the key strip goes too.
        compose.runOnIdle { height = 130.dp }
        compose.onNodeWithTag("keys").assertIsNotDisplayed()
        assertEquals(26.dp, height("viewport"))
        // Nothing else gives way: the header and the input bar are never squeezed.
        compose.runOnIdle { height = 90.dp }
        assertEquals(48.dp, height("header"))
        assertEquals(56.dp, height("input"))
        assertEquals(0.dp, height("viewport"))
    }

    @Test fun aCompactViewportFloorShedsIdleKeysThenInfoThenControlThenFailuresThenKeysBeforeItShrinks() {
        var height by mutableStateOf(360.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height)) {
                    RemoteViewColumn(Modifier.fillMaxSize(), viewportMinHeight = REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("header"))
                        Box(Modifier.fillMaxWidth().height(24.dp).remoteViewShed(RemoteViewShed.INFO).testTag("info"))
                        Box(Modifier.fillMaxWidth().height(32.dp).remoteViewShed(RemoteViewShed.CONTROL).testTag("control"))
                        Box(Modifier.fillMaxWidth().height(40.dp).remoteViewShed(RemoteViewShed.FAILURE).testTag("failure"))
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("viewport"))
                        Box(Modifier.fillMaxWidth().height(16.dp).remoteViewShed(RemoteViewShed.IDLE_KEYS).testTag("idle"))
                        Box(Modifier.fillMaxWidth().height(48.dp).remoteViewShed(RemoteViewShed.KEYS).testTag("keys"))
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("input"))
                    }
                }
            }
        }
        fun height(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        fun shown(vararg tags: String) = listOf("idle", "info", "control", "failure", "keys").forEach {
            if (it in tags) compose.onNodeWithTag(it).assertIsDisplayed() else compose.onNodeWithTag(it).assertIsNotDisplayed()
        }
        // 264dp of bars and the 96dp floor fit exactly.
        shown("idle", "info", "control", "failure", "keys")
        assertEquals(96.dp, height("viewport"))
        // Each step down sheds the next bar in order while the floor holds: a disabled key strip
        // first, though it is below the others.
        compose.runOnIdle { height = 356.dp }
        shown("info", "control", "failure", "keys")
        assertEquals(108.dp, height("viewport"))
        compose.runOnIdle { height = 340.dp }
        shown("control", "failure", "keys")
        assertEquals(116.dp, height("viewport"))
        compose.runOnIdle { height = 310.dp }
        shown("failure", "keys")
        assertEquals(118.dp, height("viewport"))
        compose.runOnIdle { height = 280.dp }
        shown("keys")
        assertEquals(128.dp, height("viewport"))
        compose.runOnIdle { height = 240.dp }
        shown()
        assertEquals(136.dp, height("viewport"))
        // Nothing left to shed: only now does the floor shrink, the header and input bar stay whole.
        compose.runOnIdle { height = 180.dp }
        assertEquals(76.dp, height("viewport"))
        assertEquals(48.dp, height("header"))
        assertEquals(56.dp, height("input"))
        compose.runOnIdle { height = 90.dp }
        assertEquals(0.dp, height("viewport"))
        assertEquals(56.dp, height("input"))
        // Room again: everything comes back.
        compose.runOnIdle { height = 360.dp }
        shown("idle", "info", "control", "failure", "keys")
    }

    /**
     * A compact pane's column as the browser's without a tab row: the header, the control bar with
     * "Take control", the address bar, the viewport, the key strip and the text bar, 312dp high (a
     * 360dp-short phone in landscape).
     */
    @androidx.compose.runtime.Composable
    private fun BrowserLikeColumn(keysEnabled: Boolean, onTake: () -> Unit) {
        Box(Modifier.fillMaxWidth().height(312.dp)) {
            RemoteViewPane("Browser", "browser", onClose = {}) {
                RemoteControlBar(RemoteControlState.VIEWING, "browser", Modifier.remoteViewShed(RemoteViewShed.CONTROL), onTake = onTake)
                RemoteInputBar("", {}, onSend = {}, tag = "browserAddress")
                RemoteViewport("browserViewport", Modifier.weight(1f).fillMaxWidth())
                RemoteKeyStrip(listOf(RemoteKey("Escape", "Esc"), RemoteKey("Tab", "Tab")), onKey = {}, tag = "browser", enabled = keysEnabled, sheds = true)
                RemoteInputBar("", {}, onSend = {}, tag = "browserText")
            }
        }
    }

    // Native graphics: the legacy text metrics ignore maxLines (see above).
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aDisabledKeyStripGivesWayBeforeTheControlBarThatOffersTakeControl() {
        var taken = 0
        compose.setContent { MaterialTheme { BrowserLikeColumn(keysEnabled = false, onTake = { taken++ }) } }
        // Header 48 + control 48 + address 56 + keys 48 + text 56 = 256, + the 96dp floor = 352 > 312.
        // The disabled strip goes first: 208 + 96 = 304 fits, so the control bar stays.
        compose.onNodeWithTag("browserKeys").assertIsNotDisplayed()
        compose.onNodeWithTag("browserControlBar").assertIsDisplayed()
        compose.onNodeWithTag("browserAcquire").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, taken) }
        compose.onNodeWithTag("browserViewport").assertHeightIsAtLeast(REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun anEnabledKeyStripStaysAndTheControlBarGivesWayFirstAsTheExistingRuleSays() {
        compose.setContent { MaterialTheme { BrowserLikeColumn(keysEnabled = true, onTake = {}) } }
        // The same 352dp in 312: the control bar goes (CONTROL before KEYS) and 304 fits.
        compose.onNodeWithTag("browserControlBar").assertIsNotDisplayed()
        compose.onNodeWithTag("browserKeys").assertIsDisplayed()
        compose.onNodeWithTag("browserKey_Escape").assertIsEnabled()
        compose.onNodeWithTag("browserViewport").assertHeightIsAtLeast(REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT)
    }

    @Test fun aCompactTerminalShedsItsNoticeForTheFloorAndKeepsTheKeyStrip() {
        // A Pixel 2 pane of 280dp under the 24dp status bar: header, a notice with a Retry button,
        // the key strip and the input bar in 256dp.
        var height by mutableStateOf(400.dp)
        val retry = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(height)) {
                    RemoteViewColumn(Modifier.fillMaxSize(), viewportMinHeight = REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("header"))
                        Box(Modifier.fillMaxWidth().height(48.dp).remoteViewShed(RemoteViewShed.FAILURE).testTag("notice")) {
                            Box(Modifier.size(48.dp).focusRequester(retry).focusable().testTag("retry"))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("viewport"))
                        Box(Modifier.fillMaxWidth().height(48.dp).remoteViewShed(RemoteViewShed.KEYS).testTag("keys"))
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("input"))
                    }
                }
            }
        }
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        compose.onNodeWithTag("notice").assertIsDisplayed().assert(!hidden)
        compose.runOnIdle { retry.requestFocus() }
        compose.onNodeWithTag("retry").assertIsFocused()
        compose.runOnIdle { height = 256.dp }
        // 200dp of bars leave 56: the notice goes, so the floor holds (256 - 152 = 104).
        compose.onNodeWithTag("notice").assertIsNotDisplayed().assert(hidden)
        compose.onNodeWithTag("retry").assertIsNotFocused()
        compose.onNodeWithTag("keys").assertIsDisplayed()
        compose.onNodeWithTag("viewport").assertHeightIsEqualTo(104.dp)
        compose.onNodeWithTag("input").assertHeightIsEqualTo(56.dp)
        compose.runOnIdle { height = 400.dp }
        compose.onNodeWithTag("notice").assertIsDisplayed().assert(!hidden)
    }

    // Native graphics: real text metrics for the title and subtitle.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aCompactHeaderIsOne48dpRowWithALongSubtitleAndTwoActions() {
        var width by mutableStateOf(411.dp)
        var compact by mutableStateOf(true)
        val subtitle = "Ctrl+Alt+Shift · a long subtitle that cannot fit beside the title on a phone"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width).testTag("frame")) {
                    PaneHeader(
                        title = "A rather long page title", subtitle = subtitle, nested = false, busy = false, tag = "pane",
                        backLabel = "Back", closeLabel = "Close", reloadLabel = "Reload", onBack = {}, onClose = {}, onReload = {},
                        actions = listOf(
                            PaneAction("Shortcuts", Icons.Default.Keyboard, "paneShortcuts"),
                            PaneAction("Tabs", Icons.Default.Keyboard, "paneTabs"),
                        ),
                        compact = compact,
                    )
                }
            }
        }
        // 411dp: both actions fit beside the title.
        compose.onNodeWithTag("frame").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("paneShortcuts").assertIsDisplayed()
        compose.onNodeWithTag("paneTabs").assertIsDisplayed()
        compose.onNodeWithTag("paneSubtitle").assertTextEquals(subtitle)
        compose.onNodeWithTag("paneTitle").assertIsDisplayed()
        // 320dp: they go into the "more" menu instead of taking a second row.
        compose.runOnIdle { width = 320.dp }
        compose.onNodeWithTag("frame").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("paneMoreActions").assertIsDisplayed()
        compose.onNodeWithTag("paneShortcuts").assertDoesNotExist()
        compose.onNodeWithTag("paneSubtitle").assertTextEquals(subtitle)
        // A pane that is not compact keeps its padding and its subtitle under the title.
        compose.runOnIdle { compact = false; width = 411.dp }
        val title = compose.onNodeWithTag("paneTitle").getUnclippedBoundsInRoot()
        val sub = compose.onNodeWithTag("paneSubtitle").getUnclippedBoundsInRoot()
        assertTrue("title=$title subtitle=$sub", sub.top >= title.bottom - 0.5.dp)
        compose.onNodeWithTag("frame").assertHeightIsAtLeast(64.dp)
    }

    // ---- Window insets: a pane pads by the part of them over its own rectangle ------------------

    /**
     * The window of the Pixel 2 API 36 emulator the androidTest probe measured, in dp: 411.4 by
     * 731.4, status bar 24 at the top, navigation bar 48 at the bottom, no cutout.
     */
    private val windowWidth = 411.4f
    private val windowHeight = 731.4f
    private val bars = RemoteViewEdges(left = 0f, top = 24f, right = 0f, bottom = 48f)

    private fun overlap(pane: androidx.compose.ui.geometry.Rect, insets: RemoteViewEdges = bars) =
        remoteViewInsetsOverlap(pane, windowWidth, windowHeight, insets)

    private fun assertEdges(expected: RemoteViewEdges, actual: RemoteViewEdges) {
        val message = "expected $expected, was $actual"
        assertEquals(message, expected.left, actual.left, 0.01f)
        assertEquals(message, expected.top, actual.top, 0.01f)
        assertEquals(message, expected.right, actual.right, 0.01f)
        assertEquals(message, expected.bottom, actual.bottom, 0.01f)
    }

    @Test fun aPaneAtTheTopOfTheWindowLosesTheStatusBarButNotTheNavigationBarItEndsAbove() {
        // The probe's H = 320 pane: 24dp under the status bar, its bottom 363dp above the navigation bar.
        assertEdges(RemoteViewEdges(0f, 24f, 0f, 0f), overlap(androidx.compose.ui.geometry.Rect(0f, 0f, windowWidth, 320f)))
    }

    @Test fun theKeyboardOverTheNavigationBarDoesNotPadAPaneThatEndsAboveIt() {
        // The probe's H = 280 pane with the IME up: safe drawing is the union of the system bars and
        // the IME, at the bottom the larger of the navigation bar's 48 and the IME's 48.
        val withIme = bars.copy(bottom = maxOf(48f, 48f))
        assertEdges(RemoteViewEdges(0f, 24f, 0f, 0f), overlap(androidx.compose.ui.geometry.Rect(0f, 0f, windowWidth, 280f), withIme))
    }

    @Test fun aPaneOverTheWholeWindowKeepsClearOfBothBars() {
        assertEdges(RemoteViewEdges(0f, 24f, 0f, 48f), overlap(androidx.compose.ui.geometry.Rect(0f, 0f, windowWidth, windowHeight)))
    }

    @Test fun aPaneThatReachesIntoTheNavigationBarPadsByTheOverlapOnly() {
        // 700 - (731.4 - 48) = 16.6dp of the pane lie over the navigation bar.
        assertEdges(RemoteViewEdges(0f, 0f, 0f, 16.6f), overlap(androidx.compose.ui.geometry.Rect(0f, 400f, windowWidth, 700f)))
    }

    @Test fun aPaneBeyondTheWindowEdgePadsByNoMoreThanTheInset() {
        // A page sliding in by offset, 40dp of it below the window's bottom edge.
        val beyond = androidx.compose.ui.geometry.Rect(0f, 400f, windowWidth, windowHeight + 40f)
        assertEdges(RemoteViewEdges(0f, 0f, 0f, 48f), overlap(beyond))
        // With no inset there it pads nothing.
        assertEdges(RemoteViewEdges(0f, 0f, 0f, 0f), overlap(beyond, bars.copy(bottom = 0f)))
    }

    @Test fun aPaneBesideTheChatPadsOnlyTheEdgeAtTheCutout() {
        val cutout = bars.copy(right = 30f)
        assertEdges(RemoteViewEdges(0f, 24f, 30f, 48f), overlap(androidx.compose.ui.geometry.Rect(200f, 0f, windowWidth, windowHeight), cutout))
        // The chat's column on the other side of the window would take a left cutout; this pane does not.
        assertEdges(RemoteViewEdges(0f, 24f, 0f, 48f), overlap(androidx.compose.ui.geometry.Rect(200f, 0f, windowWidth, windowHeight), bars.copy(left = 30f)))
    }

    @Test fun aPaneInsideTheSafeAreaKeepsAllOfItsHeight() {
        assertEdges(RemoteViewEdges(0f, 0f, 0f, 0f), overlap(androidx.compose.ui.geometry.Rect(16f, 100f, 395f, 600f), bars.copy(left = 10f, right = 10f)))
    }

    @Test fun anOverlapIsNeverMoreThanThePane() {
        // A pane 30dp high lying wholly in the 48dp navigation bar pads by its own 30dp.
        assertEdges(RemoteViewEdges(0f, 0f, 0f, 30f), overlap(androidx.compose.ui.geometry.Rect(0f, windowHeight - 30f, windowWidth, windowHeight)))
    }

    /** Sends the probe's bars, in this window's pixels, to [activity]'s window. */
    private fun dispatchBars(activity: android.app.Activity, statusTop: Int, navigationBottom: Int) {
        compose.runOnUiThread {
            val window = activity.window
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                window.decorView,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, statusTop, 0, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, navigationBottom))
                    .build(),
            )
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h731dp")
    fun thePaneModifierPadsByTheBarsOverThePaneAndNotByTheOthers() {
        var full by mutableStateOf(false)
        var activity: android.app.Activity? = null
        compose.setContent {
            activity = androidx.activity.compose.LocalActivity.current
            MaterialTheme {
                Box(if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(320.dp)) {
                    RemoteViewPane("Terminal", "terminal", onClose = {}) {
                        RemoteViewport("terminalViewport", Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
        val status = with(compose.density) { 24.dp.roundToPx() }
        val navigation = with(compose.density) { 48.dp.roundToPx() }
        dispatchBars(activity!!, status, navigation)
        fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        // At the top of the window, 320dp high: the status bar over its top 24dp pads it, the
        // navigation bar 363dp below its bottom does not.
        var pane = bounds("terminalPane")
        assertEquals(pane.top.value + 24f, bounds("terminalHeaderPill").top.value, 0.5f)
        assertEquals(pane.bottom.value, bounds("terminalViewport").bottom.value, 0.5f)
        assertEquals(320f, pane.bottom.value - pane.top.value, 0.5f)
        // Over the whole window both bars pad it. The pane is no longer compact there, so its header
        // keeps its own padding below the status bar.
        compose.runOnIdle { full = true }
        pane = bounds("terminalPane")
        assertTrue("pane=$pane header=${bounds("terminalHeaderPill")}", bounds("terminalHeaderPill").top.value >= pane.top.value + 24f - 0.5f)
        assertEquals(pane.bottom.value - 48f, bounds("terminalViewport").bottom.value, 0.5f)
    }
}
