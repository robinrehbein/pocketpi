package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRunsTest {
    private fun runs(text: String, artifactCards: Boolean = false) = markdownRuns(markdownBlocks(text), artifactCards)

    @Test fun consecutiveLinesShareAParagraphAndBlankLinesSeparateThem() {
        assertEquals(
            listOf(
                MarkdownRun.Paragraph(listOf("eins", "zwei")),
                MarkdownRun.Paragraph(listOf("drei")),
            ),
            runs("eins\nzwei\n\ndrei"),
        )
    }

    @Test fun nestedAndPlusBulletsStepInByTwoSpaces() {
        val items = (runs("- eins\n  - zwei\n    - drei\n+ vier\n\t* fünf").single() as MarkdownRun.Items).items
        assertEquals(
            listOf(
                MarkdownListItem(0, "•", "eins"),
                MarkdownListItem(1, "◦", "zwei"),
                MarkdownListItem(2, "▪", "drei"),
                MarkdownListItem(0, "•", "vier"),
                MarkdownListItem(2, "▪", "fünf"),
            ),
            items,
        )
    }

    @Test fun taskItemsShowBoxesAndKeepTheirText() {
        val items = (runs("- [ ] offen\n- [x] erledigt\n- [X] auch erledigt\n- [ ]").single() as MarkdownRun.Items).items
        assertEquals(listOf("☐", "☑", "☑", "☐"), items.map { it.marker })
        assertEquals(listOf("offen", "erledigt", "auch erledigt", ""), items.map { it.text })
    }

    @Test fun aListIsNotASingleItemUnlessTheLineStartsWithAMarker() {
        assertNull(listItem("-kein Listenpunkt"))
        assertNull(listItem("1. nummeriert"))
        assertEquals(MarkdownListItem(0, "•", "Text"), listItem("- Text"))
    }

    @Test fun quotesGroupAndDropTheirMarker() {
        assertEquals(
            listOf(MarkdownRun.Quote(listOf("erste", "> zweite", ""))),
            runs("> erste\n>> zweite\n>"),
        )
        assertEquals(MarkdownRun.Quote(listOf("zitat")), runs("> zitat").single())
    }

    @Test fun headingsAndRulesAreTheirOwnRuns() {
        assertEquals(
            listOf(
                MarkdownRun.Heading(2, "Titel"),
                MarkdownRun.Paragraph(listOf("text")),
                MarkdownRun.Rule,
                MarkdownRun.Paragraph(listOf("mehr")),
            ),
            runs("## Titel\ntext\n---\nmehr"),
        )
    }

    @Test fun differentKindsOfLinesEndTheRunBeforeThem() {
        assertEquals(
            listOf(
                MarkdownRun.Paragraph(listOf("vorher")),
                MarkdownRun.Items(listOf(MarkdownListItem(0, "•", "a"), MarkdownListItem(0, "•", "b"))),
                MarkdownRun.Paragraph(listOf("nachher")),
            ),
            runs("vorher\n- a\n- b\nnachher"),
        )
    }

    @Test fun artifactLinksReadAsTheirLineWithoutCards() {
        val source = "Siehe [Seite](artifact.html)"
        assertEquals(listOf(MarkdownRun.Paragraph(listOf(source))), runs(source, artifactCards = false))
        val cards = runs("[Seite](artifact.html)", artifactCards = true).single()
        assertTrue(cards is MarkdownRun.Block && cards.block is MarkdownBlock.Artifact)
    }

    @Test fun tablesAndImagesAreBlocksBetweenTheRuns() {
        val parts = runs("davor\nA | B\n--- | ---\n1 | 2\n\n![Bild](pic.png)\ndanach")
        assertEquals(MarkdownRun.Paragraph(listOf("davor")), parts[0])
        assertTrue((parts[1] as MarkdownRun.Block).block is MarkdownBlock.Table)
        assertTrue((parts[2] as MarkdownRun.Block).block is MarkdownBlock.Image)
        assertEquals(MarkdownRun.Paragraph(listOf("danach")), parts[3])
    }

    @Test fun plainCopyKeepsNestingBoxesAndQuotes() {
        assertEquals(
            "• eins\n  ◦ zwei\n☐ offen\nzitat",
            markdownPlainText("- eins\n  - zwei\n- [ ] offen\n> zitat"),
        )
    }

    @Test fun inlineCodeTakesTheGivenBackground() {
        val background = Color(0xFF112233)
        val text = inlineMarkdown("ein `code` Stück", Color.Blue, codeBackground = background)
        val style = text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(background, style.item.background)
        assertEquals("ein code Stück", text.text)
    }

    @Test fun inlineCodeWithoutABackgroundStaysUnfilled() {
        val text = inlineMarkdown("`code`", Color.Blue)
        assertEquals(Color.Unspecified, text.spanStyles.single().item.background)
    }
}
