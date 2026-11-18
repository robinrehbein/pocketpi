package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
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
}
