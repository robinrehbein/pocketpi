package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

    private fun suggestionTimeline(suggestion: String? = null): Timeline =
        Timeline("one").apply {
            snapshot(
                Wire.objectOf(
                    "sessionId" to "one",
                    "revision" to 3,
                    "status" to "idle",
                    "messages" to kotlinx.serialization.json.JsonArray(emptyList()),
                    "pendingQuestions" to kotlinx.serialization.json.JsonArray(emptyList()),
                    *listOfNotNull(suggestion?.let { "suggestion" to it }).toTypedArray(),
                )
            )
        }

    private fun suggestionEvent(revision: Long, value: Any?, session: String = "one") =
        Wire.objectOf(
            "type" to "event",
            "sessionId" to session,
            "revision" to revision,
            "kind" to "session.suggestion",
            "suggestion" to value,
        )

    @Test
    fun suggestionEventsSetAndClearWithoutAnUnknownKindResync() {
        val timeline = suggestionTimeline()
        assertNull(timeline.suggestion)
        assertFalse(timeline.event(suggestionEvent(4, "Run the tests again")))
        assertEquals("Run the tests again", timeline.suggestion)
        assertEquals(4L, timeline.revision)
        assertFalse(timeline.unknownKindSeen)
        // Another session's or a replayed suggestion changes nothing.
        timeline.event(suggestionEvent(5, "elsewhere", "two"))
        timeline.event(suggestionEvent(4, "replayed"))
        assertEquals("Run the tests again", timeline.suggestion)
        assertFalse(timeline.event(suggestionEvent(5, null)))
        assertNull(timeline.suggestion)
        timeline.event(suggestionEvent(6, "Commit"))
        // Malformed values advance the revision, keep the suggestion and ask for no snapshot.
        assertFalse(timeline.event(suggestionEvent(7, "")))
        assertFalse(timeline.event(suggestionEvent(8, 42)))
        assertFalse(timeline.event(suggestionEvent(9, "a".repeat(MAX_SESSION_SUGGESTION_BYTES + 1))))
        assertFalse(timeline.event(JsonObject(suggestionEvent(10, "x") + ("source" to JsonPrimitive("prompt-suggest")))))
        assertEquals(10L, timeline.revision)
        assertEquals("Commit", timeline.suggestion)
        assertFalse(timeline.unknownKindSeen)
        timeline.event(suggestionEvent(11, "é".repeat(MAX_SESSION_SUGGESTION_BYTES / 2)))
        assertEquals("é".repeat(MAX_SESSION_SUGGESTION_BYTES / 2), timeline.suggestion)
    }

    @Test
    fun snapshotsRestoreSuggestionsAndOfflineOrANewEpochClearsThem() {
        val timeline = suggestionTimeline("Run the tests again")
        assertEquals("Run the tests again", timeline.suggestion)
        timeline.event(
            Wire.objectOf("type" to "event", "sessionId" to "one", "revision" to 4, "kind" to "session.status", "status" to "running")
        )
        assertEquals("Run the tests again", timeline.suggestion)
        timeline.event(
            Wire.objectOf("type" to "event", "sessionId" to "one", "revision" to 5, "kind" to "session.status", "status" to "offline")
        )
        assertNull(timeline.suggestion)
        timeline.event(suggestionEvent(6, "Again"))
        timeline.beginSnapshotEpoch("idle")
        assertNull(timeline.suggestion)
        // A snapshot without the key, or with a malformed value, holds no suggestion.
        assertNull(suggestionTimeline().suggestion)
        assertNull(suggestionTimeline("").suggestion)
        // A new session's Timeline starts without one.
        assertNull(Timeline("two").suggestion)
    }

    private val samplePullRequest = Wire.objectOf(
        "number" to 123,
        "url" to "https://github.com/acme/app/pull/123",
        "draft" to false,
        "review" to "approved",
        "checks" to Wire.objectOf("state" to "done", "failed" to 1),
        "failingChecks" to JsonArray(listOf(JsonPrimitive("build"))),
    )

    private fun pullRequestTimeline(pullRequest: Any? = null): Timeline =
        Timeline("one").apply {
            snapshot(
                Wire.objectOf(
                    "sessionId" to "one",
                    "revision" to 3,
                    "status" to "idle",
                    "messages" to JsonArray(emptyList()),
                    "pendingQuestions" to JsonArray(emptyList()),
                    *listOfNotNull(pullRequest?.let { "pullRequest" to it }).toTypedArray(),
                )
            )
        }

    private fun pullRequestEvent(revision: Long, value: Any?, session: String = "one") =
        Wire.objectOf(
            "type" to "event",
            "sessionId" to session,
            "revision" to revision,
            "kind" to "session.pull_request",
            "pullRequest" to value,
        )

    @Test
    fun pullRequestEventsSetAndClearWithoutAnUnknownKindResync() {
        val timeline = pullRequestTimeline()
        assertNull(timeline.pullRequest)
        assertFalse(timeline.event(pullRequestEvent(4, samplePullRequest)))
        assertEquals(123L, timeline.pullRequest?.number)
        assertEquals(PullRequestReview.APPROVED, timeline.pullRequest?.review)
        assertEquals(listOf("build"), timeline.pullRequest?.failingChecks)
        assertEquals(4L, timeline.revision)
        assertFalse(timeline.unknownKindSeen)
        // Another session's or a replayed event changes nothing.
        timeline.event(pullRequestEvent(5, null, "two"))
        timeline.event(pullRequestEvent(4, null))
        assertEquals(123L, timeline.pullRequest?.number)
        // A null clears it.
        assertFalse(timeline.event(pullRequestEvent(5, null)))
        assertNull(timeline.pullRequest)
        assertEquals(5L, timeline.revision)
    }

    @Test
    fun aMalformedPullRequestEventIsIgnoredWithoutAResync() {
        val timeline = pullRequestTimeline(samplePullRequest)
        val broken = JsonObject(samplePullRequest + ("url" to JsonPrimitive("http://github.com/acme/app/pull/123")))
        assertFalse(timeline.event(pullRequestEvent(4, broken)))
        assertFalse(timeline.event(pullRequestEvent(5, "https://github.com/acme/app/pull/123")))
        assertFalse(timeline.event(JsonObject(pullRequestEvent(6, samplePullRequest) + ("source" to JsonPrimitive("pr-status")))))
        assertEquals(6L, timeline.revision)
        assertEquals(123L, timeline.pullRequest?.number)
        assertFalse(timeline.unknownKindSeen)
    }

    @Test
    fun snapshotsRestorePullRequestsAndOfflineOrANewEpochClearsThem() {
        val timeline = pullRequestTimeline(samplePullRequest)
        assertEquals(123L, timeline.pullRequest?.number)
        timeline.event(
            Wire.objectOf("type" to "event", "sessionId" to "one", "revision" to 4, "kind" to "session.status", "status" to "running")
        )
        assertEquals(123L, timeline.pullRequest?.number)
        timeline.event(
            Wire.objectOf("type" to "event", "sessionId" to "one", "revision" to 5, "kind" to "session.status", "status" to "offline")
        )
        assertNull(timeline.pullRequest)
        timeline.event(pullRequestEvent(6, samplePullRequest))
        timeline.beginSnapshotEpoch("idle")
        assertNull(timeline.pullRequest)
        // A snapshot without the key, or with a malformed or null value, holds no pull request.
        assertNull(pullRequestTimeline().pullRequest)
        assertNull(pullRequestTimeline(JsonNull).pullRequest)
        assertNull(pullRequestTimeline(JsonObject(samplePullRequest + ("extra" to JsonPrimitive(1)))).pullRequest)
        assertNull(Timeline("two").pullRequest)
    }

    @Test
    fun sharedSuggestionFixturesParseAsTheHostDoes() {
        val fixture =
            Wire.json.parseToJsonElement(javaClass.getResource("/session-suggestion-v1.json")!!.readText()).jsonObject
        fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }
        fun valid(payload: JsonObject): Boolean =
            when (payload.text("type")) {
                "event" -> sessionSuggestion(payload) != null
                // A snapshot result: the optional key is absent or a valid suggestion.
                "result" -> payload.obj("data").let { data ->
                    "suggestion" !in data || suggestionText(data["suggestion"]) != null
                }
                else -> error(payload.text("type"))
            }
        assertEquals(4, payloads("wireValid").size)
        for ((name, payload) in payloads("wireValid")) assertTrue(name, valid(payload))
        assertEquals(7, payloads("wireInvalid").size)
        for ((name, payload) in payloads("wireInvalid")) assertFalse(name, valid(payload))
        assertNull(sessionSuggestion(payloads("wireValid").single { it.first == "suggestion-cleared" }.second)!!.text)
        val data = payloads("wireValid").single { it.first == "snapshot-with-suggestion" }.second.obj("data")
        assertEquals("Run the tests again", Timeline(data.text("sessionId")).apply { snapshot(data) }.suggestion)
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

    private fun statusEvent(revision: Long, status: String, after: Any? = null) =
        Wire.objectOf(
            "type" to "event",
            "sessionId" to "session",
            "revision" to revision,
            "kind" to "session.status",
            "status" to status,
            *listOfNotNull(after?.let { "after" to it }).toTypedArray(),
        )

    private fun upsertEvent(revision: Long, after: Long?, id: String, text: String) =
        Wire.objectOf(
            "type" to "event",
            "sessionId" to "session",
            "revision" to revision,
            "kind" to "message.upsert",
            "message" to message(id, text),
            *listOfNotNull(after?.let { "after" to it }).toTypedArray(),
        )

    @Test
    fun anEventAppliesWhenItsAfterIsReachedAndAGapOtherwise() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        // `after` equal to the revision: revisions 11 and 12 were left out on purpose.
        assertFalse(timeline.event(upsertEvent(13, 10, "a", "latest")))
        assertEquals(13L, timeline.revision)
        // A hole the event does not cover is a gap: nothing applies and a snapshot is needed.
        assertTrue(timeline.event(statusEvent(16, "waiting", after = 14)))
        assertEquals(13L, timeline.revision)
        assertEquals("running", timeline.status)
        assertTrue(timeline.needsSnapshot)
        // A plain next revision after the covered hole.
        assertFalse(timeline.event(statusEvent(14, "idle")))
        assertEquals(14L, timeline.revision)
        assertEquals("idle", timeline.status)
        // Without `after`, only the next revision applies.
        assertTrue(timeline.event(statusEvent(17, "idle")))
        assertEquals(14L, timeline.revision)
    }

    @Test
    fun anEventAtOrBelowTheRevisionIsIgnoredWhateverItsAfter() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a", "first")))
        assertFalse(timeline.event(upsertEvent(10, 4, "a", "replayed")))
        assertFalse(timeline.event(upsertEvent(7, 3, "a", "older")))
        assertFalse(timeline.event(upsertEvent(9, null, "a", "older")))
        assertEquals(10L, timeline.revision)
        assertEquals("first", timeline.messages.single().text("text"))
        assertFalse(timeline.needsSnapshot)
    }

    @Test
    fun anAfterBelowTheRevisionAlsoAppliesAnEventThatOverlapsIt() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        // r = 10 lies in [after, revision) for after 8: the event applies and skips nothing it needs.
        assertFalse(timeline.event(upsertEvent(12, 8, "a", "merged")))
        assertEquals(12L, timeline.revision)
        assertEquals("merged", timeline.messages.single().text("text"))
    }

    @Test
    fun aMalformedAfterOfThisSessionThrowsAndOtherSessionsAreNotLookedAt() {
        for (after in listOf(-1, 14, 15, 12.5, "12", kotlinx.serialization.json.JsonNull)) {
            val timeline = Timeline("session")
            timeline.snapshot(snapshot(10, message("a")))
            assertThrows(IllegalArgumentException::class.java) { timeline.event(statusEvent(14, "running", after)) }
            assertEquals(10L, timeline.revision)
        }
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        val other = Wire.objectOf("sessionId" to "other", "revision" to 14, "kind" to "session.status", "status" to "idle", "after" to -1)
        assertFalse(timeline.event(other))
    }

    @Test
    fun bufferedEventsWithAfterDrainThroughTheSnapshot() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        assertTrue(timeline.event(statusEvent(16, "waiting", after = 14)))
        timeline.snapshot(snapshot(14, message("a")))
        assertEquals(16L, timeline.revision)
        assertEquals("waiting", timeline.status)
        assertFalse(timeline.needsSnapshot)
    }

    @Test
    fun noticeSuggestionAndPullRequestEventsAcceptAfter() {
        val notice =
            Wire.objectOf(
                "type" to "event", "sessionId" to "one", "revision" to 9, "after" to 7,
                "kind" to "session.notice", "level" to "info", "message" to "Context is almost full.",
            )
        assertEquals(SessionNotice(1, "one", "info", "Context is almost full."), sessionNotice(notice, 1))
        assertNull(sessionNotice(kotlinx.serialization.json.JsonObject(notice + ("after" to JsonPrimitive(9))), 1))
        assertNull(sessionNotice(kotlinx.serialization.json.JsonObject(notice + ("extra" to JsonPrimitive(1))), 1))
        val suggestion =
            Wire.objectOf(
                "type" to "event", "sessionId" to "one", "revision" to 9, "after" to 7,
                "kind" to "session.suggestion", "suggestion" to "Run the tests",
            )
        assertEquals("Run the tests", sessionSuggestion(suggestion)?.text)
        assertNull(sessionSuggestion(kotlinx.serialization.json.JsonObject(suggestion + ("after" to JsonPrimitive(-1)))))
        val cleared = kotlinx.serialization.json.JsonObject(suggestion + ("suggestion" to JsonNull))
        assertNotNull(sessionSuggestion(cleared))
        assertNull(sessionSuggestion(kotlinx.serialization.json.JsonObject(cleared + ("extra" to JsonPrimitive(1)))))
        val pullRequest =
            Wire.objectOf(
                "type" to "event", "sessionId" to "one", "revision" to 9, "after" to 7,
                "kind" to "session.pull_request", "pullRequest" to JsonNull,
            )
        assertNotNull(sessionPullRequest(pullRequest))
        assertNull(sessionPullRequest(kotlinx.serialization.json.JsonObject(pullRequest + ("after" to JsonPrimitive(9)))))
    }

    @Test
    fun theStreamFixtureEventsApplyThroughTheTimelineWithTheirAfter() {
        val fixture =
            Wire.json.parseToJsonElement(javaClass.getResource("/stream-v1.json")!!.readText()).jsonObject
        val events = fixture.array("wireValid").filter { it.obj("payload").text("type") == "event" }.map { it.obj("payload") }
        val sessionId = events.first().text("sessionId")
        val timeline = Timeline(sessionId)
        timeline.snapshot(
            Wire.objectOf(
                "sessionId" to sessionId, "revision" to 12, "status" to "idle",
                "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
            )
        )
        // Revision 14 names after 12 and each later one the revision before it; revision 20 is snapshot.required.
        for (event in events.filter { it.long("revision") in 14..28 }.sortedBy { it.long("revision") }) {
            val name = event.text("kind") + "@" + event.long("revision")
            val gap = timeline.event(event)
            if (event.text("kind") == "snapshot.required") assertTrue(name, gap) else assertFalse(name, gap)
            if (gap) break
        }
        assertEquals(19L, timeline.revision)
    }
}

