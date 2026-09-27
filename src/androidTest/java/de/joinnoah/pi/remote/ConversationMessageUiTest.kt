package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationMessageUiTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val patch =
        listOf(
                "--- src/a.kt",
                "+++ src/a.kt",
                "@@ -1,6 +1,6 @@",
                " package demo",
                " ",
                " fun main() {",
                " val greeting = \"hi\"",
                "-val answer = 41",
                "+val answer = 42",
                " }",
                "",
            )
            .joinToString("\n")

    private fun activity(
        name: String,
        arguments: String,
        state: String = "complete",
        output: String? = "done",
        outputMessageId: String? = null,
        details: ToolDetails? = null,
    ) =
        ConversationItem.Activity(
            id = "tool-$name",
            sourceId = "message-$name",
            name = name,
            arguments = arguments,
            output = output,
            state = state,
            truncated = false,
            toolCallId = "call_$name",
            outputMessageId = outputMessageId,
            details = details,
        )

    private fun show(
        item: ConversationItem,
        onOpenTool: ((ConversationItem.Activity) -> Unit)? = null,
        onOpenAgent: ((ConversationItem.Subagent, Int) -> Unit)? = null,
        onAskToFix: ((String) -> Unit)? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                ConversationMessage(
                    item,
                    thinkingDisplay = "text",
                    thinkingActive = false,
                    onQuote = {},
                    onOpenTool = onOpenTool,
                    onOpenAgent = onOpenAgent,
                    onAskToFix = onAskToFix,
                )
            }
        }
    }

    @Test
    fun bashHeaderTapOpensToolDetails() {
        val opened = mutableListOf<ConversationItem.Activity>()
        val bash = activity("bash", """{"command":"ls -la"}""")
        show(bash, onOpenTool = { opened += it })
        compose.onNodeWithTag("toolCard").assertExists()
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.waitForIdle()
        assertEquals(listOf(bash), opened)
        // The header tap did not expand the card inline.
        compose.onNodeWithContentDescription(text(R.string.remote_show_output)).assertExists()
        // The expand icon still toggles the inline preview on its own.
        compose.onNodeWithTag("toolCardToggle").performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_hide_output)).assertExists()
        compose.onNodeWithTag("toolCardArguments").assertExists()
        assertEquals(1, opened.size)
    }

    @Test
    fun withoutOpenToolTheHeaderStillTogglesInline() {
        show(activity("bash", """{"command":"ls -la"}"""))
        compose.onNodeWithTag("toolCardToggle").assertDoesNotExist()
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_hide_output)).assertExists()
    }

    @Test
    fun planCardHeaderTapStillTogglesInline() {
        val opened = mutableListOf<ConversationItem.Activity>()
        show(
            activity("submit_plan", """{"plan":"Step one of the plan"}""", output = null),
            onOpenTool = { opened += it },
        )
        // A plan starts expanded, exactly as before.
        compose.onNodeWithContentDescription(text(R.string.remote_hide_output)).assertExists()
        compose.onNodeWithText("Step one of the plan", substring = true).assertExists()
        compose.onNodeWithTag("toolCardToggle").assertDoesNotExist()
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_show_output)).assertExists()
        compose.onNodeWithText("Step one of the plan", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.onNodeWithText("Step one of the plan", substring = true).assertExists()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun editCardShowsDiffPreviewAndCounts() {
        show(
            activity(
                "edit",
                """{"path":"src/a.kt","edits":[{"oldText":"val answer = 41","newText":"val answer = 42"}]}""",
                details = ToolDetails(patch, firstChangedLine = 5, truncated = false),
            ),
            onOpenTool = {},
        )
        compose.onNodeWithTag("diffView").assertExists()
        compose.onNodeWithTag("diffView").assert(hasAnyDescendant(hasText("val answer = 42")))
        compose.onNodeWithTag("toolCardDiffCounts", useUnmergedTree = true).assertTextEquals("+1 −1")
    }

    @Test
    fun errorCardAsksPiToFixWithTheOutputMessage() {
        val asked = mutableListOf<String>()
        show(
            activity("bash", """{"command":"false"}""", state = "error", output = "exit 1", outputMessageId = "result-7"),
            onOpenTool = {},
            onAskToFix = { asked += it },
        )
        compose.onNodeWithTag("askToFix").performClick()
        compose.waitForIdle()
        assertEquals(listOf("result-7"), asked)
    }

    @Test
    fun errorCardWithoutOutputMessageHasNoAskToFix() {
        show(
            activity("bash", """{"command":"false"}""", state = "error", output = "exit 1"),
            onAskToFix = {},
        )
        compose.onNodeWithTag("askToFix").assertDoesNotExist()
    }

    @Test
    fun subagentRowTapPassesItsIndex() {
        val opened = mutableListOf<Pair<String, Int>>()
        val subagent =
            ConversationItem.Subagent(
                id = "subagent-1",
                sourceId = "message-9",
                mode = "parallel",
                agents =
                    listOf(
                        AgentProgress("explorer", "succeeded", "Found it", null),
                        AgentProgress(
                            "reviewer",
                            "running",
                            null,
                            "reading files",
                            task = "Review the diff",
                            sessionId = "child-2",
                            step = 2,
                            usage = AgentUsage(1200, 300, 0, 0, 1500, 3, 0.012),
                        ),
                    ),
                legacy = false,
                output = null,
                truncated = false,
                toolCallId = "call_sub",
            )
        show(subagent, onOpenAgent = { item, index -> opened += item.id to index })
        compose.onAllNodesWithTag("subagentAgent").assertCountEquals(2)
        compose.onNodeWithText("Review the diff", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("subagentUsage", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("subagentAgent")[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf("subagent-1" to 1), opened)
    }

    @Test
    fun assistantBubbleShowsUsageCaption() {
        show(
            ConversationItem.Bubble(
                id = "bubble-1",
                sourceId = "message-1",
                role = "assistant",
                author = "model",
                text = "Hello",
                quote = null,
                truncated = false,
                timestamp = null,
                usage = MessageUsage(1200, 350, 0, 0, 1550, 0.012),
            )
        )
        compose.onNodeWithTag("messageUsage").assertExists()
        compose.onNodeWithTag("messageUsage").assert(hasText("$0.012", substring = true))
    }

    @Test
    fun userBubbleHasNoUsageCaption() {
        show(
            ConversationItem.Bubble(
                id = "bubble-2",
                sourceId = "message-2",
                role = "user",
                author = null,
                text = "Hi",
                quote = null,
                truncated = false,
                timestamp = null,
            )
        )
        compose.onNodeWithTag("messageUsage").assertDoesNotExist()
    }
}
