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

    private val opened = mutableListOf<FileTarget>()
    private val opener =
        FileOpener(SessionFolderHints(toolPaths = setOf("Makefile")), true, { opened += it }, { error("must not copy") })

    private fun clickable(text: androidx.compose.ui.text.AnnotatedString) =
        text.getLinkAnnotations(0, text.length).filter { it.item is LinkAnnotation.Clickable }

    @Test
    fun aPathWithALineBecomesAFileLink() {
        val text = inlineMarkdown("The bug is in `src/Foo.kt:42` and src/Bar.kt:7-9.", Color.Blue, opener)
        assertEquals("The bug is in src/Foo.kt:42 and src/Bar.kt:7-9.", text.text)
        val links = clickable(text)
        assertEquals(listOf("src/Foo.kt:42", "src/Bar.kt:7-9"), links.map { text.text.substring(it.start, it.end) })
        assertEquals("file:src/Foo.kt:42", (links[0].item as LinkAnnotation.Clickable).tag)
        (links[0].item as LinkAnnotation.Clickable).linkInteractionListener?.onClick(links[0].item)
        (links[1].item as LinkAnnotation.Clickable).linkInteractionListener?.onClick(links[1].item)
        assertEquals(listOf(FileTarget("src/Foo.kt", LineSelection(42)), FileTarget("src/Bar.kt", LineSelection(7, 9))), opened)
    }

    @Test
    fun urlsProseAndUnknownNamesAreNoFileLinks() {
        for (prose in listOf(
            "See https://example.com/src/Foo.kt:42 for details.",
            "Meet at 10:30, port localhost:8080 or example.com:443.",
            "The ratio was 3:2 and Note: src/Foo.kt is fine.",
            "Run Dockerfile:3 now",
            "Not a link: foo/bar.kt:x or (src/Foo.kt)",
        )) {
            assertEquals(prose, emptyList<Any>(), clickable(inlineMarkdown(prose, Color.Blue, opener)))
        }
        // The URL stays a URL link.
        assertEquals(1, inlineMarkdown("https://example.com/src/Foo.kt:42", Color.Blue, opener).getLinkAnnotations(0, 33).size)
        // A name a tool used links without a known extension.
        assertEquals(1, clickable(inlineMarkdown("see Makefile:12", Color.Blue, opener)).size)
        // Without an opener (outside a chat, user text) nothing links.
        assertEquals(emptyList<Any>(), clickable(inlineMarkdown("`src/Foo.kt:42`", Color.Blue)))
    }
}
