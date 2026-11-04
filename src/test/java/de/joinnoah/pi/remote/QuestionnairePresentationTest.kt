package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionnairePresentationTest {
    private fun parse(q: String, role: String = "tool"): QuestionnairePresentation? =
        questionnairePresentation(Json.parseToJsonElement("""{"role":"$role","toolCallId":"opaque","questionnaire":$q}""") as JsonObject)

    private fun payload(
        state: String = "answered",
        questions: String = """[{"id":"q","prompt":"Pick"}]""",
        extra: String = "",
    ) = """{"version":1,"toolCallId":"opaque","state":"$state","questions":$questions$extra}"""

    @Test fun answersPreserveOrderMissingAndExplicitSkip() {
        val q = payload(questions = """[{"id":"a","prompt":"A"},{"id":"b","prompt":"B"},{"id":"c","prompt":"C"}]""", extra = """, "answers":[{"id":"a","skipped":false,"selections":[{"label":"One","value":"1"},{"label":"Other","value":"other","wasCustom":true}],"note":"Note"},{"id":"b","skipped":true,"selections":[]}]""")
        val result = parse(q)!!
        assertEquals(listOf("One", "Other"), result.questions[0].answers)
        assertEquals("Note", result.questions[0].note)
        assertTrue(result.questions[1].skipped)
        assertFalse(result.questions[2].skipped)
        assertTrue(result.questions[2].answers.isEmpty())
        assertFalse(parse(payload(extra = """, "answers":[{"id":"q","skipped":false,"selections":[]}]"""))!!.questions.single().skipped)
    }

    @Test fun pendingCancelledAndUserMarker() {
        assertEquals(QuestionnaireState.PENDING, parse(payload("pending"))!!.state)
        assertEquals(QuestionnaireState.CANCELLED, parse(payload("cancelled"))!!.state)
        assertNotNull(parse(payload("cancelled", extra = ",\"answers\":[]")))
        assertNull(parse(payload("cancelled", extra = ",\"answers\":[{\"id\":\"q\",\"skipped\":true,\"selections\":[]}]")))
        assertNull(parse(payload("pending"), "user"))
        assertNull(parse(payload(), "user"))
        assertNotNull(parse(payload(extra = ",\"requestId\":\"async\""), "user"))
        assertNull(parse(payload(), "assistant"))
        assertNull(questionnairePresentation(Json.parseToJsonElement("""{"role":"user","text":"Questionnaire: answered"}""") as JsonObject))
    }

    @Test fun rejectsMalformedTypesVersionsAndIds() {
        val valid = payload()
        for (q in listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            payload(questions = "{}"),
            valid.replace("\"prompt\":\"Pick\"", "\"prompt\":false"),
            valid.replace("\"toolCallId\":\"opaque\"", "\"toolCallId\":null"),
            payload(questions = """[{"id":"q","prompt":"A"},{"id":"q","prompt":"B"}]"""),
            payload(extra = """, "answers":[{"id":"other","skipped":false,"selections":[]}]"""),
            payload(extra = """, "answers":[{"id":"q","skipped":"false","selections":[]}]"""),
            payload(extra = """, "answers":[{"id":"q","skipped":true,"selections":[{"label":"x","value":"x"}]}]"""),
        )) assertNull(q, parse(q))
    }

    @Test fun rejectsTruncatedMessagesAndUnboundToolResults() {
        val q = Json.parseToJsonElement(payload())
        assertNull(questionnairePresentation(Wire.objectOf("role" to "tool", "toolCallId" to "different", "questionnaire" to q)))
        assertNull(questionnairePresentation(Wire.objectOf("role" to "tool", "toolCallId" to "opaque", "truncated" to true, "questionnaire" to q)))
        assertNull(parse(payload().replace("opaque", "has spaces")))
        assertNull(parse(payload(extra = ",\"requestId\":\"has spaces\""), "user"))
        assertNull(parse(payload(extra = """, "answers":[{"id":"q","skipped":false,"selections":[{"label":"A","value":"a","index":0}]}]""")))
    }

    @Test fun longCustomAnswerIsNotMistakenForInvalidData() {
        val answer = "x".repeat(1024)
        val q = payload(extra = """, "answers":[{"id":"q","skipped":false,"selections":[{"label":"$answer","value":"$answer","wasCustom":true}]}]""")
        assertEquals(answer, parse(q)!!.questions.single().answers.single())
    }

    @Test fun limitsAndTruncation() {
        val manyQuestions = (1..13).joinToString(",", "[", "]") { """{"id":"$it","prompt":"P"}""" }
        val manyOptions = (1..21).joinToString(",", "[", "]") { """{"label":"L","value":"V"}""" }
        val manySelections = (1..21).joinToString(",", "[", "]") { """{"label":"L","value":"V"}""" }
        assertNull(parse(payload(questions = manyQuestions)))
        assertNull(parse(payload(questions = """[{"id":"q","prompt":"P","options":$manyOptions}]""")))
        assertNull(parse(payload(extra = """, "answers":[{"id":"q","skipped":false,"selections":$manySelections}]""")))
        assertNull(parse(payload().replace("opaque", "é".repeat(129))))
        assertNull(parse(payload(extra = ",\"truncated\":true")))
        assertNull(parse(payload(extra = ",\"extra\":\"${"x".repeat(16384)}\"")))
    }
}
