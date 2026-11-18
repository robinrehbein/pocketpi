package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        val target = ChatKeyTarget()
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalChatKeyTarget provides target) {
                MaterialTheme { RemoteViewPane("Terminal", "terminal", onClose = { closed++ }, escapeCloses = false) {} }
            }
        }
        compose.runOnIdle {
            // Esc takes the pane's turn, so it neither closes it nor reaches the chat behind it.
            target.running = true
            target.escape(1_000L); target.escape(1_100L)
            assertTrue(target.paneOpen)
            assertEquals(0, closed)
            dispatcher.onBackPressed()
            assertEquals(1, closed)
        }
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

    @Test fun aCompactPaneLeavesTheTerminalItsBudgetWithDraftModifiersAndPendingInput() {
        // The terminal's chrome in its fullest compact state: header with the modifier subtitle and
        // an action, the key strip, a four-line draft with the insert action, input on its way.
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    RemoteViewPane("Terminal", "terminal", onClose = {}, subtitle = "Ctrl+Alt+Shift",
                        actions = listOf(PaneAction("Shortcuts", Icons.Default.Keyboard, "terminalShortcuts"))) {
                        RemoteViewport("terminalViewport", Modifier.weight(1f).fillMaxWidth(), busy = true)
                        RemoteKeyStrip(listOf(RemoteKey("escape", "Esc"), RemoteKey("tab", "Tab"), RemoteKey("enter", "Enter")) + remoteNavigationKeys(RemoteNavigationIds.TERMINAL), onKey = {}, tag = "terminal")
                        RemoteInputBar("first line\nsecond line\nthird line\nfourth line", {}, onSend = {}, tag = "terminalInput") {
                            androidx.compose.material3.IconButton(onClick = {}, Modifier.width(48.dp).height(48.dp)) {}
                        }
                    }
                }
            }
        }
        // On a device the header is 48dp (its pills, no padding), the key strip 48 and the input bar
        // 56: 152dp, plus 24dp of status bar over a pane at the top of the window, leaves 104dp of
        // 280. Robolectric's legacy text metrics make text rows taller, so this checks the rules
        // that budget rests on: no padding around the header, the viewport floor kept while the
        // bars yield, and the controls the terminal needs still shown.
        fun height(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val pane = compose.onNodeWithTag("terminalPane").getUnclippedBoundsInRoot()
        assertEquals(pane.top, compose.onNodeWithTag("terminalHeaderPill").getUnclippedBoundsInRoot().top)
        assertTrue("viewport=${height("terminalViewport")}", height("terminalViewport") >= REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT)
        compose.onNodeWithTag("terminalSubtitle").assertTextEquals("Ctrl+Alt+Shift")
        compose.onNodeWithTag("terminalInputSend").assertIsDisplayed()
        compose.onNodeWithTag("terminalKey_escape").assertIsDisplayed()
    }

    @Test fun aCompactPaneKeepsTheViewportFloorAndTheBarsYieldFirst() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    RemoteViewPane("Browser", "browser", onClose = {}) {
                        RemoteViewport("browserViewport", Modifier.weight(1f).fillMaxWidth())
                        Box(Modifier.fillMaxWidth().height(300.dp).testTag("tallBar"))
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("browserViewport").getUnclippedBoundsInRoot()
        assertEquals(REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT, viewport.bottom - viewport.top)
    }
}
