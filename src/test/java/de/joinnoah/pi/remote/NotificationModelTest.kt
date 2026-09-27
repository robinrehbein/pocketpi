package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationModelTest {
    @Test
    fun payloadCarriesOnlyOpaqueIds() {
        val payload =
            parsePushPayload(
                mapOf("routeId" to "route_1", "target" to "session-1", "eventId" to "e1", "event" to "question")
            )
        assertEquals(PushPayload("route_1", "session-1", "e1", PushEvent.QUESTION), payload)
    }

    @Test
    fun unknownOrMissingEventKindIsACompletion() {
        val base = mapOf("routeId" to "r", "target" to "s", "eventId" to "e")
        assertEquals(PushEvent.COMPLETE, parsePushPayload(base)?.event)
        assertEquals(PushEvent.COMPLETE, parsePushPayload(base + ("event" to "other"))?.event)
    }

    @Test
    fun malformedPayloadsAreRejected() {
        val base = mapOf("routeId" to "r", "target" to "s", "eventId" to "e")
        assertNull(parsePushPayload(base - "routeId"))
        assertNull(parsePushPayload(base + ("target" to "")))
        assertNull(parsePushPayload(base + ("target" to "a/b")))
        assertNull(parsePushPayload(base + ("eventId" to "x".repeat(257))))
        assertNull(parsePushPayload(base + ("routeId" to "r\n")))
    }

    @Test
    fun confirmOffersYesAndNo() {
        val content =
            questionNotification(
                Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Delete?", "message" to "It is gone then.")
            , true)!!
        assertEquals("Delete?", content.title)
        assertEquals("It is gone then.", content.body)
        assertEquals(
            listOf(
                NotificationAction.Answer(
                    ActionLabel.Resource(R.string.remote_yes),
                    Wire.objectOf("kind" to "confirm", "value" to true),
                ),
                NotificationAction.Answer(
                    ActionLabel.Resource(R.string.remote_no),
                    Wire.objectOf("kind" to "confirm", "value" to false),
                ),
            ),
            content.actions,
        )
    }

    @Test
    fun shortSelectOffersOneActionPerOption() {
        val content = questionNotification(select("a", "b", "c"), true)!!
        assertEquals(
            listOf("a", "b", "c").map {
                NotificationAction.Answer(ActionLabel.Text(it), Wire.objectOf("kind" to "select", "value" to it))
            },
            content.actions,
        )
        assertNull(content.body)
    }

    @Test
    fun longSelectOnlyOpensTheSession() {
        val content = questionNotification(select("a", "b", "c", "d"), true)!!
        assertEquals(listOf(NotificationAction.AnswerInApp), content.actions)
        assertEquals("a · b · c · d", content.body)
    }

    @Test
    fun selectWithDuplicateOrBlankOptionsOnlyOpens() {
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(select("a", "a"), true)!!.actions)
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(select("a", " "), true)!!.actions)
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(select(), true)!!.actions)
    }

    @Test
    fun optionsAreAnsweredFromTheNotificationOnlyIfTheirLabelFitsWhole() {
        val fits = "x".repeat(MAX_ACTION_LABEL)
        val action = questionNotification(select(fits), true)!!.actions.single() as NotificationAction.Answer
        assertEquals(fits, (action.label as ActionLabel.Text).text)
        assertEquals(fits, action.answer.text("value"))
        assertEquals(
            listOf(NotificationAction.AnswerInApp),
            questionNotification(select(fits + "y"), true)!!.actions,
        )
    }

    @Test
    fun inputOffersInlineReplyAndOpen() {
        val content =
            questionNotification(
                Wire.objectOf("id" to "q", "kind" to "input", "title" to "Name?", "placeholder" to "e.g. main")
            , true)!!
        assertEquals(listOf(NotificationAction.Reply, NotificationAction.Open), content.actions)
        assertEquals("e.g. main", content.body)
    }

    @Test
    fun planOffersApproveAndOpen() {
        val content =
            questionNotification(Wire.objectOf("id" to "q", "kind" to "plan", "title" to "Plan", "plan" to "1. Do it"), true)!!
        assertEquals(
            listOf(
                NotificationAction.Answer(
                    ActionLabel.Resource(R.string.remote_notification_approve),
                    Wire.objectOf("kind" to "plan", "action" to "approve"),
                ),
                NotificationAction.Open,
            ),
            content.actions,
        )
        assertTrue(isPlanApproval((content.actions.first() as NotificationAction.Answer).answer))
    }

    @Test
    fun questionnaireAndLocalRequiredOnlyOpen() {
        val questionnaire =
            questionNotification(Wire.objectOf("id" to "q", "kind" to "questionnaire", "questions" to JsonArray(emptyList())), true)!!
        assertEquals(emptyList<NotificationAction>(), questionnaire.actions)
        assertNull(questionnaire.title)
        val local = questionNotification(Wire.objectOf("id" to "q", "kind" to "local_required", "title" to "On the Mac"), true)!!
        assertEquals(emptyList<NotificationAction>(), local.actions)
        assertEquals("On the Mac", local.title)
    }

    @Test
    fun malformedOrUnknownQuestionsAreIgnored() {
        assertNull(questionNotification(Wire.objectOf("id" to "q", "kind" to "unknown"), true))
        assertNull(questionNotification(Wire.objectOf("kind" to "confirm", "title" to "t", "message" to "m"), true))
        assertNull(questionNotification(Wire.objectOf("id" to "q", "kind" to "select", "title" to "t"), true))
        assertNull(
            questionNotification(
                Wire.objectOf("id" to "q", "kind" to "select", "title" to "t", "options" to JsonArray(listOf(JsonPrimitive(1))))
            , true)
        )
    }

    @Test
    fun belowAndroid12OnlyInAppAnswersAreOffered() {
        val confirm = Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Delete?", "message" to "Sure?")
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(confirm, false)!!.actions)
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(select("a", "b"), false)!!.actions)
        val input = Wire.objectOf("id" to "q", "kind" to "input", "title" to "Name?")
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(input, false)!!.actions)
        val plan = Wire.objectOf("id" to "q", "kind" to "plan", "title" to "Plan", "plan" to "1. Do it")
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(plan, false)!!.actions)
        // Content stays the same; only the actions change.
        assertEquals("Sure?", questionNotification(confirm, false)!!.body)
    }

    @Test
    fun optionsWhoseShortenedLabelsCollideAreAnsweredInTheApp() {
        val prefix = "x".repeat(MAX_ACTION_LABEL)
        val content = questionNotification(select(prefix + "one", prefix + "two"), true)!!
        assertEquals(listOf(NotificationAction.AnswerInApp), content.actions)
        // Options that differ only in surrounding spaces would show the same label.
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(select("a", " a"), true)!!.actions)
    }

    @Test
    fun shortenedConfirmOrPlanIsNeverAnsweredBlind() {
        val longMessage = Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Delete?", "message" to "m".repeat(401))
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(longMessage, true)!!.actions)
        val longTitle = Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "t".repeat(121), "message" to "m")
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(longTitle, true)!!.actions)
        val longPlan = Wire.objectOf("id" to "q", "kind" to "plan", "title" to "Plan", "plan" to "p".repeat(401))
        assertEquals(listOf(NotificationAction.AnswerInApp), questionNotification(longPlan, true)!!.actions)
        val fits = Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Delete?", "message" to "m".repeat(400))
        assertEquals(2, questionNotification(fits, true)!!.actions.size)
    }

    @Test
    fun fetchesGiveUpSoonerRightAfterTheHostWasUnreachable() {
        assertEquals(FETCH_TIMEOUT_MILLIS, fetchTimeoutMillis(null, 100_000))
        assertEquals(UNREACHABLE_FETCH_TIMEOUT_MILLIS, fetchTimeoutMillis(90_000, 100_000))
        assertEquals(FETCH_TIMEOUT_MILLIS, fetchTimeoutMillis(30_000, 100_000))
        assertEquals(FETCH_TIMEOUT_MILLIS, fetchTimeoutMillis(200_000, 100_000))
    }

    @Test
    fun theOldestPendingQuestionIsNotified() {
        val first = select("a")
        assertEquals(first, notifiedQuestion(listOf(first, select("b"))))
        assertNull(notifiedQuestion(emptyList()))
    }

    @Test
    fun inlineRepliesAreTrimmedAndBlankOnesRejected() {
        assertEquals(Wire.objectOf("kind" to "input", "value" to "main"), inputAnswer("  main "))
        assertNull(inputAnswer("   "))
        assertNull(inputAnswer(null))
        assertNull(inputAnswer("x".repeat(40_000)))
    }

    @Test
    fun answerOutcomesDistinguishResolvedFromFailed() {
        assertEquals(AnswerOutcome.SENT, answerOutcome(null))
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, answerOutcome(RemoteCommandException("already_resolved")))
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, answerOutcome(RemoteCommandException("not_found")))
        assertEquals(AnswerOutcome.FAILED, answerOutcome(RemoteCommandException("forbidden")))
        assertEquals(AnswerOutcome.FAILED, answerOutcome(RemoteConnectionException()))
    }

    @Test
    fun backgroundClientSendsOneCommandAndCloses() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = OneShotTransport()
        val client = BackgroundRemoteClient(dispatcher) { transport }
        val result = async {
            client.use(host) { it.answerQuestion("s", "q", Wire.objectOf("kind" to "confirm", "value" to true)) }
        }
        runCurrent()
        assertEquals(1, transport.connects)
        transport.listener.ready()
        runCurrent()
        val sent = transport.sent.single()
        assertEquals("question.answer", sent.text("type"))
        assertEquals("q", sent.text("questionId"))
        transport.listener.message(
            Wire.objectOf("type" to "result", "requestId" to sent.text("requestId"), "ok" to true, "data" to Wire.objectOf("kind" to "accepted"))
        )
        runCurrent()
        result.await()
        assertTrue(transport.closed)
    }

    @Test
    fun backgroundClientReportsHostErrorCodes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = OneShotTransport()
        val client = BackgroundRemoteClient(dispatcher) { transport }
        val result = async {
            runCatching { client.use(host) { it.pendingQuestions("s") } }
        }
        runCurrent()
        transport.listener.ready()
        runCurrent()
        val sent = transport.sent.single()
        transport.listener.message(
            Wire.objectOf(
                "type" to "result",
                "requestId" to sent.text("requestId"),
                "ok" to false,
                "error" to Wire.objectOf("code" to "not_found", "message" to "gone"),
            )
        )
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertEquals("not_found", (error as RemoteCommandException).code)
        assertTrue(transport.closed)
    }

    @Test
    fun backgroundClientFailsWhenTheConnectionFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = OneShotTransport()
        val client = BackgroundRemoteClient(dispatcher) { transport }
        val result = async { runCatching { client.use(host) { it.pendingQuestions("s") } } }
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is RemoteConnectionException)
        assertTrue(transport.closed)
    }

    @Test
    fun pendingQuestionsComeFromTheSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = OneShotTransport()
        val client = BackgroundRemoteClient(dispatcher) { transport }
        val result = async { client.use(host) { it.pendingQuestions("s") } }
        runCurrent()
        transport.listener.ready()
        runCurrent()
        val question = select("a")
        transport.listener.message(
            Wire.objectOf(
                "type" to "result",
                "requestId" to transport.sent.single().text("requestId"),
                "ok" to true,
                "data" to Wire.objectOf(
                    "kind" to "snapshot",
                    "sessionId" to "s",
                    "revision" to 1,
                    "status" to "waiting",
                    "messages" to JsonArray(emptyList()),
                    "pendingQuestions" to JsonArray(listOf(question)),
                ),
            )
        )
        runCurrent()
        assertEquals(listOf(question), result.await())
    }

    private val host = PairedHost("r", "https://relay.example", "d", "secret", "Mac")

    private fun select(vararg options: String): JsonObject =
        Wire.objectOf(
            "id" to "q",
            "kind" to "select",
            "title" to "Pick",
            "options" to JsonArray(options.map { JsonPrimitive(it) }),
        )

    private class OneShotTransport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        var connects = 0
        var closed = false
        val sent = mutableListOf<JsonObject>()

        override fun connect(host: PairedHost) {
            connects++
        }

        override fun pair(value: JsonObject) = error("not used")

        override fun send(payload: JsonObject) {
            sent += payload
        }

        override fun close() {
            closed = true
        }
    }
}
