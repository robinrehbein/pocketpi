package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Subagent actors of `session_activity.v1`: the `executionActivity` of a `subagentProgress` agent.
 * The host specifies no receiver helper for them, so this is a conservative receiver:
 * - an actor is named only by its exact parent session, `spawnToolCallId` and `instanceId`, and
 *   only an agent whose `spawnToolCallId` equals its own message's `toolCallId` counts;
 * - a new actor or epoch comes only from a first-page snapshot (an authoritative request token);
 *   live or replayed upserts advance it only on the same epoch and clock with a greater seq;
 * - only a `running` agent animates; `queued` and terminal ones never do and are dropped;
 * - `needsInput` is this agent's own flag, never another actor's.
 */

/** At most this many actors are kept, all of the selected parent session. */
const val MAX_ACTIVITY_ACTORS = 64

data class ActivityActorKey(val sessionId: String, val spawnToolCallId: String, val instanceId: String)

/**
 * One actor: its own activity view (own clock and epoch), whether it itself awaits input, and
 * [observedUntil], the client monotonic time after which it never animates (see
 * [MAX_ACTIVITY_REMAINING_MS]); set when a new seq was accepted, never moved otherwise.
 */
data class ActivityActorView(val view: ActivityView, val needsInput: Boolean, val observedUntil: Long)

/** A running agent with structured activity found in a tool message. */
internal data class ActorActivityHint(val key: ActivityActorKey, val snapshot: ActivitySnapshot, val needsInput: Boolean)

/** What one tool message says about actors. */
internal data class ActorMessageHints(
    /** The message's `toolCallId`, or null when it is no subagent tool message. */
    val toolCallId: String?,
    /** Running agents with valid activity. */
    val running: List<ActorActivityHint>,
)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Actor hints of [message] in parent session [sessionId]. Agents without an exact
 * `spawnToolCallId` match, an `instanceId`, a valid `executionActivity` or the `running` state
 * yield nothing.
 */
internal fun actorHints(sessionId: String, message: JsonObject): ActorMessageHints {
    if (message.str("role") != "tool") return ActorMessageHints(null, emptyList())
    val toolCallId = message.str("toolCallId")?.takeIf(::validActivityId) ?: return ActorMessageHints(null, emptyList())
    val progress = message["subagentProgress"] as? JsonObject ?: return ActorMessageHints(toolCallId, emptyList())
    val agents = progress["agents"] as? JsonArray ?: return ActorMessageHints(toolCallId, emptyList())
    // The host lists at most eight; more is malformed, and nothing of it counts.
    if (agents.size > 8) return ActorMessageHints(toolCallId, emptyList())
    val running = agents.mapNotNull { entry ->
        val agent = entry as? JsonObject ?: return@mapNotNull null
        if (agent.str("spawnToolCallId") != toolCallId) return@mapNotNull null
        val instanceId = agent.str("instanceId")?.takeIf(::validActivityId) ?: return@mapNotNull null
        if (agent.str("state") != "running") return@mapNotNull null
        val snapshot = parseActivitySnapshot(agent["executionActivity"]) ?: return@mapNotNull null
        // Only `true` is valid; any other value makes the entry malformed.
        val needsInput = when (val flag = agent["needsInput"]) {
            null -> false
            is JsonPrimitive -> if (!flag.isString && flag.content == "true") true else return@mapNotNull null
            else -> return@mapNotNull null
        }
        ActorActivityHint(ActivityActorKey(sessionId, toolCallId, instanceId), snapshot, needsInput)
    }
    // A key listed twice is ambiguous: neither entry counts.
    val counts = running.groupingBy { it.key }.eachCount()
    return ActorMessageHints(toolCallId, running.filter { counts[it.key] == 1 })
}

/** Keeps the newest [MAX_ACTIVITY_ACTORS] entries, in insertion order. */
internal fun boundActors(actors: Map<ActivityActorKey, ActivityActorView>): Map<ActivityActorKey, ActivityActorView> =
    if (actors.size <= MAX_ACTIVITY_ACTORS) actors else actors.entries.toList().takeLast(MAX_ACTIVITY_ACTORS).associate { it.key to it.value }

/**
 * Actors after the first page of [sessionId] answered [request]: exactly the running ones it
 * lists, each through [applyActivityBaseline]. Actors of other sessions, and ones the page no
 * longer lists as running, go, so no historical or finished card animates again.
 */
internal fun baselineActors(
    actors: Map<ActivityActorKey, ActivityActorView>,
    sessionId: String,
    messages: List<JsonObject>,
    request: ActivityRequestToken,
    currentConnectionId: String,
    now: Long,
    clock: ActivityClockBaseline? = null,
): Map<ActivityActorKey, ActivityActorView> {
    if (request.connectionId != currentConnectionId) return actors
    val next = LinkedHashMap<ActivityActorKey, ActivityActorView>()
    for (message in messages) for (hint in actorHints(sessionId, message).running) {
        val previous = actors[hint.key]
        val applied = applyActivityBaseline(previous?.view, request, currentConnectionId, hint.snapshot)
        val view = applied.view ?: continue
        val accepted = applied.outcome == ActivityApplyOutcome.Applied || previous == null
        next.remove(hint.key)
        next[hint.key] =
            if (accepted) ActivityActorView(view, hint.needsInput, activityObservationDeadline(view.snapshot, clock, now))
            // The page is not behind any live actor change (the caller checks), so its own
            // `needsInput` is current even at the same activity seq; view and deadline stay.
            else previous!!.copy(view = view, needsInput = hint.needsInput)
    }
    return boundActors(next)
}

