package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Structured session activity (`session_activity.v1`), receiver side only. A port of
 * noah `packages/pi-atlas/extensions/remote/protocol/activity.ts` (validator and receiver helpers)
 * plus the activity parts of `parse.ts`. Pure: no timers, no I/O; every clock reading is passed in
 * and must come from a monotonic source (SystemClock.elapsedRealtime), never wall time.
 */

const val SESSION_ACTIVITY_CAPABILITY = "session_activity.v1"
const val MAX_ACTIVITY_TOOLS = 128
const val MAX_ACTIVITY_TOOLS_OMITTED = 1_000_000
const val MAX_ACTIVITY_TOOL_NAME_BYTES = 64
const val MAX_ACTIVITY_ID_BYTES = 256

/** A receiver never animates longer than this past a freshness check. */
const val MAX_ACTIVITY_REMAINING_MS = 30_000L

/** The host's `THINKING_LEVELS`; an absent level means unknown, not `off`. */
val ACTIVITY_THINKING_LEVELS: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

enum class ActivityPhase(val wire: String) { Pending("pending"), Thinking("thinking"), Writing("writing"), Tool("tool") }

data class ActivityTool(val toolCallId: String, val name: String, val parentToolCallId: String? = null)

/** Full replacement state. `Idle` is a sequenced clear; `Unknown` never means finished. */
sealed interface ActivitySnapshot {
    val clockId: String
    val epoch: String
    val seq: Long

    data class Active(
        override val clockId: String,
        override val epoch: String,
        override val seq: Long,
        /** Absolute deadline on the host's monotonic clock [clockId], in milliseconds. */
        val expiresAt: Long,
        val phase: ActivityPhase,
        /** One of [ACTIVITY_THINKING_LEVELS]; null means unknown. */
        val thinkingLevel: String?,
        /** Wire order; parallel tools are distinguished only by exact [ActivityTool.toolCallId]. */
        val tools: List<ActivityTool>,
        /** Running tools not listed; null when none. */
        val toolsOmitted: Int?,
    ) : ActivitySnapshot

    data class Idle(override val clockId: String, override val epoch: String, override val seq: Long) : ActivitySnapshot

    data class Unknown(override val clockId: String, override val epoch: String, override val seq: Long) : ActivitySnapshot
}

/** Host monotonic sample taken while it processed an authoritative request. */
data class ActivityClock(val clockId: String, val sampledAt: Long)

// ---- Validation -------------------------------------------------------------------------------

/** UTF-8 length as JS `TextEncoder` counts it: a lone surrogate becomes U+FFFD (3 bytes). */
internal fun activityUtf8Length(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.code < 0x80 -> n += 1
            c.code < 0x800 -> n += 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                n += 4
                i++
            }
            else -> n += 3
        }
        i++
    }
    return n
}

private fun boundedText(v: JsonElement?, max: Int): String? {
    if (v !is JsonPrimitive || v is JsonNull || !v.isString) return null
    val s = v.content
    return if (s.isNotEmpty() && activityUtf8Length(s) <= max) s else null
}

/** 1..256 UTF-8 bytes, no U+0000..U+0020 or U+007F. Non-ASCII is allowed. */
internal fun validActivityId(s: String?): Boolean =
    s != null && s.isNotEmpty() && activityUtf8Length(s) <= MAX_ACTIVITY_ID_BYTES &&
        s.none { it.code <= 0x20 || it.code == 0x7f }

private fun activityId(v: JsonElement?): String? =
    boundedText(v, MAX_ACTIVITY_ID_BYTES)?.takeIf { validActivityId(it) }

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** JS `Number.isSafeInteger(v) && min <= v <= max`, with JS double rounding of the literal. */
internal fun activitySafeInt(v: JsonElement?, min: Long = 0, max: Long = MAX_SAFE_INTEGER): Long? {
    if (v !is JsonPrimitive || v is JsonNull || v.isString) return null
    if (!jsonNumber.matches(v.content)) return null
    val d = v.content.toDoubleOrNull() ?: return null
    if (!d.isFinite() || d != Math.floor(d) || Math.abs(d) > MAX_SAFE_INTEGER.toDouble()) return null
    val n = d.toLong() // -0.0 becomes 0
    return if (n in min..max) n else null
}

