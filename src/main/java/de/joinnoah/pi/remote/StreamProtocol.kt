package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The streaming and resume parts of the wire protocol: `remote.stream.v1` (session focus, ping,
 * acks, the `after` field of events), and resume from a revision (`resume.v1:` request IDs, the
 * snapshot `epoch`, the `session.events` result). The host side is in noah-monorepo,
 * `packages/pi-atlas/extensions/remote/protocol`; the shared fixtures `stream-v1.json` and
 * `resume-v1.json` pin both ends. Everything here only parses and builds payloads; the repository
 * decides when to send them.
 */

/** Advertised on the `capabilities.v2:` route by a host that understands focus, `after`, ping and acks. */
internal const val STREAM_CAPABILITY = "remote.stream.v1"

/** Most sessions one `session.focus` may name. */
internal const val MAX_FOCUS_SESSIONS = 4

/** Largest `relayRttMs` in a `pong`, in milliseconds. */
internal const val MAX_RELAY_RTT_MS = 600_000L

/** Largest delivery sequence a `remote.ack` may carry (the channel's 32-bit frame counter). */
internal const val MAX_ACK_SEQUENCE = 0xffffffffL

/** Most events one `session.events` result may hold. */
internal const val MAX_SESSION_EVENTS = 512

/** Request ID prefix of a first-page `session.snapshot` that may be served from the host's journal. */
internal const val RESUME_REQUEST_PREFIX = "resume.v1:"

private const val MAX_SAFE_REVISION = 9007199254740991L
private const val MAX_REQUEST_ID_BYTES = 256
private const val EPOCH_LENGTH = 22

/** The journal epoch the host named, or null when the app has none (`-` in the request ID). */
internal data class ResumeBase(val epoch: String?, val revision: Long)

/** Whether [value] is a journal epoch: 22 characters of the unpadded base64url alphabet. */
internal fun isJournalEpoch(value: String): Boolean = value.length == EPOCH_LENGTH && value.all(::isBase64UrlChar)

private fun isBase64UrlChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

/** JavaScript's `\s`: the host's grammar for the part of a request ID that is not the base. */
private fun isScriptWhitespace(c: Char): Boolean =
    c in '\t'..'\r' || c == ' ' || c == ' ' || c == ' ' || c in ' '..' ' ||
        c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

/**
 * A request ID in the resume form, `resume.v1:<epoch>:<revision>:<rest>`: [epoch] is the journal
 * epoch of an earlier reply or null for `-` (unknown), [revision] the last revision the app
 * applied (0 with an unknown epoch), and [rest] the app's random part, which keeps IDs unique.
 * Every older host treats the whole string as an opaque ID, so the form is safe to send anywhere.
 * Throws [IllegalArgumentException] for a value the host's grammar would not read back.
 */
internal fun resumeRequestId(epoch: String?, revision: Long, rest: String = Wire.random()): String {
    require(epoch == null || isJournalEpoch(epoch))
    require(revision in 0..MAX_SAFE_REVISION)
    require(epoch != null || revision == 0L)
    val id = "$RESUME_REQUEST_PREFIX${epoch ?: "-"}:$revision:$rest"
    require(parseResumeRequest(id) != null)
    return id
}

/** The fresh-form request ID: the host answers it with a snapshot that carries the epoch. */
internal fun freshRequestId(): String = resumeRequestId(null, 0)

/**
 * The resume base in [requestId], or null for every other shape, as the host's
 * `parseResumeRequest` reads it. Never throws.
 */
internal fun parseResumeRequest(requestId: String): ResumeBase? {
    if (!requestId.startsWith(RESUME_REQUEST_PREFIX)) return null
    if (requestId.encodeToByteArray().size > MAX_REQUEST_ID_BYTES) return null
    if (requestId.any { it.code <= 0x20 || it.code == 0x7f }) return null
    val parts = requestId.substring(RESUME_REQUEST_PREFIX.length).split(':', limit = 3)
    if (parts.size != 3) return null
    val (epoch, revisionText, rest) = parts
    if (epoch != "-" && !isJournalEpoch(epoch)) return null
    val plain = revisionText == "0" || (revisionText.length in 1..16 && revisionText[0] in '1'..'9')
    if (!plain || !revisionText.all { it in '0'..'9' }) return null
    val revision = revisionText.toLong()
    if (revision > MAX_SAFE_REVISION) return null
    if (rest.isEmpty() || rest.any { it == ':' || isScriptWhitespace(it) }) return null
    return ResumeBase(epoch.takeUnless { it == "-" }, revision)
}

/** The `session.focus` command: the sessions whose upserts this connection wants (at most [MAX_FOCUS_SESSIONS]). */
internal fun sessionFocusPayload(requestId: String, sessionIds: List<String>): JsonObject {
    require(isOpaqueId(requestId))
    require(sessionIds.size <= MAX_FOCUS_SESSIONS && sessionIds.toSet().size == sessionIds.size)
    require(sessionIds.all(::isOpaqueId))
    return Wire.objectOf(
        "type" to "session.focus",
        "requestId" to requestId,
        "sessionIds" to JsonArray(sessionIds.map(::JsonPrimitive)),
    )
}

/** The `remote.ping` command; the host answers with a `pong` result. */
internal fun remotePingPayload(requestId: String): JsonObject {
    require(isOpaqueId(requestId))
    return Wire.objectOf("type" to "remote.ping", "requestId" to requestId)
}

