package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun linksInsideEmphasisKeepTheirAnnotationsAndStyle() {
        for (marker in listOf("**", "*")) {
            val text = inlineMarkdown(
                "$marker[PR #30 – Render Markdown tables in chat](https://github.com/robinrehbein/pocketpi/pull/30)$marker.",
                Color.Blue,
            )
            assertEquals("PR #30 – Render Markdown tables in chat.", text.text)
            val link = text.getLinkAnnotations(0, text.length).single()
            assertEquals("https://github.com/robinrehbein/pocketpi/pull/30", (link.item as LinkAnnotation.Url).url)
            assertEquals(0, link.start)
            assertEquals(text.length - 1, link.end)
            org.junit.Assert.assertTrue(text.spanStyles.any {
                it.start == 0 && it.end == text.length - 1 &&
                    if (marker == "**") it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold
                    else it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic
            })
        }
    }

    @Test
    fun emphasizedBareUrlsBecomeLinksButInlineCodeStaysLiteral() {
        val text = inlineMarkdown("**https://example.com.** and `[Docs](https://example.com)`", Color.Blue)
        assertEquals("https://example.com. and [Docs](https://example.com)", text.text)
        assertEquals(listOf("https://example.com"), text.getLinkAnnotations(0, text.length).map {
            (it.item as LinkAnnotation.Url).url
        })
    }

    @Test
    fun markdownAndBareUrlsBecomeLinksWithoutTrailingPunctuation() {
        val text = inlineMarkdown("[Docs](https://example.com/docs) and https://example.com/help.", Color.Blue)

        assertEquals("Docs and https://example.com/help.", text.text)
        assertEquals(
            listOf("https://example.com/docs", "https://example.com/help"),
            text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }
}
