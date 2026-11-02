package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThinkingActivityUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(display: ThinkingDisplay, activity: SessionActivityLine?) {
        compose.setContent {
            MaterialTheme {
                Column {
                    ConversationMessage(
                        ConversationItem.Thinking("thinking", "message", "Thinking content", true),
                        display,
                        thinkingActive = true,
                        onQuote = {},
                        hideThinkingStatus = activity != null,
                    )
                    ChatCurrentActivity(activity)
                }
            }
        }
    }

    @Test
    fun composerActivityReplacesStreamingThinkingStatus() {
        render(ThinkingDisplay.STATUS, SessionActivityLine(R.string.remote_waiting_for_output))
        compose.onNodeWithTag("chatCurrentActivity").assertExists()
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(context.getString(R.string.remote_thinking_active)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.remote_thinking_processing)).assertDoesNotExist()
    }

    @Test
    fun thinkingTextRemainsAlongsideComposerActivity() {
        render(ThinkingDisplay.TEXT, SessionActivityLine(R.string.remote_waiting_for_output))
        compose.onNodeWithText("Thinking content").assertExists()
        compose.onNodeWithTag("chatCurrentActivity").assertExists()
    }

    @Test
    fun thinkingStatusRemainsWhenComposerActivityIsAbsent() {
        render(ThinkingDisplay.STATUS, null)
        compose.onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.remote_thinking_active)
        ).assertExists()
    }
}
