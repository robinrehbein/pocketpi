package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search result count is announced to TalkBack only once typing settles, not on every
 * keystroke, while the visible count keeps updating immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolDetailSearchAnnouncementTest {
    @get:Rule val compose = createComposeRule()

    private fun bash(output: String) =
        ConversationItem.Activity(
            id = "tool-search",
            sourceId = "message-search",
            name = "bash",
            arguments = """{"command":"make test"}""",
            output = output,
            state = "complete",
            truncated = false,
            toolCallId = "call_bash_search",
        )

    @Test
    fun announcementLagsBehindTheVisibleCountUntilTypingSettles() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                MaterialTheme {
                    ToolDetailScreen(
                        item = bash((1..10).joinToString("\n") { "line $it message" }),
                        download = null,
                        canLoadFullOutput = false,
                        canAskToFix = false,
                        onLoadFullOutput = {},
                        onCancelFullOutput = {},
                        onAskToFix = {},
                        onQuote = {},
                        onClose = {},
                    )
                }
            }
            compose.onNodeWithTag("toolDetailSearch").performTextInput("message")
            compose.mainClock.advanceTimeBy(100)
            // The visible count updates right away...
            compose.onNodeWithTag("toolDetailSearchCount").assertExists()
            // ...but nothing has been announced yet: the debounce has not elapsed.
            compose.onNode(hasTestTag("toolDetailSearchAnnouncement")).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(SEARCH_ANNOUNCE_DEBOUNCE_MILLIS + 200)
            val announcement =
                compose.onNodeWithTag("toolDetailSearchAnnouncement").fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription].single()
            assertTrue(announcement.contains("10"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
