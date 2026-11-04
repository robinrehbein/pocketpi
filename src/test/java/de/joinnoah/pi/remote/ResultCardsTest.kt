package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class ResultCardsTest {
    @Test fun timestampedRecordsPreserveEveryLineAndOnlyClassifyExplicitMessages() {
        val text = "--- 2026-10-05T17:57:52Z id\nPlan approved: safe\n--- 2026-10-05T16:00:22Z id\nRevision required: tests\n  details\n--- 2026-10-05T15:57:45Z id\nunknown output\n"
        val cards = resultCards(text)
        assertEquals(3, cards.size)
        assertEquals(text, cards.joinToString("\n") { it.text })
        assertEquals(listOf(ResultKind.APPROVED, ResultKind.REVISION, ResultKind.NEUTRAL), cards.map { it.kind })
        assertEquals("2026-10-05T17:57:52Z", cards.first().timestamp)
    }

    @Test fun longUnstructuredOutputIsBoundedAndNeverInferredSuccessful() {
        val text = (1..100).joinToString("\n") { "test $it: not approved" }
        val cards = resultCards(text)
        assertEquals(5, cards.size)
        assertEquals(text, cards.joinToString("\n") { it.text })
        assertTrue(cards.all { it.kind == ResultKind.NEUTRAL && it.text.lines().size <= 24 })
        assertTrue(resultCards("").isEmpty())
    }

    @Test fun oversizedPreviewDoesNotCutAnEmojiInHalfOrChangeSource() {
        val text = "x".repeat(RESULT_CARD_PREVIEW_CHARS - 1) + "😀rest"
        assertEquals("x".repeat(RESULT_CARD_PREVIEW_CHARS - 1) + "…", resultCardPreview(text))
        assertEquals(text, resultCards(text).single().text)
    }

    @Test fun popoverStaysAboveAnchorAndClampsAtWindowEdges() {
        val position = SubagentPopoverPosition(12)
        assertEquals(androidx.compose.ui.unit.IntOffset(68, 288), position.calculatePosition(
            androidx.compose.ui.unit.IntRect(200, 500, 388, 550), androidx.compose.ui.unit.IntSize(400, 800),
            androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.IntSize(320, 200)))
        assertEquals(androidx.compose.ui.unit.IntOffset(12, 12), position.calculatePosition(
            androidx.compose.ui.unit.IntRect(0, 10, 100, 60), androidx.compose.ui.unit.IntSize(400, 800),
            androidx.compose.ui.unit.LayoutDirection.Rtl, androidx.compose.ui.unit.IntSize(320, 200)))
    }
}
