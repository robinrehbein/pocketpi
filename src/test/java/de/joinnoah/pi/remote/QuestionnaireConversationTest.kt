package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class QuestionnaireConversationTest {
    private fun details(state: String = "answered", requestId: String = "request-a", toolCallId: String = "call-a", questionId: String = "approach", prompt: String = "How should I proceed?", answer: String = "Make a plan") =
        Wire.objectOf(
            "version" to 1,
            "toolCallId" to toolCallId,
            "requestId" to requestId,
            "state" to state,
            "questions" to JsonArray(listOf(Wire.objectOf("id" to questionId, "prompt" to prompt))),
            "answers" to JsonArray(if (state == "answered") listOf(Wire.objectOf(
                "id" to questionId, "skipped" to false,
                "selections" to JsonArray(listOf(Wire.objectOf("value" to "plan", "label" to answer))),
            )) else emptyList()),
        )

    private fun call(id: String = "call-a", name: String = "questionnaire_async") = Wire.objectOf(
        "id" to "assistant-$id", "role" to "assistant", "text" to "",
        "parts" to JsonArray(listOf(Wire.objectOf("type" to "toolCall", "id" to id, "name" to name, "arguments" to "{}"))),
    )

    private fun output(q: JsonObject = details("pending")) = Wire.objectOf(
        "id" to "result-a", "role" to "tool", "text" to "Queued", "toolName" to "questionnaire_async",
        "toolCallId" to "call-a", "state" to "complete", "questionnaire" to q,
    )

    private fun response(q: JsonObject = details(), id: String = "answer-a") = Wire.objectOf(
        "id" to id, "role" to "user", "text" to "Technical model context stays unchanged",
        "timestamp" to 1793785855000L, "questionnaire" to q,
    )

    @Test fun answerUpdatesOriginalToolAndBecomesSmallMarker() {
        val items = conversationItems(listOf(call(), output(), response()))
        val card = items.filterIsInstance<ConversationItem.Activity>().single()
        assertEquals("assistant-call-a-call-call-a", card.id)
        assertEquals(QuestionnaireState.ANSWERED, card.questionnaire?.state)
        assertEquals(listOf("Make a plan"), card.questionnaire?.questions?.single()?.answers)
        val marker = items.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single()
        assertEquals(card.id, marker.targetId)
        assertEquals(1793785855000L, marker.timestamp)
        assertTrue(items.none { it is ConversationItem.Bubble })
    }

    @Test fun missingOriginalCardHasStandaloneFallbackAndMergesAfterPagination() {
        val answer = response()
        val presenter = ConversationPresenter()
        val standalone = presenter.present(listOf(answer), false).items.single() as ConversationItem.QuestionnaireAnswer
        assertNull(standalone.targetId)
        val merged = presenter.present(listOf(call(), output(), answer), false).items
        assertNotNull(merged.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
        assertEquals(1, merged.filterIsInstance<ConversationItem.Activity>().size)
    }

    @Test fun answerArrivalInvalidatesCachedOriginalCardButKeepsItsKey() {
        val original = call()
        val queued = output()
        val presenter = ConversationPresenter()
        val before = presenter.present(listOf(original, queued), false).items.single() as ConversationItem.Activity
        assertEquals(QuestionnaireState.PENDING, before.questionnaire?.state)
        val after = presenter.present(listOf(original, queued, response()), false).items.first() as ConversationItem.Activity
        assertEquals(before.id, after.id)
        assertEquals(QuestionnaireState.ANSWERED, after.questionnaire?.state)
        assertNotSame(before, after)
    }

    @Test fun unrelatedQuestionnairesDoNotReceiveEachOthersAnswers() {
        val items = conversationItems(listOf(call(), call("call-b"), output(), response()))
        val cards = items.filterIsInstance<ConversationItem.Activity>()
        assertEquals(QuestionnaireState.ANSWERED, cards[0].questionnaire?.state)
        assertNull(cards[1].questionnaire)
    }

    @Test fun plainUserTextAndOldHostPayloadsStayVisible() {
        val raw = Wire.objectOf("id" to "legacy", "role" to "user", "text" to "[async-questionnaire:request-a]\nHuman questionnaire response:\n{}")
        val bubble = conversationItems(listOf(raw)).single() as ConversationItem.Bubble
        assertEquals(raw.text("text"), bubble.text)
    }

    @Test fun invalidStructuredDataDoesNotHideUserText() {
        val invalid = response(Wire.objectOf("version" to 99))
        val item = conversationItems(listOf(invalid)).single() as ConversationItem.Bubble
        assertEquals(invalid.text("text"), item.text)
    }

    @Test fun blockingResultRendersOnExistingToolCallWithoutAnswerMessage() {
        val tool = Wire.objectOf(
            "id" to "blocking-result", "role" to "tool", "toolName" to "AskUserQuestion",
            "toolCallId" to "call-a", "state" to "complete", "text" to "approach: Make a plan",
            "questionnaire" to details(),
        )
        val card = conversationItems(listOf(call(name = "AskUserQuestion"), tool)).single() as ConversationItem.Activity
        assertEquals(QuestionnaireState.ANSWERED, card.questionnaire?.state)
    }

    @Test fun differentRequestForSameCallDoesNotOverwriteLoadedQuestion() {
        val items = conversationItems(listOf(call(), output(), response(details(requestId = "other-request"))))
        assertEquals(QuestionnaireState.PENDING, items.filterIsInstance<ConversationItem.Activity>().single().questionnaire?.state)
        assertNull(items.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
    }

    @Test fun latestValidResponseAloneTargetsCardAndInvalidatesCachedMarkers() {
        val original = call()
        val queued = output()
        val first = response(id = "first")
        val second = response(details(answer = "Do it now"), "second")
        val presenter = ConversationPresenter()
        val before = presenter.present(listOf(original, queued, first), false).items
        assertNotNull(before.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
        val items = presenter.present(listOf(original, queued, first, second), false).items
        val card = items.filterIsInstance<ConversationItem.Activity>().single()
        val answers = items.filterIsInstance<ConversationItem.QuestionnaireAnswer>()
        assertEquals(listOf("Do it now"), card.questionnaire?.questions?.single()?.answers)
        assertNull(answers[0].targetId)
        assertEquals(card.id, answers[1].targetId)
        assertEquals(listOf(first.text("text"), second.text("text")), answers.map { it.rawText })
    }

    @Test fun duplicateResponseDataOnlyLatestTargetsCard() {
        val first = response(id = "first")
        val second = response(id = "second")
        val items = conversationItems(listOf(call(), output(), first, second))
        val answers = items.filterIsInstance<ConversationItem.QuestionnaireAnswer>()
        assertNull(answers[0].targetId)
        assertNotNull(answers[1].targetId)
        assertEquals(listOf(first.text("text"), second.text("text")), answers.map { it.rawText })
    }

    @Test fun differentQuestionIdOrPromptDoesNotOverwriteOriginal() {
        for (changed in listOf(details(questionId = "other"), details(prompt = "Different prompt?"))) {
            val items = conversationItems(listOf(call(), output(), response(changed)))
            assertEquals(QuestionnaireState.PENDING, items.filterIsInstance<ConversationItem.Activity>().single().questionnaire?.state)
            assertNull(items.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
        }
    }

    @Test fun laterInvalidResponseDoesNotDisplaceValidResponse() {
        val items = conversationItems(listOf(call(), output(), response(id = "valid"), response(details(prompt = "Different?"), "invalid")))
        val answers = items.filterIsInstance<ConversationItem.QuestionnaireAnswer>()
        assertNotNull(answers[0].targetId)
        assertNull(answers[1].targetId)
        assertEquals(QuestionnaireState.ANSWERED, items.filterIsInstance<ConversationItem.Activity>().single().questionnaire?.state)
    }

    @Test fun absentOriginalMetadataAllowsRecognizedCallButMalformedMetadataDoesNot() {
        val noMetadata = Wire.objectOf("id" to "result", "role" to "tool", "toolName" to "questionnaire_async", "toolCallId" to "call-a", "text" to "Queued")
        val malformed = output(Wire.objectOf("version" to 1))
        assertNotNull(conversationItems(listOf(call(), noMetadata, response())).filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
        assertNull(conversationItems(listOf(call(), malformed, response())).filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
    }

    @Test fun unpairedUnrelatedToolDoesNotRenderQuestionnaire() {
        val tool = Wire.objectOf("id" to "bash-result", "role" to "tool", "toolName" to "bash", "toolCallId" to "call-a", "text" to "output", "questionnaire" to details())
        val card = conversationItems(listOf(tool)).single() as ConversationItem.Activity
        assertNull(card.questionnaire)
        assertEquals("output", card.output)
    }

    @Test fun unknownToolCannotConsumeQuestionnaireResponse() {
        val items = conversationItems(listOf(call(name = "bash"), response()))
        assertNull(items.filterIsInstance<ConversationItem.Activity>().single().questionnaire)
        assertNull(items.filterIsInstance<ConversationItem.QuestionnaireAnswer>().single().targetId)
    }
}
