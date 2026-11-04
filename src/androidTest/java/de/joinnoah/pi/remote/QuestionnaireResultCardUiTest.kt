package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class QuestionnaireResultCardUiTest {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(state: QuestionnaireState, questions: List<QuestionnaireEntry>, standalone: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                QuestionnaireResultCard(QuestionnairePresentation("call", null, state, questions), standalone = standalone)
            }
        }
    }

    @Test
    fun blockingQuestionnaireOriginalOutputCanBeOpened() {
        val item = ConversationItem.Activity(
            id = "tool", sourceId = "message", name = "AskUserQuestion", arguments = null,
            output = "Original blocking output", state = "complete", truncated = false,
            questionnaire = QuestionnairePresentation("call", null, QuestionnaireState.ANSWERED,
                listOf(QuestionnaireEntry("one", "Prompt", listOf("Answer"), false))),
        )
        compose.setContent {
            MaterialTheme { ConversationMessage(item, ThinkingDisplay.TEXT, false, onQuote = {}) }
        }
        compose.onNodeWithText("Original blocking output").assertDoesNotExist()
        compose.onNodeWithTag("questionnaireOriginalOutput").performClick()
        compose.onNodeWithText("Original blocking output", substring = true).assertExists()
    }

    @Test
    fun pendingShowsOnlyProvidedPrompts() {
        show(QuestionnaireState.PENDING, listOf(
            QuestionnaireEntry("one", "First prompt", listOf("Hidden answer"), false),
            QuestionnaireEntry("two", "Second prompt", emptyList(), false),
        ))
        compose.onNodeWithTag("questionnairePrompt-one").assertExists()
        compose.onNodeWithTag("questionnairePrompt-two").assertExists()
        compose.onNodeWithTag("questionnairePrompt-three").assertDoesNotExist()
        compose.onNodeWithText("Hidden answer").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_questionnaire_pending)).assertExists()
    }

    @Test
    fun cancelledDoesNotShowAnswersOrNotes() {
        show(QuestionnaireState.CANCELLED, listOf(QuestionnaireEntry("one", "Prompt", listOf("Secret"), false, "Note")))
        compose.onNodeWithText("Prompt").assertExists()
        compose.onNodeWithText("Secret").assertDoesNotExist()
        compose.onNodeWithText("Note").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_questionnaire_cancelled)).assertExists()
    }

    @Test
    fun longAnswerCanExpandAndCollapse() {
        val answer = "Long answer ".repeat(30)
        show(QuestionnaireState.ANSWERED, listOf(QuestionnaireEntry("one", "Prompt", listOf(answer), false)))
        compose.onNodeWithText(text(R.string.remote_questionnaire_show_more)).assertExists()
        compose.onNodeWithTag("questionnaireToggle-one").performClick()
        compose.onNodeWithText(answer).assertExists()
        compose.onNodeWithText(text(R.string.remote_questionnaire_show_less)).assertExists()
        compose.onNodeWithTag("questionnaireToggle-one").performClick()
        compose.onNodeWithText(text(R.string.remote_questionnaire_show_more)).assertExists()
    }

    @Test
    fun skippedAndMissingAreDistinctAndStandaloneHasTitle() {
        show(QuestionnaireState.ANSWERED, listOf(
            QuestionnaireEntry("skip", "Skipped prompt", listOf("Ignored"), true),
            QuestionnaireEntry("missing", "Missing prompt", emptyList(), false),
        ), standalone = true)
        compose.onNodeWithText(text(R.string.remote_questionnaire_earlier_title)).assertExists()
        compose.onNodeWithText(text(R.string.remote_questionnaire_skipped)).assertExists()
        compose.onNodeWithText(text(R.string.remote_questionnaire_missing)).assertExists()
        compose.onNodeWithText("Ignored").assertDoesNotExist()
    }
}
