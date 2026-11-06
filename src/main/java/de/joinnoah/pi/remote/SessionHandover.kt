package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Handover to a new chat (`session.handover`, protocol README "Handover to a new chat"): an idle host
 * worker continues in a new chat that starts from a summary of the old one. Advertised only on the
 * `capabilities.v3:` route of `projects.list`.
 */
internal const val SESSION_HANDOVER_CAPABILITY = "session.handover.v1"

/** The extension's `internal` reason in a chat without messages; other reasons get the generic error. */
internal const val HANDOVER_NOTHING_MESSAGE = "Nothing to hand over yet"

/** The prompt the "Continue" chip sends in a new chat; the model reads it, so it stays English. */
internal const val HANDOVER_CONTINUE_PROMPT = "Continue with the next steps from the summary."

/** Where a handover stands, as its `session.handover` events and the old chat's snapshot say. */
enum class HandoverState(val wire: String) {
    SUMMARIZING("summarizing"),
    SWITCHING("switching"),
    DONE("done"),
    FAILED("failed"),
}

/** Why a handover failed (`HANDOVER_ERRORS`). Each leaves the old chat usable. */
enum class HandoverError(val wire: String) {
    BUSY("busy"),
    CHANGED("changed"),
    TOO_LARGE("too_large"),
    SUMMARY_FAILED("summary_failed"),
    CANCELLED("cancelled"),
    OFFLINE("offline"),

    /** After `switching` the outcome is open: the session list shows whether the new chat exists. */
    TIMEOUT("timeout"),
}

/**
 * A handover of the open (old) chat. [live] is true when this phone saw it run: it sent the request
 * or a `session.handover` event arrived, not only the snapshot that keeps the outcome for ten minutes.
 * Only a live `done` moves the phone to the new chat on its own.
 */
data class SessionHandover(
    val requestId: String,
    val state: HandoverState,
    val targetSessionId: String? = null,
    val partial: Boolean = false,
    val error: HandoverError? = null,
    val live: Boolean = false,
) {
    val running: Boolean get() = state == HandoverState.SUMMARIZING || state == HandoverState.SWITCHING
}

/** The new chat's snapshot field `handedOverFrom`; [seeded] false means pi could not write the summary. */
data class HandedOverFrom(val sourceSessionId: String, val seeded: Boolean)

/** The `handover` field of the new chat's first message, a `user` message whose text is the summary. */
data class MessageHandover(val sourceSessionId: String, val partial: Boolean)

private val handoverEventKeys = setOf("type", "sessionId", "revision", "kind", "requestId", "state")
private val handoverOptionalKeys = setOf("targetSessionId", "partial", "error")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.takeIf { it.isNotEmpty() }

/**
 * The handover in [fields] (an event without its envelope, or the snapshot's `handover`): `done`
 * names its target and may say `partial: true`, `failed` names a known error, and neither field
 * appears with another state. Null when anything breaks those rules.
 */
