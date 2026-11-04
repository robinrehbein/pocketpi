package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionActivityTest {
    private val fixture = Wire.json.parseToJsonElement(
        javaClass.getResource("/session-activity-v1.json")!!.readText(),
    ).jsonObject

    private fun cases(key: String): Map<String, JsonObject> =
        fixture.getValue(key).jsonArray.associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("payload").jsonObject
        }

    /** Validates every activity part of a fixture payload the Android receiver reads. */
    private fun activityPartsValid(payload: JsonObject): Boolean {
        val type = payload.getValue("type").jsonPrimitive.content
        if (type == "result") {
            val data = payload.getValue("data").jsonObject
            parseActivityAnswer(data) ?: return false
            if (data.getValue("kind").jsonPrimitive.content == "session.events") {
                return data.getValue("events").jsonArray.all { parseSessionActivityEvent(it.jsonObject) != null }
            }
            return true
        }
        return when (payload.getValue("kind").jsonPrimitive.content) {
            "session.activity" -> parseSessionActivityEvent(payload) != null
            "message.upsert" -> payload.getValue("message").jsonObject.getValue("subagentProgress").jsonObject
                .getValue("agents").jsonArray.all {
                    val agent = it.jsonObject
                    !agent.containsKey("executionActivity") || parseActivitySnapshot(agent["executionActivity"]) != null
                }
            else -> error("unclassified payload kind")
        }
    }

    /** Fixture invalid cases about subagent fields other than `executionActivity`. */
    private val outOfScopeInvalid = setOf("subagent-bad-instance", "subagent-needs-input-false")

    @Test fun everyValidFixtureCaseParses() {
        val valid = cases("wireValid")
        assertEquals(17, valid.size)
        for ((name, payload) in valid) assertTrue(name, activityPartsValid(payload))
    }

    @Test fun everyInvalidFixtureCaseIsRejectedOrExplicitlyOutOfScope() {
        val invalid = cases("wireInvalid")
        assertTrue(invalid.keys.containsAll(outOfScopeInvalid))
        for ((name, payload) in invalid) {
            if (name in outOfScopeInvalid) {
                assertTrue("out-of-scope case only fails outside activity: $name", activityPartsValid(payload))
            } else {
                assertFalse(name, activityPartsValid(payload))
            }
        }
    }

    @Test fun containerOnlyFailuresHaveValidSnapshots() {
        val invalid = cases("wireInvalid")
        for (name in listOf("sessions-clock-mismatch", "snapshot-clock-mismatch", "sessions-activity-without-clock", "snapshot-activity-without-clock")) {
            val data = invalid.getValue(name).getValue("data").jsonObject
            val activity = data["activity"] ?: data.getValue("items").jsonArray[0].jsonObject["activity"]
            assertNotNull(name, parseActivitySnapshot(activity))
            assertNull(name, parseActivityAnswer(data))
        }
        val event = invalid.getValue("event-after-not-below-revision")
        assertNotNull(parseActivitySnapshot(event["activity"]))
        assertNull(parseSessionActivityEvent(event))
    }

    @Test fun parsedFixtureValuesMatch() {
        val valid = cases("wireValid")
        val nested = parseSessionActivityEvent(valid.getValue("event-active-tools-nested"))!!.activity as ActivitySnapshot.Active
        assertEquals(ActivityPhase.Tool, nested.phase)
        assertEquals(
            listOf(ActivityTool("call-a", "read"), ActivityTool("call-b", "read"), ActivityTool("call-c", "bash", "call-sub")),
            nested.tools,
        )
        assertEquals(listOf("call-a", "call-b", "call-c"), nested.tools.map { it.toolCallId })
        val omitted = parseSessionActivityEvent(valid.getValue("event-active-tools-omitted-only"))!!.activity as ActivitySnapshot.Active
        assertEquals(emptyList<ActivityTool>(), omitted.tools)
        assertEquals(3, omitted.toolsOmitted)
        val high = parseSessionActivityEvent(valid.getValue("event-active-thinking-level"))!!.activity as ActivitySnapshot.Active
        assertEquals("high", high.thinkingLevel)
        val pending = parseSessionActivityEvent(valid.getValue("event-active-pending"))!!
        assertNull((pending.activity as ActivitySnapshot.Active).thinkingLevel)
        assertNull(pending.after)
        assertEquals(9L, parseSessionActivityEvent(valid.getValue("event-with-after"))!!.after)
        assertEquals("grüße👋", ((parseSessionActivityEvent(valid.getValue("event-utf8-name"))!!.activity as ActivitySnapshot.Active).tools[0].name))
        assertTrue(parseSessionActivityEvent(valid.getValue("event-idle"))!!.activity is ActivitySnapshot.Idle)
        assertTrue(parseSessionActivityEvent(valid.getValue("event-unknown"))!!.activity is ActivitySnapshot.Unknown)

        val list = parseActivityAnswer(valid.getValue("sessions-with-clock").getValue("data").jsonObject)!!
        assertEquals(ActivityClock("clk-7f3a9c", 100_000), list.clock)
        assertEquals(setOf("019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"), list.activities.keys)
        assertEquals(ActivityAnswer(ActivityClock("clk-7f3a9c", 100_000), emptyMap()), parseActivityAnswer(valid.getValue("sessions-clock-only").getValue("data").jsonObject))
        assertEquals(ActivityAnswer(null, emptyMap()), parseActivityAnswer(valid.getValue("sessions-legacy").getValue("data").jsonObject))
        val snap = parseActivityAnswer(valid.getValue("snapshot-with-activity").getValue("data").jsonObject)!!
        assertTrue(snap.activities.values.single() is ActivitySnapshot.Idle)
        assertEquals(ActivityClock("clk-7f3a9c", 100_000), parseActivityAnswer(valid.getValue("pong-with-clock").getValue("data").jsonObject)!!.clock)
        // Replay carries a clock but never baseline activities.
        val replay = parseActivityAnswer(valid.getValue("session-events-with-activity-and-clock").getValue("data").jsonObject)!!
        assertEquals(emptyMap<String, ActivitySnapshot>(), replay.activities)
        assertNotNull(replay.clock)
        assertNull(parseActivityAnswer(JsonObject(mapOf("kind" to JsonPrimitive("session")))))
    }

    private fun snap(json: String) = parseActivitySnapshot(Wire.json.parseToJsonElement(json))

    private fun activeJson(extra: String = "", seq: String = "1", tools: String = "[]", phase: String = "pending") =
        """{"clockId":"c","epoch":"e","seq":$seq,"state":"active","expiresAt":5,"phase":"$phase","tools":$tools$extra}"""

    @Test fun safeIntegersMirrorJs() {
        assertEquals(1000L, snap(activeJson(seq = "1e3"))?.seq)
        assertEquals(4L, snap(activeJson(seq = "4.0"))?.seq)
        assertEquals(0L, snap(activeJson(seq = "-0"))?.seq)
        assertEquals(9_007_199_254_740_991L, snap(activeJson(seq = "9007199254740991"))?.seq)
        assertNull(snap(activeJson(seq = "9007199254740992")))
        assertNull(snap(activeJson(seq = "1e400")))
        assertNull(snap(activeJson(seq = "1.5")))
        assertNull(snap(activeJson(seq = "-1")))
        assertNull(snap(activeJson(seq = "\"1\"")))
        assertNull(snap(activeJson(seq = "true")))
        assertNull(snap(activeJson(seq = "null")))
    }

    @Test fun idsUseAsciiGrammarAndUtf8Bytes() {
        assertNotNull(snap("""{"clockId":"ü","epoch":"e","seq":1,"state":"idle"}"""))
        assertNull(snap("""{"clockId":"a b","epoch":"e","seq":1,"state":"idle"}"""))
        assertNotNull(snap("""{"clockId":"a\u00a0b","epoch":"e","seq":1,"state":"idle"}""")) // NBSP is not ASCII whitespace
        assertNull(snap("""{"clockId":"a\u007fb","epoch":"e","seq":1,"state":"idle"}"""))
        assertNotNull(snap("""{"clockId":"${"a".repeat(256)}","epoch":"e","seq":1,"state":"idle"}"""))
        assertNull(snap("""{"clockId":"${"a".repeat(257)}","epoch":"e","seq":1,"state":"idle"}"""))
        assertNull(snap("""{"clockId":"${"é".repeat(129)}","epoch":"e","seq":1,"state":"idle"}"""))
        assertEquals(3, activityUtf8Length("\ud800"))
        assertEquals(4, activityUtf8Length("👋"))
        // Tool names: 64 bytes, spaces allowed, controls not.
        assertNotNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"${"é".repeat(32)}"}]""")))
        assertNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"${"é".repeat(33)}"}]""")))
        assertNotNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"my tool"}]""")))
        assertNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"a\tb"}]""")))
    }

    @Test fun keysAndPhaseInvariants() {
        assertNull(snap("""{"clockId":"c","epoch":"e","seq":1,"state":"idle","expiresAt":0}"""))
        assertNull(snap(activeJson(extra = ""","thinkingLevel":null""")))
        assertNull(snap(activeJson(extra = ""","toolsOmitted":null""", phase = "tool")))
        assertEquals("off", (snap(activeJson(extra = ""","thinkingLevel":"off"""")) as ActivitySnapshot.Active).thinkingLevel)
        assertNull(snap(activeJson(phase = "thinking", tools = """[{"toolCallId":"a","name":"r"}]""")))
        assertNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"r","parentToolCallId":null}]""")))
        assertNotNull(snap(activeJson(phase = "tool", tools = "[]", extra = ""","toolsOmitted":1000000""")))
        val many = (0 until MAX_ACTIVITY_TOOLS).joinToString(",", "[", "]") { """{"toolCallId":"t$it","name":"r"}""" }
        assertEquals(MAX_ACTIVITY_TOOLS, (snap(activeJson(phase = "tool", tools = many)) as ActivitySnapshot.Active).tools.size)
    }

    // ---- Receiver ----

    private fun active(seq: Long, expiresAt: Long, epoch: String = "gen-1", clockId: String = "clk") =
        ActivitySnapshot.Active(clockId, epoch, seq, expiresAt, ActivityPhase.Pending, null, emptyList(), null)

    @Test fun liveEventsNeedBaselineAndIgnoreDuplicatesAndOlder() {
        assertEquals(ActivityApplyOutcome.NeedsSnapshot, applyActivityEvent(null, "c1", active(1, 100)).outcome)
        var view = applyActivityBaseline(null, beginActivityRequest("c1", 1, 0), "c1", active(3, 100)).view!!
        val dup = applyActivityEvent(view, "c1", active(3, 999))
        assertEquals(ActivityApplyOutcome.Ignored, dup.outcome)
        assertSame(view, dup.view)
        val older = applyActivityEvent(view, "c1", active(2, 999))
        assertEquals(ActivityApplyOutcome.Ignored, older.outcome)
        assertEquals(100L, (older.view!!.snapshot as ActivitySnapshot.Active).expiresAt)
        val gap = applyActivityEvent(view, "c1", active(9, 200))
        assertEquals(ActivityApplyOutcome.Applied, gap.outcome)
        view = gap.view!!
        assertEquals(9L, view.snapshot.seq)
        assertEquals(1L, view.baselineRequestSeq)
        val idle = applyActivityEvent(view, "c1", ActivitySnapshot.Idle("clk", "gen-1", 10))
        assertTrue(idle.view!!.snapshot is ActivitySnapshot.Idle)
    }

    @Test fun replayWithoutBaselineCannotBaseline() {
        val replay = cases("wireValid").getValue("session-events-with-activity-and-clock").getValue("data").jsonObject
        var view: ActivityView? = null
        for (e in replay.getValue("events").jsonArray) {
            val r = applyActivityEvent(view, "c1", parseSessionActivityEvent(e.jsonObject)!!.activity)
            assertEquals(ActivityApplyOutcome.NeedsSnapshot, r.outcome)
            view = r.view
        }
        assertNull(view)
    }

    @Test fun onlyBaselineSwitchesEpochOrClock() {
        val view = applyActivityBaseline(null, beginActivityRequest("c1", 1, 0), "c1", active(5, 100)).view!!
        assertEquals(ActivityApplyOutcome.NeedsSnapshot, applyActivityEvent(view, "c1", active(1, 100, "gen-2")).outcome)
        assertEquals(ActivityApplyOutcome.NeedsSnapshot, applyActivityEvent(view, "c1", active(6, 100, clockId = "clk-2")).outcome)
        val next = applyActivityBaseline(view, beginActivityRequest("c1", 2, 0), "c1", active(1, 100, "gen-2"))
        assertEquals(ActivityApplyOutcome.Applied, next.outcome)
        assertEquals(ActivityApplyOutcome.NeedsSnapshot, applyActivityEvent(next.view, "c1", active(50, 100)).outcome)
    }

    @Test fun baselineFencingAndReconnect() {
        val t1 = beginActivityRequest("c1", 1, 0)
        val t2 = beginActivityRequest("c1", 2, 0)
        var view = applyActivityBaseline(null, t2, "c1", active(4, 100)).view!!
        assertEquals(ActivityApplyOutcome.Ignored, applyActivityBaseline(view, t1, "c1", active(1, 100, "gen-0")).outcome)
        view = applyActivityEvent(view, "c1", active(7, 100)).view!!
        val rollback = applyActivityBaseline(view, beginActivityRequest("c1", 3, 0), "c1", active(6, 100))
        assertEquals(ActivityApplyOutcome.Ignored, rollback.outcome)
        assertEquals(7L, rollback.view!!.snapshot.seq)
        assertEquals(3L, rollback.view!!.baselineRequestSeq)
        // Same request seq as the baseline is not stale.
        assertEquals(ActivityApplyOutcome.Applied, applyActivityBaseline(view, t2, "c1", active(8, 100)).outcome)
        // Reconnect: old view needs a fresh baseline and old tokens are dead.
        assertEquals(ActivityApplyOutcome.NeedsSnapshot, applyActivityEvent(view, "c2", active(8, 100)).outcome)
        assertEquals(ActivityApplyOutcome.Ignored, applyActivityBaseline(view, t2, "c2", active(8, 100)).outcome)
        val fresh = applyActivityBaseline(view, beginActivityRequest("c2", 1, 0), "c2", active(2, 100))
        assertEquals(ActivityApplyOutcome.Applied, fresh.outcome)
        assertEquals("c2", fresh.view!!.connectionId)
    }

    @Test fun clockBaselineKeepsConservativeMaximum() {
        fun observe(b: ActivityClockBaseline?, start: Long, sampledAt: Long, conn: String = "c1", clockId: String = "clk") =
            observeActivityClock(b, beginActivityRequest(conn, 1, start), "c1", ActivityClock(clockId, sampledAt))
        var b = observe(null, 1000, 50_000)
        assertEquals(-49_000L, b!!.offset)
        b = observe(b, 2000, 50_500)
        assertEquals(-48_500L, b!!.offset)
        b = observe(b, 2100, 52_000)
        assertEquals(-48_500L, b!!.offset)
        val before = b
        assertSame(before, observe(b, 9000, 0, conn = "c0"))
        assertEquals(ActivityClockBaseline("c1", "clk-2", 2990), observe(b, 3000, 10, clockId = "clk-2"))
        assertEquals(ActivityClockBaseline("c1", "clk", 5), observeActivityClock(ActivityClockBaseline("c0", "clk", 999), beginActivityRequest("c1", 1, 5), "c1", ActivityClock("clk", 0)))
    }

    @Test fun freshnessMapsAbsoluteDeadlineCapsAndFreezes() {
        val request = beginActivityRequest("c1", 1, 1000)
        val baseline = observeActivityClock(null, request, "c1", ActivityClock("clk", 50_000))!!
        // offset -49_000; host deadline 60_000 maps to local 11_000.
        val view = applyActivityBaseline(null, request, "c1", active(1, 60_000)).view!!
        assertEquals(ActivityFreshness(true, 6000), activityFreshness(view, baseline, "c1", 5000))
        assertEquals(ActivityFreshness(true, 1), activityFreshness(view, baseline, "c1", 10_999))
        assertEquals(ActivityFreshness(false, 0), activityFreshness(view, baseline, "c1", 11_000))
        assertTrue(view.snapshot is ActivitySnapshot.Active)
        // Far deadline: cap bounds only the reported remaining time; the mapped deadline stays absolute.
        val far = applyActivityEvent(view, "c1", active(2, 500_000)).view!!
        assertEquals(MAX_ACTIVITY_REMAINING_MS, activityFreshness(far, baseline, "c1", 5000).remainingMs)
        assertEquals(ActivityFreshness(true, 1), activityFreshness(far, baseline, "c1", 450_999))
        assertFalse(activityFreshness(far, baseline, "c1", 451_000).live)
        assertFalse(activityFreshness(view, baseline, "c2", 5000).live)
        assertFalse(activityFreshness(view, baseline.copy(clockId = "clk-2"), "c1", 5000).live)
        assertFalse(activityFreshness(view, null, "c1", 5000).live)
        val idle = applyActivityEvent(view, "c1", ActivitySnapshot.Idle("clk", "gen-1", 3)).view!!
        assertFalse(activityFreshness(idle, baseline, "c1", 5000).live)
        val unknown = applyActivityEvent(view, "c1", ActivitySnapshot.Unknown("clk", "gen-1", 3)).view!!
        assertFalse(activityFreshness(unknown, baseline, "c1", 5000).live)
    }

    @Test fun lateArrivalNeverExtendsTtl() {
        val request = beginActivityRequest("c1", 1, 0)
        val baseline = observeActivityClock(null, request, "c1", ActivityClock("clk", 0))!!
        var view = applyActivityBaseline(null, request, "c1", active(1, 1000)).view!!
        // Emitted at host 0 with deadline 10_000 but delivered at client 20_000.
        view = applyActivityEvent(view, "c1", active(2, 10_000)).view!!
        assertFalse(activityFreshness(view, baseline, "c1", 20_000).live)
        // A repeated seq with a later deadline renews nothing.
        val again = applyActivityEvent(view, "c1", active(2, 90_000))
        assertEquals(ActivityApplyOutcome.Ignored, again.outcome)
        assertFalse(activityFreshness(again.view, baseline, "c1", 20_000).live)
    }

    @Test fun parallelToolsKeepExactIdsAndUnknownThinkingDiffersFromOff() {
        val tools = """[{"toolCallId":"a","name":"read"},{"toolCallId":"b","name":"read"}]"""
        val s = snap(activeJson(phase = "tool", tools = tools)) as ActivitySnapshot.Active
        assertEquals(listOf("a", "b"), s.tools.map { it.toolCallId })
        assertNull(snap(activeJson(phase = "tool", tools = """[{"toolCallId":"a","name":"read"},{"toolCallId":"a","name":"read"}]""")))
        val unknown = snap(activeJson()) as ActivitySnapshot.Active
        val off = snap(activeJson(extra = ""","thinkingLevel":"off"""")) as ActivitySnapshot.Active
        assertNull(unknown.thinkingLevel)
        assertEquals("off", off.thinkingLevel)
        assertFalse(unknown == off)
        assertTrue(fixture.getValue("wireValid") is JsonArray)
    }
}