private fun JsonElement?.plain(required: Set<String>, optional: Set<String> = emptySet()): JsonObject? {
    if (this !is JsonObject) return null
    if (!keys.containsAll(required)) return null
    return if (keys.all { it in required || it in optional }) this else null
}

private fun parseTool(v: JsonElement): ActivityTool? {
    val o = v.plain(setOf("toolCallId", "name"), setOf("parentToolCallId")) ?: return null
    val id = activityId(o["toolCallId"]) ?: return null
    val name = boundedText(o["name"], MAX_ACTIVITY_TOOL_NAME_BYTES) ?: return null
    if (name.any { it.code <= 0x1f || it.code == 0x7f }) return null
    if (!o.containsKey("parentToolCallId")) return ActivityTool(id, name)
    val parent = activityId(o["parentToolCallId"]) ?: return null
    return if (parent != id) ActivityTool(id, name, parent) else null
}

/** Mirrors `validActivityClock`; null when invalid. */
fun parseActivityClock(v: JsonElement?): ActivityClock? {
    val o = v.plain(setOf("clockId", "sampledAt")) ?: return null
    val clockId = activityId(o["clockId"]) ?: return null
    val sampledAt = activitySafeInt(o["sampledAt"]) ?: return null
    return ActivityClock(clockId, sampledAt)
}

private val COMMON_KEYS = setOf("clockId", "epoch", "seq", "state")

