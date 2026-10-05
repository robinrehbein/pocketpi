package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    @Test fun optionalPipesAndAlignment() {
        val table = markdownBlocks("Name | Mitte | Ende\n:--- | :---: | ---:\n| eins | zwei | drei |").single() as MarkdownBlock.Table
        assertEquals(listOf("Name", "Mitte", "Ende"), table.header)
        assertEquals(listOf(TextAlign.Start, TextAlign.Center, TextAlign.End), table.alignment)
        assertEquals(listOf(listOf("eins", "zwei", "drei")), table.rows)
    }

    @Test fun escapedAndCodePipesDoNotSplitCells() {
        assertEquals(listOf("a|b", "`c|d`", "``e`|f``"), tableCells("| a\\|b | `c|d` | ``e`|f`` |"))
        assertEquals(listOf("a\\\\", "b"), tableCells("a\\\\|b"))
        assertNull(tableCells("`a|b`"))
        assertEquals(listOf("`a", "b"), tableCells("`a|b"))
    }

    @Test fun inlineStylesAndLinksSurvive() {
        val table = markdownBlocks("A | B\n--- | ---\n**fett** | [Link](https://example.com)").single() as MarkdownBlock.Table
        assertEquals("fett", inlineMarkdown(table.rows[0][0], Color.Blue).text)
        val link = inlineMarkdown(table.rows[0][1], Color.Blue)
        assertEquals(1, link.getLinkAnnotations(0, link.length).size)
    }

    @Test fun incompleteAndLookalikeHeadersStayPlain() {
        for (text in listOf("A | B", "A | B\n--- | --", "A | B\n---", "A | B\ntext | text")) {
            assertTrue(markdownBlocks(text).all { it is MarkdownBlock.Line })
            assertEquals(text.lines(), markdownBlocks(text).map { (it as MarkdownBlock.Line).text })
        }
    }

    @Test fun streamingRowsNeverDiscardExtraOrMissingCells() {
        val prefix = "A | B\n--- | ---\n"
        val row = "| langer deutscher Text | weiterer Inhalt | zusätzliche Zelle |"
        for (length in 0..row.length) {
            val blocks = markdownBlocks(prefix + row.take(length))
            val table = blocks.first() as MarkdownBlock.Table
            if (length > 1) assertEquals(tableCells(row.take(length)), table.rows.firstOrNull())
        }
        val table = markdownBlocks(prefix + "eins | zwei | drei\nvier |\n\nNachher").first() as MarkdownBlock.Table
        assertEquals(listOf(listOf("eins", "zwei", "drei"), listOf("vier")), table.rows)
    }
}
