package de.joinnoah.pi.remote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A window wide enough for the tablet widths the test lays out; the default one is 320dp.
@Config(sdk = [35], qualifiers = "w1400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationWidthUiTest {
    @get:Rule val compose = createComposeRule()

    private var density = Density(1f)
    private val prose = "Ein Satz mit etwas Text, der ruhig in mehreren Zeilen umbrechen darf, damit er die Spalte füllt."
    private val code = "val answer = 42"
    private val message = "$prose\n\n```kotlin\n$code\n```"

    private fun bubble(text: String) =
        ConversationItem.Bubble(
            id = "b1", sourceId = "m1", role = "assistant", author = null, text = text,
            quote = null, truncated = false, timestamp = null,
        )

    private fun show(width: Dp, text: String = message) {
        val item = bubble(text)
        compose.setContent {
            density = LocalDensity.current
            MaterialTheme {
                Box(Modifier.width(width)) {
                    ConversationItemColumn(item) {
                        ConversationMessage(item, "hidden", thinkingActive = false, onQuote = {})
                    }
                }
            }
        }
    }

    private fun Dp.px() = with(density) { roundToPx() }

    @Test fun codeBlockGoesBeyondTheProseColumnInAWideChat() {
        show(1280.dp)
        val codeWidth = compose.onNodeWithTag("codeBlock").fetchSemanticsNode().size.width
        val proseWidth = compose.onNodeWithText(prose).fetchSemanticsNode().size.width
        assertTrue("code $codeWidth should be wider than prose $proseWidth", codeWidth > proseWidth)
        assertTrue(proseWidth <= PROSE_READING_WIDTH.px())
        assertTrue(codeWidth <= WIDE_READING_WIDTH.px())
    }

    @Test fun proseAndWideMessagesShareTheProseColumnsStartEdge() {
        val plain = bubble(prose).copy(id = "plain")
        val wide = bubble(message).copy(id = "wide", text = "Kurz.\n\n```kotlin\n$code\n```")
        compose.setContent {
            density = LocalDensity.current
            MaterialTheme {
                Column(Modifier.width(1280.dp)) {
                    ConversationItemColumn(plain) { ConversationMessage(plain, "hidden", thinkingActive = false, onQuote = {}) }
                    ConversationItemColumn(wide) { ConversationMessage(wide, "hidden", thinkingActive = false, onQuote = {}) }
                }
            }
        }
        val proseLeft = compose.onNodeWithText(prose).getUnclippedBoundsInRoot().left
        val shortLeft = compose.onNodeWithText("Kurz.").getUnclippedBoundsInRoot().left
        // No zig-zag: the wide message's text starts where the prose message's does.
        assertEquals(proseLeft, shortLeft)
    }

    @Test fun readingColumnsShareTheProseColumnsEdgeAndGrowAwayFromIt() {
        // 1280 wide, prose 700: the prose column runs from 290 to 990.
        assertEquals(ReadingColumn(290, 700), readingColumnBounds(1280, 700, 700, outgoing = false))
        assertEquals(ReadingColumn(290, 990), readingColumnBounds(1280, 1120, 700, outgoing = false))
        assertEquals(ReadingColumn(0, 1120), readingColumnBounds(2240, 1120, 2240, outgoing = false))
        // A phone: everything takes the whole width.
        assertEquals(ReadingColumn(0, 400), readingColumnBounds(400, 1120, 700, outgoing = false))
        // Outgoing items share the prose column's end edge and grow toward the start.
        assertEquals(ReadingColumn(290, 700), readingColumnBounds(1280, 700, 700, outgoing = true))
        assertEquals(ReadingColumn(0, 990), readingColumnBounds(1280, 1120, 700, outgoing = true))
        assertEquals(ReadingColumn(0, 400), readingColumnBounds(400, 700, 700, outgoing = true))
    }

    @Test fun phoneWidthKeepsCodeAndProseInsideTheScreen() {
        show(400.dp)
        assertTrue(compose.onNodeWithTag("codeBlock").fetchSemanticsNode().size.width <= 400.dp.px())
        assertTrue(compose.onNodeWithText(prose).fetchSemanticsNode().size.width <= 400.dp.px())
    }

    @Test fun proseWithoutCodeStaysInTheReadingColumn() {
        show(1280.dp, text = prose)
        compose.onNodeWithTag("codeBlock").assertDoesNotExist()
        assertTrue(compose.onNodeWithText(prose).fetchSemanticsNode().size.width <= PROSE_READING_WIDTH.px())
    }

    @Test fun copyButtonOnTheCodeBlockCopiesTheCodeAlone() {
        show(1280.dp)
        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.remote_copy_code)
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("codeBlock"))).performClick()
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun draggingACodeBlockCarriesItsTextAsAGlobalPlainClip() {
        val transfer = codeDragTransfer(code, "kotlin")
        assertEquals(code, transfer.clipData.getItemAt(0).text.toString())
        assertEquals("kotlin", transfer.clipData.description.label.toString())
        // Other apps may take the drop; the chat's own drop target ignores it.
        assertEquals(DRAG_TEXT_FLAGS, transfer.flags)
        assertSame(OwnDrag, transfer.localState)
    }

    @Test fun tasksSayWhetherTheyAreDoneAndMarkersAreNotRead() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(400.dp)) { MarkdownText("- [ ] offen\n- [x] erledigt\n- punkt") } }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText("offen")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.remote_markdown_task_open)))
        compose.onNodeWithText("erledigt")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.remote_markdown_task_done)))
        compose.onNodeWithText("punkt").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        for (marker in listOf("☐", "☑", "•"))
            compose.onNodeWithText(marker).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test fun aCodeBlockInAPrimaryContainerBubbleKeepsAVisibleEdge() {
        var outline = Color.Unspecified
        var bubble = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                outline = MaterialTheme.colorScheme.outlineVariant
                bubble = MaterialTheme.colorScheme.primaryContainer
                Box(Modifier.width(400.dp).background(bubble).padding(16.dp)) { MarkdownText("```\n$code\n```") }
            }
        }
        val image = compose.onNodeWithTag("codeBlock").captureToImage().toPixelMap()
        val edge = image[0, image.height / 2]
        fun close(a: Color, b: Color) =
            abs(a.red - b.red) < 0.08f && abs(a.green - b.green) < 0.08f && abs(a.blue - b.blue) < 0.08f
        assertTrue("edge $edge should be the outline $outline", close(edge, outline))
        assertTrue(!close(edge, bubble))
    }

    @Test fun listsAndQuotesRenderTheirMarkers() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(400.dp)) { MarkdownText("- [ ] offen\n  - [x] erledigt\n> zitat") } }
        }
        // Each item is a row with its marker and its text as separate nodes.
        compose.onNodeWithText("☐").assertExists()
        compose.onNodeWithText("offen").assertExists()
        compose.onNodeWithText("☑").assertExists()
        compose.onNodeWithText("erledigt").assertExists()
        compose.onNodeWithText("zitat").assertExists()
    }
}
