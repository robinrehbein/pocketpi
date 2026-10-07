package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class MarkdownImageTest {
    private fun local(destination: String) = (markdownImageTarget(destination) as? MarkdownImageTarget.Local)?.path

    @Test
    fun relativeAndAbsolutePathsAreNormalised() {
        assertEquals("build/shot.png", local("build/shot.png"))
        assertEquals("build/shot.png", local("./build/shot.png"))
        assertEquals("shot.png", local("build/../shot.png"))
        assertEquals("a/b.PNG", local("a/./b.PNG"))
        assertEquals("/Users/robin/p/shot.png", local("/Users/robin/p/shot.png"))
        assertEquals("/Users/p.png", local("/Users/robin/../p.png"))
        assertEquals(".cache/mock.svg", local(".cache/mock.svg"))
    }

    @Test
    fun angleFormTitlesAndPercentEscapes() {
        assertEquals("my shots/a b.png", local("<my shots/a b.png>"))
        assertEquals("shot.png", local("shot.png \"The title\""))
        assertEquals("shot.png", local("<shot.png> 'title'"))
        assertEquals("shot.png", local("shot.png (title)"))
        assertEquals("a b/ü.png", local("a%20b/%C3%BC.png"))
        // Decoded once only: %2520 stays %20.
        assertEquals("a%20b.png", local("a%2520b.png"))
        assertEquals("a#b.png", local("a%23b.png"))
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("a%zz.png"))
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("a%FF.png"))
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("a%0A.png"))
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("a%5Cb.png"))
        // A destination with spaces but no angle brackets is not a destination.
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("a b.png"))
    }

    @Test
    fun everythingElseIsRejected() {
        for (bad in listOf(
            "../shot.png", "a/../../shot.png", "/../a.png", "shot.png?x=1", "shot.png#frag", "a\\b.png", "//host/a.png",
            "a//b.png", "build/", "shot.txt", "shot", "file:///etc/a.png", "data:image/png;base64,AAAA", "C:/a.png", "ftp://x/a.png", "javascript:alert(1)",
            "", "<>", "<a.png",
        )) assertEquals(bad, MarkdownImageTarget.Rejected, markdownImageTarget(bad))
        assertEquals(MarkdownImageTarget.Remote("https://x.test/a.png"), markdownImageTarget("https://x.test/a.png"))
        assertEquals(MarkdownImageTarget.Remote("http://x.test/a.png"), markdownImageTarget("http://x.test/a.png"))
    }

    @Test
    fun extensionsAreCaseInsensitive() {
        for (name in listOf("a.png", "a.JPG", "a.jpeg", "a.WebP", "a.gif", "a.SVG")) assertNotNull(name, local(name))
        assertNull(local("a.bmp"))
        assertNull(local("a.svgz"))
    }

    @Test
    fun aLineOfOnlyImagesBecomesImageBlocks() {
        assertEquals(
            listOf(MarkdownBlock.Image("Result", "build/shot.png")),
            markdownBlocks("![Result](build/shot.png)"),
        )
        assertEquals(
            listOf(MarkdownBlock.Image("a", "a.png"), MarkdownBlock.Image("b", "b.png")),
            markdownBlocks("  ![a](a.png) ![b](./b.png)"),
        )
        assertEquals(
            listOf(MarkdownBlock.Line("before"), MarkdownBlock.Image("x", "x.png"), MarkdownBlock.Line("after")),
            markdownBlocks("before\n![x](x.png)\nafter"),
        )
    }

    @Test
    fun otherLinesStayLinesAndShowTheirAltText() {
        for (line in listOf(
            "text ![a](a.png)", "- ![a](a.png)", "![a](a.png) text", "    ![a](a.png)", "![a](https://x.test/a.png)",
            "![a](../a.png)", "![a](a.txt)", "![a](a.png", "`![a](a.png)`", "![a][ref]",
        )) assertEquals(line, listOf(MarkdownBlock.Line(line)), markdownBlocks(line))
        val link = Color.Blue
        assertEquals("see Result here", inlineMarkdown("see ![Result](a.png) here", link).text)
        assertEquals("a.png", inlineMarkdown("![](a.png)", link).text)
        assertEquals("Logo", inlineMarkdown("![Logo](https://x.test/a.png)", link).text)
        assertEquals("gone", inlineMarkdown("![gone](../../a.png)", link).text)
        assertEquals("![a](a.png)", inlineMarkdown("`![a](a.png)`", link).text)
        assertEquals("shown", markdownImageAltText(MarkdownBlock.Image("shown", "a/b.png")))
        assertEquals("b.png", markdownImageAltText(MarkdownBlock.Image("", "a/b.png")))
    }

    @Test
    fun imagesInsideFencedCodeAreCode() {
        val segments = markdownSegments("```\n![a](a.png)\n```")
        assertEquals(listOf(MarkdownSegment("![a](a.png)", true)), segments)
    }
}
