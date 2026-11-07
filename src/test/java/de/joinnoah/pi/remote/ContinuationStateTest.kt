package de.joinnoah.pi.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ContinuationStateTest {
    private val unavailable = SessionContinuations(freshness = ContinuationFreshness.UNAVAILABLE)
    private fun parse(value: String) = parseSessionContinuations(Json.parseToJsonElement(value))
    private fun item(
        kind: String = "wakeup",
        phase: String = "continuing",
        policy: String = if (kind == "goal") "restore" else "volatile",
        extra: String = "",
    ) = """{"id":"one","kind":"$kind","phase":"$phase","restartPolicy":"$policy"$extra}"""
    private fun snapshot(items: String = item(), owner: String = "epoch") =
        """{"owner":"$owner","items":[$items]}"""
    private fun reject(value: String) = assertEquals(value, unavailable, parse(value))

    @Test fun validStatesAndEmpty() {
        assertEquals("session.continuations.v1", SESSION_CONTINUATIONS_CAPABILITY)
        assertEquals(SessionContinuations("epoch", freshness = ContinuationFreshness.LIVE), parse(snapshot("")))
        for (kind in ContinuationKind.entries) {
            for (phase in ContinuationPhase.entries) {
                if (phase in listOf(ContinuationPhase.HOLDING, ContinuationPhase.PAUSED) && kind != ContinuationKind.GOAL) continue
                val extra = when (phase) {
                    ContinuationPhase.AFTER_TURN -> ",\"delayMs\":0"
                    ContinuationPhase.SCHEDULED -> ",\"dueAt\":0"
                    else -> ""
                }
                val parsed = parse(snapshot(item(kind.name.lowercase(), phase.name.lowercase(), extra = extra)))
                assertEquals(ContinuationFreshness.LIVE, parsed.freshness)
                assertEquals(SessionContinuation("one", kind, phase,
                    if (kind == ContinuationKind.GOAL) ContinuationRestartPolicy.RESTORE else ContinuationRestartPolicy.VOLATILE,
                    dueAt = if (phase == ContinuationPhase.SCHEDULED) 0 else null,
                    delayMs = if (phase == ContinuationPhase.AFTER_TURN) 0 else null), parsed.items.single())
            }
        }
        val dispatched = parse(snapshot(item(extra = ",\"dueAt\":9007199254740991,\"purpose\":\"Next check\""))).items.single()
        assertEquals(9007199254740991L, dispatched.dueAt)
        assertEquals("Next check", dispatched.purpose)
        assertEquals(86400000L, parse(snapshot(item(phase = "after_turn", extra = ",\"delayMs\":86400000"))).items.single().delayMs)
    }

    @Test fun upstreamSnapshotExamplesRemainCompatible() {
        val fixture = checkNotNull(javaClass.getResource("/continuations/session-continuations-v1.json")).readText()
        val examples = Json.parseToJsonElement(fixture).jsonObject["snapshots"]!!.jsonArray
        assertEquals(4, examples.size)
        val snapshots = examples.map { parseSessionContinuations(it.jsonObject["continuations"]) }
        assertTrue(snapshots.all { it.freshness == ContinuationFreshness.LIVE })
        assertEquals(listOf(1, 1, 2, 0), snapshots.map { it.items.size })
        assertEquals(ContinuationPhase.AFTER_TURN, snapshots[0].items.single().phase)
        assertEquals(ContinuationPhase.SCHEDULED, snapshots[1].items.single().phase)
        assertEquals(ContinuationPhase.HOLDING, snapshots[2].items.last().phase)
    }

    @Test fun malformedObjectsAndRequiredFields() {
        assertEquals(unavailable, parseSessionContinuations(null))
        for (value in listOf("null", "[]", "1", "true", "\"x\"", "{}",
            """{"owner":"epoch"}""", """{"items":[]}""",
            """{"owner":"epoch","items":null}""", """{"owner":"epoch","items":{}}""",
            snapshot("null"), snapshot("[]"), snapshot("{}"), snapshot("1"),
            snapshot().dropLast(1) + ",\"unknown\":true}")) reject(value)
        val base = Json.parseToJsonElement(item()).jsonObject
        for (key in base.keys) {
            reject(snapshot(JsonObject(base - key).toString()))
            val invalid = listOf("null", "1", "true", "[]", "{}", "\"\"") +
                if (key == "id") emptyList() else listOf("\"unknown\"")
            for (bad in invalid) {
                reject(snapshot(JsonObject(base + (key to Json.parseToJsonElement(bad))).toString()))
            }
        }
        for (bad in listOf("null", "1", "true", "[]", "{}", "\"\"")) {
            reject("""{"owner":$bad,"items":[]}""")
        }
        reject(snapshot(item(extra = ",\"unknown\":null")))
        for (bad in listOf("null", "1", "true", "[]", "{}", "\"\"")) reject(snapshot(item(extra = ",\"purpose\":$bad")))
    }

    @Test fun fieldConsistency() {
        reject(snapshot(item(phase = "scheduled")))
        reject(snapshot(item(phase = "after_turn")))
        for (kind in listOf("wakeup", "loop", "goal")) {
            reject(snapshot(item(kind, policy = if (kind == "goal") "volatile" else "restore")))
            for (phase in listOf("after_turn", "scheduled", "continuing", "holding", "paused")) {
                if (phase != "after_turn") reject(snapshot(item(kind, phase, extra = ",\"delayMs\":0,\"dueAt\":0")))
                if (phase !in listOf("scheduled", "continuing")) reject(snapshot(item(kind, phase, extra = ",\"dueAt\":0,\"delayMs\":0")))
                if (kind != "goal" && phase in listOf("holding", "paused")) reject(snapshot(item(kind, phase)))
            }
        }
    }

    @Test fun numericTypesAndBounds() {
        for (key in listOf("dueAt", "delayMs")) {
            val phase = if (key == "dueAt") "scheduled" else "after_turn"
            val invalid = listOf("null", "true", "false", "[]", "{}", "\"0\"", "\"1\"", "-1", "0.5",
                "9007199254740992", "9223372036854775808", "1e100") +
                if (key == "delayMs") listOf("86400001", "9007199254740991") else emptyList()
            for (number in invalid) reject(snapshot(item(phase = phase, extra = ",\"$key\":$number")))
            // JSON numbers are validated by value, like Number.isSafeInteger on the host.
            for (number in listOf("1.0", "1e3")) {
                val parsed = parse(snapshot(item(phase = phase, extra = ",\"$key\":$number")))
                assertEquals(ContinuationFreshness.LIVE, parsed.freshness)
                assertEquals(if (number == "1.0") 1L else 1000L,
                    if (key == "dueAt") parsed.items.single().dueAt else parsed.items.single().delayMs)
            }
        }
    }

    @Test fun utf8BoundsAndOpaqueIdentifiers() {
        val base = Json.parseToJsonElement(item()).jsonObject
        for ((key, maximum) in listOf("id" to 128, "purpose" to 512)) {
            for (text in listOf("x".repeat(maximum), "é".repeat(maximum / 2))) {
                assertEquals(ContinuationFreshness.LIVE, parse(snapshot(JsonObject(base + (key to JsonPrimitive(text))).toString())).freshness)
            }
            for (text in listOf("x".repeat(maximum + 1), "é".repeat(maximum / 2) + "x")) {
                reject(snapshot(JsonObject(base + (key to JsonPrimitive(text))).toString()))
            }
        }
        for (owner in listOf("x".repeat(128), "é".repeat(64), " ")) assertEquals(ContinuationFreshness.LIVE, parse(snapshot(owner = owner)).freshness)
        for (owner in listOf("x".repeat(129), "é".repeat(64) + "x")) reject(snapshot(owner = owner))
        assertEquals(ContinuationFreshness.LIVE, parse(snapshot(JsonObject(base + ("id" to JsonPrimitive(" "))).toString())).freshness)
    }

    @Test fun duplicateIdsCountAndObjectByteLimit() {
        reject(snapshot(listOf(item(), item()).joinToString(",")))
        val base = Json.parseToJsonElement(item()).jsonObject
        fun items(count: Int, purpose: String = "x") = (1..count).joinToString(",") {
            JsonObject(base + mapOf("id" to JsonPrimitive("id-$it"), "purpose" to JsonPrimitive(purpose))).toString()
        }
        assertEquals(8, parse(snapshot(items(8))).items.size)
        reject(snapshot(items(9)))
        // Escaping counts toward the serialized object budget even when decoded text fits.
        val oversized = snapshot(items(8, "\u0001".repeat(512)))
        assertTrue(oversized.encodeToByteArray().size > 16384)
        reject(oversized)
        assertEquals(ContinuationFreshness.LIVE, parse(snapshot(items(8, "x".repeat(512)))).freshness)
        val objectValue = (1..512).map {
            Json.parseToJsonElement(snapshot(items(8, "\u0001".repeat(it)))).jsonObject
        }.last { it.toString().encodeToByteArray().size <= 16384 }
        val budget = 16384 - objectValue.toString().encodeToByteArray().size
        // An opaque owner fills the remaining budget without changing item limits.
        val exact = JsonObject(objectValue + ("owner" to JsonPrimitive("epoch" + "x".repeat(budget))))
        assertEquals(16384, exact.toString().encodeToByteArray().size)
        assertEquals(ContinuationFreshness.LIVE, parseSessionContinuations(exact).freshness)
        val over = JsonObject(exact + ("owner" to JsonPrimitive("epoch" + "x".repeat(budget + 1))))
        assertEquals(unavailable, parseSessionContinuations(over))
    }

    @Test fun freshnessTransitionsPreserveDataAndOnlyChangeExpectedState() {
        val live = parse(snapshot())
        for (freshness in ContinuationFreshness.entries) {
            val state = live.copy(freshness = freshness)
            assertEquals(state.copy(freshness = if (freshness == ContinuationFreshness.LIVE) ContinuationFreshness.STALE else freshness), staleContinuations(state))
            assertEquals(state.copy(freshness = if (freshness == ContinuationFreshness.STALE) ContinuationFreshness.LIVE else freshness), liveContinuations(state))
        }
        assertEquals(SessionContinuations(), staleContinuations(SessionContinuations()))
        assertEquals(live, liveContinuations(staleContinuations(live)))
    }
}
