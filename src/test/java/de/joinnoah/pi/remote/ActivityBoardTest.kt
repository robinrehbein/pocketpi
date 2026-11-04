package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityBoardTest {
    private val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")

    private fun card(
        id: String,
        status: String,
        updatedAt: Long?,
        pinned: Boolean = false,
        unread: Boolean = false,
    ) = ActivityCard("host", "project", "Project", id, id, null, status, updatedAt, pinned, unread)

    @Test
    fun cardsGroupByWhatTheyNeedFromTheUser() {
        val board =
            activityBoard(
                listOf(
                    card("waiting", "waiting", 1),
                    card("running", "running", 2),
                    card("finished", "idle", 3, unread = true),
                    card("read", "idle", 4),
                    card("offline", "offline", 5, unread = true),
                ),
            )

        assertEquals(listOf("waiting"), board.getValue(ActivityGroup.NEEDS_YOU).map(ActivityCard::sessionId))
        assertEquals(listOf("running"), board.getValue(ActivityGroup.WORKING).map(ActivityCard::sessionId))
        assertEquals(listOf("finished"), board.getValue(ActivityGroup.FINISHED).map(ActivityCard::sessionId))
        assertEquals(listOf(ActivityGroup.NEEDS_YOU, ActivityGroup.WORKING, ActivityGroup.FINISHED), board.keys.toList())
    }

    @Test
    fun pinnedCardsLeadTheirGroupThenTheNewest() {
        val board =
            activityBoard(
                listOf(
                    card("a", "running", 10),
                    card("b", "running", 30),
                    card("c", "running", 5, pinned = true),
                ),
            )

        assertEquals(listOf("c", "b", "a"), board.getValue(ActivityGroup.WORKING).map(ActivityCard::sessionId))
    }

    @Test
    fun theCardsAreTheConnectedMacsSessionsOnceWithTheirMarks() {
        val sessions =
            listOf(
                session("s1", "running", 100),
                session("s2", "waiting", 200),
            )
        val all =
            listOf(
                session("s1", "running", 100),
                session("s2", "waiting", 200),
                session("s3", "idle", 50),
            )
        val state =
            RemoteState(
                hosts = listOf(host),
                hostsLoaded = true,
                host = host,
                selection = RemoteSelection("host", "project", "s1"),
                connected = true,
                session = sessions.first(),
                sessions = sessions,
                projectSessions = mapOf("project" to projectSessions("project", "Project", all, verified = true)),
            )
        val marks = mapOf("s3" to SessionMark(seenUpdatedAt = 10))

        val cards = activityCards(state, marks).associateBy(ActivityCard::sessionId)

        assertEquals(setOf("s1", "s2", "s3"), cards.keys)
        assertEquals(false, cards.getValue("s1").unread)
        assertTrue(cards.getValue("s3").unread)
        assertEquals(1, needsYouCount(state, marks))
    }

    @Test
    fun nothingIsListedWithoutAMac() {
        assertTrue(activityCards(RemoteState(), emptyMap()).isEmpty())
        assertNull(activityGroup(card("idle", "idle", 1)))
    }

    private fun session(id: String, status: String, updatedAt: Long): JsonObject =
        Wire.objectOf("id" to id, "title" to id, "origin" to "rpc", "status" to status, "updatedAt" to updatedAt)
}
