package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class MarkdownArtifactTest {
    private fun local(destination: String) = (markdownArtifactTarget(destination) as? MarkdownImageTarget.Local)?.path

    @Test
    fun pathRulesMatchTheImageRules() {
        assertEquals("out/report.html", local("out/report.html"))
        assertEquals("out/report.HTM", local("./out/report.HTM"))
        assertEquals("report.html", local("out/../report.html"))
        assertEquals("/Users/robin/p/r.html", local("/Users/robin/p/r.html"))
        assertEquals("my dir/a b.html", local("<my dir/a b.html>"))
        assertEquals("a b.html", local("a%20b.html"))
        assertEquals("a%20b.html", local("a%2520b.html"))
        assertEquals("r.html", local("r.html \"The title\""))
        assertEquals("my report.html", local("my report.html"))
        for (bad in listOf(
            "../r.html", "a/../../r.html", "/../r.html", "r.html?x=1", "r.html#frag", "a\\b.html", "//host/r.html", "a//b.html",
            "build/", "r.txt", "r.png", "r", "file:///etc/r.html", "data:text/html,x", "javascript:alert(1)", "a%zz.html",
            "a%FF.html", "a%0A.html", "a%5Cb.html", "", "<>", "<r.html",
        )) assertEquals(bad, MarkdownImageTarget.Rejected, markdownArtifactTarget(bad))
        assertEquals(MarkdownImageTarget.Remote("https://x.test/r.html"), markdownArtifactTarget("https://x.test/r.html"))
        // Images keep their own extensions.
        assertEquals(MarkdownImageTarget.Rejected, markdownImageTarget("r.html"))
        assertEquals(MarkdownImageTarget.Rejected, markdownArtifactTarget("a.png"))
    }

    @Test
    fun scansLinksAndImages() {
        val link = scanMarkdownLink("x [Report](out/r.html) y", 2)!!
        assertEquals(MarkdownLinkToken("Report", "out/r.html", false, 22), link)
        assertEquals(MarkdownLinkToken("alt", "r.html", true, 15), scanMarkdownLink("![alt](r.html)", 0)?.copy(end = 15))
        assertNull(scanMarkdownLink("[open", 0))
        assertNull(scanMarkdownLink("[a] (b.html)", 0))
        assertNull(scanMarkdownLink("plain", 0))
        assertNull(scanMarkdownLink("[a](" + "x".repeat(5000) + ")", 0))
    }

    @Test
    fun aLoneLinkBecomesAnArtifactBlock() {
        assertEquals(
            listOf(MarkdownBlock.Artifact("Report", "out/r.html", "[Report](out/r.html)")),
            markdownBlocks("[Report](out/r.html)"),
        )
        assertEquals(
            listOf(MarkdownBlock.Artifact("Report", "out/r.html", "  - [Report](out/r.html)  ")),
            markdownBlocks("  - [Report](out/r.html)  "),
        )
        assertEquals(
            listOf(MarkdownBlock.Artifact("Report", "r.htm", "* [Report](./r.htm)")),
            markdownBlocks("* [Report](./r.htm)"),
        )
        assertEquals(
            listOf(MarkdownBlock.Artifact("Alt", "out/r.html", "![Alt](out/r.html)")),
            markdownBlocks("![Alt](out/r.html)"),
        )
        assertEquals(
            listOf(MarkdownBlock.Line("before"), MarkdownBlock.Artifact("x", "x.html", "[x](x.html)"), MarkdownBlock.Line("after")),
            markdownBlocks("before\n[x](x.html)\nafter"),
        )
    }

    @Test
    fun linksInProseRemoteAndOtherFilesStayText() {
        for (line in listOf(
            "Open [the report](out/r.html) now", "[a](a.html) [b](b.html)", "[Report](out/r.html) done", "see: [r](r.html)",
            "[Remote](https://x.test/r.html)", "[Doc](notes.md)", "[Img](shot.png)", "[Up](../r.html)", "[Frag](r.html#top)",
            "[Q](r.html?x=1)", "    [Code](r.html)", "\t[Code](r.html)", "- text [r](r.html)", "+ [r](r.html)", "- ", "[r](r.html",
        )) assertEquals(line, listOf(MarkdownBlock.Line(line)), markdownBlocks(line))
    }

    @Test
    fun anImageOnlyLineStaysAnImageForPictures() {
        assertEquals(listOf(MarkdownBlock.Image("x", "x.png")), markdownBlocks("![x](x.png)"))
    }

    @Test
    fun mermaidNeedsAClosedFenceAndTheFirstInfoWord() {
        val closed = markdownSegments("```mermaid\ngraph TD\nA-->B\n```").single()
        assertTrue(closed.isMermaid)
        assertEquals("mermaid", closed.language)
        assertEquals("graph TD\nA-->B", closed.text)
        assertTrue(markdownSegments("~~~Mermaid\ngraph TD\n~~~").single().isMermaid)
        assertTrue(markdownSegments("```mermaid title=x\ngraph TD\n```").single().isMermaid)
        assertTrue(markdownSegments("   ```MERMAID\ngraph TD\n```").single().isMermaid)
        // Still streaming: an ordinary code block.
        val open = markdownSegments("```mermaid\ngraph TD\nA-->").single()
        assertFalse(open.closed)
        assertFalse(open.isMermaid)
        assertTrue(open.code)
        assertFalse(markdownSegments("~~~mermaid\ngraph TD").single().isMermaid)
        // Other languages and look-alikes.
        assertFalse(markdownSegments("```mermaids\nx\n```").single().isMermaid)
        assertFalse(markdownSegments("```kotlin mermaid\nx\n```").single().isMermaid)
        assertFalse(markdownSegments("```\nx\n```").single().isMermaid)
        assertNull(markdownSegments("```\nx\n```").single().language)
        assertEquals("kotlin", markdownSegments("```kotlin\nx\n```").single().language)
        // A longer closing fence closes; a shorter one does not; prose around stays text.
        assertTrue(markdownSegments("````mermaid\nA\n`````").single().isMermaid)
        assertFalse(markdownSegments("````mermaid\nA\n```").single().isMermaid)
        val mixed = markdownSegments("before\n```mermaid\nA\n```\nafter")
        assertEquals(listOf(false, true, false), mixed.map { it.code })
        assertEquals(listOf(false, true, false), mixed.map { it.isMermaid })
        assertNull(mixed[0].language)
    }
}
