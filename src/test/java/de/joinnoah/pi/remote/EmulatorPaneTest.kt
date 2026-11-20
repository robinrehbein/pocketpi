package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.coroutines.EmptyCoroutineContext
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmulatorPaneTest {
    @get:Rule val compose = createComposeRule()
    private fun png(width: Int = 20, height: Int = 30): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }
    private fun actions(inputs: MutableList<EmulatorInput> = mutableListOf(), visible: MutableList<Boolean> = mutableListOf(), retries: MutableList<Unit> = mutableListOf()) =
        EmulatorPaneActions({ true }, { true }, { true }, { true }, { inputs.add(it); true }, { true }, { visible.add(it) }, retry = { retries.add(Unit); true })
    // The decode runs on Dispatchers.Default, which the compose test does not wait for.
    private fun settle(condition: () -> Boolean) {
        repeat(250) { compose.waitForIdle(); if (condition()) return; Thread.sleep(20) }
        throw AssertionError("Condition not met")
    }
    private fun controlled(image: EmulatorImage) = EmulatorView("s", opening = false, emulatorId = "e", ownsControl = true, image = image)

    @Test fun withControlsTheScreenSendsRawTouchAndTheTextBarRefusesNonAscii() {
        val inputs = mutableListOf<EmulatorInput>(); val touches = mutableListOf<EmulatorInput.Touch>(); val rotations = mutableListOf<Long>()
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        val view = controlled(image).copy(controls = true, geometry = EmulatorGeometry(20, 30, 0, 1))
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { inputs.add(it); true }, { true }, {}, touch = { touches.add(it); true }, rotate = { rotations.add(it); true })
        compose.setContent { MaterialTheme { EmulatorPane(view, actions, {}, decodeContext = EmptyCoroutineContext) } }
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); moveBy(Offset(5f, 5f)); up() }
        compose.runOnIdle {
            assertTrue(inputs.none { it is EmulatorInput.Tap || it is EmulatorInput.Swipe })
            assertTrue(touches.size >= 2)
            assertTrue(touches.first().touches.single().pressure > 0)
            assertEquals(0, touches.last().touches.single().pressure)
        }
        compose.onNodeWithTag("emulatorText").performTextInput("Grüße")
        compose.onNodeWithTag("emulatorTextHint", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("emulatorTextSend").assertIsNotEnabled()
        compose.onNodeWithTag("emulatorText").performTextReplacement("hello")
        compose.onNodeWithTag("emulatorTextSend").performClick()
        compose.runOnIdle { assertEquals(EmulatorInput.Text("hello"), inputs.last()) }
        // The strip holds the hardware keys too (sending them is EmulatorControlsTest's).
        compose.onAllNodesWithTag("emulatorKey_Power").assertCountEquals(1)
        compose.onAllNodesWithTag("emulatorKey_VolumeUp").assertCountEquals(1)
    }
    @Test fun withTheKeyboardTheTextBarSendsNfcTextAndTheFocusedScreenTypes() {
        val inputs = mutableListOf<EmulatorInput>(); val typed = mutableListOf<Pair<EmulatorInput, Boolean>>()
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        var view by mutableStateOf(controlled(image).copy(controls = true, keyboard = true, geometry = EmulatorGeometry(20, 30, 0, 1)))
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { inputs.add(it); true }, { true }, {}, touch = { true }, type = { input, repeat -> typed.add(input to repeat); true })
        compose.setContent { MaterialTheme { EmulatorPane(view, actions, {}, decodeContext = EmptyCoroutineContext) } }
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emulatorText").performTextInput("x".repeat(257))
        compose.onNodeWithText("At most 256 characters can be sent to the emulator at once.").assertExists()
        compose.onNodeWithTag("emulatorTextSend").assertIsNotEnabled()
        compose.onNodeWithTag("emulatorText").performTextReplacement("Grüße")
        compose.onNodeWithTag("emulatorTextSend").performClick()
        compose.runOnIdle { assertEquals(EmulatorInput.Text("Grüße"), inputs.last()) }
        // Touching the screen gives it focus; a hardware key then goes out as a keystroke.
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); up() }
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
        // A gap between frames (a rotation, a new watch) takes the image away but not the focus.
        view = view.copy(image = null)
        compose.onAllNodesWithTag("emulatorScreen").assertCountEquals(0)
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
        view = view.copy(image = image.copy(frameId = 2))
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
        compose.onNodeWithTag("emulatorViewport").performKeyInput { withKeyDown(androidx.compose.ui.input.key.Key.CtrlLeft) { pressKey(androidx.compose.ui.input.key.Key.A) } }
        compose.runOnIdle { assertEquals(EmulatorInput.Key("KeyA", listOf("Control")) to false, typed.last()) }
    }
    @Test fun onlyThisUsersOwnTakeMovesTheFocusToTheScreen() {
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        val acquired = AtomicInteger()
        var view by mutableStateOf(EmulatorView("s", opening = false, emulatorId = "e", image = image, geometry = EmulatorGeometry(20, 30, 0, 1)))
        val actions = EmulatorPaneActions({ true }, { acquired.incrementAndGet(); true }, { true }, { true }, { true }, { true }, {})
        compose.setContent {
            // A hardware keyboard attached and open.
            val keyboard = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                this.keyboard = android.content.res.Configuration.KEYBOARD_QWERTY
                hardKeyboardHidden = android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides keyboard) {
                MaterialTheme { EmulatorPane(view, actions, {}, decodeContext = EmptyCoroutineContext) }
            }
        }
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        // An automatic re-acquire (a reconnect, showing the pane again) leaves the focus alone.
        view = view.copy(ownsControl = true)
        compose.waitForIdle()
        compose.onNodeWithTag("emulatorViewport").assertIsNotFocused()
        view = view.copy(ownsControl = false)
        compose.onNodeWithTag("emulatorAcquire").performClick()
        compose.runOnIdle { assertEquals(1, acquired.get()) }
        view = view.copy(ownsControl = true)
        compose.waitForIdle()
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
    }
    @Test fun inAFrameGapTheFocusedScreenSwallowsNamedKeysAndSendsNothing() {
        val typed = mutableListOf<EmulatorInput>(); val leaked = mutableListOf<androidx.compose.ui.input.key.Key>()
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        var view by mutableStateOf(controlled(image).copy(controls = true, keyboard = true, geometry = EmulatorGeometry(20, 30, 0, 1)))
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { true }, { true }, {}, touch = { true }, type = { input, _ -> typed.add(input); true })
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onKeyEvent { leaked.add(it.key); false }) {
            EmulatorPane(view, actions, {}, decodeContext = EmptyCoroutineContext) } } }
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); up() }
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
        // A rotation or a new watch: control is still held but no frame shows.
        view = view.copy(image = null)
        compose.waitForIdle()
        compose.onNodeWithTag("emulatorViewport").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape); pressKey(androidx.compose.ui.input.key.Key.Tab) }
        compose.runOnIdle { assertTrue(typed.isEmpty()); assertTrue(leaked.toString(), leaked.isEmpty()) }
        compose.onNodeWithTag("emulatorViewport").assertIsFocused()
    }
    @Test fun withoutControlsThereIsNoTextBarOrExtraKey() {
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        compose.setContent { MaterialTheme { EmulatorPane(controlled(image), actions(), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onAllNodesWithTag("emulatorText").assertCountEquals(0)
        compose.onAllNodesWithTag("emulatorKey_Power").assertCountEquals(0)
        compose.onAllNodesWithTag("emulatorRotate").assertCountEquals(0)
    }
    @Test fun profileChoiceRequiresExplicitSelection() {
        val selected = mutableListOf<String>()
        val actions = EmulatorPaneActions({ selected.add(it); true }, { true }, { true }, { true }, { true }, { true }, {})
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, profiles = listOf(EmulatorProfileInfo("phone", "Test phone"))), actions, {}) } }
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("Test phone").performClick()
        compose.runOnIdle { assertEquals(listOf("phone"), selected) }
    }
    @Test fun canceledPointerDoesNotSendInput() {
        val inputs = mutableListOf<EmulatorInput>()
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        compose.setContent { MaterialTheme { EmulatorPane(controlled(image), actions(inputs), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(emptyList<EmulatorInput>(), inputs) }
    }
    @Test fun profilePickerDisplaysLoadAndOpenFailures() {
        var view by mutableStateOf(EmulatorView("s", opening = false, notice = EmulatorNotice.FAILED))
        val retries = mutableListOf<Unit>()
        compose.setContent { MaterialTheme { EmulatorPane(view, actions(retries = retries), {}) } }
        compose.onNodeWithText("The request failed.").assertIsDisplayed()
        compose.runOnIdle { view = view.copy(profiles = listOf(EmulatorProfileInfo("phone", "Test phone")), profileId = "phone") }
        compose.onNodeWithText("The request failed.").assertIsDisplayed()
        compose.onNodeWithTag("emulatorProfile_phone").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries.size) }
    }
    @Test fun newFramesDoNotCancelAnActiveSwipe() {
        val inputs = mutableListOf<EmulatorInput>()
        var view by mutableStateOf(controlled(EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)))
        compose.setContent { MaterialTheme { EmulatorPane(view, actions(inputs), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); moveBy(Offset(0f, 30f)) }
        compose.runOnIdle { view = view.copy(image = requireNotNull(view.image).copy(frameId = 2)) }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, inputs.size); assertTrue(inputs.single() is EmulatorInput.Swipe) }
    }
    @Test fun aHeldFingerSendsOneLongPressSwipeThatDoesNotMove() {
        val inputs = mutableListOf<EmulatorInput>()
        compose.setContent { MaterialTheme { EmulatorPane(controlled(EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)), actions(inputs), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); advanceEventTime(1_500); up() }
        compose.runOnIdle {
            val swipe = inputs.single() as EmulatorInput.Swipe
            assertEquals(swipe.x to swipe.y, swipe.endX to swipe.endY)
            assertEquals(EMULATOR_LONG_PRESS_MS, swipe.durationMs)
        }
    }
    @Test fun aShortTouchIsATap() {
        val inputs = mutableListOf<EmulatorInput>()
        compose.setContent { MaterialTheme { EmulatorPane(controlled(EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)), actions(inputs), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); advanceEventTime(50); up() }
        compose.runOnIdle { assertTrue(inputs.single() is EmulatorInput.Tap) }
    }
    @Test fun eachFrameIsDecodedOnceOffTheMainThreadAndAnInputInFlightShows() {
        val calls = AtomicInteger(); val onMain = AtomicBoolean(false)
        val decode: suspend (EmulatorImage) -> Bitmap? = {
            calls.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) onMain.set(true)
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        }
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        var view by mutableStateOf(controlled(image))
        compose.setContent { MaterialTheme { EmulatorPane(view, actions(), {}, decode) } }
        settle { compose.onAllNodesWithTag("emulatorScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("emulatorViewportBusy").assertCountEquals(0)
        // Recomposing for anything but a new frame decodes nothing.
        compose.runOnIdle { view = view.copy(inputPending = true) }
        compose.onNodeWithTag("emulatorViewportBusy").assertContentDescriptionEquals("Sending input…")
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.runOnIdle { view = view.copy(image = image.copy(frameId = 2)) }
        settle { calls.get() == 2 }
        assertEquals(2, calls.get())
        assertFalse(onMain.get())
    }
    @Test fun anUndecodableFrameShowsItsPlaceholder() {
        val image = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        compose.setContent { MaterialTheme { EmulatorPane(controlled(image), actions(), {}, { null }, EmptyCoroutineContext) } }
        compose.onNodeWithText("The picture could not be shown.").assertIsDisplayed()
        compose.onAllNodesWithTag("emulatorScreen").assertCountEquals(0)
    }
    @Test fun aFrameThatFailsAfterAGoodOneDoesNotLeaveTheOldFrameShowing() {
        val first = EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)
        var view by mutableStateOf(controlled(first))
        compose.setContent { MaterialTheme { EmulatorPane(view, actions(), {}, { if (it.frameId == 1L) Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888) else null }, EmptyCoroutineContext) } }
        compose.onNodeWithTag("emulatorScreen").assertExists()
        compose.onNodeWithTag("emulatorKey_Home").assertIsEnabled()
        compose.runOnIdle { view = view.copy(image = first.copy(frameId = 2)) }
        compose.onNodeWithText("The picture could not be shown.").assertIsDisplayed()
        compose.onAllNodesWithTag("emulatorScreen").assertCountEquals(0)
        compose.onNodeWithTag("emulatorKey_Home").assertIsNotEnabled()
    }
    @Test fun aStoppedStreamShowsAFailedPlaceholderWithoutASpinner() {
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e", notice = EmulatorNotice.STREAM_FAILED), actions(), {}) } }
        compose.onNodeWithText("The picture stopped updating.").assertIsDisplayed()
        compose.onNodeWithText("The picture could not be shown.").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the first frame…").assertDoesNotExist()
    }
    @Test fun aRefusedRequestBeforeTheFirstFrameKeepsWaitingForIt() {
        // A refused acquire or refresh leaves the watch alive: a frame follows.
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e", notice = EmulatorNotice.FAILED), actions(), {}) } }
        compose.onNodeWithText("The request failed.").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed()
        compose.onNodeWithText("The picture could not be shown.").assertDoesNotExist()
    }
    @Test fun theControlBarSitsDirectlyUnderTheNoticesAboveTheScreen() {
        compose.setContent { MaterialTheme { EmulatorPane(controlled(EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)).copy(notice = EmulatorNotice.FAILED), actions(), {}, decodeContext = EmptyCoroutineContext) } }
        val notice = compose.onNodeWithTag("emulatorNotice").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("emulatorControlBar").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("emulatorViewport").getUnclippedBoundsInRoot()
        assertTrue(bar.top >= notice.bottom && viewport.top >= bar.bottom)
        // The header updates who has control; it has no reload, since nothing reloads.
        compose.onNodeWithTag("emulatorReload").assertDoesNotExist()
    }
    @Test fun noProfilesShowTheEmptyNoticeUnderAHeading() {
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false), actions(), {}) } }
        compose.onNodeWithText("Choose a profile").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
        compose.onNodeWithTag("emulatorProfilesEmpty").assertTextEquals("Nothing to show yet.")
    }
    @Test fun keysAreLabelledAndSendTheHostKeyNames() {
        val inputs = mutableListOf<EmulatorInput>()
        compose.setContent { MaterialTheme { EmulatorPane(controlled(EmulatorImage(png(), 20, 30, 20, 30, 0, 1, 1)), actions(inputs), {}, decodeContext = EmptyCoroutineContext) } }
        compose.onNodeWithContentDescription("Up arrow").performScrollTo().performClick()
        compose.onNodeWithTag("emulatorKey_Home").assertContentDescriptionEquals("Home screen")
        compose.runOnIdle { assertEquals(listOf<EmulatorInput>(EmulatorInput.Key("ArrowUp")), inputs) }
    }
    @Test fun anUncertainInputOffersReopen() {
        val retries = mutableListOf<Unit>()
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e", notice = EmulatorNotice.UNCERTAIN), actions(retries = retries), {}) } }
        compose.onNodeWithText("Reopen").performClick()
        compose.runOnIdle { assertEquals(1, retries.size) }
    }
    @Test fun theEmulatorTabStaysComposedWhileHiddenAndClosingDisposesIt() {
        var tabs by mutableStateOf(InspectorTabs().open(InspectorTab.EMULATOR))
        val visible = mutableListOf<Boolean>(); var closed = 0; var disposed = 0
        val actions = actions(visible = visible)
        compose.setContent {
            MaterialTheme {
                InspectorHost(tabs, content = { tab ->
                    if (tab == InspectorTab.EMULATOR) ({
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                        EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e"), actions, { closed++ })
                    }) else ({})
                }, onSelect = { tabs = tabs.open(it) }, onClose = { tabs = tabs.close(it) }, expanded = false, onExpand = {})
            }
        }
        compose.onNodeWithTag("inspectorTab_emulator").assertExists()
        compose.runOnIdle { assertEquals(listOf(true), visible); tabs = tabs.open(InspectorTab.FILES) }
        // Hidden: the stream stops, the pane (and with it the session and lease) stays.
        compose.runOnIdle { assertEquals(listOf(true, false), visible); assertEquals(0, disposed) }
        compose.onNodeWithTag("inspectorTab_emulator").performClick()
        compose.runOnIdle { assertEquals(listOf(true, false, true), visible) }
        compose.onNodeWithTag("emulatorClose").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
        compose.onNodeWithTag("inspectorTabClose_emulator").performClick()
        compose.runOnIdle { assertEquals(1, disposed); assertEquals(false, visible.last()) }
    }
    @Test fun theAppInTheBackgroundHidesThePane() {
        val owner = object : LifecycleOwner { val registry = LifecycleRegistry.createUnsafe(this); override val lifecycle: Lifecycle get() = registry }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val visible = mutableListOf<Boolean>()
        val actions = actions(visible = visible)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e"), actions, {}) }
            }
        }
        compose.runOnIdle { assertEquals(listOf(true), visible); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(listOf(true, false), visible); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(listOf(true, false, true), visible) }
    }
}
