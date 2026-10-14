package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionStateTest {
    @Test
    fun questionsFollowSnapshotOpenAndCloseWithSessionAndRevisionFencing() {
        val timeline = Timeline("one")
        val initial = Wire.objectOf("id" to "initial", "kind" to "confirm")
        val opened = Wire.objectOf("id" to "opened", "kind" to "input")
        timeline.snapshot(Wire.objectOf(
            "sessionId" to "one", "revision" to 3, "status" to "idle",
            "messages" to kotlinx.serialization.json.JsonArray(emptyList()),
            "pendingQuestions" to kotlinx.serialization.json.JsonArray(listOf(initial)),
        ))
        assertEquals(listOf(initial), timeline.questions)
        fun event(session: String, revision: Long, kind: String) = Wire.objectOf(
            "sessionId" to session, "revision" to revision, "kind" to kind,
            "question" to opened, "questionId" to "initial",
        )
        timeline.event(event("two", 4, "question.open"))
        timeline.event(event("one", 2, "question.open"))
        assertEquals(listOf(initial), timeline.questions)
        timeline.event(event("one", 4, "question.open"))
        assertEquals(listOf(initial, opened), timeline.questions)
        timeline.event(event("two", 5, "question.closed"))
        timeline.event(event("one", 3, "question.closed"))
        assertEquals(listOf(initial, opened), timeline.questions)
        timeline.event(event("one", 5, "question.closed"))
        assertEquals(listOf(opened), timeline.questions)
        timeline.snapshot(Wire.objectOf(
            "sessionId" to "one", "revision" to 6, "status" to "idle",
            "messages" to kotlinx.serialization.json.JsonArray(emptyList()),
            "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
        ))
        assertTrue(timeline.questions.isEmpty())
    }

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
    fun noticesAdvanceTheRevisionWithoutASnapshotAndParseStrictly() {
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
        fun notice(level: Any, message: Any) =
            Wire.objectOf(
                "type" to "event",
                "sessionId" to "one",
                "revision" to 4,
                "kind" to "session.notice",
                "level" to level,
                "message" to message,
            )
        val event = notice("info", "No artifacts in this session yet.")
        assertFalse(timeline.event(event))
        assertEquals(4L, timeline.revision)
        assertEquals(
            SessionNotice(7, "one", "info", "No artifacts in this session yet."),
            sessionNotice(event, 7),
        )
        assertEquals("error", sessionNotice(notice("error", "x"), 1)?.level)
        assertNull(sessionNotice(notice("debug", "x"), 1))
        assertNull(sessionNotice(notice("info", " "), 1))
        assertNull(sessionNotice(notice("info", 3), 1))
        assertNull(sessionNotice(notice("info", "é".repeat(MAX_SESSION_NOTICE_BYTES / 2 + 1)), 1))
        assertNotNull(sessionNotice(notice("info", "a".repeat(MAX_SESSION_NOTICE_BYTES)), 1))
    }

    @Test
    fun sharedNoticeFixturesParseAsTheHostDoes() {
        val fixture =
            Wire.json.parseToJsonElement(javaClass.getResource("/session-notice-v1.json")!!.readText()).jsonObject
        fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }
        assertTrue(payloads("wireValid").isNotEmpty())
        for ((name, payload) in payloads("wireValid")) assertNotNull(name, sessionNotice(payload, 1))
        assertTrue(payloads("wireInvalid").isNotEmpty())
        for ((name, payload) in payloads("wireInvalid")) assertNull(name, sessionNotice(payload, 1))
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

    private fun withCursor(data: kotlinx.serialization.json.JsonObject, cursor: String?) =
        kotlinx.serialization.json.JsonObject(
            data + ("nextCursor" to (cursor?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull))
        )

    @Test
    fun eventsBeforeTheFirstSnapshotLeaveItMissing() {
        val timeline = Timeline("session")
        timeline.beginSnapshotEpoch("running")
        // A gap, an unknown kind and another session's event: none of them is a snapshot.
        assertTrue(
            timeline.event(
                Wire.objectOf("sessionId" to "session", "revision" to 5, "kind" to "session.status", "status" to "idle")
            )
        )
        timeline.event(Wire.objectOf("sessionId" to "session", "revision" to 0, "kind" to "future.kind"))
        timeline.event(
            Wire.objectOf("sessionId" to "other", "revision" to 1, "kind" to "session.status", "status" to "idle")
        )
        assertFalse(timeline.hasSnapshot)
        assertTrue(timeline.messages.isEmpty())
        timeline.snapshot(snapshot(8, "first"))
        assertTrue(timeline.hasSnapshot)
        timeline.beginSnapshotEpoch("idle")
        assertFalse(timeline.hasSnapshot)
    }

    @Test
    fun resyncOfTheSameSessionKeepsOlderPagesAndTheirCursor() {
        val timeline = Timeline("session")
        timeline.snapshot(withCursor(snapshot(8, "c", "d"), "before-c"))
        timeline.snapshot(withCursor(snapshot(8, "a", "b"), "before-a"), older = true)
        assertEquals(listOf("a", "b", "c", "d"), timeline.messages.map { it.text("id") })

        // The newest page now starts at d: a, b and c stay before it, and so does the cursor.
        timeline.snapshot(withCursor(snapshot(12, "d", "e"), "before-d"))
        assertEquals(listOf("a", "b", "c", "d", "e"), timeline.messages.map { it.text("id") })
        assertEquals("before-a", timeline.nextCursor)

        // A page that does not overlap what is shown replaces it.
        timeline.snapshot(withCursor(snapshot(20, "x", "y"), "before-x"))
        assertEquals(listOf("x", "y"), timeline.messages.map { it.text("id") })
        assertEquals("before-x", timeline.nextCursor)
    }

    @Test
    fun aNewEpochKeepsWhatWasShownForItsFirstSnapshot() {
        val timeline = Timeline("session")
        timeline.retain(
            listOf(Wire.objectOf("id" to "a"), Wire.objectOf("id" to "b"), Wire.objectOf("id" to "c")),
            "before-a",
        )
        timeline.snapshot(withCursor(snapshot(3, "b", "c", "d"), "before-b"))
        assertEquals(listOf("a", "b", "c", "d"), timeline.messages.map { it.text("id") })
        assertEquals("before-a", timeline.nextCursor)

        timeline.beginSnapshotEpoch("idle")
        timeline.snapshot(withCursor(snapshot(0, "c", "d"), "before-c"))
        assertEquals(listOf("a", "b", "c", "d"), timeline.messages.map { it.text("id") })
        assertEquals("before-a", timeline.nextCursor)
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
        // A newer host's kind advances the revision and asks for a resync instead of a snapshot now.
        assertFalse(
            timeline.event(Wire.objectOf("sessionId" to "one", "revision" to 3, "kind" to "future.kind"))
        )
        assertTrue(timeline.unknownKindSeen)
        assertEquals(3L, timeline.revision)
        assertNull(timeline.compaction)
        assertEquals("idle", timeline.status)
        assertEquals(1, timeline.messages.size)
    }

    @Test
    fun malformedBufferedEventDoesNotUndoTheSnapshotAndAsksForAResync() {
        val timeline = Timeline("one") { 1L }
        timeline.snapshot(snapshotData("one", 0))
        // Revision 2 arrives before 1, so it waits for the next snapshot; it has no message.
        assertTrue(
            timeline.event(Wire.objectOf("sessionId" to "one", "revision" to 2, "kind" to "message.upsert"))
        )
        assertFalse(timeline.unknownKindSeen)

        timeline.snapshot(snapshotData("one", 1))

        assertEquals(2L, timeline.revision)
        assertTrue(timeline.unknownKindSeen)
        assertFalse(timeline.needsSnapshot)
    }

    @Test
    fun unknownStatusEventKeepsTheStatusAndThrowsAfterAdvancingTheRevision() {
        val timeline = Timeline("one") { 1L }
        timeline.snapshot(snapshotData("one", 0))
        assertThrows(IllegalArgumentException::class.java) {
            timeline.event(Wire.objectOf("sessionId" to "one", "revision" to 1, "kind" to "session.status", "status" to "retrying"))
        }
        // The revision stays in step; the caller marks the timeline for a resync.
        assertEquals(1L, timeline.revision)
        assertEquals("running", timeline.status)
    }

    @Test
    fun eventWithoutASessionIsNotTheTimelines() {
        val timeline = Timeline("one") { 1L }
        timeline.snapshot(snapshotData("one", 0))
        assertFalse(timeline.event(Wire.objectOf("revision" to 1, "kind" to "host.future")))
        assertEquals(0L, timeline.revision)
        assertFalse(timeline.unknownKindSeen)
    }
}

