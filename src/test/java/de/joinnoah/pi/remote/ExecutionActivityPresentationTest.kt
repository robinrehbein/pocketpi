package de.joinnoah.pi.remote

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OBSERVED_UNTIL = MAX_ACTIVITY_REMAINING_MS

class ExecutionActivityPresentationTest {
    private val destination = RemoteSelection("host", "project", "s1")
    private val conn = "7"
    private val clock = ActivityClockBaseline(conn, "clk", offset = 1_000)

    private fun active(
        phase: ActivityPhase,
        expiresAt: Long = 10_000,
        seq: Long = 1,
        level: String? = null,
        tools: List<ActivityTool> = emptyList(),
        omitted: Int? = null,
    ) = ActivitySnapshot.Active("clk", "e1", seq, expiresAt, phase, level, tools, omitted)

    private fun state(
        snapshot: ActivitySnapshot? = null,
        negotiated: Boolean = true,
        status: String = "running",
        actors: Map<ActivityActorKey, ActivityActorView> = emptyMap(),
        tasks: SessionTasks = SessionTasks(),
        observedUntil: Long = OBSERVED_UNTIL,
    ) = RemoteState(
        connected = true,
        selection = destination,
        status = status,
        tasks = tasks,
        executionActivity = ExecutionActivityState(
            connectionId = conn,
            negotiated = negotiated,
            clock = clock,
            sessions = snapshot?.let { mapOf("s1" to ActivityView(conn, 1, it)) }.orEmpty(),
            // Accepted at client time 0: animation never outlasts 0 + MAX_ACTIVITY_REMAINING_MS.
            sessionObservedUntil = snapshot?.let { mapOf("s1" to observedUntil) }.orEmpty(),
            actors = actors,
        ),
    )

    // Local deadline = expiresAt + offset = 11_000.
    private val before = 10_999L
    private val after = 11_000L

    @Test fun listUsesItsOwnFreshPhaseWithoutRequiringAnOpenChat() {
        val row = sessionListItem(
            JsonObject(mapOf(
                "id" to kotlinx.serialization.json.JsonPrimitive("s1"),
                "title" to kotlinx.serialization.json.JsonPrimitive("Session"),
                "origin" to kotlinx.serialization.json.JsonPrimitive("tui"),
                "status" to kotlinx.serialization.json.JsonPrimitive("running"),
            )),
            connected = true, loading = false, confirmed = true,
        )
        val snapshot = active(ActivityPhase.Writing)
        val s = state(snapshot).copy(selection = RemoteSelection("host", "project"))
        assertEquals(snapshot, sessionListActivity(s, row, before))
        for (phase in ActivityPhase.entries) {
            val phaseSnapshot = active(phase)
            assertEquals(phaseSnapshot, sessionListActivity(state(phaseSnapshot), row, before))
        }
        assertNull(sessionListActivity(s, row.copy(id = "other"), before))
        assertNull(sessionListActivity(s, row, after))
        assertNull(sessionListActivity(s, row.copy(lastKnown = true), before))
        assertNull(sessionListActivity(s, row.copy(compacting = true), before))
        for (availability in listOf(SessionAvailability.IDLE, SessionAvailability.WAITING, SessionAvailability.OFFLINE)) {
            assertNull(sessionListActivity(s, row.copy(availability = availability), before))
        }
        for (unavailable in listOf(s.copy(connected = false), s.copy(loading = true), s.copy(stale = true), s.copy(preview = true), state(snapshot, negotiated = false))) {
            assertNull(sessionListActivity(unavailable, row, before))
        }
    }

    @Test fun legacyCallsWithoutNowNeedNoAndroidClock() {
        // Plain JVM: no android.os.SystemClock is reachable; the legacy path must not touch it.
        val items = listOf(ConversationItem.Activity("a", "a", "read", "{\"path\":\"x\"}", null, "streaming", false))
        assertTrue(chatStatusLive(state(negotiated = false), destination))
        assertNotNull(sessionActivityLine(state(negotiated = false), items, destination))
    }

