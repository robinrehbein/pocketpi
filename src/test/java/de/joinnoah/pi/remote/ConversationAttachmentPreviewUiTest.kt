package de.joinnoah.pi.remote

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationAttachmentPreviewUiTest {
    @get:Rule val compose = createComposeRule()

    private fun item(id: String = "first", width: Int = 40, height: Int = 20) = ConversationAttachmentPreviewItem(
        id, "$id.jpg", ConversationAttachmentAvailability.AvailableUntil("12 Oct"),
        ConversationAttachmentImageState.Ready(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImageBitmap()),
        ConversationAttachmentImageState.Ready(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImageBitmap()),
    )

    @Test fun selectingSecondImageOpensItsDialogAndCloseDismissesIt() {
        var opened: String? = null
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(item(), item("second")), { opened = it }, { _, _ -> }) } }
        compose.onNodeWithTag("conversationAttachmentImage-Thumbnail-first", true).assertIsDisplayed()
        compose.onNodeWithTag("conversationAttachmentThumbnail-second").performClick()
        assertEquals("second", opened)
        compose.onNodeWithTag("conversationAttachmentLarge-second").assertIsDisplayed()
        compose.onNodeWithTag("conversationAttachmentLarge-first").assertDoesNotExist()
        compose.onNodeWithTag("closeConversationAttachmentPreview").performClick()
        compose.onNodeWithTag("conversationAttachmentLarge-second").assertDoesNotExist()
    }

    @Test fun openDialogTracksExternalStateAndSelectedItemRemoval() {
        val items = mutableStateOf(listOf(item()))
        val retries = mutableListOf<Pair<String, ConversationAttachmentPreviewTarget>>()
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(items.value, {}, { id, target -> retries += id to target }) } }
        compose.onNodeWithTag("conversationAttachmentThumbnail-first").performClick()
        compose.runOnIdle { items.value = listOf(items.value.single().copy(largeImage = ConversationAttachmentImageState.Loading)) }
        compose.onNodeWithText("Loading image…").assertIsDisplayed()
        compose.runOnIdle { items.value = listOf(items.value.single().copy(largeImage = ConversationAttachmentImageState.ConnectionFailure)) }
        compose.onNodeWithTag("conversationAttachmentRetry-LargeImage-first").performClick()
        assertEquals(listOf("first" to ConversationAttachmentPreviewTarget.LargeImage), retries)
        compose.runOnIdle { items.value = emptyList() }
        compose.onNodeWithTag("conversationAttachmentLarge-first").assertDoesNotExist()
        compose.runOnIdle { items.value = listOf(item()) }
        compose.onNodeWithTag("conversationAttachmentLarge-first").assertDoesNotExist()
    }

    @Test fun allThumbnailStatesHaveMessagesAndOnlyConnectionFailureCanRetry() {
        val current = mutableStateOf(item().copy(thumbnail = ConversationAttachmentImageState.Loading))
        val retries = mutableListOf<Pair<String, ConversationAttachmentPreviewTarget>>()
        var opens = 0
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(current.value), { opens++ }, { id, target -> retries += id to target }) } }
        val cases = listOf(
            ConversationAttachmentImageState.Loading to "Loading image…",
            ConversationAttachmentImageState.ConnectionFailure to "Could not load image. Check your connection.",
            ConversationAttachmentImageState.Unavailable to "Photo preview unavailable",
            ConversationAttachmentImageState.Expired to "This image has expired.",
            ConversationAttachmentImageState.UnsupportedHost to "Update the host to view sent images.",
            ConversationAttachmentImageState.MalformedImage to "This image cannot be displayed.",
        )
        for ((state, message) in cases) {
            compose.runOnIdle { current.value = current.value.copy(thumbnail = state) }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithTag("conversationAttachmentThumbnail-first").assertHasNoClickAction()
            if (state == ConversationAttachmentImageState.ConnectionFailure)
                compose.onNodeWithTag("conversationAttachmentRetry-Thumbnail-first").performClick()
            else compose.onNodeWithTag("conversationAttachmentRetry-Thumbnail-first").assertDoesNotExist()
        }
        assertEquals(0, opens)
        assertEquals(listOf("first" to ConversationAttachmentPreviewTarget.Thumbnail), retries)
    }

    @Test fun openDialogRendersAllLargeImageFailureStates() {
        val current = mutableStateOf(item())
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(current.value), {}, { _, _ -> }) } }
        compose.onNodeWithTag("conversationAttachmentThumbnail-first").performClick()
        for ((state, message) in listOf(
            ConversationAttachmentImageState.Loading to "Loading image…",
            ConversationAttachmentImageState.Unavailable to "Photo preview unavailable",
            ConversationAttachmentImageState.Expired to "This image has expired.",
            ConversationAttachmentImageState.UnsupportedHost to "Update the host to view sent images.",
            ConversationAttachmentImageState.MalformedImage to "This image cannot be displayed.",
        )) {
            compose.runOnIdle { current.value = current.value.copy(largeImage = state) }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithTag("conversationAttachmentRetry-LargeImage-first").assertDoesNotExist()
        }
        compose.runOnIdle { current.value = item() }
        compose.onNodeWithTag("conversationAttachmentImage-LargeImage-first", true).assertIsDisplayed()
    }

    @Test fun availabilityUpdatesWithoutReadingClock() {
        val current = mutableStateOf(item().copy(thumbnail = ConversationAttachmentImageState.Loading))
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(current.value), {}, { _, _ -> }) } }
        compose.onNodeWithText("first.jpg").assertIsDisplayed()
        compose.onNodeWithText("Available until 12 Oct").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(availability = ConversationAttachmentAvailability.ExpiredOn("13 Oct")) }
        compose.onNodeWithText("Expired on 13 Oct").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(availability = ConversationAttachmentAvailability.Unavailable) }
        compose.onNodeWithText("Photo preview unavailable").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanResourcesDescribeLoadingExpiryAndRetry() {
        val current = mutableStateOf(item().copy(thumbnail = ConversationAttachmentImageState.Loading))
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(current.value), {}, { _, _ -> }) } }
        compose.onNodeWithText("Bild wird geladen…").assertIsDisplayed()
        compose.onNodeWithText("Verfügbar bis 12 Oct").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(thumbnail = ConversationAttachmentImageState.ConnectionFailure) }
        compose.onNodeWithText("Erneut versuchen").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(thumbnail = ConversationAttachmentImageState.Expired, availability = ConversationAttachmentAvailability.ExpiredOn("13 Oct")) }
        compose.onNodeWithText("Dieses Bild ist abgelaufen.").assertIsDisplayed()
        compose.onNodeWithText("Abgelaufen am 13 Oct").assertIsDisplayed()
    }

    @Test fun draggingThumbnailDoesNotOpenDialog() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConversationAttachmentPreview(listOf(item()), { opens++ }, { _, _ -> })
                    Spacer(Modifier.height(1000.dp))
                }
            }
        }
        compose.onNodeWithTag("conversationAttachmentThumbnail-first").performTouchInput { swipeUp() }
        compose.onNodeWithTag("conversationAttachmentLarge-first").assertDoesNotExist()
        assertEquals(0, opens)
    }

    @Test fun largeImageSupportsPortraitAndLandscapeLayouts() {
        val current = mutableStateOf(item(width = 20, height = 40))
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(current.value), {}, { _, _ -> }) } }
        compose.onNodeWithTag("conversationAttachmentThumbnail-first").performClick()
        // The layout may letterbox a constrained image; Fit must never stretch its pixels.
        compose.onNodeWithTag("conversationAttachmentImage-LargeImage-first", true).assertIsDisplayed()
        compose.runOnIdle { current.value = item(width = 40, height = 20) }
        compose.onNodeWithTag("conversationAttachmentImage-LargeImage-first", true).assertIsDisplayed()
    }
}
