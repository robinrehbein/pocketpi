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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserPaneTest {
    @get:Rule val compose = createComposeRule()
    private fun image(): BrowserImage {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); bitmap.recycle()
        return BrowserImage(out.toByteArray(), 400, 200, 400, 200, 1.0, 0.0, 0.0, 1, 1)
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
        compose.onNodeWithText("Waiting for browser image…").assertIsDisplayed()
        compose.onNodeWithText("Could not display the browser image. Refresh to try again.").assertDoesNotExist()
        compose.runOnIdle { pending.complete(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun failedDecodeReportsFailureOnlyAfterCompletion() {
        val pending = CompletableDeferred<Bitmap?>()
        val view = BrowserView("s", opening = false, browserId = "b", generation = 1, viewportRevision = 1, image = image())
        compose.setContent { MaterialTheme { BrowserPane(view, BrowserPaneActions(), {}, decodeImage = { pending.await() }) } }
        compose.onNodeWithText("Waiting for browser image…").assertIsDisplayed()
        compose.runOnIdle { pending.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not display the browser image. Refresh to try again.").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Waiting for browser image…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remote browser image").assertDoesNotExist()
        unused.recycle()
        compose.runOnIdle { second.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not display the browser image. Refresh to try again.").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Waiting for browser image…").assertIsDisplayed()
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
        compose.onNodeWithText("Waiting for browser image…").assertDoesNotExist()
        compose.onNodeWithTag("browserSendText").assertIsEnabled()
        compose.runOnIdle { next.complete(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not display the browser image. Refresh to try again.").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("Enter").assertIsEnabled().performClick()
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
        compose.onNodeWithText("Enter").assertIsNotEnabled()
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
        compose.onNodeWithTag("browserViewport").performTouchInput { down(center) }
        compose.onNodeWithTag("browserViewport").assertIsFocused()
        compose.waitUntil(5_000) { compose.runOnIdle { sent.any { it is BrowserInput.Pointer && it.action == "down" } } }
        compose.runOnIdle { view = view.copy(image = null) }
        compose.onNodeWithTag("browserViewport").performTouchInput { up() }
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

}
