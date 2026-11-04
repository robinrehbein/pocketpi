package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchQuestionnaireTest {
    private val text: (Int) -> String = { it.toString() }
    private val entry = QuestionnaireEntry("one", "Prompt", listOf("First", "Second"), false, "Note")
    private fun presentation(state: QuestionnaireState) = QuestionnairePresentation("call", null, state, listOf(entry))

    @Test fun statesOnlyRevealAnswersWhenAnswered() {
        for (state in listOf(QuestionnaireState.PENDING, QuestionnaireState.CANCELLED)) {
            val summary = watchQuestionnaireSummary(presentation(state), text)
            assertTrue(summary.contains("Prompt"))
            assertFalse(summary.contains("First"))
            assertFalse(summary.contains("Note"))
        }
        val summary = watchQuestionnaireSummary(presentation(QuestionnaireState.ANSWERED), text)
        assertTrue(summary.contains("First, Second"))
        assertTrue(summary.contains("Note"))
    }

    @Test fun linkedMarkerDoesNotRepeatSummaryButStandaloneDoes() {
        val answer = ConversationItem.QuestionnaireAnswer("id", "source", presentation(QuestionnaireState.ANSWERED), "target", null, "raw JSON")
        assertEquals(text(R.string.remote_questionnaire_answered), watchMessageContent(answer, text))
        val standalone = watchMessageContent(answer.copy(targetId = null), text)
        assertTrue(standalone.contains("Prompt"))
        assertFalse(standalone.contains("raw JSON"))
    }

    @Test fun downloadedFullOutputIsReadableForQuestionnaireTools() {
        val card = ConversationItem.Activity(
            "card", "result", "AskUserQuestion", null, "Short output", "complete", false,
            toolCallId = "call", questionnaire = presentation(QuestionnaireState.ANSWERED),
        )
        val output = ToolOutputDownload("call", 12, 12, "Full original", false, false, null)
        assertTrue(watchMessageReaderContent(card, null, text).contains("Prompt"))
        assertEquals("Full original", watchMessageReaderContent(card, output, text))
        assertTrue(watchMessageReaderContent(card, output.copy(toolCallId = "other"), text).contains("Prompt"))
    }

    @Test fun skippedAndMissingAreDistinct() {
        val p = presentation(QuestionnaireState.ANSWERED).copy(questions = listOf(
            entry.copy(skipped = true), entry.copy(id = "two", answers = emptyList(), note = null),
        ))
        val summary = watchQuestionnaireSummary(p, text)
        assertTrue(summary.contains(text(R.string.remote_questionnaire_skipped)))
        assertTrue(summary.contains(text(R.string.remote_questionnaire_missing)))
        assertFalse(summary.contains("First"))
    }
}
