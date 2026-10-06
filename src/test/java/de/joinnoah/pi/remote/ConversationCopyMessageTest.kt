package de.joinnoah.pi.remote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationCopyMessageTest {
    @get:Rule val compose = createComposeRule()

    private fun bubble(role: String, streaming: Boolean = false, error: Boolean = false, text: String = "**Hello** `world`") =
        ConversationItem.Bubble(
            id = "b1", sourceId = "m1", role = role, author = null, text = text,
            quote = null, truncated = false, timestamp = 1_000L, streaming = streaming, error = error,
        )

    private fun show(item: ConversationItem.Bubble, copyButton: Boolean = true) =
        compose.setContent {
            MaterialTheme {
                ConversationMessage(item, "hidden", thinkingActive = false, onQuote = {}, copyButton = copyButton)
            }
        }

    @Test fun completeAssistantBubbleCopiesItsRawMarkdown() {
        show(bubble("assistant"))
        compose.onNodeWithTag("copyMessage-b1").performClick()
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("**Hello** `world`", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun olderAssistantBubbleHasNoCopyButton() {
        show(bubble("assistant"), copyButton = false)
        compose.onAllNodesWithTag("copyMessage-b1").assertCountEquals(0)
    }

    @Test fun userBubbleHasNoCopyButton() {
        show(bubble("user"))
        compose.onAllNodesWithTag("copyMessage-b1").assertCountEquals(0)
    }

    @Test fun streamingAssistantBubbleHasNoCopyButton() {
        show(bubble("assistant", streaming = true))
        compose.onAllNodesWithTag("copyMessage-b1").assertCountEquals(0)
    }

    @Test fun errorAssistantBubbleHasNoCopyButton() {
        show(bubble("assistant", error = true))
        compose.onAllNodesWithTag("copyMessage-b1").assertCountEquals(0)
    }

    @Test fun completeBlankAssistantBubbleHasNoCopyButton() {
        show(bubble("assistant", text = "  "))
        compose.onAllNodesWithTag("copyMessage-b1").assertCountEquals(0)
    }
}
