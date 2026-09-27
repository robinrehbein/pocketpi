package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {
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
