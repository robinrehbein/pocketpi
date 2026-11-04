package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationAttachmentPreviewUiTest {
    @get:Rule val compose = createComposeRule()

    private fun image(id: String) = ConversationAttachmentPreviewItem(
        id, "$id.jpg", ConversationAttachmentAvailability.AvailableUntil("12 Oct"),
        ConversationAttachmentImageState.Ready(ImageBitmap(20, 40)),
        ConversationAttachmentImageState.Ready(ImageBitmap(40, 20)),
    )

    @Test fun backDismissesSelectedImageAndCloseAlsoWorks() {
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(listOf(image("one"), image("two")), {}, { _, _ -> }) } }
        compose.onNodeWithTag("conversationAttachmentThumbnail-two").performClick()
        compose.onNodeWithTag("conversationAttachmentImage-LargeImage-two", true).assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("conversationAttachmentLarge-two").assertDoesNotExist()
        compose.onNodeWithTag("conversationAttachmentThumbnail-one").performClick()
        compose.onNodeWithTag("closeConversationAttachmentPreview").performClick()
        compose.onNodeWithTag("conversationAttachmentLarge-one").assertDoesNotExist()
    }

    @Test fun externalUpdateAndRemovalAffectOpenDialog() {
        val items = mutableStateOf(listOf(image("one")))
        var retry: Pair<String, ConversationAttachmentPreviewTarget>? = null
        compose.setContent { MaterialTheme { ConversationAttachmentPreview(items.value, {}, { id, target -> retry = id to target }) } }
        compose.onNodeWithTag("conversationAttachmentThumbnail-one").performClick()
        compose.runOnIdle { items.value = listOf(items.value.single().copy(largeImage = ConversationAttachmentImageState.ConnectionFailure)) }
        compose.onNodeWithTag("conversationAttachmentRetry-LargeImage-one").performClick()
        assertEquals("one" to ConversationAttachmentPreviewTarget.LargeImage, retry)
        compose.runOnIdle { items.value = emptyList() }
        compose.onNodeWithTag("conversationAttachmentLarge-one").assertDoesNotExist()
    }

    @Test fun scrollGestureDoesNotOpenImage() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConversationAttachmentPreview(listOf(image("one")), { opens++ }, { _, _ -> })
                    Spacer(Modifier.height(1000.dp))
                }
            }
        }
        compose.onNodeWithTag("conversationAttachmentThumbnail-one").performTouchInput { swipeUp() }
        assertEquals(0, opens)
        compose.onNodeWithTag("conversationAttachmentLarge-one").assertDoesNotExist()
    }
}
