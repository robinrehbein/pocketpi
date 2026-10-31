package de.joinnoah.pi.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionReceiverTest {
    private val host = PairedHost("r", "https://relay.example", "d", "secret", "Mac")
    private val answer = Wire.objectOf("kind" to "choice", "value" to "yes")

    /** Records each command it is asked to send, and answers with [reply]. */
    private class Recording(private val reply: suspend (String) -> JsonObject = { Wire.objectOf("kind" to "accepted") }) {
        val sent = mutableListOf<JsonObject>()
        val commands = RemoteCommands { type, fields ->
            sent += Wire.objectOf("type" to type, *fields)
            reply(type)
        }
    }

    private fun answerer(
        live: suspend (String, () -> Unit, suspend (RemoteCommands) -> Unit) -> Unit? = { _, _, _ -> null },
        background: suspend (PairedHost, suspend (RemoteCommands) -> Unit) -> Unit = { _, _ -> error("no fallback expected") },
        pairedHost: suspend (String) -> PairedHost? = { route -> host.takeIf { route == "r" } },
    ) = NotificationAnswerer(pairedHost = pairedHost, live = live, background = background)

    @Test
    fun aHealthyAppConnectionCarriesTheAnswer() = runTest {
        val socket = Recording()
        val outcome = answerer(live = { route, _, block -> if (route == "r") block(socket.commands) else null })
            .answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.SENT, outcome)
        assertEquals(
            listOf(Wire.objectOf("type" to "question.answer", "sessionId" to "s", "questionId" to "q", "answer" to answer)),
            socket.sent,
        )
    }

    private fun snapshot(question: JsonObject?, session: String = "s") = Wire.objectOf(
        "kind" to "snapshot", "sessionId" to session, "revision" to 1, "status" to "waiting",
        "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(listOfNotNull(question)),
    )

    @Test
    fun backgroundInlinePositiveChecksTheCurrentQuestionAndSendsOnSameConnection() = runTest {
        val question = Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "Continue?")
        val own = Recording { type -> if (type == "session.snapshot") snapshot(question) else Wire.objectOf("kind" to "accepted") }
        val yes = Wire.objectOf("kind" to "confirm", "value" to true)
        val outcome = answerer(background = { _, block -> block(own.commands) }).answer("r", "s", "q", yes)
        assertEquals(AnswerOutcome.SENT, outcome)
        assertEquals(listOf("session.snapshot", "question.answer"), own.sent.map { it.text("type") })
        assertEquals(yes, own.sent.last()["answer"])
    }

    @Test
    fun backgroundPositiveDetailsOrUnverifiableQuestionNeverSendsAnswer() = runTest {
        val details = Wire.parse("""{"id":"q","kind":"confirm","message":"Preview","details":{"id":"d","totalBytes":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}}""")
        val cases = listOf(
            snapshot(details), snapshot(null), snapshot(Wire.objectOf("id" to "other", "kind" to "confirm", "message" to "Continue?")),
            snapshot(Wire.objectOf("id" to "q", "kind" to "select", "message" to "Continue?")),
            snapshot(Wire.objectOf("id" to "q", "kind" to "confirm", "message" to "Continue?"), session = "other"),
            snapshot(Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "x".repeat(16385))),
            snapshot(Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "Go?", "unknown" to true)),
            snapshot(Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "Go?", "details" to null)),
            Wire.objectOf("kind" to "snapshot", "sessionId" to "s", "pendingQuestions" to JsonArray(listOf(
                Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "Go?"),
                Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Confirm", "message" to "Go?")))),
            Wire.objectOf("kind" to "unavailable"),
        )
        for (data in cases) {
            val own = Recording { data }
            val outcome = answerer(background = { _, block -> block(own.commands) })
                .answer("r", "s", "q", Wire.objectOf("kind" to "confirm", "value" to true))
            assertEquals(data.toString(), AnswerOutcome.FAILED, outcome)
            assertTrue(data.toString(), own.sent.none { it.text("type") == "question.answer" })
        }
    }

    @Test
    fun backgroundNoAndCancelDoNotDependOnSnapshot() = runTest {
        for (answer in listOf(Wire.objectOf("kind" to "confirm", "value" to false), Wire.objectOf("kind" to "cancel"))) {
            val own = Recording()
            val outcome = answerer(background = { _, block -> block(own.commands) }).answer("r", "s", "q", answer)
            assertEquals(AnswerOutcome.SENT, outcome)
            assertEquals(listOf("question.answer"), own.sent.map { it.text("type") })
            assertEquals(answer, own.sent.single()["answer"])
        }
    }

    @Test
    fun withoutAHealthyConnectionTheAnswerUsesItsOwn() = runTest {
        val own = Recording()
        var usedHost: PairedHost? = null
        val outcome =
            answerer(background = { paired, block ->
                usedHost = paired
                block(own.commands)
            }).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.SENT, outcome)
        assertEquals(host, usedHost)
        assertEquals(listOf("question.answer"), own.sent.map { it.text("type") })
    }

    @Test
    fun anAppConnectionThatTimesOutFallsBackWithinTheBudget() = runTest {
        val own = Recording()
        val outcome =
            answerer(
                live = { _, _, _ ->
                    delay(HEALTHY_REQUEST_TIMEOUT_MILLIS)
                    null
                },
                background = { _, block ->
                    delay(2_000)
                    block(own.commands)
                },
            ).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.SENT, outcome)
        assertEquals(HEALTHY_REQUEST_TIMEOUT_MILLIS + 2_000, currentTime)
        assertEquals(1, own.sent.size)
    }

    @Test
    fun aBlockingPairingLoadStaysInsideTheTimeout() = runTest {
        var tried = false
        // As a Keystore that hangs: the load blocks its thread, and the receiver only awaits it.
        val keystore = CountDownLatch(1)
        val io = CoroutineScope(Dispatchers.IO)
        try {
            val outcome =
                answerer(
                    live = { _, _, _ ->
                        tried = true
                        null
                    },
                    pairedHost = {
                        awaitApart(io) {
                            keystore.await(10, TimeUnit.SECONDS)
                            host
                        }
                    },
                ).answer("r", "s", "q", answer)
            assertEquals(AnswerOutcome.FAILED, outcome)
            assertEquals(ANSWER_TIMEOUT_MILLIS, currentTime)
            assertTrue("nothing is sent without the pairing", !tried)
        } finally {
            keystore.countDown()
            io.cancel()
        }
    }

    @Test
    fun anAnswerTheAppConnectionMayHaveSentCountsAsSentWhenTheFallbackFindsItAnswered() = runTest {
        val refused: suspend (PairedHost, suspend (RemoteCommands) -> Unit) -> Unit =
            { _, block -> block(Recording { throw RemoteCommandException("already_resolved") }.commands) }
        val afterSend =
            answerer(
                live = { _, sent, _ ->
                    // The request went out, then its reply did not come in time.
                    sent()
                    delay(HEALTHY_REQUEST_TIMEOUT_MILLIS)
                    null
                },
                background = refused,
            ).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.SENT, afterSend)
        // Nothing went out on the app's connection: the answer was someone else's.
        val beforeSend = answerer(live = { _, _, _ -> null }, background = refused).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, beforeSend)
        // Another refusal still fails.
        val invalid =
            answerer(
                live = { _, sent, _ ->
                    sent()
                    null
                },
                background = { _, block -> block(Recording { throw RemoteCommandException("invalid_answer") }.commands) },
            ).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.FAILED, invalid)
    }

    @Test
    fun anUnpairedHostIsNeverAnswered() = runTest {
        var tried = false
        val outcome =
            answerer(live = { _, _, _ ->
                tried = true
                null
            }).answer("gone", "s", "q", answer)
        assertEquals(AnswerOutcome.FAILED, outcome)
        assertTrue(!tried)
    }

    @Test
    fun aConnectionThatNeverAnswersFailsAtTheDeadline() = runTest {
        val outcome = answerer(background = { _, _ -> CompletableDeferred<Unit>().await() }).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.FAILED, outcome)
        assertEquals(ANSWER_TIMEOUT_MILLIS, currentTime)
    }

    @Test
    fun hostErrorsMapToWhatTheNotificationSays() = runTest {
        fun failing(error: Exception): suspend (PairedHost, suspend (RemoteCommands) -> Unit) -> Unit =
            { _, block -> block(Recording { throw error }.commands) }
        for ((error, expected) in listOf(
            RemoteCommandException("already_resolved") to AnswerOutcome.ALREADY_RESOLVED,
            RemoteCommandException("not_found") to AnswerOutcome.ALREADY_RESOLVED,
            RemoteCommandException("invalid_answer") to AnswerOutcome.FAILED,
            RemoteConnectionException() to AnswerOutcome.FAILED,
        )) {
            assertEquals(error.toString(), expected, answerer(background = failing(error)).answer("r", "s", "q", answer))
        }
        // The app's connection maps the host's refusal the same way, without trying again.
        val refused =
            answerer(live = { _, _, block -> block(Recording { throw RemoteCommandException("already_resolved") }.commands) })
                .answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, refused)
    }
}
