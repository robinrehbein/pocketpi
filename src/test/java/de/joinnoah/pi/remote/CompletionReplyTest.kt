package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionReplyTest {
    private val host = PairedHost("r", "https://relay.example", "d", "secret", "Mac")

    /** What one socket was asked to send, by request ID, and how it answers. */
    private class Socket(private val answer: suspend (String) -> JsonObject = { Wire.objectOf("kind" to "accepted") }) :
        MutationCommands {
        val sent = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun request(type: String, vararg fields: Pair<String, Any?>) = error("not used")

        override suspend fun mutate(requestId: String, type: String, vararg fields: Pair<String, Any?>): JsonObject {
            sent += requestId to Wire.objectOf("type" to type, *fields)
            return answer(requestId)
        }
    }

    private fun replier(
        live: Socket?,
        background: Socket = Socket(),
        paired: PairedHost? = host,
        order: MutableList<String> = mutableListOf(),
        timeoutMillis: Long = REPLY_TIMEOUT_MILLIS,
    ) =
        CompletionReplier(
            pairedHost = { paired },
            live = { routeId, block ->
                order += "live:$routeId"
                if (live == null) null else block(live)
            },
            background = { pairedHost, block ->
                order += "background:${pairedHost.routeId}"
                block(background)
            },
            mutationId = { "mutation-1" },
            timeoutMillis = timeoutMillis,
        )

    @Test
    fun aHealthyAppConnectionCarriesTheReplyAndNoOtherIsOpened() = runTest {
        val live = Socket()
        val background = Socket()
        val order = mutableListOf<String>()
        assertEquals(ReplyOutcome.SENT, replier(live, background, order = order).send("r", "s", "Go on"))
        assertEquals(listOf("live:r"), order)
        val (id, prompt) = live.sent.single()
        assertEquals("mutation-1", id)
        assertEquals("session.prompt", prompt.text("type"))
        assertEquals("s", prompt.text("sessionId"))
        assertEquals("Go on", prompt.text("text"))
        assertTrue(background.sent.isEmpty())
    }

    @Test
    fun withoutAHealthyConnectionTheLiveSocketIsTriedFirstThenTheBackgroundOneUnderTheSameId() = runTest {
        val background = Socket()
        val order = mutableListOf<String>()
        assertEquals(ReplyOutcome.SENT, replier(live = null, background = background, order = order).send("r", "s", "Go on"))
        assertEquals(listOf("live:r", "background:r"), order)
        assertEquals("mutation-1", background.sent.single().first)
    }

    @Test
    fun aLiveSendWithoutAnAcknowledgementIsUncertainAndNotSentAgain() = runTest {
        val live = Socket { throw RemoteRequestException.timeout() }
        val background = Socket()
        val order = mutableListOf<String>()
        assertEquals(ReplyOutcome.UNCERTAIN, replier(live, background, order = order).send("r", "s", "Go on"))
        assertEquals(1, live.sent.size)
        assertTrue(background.sent.isEmpty())
        assertEquals(listOf("live:r"), order)
    }

    @Test
    fun aLiveSendRefusedBeforeItLeftGoesOnOverTheBackgroundConnection() = runTest {
        // The app's connection dropped between the health check and the send.
        val live = Socket { throw MutationNotSentException() }
        val background = Socket()
        val order = mutableListOf<String>()
        assertEquals(ReplyOutcome.SENT, replier(live, background, order = order).send("r", "s", "Go on"))
        assertEquals(listOf("live:r", "background:r"), order)
        assertEquals("mutation-1", background.sent.single().first)
        // Refused on both: nothing left the phone.
        assertEquals(
            ReplyOutcome.FAILED,
            replier(Socket { throw MutationNotSentException() }, Socket { throw MutationNotSentException() })
                .send("r", "s", "Go on"),
        )
    }

    @Test
    fun aLostBackgroundConnectionAfterTheSendIsUncertain() = runTest {
        val background = Socket { throw RemoteConnectionException() }
        assertEquals(ReplyOutcome.UNCERTAIN, replier(live = null, background = background).send("r", "s", "Go on"))
        assertEquals(1, background.sent.size)
    }

    @Test
    fun aReplyStillWaitingAtTheDeadlineIsUncertain() = runTest {
        val background = Socket { awaitCancellation() }
        assertEquals(
            ReplyOutcome.UNCERTAIN,
            replier(live = null, background = background, timeoutMillis = 1_000).send("r", "s", "Go on"),
        )
        assertEquals(1, background.sent.size)
    }

    @Test
    fun aRefusedReplyFailedAndOneThatNeverLeftFailedToo() = runTest {
        val refusedLive = Socket { throw RemoteRequestException("busy") }
        assertEquals(ReplyOutcome.FAILED, replier(refusedLive).send("r", "s", "Go on"))
        val refused = Socket { throw RemoteCommandException("busy") }
        assertEquals(ReplyOutcome.FAILED, replier(live = null, background = refused).send("r", "s", "Go on"))
        // The connection failed before the prompt could be sent.
        val unreachable =
            CompletionReplier(
                pairedHost = { host },
                live = { _, _ -> null },
                background = { _, _ -> throw RemoteConnectionException() },
            )
        assertEquals(ReplyOutcome.FAILED, unreachable.send("r", "s", "Go on"))
        // A connection that never comes up within the deadline sent nothing either.
        val hanging =
            CompletionReplier(
                pairedHost = { host },
                live = { _, _ -> null },
                background = { _, _ -> CompletableDeferred<Unit>().await() },
                timeoutMillis = 1_000,
            )
        assertEquals(ReplyOutcome.FAILED, hanging.send("r", "s", "Go on"))
    }

    @Test
    fun anUnpairedMacFailsWithoutSending() = runTest {
        val background = Socket()
        assertEquals(ReplyOutcome.FAILED, replier(live = null, background = background, paired = null).send("r", "s", "Go on"))
        assertTrue(background.sent.isEmpty())
    }

    @Test
    fun eachReplyGetsAFreshMutationId() = runTest {
        val live = Socket()
        var next = 0
        val replier =
            CompletionReplier(
                pairedHost = { host },
                live = { _, block -> block(live) },
                background = { _, _ -> error("not used") },
                mutationId = { "mutation-${++next}" },
            )
        replier.send("r", "s", "One")
        replier.send("r", "s", "Two")
        assertEquals(listOf("mutation-1", "mutation-2"), live.sent.map { it.first })
    }

    @Test
    fun repliesAreTrimmedAndBlankOrHugeOnesRejected() {
        assertEquals("Go on", replyText("  Go on \n"))
        assertNull(replyText("   "))
        assertNull(replyText(null))
        assertNull(replyText("x".repeat(40_000)))
    }
}