private fun handover(fields: JsonObject, live: Boolean): SessionHandover? {
    val requestId = fields.string("requestId") ?: return null
    val state = HandoverState.entries.firstOrNull { it.wire == fields.string("state") } ?: return null
    val target = fields["targetSessionId"]
    val partial = fields["partial"]
    val error = fields["error"]
    return when (state) {
        HandoverState.DONE -> {
            if (error != null) return null
            val targetId = fields.string("targetSessionId") ?: return null
            // `partial` is present only as true.
            if (partial != null && (partial as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true) return null
            SessionHandover(requestId, state, targetId, partial = partial != null, live = live)
        }
        HandoverState.FAILED -> {
            if (target != null || partial != null) return null
            val code = HandoverError.entries.firstOrNull { it.wire == fields.string("error") } ?: return null
            SessionHandover(requestId, state, error = code, live = live)
        }
        else -> if (target != null || partial != null || error != null) null else SessionHandover(requestId, state, live = live)
    }
}

/** The handover a `session.handover` [event] reports, or null when its shape breaks the protocol. */
internal fun sessionHandoverEvent(event: JsonObject): SessionHandover? {
    val extra = event.keys - handoverEventKeys - "after"
    if (!handoverEventKeys.all { it in event } || !handoverOptionalKeys.containsAll(extra)) return null
    if (!hasEventShape(event, handoverEventKeys + extra)) return null
    if (event.string("sessionId") == null) return null
    return handover(event, live = true)
}

/** The old chat's snapshot field `handover`; absent or malformed is null. */
internal fun snapshotHandover(value: JsonElement?): SessionHandover? {
    val fields = value as? JsonObject ?: return null
    if (!(setOf("requestId", "state") + handoverOptionalKeys).containsAll(fields.keys)) return null
    return handover(fields, live = false)
}

/** The new chat's snapshot field `handedOverFrom`; absent or malformed (without `seeded`) is null. */
internal fun parseHandedOverFrom(value: JsonElement?): HandedOverFrom? {
    val fields = value as? JsonObject ?: return null
    if (fields.keys != setOf("sourceSessionId", "seeded")) return null
    val source = fields.string("sourceSessionId") ?: return null
    val seeded = (fields["seeded"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
    return HandedOverFrom(source, seeded)
}

/**
 * The summary mark of [message]: its `handover` field on a `user` message, with `sourceSessionId`
 * and at most `partial: true`. On any other message, or with another key, it is no summary and the
 * message shows as it is.
 */
internal fun messageHandover(message: JsonObject): MessageHandover? {
    val fields = message["handover"] as? JsonObject ?: return null
    if (message.string("role") != "user") return null
    if (!setOf("sourceSessionId", "partial").containsAll(fields.keys)) return null
    val source = fields.string("sourceSessionId") ?: return null
    val partial = fields["partial"]
    if (partial != null && (partial as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true) return null
    return MessageHandover(source, partial != null)
}

/** A handover of the open chat was requested or runs: the old chat takes no input meanwhile. */
internal fun handoverBusy(state: RemoteState): Boolean =
    state.handoverRequesting || state.handover?.running == true

/**
 * "New chat with summary" is offered: the host advertises [SESSION_HANDOVER_CAPABILITY], the open chat
 * is a host worker (a chat started from the phone, never a terminal or RPC session, nor a subagent
 * child) and the host did not answer `unsupported` for it ([ExtensionControl.HANDOVER]).
 */
internal fun handoverAvailable(state: RemoteState): Boolean {
    val session = state.session ?: return false
    return SESSION_HANDOVER_CAPABILITY in state.capabilities &&
        SESSION_HANDOVER_CAPABILITY !in state.unavailableCapabilities &&
        isHostWorker(session) && hostOwned(state, session) && !isChildSession(state) &&
        session.optionalText("id") == state.selection.sessionId &&
        ExtensionControl.HANDOVER !in state.sessionControlUnsupported[state.selection.sessionId].orEmpty()
}

/** The handover can start now: offered, and the chat is idle and can compact (nothing else runs). */
internal fun canHandover(state: RemoteState, nowMillis: Long): Boolean =
    handoverAvailable(state) && canCompact(state, nowMillis)

/**
 * The new chat shows the "Continue" chip: it came from a handover with a summary, holds only that
 * summary (nothing was sent yet, nothing waits to go) and is idle.
 */
internal fun handoverContinueOffered(state: RemoteState): Boolean {
    val from = state.handedOverFrom?.takeIf { it.seeded } ?: return false
    if (state.status != "idle" || state.loading || !state.connected || state.sending || state.pendingSend != null) return false
    if (state.questions.isNotEmpty() || state.attachments.isNotEmpty() || state.quote != null || state.draft.isNotBlank()) return false
    val only = state.messages.singleOrNull() ?: return false
    return messageHandover(only)?.sourceSessionId == from.sourceSessionId
}
