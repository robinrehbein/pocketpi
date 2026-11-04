package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentMorphMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun label(id: Int) = compose.activity.getString(id)
    private fun open() {
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).performClick()
        compose.waitForIdle()
    }

    @Test fun keyboardCannotActivateClippedRowsDuringOpening() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 320.dp)) {
                    AttachmentMorphMenu(true, { calls++ }, { calls++ })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onAllNodes(isRoot()).onLast().performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        compose.runOnIdle { assertEquals("Clipped rows must not accept keyboard activation", 0, calls) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // A disabled transition must not leave the final rows permanently disabled.
        if (compose.onAllNodesWithText(label(R.string.remote_pick_photos)).fetchSemanticsNodes().isEmpty()) open()
        compose.onNodeWithText(label(R.string.remote_pick_photos)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun everyActionRunsExactlyOnceAndClosesBeforeItCanRunAgain() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 320.dp)) {
                    AttachmentMorphMenu(
                        enabled = true,
                        onPickPhotos = { calls += "photos" },
                        onPickFiles = { calls += "files" },
                        onTakePhoto = { calls += "camera" },
                        onPaste = { calls += "paste" },
                    )
                }
            }
        }
        val actions = listOf(
            label(R.string.remote_pick_photos) to "photos",
            label(R.string.remote_pick_files) to "files",
            label(R.string.remote_take_photo) to "camera",
            label(R.string.remote_drop_paste_image) to "paste",
        )
        actions.forEach { (text, expected) ->
            open()
            compose.onAllNodesWithText(text).assertCountEquals(1)
            if (expected == "camera") compose.onNodeWithTag("takePhoto").assertExists()
            if (expected == "paste") compose.onNodeWithTag("pasteAttachment").assertExists()
            compose.mainClock.autoAdvance = false
            // Retain the same semantics action to simulate a second tap during collapse.
            val click = compose.onNodeWithText(text).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.runOnIdle { click(); click() }
            assertEquals(expected, calls.last())
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithText(text).assertDoesNotExist()
        }
        assertEquals(listOf("photos", "files", "camera", "paste"), calls)
    }

    @Test fun optionalActionsAreAbsentAndDisablingDismissesWithoutInvokingAnything() {
        var enabled by mutableStateOf(true)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 320.dp)) {
                    AttachmentMorphMenu(enabled, { calls++ }, { calls++ })
                }
            }
        }
        open()
        compose.onNodeWithTag("takePhoto").assertDoesNotExist()
        compose.onNodeWithTag("pasteAttachment").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { enabled = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // The popup is still attached during collapse: this is a real reachable click,
        // not a retained semantics action on a node removed from composition.
        compose.onNodeWithText(label(R.string.remote_pick_files)).performClick()
        assertEquals(0, calls)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_pick_files)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).assertIsNotEnabled()
    }

    @Test fun latestCallbackIsUsedRatherThanTheOneThatOpenedTheMenu() {
        var version by mutableStateOf(1)
        val calls = mutableListOf<Int>()
        compose.setContent {
            val current = version
            MaterialTheme {
                Box(Modifier.padding(top = 320.dp)) {
                    AttachmentMorphMenu(true, { calls += current }, {})
                }
            }
        }
        open()
        compose.runOnIdle { version = 2 }
        compose.onNodeWithText(label(R.string.remote_pick_photos)).performClick()
        assertEquals(listOf(2), calls)
    }

    @Test fun composerGeometryStaysFixedThroughoutOpeningAndClosing() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().padding(top = 320.dp).testTag("composerContainer")) {
                    ChatComposer(
                        state = RemoteState(connected = true, capabilities = setOf(ATTACHMENTS_CAPABILITY)),
                        onSend = {}, onStop = {}, onRemoveQuote = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val container = compose.onNodeWithTag("composerContainer").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("composerField").fetchSemanticsNode().boundsInRoot
        val anchor = compose.onNodeWithTag("attachmentMenuAnchor", useUnmergedTree = true)
        anchor.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("attachmentMenuSurface", useUnmergedTree = true)
            .assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
        val anchorBounds = anchor.fetchSemanticsNode().boundsInRoot
        assertTrue(anchorBounds.bottom <= field.top)
        val slot = compose.onNodeWithTag("attachmentMenuSlot", useUnmergedTree = true)
        val slotBounds = slot.fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("attachmentMenuSurface", useUnmergedTree = true)
        val surfaceBounds = surface.fetchSemanticsNode().boundsInRoot
        fun assertStable() {
            val currentContainer = compose.onNodeWithTag("composerContainer").fetchSemanticsNode().boundsInRoot
            val currentField = compose.onNodeWithTag("composerField").fetchSemanticsNode().boundsInRoot
            val currentSlot = slot.fetchSemanticsNode().boundsInRoot
            val currentSurface = surface.fetchSemanticsNode().boundsInRoot
            println("Attachment geometry: container=$currentContainer field=$currentField slot=$currentSlot surface=$currentSurface")
            assertEquals(container, currentContainer)
            assertEquals(field, currentField)
            assertEquals(slotBounds, currentSlot)
            assertEquals(anchorBounds, anchor.fetchSemanticsNode().boundsInRoot)
            assertEquals(surfaceBounds, currentSurface)
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertStable()
        compose.mainClock.advanceTimeBy(80)
        assertStable()
        compose.mainClock.advanceTimeBy(1000)
        assertStable()
        val panelBounds = compose.onNodeWithTag("attachmentMenuPanel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val closeBounds = compose.onNodeWithContentDescription(label(R.string.remote_attachment_menu_close))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Attachment options must expand above the painted plus", panelBounds.top < surfaceBounds.top)
        // The helper expands the 32dp paint frame symmetrically to the 48dp control frame.
        assertEquals(anchorBounds.bottom, panelBounds.bottom, 1f)
        assertEquals(surfaceBounds.center.x, closeBounds.center.x, 1f)
        assertEquals(surfaceBounds.center.y, closeBounds.center.y, 1f)
        compose.onNodeWithContentDescription(label(R.string.remote_attachment_menu_close)).performClick()
        compose.mainClock.advanceTimeBy(80)
        assertStable()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertStable()
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).assertIsEnabled()
    }

    @Test fun attachmentPanelWrapsCompactLabelsInsteadOfUsingTheComposerWidth() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().padding(top = 320.dp).testTag("attachmentViewport")) {
                    AttachmentMorphMenu(true, {}, {}, onTakePhoto = {}, onPaste = {})
                }
            }
        }
        open()
        val panel = compose.onNodeWithTag("attachmentMenuPanel", useUnmergedTree = true)
        panel.assertWidthIsAtLeast(48.dp)
        val panelWidth = panel.fetchSemanticsNode().boundsInRoot.width
        val viewportWidth = compose.onNodeWithTag("attachmentViewport").fetchSemanticsNode().boundsInRoot.width
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Compact attachment labels must not reserve the old 320dp width", panelWidth < 320f * density)
        assertTrue("Attachment menu must not fill its composer", panelWidth < viewportWidth)
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w600dp-h891dp")
    fun germanLabelsGrowWithFontScaleWhenTheWindowDoesNotClampThem() {
        // Configure the popup's actual Android resources before creating its window.
        RuntimeEnvironment.setFontScale(1.8f)
        lateinit var measurer: TextMeasurer
        compose.setContent {
            measurer = rememberTextMeasurer()
            MaterialTheme {
                Box(Modifier.fillMaxWidth().padding(top = 500.dp).testTag("attachmentViewport")) {
                    AttachmentMorphMenu(true, {}, {}, onTakePhoto = {}, onPaste = {})
                }
            }
        }
        open()
        val expandedWidth = compose.onNodeWithTag("attachmentMenuPanel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val viewportWidth = compose.onNodeWithTag("attachmentViewport").fetchSemanticsNode().boundsInRoot.width
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label(R.string.remote_drop_paste_image))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val input = results.single().layoutInput
        assertEquals(1.8f, input.density.fontScale, 0.01f)
        val baselineLabelWidth = measurer.measure(
            text = AnnotatedString(label(R.string.remote_drop_paste_image)),
            style = input.style,
            density = Density(input.density.density, 1f),
        ).size.width
        val chrome = expandedWidth - input.constraints.maxWidth
        assertTrue("Large-font panel must exceed the same label and chrome at normal scale",
            expandedWidth > baselineLabelWidth + chrome)
        assertTrue("Content-sized panel must not fill the wide window", expandedWidth < viewportWidth)
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w280dp-h891dp")
    fun germanLabelsAtLargeFontScaleFitTheNarrowWindowWithoutOverflow() {
        RuntimeEnvironment.setFontScale(1.8f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().padding(top = 500.dp).testTag("attachmentViewport")) {
                    AttachmentMorphMenu(true, {}, {}, onTakePhoto = {}, onPaste = {})
                }
            }
        }
        open()
        val panel = compose.onNodeWithTag("attachmentMenuPanel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val viewportWidth = compose.onNodeWithTag("attachmentViewport").fetchSemanticsNode().boundsInRoot.width
        println("Narrow German attachment bounds: panel=$panel viewport=$viewportWidth")
        assertTrue("The attachment panel must remain within the available window", panel.width <= viewportWidth)
        assertTrue("Panel must not extend beyond the window start", panel.left >= 0f)
        assertTrue("Panel must not extend beyond the window end", panel.right <= viewportWidth)
        compose.onNodeWithText(label(R.string.remote_pick_photos)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_pick_files)).assertIsDisplayed()
        compose.onNodeWithTag("takePhoto").assertIsDisplayed()
        compose.onNodeWithTag("pasteAttachment").assertIsDisplayed()
        listOf(
            R.string.remote_pick_photos, R.string.remote_pick_files,
            R.string.remote_take_photo, R.string.remote_drop_paste_image,
        ).forEach { id ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label(id)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            assertTrue("The localized label must expose its text layout", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals("Popup must use the configured system font scale", 1.8f, layout.layoutInput.density.fontScale, 0.01f)
                // didOverflowWidth compares paragraph allocation with rounded Text size, not glyphs.
                // Check painted lines instead; native text may allocate a wider empty paragraph.
                for (line in 0 until layout.lineCount) {
                    assertTrue("Label must fit its left edge", layout.getLineLeft(line) >= -1f)
                    assertTrue("Label must fit its right edge", layout.getLineRight(line) <= layout.size.width + 1f)
                    assertFalse("Label must not be ellipsized", layout.isLineEllipsized(line))
                }
                assertEquals("All label characters must remain visible", layout.layoutInput.text.length,
                    layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                assertTrue("Label height must not clip its lines", layout.multiParagraph.height <= layout.size.height + 1f)
            }
        }
    }

    @Test fun sessionAndCapabilityLossDisposeTheOpenMenu() {
        var state by mutableStateOf(RemoteState(
            connected = true,
            selection = RemoteSelection("host", "project", "one"),
            capabilities = setOf(ATTACHMENTS_CAPABILITY),
        ))
        var calls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 320.dp)) {
                    ChatComposer(
                        state = state, onSend = {}, onStop = {}, onRemoveQuote = {},
                        onPickFiles = { calls++ },
                    )
                }
            }
        }
        open()
        compose.runOnIdle { state = state.copy(selection = RemoteSelection("host", "project", "two")) }
        compose.onNodeWithText(label(R.string.remote_pick_files)).assertDoesNotExist()
        open()
        compose.runOnIdle { state = state.copy(capabilities = emptySet()) }
        compose.onNodeWithText(label(R.string.remote_pick_files)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.remote_add_attachment)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(capabilities = setOf(ATTACHMENTS_CAPABILITY)) }
        open()
        compose.onNodeWithText(label(R.string.remote_pick_files)).performClick()
        assertEquals(1, calls)
    }
}