class TimelineIndexTest {
    private fun message(id: String, text: String = id) = Wire.objectOf("id" to id, "role" to "assistant", "text" to text)

    private fun snapshot(revision: Long, vararg messages: kotlinx.serialization.json.JsonObject, cursor: String? = null) =
        Wire.objectOf(
            "sessionId" to "session",
            "revision" to revision,
            "status" to "running",
            "messages" to kotlinx.serialization.json.JsonArray(messages.toList()),
            "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
            "nextCursor" to cursor,
        )

    private fun upsert(timeline: Timeline, message: kotlinx.serialization.json.JsonObject) =
        assertFalse(
            timeline.event(
                Wire.objectOf(
                    "sessionId" to "session",
                    "revision" to timeline.revision + 1,
                    "kind" to "message.upsert",
                    "message" to message,
                )
            )
        )

    /** Every ID maps to its first position, as a scan from the start finds it. */
    private fun assertIndexed(timeline: Timeline, vararg absent: String) {
        for (message in timeline.messages) {
            val id = message.text("id")
            assertEquals(id, timeline.messages.indexOfFirst { it.text("id") == id }, timeline.messageIndex(id))
        }
        for (id in absent) assertEquals(id, -1, timeline.messageIndex(id))
    }

    private fun ids(timeline: Timeline) = timeline.messages.map { it.text("id") }