/** The outcome of a live or replayed tool message for actors. */
internal data class ActorEventApply(
    val actors: Map<ActivityActorKey, ActivityActorView>,
    /** Actors (at most eight per message) whose clock or epoch needs a fresh snapshot. */
    val needsSnapshot: List<ActorActivityHint>,
)

/**
 * Applies a live or replayed `message.upsert` of [sessionId]. A known running actor advances on
 * the same epoch and clock with a greater seq; a new actor, epoch or clock asks for a snapshot. An
 * actor of this message that is no longer listed as running goes.
 */
internal fun applyActorMessage(
    actors: Map<ActivityActorKey, ActivityActorView>,
    sessionId: String,
    message: JsonObject,
    currentConnectionId: String,
    now: Long,
    clock: ActivityClockBaseline? = null,
    /**
     * Whether this message is a newer journal observation of the actor than the last one applied.
     * Only a newer source may change, end or introduce the actor; `needsInput` changes without a
     * new activity seq.
     */
    sourceNewer: (ActivityActorKey) -> Boolean = { false },
): ActorEventApply {
    val hints = actorHints(sessionId, message)
    val toolCallId = hints.toolCallId ?: return ActorEventApply(actors, emptyList())
    val listed = hints.running.map { it.key }.toSet()
    // An actor of this card no longer listed as running goes, but only on a newer journal
    // observation of that exact actor: an older message cannot end it.
    var next = actors.filterKeys {
        it.sessionId != sessionId || it.spawnToolCallId != toolCallId || it in listed || !sourceNewer(it)
    }
    val needsSnapshot = mutableListOf<ActorActivityHint>()
    for (hint in hints.running) {
        // An older observation changes nothing for this actor: no update, no revival, no request.
        if (!sourceNewer(hint.key)) continue
        val previous = next[hint.key]
        val applied = applyActivityEvent(previous?.view, currentConnectionId, hint.snapshot)
        when (applied.outcome) {
            ActivityApplyOutcome.Applied -> next = next + (hint.key to ActivityActorView(applied.view!!, hint.needsInput, activityObservationDeadline(hint.snapshot, clock, now)))
            ActivityApplyOutcome.NeedsSnapshot -> {
                needsSnapshot += hint
                // A mismatched epoch or clock: the known actor stops animating until a snapshot.
                if (previous != null) next = next + (hint.key to previous.copy(view = previous.view.unknown()))
            }
            ActivityApplyOutcome.Ignored ->
                // The same seq on the same epoch and clock: the view and its deadline stay; only its
                // own flag follows. A lower seq changes nothing.
                if (previous != null && previous.needsInput != hint.needsInput &&
                    hint.snapshot.seq == previous.view.snapshot.seq &&
                    hint.snapshot.epoch == previous.view.snapshot.epoch && hint.snapshot.clockId == previous.view.snapshot.clockId)
                    next = next + (hint.key to previous.copy(needsInput = hint.needsInput))
        }
    }
    return ActorEventApply(next, needsSnapshot)
}

/** [this] frozen as `unknown` on its own clock, epoch and seq: no animation, and a baseline still applies. */
internal fun ActivityView.unknown(): ActivityView =
    if (snapshot is ActivitySnapshot.Unknown) this
    else copy(snapshot = ActivitySnapshot.Unknown(snapshot.clockId, snapshot.epoch, snapshot.seq))

/**
 * The local observation deadline pinned when a new seq is accepted at client monotonic [acceptedAt]:
 * the host deadline mapped through the clock offset known then, capped at
 * [MAX_ACTIVITY_REMAINING_MS]. A later, better offset never moves it.
 */
fun activityObservationDeadline(snapshot: ActivitySnapshot, clock: ActivityClockBaseline?, acceptedAt: Long): Long {
    val cap = acceptedAt + MAX_ACTIVITY_REMAINING_MS
    if (snapshot !is ActivitySnapshot.Active || clock == null || clock.clockId != snapshot.clockId) return cap
    val mapped = snapshot.expiresAt + clock.offset
    return if (mapped < cap) mapped else cap
}

/** Every actor key [message] names with an exact `spawnToolCallId` match, in any state. */
internal fun actorKeysNamed(sessionId: String, message: JsonObject): Set<ActivityActorKey> {
    if ((message["role"] as? JsonPrimitive)?.content != "tool") return emptySet()
    val toolCallId = (message["toolCallId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptySet()
    val agents = (message["subagentProgress"] as? JsonObject)?.get("agents") as? JsonArray ?: return emptySet()
    return agents.mapNotNull { entry ->
        val agent = entry as? JsonObject ?: return@mapNotNull null
        if (agent.str("spawnToolCallId") != toolCallId) return@mapNotNull null
        val instanceId = (agent["instanceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::validActivityId) ?: return@mapNotNull null
        ActivityActorKey(sessionId, toolCallId, instanceId)
    }.toSet()
}