/** The `remote.ack` payload: the highest frame sequence this app received. It has no request ID and no reply. */
internal fun remoteAckPayload(sequence: Long): JsonObject {
    require(sequence in 0..MAX_ACK_SEQUENCE)
    return Wire.objectOf("type" to "remote.ack", "seq" to sequence)
}

/** The sessions a `session.focus` result echoes: the accepted list, unique and within the limit. */
internal fun sessionFocusResult(data: JsonObject): List<String> {
    Wire.keys(data, setOf("kind", "sessionIds"))
    require(data.text("kind") == "session.focus")
    val ids = (data.getValue("sessionIds") as JsonArray).map { (it as JsonPrimitive).also { id -> require(id.isString) }.content }
    require(ids.size <= MAX_FOCUS_SESSIONS && ids.all(::isOpaqueId) && ids.toSet().size == ids.size)
    return ids
}

/** The relay round trip, in milliseconds, a `pong` result reports, or null when the host sent none. */
internal fun pongResult(data: JsonObject): Long? {
    Wire.keys(data, setOf("kind"), setOf("relayRttMs"))
    require(data.text("kind") == "pong")
    val rtt = data["relayRttMs"] ?: return null
    return requireNotNull(wireInteger(rtt)).also { require(it in 0..MAX_RELAY_RTT_MS) }
}

/**
 * The journal epoch of a first-page snapshot, or null when the host sent none (an older host, a
 * session without a live process). Throws [IllegalArgumentException] for a malformed value, like
 * any other malformed field of the snapshot.
 */
internal fun snapshotEpoch(data: JsonObject): String? {
    val value = data["epoch"] ?: return null
    val epoch = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(epoch != null && isJournalEpoch(epoch))
    return epoch
}

/**
 * The `after` of a session [event]: the revision before which the host left events out on
 * purpose for this receiver (upserts outside the focus, superseded or merged upserts), or null
 * when it sent none. A receiver at revision r applies the event when `after ?: (revision - 1) <= r
 * < revision`; below that it has a gap. Throws [IllegalArgumentException] unless `after` is an
 * integer in `0 until revision`.
 */
internal fun eventAfter(event: JsonObject): Long? {
    val value = event["after"] ?: return null
    val after = requireNotNull(wireInteger(value))
    require(after >= 0 && after < event.long("revision"))
    return after
}

/** Whether [event] has exactly the [keys] of its kind, plus a well-formed `after` when it has one. */
internal fun hasEventShape(event: JsonObject, keys: Set<String>): Boolean =
    (event.keys - "after") == keys && runCatching { eventAfter(event) }.isSuccess

/** A `session.events` result: the journal events after [from] for [sessionId], ending at [revision]. */
internal data class SessionEventsReply(
    val sessionId: String,
    val epoch: String,
    val from: Long,
    val revision: Long,
    val events: List<JsonObject>,
)

/**
 * Checks a `session.events` result as the host's parser does. The events are ascending inside
 * `(from, revision]`, all of [sessionId], end at `revision` (or `from` when empty), and carry
 * `after` exactly across each hole: the previous revision (`from` for the first), never without
 * a hole. There are at most [MAX_SESSION_EVENTS] and no tasks. Each event is checked for shape
 * only; a kind this app does not know passes, and applying it is the Timeline's business. Throws
 * [IllegalArgumentException] (or a JSON exception) for anything else.
 */
internal fun sessionEventsReply(data: JsonObject): SessionEventsReply {
    Wire.keys(data, setOf("kind", "sessionId", "epoch", "from", "revision", "events"))
    require(data.text("kind") == "session.events")
    val sessionId = data.text("sessionId")
    require(isOpaqueId(sessionId))
    val epoch = data.text("epoch")
    require(isJournalEpoch(epoch))
    val from = requireNotNull(wireInteger(data["from"]))
    val revision = requireNotNull(wireInteger(data["revision"]))
    require(from >= 0 && revision >= 0)
    val list = data.getValue("events") as JsonArray
    require(list.size <= MAX_SESSION_EVENTS)
    var previous = from
    val events =
        list.map { element ->
            val event = element as JsonObject
            require(event["type"].isText("event") && event["sessionId"].isText(sessionId))
            val eventRevision = requireNotNull(wireInteger(event["revision"]))
            require(eventRevision > previous)
            if (eventRevision == previous + 1) require("after" !in event)
            else require(wireInteger(event["after"]) == previous)
            // Re-checks `after` against the revision, whatever the hole rule left to it.
            eventAfter(event)
            requireNotNull(event["kind"]?.let { it as? JsonPrimitive }?.takeIf { it.isString })
            requireEventFields(event)
            previous = eventRevision
            event
        }
    require(previous == revision)
    return SessionEventsReply(sessionId, epoch, from, revision, events)
}

private fun JsonElement?.isText(expected: String): Boolean =
    this is JsonPrimitive && isString && content == expected

/** The fields of the kinds a replay applies that the Timeline would otherwise only meet when it throws. */
private fun requireEventFields(event: JsonObject) {
    when (event.text("kind")) {
        "session.status" -> require(event.text("status") in SESSION_STATUSES)
        "message.upsert" -> require(event.obj("message").text("id").isNotEmpty())
    }
}