    @Test
    fun idIndexFollowsInsertReplaceAndSnapshots() {
        val timeline = Timeline("session")
        assertIndexed(timeline, "a")
        timeline.snapshot(snapshot(8, message("c"), message("d"), cursor = "before-c"))
        assertIndexed(timeline, "a", "e")

        upsert(timeline, message("e"))
        assertEquals(listOf("c", "d", "e"), ids(timeline))
        assertIndexed(timeline)
        upsert(timeline, message("d", "streamed"))
        assertEquals(listOf("c", "d", "e"), ids(timeline))
        assertEquals("streamed", timeline.messages[1].text("text"))
        assertIndexed(timeline)

        // An older page moves every loaded message back.
        timeline.snapshot(snapshot(8, message("a"), message("b")), older = true)
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(timeline))
        assertIndexed(timeline)
        upsert(timeline, message("e", "after the page"))
        upsert(timeline, message("f"))
        assertEquals("after the page", timeline.messages[4].text("text"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), ids(timeline))
        assertIndexed(timeline)

        // A resync keeps the prefix before its page and drops what the page no longer holds.
        timeline.snapshot(snapshot(20, message("c"), message("g")))
        assertEquals(listOf("a", "b", "c", "g"), ids(timeline))
        assertIndexed(timeline, "d", "e", "f")
        upsert(timeline, message("d"))
        assertEquals(listOf("a", "b", "c", "g", "d"), ids(timeline))
        assertIndexed(timeline, "e", "f")

        // A resync that does not overlap replaces everything.
        timeline.snapshot(snapshot(30, message("x")))
        assertEquals(listOf("x"), ids(timeline))
        assertIndexed(timeline, "a", "b", "c", "d", "g")

        timeline.beginSnapshotEpoch("idle")
        assertIndexed(timeline, "x")
        upsert(timeline, message("pending"))
        assertEquals(listOf("pending"), ids(timeline))
        assertIndexed(timeline)
    }

    @Test
    fun duplicateIdsInAPageReplaceTheFirstOccurrence() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(1, message("a"), message("b"), message("a", "second")))
        assertEquals(0, timeline.messageIndex("a"))
        upsert(timeline, message("a", "updated"))
        assertEquals(listOf("updated", "b", "second"), timeline.messages.map { it.text("text") })
        assertIndexed(timeline)
    }
}
