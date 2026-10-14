package de.joinnoah.pi.remote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    @Test fun dragginACodeBlockCarriesItsTextAsPlainClip() {
        val transfer = codeDragTransfer(code, "kotlin")
        assertEquals(code, transfer.clipData.getItemAt(0).text.toString())
        assertEquals("kotlin", transfer.clipData.description.label.toString())
    }

    @Test fun listsAndQuotesRenderTheirMarkers() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(400.dp)) { MarkdownText("- [ ] offen\n  - [x] erledigt\n> zitat") } }
        }
        // The list is one text with a line per item, so the items match as parts of it.
        compose.onNodeWithText("☐ offen", substring = true).assertExists()
        compose.onNodeWithText("☑ erledigt", substring = true).assertExists()
        compose.onNodeWithText("zitat").assertExists()
    }
}
