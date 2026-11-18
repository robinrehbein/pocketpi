package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserPaneTest {
    @get:Rule val compose = createComposeRule()
    private fun image(): BrowserImage {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); bitmap.recycle()
        return BrowserImage(out.toByteArray(), 400, 200, 400, 200, 1.0, 0.0, 0.0, 1, 1)
    }
    @Test fun goWithNoTabOpensTheAddressInANewTab() {
        val opened = mutableListOf<String>(); var navigated = 0
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1)
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(newTab = { opened += it; true }, navigate = { navigated++; true }), {}) } }
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com/a")
        compose.onNodeWithTag("browserGo").performClick()
        assertEquals(listOf("https://example.com/a"), opened); assertEquals(0, navigated)
        compose.onAllNodesWithTag("browserGoRefused").assertCountEquals(0)
    }
    @Test fun refusedGoSaysSoAndASentOneClearsTheNotice() {
        var accept = false; val navigated = mutableListOf<String>()
        val tabs = listOf(BrowserTab("tab", "T", "https://example.com"))
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, tabs = tabs, selectedTabId = "tab")
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(navigate = { navigated += it; accept }), {}) } }
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com/b")
        compose.onNodeWithTag("browserGo").performClick()
        compose.onNodeWithTag("browserGoRefused").assertIsDisplayed()
        accept = true
        compose.onNodeWithTag("browserGo").performClick()
        compose.onAllNodesWithTag("browserGoRefused").assertCountEquals(0)
        assertEquals(listOf("https://example.com/b", "https://example.com/b"), navigated)
    }
    @Test fun refusedNewTabSaysSoKeepsTheAddressAndEditingClearsTheNotice() {
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1)
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(newTab = { false }), {}) } }
        compose.onNodeWithTag("browserAddress").performTextReplacement("example.com")
        compose.onNodeWithTag("browserGo").performClick()
        compose.onNodeWithTag("browserGoRefused").assertIsDisplayed()
        compose.onNodeWithTag("browserAddress").assertTextContains("example.com")
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com")
        compose.onAllNodesWithTag("browserGoRefused").assertCountEquals(0)
    }
    @Test fun paneCoversTheChatBeneathIt() {
        // A phone shows the pane over the chat: nothing beneath may show through or take a tap.
        var beneathClicks = 0
        val view = BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()
                        .background(Color.Red).clickable { beneathClicks++ })
                    BrowserPane(view, BrowserPaneActions(), {})
                }
            }
        }
        compose.onNodeWithTag("browserPane").performTouchInput { click(percentOffset(0.98f, 0.99f)) }
        compose.runOnIdle { assertEquals(0, beneathClicks) }
        val pixels = compose.onNodeWithTag("browserPane").captureToImage().asAndroidBitmap()
        val corner = pixels.getPixel(pixels.width - 2, pixels.height - 2)
        assertFalse("chat shows through the pane", android.graphics.Color.red(corner) > 200 && android.graphics.Color.green(corner) < 50)
    }

    @Test fun pendingDecodeWaitsAndSuccessfulDecodeDisplaysImage() {
        val pending = CompletableDeferred<Bitmap?>()
        val view = BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, image = image())
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { pending.await() }) } }
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed()
        compose.onNodeWithText("The picture could not be shown.").assertDoesNotExist()
        compose.runOnIdle { pending.complete(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun failedDecodeReportsFailureOnlyAfterCompletion() {
        val pending = CompletableDeferred<Bitmap?>()
        val view = BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, image = image())
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { pending.await() }) } }
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed()
        compose.runOnIdle { pending.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The picture could not be shown.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
    }

    @Test fun changingSessionDoesNotShowOldDecodedImageOrOldCompletion() {
        val first = CompletableDeferred<Bitmap?>()
        val second = CompletableDeferred<Bitmap?>()
        val firstImage = image()
        val secondImage = image()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, image = firstImage))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { if (it === firstImage) first.await() else second.await() }) } }
        compose.runOnIdle { view = view.copy(sessionId = "other", browserId = "new", generation = 2, image = secondImage) }
        val unused = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { first.complete(unused) }
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
        unused.recycle()
        compose.runOnIdle { second.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The picture could not be shown.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun newGenerationHidesPreviouslyDecodedImageWhileNextFrameWaits() {
        val next = CompletableDeferred<Bitmap?>()
        val firstImage = image()
        val nextImage = image()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, image = firstImage))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { if (it === firstImage) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else next.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { view = view.copy(generation = 2, image = nextImage) }
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
        compose.onNodeWithText("Waiting for the first frame…").assertIsDisplayed()
        compose.runOnIdle { next.complete(null) }
    }

    @Test fun structurallyEqualNewFrameIsDecodedByIdentity() {
        val next = CompletableDeferred<Bitmap?>()
        val firstImage = image()
        val nextImage = firstImage.copy()
        assertTrue(firstImage == nextImage && firstImage !== nextImage)
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = firstImage), referentialEqualityPolicy())
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { if (it === firstImage) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else next.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { view = view.copy(image = nextImage) }
        compose.onNodeWithContentDescription("Remote browser image").assertExists()
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { next.complete(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)) }
        compose.onNodeWithTag("browserText").performTextInput("ready")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false) }
    }

    @Test fun nextFrameKeepsPriorImageUntilDecodeFails() {
        val next = CompletableDeferred<Bitmap?>()
        val firstImage = image()
        val nextImage = firstImage.copy(frameId = 2)
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = firstImage))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { if (it === firstImage) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else next.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.runOnIdle { view = view.copy(image = nextImage) }
        compose.onNodeWithContentDescription("Remote browser image").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the first frame…").assertDoesNotExist()
        compose.onNodeWithTag("browserSendText").assertIsEnabled()
        compose.runOnIdle { next.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The picture could not be shown.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
    }

    @Test fun pendingSuccessorFramesPermitOneTextSendWithoutReplayingIt() {
        val pending = CompletableDeferred<Bitmap?>()
        val first = image()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = first))
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); view = view.copy(inputPending = true); true
        }), {}, decodeImage = { if (it === first) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else pending.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.runOnIdle { view = view.copy(image = first.copy(frameId = 2)) }
        compose.runOnIdle { view = view.copy(image = first.copy(frameId = 3)) }
        compose.onNodeWithContentDescription("Remote browser image").assertIsDisplayed()
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("browserSendText").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 1 } }
        compose.runOnIdle { view = view.copy(inputPending = false, receipt = BrowserInputReceipt(1, "accepted")) }
        compose.onNodeWithTag("browserText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(listOf(BrowserInput.Text("draft")), sent) }
    }

    @Test fun pendingSuccessorDoesNotRelaxMovingMissingFrameOrContextGates() {
        val pending = CompletableDeferred<Bitmap?>()
        val first = image()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = first))
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { sent.add(it); true }), {}, decodeImage = { if (it === first) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else pending.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.runOnIdle { view = view.copy(image = first.copy(frameId = 2), moving = true) }
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { view = view.copy(moving = false, image = null) }
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { view = view.copy(generation = 2, image = first.copy(frameId = 3)) }
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun pendingSuccessorPermitsKeyDownAndRelease() {
        val pending = CompletableDeferred<Bitmap?>()
        val first = image()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = first))
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); view = view.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")); true
        }), {}, decodeImage = { if (it === first) Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888) else pending.await() }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { view = view.copy(image = first.copy(frameId = 2)) }
        compose.onNodeWithTag("browserKey_Enter").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 2 } }
        compose.runOnIdle { assertEquals(listOf(BrowserInput.Key("down", "Enter", "Enter"), BrowserInput.Key("up", "Enter", "Enter")), sent) }
    }

    @Test fun resizePendingBlocksTextAndKeysEvenWithDecodedImage() {
        var requested = false
        val first = image()
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = first)
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(resize = { _, _, _, _ -> requested = true; true }), {}) } }
        compose.waitUntil(10_000) { compose.runOnIdle { requested } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.onNodeWithTag("browserKey_Enter").assertIsNotEnabled()
    }
    @Test fun switchingBetweenBrowserAndPreviewKeepsDraftsSeparate() {
        var preview by mutableStateOf(false)
        compose.setContent {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            MaterialTheme {
                holder.SaveableStateProvider(if (preview) "preview" else "browser") {
                    BrowserPane(BrowserView("s", opening = false, ownsControl = true, previewPort = if (preview) 3000 else null), BrowserPaneActions(), {})
                }
            }
        }
        compose.onNodeWithTag("browserText").performTextInput("browser draft")
        compose.runOnIdle { preview = true }
        compose.onNodeWithTag("browserText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("browserText").performTextInput("preview draft")
        compose.runOnIdle { preview = false }
        compose.onNodeWithTag("browserText").assertTextContains("browser draft")
        compose.runOnIdle { preview = true }
        compose.onNodeWithTag("browserText").assertTextContains("preview draft")
    }
    @Test fun readOnlyPaneRequiresExplicitAcquire() {
        var acquired = 0
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(BrowserView("s", opening = false, owner = BrowserOwner("agent", "a"), image = image()), BrowserPaneActions(acquire = { acquired++; true }, input = { sent.add(it); true }), {}) } }
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
        compose.onNodeWithTag("browserAcquire").performClick()
        assertEquals(1, acquired); assertTrue(sent.isEmpty())
    }
    @Test fun uncertainReceiptPreservesTextDraft() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { view = view.copy(inputPending = true); true }), {}) } }
        compose.onNodeWithTag("browserText").performTextInput("keep this")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.runOnIdle { view = view.copy(inputPending = false, ownsControl = false, receipt = BrowserInputReceipt(0, "uncertain"), notice = BrowserNotice.UNCERTAIN) }
        compose.onNodeWithTag("browserText").assertTextContains("keep this")
    }
    @Test fun uncertainOutcomeWithRetainedControlAsksToCheckPage() {
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image(), notice = BrowserNotice.UNCERTAIN)
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}) } }
        compose.onNodeWithText("Input outcome is uncertain. Check the page before sending again.").assertIsDisplayed()
        compose.onNodeWithText("Input outcome is uncertain. Take control again before continuing.").assertDoesNotExist()
    }
    @Test fun refusedTextSendShowsNoticeAndPreservesDraftWithoutRetry() {
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image())
        val attempted = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { attempted.add(it); false }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("keep this")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.onNodeWithTag("browserSendRefused").assertIsDisplayed()
        compose.onNodeWithTag("browserText").assertTextContains("keep this")
        compose.runOnIdle { assertEquals(listOf(BrowserInput.Text("keep this")), attempted) }
    }
    @Test fun manualRetryAfterRefusalClearsNoticeAndAcceptedDraft() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        var attempts = 0
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            attempts++
            if (attempts == 1) false else { view = view.copy(inputPending = true); true }
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.onNodeWithTag("browserSendRefused").assertIsDisplayed()
        compose.runOnIdle { view = view.copy(moving = true) }
        compose.runOnIdle { view = view.copy(moving = false) }
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithTag("browserText").assertTextContains("draft")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.onNodeWithTag("browserSendRefused").assertDoesNotExist()
        compose.runOnIdle { view = view.copy(inputPending = false, receipt = BrowserInputReceipt(0, "accepted")) }
        compose.onNodeWithTag("browserText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(2, attempts) }
    }
    @Test fun acceptedReceiptClearsOnlyTheSubmittedText() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { view = view.copy(inputPending = true); true }), {}) } }
        compose.onNodeWithTag("browserText").performTextInput("sent")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.runOnIdle { view = view.copy(inputPending = false, receipt = BrowserInputReceipt(0, "accepted")) }
        compose.onNodeWithTag("browserText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
    }
    @Test fun invalidImageNeverEnablesSendingInput() {
        val bad = BrowserImage(byteArrayOf(1, 2, 3), 4, 2, 400, 200, 1.0, 0.0, 0.0, 1, 1)
        val sent = mutableListOf<BrowserInput>()
        val view = BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = bad)
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { sent.add(it); true }), {}) } }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun pointerReleaseIsDeliveredAfterFrameIsLost() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        var receipt = 0L
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); receipt++; view = view.copy(receipt = BrowserInputReceipt(receipt, "accepted")); true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        // A mouse button: a finger sends nothing before it lifts.
        compose.onNodeWithTag("browserViewport").performMouseInput { moveTo(center); press() }
        compose.onNodeWithTag("browserViewport").assertIsFocused()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it is BrowserInput.Pointer && it.action == "down" } } }
        compose.runOnIdle { view = view.copy(image = null) }
        compose.onNodeWithTag("browserViewport").performMouseInput { release() }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it is BrowserInput.Pointer && it.action == "up" } } }
        compose.runOnIdle {
            val pointer = sent.filterIsInstance<BrowserInput.Pointer>()
            assertEquals(listOf("down", "up"), pointer.map { it.action })
            assertEquals(pointer[0].x, pointer[1].x, 0.0)
            assertEquals(pointer[0].y, pointer[1].y, 0.0)
        }
    }

    @Test fun imageLossDoesNotDropViewportFocusWhileControlIsHeld() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        var receipt = 0L
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            receipt++; view = view.copy(receipt = BrowserInputReceipt(receipt, "accepted")); true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.onNodeWithTag("browserViewport").assertIsFocused()
        compose.runOnIdle { view = view.copy(image = null) }
        compose.onNodeWithTag("browserViewport").assertIsFocused()
    }

    @Test fun lostFrameAndControlRemovePreviouslyDecodedImage() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { view = view.copy(image = null, ownsControl = false, notice = BrowserNotice.FRAME_FAILED) }
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
    }

    @Test fun documentResetKeepsUncertainDraftAndDiscardsQueuedInput() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = { sent.add(it); view = view.copy(inputPending = true); true }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("keep draft")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.runOnIdle { view = view.copy(viewportRevision = 2, documentEpoch = 1, inputPending = false, image = null) }
        compose.onNodeWithTag("browserText").assertTextContains("keep draft")
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun movingViewBlocksNewTextUntilStableFrame() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.runOnIdle { view = view.copy(moving = true) }
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        compose.runOnIdle { view = view.copy(moving = false) }
        compose.onNodeWithTag("browserSendText").assertIsEnabled()
    }

    @Test fun losingViewportFocusReleasesHeldKeyAndUnblocksNavigation() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); view = view.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")); true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.onNodeWithTag("browserViewport").performKeyInput { keyDown(Key.DirectionDown) }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it == BrowserInput.Key("down", "ArrowDown", "ArrowDown") } } }
        compose.onNodeWithText("New tab").assertIsNotEnabled()
        compose.onNodeWithTag("browserText").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it == BrowserInput.Key("up", "ArrowDown", "ArrowDown") } } }
        compose.onNodeWithText("New tab").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("down", "up"), sent.filterIsInstance<BrowserInput.Key>().map { it.action }) }
    }

    @Test fun hardwareRepeatUsesValidTransitionsAndFinalRelease() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        lateinit var hostView: android.view.View
        compose.setContent { hostView = LocalView.current; MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); view = view.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")); true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.runOnIdle {
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0, NativeKeyEvent.META_SHIFT_ON))
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 1))
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0))
        }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.filterIsInstance<BrowserInput.Key>().size == 4 } }
        compose.runOnIdle {
            assertEquals(listOf(
                BrowserInput.Key("down", "ArrowDown", "ArrowDown", shift = true),
                BrowserInput.Key("up", "ArrowDown", "ArrowDown", shift = true),
                BrowserInput.Key("down", "ArrowDown", "ArrowDown"),
                BrowserInput.Key("up", "ArrowDown", "ArrowDown"),
            ), sent.filterIsInstance<BrowserInput.Key>())
        }
    }

    @Test fun repeatBurstKeepsQueueBoundedAndReservesFinalRelease() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        lateinit var hostView: android.view.View
        compose.setContent { hostView = LocalView.current; MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it); view = view.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")); true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.runOnIdle {
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DEL, 0))
            repeat(40) { hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DEL, it + 1)) }
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DEL, 0))
        }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.filterIsInstance<BrowserInput.Key>().size == 32 } }
        compose.runOnIdle {
            val keys = sent.filterIsInstance<BrowserInput.Key>()
            assertEquals(List(32) { if (it % 2 == 0) "down" else "up" }, keys.map { it.action })
        }
        compose.onNodeWithText("New tab").assertIsEnabled()
    }

    @Test fun losingControlDiscardsQueuedRepeatsAndRelease() {
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        val sent = mutableListOf<BrowserInput>()
        lateinit var hostView: android.view.View
        compose.setContent { hostView = LocalView.current; MaterialTheme { BrowserPane(view, BrowserPaneActions(input = {
            sent.add(it)
            view = if (it is BrowserInput.Key) view.copy(inputPending = true) else view.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted"))
            true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.runOnIdle { hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0)) }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it is BrowserInput.Key } } }
        compose.runOnIdle {
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 1))
            hostView.dispatchKeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0))
            view = view.copy(ownsControl = false, inputPending = false)
        }
        compose.runOnIdle { assertEquals(listOf(BrowserInput.Key("down", "ArrowDown", "ArrowDown")), sent.filterIsInstance<BrowserInput.Key>()) }
    }


    // Touch gestures, key chips, reload and the shared scaffold.

    /** Shows a controlling pane; its input is acknowledged at once while [acknowledge] says so. */
    private fun showControlled(sent: MutableList<BrowserInput>, tabs: List<BrowserTab> = emptyList(), navigated: MutableList<String> = mutableListOf(), acknowledge: () -> Boolean = { true }): MutableState<BrowserView> {
        val state = mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image(), tabs = tabs, selectedTabId = tabs.firstOrNull()?.id))
        compose.setContent { MaterialTheme { BrowserPane(state.value, BrowserPaneActions(navigate = { navigated += it; true }, input = {
            sent.add(it)
            state.value = if (acknowledge()) state.value.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")) else state.value.copy(inputPending = true)
            true
        }), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        return state
    }
    /** CSS pixels per view pixel of the 400x200 test image as the viewport letterboxes it. */
    private fun cssPerPixel(): Double {
        val size = compose.onNodeWithTag("browserViewport").fetchSemanticsNode().size
        val fit = requireNotNull(browserImageFit(size.width.toFloat(), size.height.toFloat(), 400, 200))
        assertTrue("the test image is letterboxed", fit.left > 0f || fit.top > 0f)
        return 200.0 / fit.height
    }
    private fun settle() { compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }

    @Test fun fingerTapClicksAtTheTouchedPoint() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        compose.onNodeWithTag("browserViewport").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 2 } }
        compose.runOnIdle {
            val (down, up) = sent.map { it as BrowserInput.Pointer }
            assertEquals(listOf("down", "up"), listOf(down.action, up.action))
            assertEquals(listOf("left", "left"), listOf(down.button, up.button))
            assertEquals(down.x, up.x, 0.0); assertEquals(down.y, up.y, 0.0)
            assertEquals(200.0, down.x, 1.0); assertEquals(100.0, down.y, 1.0)
        }
    }

    @Test fun fingerDragScrollsWithWheelInCssPixelsAndNaturalDirection() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        val scale = cssPerPixel()
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); moveBy(Offset(0f, -60f)); moveBy(Offset(-30f, -60f)); up() }
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.filterIsInstance<BrowserInput.Wheel>().sumOf { it.deltaY } >= 119 * scale } }
        compose.runOnIdle {
            assertTrue("a scroll presses no button", sent.none { it is BrowserInput.Pointer })
            val wheels = sent.filterIsInstance<BrowserInput.Wheel>()
            // The finger moved up and left: the page scrolls down and right, by the finger's distance.
            assertEquals(120 * scale, wheels.sumOf { it.deltaY }, 0.01)
            assertEquals(30 * scale, wheels.sumOf { it.deltaX }, 0.01)
            assertTrue(wheels.all { abs(it.x - 200.0) < 1.0 && abs(it.y - 100.0) < 1.0 })
        }
    }

    @Test fun wheelDeltasAddUpWhileAnInputIsInFlight() {
        val sent = mutableListOf<BrowserInput>()
        var acknowledge = false
        val state = showControlled(sent) { acknowledge }
        val scale = cssPerPixel()
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); moveBy(Offset(0f, -40f)) }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 1 } }
        compose.onNodeWithTag("browserViewport").performTouchInput { repeat(10) { moveBy(Offset(0f, -10f)) }; up() }
        settle()
        compose.runOnIdle { assertEquals("one input in flight at a time", 1, sent.size) }
        compose.runOnIdle { acknowledge = true; state.value = state.value.copy(inputPending = false, receipt = BrowserInputReceipt(1, "accepted")) }
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 2 } }
        compose.runOnIdle {
            val (first, second) = sent.map { it as BrowserInput.Wheel }
            assertEquals(40 * scale, first.deltaY, 0.01)
            assertEquals("the queued wheel carries the sum", 100 * scale, second.deltaY, 0.01)
        }
    }

    @Test fun longPressThenDragIsALeftButtonMouseDrag() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); advanceEventTime(1_000); moveBy(Offset(40f, 0f)); moveBy(Offset(40f, 0f)); up() }
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.lastOrNull().let { it is BrowserInput.Pointer && it.action == "up" } } }
        compose.runOnIdle {
            assertTrue(sent.none { it is BrowserInput.Wheel })
            val pointers = sent.map { it as BrowserInput.Pointer }
            assertEquals("down", pointers.first().action); assertEquals("up", pointers.last().action)
            assertTrue(pointers.any { it.action == "move" })
            assertTrue(pointers.all { it.button == "left" })
            assertTrue("the drag moved right", pointers.last().x > pointers.first().x)
        }
        compose.onNodeWithText("New tab").assertIsEnabled()
    }

    @Test fun longPressWithoutMovementIsARightClick() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); advanceEventTime(1_000); up() }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 2 } }
        compose.runOnIdle {
            assertEquals(listOf("down" to "right", "up" to "right"), sent.map { (it as BrowserInput.Pointer).action to it.button })
        }
    }

    @Test fun cancelledGesturesNeverLeaveAButtonHeld() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        // A cancelled scroll or tap sends nothing.
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); moveBy(Offset(0f, -60f)); cancel() }
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); cancel() }
        settle()
        compose.runOnIdle { assertTrue(sent.none { it is BrowserInput.Pointer }) }
        // A cancelled drag releases its button.
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); advanceEventTime(1_000); moveBy(Offset(40f, 0f)); cancel() }
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.lastOrNull().let { it is BrowserInput.Pointer && it.action == "up" } } }
        compose.onNodeWithText("New tab").assertIsEnabled()
        // Focus moving away mid-drag releases it as well.
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); advanceEventTime(1_000); moveBy(Offset(40f, 0f)) }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.lastOrNull().let { it is BrowserInput.Pointer && it.action == "move" } } }
        compose.onNodeWithText("New tab").assertIsNotEnabled()
        compose.onNodeWithTag("browserText").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.lastOrNull().let { it is BrowserInput.Pointer && it.action == "up" } } }
        compose.onNodeWithText("New tab").assertIsEnabled()
        // The finger lifting later sends nothing more: the gesture ended with the focus.
        compose.onNodeWithTag("browserViewport").performTouchInput { up() }
        settle()
        compose.runOnIdle {
            val pointers = sent.filterIsInstance<BrowserInput.Pointer>()
            assertEquals(pointers.count { it.action == "down" }, pointers.count { it.action == "up" })
        }
    }

    @Test fun mouseKeepsPlainMouseSemantics() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        compose.onNodeWithTag("browserViewport").performMouseInput { moveTo(center); press(); moveBy(Offset(30f, 0f)); release() }
        settle()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.lastOrNull().let { it is BrowserInput.Pointer && it.action == "up" } } }
        compose.runOnIdle {
            assertTrue(sent.none { it is BrowserInput.Wheel })
            val pointers = sent.map { it as BrowserInput.Pointer }
            assertEquals("down", pointers.first().action)
            assertTrue(pointers.any { it.action == "move" })
            assertTrue(pointers.all { it.button == "left" })
        }
    }

    @Test fun keyChipIsOnePressThatCannotStayDown() {
        val sent = mutableListOf<BrowserInput>()
        showControlled(sent)
        compose.onNodeWithTag("browserKey_PageDown").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 2 } }
        compose.runOnIdle { assertEquals(listOf(BrowserInput.Key("down", "PageDown", "PageDown"), BrowserInput.Key("up", "PageDown", "PageDown")), sent) }
        compose.onNodeWithText("New tab").assertIsEnabled()
        compose.onNodeWithTag("browserKey_Escape").assertContentDescriptionEquals("Escape")
    }

    @Test fun reloadOpensTheCurrentAddressAgainOnlyWhenThatReloads() {
        val sent = mutableListOf<BrowserInput>(); val navigated = mutableListOf<String>()
        val state = showControlled(sent, listOf(BrowserTab("tab", "T", "https://example.com/a")), navigated)
        compose.onNodeWithTag("browserReload").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.com/a"), navigated) }
        compose.runOnIdle { state.value = state.value.copy(tabs = listOf(BrowserTab("tab", "T", "https://example.com/a#part"))) }
        compose.onAllNodesWithTag("browserReload").assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(tabs = listOf(BrowserTab("tab", "T", "https://example.com/a")), ownsControl = false) }
        compose.onAllNodesWithTag("browserReload").assertCountEquals(0)
        compose.onNodeWithTag("browserSync").assertExists()
    }

    @Test fun controlBarNamesTheHolderAndHandsOverToALocalizedParticipant() {
        val handed = mutableListOf<String>()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, owner = BrowserOwner("agent", "agent-123456")))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(handoff = { handed += it; true }), {}) } }
        compose.onNodeWithTag("browserControlState").assertTextEquals("Control held by Pi agent 123456")
        compose.runOnIdle { view = view.copy(ownsControl = true, owner = BrowserOwner("device", "me"), participants = listOf(BrowserOwner("device", "me"), BrowserOwner("agent", "agent-123456"))) }
        compose.onNodeWithTag("browserControlState").assertTextEquals("You have control")
        compose.onNodeWithTag("browserHandoff").performClick()
        compose.onNodeWithTag("browserHandoff_agent-123456").assertTextEquals("Pi agent 123456").performClick()
        compose.runOnIdle { assertEquals(listOf("agent-123456"), handed) }
    }

    @Test fun frameFailureOffersRetryAndOpeningShowsAPlaceholder() {
        var refreshed = 0
        var view by mutableStateOf<BrowserView?>(BrowserView("s"))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(refresh = { refreshed++; true }), {}) } }
        compose.onNodeWithTag("browserViewportPlaceholder").assertExists()
        compose.onNodeWithText("Opening…").assertExists()
        compose.onAllNodesWithTag("browserControlBar").assertCountEquals(0)
        compose.runOnIdle { view = BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, notice = BrowserNotice.FRAME_FAILED) }
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }

    @Test fun aRefusedInputWhileAButtonIsHeldDropsControlWithoutHidingThePane() {
        val sent = mutableListOf<BrowserInput>(); val visible = mutableListOf<Boolean>(); var drops = 0
        val state = mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { BrowserPane(state.value, BrowserPaneActions(
            input = {
                if (sent.isNotEmpty()) false
                else { sent.add(it); state.value = state.value.copy(receipt = BrowserInputReceipt(1, "accepted")); true }
            },
            visible = { visible += it }, dropControl = { drops++ },
        ), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserViewport").performMouseInput { moveTo(center); press() }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.size == 1 } }
        compose.onNodeWithTag("browserViewport").performMouseInput { moveBy(Offset(20f, 0f)) }
        compose.waitUntil(5_000) { compose.runOnIdle { drops == 1 } }
        // The drop is not a hide and show, which would take control back on its own.
        compose.runOnIdle { assertEquals(listOf(true), visible) }
    }

    @Test fun tabsAreSelectableGlassChipsWithAFortyEightDpClose() {
        val selected = mutableListOf<String>(); val closed = mutableListOf<String>()
        var view by mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1,
            tabs = listOf(BrowserTab("t1", "First", "https://a.example"), BrowserTab("t2", "", "https://b.example")), selectedTabId = "t1"))
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(selectTab = { selected += it }, closeTab = { closed += it; true }), {}) } }
        compose.onNodeWithTag("browserTab_t1").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithTag("browserTab_t2").assertIsNotSelected().assertTextEquals("https://b.example").performClick()
        compose.onNodeWithTag("browserTabClose_t1").assertContentDescriptionEquals("Close tab First").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("t2"), selected); assertEquals(listOf("t1"), closed) }
        // Without control a tab can still be watched, but not closed.
        compose.runOnIdle { view = view.copy(ownsControl = false) }
        compose.onNodeWithTag("browserTabClose_t1").assertDoesNotExist()
        compose.onNodeWithTag("browserTab_t1").assertExists()
    }

    @Test fun aCompactPaneLeavesOutTheTabRowAndAnEmptyTextBar() {
        var height by mutableStateOf(360.dp)
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxWidth().height(height)) {
            BrowserPane(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1,
                tabs = listOf(BrowserTab("t1", "First", "https://a.example")), selectedTabId = "t1"), BrowserPaneActions(), {})
        } } }
        compose.onNodeWithTag("browserTabs").assertDoesNotExist()
        compose.onNodeWithTag("browserText").assertDoesNotExist()
        compose.onNodeWithTag("browserAddress").assertExists()
        compose.onNodeWithTag("browserKeys").assertExists()
        compose.runOnIdle { height = 700.dp }
        compose.onNodeWithTag("browserTabs").assertExists()
        compose.onNodeWithTag("browserText").assertExists()
    }

    /** A controlling pane in a box [height] high, whose resize the Host applies at once. */
    private fun showResizable(sent: MutableList<BrowserInput>, resizes: MutableList<Int>, height: MutableState<Int>): MutableState<BrowserView> {
        val state = mutableStateOf(BrowserView("s", opening = false, browserId = "b", ownsControl = true, generation = 1, viewportRevision = 1, image = image()))
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxWidth().height(height.value.dp)) { BrowserPane(state.value, BrowserPaneActions(
            resize = { _, h, _, _ ->
                resizes += h
                val revision = state.value.viewportRevision + 1
                state.value = state.value.copy(viewportRevision = revision, image = state.value.image?.copy(viewportRevision = revision))
                true
            },
            input = { sent.add(it); state.value = state.value.copy(receipt = BrowserInputReceipt(sent.size.toLong(), "accepted")); true },
        ), {}) } } }
        compose.waitUntil(10_000) { compose.runOnIdle { resizes.isNotEmpty() } }
        // The resize replaced the frame: wait for the new one to be decoded.
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        return state
    }

    @Test fun theViewportDoesNotResizeUnderAFingerSoTheTapIsNotRefused() {
        val sent = mutableListOf<BrowserInput>(); val resizes = mutableListOf<Int>(); val height = mutableStateOf(700)
        showResizable(sent, resizes, height)
        // The finger goes down; the soft keyboard hides and the viewport grows meanwhile.
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center) }
        compose.runOnIdle { height.value = 600 }
        settle()
        compose.runOnIdle { assertEquals("no resize under the finger", 1, resizes.size) }
        compose.onNodeWithTag("browserViewport").performTouchInput { up() }
        compose.waitUntil(5_000) { compose.runOnIdle { sent.count { it is BrowserInput.Pointer } == 2 } }
        compose.waitUntil(5_000) { compose.runOnIdle { resizes.size == 2 } }
        compose.runOnIdle { assertEquals(listOf("down", "up"), sent.map { (it as BrowserInput.Pointer).action }) }
    }

    @Test fun aCancelledFingerGestureEndsSoTheViewportResizesAgain() {
        val sent = mutableListOf<BrowserInput>(); val resizes = mutableListOf<Int>(); val height = mutableStateOf(700)
        showResizable(sent, resizes, height)
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center); cancel() }
        compose.runOnIdle { height.value = 600 }
        settle()
        // The cancel reached the touch handling: the gesture ended, nothing was clicked.
        compose.waitUntil(5_000) { compose.runOnIdle { resizes.size == 2 } }
        compose.runOnIdle { assertTrue(sent.none { it is BrowserInput.Pointer }) }
    }

    @Test fun typingADraftReportsUnsentInput() {
        var unsent = false
        compose.setContent { MaterialTheme { BrowserPane(BrowserView("s", opening = false, ownsControl = true), BrowserPaneActions(), {}, { unsent = it }) } }
        compose.runOnIdle { assertFalse(unsent) }
        compose.onNodeWithTag("browserText").performTextInput("draft")
        compose.runOnIdle { assertTrue(unsent) }
        compose.onNodeWithTag("browserText").performTextReplacement("")
        compose.runOnIdle { assertFalse(unsent) }
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com")
        compose.runOnIdle { assertTrue(unsent) }
    }
}
