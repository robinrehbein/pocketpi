package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

enum class ContinuationKind { WAKEUP, LOOP, GOAL }
/** CONTINUING means the prompt was handed off, not that the agent is running. */
enum class ContinuationPhase { AFTER_TURN, SCHEDULED, CONTINUING, HOLDING, PAUSED }
enum class ContinuationRestartPolicy { VOLATILE, RESTORE }
enum class ContinuationFreshness { UNKNOWN, LIVE, STALE, UNAVAILABLE }

data class SessionContinuation(
    val id: String,
    val kind: ContinuationKind,
    val phase: ContinuationPhase,
    val restartPolicy: ContinuationRestartPolicy,
    val purpose: String? = null,
    val dueAt: Long? = null,
    val delayMs: Long? = null,
)

data class SessionContinuations(
    val owner: String? = null,
    val items: List<SessionContinuation> = emptyList(),
    val freshness: ContinuationFreshness = ContinuationFreshness.UNKNOWN,
)

internal const val SESSION_CONTINUATIONS_CAPABILITY = "session.continuations.v1"

internal fun staleContinuations(state: SessionContinuations): SessionContinuations =
    if (state.freshness == ContinuationFreshness.LIVE) state.copy(freshness = ContinuationFreshness.STALE) else state

/** A resume replay showed that the known continuations have not changed. */
internal fun liveContinuations(state: SessionContinuations): SessionContinuations =
    if (state.freshness == ContinuationFreshness.STALE) state.copy(freshness = ContinuationFreshness.LIVE) else state

internal fun parseSessionContinuations(value: JsonElement?): SessionContinuations {
    val unavailable = SessionContinuations(freshness = ContinuationFreshness.UNAVAILABLE)
    val snapshot = value as? JsonObject ?: return unavailable
    if (snapshot.keys.any { it !in setOf("owner", "items") } ||
        snapshot.toString().encodeToByteArray().size > 16384
    ) return unavailable
    fun text(objectValue: JsonObject, key: String, maxBytes: Int): String? =
        (objectValue[key] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() && it.content.encodeToByteArray().size <= maxBytes }
            ?.content
    val owner = text(snapshot, "owner", 128) ?: return unavailable
    val array = snapshot["items"] as? JsonArray ?: return unavailable
    if (array.size > 8) return unavailable
    val ids = mutableSetOf<String>()
    val items = array.map { element ->
        val item = element as? JsonObject ?: return unavailable
        if (item.keys.any { it !in setOf("id", "kind", "phase", "restartPolicy", "purpose", "dueAt", "delayMs") }) return unavailable
        val id = text(item, "id", 128) ?: return unavailable
        if (!ids.add(id)) return unavailable
        val kind = when (text(item, "kind", 128)) {
            "wakeup" -> ContinuationKind.WAKEUP
            "loop" -> ContinuationKind.LOOP
            "goal" -> ContinuationKind.GOAL
            else -> return unavailable
        }
        val phase = when (text(item, "phase", 128)) {
            "after_turn" -> ContinuationPhase.AFTER_TURN
            "scheduled" -> ContinuationPhase.SCHEDULED
            "continuing" -> ContinuationPhase.CONTINUING
            "holding" -> ContinuationPhase.HOLDING
            "paused" -> ContinuationPhase.PAUSED
            else -> return unavailable
        }
        val restartPolicy = when (text(item, "restartPolicy", 128)) {
            "volatile" -> ContinuationRestartPolicy.VOLATILE
            "restore" -> ContinuationRestartPolicy.RESTORE
            else -> return unavailable
        }
        if ((kind == ContinuationKind.GOAL) != (restartPolicy == ContinuationRestartPolicy.RESTORE)) return unavailable
        if (phase in setOf(ContinuationPhase.HOLDING, ContinuationPhase.PAUSED) && kind != ContinuationKind.GOAL) return unavailable
        val purpose = if ("purpose" in item) text(item, "purpose", 512) ?: return unavailable else null
        fun number(key: String, maximum: Long): Long? = (item[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 && it <= maximum.toDouble() && it % 1.0 == 0.0 }
            ?.toLong()
        val dueAt = if ("dueAt" in item) number("dueAt", 9007199254740991L) ?: return unavailable else null
        val delayMs = if ("delayMs" in item) number("delayMs", 86400000L) ?: return unavailable else null
        if (phase == ContinuationPhase.SCHEDULED && dueAt == null) return unavailable
        if (dueAt != null && phase !in setOf(ContinuationPhase.SCHEDULED, ContinuationPhase.CONTINUING)) return unavailable
        if ((phase == ContinuationPhase.AFTER_TURN) != (delayMs != null)) return unavailable
        SessionContinuation(id, kind, phase, restartPolicy, purpose, dueAt, delayMs)
    }
    return SessionContinuations(owner, items, ContinuationFreshness.LIVE)
}