    @Test fun negotiatedFreshSnapshotWithoutNowNeverAnimates() {
        val s = state(active(ActivityPhase.Writing))
        val items = listOf(ConversationItem.Activity("a", "a", "read", "{\"path\":\"x\"}", null, "streaming", false))
        assertFalse(chatStatusLive(s, destination))
        val line = sessionActivityLine(s, items, destination)!!
        assertFalse(line.animate)
        assertEquals(R.string.remote_chat_activity_working, line.labelRes)
        // With an explicit fresh monotonic now the same state animates.
        assertTrue(chatStatusLive(s, destination, before))
        assertTrue(sessionActivityLine(s, items, destination, before)!!.animate)
    }

    @Test fun legacyConnectionKeepsTheLegacyLine() {
        val items = listOf(ConversationItem.Activity("a", "a", "read", "{\"path\":\"x\"}", null, "streaming", false))
        val line = sessionActivityLine(state(negotiated = false), items, destination, before)!!
        assertEquals(StatusGlyphKind.TOOL_RUNNING, line.glyphKind)
        assertTrue(line.animate)
        assertNull(typedSessionActivity(state(negotiated = false), "s1", before))
    }

    @Test fun negotiatedPhaseComesOnlyFromTheTypedViewNeverFromStreamingItems() {
        // A streaming tool card in the transcript, but the typed state says writing.
        val items = listOf(ConversationItem.Activity("a", "a", "bash", "{}", null, "streaming", false, toolCallId = "c1"))
        val line = sessionActivityLine(state(active(ActivityPhase.Writing)), items, destination, before)!!
        assertEquals(R.string.remote_chat_activity_writing, line.labelRes)
        assertEquals(StatusGlyphKind.SESSION_RUNNING, line.glyphKind)
        assertEquals(ActivityPhase.Writing, line.phase)
        assertTrue(line.animate)
    }

    @Test fun missingIdleUnknownAndExpiredAreStaticNeverAToolSpinner() {
        val items = listOf(ConversationItem.Activity("a", "a", "read", "{}", null, "streaming", false))
        val cases = listOf(
            state(null) to before,
            state(ActivitySnapshot.Idle("clk", "e1", 2)) to before,
            state(ActivitySnapshot.Unknown("clk", "e1", 2)) to before,
            state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read")))) to after,
        )
        for ((s, now) in cases) {
            val line = sessionActivityLine(s, items, destination, now)!!
            assertFalse(line.animate)
            assertEquals(StatusGlyphKind.SESSION_RUNNING, line.glyphKind)
            assertNull(line.phase)
            assertEquals(TypedActivity.Static, typedSessionActivity(s, "s1", now))
            assertFalse(chatStatusLive(s, destination, now))
            assertNull(chatStatusPhase(s, destination, now))
        }
        // Not running and nothing live: no line at all.
        assertNull(sessionActivityLine(state(null, status = "idle"), items, destination, before))
    }

    @Test fun headerAnimatesTheTypedPhaseOnlyWhileFresh() {
        val s = state(active(ActivityPhase.Thinking, level = "high"))
        assertTrue(chatStatusLive(s, destination, before))
        assertEquals(ActivityPhase.Thinking, chatStatusPhase(s, destination, before)!!.phase)
        assertFalse(chatStatusLive(s, destination, after))
        // A waiting header never animates a phase.
        assertNull(chatStatusPhase(s.copy(status = "waiting"), destination, before))
    }

    @Test fun farFutureDeadlineStopsAtTheObservationCap() {
        val far = state(active(ActivityPhase.Writing, expiresAt = 10_000_000))
        assertEquals(MAX_ACTIVITY_REMAINING_MS, nextActivityDeadlineMs(far.executionActivity, 0))
        // Live until acceptance + 30 s, then stale although the host deadline lies far ahead.
        assertTrue(typedSessionActivity(far, "s1", OBSERVED_UNTIL - 1) is TypedActivity.Live)
        assertEquals(1L, nextActivityDeadlineMs(far.executionActivity, OBSERVED_UNTIL - 1))
        assertEquals(TypedActivity.Static, typedSessionActivity(far, "s1", OBSERVED_UNTIL))
        assertNull(nextActivityDeadlineMs(far.executionActivity, OBSERVED_UNTIL))
        assertFalse(chatStatusLive(far, destination, OBSERVED_UNTIL))
        // The earlier host deadline still wins when it comes first.
        assertEquals(1L, nextActivityDeadlineMs(state(active(ActivityPhase.Pending)).executionActivity, before))
        assertNull(nextActivityDeadlineMs(state(active(ActivityPhase.Pending), negotiated = false).executionActivity, 0))
        // No pin, no animation.
        assertEquals(TypedActivity.Static, typedSessionActivity(state(active(ActivityPhase.Writing)).let {
            it.copy(executionActivity = it.executionActivity.copy(sessionObservedUntil = emptyMap()))
        }, "s1", 0))
    }

