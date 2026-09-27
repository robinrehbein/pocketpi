package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {
    @Test
    fun staleOrOtherSessionEventsNeverReachCurrentConversation() {
        val timeline = Timeline("one")
        timeline.snapshot(
            Wire.objectOf(
                "sessionId" to "one",
                "revision" to 3,
                "status" to "idle",
                "messages" to kotlinx.serialization.json.JsonArray(emptyList()),
                "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
            )
        )
        assertFalse(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "two",
                    "revision" to 4,
                    "kind" to "session.status",
                    "status" to "running",
                )
            )
        )
        assertEquals("idle", timeline.status)
        assertFalse(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "one",
                    "revision" to 2,
                    "kind" to "session.status",
                    "status" to "running",
                )
            )
        )
        assertTrue(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "one",
                    "revision" to 5,
                    "kind" to "session.status",
                    "status" to "running",
                )
            )
        )
        assertEquals("idle", timeline.status)
    }

    @Test
    fun pendingRequestCannotResolveAfterSelectionChanges() {
        val requests = RequestLedger()
        requests.add("r1", PendingRequest("session.prompt", "one", epoch = 2, draft = "hello"))
        assertNull(requests.take("r1", 3))
        requests.add("r2", PendingRequest("session.snapshot", "two", epoch = 3))
        assertEquals("two", requests.take("r2", 3)?.sessionId)
        assertNull(requests.take("r2", 3))
        requests.add("r3", PendingRequest("session.prompt", "two", epoch = 3))
        requests.clear()
        assertNull(requests.take("r3", 3))
    }
}

class SnapshotFencingTest {
    private fun snapshot(revision: Long, vararg ids: String) =
        Wire.objectOf(
            "sessionId" to "session",
            "revision" to revision,
            "status" to "idle",
            "messages" to
                kotlinx.serialization.json.JsonArray(ids.map { Wire.objectOf("id" to it) }),
            "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
        )