/** Mirrors `validActivitySnapshot`; null when invalid. */
fun parseActivitySnapshot(v: JsonElement?): ActivitySnapshot? {
    val o = v.plain(COMMON_KEYS, setOf("expiresAt", "phase", "thinkingLevel", "tools", "toolsOmitted")) ?: return null
    val clockId = activityId(o["clockId"]) ?: return null
    val epoch = activityId(o["epoch"]) ?: return null
    val seq = activitySafeInt(o["seq"]) ?: return null
    val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (state == "idle" || state == "unknown") {
        if (o.plain(COMMON_KEYS) == null) return null
        return if (state == "idle") ActivitySnapshot.Idle(clockId, epoch, seq) else ActivitySnapshot.Unknown(clockId, epoch, seq)
    }
    if (state != "active") return null
    o.plain(COMMON_KEYS + setOf("expiresAt", "phase", "tools"), setOf("thinkingLevel", "toolsOmitted")) ?: return null
    val expiresAt = activitySafeInt(o["expiresAt"]) ?: return null
    val phaseWire = (o["phase"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val phase = ActivityPhase.entries.firstOrNull { it.wire == phaseWire } ?: return null
    var thinkingLevel: String? = null
    if (o.containsKey("thinkingLevel")) {
        thinkingLevel = (o["thinkingLevel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it in ACTIVITY_THINKING_LEVELS } ?: return null
    }
    val rawTools = o["tools"] as? JsonArray ?: return null
    if (rawTools.size > MAX_ACTIVITY_TOOLS) return null
    val tools = rawTools.map { parseTool(it) ?: return null }
    if (tools.map { it.toolCallId }.toSet().size != tools.size) return null
    val omittedPresent = o.containsKey("toolsOmitted")
    val omitted = if (omittedPresent) {
        activitySafeInt(o["toolsOmitted"], 1, MAX_ACTIVITY_TOOLS_OMITTED.toLong())?.toInt() ?: return null
    } else {
        null
    }
    val ok = if (phase == ActivityPhase.Tool) tools.isNotEmpty() || omittedPresent else tools.isEmpty() && !omittedPresent
    if (!ok) return null
    return ActivitySnapshot.Active(clockId, epoch, seq, expiresAt, phase, thinkingLevel, tools, omitted)
}

// ---- Containers (activity parts of parse.ts only) ---------------------------------------------

/**
 * Activity parts of an authoritative result `data`. [activities] maps session ID to its baseline
 * snapshot (`sessions` items, or the `snapshot` session); `pong` and `session.events` only carry
 * a clock. Replayed `session.events` activities are live events, never baselines.
 */
data class ActivityAnswer(val clock: ActivityClock?, val activities: Map<String, ActivitySnapshot>)

/**
 * Validates `activityClock` and `activity` fields of a `sessions`, `snapshot`, `pong` or
 * `session.events` result `data` like the host parser: a present clock must be valid, and any
 * activity needs a clock beside it naming the same clock. Returns null when they are invalid or
 * the kind carries no activity. Other fields stay the caller's existing validation.
 */
fun parseActivityAnswer(data: JsonObject): ActivityAnswer? {
    val kind = (data["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (kind !in setOf("sessions", "snapshot", "pong", "session.events")) return null
    val clock = if (data.containsKey("activityClock")) parseActivityClock(data["activityClock"]) ?: return null else null
    val activities = linkedMapOf<String, ActivitySnapshot>()
    when (kind) {
        "sessions" -> {
            val items = data["items"] as? JsonArray ?: return null
            for (item in items) {
                val o = item as? JsonObject ?: return null
                if (!o.containsKey("activity")) continue
                val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                activities[id] = parseActivitySnapshot(o["activity"]) ?: return null
            }
        }
        "snapshot" -> if (data.containsKey("activity")) {
            val id = (data["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            activities[id] = parseActivitySnapshot(data["activity"]) ?: return null
        }
    }
    if (activities.isNotEmpty() && (clock == null || activities.values.any { it.clockId != clock.clockId })) return null
    return ActivityAnswer(clock, activities)
}

/** A validated `session.activity` event. */
data class SessionActivityEvent(val sessionId: String, val revision: Long, val after: Long?, val activity: ActivitySnapshot)

/**
 * Validates a `session.activity` event: exact key set, safe-integer `revision`, optional `after`
 * at most `revision - 1`, and a valid snapshot. Session ID grammar stays the caller's check.
 */
fun parseSessionActivityEvent(payload: JsonObject): SessionActivityEvent? {
    val o = payload.plain(setOf("type", "sessionId", "revision", "kind", "activity"), setOf("after")) ?: return null
    if ((o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "event") return null
    if ((o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "session.activity") return null
    val sessionId = (o["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val revision = activitySafeInt(o["revision"]) ?: return null
    val after = if (o.containsKey("after")) activitySafeInt(o["after"], 0, revision - 1) ?: return null else null
    val activity = parseActivitySnapshot(o["activity"]) ?: return null
    return SessionActivityEvent(sessionId, revision, after, activity)
}

// ---- Receiver ---------------------------------------------------------------------------------

/**
 * An authoritative request (first-page snapshot, session list or pong) about to be sent.
 * [requestStart] is the client's monotonic clock read before sending; [requestSeq] grows per
 * request on one connection.
 */
data class ActivityRequestToken(val connectionId: String, val requestSeq: Long, val requestStart: Long)

/** Per connection: the conservative host-to-client offset (`requestStart - sampledAt`, max seen). */
data class ActivityClockBaseline(val connectionId: String, val clockId: String, val offset: Long)

/** Per session: the last applied snapshot and the connection that confirmed it. */
data class ActivityView(val connectionId: String, val baselineRequestSeq: Long, val snapshot: ActivitySnapshot)

enum class ActivityApplyOutcome { Applied, Ignored, NeedsSnapshot }

data class ActivityApply(val outcome: ActivityApplyOutcome, val view: ActivityView?)

data class ActivityFreshness(val live: Boolean, val remainingMs: Long)

fun beginActivityRequest(connectionId: String, requestSeq: Long, now: Long): ActivityRequestToken =
    ActivityRequestToken(connectionId, requestSeq, now)

/**
 * Folds a host clock sample into the connection baseline. A token from another connection changes
 * nothing; a new connection or clock starts over; the same clock keeps the larger offset.
 */
fun observeActivityClock(
    baseline: ActivityClockBaseline?,
    request: ActivityRequestToken,
    currentConnectionId: String,
    clock: ActivityClock,
): ActivityClockBaseline? {
    if (request.connectionId != currentConnectionId) return baseline
    val offset = request.requestStart - clock.sampledAt
    if (baseline == null || baseline.connectionId != currentConnectionId || baseline.clockId != clock.clockId) {
        return ActivityClockBaseline(currentConnectionId, clock.clockId, offset)
    }
    return if (offset > baseline.offset) baseline.copy(offset = offset) else baseline
}

/**
 * Applies the activity of an authoritative snapshot or list answer to [request]. Only this path
 * may introduce a new epoch or clock. A token of another connection, or older than the view's
 * baseline, is ignored; so is a same-epoch snapshot that is not newer than the view.
 */
fun applyActivityBaseline(
    view: ActivityView?,
    request: ActivityRequestToken,
    currentConnectionId: String,
    snapshot: ActivitySnapshot,
): ActivityApply {
    if (request.connectionId != currentConnectionId) return ActivityApply(ActivityApplyOutcome.Ignored, view)
    if (view != null && view.connectionId == currentConnectionId) {
        if (request.requestSeq < view.baselineRequestSeq) return ActivityApply(ActivityApplyOutcome.Ignored, view)
        val same = view.snapshot.epoch == snapshot.epoch && view.snapshot.clockId == snapshot.clockId
        if (same && snapshot.seq <= view.snapshot.seq) {
            return ActivityApply(ActivityApplyOutcome.Ignored, view.copy(baselineRequestSeq = request.requestSeq))
        }
    }
    return ActivityApply(ActivityApplyOutcome.Applied, ActivityView(currentConnectionId, request.requestSeq, snapshot))
}

/**
 * Applies a live `session.activity` event (also a replayed one). Without a baseline on this
 * connection, or for another epoch or clock, nothing changes and the caller should fetch a
 * snapshot. An older or duplicate seq is a full no-op, deadline included.
 */
fun applyActivityEvent(view: ActivityView?, currentConnectionId: String, snapshot: ActivitySnapshot): ActivityApply {
    if (view == null || view.connectionId != currentConnectionId) return ActivityApply(ActivityApplyOutcome.NeedsSnapshot, view)
    if (view.snapshot.epoch != snapshot.epoch || view.snapshot.clockId != snapshot.clockId) {
        return ActivityApply(ActivityApplyOutcome.NeedsSnapshot, view)
    }
    if (snapshot.seq <= view.snapshot.seq) return ActivityApply(ActivityApplyOutcome.Ignored, view)
    return ActivityApply(ActivityApplyOutcome.Applied, view.copy(snapshot = snapshot))
}

/**
 * Whether the client may animate [view] at client monotonic time [now]. The host deadline maps
 * through the conservative offset to an absolute local deadline; the 30 s cap only bounds the
 * returned remaining time and never moves that deadline. Expiry freezes the state, never idle.
 */
fun activityFreshness(
    view: ActivityView?,
    baseline: ActivityClockBaseline?,
    currentConnectionId: String,
    now: Long,
): ActivityFreshness {
    val stale = ActivityFreshness(false, 0)
    if (view == null || baseline == null) return stale
    if (view.connectionId != currentConnectionId || baseline.connectionId != currentConnectionId) return stale
    val s = view.snapshot
    if (s !is ActivitySnapshot.Active || s.clockId != baseline.clockId) return stale
    val remaining = s.expiresAt + baseline.offset - now
    if (remaining <= 0) return stale
    return ActivityFreshness(true, minOf(remaining, MAX_ACTIVITY_REMAINING_MS))
}