class TimelineResumeTest {
    private val epoch = "A".repeat(22)
    private val restarted = "B".repeat(22)

    private fun message(id: String, text: String = id) = Wire.objectOf("id" to id, "role" to "assistant", "text" to text)

    private fun snapshot(
        revision: Long,
        vararg messages: JsonObject,
        epoch: String? = this.epoch,
        cursor: String? = null,
        status: String = "idle",
    ) =
        JsonObject(
            buildMap {
                put("sessionId", JsonPrimitive("session"))
                put("revision", JsonPrimitive(revision))
                put("status", JsonPrimitive(status))
                put("messages", JsonArray(messages.toList()))
                put("pendingQuestions", JsonArray(emptyList()))
                if (epoch != null) put("epoch", JsonPrimitive(epoch))
                if (cursor != null) put("nextCursor", JsonPrimitive(cursor))
            }
        )

    private fun event(revision: Long, kind: String, after: Long?, vararg fields: Pair<String, Any?>) =
        Wire.objectOf(
            "type" to "event", "sessionId" to "session", "revision" to revision, "kind" to kind,
            *fields, *listOfNotNull(after?.let { "after" to it }).toTypedArray(),
        )

    private fun upsert(revision: Long, id: String, text: String = id, after: Long? = null) =
        event(revision, "message.upsert", after, "message" to message(id, text))

