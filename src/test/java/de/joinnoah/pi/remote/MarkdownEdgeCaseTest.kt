package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.*
import org.junit.Test

class MarkdownEdgeCaseTest {
    @Test fun formattedLinkLabelsAndBalancedUrlParentheses() {
        val text = inlineMarkdown("[**Docs**](https://example.com/a_(b)) and https://example.com/a_(b).", Color.Blue)
        assertEquals("Docs and https://example.com/a_(b).", text.text)
        assertEquals(listOf("https://example.com/a_(b)", "https://example.com/a_(b)"), text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url })
    }

    @Test fun escapesAndMultipleBacktickCodeSpansStayLiteral() {
        val text = inlineMarkdown("\\*literal\\* and ``a`|b https://example.com``", Color.Blue)
        assertEquals("*literal* and a`|b https://example.com", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertEquals("**unvollständig", inlineMarkdown("**unvollständig", Color.Blue).text)
    }

    @Test fun emphasisCanContainCodeAndTripleEmphasis() {
        assertEquals("a*b und wichtig", inlineMarkdown("**`a*b`** und ***wichtig***", Color.Blue).text)
    }

    @Test fun nestedEmphasisKeepsOuterAndInnerStyles() {
        for (source in listOf("*outer **bold** tail*", "**outer *italic* tail**")) {
            val text = inlineMarkdown(source, Color.Blue)
            assertEquals(if (source.startsWith("**")) "outer italic tail" else "outer bold tail", text.text)
            assertTrue(text.spanStyles.any { it.start == 0 && it.end == text.length })
            assertTrue(text.spanStyles.any { it.start == 6 && it.end < text.length })
        }
    }

    @Test fun adjacentNestedEmphasisClosersShareTheirMarkerRun() {
        for (source in listOf("**outer *inner***", "*outer **inner***")) {
            val text = inlineMarkdown(source, Color.Blue)
            assertEquals("outer inner", text.text)
            assertTrue(text.spanStyles.any { it.start == 0 && it.end == text.length })
            assertTrue(text.spanStyles.any { it.start == 6 && it.end == text.length })
        }
    }

    @Test fun mixedMarkersAreNotATableBoundary() {
        val table = markdownBlocks("A | B\n--- | ---\none\n---***\ntwo | three").single() as MarkdownBlock.Table
        assertEquals(listOf(listOf("one"), listOf("---***"), listOf("two", "three")), table.rows)
    }

    @Test fun escapedUrlParenthesesAreDecoded() {
        val text = inlineMarkdown("[Docs](https://example.com/a\\(b\\))", Color.Blue)
        assertEquals("Docs", text.text)
        assertEquals("https://example.com/a(b)", (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
    }

    @Test fun rowsWithMissingSeparatorsRemainInTableUntilBlockBoundary() {
        val blocks = markdownBlocks("A | B\n--- | ---\none\ntwo | three\n# Heading")
        val table = blocks.first() as MarkdownBlock.Table
        assertEquals(listOf(listOf("one"), listOf("two", "three")), table.rows)
        assertEquals(MarkdownBlock.Line("# Heading"), blocks.last())
    }

    @Test fun incompleteLinksNeverThrowForAnyStreamingPrefix() {
        val source = "[**Dokumentation**](https://example.com/a_(b))"
        for (end in 0..source.length) inlineMarkdown(source.take(end), Color.Blue)
    }

    @Test fun fencesDoNotConsumeInlineBackticksAndSupportLongAndTildeFences() {
        assertEquals(listOf(MarkdownSegment("Text ```inline``` bleibt", false)), markdownSegments("Text ```inline``` bleibt"))
        assertEquals(listOf(MarkdownSegment("A | B\n--- | ---", true, "md")), markdownSegments("~~~md\nA | B\n--- | ---\n~~~"))
        assertEquals(listOf(MarkdownSegment("```\nA | B", true, "md")), markdownSegments("````md\n```\nA | B\n````"))
    }

    @Test fun streamingCodeRetainsFirstContentLine() {
        assertEquals(listOf(MarkdownSegment("erste Zeile", true, "kotlin", false)), markdownSegments("```kotlin\nerste Zeile"))
        assertEquals(listOf(MarkdownSegment("erste\nzweite", true, null, false)), markdownSegments("```\nerste\nzweite"))
    }
}
