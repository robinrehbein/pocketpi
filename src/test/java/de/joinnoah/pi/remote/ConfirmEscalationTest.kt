package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared `escalation-v1.json` fixture: auto-mode escalations on a `confirm` question. */
class ConfirmEscalationTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/escalation-v1.json")!!.readText()).jsonObject

    private fun payloads(group: String): Map<String, JsonObject> =
        fixture.getValue(group).jsonArray.associate {
            val entry = it.jsonObject
            entry.text("name") to entry.obj("payload")
        }

    private val valid = payloads("wireValid")
    private val invalid = payloads("wireInvalid")

    private fun question(name: String) = (valid[name] ?: invalid.getValue(name)).obj("question")

    @Test
    fun validEscalationsAreRead() {
        assertNull(confirmEscalation(question("escalation-plain-confirm")))
        assertEquals(
            ConfirmEscalation("bash", "Pushing to a shared branch (p=0.91)", "Explore#a1b2c3d4"),
            confirmEscalation(question("escalation-child")),
        )
        assertEquals(
            ConfirmEscalation("mcp__git__push", "", null),
            confirmEscalation(question("escalation-top-level-empty-reason")),
        )
    }

    @Test
    fun invalidEscalationsFallBackToThePlainConfirm() {
        val questions = invalid.filterValues { it.text("type") == "event" }
        assertEquals(7, questions.size)
        for ((name, payload) in questions) {
            val question = payload.obj("question")
            assertNull(name, confirmEscalation(question))
            // The question still reaches the user through its title and message.
            if (question.text("kind") == "confirm") {
                val content = questionNotification(question, true)
                assertNotNull(name, content)
                content!!
                assertEquals(name, question.text("title"), content.title)
                assertNull(name, content.escalation)
            }
        }
    }

    @Test
    fun theAppsAnswersAreTheFixturesAnswers() {
        val yes = valid.getValue("escalation-answer-yes").obj("answer")
        val cancel = valid.getValue("escalation-answer-cancel").obj("answer")
        val content = questionNotification(question("escalation-child"), true)!!
        assertEquals(
            listOf(yes, Wire.objectOf("kind" to "confirm", "value" to false)),
            content.actions.map { (it as NotificationAction.Answer).answer },
        )
        assertEquals(Wire.objectOf("kind" to "cancel"), cancel)
        // The phone never answers with a session allow.
        val session = invalid.getValue("escalation-answer-session").obj("answer")
        assertTrue(content.actions.none { (it as NotificationAction.Answer).answer == session })
    }

    @Test
    fun aNotificationNamesTheEscalationAndOffersYesOnce() {
        val content = questionNotification(question("escalation-child"), true)!!
        assertEquals(
            ConfirmEscalation("bash", "Pushing to a shared branch (p=0.91)", "Explore#a1b2c3d4"),
            content.escalation,
        )
        assertEquals("Auto mode blocked bash from Explore#a1b2c3d4", content.title)
        assertEquals(
            ActionLabel.Resource(R.string.remote_escalation_yes),
            (content.actions.first() as NotificationAction.Answer).label,
        )
        val plain = questionNotification(question("escalation-plain-confirm"), true)!!
        assertNull(plain.escalation)
        assertEquals(
            ActionLabel.Resource(R.string.remote_yes),
            (plain.actions.first() as NotificationAction.Answer).label,
        )
    }

    @Test
    fun theTimelineKeepsTheEscalationOnOpenAndInSnapshots() {
        val timeline = Timeline("019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d")
        val child = question("escalation-child")
        timeline.snapshot(
            Wire.objectOf(
                "sessionId" to "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d",
                "revision" to 30,
                "status" to "running",
                "messages" to JsonArray(emptyList()),
                "pendingQuestions" to JsonArray(listOf(child)),
            )
        )
        assertEquals(listOf(child), timeline.questions)
        timeline.event(valid.getValue("escalation-plain-confirm"))
        timeline.event(valid.getValue("escalation-child"))
        assertEquals(listOf(child), timeline.questions)
        assertNotNull(confirmEscalation(timeline.questions.single()))
    }
}
