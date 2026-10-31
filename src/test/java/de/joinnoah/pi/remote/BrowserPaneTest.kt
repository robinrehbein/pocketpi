package de.joinnoah.pi.remote

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import java.io.ByteArrayOutputStream
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

}
