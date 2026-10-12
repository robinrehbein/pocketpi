package de.joinnoah.pi.remote

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationSentImagesUiTest {
    @get:Rule val compose = createComposeRule()

    @org.junit.Before fun emptyThumbnailCache() = SentImageThumbnails.clear()

    private val image = attachment("AAAAAAAAAAAAAAAAAAAAAA", "photo.jpg", "image", "image/jpeg")
    private val file = attachment("BBBBBBBBBBBBBBBBBBBBBA", "notes.txt", "file", "text/plain")

    private fun attachment(id: String, name: String, kind: String, mime: String, expiresAt: Long = Long.MAX_VALUE) =
        RemoteAttachment(id, name, kind, mime, 2048, "a".repeat(64), expiresAt)

    private fun bubble(vararg attachments: RemoteAttachment) =
        ConversationItem.Bubble(
            id = "bubble", sourceId = "message", role = "user", author = null, text = "Look",
            quote = null, truncated = false, timestamp = null, attachments = attachments.toList(),
        )

    private fun source(read: suspend (String, RemoteAttachment) -> AttachmentReadResult) =
        SentImageSource("session", connected = true, capabilitiesKnown = true, supported = true, read = read)

    private fun show(item: ConversationItem.Bubble, images: SentImageSource?) =
        compose.setContent {
            MaterialTheme {
                ConversationMessage(item, "hidden", thinkingActive = false, onQuote = {}, images = images)
            }
        }

    private fun png(): ByteArray =
        ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

    @Test fun imageShowsAThumbnailAndAFileKeepsItsText() {
        val reads = mutableListOf<String>()
        show(bubble(image, file), source { _, a -> reads += a.id; AttachmentReadResult.Loaded(png()) })
        compose.onNodeWithTag("conversationAttachmentThumbnail-${image.id}").assertExists()
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithTag("conversationAttachmentThumbnail-${file.id}").assertDoesNotExist()
        assertEquals(listOf(image.id), reads)
    }

    @Test fun decodedImageReplacesTheLoadingState() {
        show(bubble(image), source { _, _ -> AttachmentReadResult.Loaded(png()) })
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversationAttachmentImage-Thumbnail-${image.id}", true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun unsupportedHostShowsItsText() {
        show(bubble(image), source { _, _ -> AttachmentReadResult.Unsupported })
        compose.onNodeWithText("Update the host to view sent images.").assertIsDisplayed()
    }

    @Test fun expiredImageSendsNoRequest() {
        val reads = mutableListOf<String>()
        show(bubble(image.copy(expiresAt = 1)), source { _, a -> reads += a.id; AttachmentReadResult.Unavailable })
        compose.onNodeWithText("This image has expired.").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), reads)
    }

    @Test fun retryReadsAgainAfterAConnectionFailure() {
        var calls = 0
        show(bubble(image), source { _, _ -> if (++calls == 1) AttachmentReadResult.Failed else AttachmentReadResult.Loaded(png()) })
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversationAttachmentRetry-Thumbnail-${image.id}", true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("conversationAttachmentRetry-Thumbnail-${image.id}", true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversationAttachmentImage-Thumbnail-${image.id}", true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, calls)
    }

    @Test fun withoutASourceEveryAttachmentKeepsTheTextPresentation() {
        show(bubble(image, file), null)
        compose.onNodeWithText("photo.jpg").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithTag("conversationAttachmentThumbnail-${image.id}").assertDoesNotExist()
    }

    @Test fun unknownCapabilitiesStayLoadingWithoutARequest() {
        val reads = mutableListOf<String>()
        show(bubble(image), SentImageSource("session", true, false, false) { _, a -> reads += a.id; AttachmentReadResult.Unsupported })
        compose.waitForIdle()
        compose.onNodeWithText("Loading image…").assertIsDisplayed()
        assertEquals(emptyList<String>(), reads)
    }

    @Test fun aReadyThumbnailSurvivesAConnectionChange() {
        var calls = 0
        val current = androidx.compose.runtime.mutableStateOf(source { _, _ -> calls++; AttachmentReadResult.Loaded(png()) })
        compose.setContent {
            MaterialTheme { ConversationMessage(bubble(image), "hidden", thinkingActive = false, onQuote = {}, images = current.value) }
        }
        val tag = "conversationAttachmentImage-Thumbnail-${image.id}"
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { current.value = SentImageSource("session", false, true, true, current.value.read) }
        compose.waitForIdle()
        compose.onNodeWithTag(tag, true).assertExists()
        assertEquals(1, calls)
    }

    @Test fun imagesAndFilesKeepTheirOrderInTheBubble() {
        show(bubble(file, image), source { _, _ -> AttachmentReadResult.Unsupported })
        val file = compose.onNodeWithText("notes.txt").fetchSemanticsNode().positionInRoot.y
        val image = compose.onNodeWithTag("conversationAttachmentThumbnail-${image.id}").fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue(file < image)
    }
}
