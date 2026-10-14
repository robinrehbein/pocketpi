package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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
        live: suspend (String, suspend (RemoteCommands) -> Unit) -> Unit? = { _, _ -> null },
        background: suspend (PairedHost, suspend (RemoteCommands) -> Unit) -> Unit = { _, _ -> error("no fallback expected") },
        pairedHost: suspend (String) -> PairedHost? = { route -> host.takeIf { route == "r" } },
    ) = NotificationAnswerer(pairedHost = pairedHost, live = live, background = background)

    @Test
    fun aHealthyAppConnectionCarriesTheAnswer() = runTest {
        val socket = Recording()
        val outcome = answerer(live = { route, block -> if (route == "r") block(socket.commands) else null })
            .answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.SENT, outcome)
        assertEquals(
            listOf(Wire.objectOf("type" to "question.answer", "sessionId" to "s", "questionId" to "q", "answer" to answer)),
            socket.sent,
        )
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
                live = { _, _ ->
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
    fun aSlowPairingLoadStaysInsideTheTimeout() = runTest {
        var tried = false
        val outcome =
            answerer(
                live = { _, _ ->
                    tried = true
                    null
                },
                pairedHost = { CompletableDeferred<PairedHost?>().await() },
            ).answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.FAILED, outcome)
        assertEquals(ANSWER_TIMEOUT_MILLIS, currentTime)
        assertTrue("nothing is sent without the pairing", !tried)
    }

    @Test
    fun anUnpairedHostIsNeverAnswered() = runTest {
        var tried = false
        val outcome =
            answerer(live = { _, _ ->
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
            answerer(live = { _, block -> block(Recording { throw RemoteCommandException("already_resolved") }.commands) })
                .answer("r", "s", "q", answer)
        assertEquals(AnswerOutcome.ALREADY_RESOLVED, refused)
    }
}
