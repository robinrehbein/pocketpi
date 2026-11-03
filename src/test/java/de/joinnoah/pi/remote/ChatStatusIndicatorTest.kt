package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStatusIndicatorTest {
    private val here = RemoteSelection("host", "project", "s1")
    private val fresh = RemoteState(connected = true, status = "running", selection = here)
    private val question = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("q1")) }

    @Test
    fun onlyAFreshlyConfirmedRunAnimates() {
        fun chatStatusLive(state: RemoteState) = chatStatusLive(state, here)
        assertTrue(chatStatusLive(fresh))
        assertFalse(chatStatusLive(fresh.copy(stale = true)))
        assertFalse(chatStatusLive(fresh.copy(loading = true)))
        assertFalse(chatStatusLive(fresh.copy(preview = true)))
        assertFalse(chatStatusLive(fresh.copy(connected = false)))
        assertFalse(chatStatusLive(fresh.copy(status = "waiting")))
        assertFalse(chatStatusLive(fresh.copy(status = "idle")))
        assertFalse(chatStatusLive(fresh.copy(status = "unrecognized")))
        assertFalse(chatStatusLive(fresh.copy(questions = listOf(question))))
    }

    @Test
    fun onlyTheRenderedSessionAnimatesEvenWithoutLoading() {
        // The state already belongs to another session while the old chat still renders.
        val switched = fresh.copy(selection = here.copy(sessionId = "s2"))
        assertFalse(chatStatusLive(switched, here))
        assertTrue(chatStatusLive(switched, here.copy(sessionId = "s2")))
        assertFalse(chatStatusLive(fresh, null))
        assertFalse(chatStatusLive(fresh, here.copy(sessionId = null)))
    }

    @Test
    fun anOpenQuestionShowsWaitingNotWork() {
        val asking = chatHeaderState(fresh.copy(questions = listOf(question)))
        assertEquals(ChatStatusShape.WAITING, chatStatusShape(asking))
        assertEquals(R.string.remote_status_waiting, chatStatusLabel(asking))
        assertFalse(chatStatusLive(asking, here))
        assertEquals(fresh, chatHeaderState(fresh))
    }

    @Test
    fun waitingKeepsTheDiamondAndStaleRunningKeepsThePlayMark() {
        assertEquals(ChatStatusShape.WAITING, chatStatusShape(fresh.copy(status = "waiting")))
        assertEquals(ChatStatusShape.RUNNING, chatStatusShape(fresh.copy(stale = true)))
        assertEquals(ChatStatusShape.OFFLINE, chatStatusShape(fresh.copy(connected = false)))
    }
}
