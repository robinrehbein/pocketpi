package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionUpgraderTest {
    private class FakeState : UpgradeState {
        val events = mutableMapOf<String, String>()
        val unreachable = mutableMapOf<String, Long>()

        override fun latestEvent(tag: String) = events[tag]

        override fun setLatestEvent(tag: String, eventId: String?) {
            if (eventId == null) events.remove(tag) else events[tag] = eventId
        }

        override fun unreachableAt(routeId: String) = unreachable[routeId]

        override fun setUnreachableAt(routeId: String, at: Long?) {
            if (at == null) unreachable.remove(routeId) else unreachable[routeId] = at
        }
    }

    private val host = PairedHost("r", "https://relay.example", "d", "secret", "Mac")
    private val confirm =
        Wire.objectOf("id" to "q", "kind" to "confirm", "title" to "Delete?", "message" to "Sure?")

    private fun push(event: String, kind: PushEvent = PushEvent.QUESTION) =
        PushPayload("r", "s", event, kind)

    private fun TestScope.upgrader(
        state: FakeState,
        fetch: suspend (PairedHost, String) -> List<JsonObject>,
    ) = QuestionUpgrader(state, backgroundAnswers = true, now = { currentTime }, fetch = fetch)

    @Test
    fun theLatestPushIsUpgradedWithItsQuestion() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        val result = upgrader(state) { _, _ -> listOf(confirm) }.run(host, push("e1")) { true }
        assertEquals("q", (result as UpgradeResult.Post).content.questionId)
    }

    @Test
    fun aNewerPushSupersedesAnOlderUpgrade() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        recordPushEvent(state, push("e2"))
        val fetched = mutableListOf<String>()
        val result =
            upgrader(state) { _, id -> fetched += id; listOf(confirm) }.run(host, push("e1")) { true }
        assertEquals(UpgradeResult.Superseded, result)
        assertTrue("A superseded upgrade doesn't even connect", fetched.isEmpty())
    }

    @Test
    fun aPushArrivingDuringTheFetchStillWins() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        val gate = CompletableDeferred<Unit>()
        val result = async {
            upgrader(state) { _, _ -> gate.await(); listOf(confirm) }.run(host, push("e1")) { true }
        }
        runCurrent()
        recordPushEvent(state, push("e2"))
        gate.complete(Unit)
        assertEquals(UpgradeResult.Superseded, result.await())
    }

    @Test
    fun completionCancelsAndBlocksThePendingUpgrade() = runTest {
        val state = FakeState()
        assertFalse(recordPushEvent(state, push("e1")))
        assertTrue(recordPushEvent(state, push("e2", PushEvent.COMPLETE)))
        assertNull(state.latestEvent(RemoteNotifications.tag("r", "s")))
        val result = upgrader(state) { _, _ -> listOf(confirm) }.run(host, push("e1")) { true }
        assertEquals(UpgradeResult.Superseded, result)
    }

    @Test
    fun anUnwantedNotificationIsNotBroughtBack() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        val result = upgrader(state) { _, _ -> listOf(confirm) }.run(host, push("e1")) { false }
        assertEquals(UpgradeResult.NotWanted, result)
    }

    @Test
    fun connectionErrorsPostNothingAndMarkTheHostUnreachable() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        advanceTimeBy(1_000)
        val result =
            upgrader(state) { _, _ -> throw RemoteConnectionException() }.run(host, push("e1")) { true }
        assertEquals(UpgradeResult.Unreachable, result)
        assertEquals(1_000L, state.unreachable["r"])
    }

    @Test
    fun hostErrorsMeanNoQuestionButAReachableHost() = runTest {
        val state = FakeState()
        state.unreachable["r"] = 0
        recordPushEvent(state, push("e1"))
        val result =
            upgrader(state) { _, _ -> throw RemoteCommandException("not_found") }.run(host, push("e1")) { true }
        assertEquals(UpgradeResult.NoQuestion, result)
        assertNull(state.unreachable["r"])
    }

    @Test
    fun aSlowHostTimesOutAfterSixSecondsThenAfterTwoWithinTheWindow() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        val hanging = upgrader(state) { _, _ -> awaitCancellation() }
        val start = currentTime
        assertEquals(UpgradeResult.Unreachable, hanging.run(host, push("e1")) { true })
        assertEquals(FETCH_TIMEOUT_MILLIS, currentTime - start)
        val second = currentTime
        assertEquals(UpgradeResult.Unreachable, hanging.run(host, push("e1")) { true })
        assertEquals(UNREACHABLE_FETCH_TIMEOUT_MILLIS, currentTime - second)
        // After the window a host gets the full time again.
        advanceTimeBy(UNREACHABLE_WINDOW_MILLIS + 1)
        val third = currentTime
        hanging.run(host, push("e1")) { true }
        assertEquals(FETCH_TIMEOUT_MILLIS, currentTime - third)
    }

    @Test
    fun noPendingOrUnsupportedQuestionPostsNothing() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        assertEquals(
            UpgradeResult.NoQuestion,
            upgrader(state) { _, _ -> emptyList() }.run(host, push("e1")) { true },
        )
        val unknown = Wire.objectOf("id" to "q", "kind" to "unknown", "options" to JsonArray(listOf(JsonPrimitive("a"))))
        assertEquals(
            UpgradeResult.NoQuestion,
            upgrader(state) { _, _ -> listOf(unknown) }.run(host, push("e1")) { true },
        )
    }

    @Test
    fun belowAndroid12TheUpgradeOffersOnlyInAppAnswers() = runTest {
        val state = FakeState()
        recordPushEvent(state, push("e1"))
        val result =
            QuestionUpgrader(state, backgroundAnswers = false, now = { currentTime }) { _, _ -> listOf(confirm) }
                .run(host, push("e1")) { true }
        assertEquals(listOf(NotificationAction.AnswerInApp), (result as UpgradeResult.Post).content.actions)
    }
}
