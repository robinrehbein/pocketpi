package de.joinnoah.pi.remote

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Repository wiring of `session_activity.v1`: negotiation, baselines, events, replay, reconnects, actors. */
class ExecutionActivityRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val project = Wire.objectOf("id" to "project")
    private val journal = "A".repeat(22)
    private fun session(id: String, activity: JsonObject? = null) = JsonObject(
        Wire.objectOf("id" to id, "origin" to "rpc", "status" to "running", "projectId" to "project") +
            (if (activity == null) emptyMap() else mapOf("activity" to activity)),
    )

    private fun clock(id: String = "clk", sampledAt: Long = 1_000) = Wire.objectOf("clockId" to id, "sampledAt" to sampledAt)

    private fun active(seq: Long, expiresAt: Long, epoch: String = "gen-1", clockId: String = "clk") = Wire.objectOf(
        "clockId" to clockId, "epoch" to epoch, "seq" to seq, "state" to "active", "expiresAt" to expiresAt,
        "phase" to "thinking", "tools" to JsonArray(emptyList()),
    )

    private fun idle(seq: Long, epoch: String = "gen-1") =
        Wire.objectOf("clockId" to "clk", "epoch" to epoch, "seq" to seq, "state" to "idle")

    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var routeCapabilities = setOf(SESSION_ACTIVITY_CAPABILITY)
        /** Answers v3 discovery like a v2 host (no list), so the app falls back. */
        var v2Only = false
        var handshake = emptySet<String>()
        var revision = 0L
        /** Activity fields added to the first snapshot page; null leaves them out. */
        var snapshotActivity: JsonObject? = null
        var snapshotClock: JsonObject? = clock()
        var snapshotMessages: List<JsonObject> = emptyList()
        var listActivity: JsonObject? = null
        var holdSnapshots = false
        /** Events a resume request is answered with, as `session.events`. */
        var replay: List<JsonObject>? = null
        var pongClock: JsonObject? = null
        var holdLists = false
        fun lists() = sent.filter { it.text("type") == "sessions.list" }
        fun list(activity: JsonObject?): JsonObject {
            val items = JsonArray(listOf(session("session", activity), session("other")))
            return if (activity == null) Wire.objectOf("kind" to "sessions", "items" to items)
            else Wire.objectOf("kind" to "sessions", "items" to items, "activityClock" to clock())
        }

        override fun connect(host: PairedHost) = listener.ready(handshake)
        override fun close() = Unit
        override fun pair(value: JsonObject) = Unit
        fun deliver(value: JsonObject) { listener.inbound(); listener.message(value) }
        fun result(request: JsonObject, data: JsonObject) = deliver(Wire.objectOf(
            "type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data,
        ))

        fun snapshot(request: JsonObject): JsonObject {
            val fields = linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive("snapshot"), "sessionId" to JsonPrimitive(request.text("sessionId")),
                "revision" to JsonPrimitive(revision), "status" to JsonPrimitive("running"),
                "messages" to JsonArray(snapshotMessages), "pendingQuestions" to JsonArray(emptyList()),
            )
            if ("cursor" !in request) {
                fields["epoch"] = JsonPrimitive(journal)
                snapshotClock?.let { fields["activityClock"] = it }
                snapshotActivity?.let { fields["activity"] = it }
            }
            return JsonObject(fields)
        }

        override fun send(payload: JsonObject) {
            // Every test sends far fewer; more means a request loop (a stale page asked again and again).
            check(sent.size < 200) { "runaway transport: ${sent.size} requests sent" }
            sent += payload
            val data = when (payload.text("type")) {
                "projects.list" -> {
                    val base = Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(project)))
                    if (v2Only && payload.text("requestId").startsWith("capabilities.v3:")) base
                    else JsonObject(base + ("capabilities" to JsonArray(routeCapabilities.map(::JsonPrimitive))))
                }
                "sessions.list" -> if (holdLists) null else {
                    val items = JsonArray(listOf(session("session", listActivity), session("other")))
                    if (listActivity == null) Wire.objectOf("kind" to "sessions", "items" to items)
                    else Wire.objectOf("kind" to "sessions", "items" to items, "activityClock" to clock())
                }
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session(payload.text("sessionId")))
                "remote.ping" -> pongClock?.let { Wire.objectOf("kind" to "pong", "activityClock" to it) } ?: Wire.objectOf("kind" to "pong")
                "session.snapshot" -> {
                    val base = parseResumeRequest(payload.text("requestId"))
                    val events = replay
                    if (holdSnapshots) null
                    else if (events != null && base?.epoch == journal && "cursor" !in payload) Wire.objectOf(
                        "kind" to "session.events", "sessionId" to "session", "epoch" to journal, "from" to base.revision,
                        "revision" to events.maxOf { it.long("revision") }, "events" to JsonArray(events),
                        "activityClock" to clock(sampledAt = 2_000),
                    )
                    else snapshot(payload)
                }
                else -> Wire.objectOf("kind" to "accepted")
            }
            if (data != null) result(payload, data)
        }

        fun snapshots() = sent.filter { it.text("type") == "session.snapshot" && "cursor" !in it }

        fun event(kind: String, sessionId: String = "session", vararg fields: Pair<String, Any?>): JsonObject {
            revision++
            // The host's parser allows an envelope `epoch` on status and title events only.
            return Wire.objectOf("type" to "event", "sessionId" to sessionId, "revision" to revision, "kind" to kind, *fields)
        }

        fun activity(activity: JsonObject, sessionId: String = "session") = deliver(event("session.activity", sessionId, "activity" to activity))
    }

    /** The client's monotonic clock; the repository reads it through `terminalNow`. */
    private var monotonic = 50_000L

    private fun TestScope.repository(transport: Transport): DefaultRemoteRepository {
        val pairing = object : PairingStorage {
            override fun load() = listOf(host)
            override fun save(hosts: List<PairedHost>) = Unit
        }
        val drafts = object : DraftStorage {
            override fun load() = emptyMap<DraftKey, StoredDraft>()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        return DefaultRemoteRepository(
            pairing, drafts, transport, backgroundScope, StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime }, jitter = { 0.5 }, terminalNow = { monotonic },
        )
    }

    private fun DefaultRemoteRepository.activity() = state.value.executionActivity

    @Test fun observedRunDurationReachesTheChatLineWithNegotiatedActivity() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        // Opening an already running session must not manufacture a start time.
        transport.deliver(transport.event("session.status", "session", "status" to "idle")); runCurrent()
        assertNull(repo.state.value.chatWorkTiming.durationMillis)
        monotonic = 60_000
        transport.deliver(transport.event("session.status", "session", "status" to "running")); runCurrent()
        assertEquals(60_000L, repo.state.value.chatWorkTiming.startedAtMillis)
        monotonic = 114_000
        transport.deliver(transport.event("session.status", "session", "status" to "idle")); runCurrent()
        val state = repo.state.value
        assertEquals(54_000L, state.chatWorkTiming.durationMillis)
        assertEquals(StatusGlyphKind.WORK_COMPLETED, sessionActivityLine(state, emptyList(), selection, monotonic)?.glyphKind)
    }

    @Test fun v3DiscoveryOptsInAndTheRouteAnswerGrants() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val discovery = transport.sent.first { it.text("type") == "projects.list" }.text("requestId")
        assertTrue(discovery.startsWith("capabilities.v3:optin="))
        assertTrue(SESSION_ACTIVITY_CAPABILITY in discovery.substringAfter("optin=").substringBefore(':').split('+'))
        assertTrue(repo.activity().negotiated)
        val view = repo.activity().sessions.getValue("session")
        assertEquals(1L, view.snapshot.seq)
        // offset = requestStart (50 000) - sampledAt (1 000); the deadline is 1 500 on the host.
        assertEquals(ActivityFreshness(true, 500), repo.activity().freshness("session", monotonic))
        assertFalse(repo.activity().freshness("session", 50_500).live)
    }

    @Test fun theLocalObservationCapIsPinnedAtAcceptance() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 9_000_000_000) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val accepted = monotonic
        assertEquals(ActivityFreshness(true, 30_000), repo.activity().freshness("session", accepted))
        // Duplicates and clock-only answers never move it.
        transport.activity(active(1, 9_000_000_000)); runCurrent()
        assertFalse(repo.activity().freshness("session", accepted + 30_001).live)
        assertEquals(1L, repo.activity().sessions.getValue("session").snapshot.seq)
    }

    @Test fun aBetterClockOffsetDoesNotExtendThePinnedDeadline() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        // Deadline pinned at 50 500. A later list samples the same clock with a larger offset.
        monotonic += 100
        transport.snapshotActivity = null
        transport.listActivity = idle(0, epoch = "list")
        repo.openNotification("host", "elsewhere"); runCurrent()
        assertTrue((repo.activity().clock?.offset ?: 0) > 49_000)
        assertFalse(repo.activity().freshness("session", 50_500).live)
    }

    @Test fun legacyHandshakeAndV2NeverGrant() = runTest {
        for (case in listOf("none", "handshake", "v2")) {
            val transport = Transport().apply {
                snapshotActivity = active(1, 1_500)
                when (case) {
                    "none" -> routeCapabilities = emptySet()
                    "handshake" -> { routeCapabilities = emptySet(); handshake = setOf(SESSION_ACTIVITY_CAPABILITY) }
                    "v2" -> v2Only = true
                }
            }
            val repo = repository(transport)
            repo.activate(selection); runCurrent()
            assertFalse(case, repo.activity().negotiated)
            assertTrue(case, repo.activity().sessions.isEmpty())
            transport.activity(active(2, 1_600)); runCurrent()
            assertTrue(case, repo.activity().sessions.isEmpty())
            // The ordinary chat still works.
            assertTrue(case, repo.state.value.connected)
        }
    }

    @Test fun liveEventsAdvanceBySeqAndDuplicatesNeverExtendTheDeadline() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val pages = transport.snapshots().size
        transport.activity(active(3, 2_000)); runCurrent()
        assertEquals(3L, repo.activity().sessions.getValue("session").snapshot.seq)
        // A duplicate or older seq with a later deadline changes nothing.
        transport.activity(active(3, 9_000)); runCurrent()
        transport.activity(active(2, 9_000)); runCurrent()
        val shown = repo.activity().sessions.getValue("session").snapshot as ActivitySnapshot.Active
        assertEquals(2_000L, shown.expiresAt)
        // Expiry freezes; it never turns the view idle.
        assertFalse(repo.activity().freshness("session", 51_001).live)
        assertTrue(repo.activity().sessions.getValue("session").snapshot is ActivitySnapshot.Active)
        transport.activity(idle(4)); runCurrent()
        assertTrue(repo.activity().sessions.getValue("session").snapshot is ActivitySnapshot.Idle)
        // The timeline took the events as known revisions: no snapshot was asked for.
        assertEquals(pages, transport.snapshots().size)
    }

    @Test fun anotherEpochFreezesAsUnknownAndAsksOneFreshSnapshot() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val pages = transport.snapshots().size
        transport.snapshotActivity = active(1, 1_800, epoch = "gen-2")
        transport.holdSnapshots = true
        transport.activity(active(5, 1_700, epoch = "gen-2")); runCurrent()
        assertTrue(repo.activity().sessions.getValue("session").snapshot is ActivitySnapshot.Unknown)
        assertFalse(repo.activity().freshness("session", monotonic).live)
        assertEquals(pages + 1, transport.snapshots().size)
        // More events of the same epoch do not ask again.
        transport.activity(active(6, 1_700, epoch = "gen-2")); runCurrent()
        transport.activity(active(7, 1_700, epoch = "gen-2")); runCurrent()
        assertEquals(pages + 1, transport.snapshots().size)
        transport.holdSnapshots = false
        val fresh = transport.snapshots().last()
        transport.result(fresh, transport.snapshot(fresh)); runCurrent()
        val view = repo.activity().sessions.getValue("session")
        assertEquals("gen-2", view.snapshot.epoch)
        assertTrue(view.snapshot is ActivitySnapshot.Active)
    }

    @Test fun anEventWithAnUnknownKeyIsDropped() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.deliver(JsonObject(transport.event("session.activity", "session", "activity" to active(2, 1_600)) + ("epoch" to JsonPrimitive(journal))))
        runCurrent()
        assertEquals(1L, repo.activity().sessions.getValue("session").snapshot.seq)
    }

    @Test fun aListedRowWithoutActivityFreezesWithoutTurningIdle() = runTest {
        val transport = Transport().apply { snapshotActivity = null; snapshotClock = null; listActivity = active(2, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.listActivity = null
        repo.openNotification("host", "elsewhere"); runCurrent()
        val shown = repo.activity().sessions["session"]?.snapshot
        assertTrue(shown == null || shown is ActivitySnapshot.Unknown)
        assertFalse(repo.activity().freshness("session", monotonic).live)
    }

    @Test fun aDelayedPageCannotBringBackAnActorALaterUpsertEnded() = runTest {
        val transport = Transport().apply {
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1")))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val key = ActivityActorKey("session", "call-1", "inst-1")
        assertTrue(key in repo.activity().actors)
        // A fresh page goes out, then the agent finishes live before the page's reply.
        transport.holdSnapshots = true
        transport.activity(active(1, 1_500, epoch = "gen-2")); runCurrent()
        val held = transport.snapshots().last()
        val pageRevision = transport.revision
        transport.deliver(transport.event("message.upsert", "session",
            "message" to toolMessage("m1", "call-1", agent("inst-1", "call-1", state = "succeeded"))))
        runCurrent()
        assertTrue(repo.activity().actors.isEmpty())
        // The page still lists it running at an older revision: it must not come back.
        transport.result(held, JsonObject(transport.snapshot(held) + ("revision" to JsonPrimitive(pageRevision))))
        runCurrent()
        assertFalse(key in repo.activity().actors)
    }

    @Test fun aDuplicatedActorKeyCountsForNeither() = runTest {
        val transport = Transport().apply {
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1"), agent("inst-1", "call-1")))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertTrue(repo.activity().actors.isEmpty())
    }

    @Test fun anEventOfAnotherSessionNeverTouchesThisOne() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500); listActivity = null }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val before = repo.activity().sessions.getValue("session")
        val pages = transport.snapshots().size
        transport.activity(active(9, 9_000), sessionId = "other"); runCurrent()
        assertEquals(before, repo.activity().sessions["session"])
        assertNull(repo.activity().sessions["other"])
        // Only the selected chat reads a snapshot for a missing view.
        assertEquals(pages, transport.snapshots().size)
    }

    @Test fun theSessionListBaselinesItsRows() = runTest {
        val transport = Transport().apply { snapshotActivity = null; snapshotClock = null; listActivity = active(2, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        // The first list may go out before the grant; a list read after it baselines its rows.
        repo.openNotification("host", "elsewhere"); runCurrent()
        assertEquals(2L, repo.activity().sessions.getValue("session").snapshot.seq)
        assertNull(repo.activity().sessions["other"])
        assertEquals("clk", repo.activity().clock?.clockId)
    }

    @Test fun aReconnectForgetsEverythingAndOldRepliesCannotBaselineTheNewConnection() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val oldConnection = repo.activity().connectionId
        transport.listener.failed(true, R.string.remote_connection_error)
        assertFalse(repo.activity().negotiated)
        assertTrue(repo.activity().sessions.isEmpty())
        assertNull(repo.activity().clock)
        // The new connection does not grant: nothing of the old one carries over.
        transport.routeCapabilities = emptySet()
        backgroundScope.launch { repo.activate(selection) }
        runCurrent()
        assertNotEquals(oldConnection, repo.activity().connectionId)
        assertFalse(repo.activity().negotiated)
        assertTrue(repo.activity().sessions.isEmpty())
        transport.activity(active(5, 9_000)); runCurrent()
        assertTrue(repo.activity().sessions.isEmpty())
    }

    @Test fun aHeldOldConnectionSnapshotCannotChangeTheNewState() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.holdSnapshots = true
        transport.activity(active(1, 1_500, epoch = "gen-2")); runCurrent()
        val held = transport.snapshots().last()
        transport.listener.failed(true, R.string.remote_connection_error)
        backgroundScope.launch { repo.activate(selection) }
        runCurrent()
        val reconnect = transport.snapshots().last()
        assertNotEquals(held.text("requestId"), reconnect.text("requestId"))
        transport.snapshotActivity = active(9, 9_000, epoch = "old")
        transport.result(held, transport.snapshot(held)); runCurrent()
        assertNull(repo.activity().sessions["session"])
        transport.snapshotActivity = active(1, 1_500, epoch = "new")
        transport.result(reconnect, transport.snapshot(reconnect)); runCurrent()
        // A page read before this connection's grant is followed by one fresh page.
        val after = transport.snapshots().last()
        if (after !== reconnect) { transport.result(after, transport.snapshot(after)); runCurrent() }
        assertEquals("new", repo.activity().sessions.getValue("session").snapshot.epoch)
    }

    @Test fun aReplayAppliesOnlyOnTheSameEpochAndNeverBaselines() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        // What the phone missed: an activity event, replayed on resume with a clock sample.
        transport.replay = listOf(transport.event("session.activity", "session", "activity" to active(4, 3_000)))
        // The page that follows the replay is the baseline; the replay's seq 4 is not.
        transport.snapshotActivity = active(5, 3_000)
        backgroundScope.launch { repo.activate(selection) }
        runCurrent()
        val pages = transport.snapshots()
        val resume = pages.indexOfFirst { parseResumeRequest(it.text("requestId"))?.epoch == journal }
        assertTrue(resume >= 0)
        // A fresh page followed the replay on this connection, exactly once.
        assertEquals(1, pages.drop(resume + 1).count { parseResumeRequest(it.text("requestId"))?.epoch == null })
        assertEquals(5L, repo.activity().sessions.getValue("session").snapshot.seq)
    }

    private fun agent(
        instanceId: String,
        spawn: String,
        state: String = "running",
        activity: JsonObject? = active(1, 1_500, epoch = "child"),
        needsInput: Boolean = false,
    ) = JsonObject(
        buildMap {
            put("agent", JsonPrimitive("scout"))
            put("state", JsonPrimitive(state))
            put("instanceId", JsonPrimitive(instanceId))
            put("spawnToolCallId", JsonPrimitive(spawn))
            activity?.let { put("executionActivity", it) }
            if (needsInput) put("needsInput", JsonPrimitive(true))
        },
    )

    private fun toolMessage(id: String, toolCallId: String, vararg agents: JsonObject) = Wire.objectOf(
        "id" to id, "role" to "tool", "text" to "", "state" to "streaming", "toolName" to "subagent",
        "toolCallId" to toolCallId,
        "subagentProgress" to Wire.objectOf("mode" to "parallel", "agents" to JsonArray(agents.toList())),
    )

    @Test fun actorsBaselineOnlyExactRunningAgentsAndAdvanceBySeq() = runTest {
        val transport = Transport().apply {
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(
                toolMessage(
                    "m1", "call-1",
                    agent("inst-1", "call-1", needsInput = true),
                    // Another card's spawn, a queued and a finished agent never count.
                    agent("inst-2", "call-other"),
                    agent("inst-3", "call-1", state = "queued"),
                    agent("inst-4", "call-1", state = "succeeded"),
                ),
            )
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val key = ActivityActorKey("session", "call-1", "inst-1")
        assertEquals(setOf(key), repo.activity().actors.keys)
        assertTrue(repo.activity().actors.getValue(key).needsInput)
        assertTrue(repo.activity().actorFreshness(key, monotonic).live)

        // Parent activity never moves the actor, and the actor never the parent.
        transport.activity(idle(2)); runCurrent()
        assertTrue(repo.activity().actors.getValue(key).view.snapshot is ActivitySnapshot.Active)

        transport.deliver(transport.event("message.upsert", "session",
            "message" to toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = active(3, 1_900, epoch = "child")))))
        runCurrent()
        val actor = repo.activity().actors.getValue(key)
        assertEquals(3L, actor.view.snapshot.seq)
        assertFalse(actor.needsInput)
        // An older seq is a no-op.
        transport.deliver(transport.event("message.upsert", "session",
            "message" to toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = active(2, 9_000, epoch = "child")))))
        runCurrent()
        assertEquals(3L, repo.activity().actors.getValue(key).view.snapshot.seq)
        // Finishing drops it.
        transport.deliver(transport.event("message.upsert", "session",
            "message" to toolMessage("m1", "call-1", agent("inst-1", "call-1", state = "succeeded"))))
        runCurrent()
        assertTrue(repo.activity().actors.isEmpty())
    }

    @Test fun aNewActorLiveAsksOneSnapshotAndNeverAppearsWithoutIt() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val pages = transport.snapshots().size
        transport.holdSnapshots = true
        repeat(3) {
            transport.deliver(transport.event("message.upsert", "session",
                "message" to toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = active(2L + it, 1_500, epoch = "child")))))
            runCurrent()
        }
        assertTrue(repo.activity().actors.isEmpty())
        assertEquals(pages + 1, transport.snapshots().size)
    }

    private fun TestScope.upsert(transport: Transport, vararg agents: JsonObject) {
        transport.deliver(transport.event("message.upsert", "session", "message" to toolMessage("m1", "call-1", *agents)))
        runCurrent()
    }

    @Test fun aSecondInstanceUnderTheSameSpawnAsksItsOwnSnapshot() = runTest {
        val transport = Transport().apply {
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1")))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001 // a fresh throttle window
        transport.holdSnapshots = true
        val pages = transport.snapshots().size
        upsert(transport, agent("inst-1", "call-1"), agent("inst-2", "call-1"))
        assertEquals(pages + 1, transport.snapshots().size)
        val first = transport.snapshots().last()
        transport.snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1"), agent("inst-2", "call-1")))
        transport.result(first, JsonObject(transport.snapshot(first) + ("revision" to JsonPrimitive(transport.revision))))
        runCurrent()
        assertTrue(ActivityActorKey("session", "call-1", "inst-2") in repo.activity().actors)
        // A third instance on the same card is its own reason.
        upsert(transport, agent("inst-1", "call-1"), agent("inst-2", "call-1"), agent("inst-3", "call-1"))
        assertEquals(pages + 2, transport.snapshots().size)
    }

    @Test fun aRecoveryPageBeforeTheAgentRanLetsALaterUpdateAskAgain() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001
        transport.holdSnapshots = true
        val pages = transport.snapshots().size
        upsert(transport, agent("inst-1", "call-1"))
        val recovery = transport.snapshots().last()
        assertEquals(pages + 1, transport.snapshots().size)
        // The page still lists it queued: no actor, and the reason is free again.
        transport.snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1", state = "queued")))
        transport.result(recovery, JsonObject(transport.snapshot(recovery) + ("revision" to JsonPrimitive(transport.revision))))
        runCurrent()
        assertTrue(repo.activity().actors.isEmpty())
        upsert(transport, agent("inst-1", "call-1", activity = active(2, 1_500, epoch = "child")))
        assertEquals(pages + 2, transport.snapshots().size)
    }

    @Test fun aPongClockSampleCannotExtendThePinnedDeadline() = runTest {
        val transport = Transport().apply {
            routeCapabilities = setOf(SESSION_ACTIVITY_CAPABILITY, STREAM_CAPABILITY)
            snapshotActivity = active(1, 1_500)
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val deadline = monotonic + 500
        assertEquals(ActivityFreshness(true, 500), repo.activity().freshness("session", monotonic))
        // A later ping on the same host clock yields a larger offset.
        monotonic += 200
        transport.pongClock = clock()
        advanceTimeBy(PING_INTERVAL_MILLIS + 1); runCurrent()
        assertTrue(transport.sent.any { it.text("type") == "remote.ping" })
        assertTrue((repo.activity().clock?.offset ?: 0) >= 49_200)
        assertFalse(repo.activity().freshness("session", deadline).live)
        assertEquals(ActivityFreshness(true, 100), repo.activity().freshness("session", deadline - 100))
    }

    @Test fun aDeselectedFirstPageAdmitsNothing() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001
        transport.holdSnapshots = true
        transport.activity(active(1, 1_500, epoch = "gen-2")); runCurrent()
        val held = transport.snapshots().last()
        backgroundScope.launch { repo.activate(RemoteSelection("host", "project", "other")) }
        runCurrent()
        transport.snapshotActivity = active(3, 9_000, epoch = "gen-2")
        transport.snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1")))
        transport.result(held, transport.snapshot(held)); runCurrent()
        assertNotEquals("gen-2", repo.activity().sessions["session"]?.snapshot?.epoch)
        assertTrue(repo.activity().actors.isEmpty())
    }

    private fun TestScope.upsertAt(transport: Transport, revision: Long, vararg agents: JsonObject) {
        transport.deliver(Wire.objectOf(
            "type" to "event", "sessionId" to "session", "revision" to revision, "kind" to "message.upsert",
            "message" to toolMessage("m1", "call-1", *agents),
        ))
        runCurrent()
    }

    @Test fun needsInputFollowsNewerMessagesAtTheSameSeqWithoutRenewal() = runTest {
        for (activity in listOf(active(1, 1_500, epoch = "child"), idle(1, epoch = "child"))) {
            val transport = Transport().apply {
                snapshotActivity = active(1, 1_500)
                snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = activity)))
            }
            val repo = repository(transport)
            repo.activate(selection); runCurrent()
            val key = ActivityActorKey("session", "call-1", "inst-1")
            val before = repo.activity().actors.getValue(key)
            assertFalse(before.needsInput)
            monotonic += 100
            upsert(transport, agent("inst-1", "call-1", activity = activity, needsInput = true))
            val set = repo.activity().actors.getValue(key)
            assertTrue(set.needsInput)
            assertEquals(before.view, set.view)
            assertEquals(before.observedUntil, set.observedUntil)
            val newest = transport.revision
            upsert(transport, agent("inst-1", "call-1", activity = activity))
            assertFalse(repo.activity().actors.getValue(key).needsInput)
            // An older journal observation cannot roll it back.
            upsertAt(transport, newest, agent("inst-1", "call-1", activity = activity, needsInput = true))
            assertFalse(repo.activity().actors.getValue(key).needsInput)
            assertEquals(before.observedUntil, repo.activity().actors.getValue(key).observedUntil)
            if (activity["state"] == JsonPrimitive("idle")) assertFalse(repo.activity().actorFreshness(key, monotonic).live)
        }
    }

    @Test fun anOlderListCannotBaselineASessionANewerListOmitted() = runTest {
        val transport = Transport().apply { snapshotActivity = null; snapshotClock = null }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.holdLists = true
        val before = transport.lists().size
        backgroundScope.launch { repo.openNotification("host", "elsewhere") }; runCurrent()
        backgroundScope.launch { repo.openNotification("host", "elsewhere") }; runCurrent()
        val held = transport.lists().drop(before)
        assertTrue("two lists in flight: ${held.size}", held.size >= 2)
        transport.result(held.last(), transport.list(null)); runCurrent()
        transport.result(held.first(), transport.list(active(2, 1_500))); runCurrent()
        assertFalse(repo.activity().freshness("session", monotonic).live)
        assertNull(repo.activity().sessions["session"])
    }

    @Test fun anActorRecoveryDuringAPageAsksOnceAfterAnOlderPage() = runTest {
        val transport = Transport().apply { snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001
        transport.holdSnapshots = true
        transport.activity(active(1, 1_500, epoch = "gen-2")); runCurrent()
        val page = transport.snapshots().last()
        val pageRevision = transport.revision
        val pages = transport.snapshots().size
        // A new actor that waits for input arrives while the page runs.
        upsert(transport, agent("inst-1", "call-1", activity = idle(1, epoch = "child"), needsInput = true))
        assertEquals(pages, transport.snapshots().size)
        transport.snapshotActivity = active(1, 1_500, epoch = "gen-2")
        transport.snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = idle(1, epoch = "child"), needsInput = true)))
        transport.result(page, JsonObject(transport.snapshot(page) + ("revision" to JsonPrimitive(pageRevision)))); runCurrent()
        // Behind the actor's message: no actor from it, and exactly one follow-up page.
        assertTrue(repo.activity().actors.isEmpty())
        assertEquals(pages + 1, transport.snapshots().size)
        val follow = transport.snapshots().last()
        transport.result(follow, JsonObject(transport.snapshot(follow) + ("revision" to JsonPrimitive(transport.revision)))); runCurrent()
        val key = ActivityActorKey("session", "call-1", "inst-1")
        assertTrue(repo.activity().actors.getValue(key).needsInput)
        assertEquals(pages + 1, transport.snapshots().size)
    }

    @Test fun anOlderMessageCannotEndOrReviveAnActor() = runTest {
        val transport = Transport().apply {
            revision = 100
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1", needsInput = true)))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val key = ActivityActorKey("session", "call-1", "inst-1")
        val before = repo.activity().actors.getValue(key)
        assertTrue(before.needsInput)
        // Stale terminal and omission messages from before the page change nothing.
        upsertAt(transport, 90, agent("inst-1", "call-1", state = "succeeded"))
        upsertAt(transport, 91)
        assertEquals(before, repo.activity().actors[key])
        // A newer terminal message ends it.
        upsertAt(transport, 101, agent("inst-1", "call-1", state = "succeeded"))
        assertNull(repo.activity().actors[key])
        // A delayed running message cannot bring it back, nor ask for a snapshot.
        monotonic += 10_001
        val pages = transport.snapshots().size
        upsertAt(transport, 95, agent("inst-1", "call-1", activity = active(5, 1_500, epoch = "child")))
        assertNull(repo.activity().actors[key])
        assertEquals(pages, transport.snapshots().size)
    }

    @Test fun aLowerSeqNeverChangesNeedsInput() = runTest {
        val transport = Transport().apply {
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", agent("inst-1", "call-1", activity = active(3, 1_500, epoch = "child"))))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val key = ActivityActorKey("session", "call-1", "inst-1")
        upsert(transport, agent("inst-1", "call-1", activity = active(2, 1_500, epoch = "child"), needsInput = true))
        assertFalse(repo.activity().actors.getValue(key).needsInput)
        assertEquals(3L, repo.activity().actors.getValue(key).view.snapshot.seq)
    }

    @Test fun everyActorMutationIsFencedByItsSource() = runTest {
        val key = ActivityActorKey("session", "call-1", "inst-1")
        fun running(seq: Long, wait: Boolean = false) = agent("inst-1", "call-1", activity = active(seq, 1_500, epoch = "child"), needsInput = wait)
        val terminal = agent("inst-1", "call-1", state = "succeeded")
        // (revision, agents, actor expected afterwards: null = absent, else its seq and wait)
        data class Step(val revision: Long, val agents: List<JsonObject>, val expected: Pair<Long, Boolean>?)
        val steps = listOf(
            Step(95, listOf(running(9)), 1L to true), // stale running: no change
            Step(90, listOf(terminal), 1L to true), // stale terminal: no end
            Step(100, listOf(terminal), 1L to true), // the page's own revision: idempotent
            Step(101, listOf(terminal), null), // newer terminal ends it
            Step(95, listOf(running(9)), null), // stale running cannot revive
            Step(101, listOf(running(9)), null), // same revision as the end: idempotent
        )
        val transport = Transport().apply {
            revision = 100
            snapshotActivity = active(1, 1_500)
            snapshotMessages = listOf(toolMessage("m1", "call-1", running(1, wait = true)))
        }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val pinned = repo.activity().actors.getValue(key).observedUntil
        monotonic += 10_001
        val pages = transport.snapshots().size
        for ((index, step) in steps.withIndex()) {
            upsertAt(transport, step.revision, *step.agents.toTypedArray())
            val actor = repo.activity().actors[key]
            if (step.expected == null) assertNull("step $index", actor)
            else {
                assertEquals("step $index", step.expected.first, actor?.view?.snapshot?.seq)
                assertEquals("step $index", step.expected.second, actor?.needsInput)
                assertEquals("step $index", pinned, actor?.observedUntil)
            }
        }
        // None of them asked for a snapshot.
        assertEquals(pages, transport.snapshots().size)
        // A running message newer than the end is a new observation: it asks for a snapshot.
        upsertAt(transport, 102, running(10))
        assertNull(repo.activity().actors[key])
        assertEquals(pages + 1, transport.snapshots().size)
    }

    @Test fun anActorAPageNeverNamedCannotComeFromAnOlderMessage() = runTest {
        val transport = Transport().apply { revision = 100; snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001
        val pages = transport.snapshots().size
        upsertAt(transport, 95, agent("inst-9", "call-1"))
        assertTrue(repo.activity().actors.isEmpty())
        assertEquals(pages, transport.snapshots().size)
    }

    @Test fun aTerminalRowOfAnUnseenActorStampsItsSource() = runTest {
        val transport = Transport().apply { revision = 100; snapshotActivity = active(1, 1_500) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        monotonic += 10_001
        val pages = transport.snapshots().size
        // Gap-free revisions, so no resume is asked; held, so no stale page answers the recovery.
        transport.holdSnapshots = true
        upsertAt(transport, 101)
        upsertAt(transport, 102, agent("inst-9", "call-1", state = "succeeded"))
        // Above the page (100) but below the terminal row's own source (102).
        upsertAt(transport, 101, agent("inst-9", "call-1"))
        assertEquals(pages, transport.snapshots().size)
        upsertAt(transport, 103, agent("inst-9", "call-1"))
        assertEquals(pages + 1, transport.snapshots().size)
        // While that page is held, a newer running row asks nothing more.
        upsertAt(transport, 104, agent("inst-9", "call-1"))
        assertEquals(pages + 1, transport.snapshots().size)
        assertTrue(repo.activity().actors.isEmpty())
    }
}
