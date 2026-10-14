package de.joinnoah.pi.remote

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onPlaced
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
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
class CodeGutterUiTest {
    @get:Rule val compose = createComposeRule()
    private val densities = listOf(1f, 1.33125f, 1.5f, 2.125f, 2.625f, 2.75f, 3.0625f, 3.375f, 3.5f)
    private val scales = listOf(0.85f, 1f, 1.3f, 2f)

    private fun assertNumberFits(number: Int) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(number.toString(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("Gutter $number clips", layout.hasVisualOverflow)
        assertTrue("Gutter $number has less width than its intrinsic text",
            layout.size.width >= ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt())
    }

    @Test fun bothDiffGuttersFitCompleteNumbersAtFractionalDensitiesAndLargeFonts() {
        val density = mutableStateOf(Density(1f))
        val number = mutableStateOf(19)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        DiffView(listOf(DiffLine(DiffKind.CONTEXT, "code", number.value, number.value + 1)))
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) for (value in listOf(19, 999, 9999)) {
            compose.runOnIdle { density.value = Density(dpi, scale); number.value = value }
            assertNumberFits(value)
            assertNumberFits(value + 1)
        }
    }

    @Test fun toolOutputGutterFitsAcrossDensityAndFontSettings() {
        val density = mutableStateOf(Density(1f))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        ToolDetailScreen(
                            item = ConversationItem.Activity("tool", "message", "read", null,
                                List(20) { "output row" }.joinToString("\n"), "done", false),
                            download = null, canLoadFullOutput = false, canAskToFix = false,
                            onLoadFullOutput = {}, onCancelFullOutput = {}, onAskToFix = {},
                            onQuote = {}, onClose = {},
                        )
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) {
            compose.runOnIdle { density.value = Density(dpi, scale) }
            compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasText("19"))
            assertNumberFits(19)
        }
    }

    @Test fun fileGutterFitsFourDigitsAndKeepsItsTouchWidth() {
        val density = mutableStateOf(Density(1f))
        val state = FilesState("session", loading = false,
            file = OpenFile("source.txt", loading = false, content = List(1000) { "code" }.joinToString("\n")))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        FilesPane(state, FilesActions(onClose = {}, onOpenDir = {}, onOpenFile = {},
                            onLoadMore = {}, onReload = {}, onSelectLines = {}, onSend = { true },
                            onRequestPreview = {}, onShowPeek = { _, _ -> }, onDismissPeek = {}))
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) {
            compose.runOnIdle { density.value = Density(dpi, scale) }
            compose.onNodeWithTag("filesFile").performScrollToNode(hasText("1000"))
            assertNumberFits(1000)
            compose.onNodeWithTag("filesLine:1000").assertWidthIsAtLeast(48.dp)
        }
    }

    /** Ten rows of 20px; a row's gutter is its number cell, and the log keeps what the gestures said. */
    @Composable
    private fun GutterHarness(log: MutableList<String>) {
        val state = rememberLazyListState()
        val rows = remember(state) { GutterRows(state) }
        LazyColumn(Modifier.size(300.dp, 200.dp).testTag("gutterList").onPlaced { rows.coordinates = it }, state = state) {
            items((1..10).toList()) { line ->
                Row(Modifier.height(20.dp).fillMaxWidth()) {
                    Text(
                        "$line",
                        Modifier.size(width = 40.dp, height = 20.dp)
                            .testTag("gutter:$line")
                            .codeGutter(
                                line = line,
                                rows = rows,
                                tapLabel = "tap",
                                onTap = { extend -> log += "tap:$line:$extend" },
                                onStart = { start, extend -> log += "start:$start:$extend" },
                                onDrag = { end -> log += "drag:$end" },
                            ),
                    )
                    Text("code $line")
                }
            }
        }
    }

    @Test fun aTapOnTheGutterPicksThatLineWithoutShift() {
        val log = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme { GutterHarness(log) }
            }
        }
        compose.onNodeWithTag("gutter:2").performMouseInput { click() }
        compose.waitForIdle()
        assertEquals(listOf("tap:2:false"), log)
    }

    @Test fun aMouseDragOverTheGutterStartsARangeAndExtendsItAcrossRows() {
        val log = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme { GutterHarness(log) }
            }
        }
        // Row 2 is 20 to 40px down and row 4 is 60 to 80px; the drag ends in row 4.
        compose.onNodeWithTag("gutter:2").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(0f, 40f))
            release()
        }
        compose.waitForIdle()
        assertEquals("start:2:false", log.first())
        assertEquals("drag:4", log.last())
        assertFalse("a drag is not a tap", log.any { it.startsWith("tap") })
    }

    @Test fun aRightClickOnTheGutterIsNoTapAndNoDrag() {
        val log = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme { GutterHarness(log) } }
        }
        // The secondary button opens the context menu; it must not also pick the line.
        compose.onNodeWithTag("gutter:3").performMouseInput { rightClick() }
        compose.onNodeWithTag("gutter:3").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveTo(center + Offset(0f, 40f))
            release(MouseButton.Secondary)
        }
        compose.waitForIdle()
        assertTrue("a secondary press left the gutter alone: $log", log.isEmpty())
        compose.onNodeWithTag("gutter:3").performMouseInput { click() }
        compose.waitForIdle()
        assertEquals(listOf("tap:3:false"), log)
    }

    @Test fun enterAndSpaceTapAFocusedGutterNumber() {
        val log = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme { GutterHarness(log) } }
        }
        compose.onNodeWithTag("gutter:4").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("gutter:4").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("gutter:4").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Spacebar)
            keyUp(Key.ShiftLeft)
        }
        compose.waitForIdle()
        assertEquals(listOf("tap:4:false", "tap:4:true"), log)
    }

    @Test fun aDragOverTheGutterOfAScrolledListFindsTheRowsThatAreShown() {
        val log = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme { GutterHarness(log) } }
        }
        // Rows are 20px; scrolled by 100px, row 6 tops the list, and a drag of 40px from it ends in row 8.
        compose.onNodeWithTag("gutterList").performScrollToNode(hasText("code 10"))
        compose.waitForIdle()
        val first = (1..10).first { line ->
            runCatching { compose.onNodeWithTag("gutter:$line").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("gutter:$first").performMouseInput {
            moveTo(center)
            press()
            moveTo(center + Offset(0f, 40f))
            release()
        }
        compose.waitForIdle()
        assertEquals("start:$first:false", log.first())
        assertEquals("drag:${first + 2}", log.last())
    }

    @Test fun ctrlWithTheWheelZoomsTheCodeAndTheListDoesNotScroll() {
        CodeZoom.reset()
        lateinit var state: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    state = rememberLazyListState()
                    LazyColumn(Modifier.size(300.dp, 200.dp).testTag("wheelList").codeZoomGestures("wheel-test"), state = state) {
                        items((1..50).toList()) { line -> Text("code $line", Modifier.height(20.dp)) }
                    }
                }
            }
        }
        // A plain wheel scrolls the list.
        compose.onNodeWithTag("wheelList").performMouseInput { scroll(40f) }
        compose.waitForIdle()
        val scrolled = compose.runOnIdle { state.firstVisibleItemScrollOffset + state.firstVisibleItemIndex * 20 }
        assertTrue("the wheel scrolls the list", scrolled > 0)
        assertEquals(1f, CodeZoom.scale("wheel-test"), 0f)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(0) } }
        // With Ctrl it zooms, and the same event does not scroll.
        compose.onNodeWithTag("wheelList").performKeyInput { keyDown(Key.CtrlLeft) }
        compose.onNodeWithTag("wheelList").performMouseInput { scroll(40f) }
        compose.onNodeWithTag("wheelList").performKeyInput { keyUp(Key.CtrlLeft) }
        compose.waitForIdle()
        assertTrue("Ctrl+wheel down zooms out", CodeZoom.scale("wheel-test") < 1f)
        assertEquals(0, compose.runOnIdle { state.firstVisibleItemScrollOffset + state.firstVisibleItemIndex })
        CodeZoom.reset()
    }

    @Test fun aPinchZoomsThePaneAndALaterPinchKeepsTheValue() {
        CodeZoom.reset()
        compose.setContent {
            Box(Modifier.size(200.dp).testTag("zoomArea").codeZoomGestures("pinch-test"))
        }
        // Two fingers 40px apart, then 100px apart: the text grows by 2.5, the most it may.
        compose.onNodeWithTag("zoomArea").performTouchInput {
            down(0, Offset(20f, 50f))
            down(1, Offset(60f, 50f))
            move()
            moveTo(0, Offset(0f, 50f))
            moveTo(1, Offset(100f, 50f))
            move()
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(CodeZoom.MAX, CodeZoom.scale("pinch-test"), 0.01f)
        CodeZoom.reset()
    }

    @Composable
    private fun ZoomKeysHarness(pane: String) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier.size(100.dp)
                .testTag("zoomKeys")
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (resolveCodeShortcut(event)) {
                        Shortcut.ZOOM_IN -> { CodeZoom.step(pane, inward = true); true }
                        Shortcut.ZOOM_OUT -> { CodeZoom.step(pane, inward = false); true }
                        else -> false
                    }
                },
        )
    }

    @Test fun ctrlEqualsAndCtrlMinusZoomTheFocusedPaneOnly() {
        CodeZoom.reset()
        compose.setContent { MaterialTheme { ZoomKeysHarness("keys-test") } }
        compose.waitForIdle()
        compose.onNodeWithTag("zoomKeys").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Equals)
            keyUp(Key.Equals)
            keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals(CodeZoom.STEP, CodeZoom.scale("keys-test"), 0.001f)
        assertEquals(1f, CodeZoom.scale("other-pane"), 0f)
        compose.onNodeWithTag("zoomKeys").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Minus)
            keyUp(Key.Minus)
            keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        assertEquals(1f, CodeZoom.scale("keys-test"), 0.001f)
        CodeZoom.reset()
    }
}