    @Test fun aDuplicateClockSampleOrSameSeqNeverExtendsTheCap() {
        val far = state(active(ActivityPhase.Writing, expiresAt = 10_000_000))
        // A better offset from a later clock sample moves the host deadline, never the pin.
        val resampled = far.copy(executionActivity = far.executionActivity.copy(clock = clock.copy(offset = 5_000)))
        assertEquals(TypedActivity.Static, typedSessionActivity(resampled, "s1", OBSERVED_UNTIL))
        // The same seq replayed leaves the pin as the repository keeps it: still stale.
        val replayed = resampled.copy(executionActivity = resampled.executionActivity.copy(
            sessions = mapOf("s1" to ActivityView(conn, 2, active(ActivityPhase.Writing, expiresAt = 10_000_000)))))
        assertEquals(TypedActivity.Static, typedSessionActivity(replayed, "s1", OBSERVED_UNTIL + 1))
        // Actors: the same, by their own pin.
        val key = ActivityActorKey("s1", "call-sub", "inst-1")
        val actors = mapOf(key to ActivityActorView(ActivityView(conn, 1, active(ActivityPhase.Tool, expiresAt = 10_000_000, tools = listOf(ActivityTool("t", "read")))), false, OBSERVED_UNTIL))
        val s = state(active(ActivityPhase.Writing, expiresAt = 10_000_000), actors = actors).let {
            it.copy(executionActivity = it.executionActivity.copy(clock = clock.copy(offset = 5_000)))
        }
        val row = agent("scout", "inst-1", "call-sub")
        assertNotNull(actorRowActivity(s, "s1", "call-sub", row, OBSERVED_UNTIL - 1)!!.live)
        assertNull(actorRowActivity(s, "s1", "call-sub", row, OBSERVED_UNTIL))
    }

    @Test fun toolLineNamesTheFirstTypedToolAndCountsTheRestWithoutNames() {
        val s = state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read"), ActivityTool("c2", "grep")), omitted = 5))
        val line = sessionActivityLine(s, emptyList(), destination, before)!!
        assertEquals(R.string.remote_tool_read, line.labelRes)
        assertEquals(StatusGlyphKind.TOOL_RUNNING, line.glyphKind)
        assertEquals(6, line.extraTools)
        val onlyOmitted = sessionActivityLine(state(active(ActivityPhase.Tool, omitted = 3)), emptyList(), destination, before)!!
        assertEquals(R.string.remote_chat_activity_working, onlyOmitted.labelRes)
        assertEquals(3, onlyOmitted.extraTools)
    }

    @Test fun taskNeverReplacesTheTypedPhaseGlyphOrLabel() {
        val tasks = SessionTasks(
            items = listOf(SessionTask(1L, "Fix it", TaskStatus.IN_PROGRESS, activeForm = "Fixing it")),
            freshness = TaskFreshness.LIVE,
        )
        val stale = sessionActivityLine(state(null, tasks = tasks), emptyList(), destination, before)!!
        assertEquals(StatusGlyphKind.SESSION_RUNNING, stale.glyphKind)
        assertEquals(R.string.remote_chat_activity_working, stale.labelRes)
        assertNull(stale.detail)
        assertFalse(stale.animate)
        val thinking = sessionActivityLine(state(active(ActivityPhase.Thinking), tasks = tasks), emptyList(), destination, before)!!
        assertEquals(StatusGlyphKind.SESSION_RUNNING, thinking.glyphKind)
        assertEquals(R.string.remote_chat_activity_thinking, thinking.labelRes)
        assertEquals("Fixing it", thinking.detail)
        assertTrue(thinking.animate)
        val tool = sessionActivityLine(
            state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read"))), tasks = tasks), emptyList(), destination, before,
        )!!
        assertEquals(StatusGlyphKind.TOOL_RUNNING, tool.glyphKind)
        assertEquals(R.string.remote_tool_read, tool.labelRes)
        for (phase in ActivityPhase.entries) {
            val line = sessionActivityLine(
                state(active(phase, tools = if (phase == ActivityPhase.Tool) listOf(ActivityTool("c1", "x")) else emptyList()), tasks = tasks),
                emptyList(), destination, before,
            )!!
            assertEquals(activityGlyphKind(phase), line.glyphKind)
        }
    }

