package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ToolDetailScreenUiTest {
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

    private fun edit(truncated: Boolean = false, toolCallId: String? = "call_edit_1") =
        ConversationItem.Activity(
            id = "tool-1",
            sourceId = "message-1",
            name = "edit",
            arguments = """{"path":"src/a.kt","edits":[{"oldText":"val answer = 41","newText":"val answer = 42"}]}""",
            output = "Successfully replaced 1 block in src/a.kt.",
            state = "complete",
            truncated = truncated,
            toolCallId = toolCallId,
            details = ToolDetails(patch, firstChangedLine = 5, truncated = false),
        )

    @Test
    fun largeEditArgumentsStartAsAPreviewUntilShowAll() {
        val old = (1..400).joinToString("\\n") { "old line $it" }
        val new = (1..400).joinToString("\\n") { "new line $it" }
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = edit().copy(arguments = """{"path":"src/a.kt","edits":[{"oldText":"$old","newText":"$new"}]}""", details = null),
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
        // Rows are lazy items inside CodeBlock's bounded-height list: scroll each one into view
        // rather than expecting every row to be composed up front.
        fun rows(ancestorTag: String) = compose.onNode(hasTestTag("codeBlockRows") and hasAnyAncestor(hasTestTag(ancestorTag)))
        rows("toolDetailEditBefore-0").performScrollToNode(hasText("old line 200"))
        compose.onNodeWithTag("toolDetailEditBefore-0").assert(hasAnyDescendant(hasText("old line 200")))
        compose.onNodeWithTag("toolDetailEditBefore-0").assert(!hasAnyDescendant(hasText("old line 201")))
        compose.onNodeWithTag("toolDetailArgumentsToggle").performScrollTo().performClick()
        rows("toolDetailEditBefore-0").performScrollToNode(hasText("old line 400"))
        compose.onNodeWithTag("toolDetailEditBefore-0").assert(hasAnyDescendant(hasText("old line 400")))
        rows("toolDetailEditAfter-0").performScrollToNode(hasText("new line 400"))
        compose.onNodeWithTag("toolDetailEditAfter-0").assert(hasAnyDescendant(hasText("new line 400")))
    }

    @Test
    fun editShowsBothSidesAndTheUnifiedDiff() {
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = edit(),
                    download = null,
                    canLoadFullOutput = true,
                    canAskToFix = false,
                    onLoadFullOutput = {},
                    onCancelFullOutput = {},
                    onAskToFix = {},
                    onQuote = {},
                    onClose = {},
                )
            }
        }
        compose.onNodeWithTag("toolDetail").assertExists()
        compose.onNodeWithTag("toolDetailArguments").assertExists()
        compose.onNodeWithTag("toolDetailEditBefore-0").assertExists()
        compose.onNodeWithTag("toolDetailEditAfter-0").assertExists()
        compose.onNodeWithTag("toolDetailEditBefore-0").assert(hasAnyDescendant(hasText("val answer = 41")))
        compose.onNodeWithTag("toolDetailEditAfter-0").assert(hasAnyDescendant(hasText("val answer = 42")))
        compose.onNodeWithTag("diffView").assertExists()
        compose.onNodeWithTag("diffView").assert(hasAnyDescendant(hasText("@@ -1,6 +1,6 @@")))
        compose.onNodeWithTag("diffView").assert(hasAnyDescendant(hasText("+")))
        compose.onNodeWithTag("diffView").assert(hasAnyDescendant(hasText("-")))
        // Output was not truncated, so there is nothing more to load.
        compose.onNodeWithTag("toolDetailLoadFull").assertDoesNotExist()
        compose.onNodeWithTag("toolDetailAskFix").assertDoesNotExist()
    }

    @Test
    fun loadFullOutputNeedsTruncationTheCapabilityAndACallId() {
        var item by mutableStateOf(edit(truncated = true))
        var capable by mutableStateOf(true)
        var loads = 0
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = item,
                    download = null,
                    canLoadFullOutput = capable,
                    canAskToFix = true,
                    onLoadFullOutput = { loads++ },
                    onCancelFullOutput = {},
                    onAskToFix = {},
                    onQuote = {},
                    onClose = {},
                )
            }
        }
        compose.onNodeWithTag("toolDetailLoadFull").assertExists()
        compose.onNodeWithTag("toolDetailAskFix").assertExists()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("toolDetailLoadFull"))).performClick()
        assertEquals(1, loads)

        capable = false
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailLoadFull").assertDoesNotExist()

        capable = true
        item = edit(truncated = false)
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailLoadFull").assertDoesNotExist()

        item = edit(truncated = true, toolCallId = null)
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailLoadFull").assertDoesNotExist()
    }

    @Test
    fun completedDownloadReplacesTheLoadButton() {
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = edit(truncated = true),
                    download =
                        ToolOutputDownload("call_edit_1", 12, 12, "full output\n", truncated = false, isError = false, failure = null),
                    canLoadFullOutput = true,
                    canAskToFix = false,
                    onLoadFullOutput = {},
                    onCancelFullOutput = {},
                    onAskToFix = {},
                    onQuote = {},
                    onClose = {},
                )
            }
        }
        compose.onNodeWithTag("toolDetailLoadFull").assertDoesNotExist()
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasText("full output"))
        compose.onNodeWithText("full output").assertIsDisplayed()
    }

    @Test
    fun anUnsupportedAnswerStaysVisibleAfterTheCapabilityDrops() {
        var download by mutableStateOf<ToolOutputDownload?>(ToolOutputDownload("call_edit_1", 0, null, null, false, false, null))
        var capable by mutableStateOf(true)
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = edit(truncated = true),
                    download = download,
                    canLoadFullOutput = capable,
                    canAskToFix = false,
                    onLoadFullOutput = {},
                    onCancelFullOutput = { cancelled = true },
                    onAskToFix = {},
                    onQuote = {},
                    onClose = {},
                )
            }
        }
        val cancel = text(R.string.remote_tool_detail_cancel_load)
        capable = false
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasText(cancel))
        compose.onNodeWithText(cancel).performClick()
        assertTrue(cancelled)

        download = ToolOutputDownload("call_edit_1", 0, null, null, false, false, ToolOutputFailure.UNSUPPORTED)
        compose.waitForIdle()
        val message = text(R.string.remote_tool_detail_failure_unsupported)
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasText(message))
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_tool_detail_load_full)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_tool_detail_retry)).assertDoesNotExist()
    }

    @Test
    fun closeButtonCallsOnClose() {
        var closed = false
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = edit(),
                    download = null,
                    canLoadFullOutput = false,
                    canAskToFix = false,
                    onLoadFullOutput = {},
                    onCancelFullOutput = {},
                    onAskToFix = {},
                    onQuote = {},
                    onClose = { closed = true },
                )
            }
        }
        compose.onNodeWithTag("toolDetailClose").performClick()
        compose.waitForIdle()
        assertTrue(closed)
    }

    /** The match counter showing "1 of [total]" in any locale: both numbers, and nothing else numeric. */
    private fun count(total: String) =
        hasTestTag("toolDetailSearchCount") and
            SemanticsMatcher("counts 1 and $total") { node ->
                val text = node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString("").orEmpty()
                Regex("\\d+").findAll(text).map { it.value }.toList() == listOf("1", total)
            }

    private fun bash(output: String) =
        ConversationItem.Activity(
            id = "tool-2",
            sourceId = "message-2",
            name = "bash",
            arguments = """{"command":"make test"}""",
            output = output,
            state = "streaming",
            truncated = false,
            toolCallId = "call_bash_1",
        )

    @Test
    fun outputChangingUnderAnActiveSearchKeepsWorking() {
        var item by mutableStateOf(bash((1..40).joinToString("\n") { "line $it: a long error message here" }))
        compose.setContent {
            MaterialTheme {
                ToolDetailScreen(
                    item = item,
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
        compose.waitUntil(5_000) { compose.onAllNodes(count("40")).fetchSemanticsNodes().isNotEmpty() }

        // A tail window: every row is now shorter than the old match offsets.
        item = bash((1..40).joinToString("\n") { "x$it" } + "\nmessage")
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(count("1")).fetchSemanticsNodes().isNotEmpty() }

        item = bash("ok")
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetailSearchCount").assertExists()
    }

    @Test
    fun aLongCjkLineAtLargeFontScaleRenders() {
        val cjk = "漢".repeat(MAX_CODE_LINE_CHARS * 2)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3.5f, fontScale = 2f)) {
                MaterialTheme {
                    ToolDetailScreen(
                        item =
                            bash(cjk)
                                .copy(
                                    name = "write",
                                    arguments = """{"path":"a.txt","content":"$cjk"}""",
                                    state = "complete",
                                ),
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
        }
        compose.onNodeWithTag("toolDetail").assertExists()
        compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasTestTag("toolDetailSearch"))
        compose.onNodeWithTag("toolDetailSearch").assertExists()
    }
}