    @Test
    fun olderPagesPreserveOrderAndCannotReplaceNewerState() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(8, "newest"))
        timeline.snapshot(snapshot(8, "oldest", "newest"), older = true)
        assertEquals(listOf("oldest", "newest"), timeline.messages.map { it.text("id") })
        timeline.snapshot(snapshot(7, "stale"))
        timeline.snapshot(snapshot(9, "different-revision"), older = true)
        assertEquals(listOf("oldest", "newest"), timeline.messages.map { it.text("id") })
    }

    @Test
    fun eventReceivedBeforeSnapshotIsAppliedAfterSnapshot() {
        val timeline = Timeline("session")
        assertTrue(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "session",
                    "revision" to 9,
                    "kind" to "session.status",
                    "status" to "running",
                )
            )
        )
        timeline.snapshot(snapshot(8, "first"))
        assertEquals("running", timeline.status)
        assertEquals(9L, timeline.revision)
    }

    @Test
    fun foregroundPushTitleUsesEventDiscriminator() {
        assertEquals(R.string.remote_notification_question, notificationTitle("question"))
        assertEquals(R.string.remote_notification_complete, notificationTitle("complete"))
    }

    @Test
    fun frozenHistoryPagesMergeAfterLiveEventsWithoutReplacingLiveState() {
        val timeline = Timeline("session")
        val message = Wire.objectOf("id" to "shared", "text" to "old")
        val first =
            kotlinx.serialization.json.JsonObject(
                snapshot(8, "newest") +
                    mapOf(
                        "status" to kotlinx.serialization.json.JsonPrimitive("running"),
                        "nextCursor" to kotlinx.serialization.json.JsonPrimitive("page-two"),
                    )
            )
        timeline.snapshot(first)
        timeline.event(
            Wire.objectOf(
                "sessionId" to "session",
                "revision" to 9,
                "kind" to "message.upsert",
                "message" to Wire.objectOf("id" to "shared", "text" to "live"),
            )
        )
        timeline.event(
            Wire.objectOf(
                "sessionId" to "session",
                "revision" to 10,
                "kind" to "session.status",
                "status" to "idle",
            )
        )
        val question = Wire.objectOf("id" to "question", "kind" to "input", "title" to "Answer")
        timeline.event(
            Wire.objectOf(
                "sessionId" to "session",
                "revision" to 11,
                "kind" to "question.open",
                "question" to question,
            )
        )
        val second =
            kotlinx.serialization.json.JsonObject(
                snapshot(8) +
                    mapOf(
                        "messages" to
                            kotlinx.serialization.json.JsonArray(
                                listOf(Wire.objectOf("id" to "older"), message)
                            ),
                        "status" to kotlinx.serialization.json.JsonPrimitive("running"),
                        "nextCursor" to kotlinx.serialization.json.JsonPrimitive("page-three"),
                    )
            )
        timeline.snapshot(second, older = true)
        assertEquals(listOf("older", "shared", "newest"), timeline.messages.map { it.text("id") })
        assertEquals("live", timeline.messages.first { it.text("id") == "shared" }.text("text"))
        assertEquals("idle", timeline.status)
        assertEquals(listOf(question), timeline.questions)
        assertEquals("page-three", timeline.nextCursor)
        timeline.snapshot(snapshot(8, "oldest"), older = true)
        assertEquals(
            listOf("oldest", "older", "shared", "newest"),
            timeline.messages.map { it.text("id") },
        )
        assertEquals(11L, timeline.revision)
        assertEquals("idle", timeline.status)
        assertEquals(listOf(question), timeline.questions)
        assertNull(timeline.nextCursor)
    }

    @Test
    fun snapshotCompletionReplacesStaleRunningSessionSummary() {
        val timeline = Timeline("session").apply { status = "running" }
        timeline.snapshot(snapshot(12, "completed"))
        assertEquals("idle", timeline.status)
    }

    @Test
    fun openingAfterDaemonRestartResetsRevisionAndBuffersNewEvents() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(200, "previous"))
        timeline.beginSnapshotEpoch("running")
        timeline.event(
            Wire.objectOf(
                "sessionId" to "session",
                "revision" to 1,
                "kind" to "session.status",
                "status" to "idle",
            )
        )
        timeline.snapshot(
            kotlinx.serialization.json.JsonObject(
                snapshot(0, "restarted") +
                    ("status" to kotlinx.serialization.json.JsonPrimitive("running"))
            )
        )
        assertEquals(listOf("restarted"), timeline.messages.map { it.text("id") })
        assertEquals("idle", timeline.status)
        assertEquals(1L, timeline.revision)
    }

    private fun insight(name: String): kotlinx.serialization.json.JsonObject =
        Wire.json.parseToJsonElement(javaClass.getResource("/insights-v1.json")!!.readText())
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("valid").let { it as kotlinx.serialization.json.JsonArray }
            .map { it as kotlinx.serialization.json.JsonObject }
            .single { it.text("name") == name }
            .obj("payload")

    private fun at(event: kotlinx.serialization.json.JsonObject, revision: Long) =
        kotlinx.serialization.json.JsonObject(
            event + ("revision" to kotlinx.serialization.json.JsonPrimitive(revision))
        )

    private fun snapshotData(sessionId: String, revision: Long, compacting: Boolean? = null) =
        kotlinx.serialization.json.JsonObject(
            Wire.objectOf(
                "sessionId" to sessionId,
                "revision" to revision,
                "status" to "running",
                "messages" to kotlinx.serialization.json.JsonArray(emptyList()),
                "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
            ) + listOfNotNull(
                compacting?.let { "compacting" to kotlinx.serialization.json.JsonPrimitive(it) }
            )
        )

    @Test
    fun compactionEventsSetAndClearWithoutResnapshot() {
        var clock = 1_000L
        val running = insight("compaction-running")
        val done = insight("compaction-done")
        val sessionId = running.text("sessionId")
        val timeline = Timeline(sessionId) { clock }
        timeline.snapshot(snapshotData(sessionId, 14))

        assertFalse(timeline.event(running))
        assertEquals(SessionCompaction("threshold", 1_000L), timeline.compaction)
        assertTrue(compactionVisible(timeline.compaction, 1_000L + 9 * 60_000L))
        assertFalse(compactionVisible(timeline.compaction, 1_000L + 10 * 60_000L))
        assertFalse(compactionVisible(null, 0L))

        clock = 5_000L
        val repeated = kotlinx.serialization.json.JsonObject(
            at(running, 16) - "reason"
        )
        assertFalse(timeline.event(repeated))
        assertEquals(SessionCompaction("threshold", 1_000L), timeline.compaction)

        assertFalse(timeline.event(at(done, 17)))
        assertNull(timeline.compaction)
        assertFalse(timeline.needsSnapshot)
        assertEquals(17L, timeline.revision)

        for (state in listOf("failed", "aborted")) {
            timeline.event(at(running, timeline.revision + 1))
            assertNotNull(timeline.compaction)
            val terminal = kotlinx.serialization.json.JsonObject(
                at(done, timeline.revision + 1) + ("state" to kotlinx.serialization.json.JsonPrimitive(state))
            )
            assertFalse(timeline.event(terminal))
            assertNull(timeline.compaction)
        }
    }

    @Test
    fun unknownCompactionStateOrReasonIsIgnored() {
        val running = insight("compaction-running")
        val sessionId = running.text("sessionId")
        val timeline = Timeline(sessionId) { 42L }
        timeline.snapshot(snapshotData(sessionId, 14))
        val paused = kotlinx.serialization.json.JsonObject(
            running + ("state" to kotlinx.serialization.json.JsonPrimitive("paused"))
        )
        assertFalse(timeline.event(paused))
        assertNull(timeline.compaction)
        val numeric = kotlinx.serialization.json.JsonObject(
            at(running, 16) + ("state" to kotlinx.serialization.json.JsonPrimitive(1))
        )
        assertFalse(timeline.event(numeric))
        assertNull(timeline.compaction)
        val oddReason = kotlinx.serialization.json.JsonObject(
            at(running, 17) + ("reason" to kotlinx.serialization.json.JsonPrimitive("weather"))
        )
        assertFalse(timeline.event(oddReason))
        assertEquals(SessionCompaction(null, 42L), timeline.compaction)
        assertEquals(17L, timeline.revision)
    }

    @Test
    fun snapshotCompactingSetsKeepsAndClears() {
        var clock = 100L
        val data = insight("snapshot-compacting").obj("data")
        val sessionId = data.text("sessionId")
        val timeline = Timeline(sessionId) { clock }
        timeline.snapshot(data)
        assertEquals(SessionCompaction(null, 100L), timeline.compaction)

        clock = 200L
        timeline.snapshot(snapshotData(sessionId, 16, compacting = true))
        assertEquals(SessionCompaction(null, 100L), timeline.compaction)

        timeline.snapshot(snapshotData(sessionId, 17, compacting = false))
        assertNull(timeline.compaction)

        timeline.snapshot(snapshotData(sessionId, 18, compacting = true))
        timeline.snapshot(snapshotData(sessionId, 19))
        assertNull(timeline.compaction)

        timeline.snapshot(snapshotData(sessionId, 20, compacting = true))
        timeline.beginSnapshotEpoch("idle")
        assertNull(timeline.compaction)
    }

    @Test
    fun eventStreamWithoutCompactionLeavesItUnset() {
        val timeline = Timeline("one") { 1L }
        timeline.snapshot(snapshotData("one", 0))
        assertFalse(
            timeline.event(
                Wire.objectOf("sessionId" to "one", "revision" to 1, "kind" to "session.status", "status" to "idle")
            )
        )
        assertFalse(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "one",
                    "revision" to 2,
                    "kind" to "message.upsert",
                    "message" to Wire.objectOf("id" to "m", "role" to "assistant", "text" to "hi"),
                )
            )
        )
        assertTrue(
            timeline.event(Wire.objectOf("sessionId" to "one", "revision" to 3, "kind" to "future.kind"))
        )
        assertNull(timeline.compaction)
        assertEquals("idle", timeline.status)
        assertEquals(1, timeline.messages.size)
    }
}