    @Test fun explicitWaitOwnsTheHeaderAndHidesThePhaseLine() {
        val live = active(ActivityPhase.Writing)
        assertNull(sessionActivityLine(state(live, status = "waiting"), emptyList(), destination, before))
        assertNull(chatStatusPhase(state(live, status = "waiting"), destination, before))
        assertFalse(chatStatusLive(state(live, status = "waiting"), destination, before))
        val asked = state(live).copy(questions = listOf(Wire.objectOf("id" to "q1")))
        assertNull(sessionActivityLine(asked, emptyList(), destination, before))
        assertEquals(ChatStatusShape.WAITING, chatStatusShape(chatHeaderState(asked)))
        assertNull(chatStatusPhase(chatHeaderState(asked), destination, before))
    }

    @Test fun toolCardBouncesOnlyForItsExactTypedCall() {
        fun card(id: String?) = ConversationItem.Activity("a", "a", "read", "{}", null, "streaming", false, toolCallId = id)
        val typed = typedSessionActivity(state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read", parentToolCallId = "p")))), "s1", before)
        assertEquals(ToolCardActivity(true), typedToolCardActivity(typed, card("c1")))
        assertEquals(ToolCardActivity(false), typedToolCardActivity(typed, card("c2")))
        assertEquals(ToolCardActivity(false), typedToolCardActivity(typed, card("p")))
        assertEquals(ToolCardActivity(false), typedToolCardActivity(typed, card(null)))
        // The tool left the typed state: static, never complete.
        val writing = typedSessionActivity(state(active(ActivityPhase.Writing, seq = 2)), "s1", before)
        assertEquals(ToolCardActivity(false), typedToolCardActivity(writing, card("c1")))
        assertNull(typedToolCardActivity(null, card("c1")))
    }

    private fun agent(name: String, instance: String?, spawn: String?, state: String = "running") =
        AgentProgress(name, state, null, null, instanceId = instance, spawnToolCallId = spawn)

    @Test fun actorRowsJoinTheLedgerOnlyByExactKeyNeverByName() {
        val key = ActivityActorKey("s1", "call-sub", "inst-1")
        val actors = mapOf(key to ActivityActorView(ActivityView(conn, 1, active(ActivityPhase.Thinking, level = "low")), false, OBSERVED_UNTIL))
        val s = state(actors = actors)
        assertEquals(ActivityPhase.Thinking, actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-sub"), before)!!.live!!.phase)
        // Same name, other instance / spawn call / parent session / card: nothing borrowed.
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-2", "call-sub"), before))
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-other"), before))
        assertNull(actorRowActivity(s, "s1", "call-other", agent("scout", "inst-1", "call-other"), before))
        assertNull(actorRowActivity(s, "s2", "call-sub", agent("scout", "inst-1", "call-sub"), before))
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", null, "call-sub"), before))
        // Queued or finished lifecycle, expiry, or a legacy connection: static.
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-sub", "queued"), before))
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-sub", "succeeded"), before))
        assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-sub"), after))
        assertNull(actorRowActivity(state(negotiated = false, actors = actors), "s1", "call-sub", agent("scout", "inst-1", "call-sub"), before))
    }

    @Test fun actorNeedsInputIsItsOwnConfirmedObservation() {
        val key = ActivityActorKey("s1", "call-sub", "inst-1")
        val row = agent("scout", "inst-1", "call-sub")
        fun ledger(snapshot: ActivitySnapshot, connection: String = conn) =
            state(actors = mapOf(key to ActivityActorView(ActivityView(connection, 1, snapshot), true, OBSERVED_UNTIL)))
        // The own accepted observation shows a static wait beside any source state, expired included:
        // it never animates, so it needs no activity deadline.
        for (source in listOf(active(ActivityPhase.Pending), ActivitySnapshot.Idle("clk", "e1", 4), ActivitySnapshot.Unknown("clk", "e1", 3)))
            for (now in listOf(before, after))
                assertEquals(ActorRowActivity(null, true), actorRowActivity(ledger(source), "s1", "call-sub", row, now))
        // Without the flag nothing is waiting, and an unknown or idle source animates nothing.
        val plain = state(actors = mapOf(key to ActivityActorView(ActivityView(conn, 1, ActivitySnapshot.Unknown("clk", "e1", 3)), false, OBSERVED_UNTIL)))
        assertNull(actorRowActivity(plain, "s1", "call-sub", row, before))
        // Another connection's entry never counts; stale, loading or another selected chat neither.
        assertNull(actorRowActivity(ledger(ActivitySnapshot.Idle("clk", "e1", 4)).copy(stale = true), "s1", "call-sub", row, before))
        assertNull(actorRowActivity(ledger(ActivitySnapshot.Idle("clk", "e1", 4)).copy(loading = true), "s1", "call-sub", row, before))
        assertNull(actorRowActivity(ledger(ActivitySnapshot.Idle("clk", "e1", 4)).copy(selection = RemoteSelection("host", "project", "other")), "s1", "call-sub", row, before))
        assertNull(actorRowActivity(ledger(ActivitySnapshot.Idle("clk", "e1", 4), connection = "6"), "s1", "call-sub", row, before))
        // Never another actor's, never a queued or finished one, never off the selected chat.
        assertNull(actorRowActivity(ledger(active(ActivityPhase.Pending)), "s1", "call-sub", agent("scout", "inst-9", "call-sub"), before))
        assertNull(actorRowActivity(ledger(active(ActivityPhase.Pending)), "s1", "call-sub", agent("scout", "inst-1", "call-sub", "queued"), before))
        assertNull(actorRowActivity(ledger(ActivitySnapshot.Idle("clk", "e1", 4)).copy(preview = true), "s1", "call-sub", row, before))
        // Its own wait still shows while the parent waits (it may be the reason); a phase does not.
        assertEquals(ActorRowActivity(null, true),
            actorRowActivity(ledger(active(ActivityPhase.Pending)).copy(status = "waiting"), "s1", "call-sub", row, before))
    }

    @Test fun parentWaitStopsEveryTypedPhase() {
        val key = ActivityActorKey("s1", "call-sub", "inst-1")
        val actors = mapOf(key to ActivityActorView(ActivityView(conn, 1, active(ActivityPhase.Writing)), false, OBSERVED_UNTIL))
        val card = ConversationItem.Activity("a", "a", "read", "{}", null, "streaming", false, toolCallId = "c1")
        val live = state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read"))), actors = actors)
        for (s in listOf(live.copy(status = "waiting"), live.copy(questions = listOf(Wire.objectOf("id" to "q1"))))) {
            val typed = typedSessionActivity(s, "s1", before)
            assertEquals(TypedActivity.Static, typed)
            assertEquals(ToolCardActivity(false), typedToolCardActivity(typed, card))
            assertNull(actorRowActivity(s, "s1", "call-sub", agent("scout", "inst-1", "call-sub"), before))
        }
    }

    @Test fun thinkingAndWritingColoursFollowTheSelectedPalette() {
        val dark = darkColorScheme(surface = Color(0xFF262335))
        val light = lightColorScheme(surface = Color.White)
        val sw = ThemePalette.SYNTHWAVE
        assertEquals(Color(0xFF9D8BCA), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "low"))
        assertEquals(Color(0xFFB893CE), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "medium"))
        assertEquals(Color(0xFFFF7EDB), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "high"))
        assertEquals(Color(0xFFF97E72), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "xhigh"))
        assertEquals(Color(0xFFFEDE5D), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "max"))
        assertEquals(Color(0xFF9479AF), activityPhaseColor(sw, light, ActivityPhase.Thinking, "low"))
        assertEquals(Color(0xFF694080), activityPhaseColor(sw, light, ActivityPhase.Thinking, "medium"))
        assertEquals(Color(0xFFA01870), activityPhaseColor(sw, light, ActivityPhase.Thinking, "high"))
        assertEquals(Color(0xFFA13232), activityPhaseColor(sw, light, ActivityPhase.Thinking, "xhigh"))
        assertEquals(Color(0xFF795500), activityPhaseColor(sw, light, ActivityPhase.Thinking, "max"))
        // off/minimal read as medium; a missing level stays neutral.
        assertEquals(Color(0xFFB893CE), activityPhaseColor(sw, dark, ActivityPhase.Thinking, "off"))
        assertEquals(Color(0xFF694080), activityPhaseColor(sw, light, ActivityPhase.Thinking, "minimal"))
        assertEquals(dark.onSurfaceVariant, activityPhaseColor(sw, dark, ActivityPhase.Thinking, null))
        assertEquals(light.onSurfaceVariant, activityPhaseColor(ThemePalette.BRAND, light, ActivityPhase.Thinking, null))
        // Brand and dynamic map onto their own scheme, not synthwave.
        assertEquals(light.primary, activityPhaseColor(ThemePalette.BRAND, light, ActivityPhase.Thinking, "medium"))
        assertEquals(dark.secondary, activityPhaseColor(ThemePalette.DYNAMIC, dark, ActivityPhase.Thinking, "low"))
        assertEquals(dark.tertiary, activityPhaseColor(ThemePalette.BRAND, dark, ActivityPhase.Thinking, "max"))
        assertEquals(light.outline, activityPhaseColor(ThemePalette.BRAND, light, ActivityPhase.Pending, null))
        assertEquals(WritingCyanDark, activityPhaseColor(ThemePalette.BRAND, dark, ActivityPhase.Writing, null))
        assertEquals(WritingCyanLight, activityPhaseColor(sw, light, ActivityPhase.Writing, null))
        assertEquals(dark.primary, activityPhaseColor(sw, dark, ActivityPhase.Tool, null))
        // DYNAMIC that cannot draw wallpaper colours is BRAND, like its scheme.
        assertEquals(ThemePalette.BRAND, effectiveThemePalette(ThemePalette.DYNAMIC, 30))
        assertEquals(ThemePalette.DYNAMIC, effectiveThemePalette(ThemePalette.DYNAMIC, 31))
        assertEquals(ThemePalette.SYNTHWAVE, effectiveThemePalette(ThemePalette.SYNTHWAVE, 30))
    }

    @Test fun onlyTheSelectedCurrentChatAnimatesNeverPreviewsLoadingStaleOrOtherSessions() {
        val key = ActivityActorKey("s1", "call-sub", "inst-1")
        val actors = mapOf(key to ActivityActorView(ActivityView(conn, 1, active(ActivityPhase.Writing)), false, OBSERVED_UNTIL))
        val live = state(active(ActivityPhase.Tool, tools = listOf(ActivityTool("c1", "read"))), actors = actors)
        val row = agent("scout", "inst-1", "call-sub")
        val card = ConversationItem.Activity("a", "a", "read", "{}", null, "streaming", false, toolCallId = "c1")
        assertTrue(typedSessionActivity(live, "s1", before) is TypedActivity.Live)
        assertNotNull(actorRowActivity(live, "s1", "call-sub", row, before))
        val blocked = listOf(
            live.copy(preview = true),
            live.copy(loading = true),
            live.copy(stale = true),
            live.copy(connected = false),
            live.copy(selection = RemoteSelection("host", "project", "other")),
        )
        for (s in blocked) {
            assertEquals(TypedActivity.Static, typedSessionActivity(s, "s1", before))
            assertEquals(ToolCardActivity(false), typedToolCardActivity(typedSessionActivity(s, "s1", before), card))
            assertNull(actorRowActivity(s, "s1", "call-sub", row, before))
        }
    }

    private fun stripEntry(id: String?, state: String = "running", lastKnown: Boolean = false) =
        SubagentStripEntry(id, "scout", "t", state, null, abortable = false, lastKnown = lastKnown)

    @Test fun stripAnimatesOnlyAnExactChildsFreshTypedViewNeverAnActorOrName() {
        val child = ActivityView(conn, 1, active(ActivityPhase.Thinking, level = "high"))
        val actorKey = ActivityActorKey("s1", "call-sub", "inst-1")
        val s = state().let {
            it.copy(executionActivity = it.executionActivity.copy(
                sessions = mapOf("child-1" to child),
                sessionObservedUntil = mapOf("child-1" to OBSERVED_UNTIL),
                actors = mapOf(actorKey to ActivityActorView(ActivityView(conn, 1, active(ActivityPhase.Pending)), true, OBSERVED_UNTIL)),
            ))
        }
        val activity = stripActivity(s, listOf("child-1", "child-2"), before)!!
        assertEquals(ActivityPhase.Thinking, stripRowActivity(activity, stripEntry("child-1"))!!.live!!.phase)
        // Another child, an ID-less record, or a matching actor elsewhere: static, no borrowed wait.
        assertEquals(StripRowActivity(null), stripRowActivity(activity, stripEntry("child-2")))
        assertEquals(StripRowActivity(null), stripRowActivity(activity, stripEntry(null)))
        // Only running, current rows take typed state; waiting stays the child's own status.
        assertNull(stripRowActivity(activity, stripEntry("child-1", "waiting")))
        assertNull(stripRowActivity(activity, stripEntry("child-1", lastKnown = true)))
        // Expired, stale or legacy.
        assertEquals(StripRowActivity(null), stripRowActivity(stripActivity(s, listOf("child-1"), after), stripEntry("child-1")))
        assertEquals(StripRowActivity(null), stripRowActivity(stripActivity(s.copy(stale = true), listOf("child-1"), before), stripEntry("child-1")))
        assertNull(stripActivity(state(negotiated = false), listOf("child-1"), before))
    }

    // ---- AgentProgress parsing --------------------------------------------------------------

    private val fixture = Wire.json.parseToJsonElement(
        javaClass.getResource("/session-activity-v1.json")!!.readText(),
    ).jsonObject

    private fun fixtureMessage(group: String, name: String): JsonObject =
        fixture.getValue(group).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == name }
            .getValue("payload").jsonObject.getValue("message").jsonObject

    private fun agents(message: JsonObject): List<AgentProgress> =
        (conversationItems(listOf(message)).singleOrNull() as? ConversationItem.Subagent)?.agents.orEmpty()

    @Test fun validFixtureAgentKeepsItsStructuredFieldsAsHistoryOnly() {
        val agent = agents(fixtureMessage("wireValid", "subagent-structured")).single()
        assertEquals("inst-1", agent.instanceId)
        assertEquals("call-sub", agent.spawnToolCallId)
        assertTrue(agent.needsInput)
        assertEquals(ActivityPhase.Tool, (agent.executionActivity as ActivitySnapshot.Active).phase)
        // Recorded activity proves nothing: without a ledger entry the row stays static.
        assertNull(actorRowActivity(state(), "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d", "call-sub", agent, 0))
    }

    @Test fun invalidFixtureAgentsAreDroppedNotKeptLive() {
        for (name in listOf("subagent-bad-instance", "subagent-needs-input-false", "subagent-bad-execution")) {
            val message = fixtureMessage("wireInvalid", name)
            assertTrue(name, agents(message).isEmpty())
            // The message falls back to a plain tool card, never a running agent row.
            val item = conversationItems(listOf(message)).single()
            assertTrue(name, item is ConversationItem.Activity)
        }
    }

    @Test fun duplicateInstanceIdsLoseTheirStructuredFields() {
        val structured = fixtureMessage("wireValid", "subagent-structured")
        val agent = structured.getValue("subagentProgress").jsonObject.getValue("agents").jsonArray.single()
        val progress = Wire.objectOf("mode" to "parallel", "agents" to kotlinx.serialization.json.JsonArray(listOf(agent, agent)))
        val message = JsonObject(structured + ("subagentProgress" to progress))
        val parsed = agents(message)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.instanceId == null && it.executionActivity == null && !it.needsInput })
        assertNotNull(parsed.first().name)
    }

    @Test fun needsInputCountsOnlyOnARunningAgent() {
        val structured = fixtureMessage("wireValid", "subagent-structured")
        val agent = structured.getValue("subagentProgress").jsonObject.getValue("agents").jsonArray.single().jsonObject
        val done = JsonObject(agent - "executionActivity" + ("state" to kotlinx.serialization.json.JsonPrimitive("succeeded")))
        val message = JsonObject(structured + ("subagentProgress" to Wire.objectOf("mode" to "single", "agents" to kotlinx.serialization.json.JsonArray(listOf(done)))))
        assertFalse(agents(message).single().needsInput)
    }
}
