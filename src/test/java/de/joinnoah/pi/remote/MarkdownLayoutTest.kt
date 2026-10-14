package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun listItemsSitOnConsecutiveLines() {
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) { MarkdownText("- one\n- two\n- three") } } }
        val one = compose.onNodeWithText("one").fetchSemanticsNode().boundsInRoot
        val two = compose.onNodeWithText("two").fetchSemanticsNode().boundsInRoot
        val three = compose.onNodeWithText("three").fetchSemanticsNode().boundsInRoot
        // Each item is one row of one line; a blank line between items would double the step.
        assertEquals(one.height, two.top - one.top, 1f)
        assertEquals(one.height, three.top - two.top, 1f)
    }

    @Test fun tableLinksTakeTheOutgoingBubbleColour() {
        val link = Color(0xFF1B5E20)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    CompositionLocalProvider(LocalMarkdownLinkColor provides link) {
                        MarkdownText("| Seite |\n| --- |\n| [Site](https://example.org) |")
                    }
                }
            }
        }
        val spans = compose.onNodeWithText("Site").fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles
        assertTrue(spans.any { it.item.color == link })
    }

    @Test fun codeBlockHasADragHandleOnlyWhereDragIsOn() {
        val label = context.getString(R.string.remote_drag_code)
        compose.setContent { MaterialTheme { Box(Modifier.width(400.dp)) { MarkdownText("```kotlin\nval a = 1\n```") } } }
        compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("codeBlock"))).assertExists()
    }

    @Test fun toolCardCodeBlocksHaveNoDragHandle() {
        val label = context.getString(R.string.remote_drag_code)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp)) {
                    CompositionLocalProvider(LocalCodeBlockDrag provides false) {
                        MarkdownText("```kotlin\nval a = 1\n```")
                    }
                }
            }
        }
        compose.onNodeWithTag("codeBlock").assertExists()
        compose.onNode(hasContentDescription(label)).assertDoesNotExist()
    }

    @Test fun dragCarriesACappedCopyOfAHugeBlock() {
        val transfer = codeDragTransfer("x".repeat(CLIPBOARD_MAX_CHARS + 10), "kotlin")
        assertEquals(CLIPBOARD_MAX_CHARS, transfer.clipData.getItemAt(0).text.length)
    }

    @Test fun proseNeverNeedsTheWideColumn() {
        assertFalse(hasWideContent("Ein Satz mit Text, ein zweiter Satz, und ein Gedanke - ohne Code."))
        assertFalse(hasWideContent("- eine Liste\n> ein Zitat\n\nAbsatz"))
        assertTrue(hasWideContent("Text\n\n```\nval a = 1\n```"))
        assertTrue(hasWideContent("A | B\n--- | ---\n1 | 2"))
        assertTrue(hasWideContent("![Bild](images/a.png)"))
    }
}
