package de.joinnoah.pi.remote

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionCompactionsTest {
    private fun event(id: String, revision: Long, state: String, reason: String = "manual") =
        Wire.objectOf("kind" to "session.compaction", "sessionId" to id,
            "revision" to revision, "state" to state, "reason" to reason)

    @Test fun tracksIndependentSessionsAndRejectsStaleOrDuplicateEvents() {
        val activity = SessionCompactions { 100L }
        activity.event(event("a", 10, "running"))
        activity.event(event("b", 200, "running", "threshold"))
        assertEquals(setOf("a", "b"), activity.values.keys)
        assertEquals(SessionCompaction("threshold", 100), activity.values["b"])
        activity.event(event("a", 12, "done"))
        activity.event(event("a", 10, "running"))
        activity.event(event("a", 12, "running"))
        assertEquals(setOf("b"), activity.values.keys)
    }

    @Test fun everyTerminalStateClearsAndUnknownStateIsIgnored() {
        val activity = SessionCompactions { 100L }
        for (terminal in listOf("done", "failed", "aborted")) {
            activity.clear()
            activity.event(event("a", 1, "running", "future-reason"))
            assertNull(activity.values.getValue("a").reason)
            activity.event(event("a", 3, "future-state"))
            assertTrue(activity.values.containsKey("a"))
            activity.event(event("a", 2, terminal))
            assertTrue(activity.values.isEmpty())
        }
    }

    @Test fun acceptedTimelineSuppliesStateAndRevisionFloor() {
        val activity = SessionCompactions { 100L }
        activity.acceptedTimeline("a", 10, SessionCompaction("manual", 50))
        activity.event(event("a", 9, "done"))
        assertEquals(SessionCompaction("manual", 50), activity.values["a"])
        activity.acceptedTimeline("a", 12, null)
        activity.event(event("a", 11, "running"))
        assertTrue(activity.values.isEmpty())
    }

    @Test fun timelineWithPendingGapCannotOverwriteNewerGlobalEvent() {
        val activity = SessionCompactions { 100L }
        activity.event(event("a", 20, "running"))
        activity.acceptedTimeline("a", 10, null)
        activity.event(event("a", 19, "done"))
        assertTrue(activity.values.containsKey("a"))
    }

    @Test fun idleDoesNotEndCompactionButOfflineDoes() {
        val activity = SessionCompactions { 100L }
        activity.event(event("a", 1, "running"))
        activity.status(Wire.objectOf("sessionId" to "a", "revision" to 2, "status" to "idle"))
        assertTrue(activity.values.containsKey("a"))
        activity.status(Wire.objectOf("sessionId" to "a", "revision" to 3, "status" to "offline"))
        activity.event(event("a", 2, "running"))
        assertTrue(activity.values.isEmpty())
    }

    @Test fun consumesAuthoritativeHostListFixture() {
        val fixture = Wire.json.parseToJsonElement(
            javaClass.getResource("/insights-v1.json")!!.readText(),
        ).jsonObject.array("compactionListValid")
        val result = fixture.single { it.text("name") == "sessions-list-compaction-result" }
            .obj("payload").obj("data")
        val activity = SessionCompactions { 100L }
        activity.list(result.array("items"), activity.watermark())
        assertEquals(setOf("active-worker"), activity.values.keys)
    }

    @Test fun optedInListRestoresAndClearsStateAfterReconnect() {
        val activity = SessionCompactions { 100L }
        activity.list(listOf(Wire.objectOf("id" to "a", "compacting" to true)), activity.watermark())
        assertEquals(SessionCompaction(null, 100), activity.values["a"])
        activity.list(listOf(Wire.objectOf("id" to "a")), activity.watermark())
        assertTrue(activity.values.isEmpty())
        val previousEpoch = activity.watermark()
        activity.clear()
        activity.list(listOf(Wire.objectOf("id" to "a", "compacting" to true)), previousEpoch)
        assertTrue(activity.values.isEmpty())
        activity.list(listOf(Wire.objectOf("id" to "a", "compacting" to true)), activity.watermark())
        assertTrue(activity.values.containsKey("a"))
    }

    @Test fun eventsAndSnapshotsDuringListRequestWinPerSession() {
        val activity = SessionCompactions { 100L }
        activity.event(event("ended", 1, "running"))
        val before = activity.watermark()
        activity.event(event("started", 10, "running"))
        activity.event(event("ended", 2, "done"))
        activity.acceptedTimeline("snapshot", 5, SessionCompaction(null, 100))
        activity.status(Wire.objectOf("sessionId" to "offline", "revision" to 10, "status" to "offline"))
        activity.list(listOf(
            Wire.objectOf("id" to "started"),
            Wire.objectOf("id" to "ended", "compacting" to true),
            Wire.objectOf("id" to "snapshot"),
            Wire.objectOf("id" to "offline", "compacting" to true),
            Wire.objectOf("id" to "unrelated", "compacting" to true),
        ), before)
        assertEquals(setOf("started", "snapshot", "unrelated"), activity.values.keys)
    }

    @Test fun newerListResultCannotBeOverwrittenByAnEarlierRequest() {
        val activity = SessionCompactions { 100L }
        val earlier = activity.watermark()
        val later = activity.watermark()
        activity.list(listOf(Wire.objectOf("id" to "a")), later)
        activity.list(listOf(Wire.objectOf("id" to "a", "compacting" to true)), earlier)
        assertTrue(activity.values.isEmpty())
    }

    @Test fun acceptedTimelineBeatsAnOlderListRequest() {
        val activity = SessionCompactions { 100L }
        val list = activity.watermark()
        activity.acceptedTimeline("a", 5, SessionCompaction(null, 100))
        activity.list(listOf(Wire.objectOf("id" to "a")), list)
        assertTrue(activity.values.containsKey("a"))
    }

    @Test fun journalChangeAllowsLowerRevisionsWithoutFlicker() {
        val activity = SessionCompactions { 100L }
        activity.event(event("a", 100, "running"))
        activity.forgetRevision("a")
        assertTrue(activity.values.containsKey("a"))
        activity.event(event("a", 1, "done"))
        assertTrue(activity.values.isEmpty())
    }

    @Test fun laterListRequestStillAppliesWhenEarlierResultArrivesFirst() {
        val activity = SessionCompactions { 100L }
        val earlier = activity.watermark()
        val later = activity.watermark()
        activity.list(listOf(Wire.objectOf("id" to "a")), earlier)
        activity.list(listOf(Wire.objectOf("id" to "a", "compacting" to true)), later)
        assertTrue(activity.values.containsKey("a"))
    }

    @Test fun everyInsertionPathBoundsItsStateAndKeepsRecentRevisionFloors() {
        for (timelineWrite in listOf(false, true)) {
            val activity = SessionCompactions { 100L }
            activity.event(event("oldest", 100, "running"))
            repeat(4096) { index ->
                if (timelineWrite) activity.acceptedTimeline("s$index", 10, null)
                else activity.forgetRevision("s$index")
            }
            assertFalse(activity.values.containsKey("oldest"))
            if (timelineWrite) {
                activity.event(event("s4095", 9, "running"))
                assertFalse(activity.values.containsKey("s4095"))
            }
        }
    }

    @Test fun clearStartsANewHostEpoch() {
        val activity = SessionCompactions { 100L }
        activity.event(event("a", 1000, "running"))
        activity.clear()
        assertTrue(activity.values.isEmpty())
        activity.event(event("a", 1, "running"))
        assertTrue(activity.values.containsKey("a"))
    }
}