    private fun status(revision: Long, value: String, after: Long? = null) =
        event(revision, "session.status", after, "status" to value)

    private fun events(from: Long, revision: Long, vararg events: JsonObject, epoch: String = this.epoch) =
        sessionEventsReply(
            Wire.objectOf(
                "kind" to "session.events", "sessionId" to "session", "epoch" to epoch,
                "from" to from, "revision" to revision, "events" to JsonArray(events.toList()),
            )
        )

    private fun texts(timeline: Timeline) = timeline.messages.map { it.text("text") }

    @Test
    fun aResumeBaseNeedsASnapshotAndAnEpoch() {
        val timeline = Timeline("session")
        assertNull(timeline.resumeBase())
        timeline.snapshot(snapshot(4, epoch = null))
        assertNull(timeline.resumeBase())
        timeline.snapshot(snapshot(5))
        assertEquals(ResumeBase(epoch, 5), timeline.resumeBase())
        timeline.event(status(6, "running"))
        assertEquals(ResumeBase(epoch, 6), timeline.resumeBase())
        timeline.beginSnapshotEpoch("idle")
        assertNull(timeline.resumeBase())
        assertNull(timeline.journalEpoch)
    }

    @Test
    fun continueFromKeepsTheChatItsPagesAndItsBaseButNotWhatItBuffered() {
        val previous = Timeline("session")
        previous.snapshot(snapshot(5, message("b"), message("c"), cursor = "token:2", status = "running"))
        previous.event(upsert(6, "d"))
        previous.tasksKnown = true
        // A gap the previous Timeline still waits on: the resume's reply covers it.
        assertTrue(previous.event(status(9, "idle")))

        val continued = Timeline("session")
        continued.continueFrom(previous)

        assertTrue(continued.hasSnapshot)
        assertEquals(listOf("b", "c", "d"), texts(continued))
        assertEquals("token:2", continued.nextCursor)
        assertEquals("running", continued.status)
        assertEquals(6L, continued.revision)
        assertEquals(ResumeBase(epoch, 6), continued.resumeBase())
        assertTrue(continued.tasksKnown)
        assertFalse(continued.needsSnapshot)
        // The older pages of the first snapshot's token still fit: its revision was kept.
        assertTrue(continued.snapshot(snapshot(5, message("a"), epoch = null), older = true))
        assertEquals(listOf("a", "b", "c", "d"), texts(continued))
        assertEquals(0, continued.messageIndex("a"))
    }

