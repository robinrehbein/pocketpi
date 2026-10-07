package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolOutputGestureUiTest {
    @get:Rule val compose = createComposeRule()

    private fun activity() = ConversationItem.Activity(
        id = "tool", sourceId = "message", name = "read", arguments = "{\"path\":\"Main.kt\"}",
        output = "package example\nfun main() {}", state = "complete", truncated = false,
    )

    @Test fun longPressOnCollapsedHeaderOpensPreviewWithoutNavigating() {
        var opened = 0
        compose.setContent { MaterialTheme {
            ConversationMessage(activity(), "hidden", false, onQuote = {}, onOpenTool = { opened++ })
        } }
        compose.onNodeWithTag("toolCardHeader").performTouchInput { longClick() }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("package example\nfun main() {}").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, opened) }
    }

    @Test fun shortTapOnHeaderStillNavigates() {
        var opened = 0
        compose.setContent { MaterialTheme {
            ConversationMessage(activity(), "hidden", false, onQuote = {}, onOpenTool = { opened++ })
        } }
        compose.onNodeWithTag("toolCardHeader").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, opened) }
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test fun longPressOnHeaderWithoutDetailCallbackOpensPreview() {
        compose.setContent { MaterialTheme { ConversationMessage(activity(), "hidden", false, onQuote = {}) } }
        compose.onNodeWithTag("toolCardHeader").performTouchInput { longClick() }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun longPressOnSourceTextOpensPreview() {
        compose.setContent { MaterialTheme { ToolOutputView("package example\nfun main() {}", "read") } }
        compose.onNodeWithText("package example\nfun main() {}").performTouchInput { longClick() }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun longPressOnMarkdownCodeOpensPreview() {
        compose.setContent { MaterialTheme { ToolOutputView("```kotlin\nfun main() {}\n```", "custom") } }
        compose.onNodeWithText("fun main() {}").performTouchInput { longClick() }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
