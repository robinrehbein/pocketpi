package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A large argument value (write's `content`, up to MAX_ARGUMENT_ROWS lines when expanded) must
 * not compose every row at once: CodeBlock lays its rows out in a bounded-height lazy list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolDetailArgumentsLazyTest {
    @get:Rule val compose = createComposeRule()

    private fun writeItem(lines: Int) =
        ConversationItem.Activity(
            id = "tool-write",
            sourceId = "message-write",
            name = "write",
            arguments = """{"path":"a.txt","content":"${(1..lines).joinToString("\\n") { "line $it" }}"}""",
            output = null,
            state = "complete",
            truncated = false,
            toolCallId = "call_write_1",
        )

    @Test
    fun onlyASmallWindowOfALargeCodeBlockIsComposedUpFront() {
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = writeItem(500),
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
        compose.onNodeWithTag("toolDetailArguments").assert(hasAnyDescendant(hasText("line 1")))
        // A row far below the visible window is not composed without scrolling to it.
        compose.onAllNodes(hasText("line 199")).assertCountEquals(0)
        val rows = compose.onNode(hasTestTag("codeBlockRows") and hasAnyAncestor(hasTestTag("toolDetailArguments")))
        rows.performScrollToNode(hasText("line 199"))
        compose.onNodeWithTag("toolDetailArguments").assert(hasAnyDescendant(hasText("line 199")))
    }
}