    @Test
    fun aReplayAppliesCompactedAndMergedEventsAndReportsWhatItApplied() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        // b's first appearance carries its latest state (merged); a's superseded upserts are left out.
        val reply =
            events(
                10, 15,
                upsert(11, "b", "b latest"),
                status(13, "running", after = 11),
                upsert(15, "a", "a latest", after = 13),
            )
        val result = timeline.replay(reply)
        assertTrue(result.fits)
        assertEquals(reply.events, result.applied)
        assertEquals(15L, timeline.revision)
        assertEquals(listOf("a latest", "b latest"), texts(timeline))
        assertEquals("running", timeline.status)
        assertFalse(timeline.needsSnapshot)
    }

    @Test
    fun replayedReceiptsApplyAsCommandReceiptsAndComeBackInTheirWireForm() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(3))
        val receipt = event(4, "follow_up.status", null, "requestId" to "request", "status" to "delivered")
        val result = timeline.replay(events(3, 4, receipt))
        assertTrue(result.fits)
        assertSame(receipt, result.applied.single())
        assertEquals(4L, timeline.revision)
        assertFalse(timeline.unknownKindSeen)
    }

    @Test
    fun aReplayThatDoesNotFitAppliesNothingItCannotPlaceAndAsksForAFreshSnapshot() {
        fun fresh() = Timeline("session").apply { snapshot(snapshot(10, message("a"))) }
        // Another epoch: nothing applies.
        fresh().let { timeline ->
            val result = timeline.replay(events(10, 11, upsert(11, "b"), epoch = restarted))
            assertFalse(result.fits)
            assertTrue(result.applied.isEmpty())
            assertEquals(10L, timeline.revision)
        }
        // From a revision this Timeline has not reached.
        fresh().let { timeline ->
            val result = timeline.replay(events(12, 13, upsert(13, "b")))
            assertFalse(result.fits)
            assertEquals(listOf("a"), texts(timeline))
        }
        // Another session.
        fresh().let { timeline ->
            val result = timeline.replay(SessionEventsReply("other", epoch, 10, 10, emptyList()))
            assertFalse(result.fits)
        }
        // A hole the reply does not cover leaves the Timeline below the reply's revision.
        fresh().let { timeline ->
            val result = timeline.replay(SessionEventsReply("session", epoch, 10, 12, listOf(status(12, "running"))))
            assertFalse(result.fits)
            assertEquals(10L, timeline.revision)
        }
        // Without an epoch of its own (an older host's snapshot), no reply fits.
        Timeline("session").let { timeline ->
            timeline.snapshot(snapshot(10, epoch = null))
            assertFalse(timeline.replay(events(10, 10)).fits)
        }
    }

    @Test
    fun awaitingReplyHoldsEventsOfTheSessionAndAppliesThemOnceAfterTheReply() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        timeline.awaitReply()
        // Live events overtake the reply: 11 is in the reply too, 12 came after it.
        assertFalse(timeline.event(upsert(11, "b", "b live")))
        assertFalse(timeline.event(status(12, "running")))
        // A gap while waiting asks for nothing either.
        assertFalse(timeline.event(status(14, "idle")))
        assertEquals(10L, timeline.revision)
        assertEquals(listOf("a"), texts(timeline))
        assertFalse(timeline.needsSnapshot)

        val reply = events(10, 11, upsert(11, "b", "b replayed"))
        val result = timeline.replay(reply)

        assertFalse(timeline.awaitingReply)
        // Only the reply's event counts as replayed; the held ones ran their effects on arrival.
        assertEquals(reply.events, result.applied)
        assertEquals(12L, timeline.revision)
        assertEquals(listOf("a", "b replayed"), texts(timeline))
        assertEquals("running", timeline.status)
        // 14 still waits behind 13: the reply does not fit, and a fresh snapshot follows.
        assertFalse(result.fits)
        assertTrue(timeline.needsSnapshot)
    }

    @Test
    fun aFailedResumeAppliesTheHeldEventsAsTheyWouldHaveCome() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        timeline.awaitReply()
        timeline.event(upsert(11, "b"))
        timeline.event(status(13, "running"))
        assertTrue(timeline.endAwaiting())
        assertEquals(11L, timeline.revision)
        assertEquals(listOf("a", "b"), texts(timeline))
        assertTrue(timeline.needsSnapshot)
        assertFalse(timeline.endAwaiting())
    }

    @Test
    fun aSnapshotOfAnotherEpochAtALowerRevisionStartsOverOnAContinuedAndOnAKeptTimeline() {
        for (kept in listOf(false, true)) {
            val previous = Timeline("session")
            previous.snapshot(snapshot(50, message("a"), message("b"), cursor = "old:2"))
            previous.tasksKnown = true
            if (kept) previous.detached = true
            val timeline = Timeline("session")
            timeline.continueFrom(previous)
            assertFalse(timeline.detached)

            // A restarted host: its revisions start again near 0.
            assertTrue(timeline.snapshot(snapshot(3, message("b"), message("c"), epoch = restarted, cursor = "new:2")))

            assertEquals(kept.toString(), 3L, timeline.revision)
            assertEquals(restarted, timeline.journalEpoch)
            // The shown messages stay as the prefix the new page overlaps.
            assertEquals(listOf("a", "b", "c"), texts(timeline))
            assertFalse(timeline.tasksKnown)
            assertFalse(timeline.event(status(4, "running")))
            assertEquals("running", timeline.status)
        }
    }

    @Test
    fun anEpochRestartDropsHeldEventsAndOwesAResumeOnlyForThosePastTheNewPage() {
        // past: whether an event held for the resume lies past the new page's revision.
        for (past in listOf(false, true)) {
            val timeline = Timeline("session")
            timeline.snapshot(snapshot(50, message("a")))
            // Buffered behind a gap before the request: these may count in the old epoch.
            assertTrue(timeline.event(status(60, "running")))
            timeline.awaitReply()
            // Behind the old revision, so not applied, but possibly the new epoch's.
            assertFalse(timeline.event(status(if (past) 4 else 3, "waiting")))

            assertTrue(timeline.snapshot(snapshot(3, message("a"), epoch = restarted)))

            // Neither the old gap nor the held event applied on the new epoch.
            assertEquals(past.toString(), 3L, timeline.revision)
            assertEquals(past.toString(), "idle", timeline.status)
            assertFalse(past.toString(), timeline.needsSnapshot)
            assertEquals(past.toString(), past, timeline.takeOwedResume())
            assertFalse(past.toString(), timeline.takeOwedResume())
            assertEquals(past.toString(), ResumeBase(restarted, 3), timeline.resumeBase())
        }
        // Without a held event past it, a gap buffered before the request owes nothing.
        val gapOnly = Timeline("session")
        gapOnly.snapshot(snapshot(50))
        gapOnly.event(status(60, "running"))
        gapOnly.awaitReply()
        gapOnly.snapshot(snapshot(3, epoch = restarted))
        assertFalse(gapOnly.takeOwedResume())
        // A held event that a drain already applied is no longer held.
        val drained = Timeline("session")
        drained.snapshot(snapshot(10))
        drained.awaitReply()
        drained.event(status(11, "running"))
        drained.endAwaiting()
        drained.snapshot(snapshot(3, epoch = restarted))
        assertFalse(drained.takeOwedResume())
    }

    @Test
    fun aSnapshotWithoutEpochOnAContinuedTimelineStartsOver() {
        val previous = Timeline("session")
        previous.snapshot(snapshot(50, message("a")))
        val timeline = Timeline("session")
        timeline.continueFrom(previous)
        assertTrue(timeline.snapshot(snapshot(7, message("a", "downgraded"), epoch = null)))
        assertEquals(7L, timeline.revision)
        assertNull(timeline.journalEpoch)
        assertNull(timeline.resumeBase())
        assertEquals(listOf("downgraded"), texts(timeline))
    }

    @Test
    fun aSnapshotOfTheSameEpochAtALowerRevisionIsStillOutdatedAndReleasesTheHeldEvents() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        timeline.awaitReply()
        timeline.event(upsert(11, "b"))
        assertFalse(timeline.snapshot(snapshot(9, message("old"))))
        assertFalse(timeline.awaitingReply)
        assertEquals(11L, timeline.revision)
        assertEquals(listOf("a", "b"), texts(timeline))
        // Both without an epoch count as the same: an older host's outdated reply.
        val legacy = Timeline("session")
        legacy.snapshot(snapshot(10, message("a"), epoch = null))
        assertFalse(legacy.snapshot(snapshot(9, message("old"), epoch = null)))
        assertEquals(10L, legacy.revision)
    }

    @Test
    fun eventNeedTellsAGapFromSnapshotRequired() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10))
        timeline.tasksKnown = true
        assertEquals(SnapshotNeed.NONE, timeline.eventNeed(status(11, "running")))
        assertEquals(SnapshotNeed.GAP, timeline.eventNeed(status(13, "idle")))
        assertTrue(timeline.tasksKnown)
        assertEquals(SnapshotNeed.REQUIRED, timeline.eventNeed(event(12, "snapshot.required", null)))
        assertFalse(timeline.tasksKnown)
    }

    @Test
    fun aDetachedTimelineAppliesNothingAndAsksForNothing() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("a")))
        timeline.detached = true
        assertFalse(timeline.event(upsert(11, "b")))
        assertFalse(timeline.event(status(20, "running")))
        assertEquals(10L, timeline.revision)
        assertFalse(timeline.needsSnapshot)
    }

    @Test
    fun aFirstPageThatDropsTheOlderPagesTakesItsOwnCursor() {
        val timeline = Timeline("session")
        timeline.snapshot(snapshot(10, message("b"), message("c"), cursor = "old:2"))
        timeline.snapshot(snapshot(10, message("a"), epoch = null, cursor = "old:3"), older = true)
        assertEquals(listOf("a", "b", "c"), texts(timeline))

        // A resync keeps the older pages and their cursor where the new page overlaps them...
        val kept = Timeline("session").apply { continueFrom(timeline) }
        kept.snapshot(snapshot(12, message("c"), message("d"), cursor = "new:2"))
        assertEquals(listOf("a", "b", "c", "d"), texts(kept))
        assertEquals("old:3", kept.nextCursor)

        // ...while the stale_cursor recovery starts from the new page and its cursor.
        timeline.snapshot(snapshot(12, message("c"), message("d"), cursor = "new:2"), keepOlder = false)
        assertEquals(listOf("c", "d"), texts(timeline))
        assertEquals("new:2", timeline.nextCursor)
        assertTrue(timeline.snapshot(snapshot(12, message("b"), epoch = null), older = true))
        assertEquals(listOf("b", "c", "d"), texts(timeline))
    }
}
