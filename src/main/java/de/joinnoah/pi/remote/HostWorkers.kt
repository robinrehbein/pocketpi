package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A `sessions.list`, `sessions.create` or `sessions.open` whose request ID starts with this returns
 * host workers (`session.host_worker.v1`) as `worker` instead of `tui`. Older hosts treat it as part
 * of an opaque request ID, so every such request may carry it.
 */
internal const val HOST_WORKER_REQUEST_PREFIX = "host_worker.v1:"

/** Set on a list row the host listed as `worker`; the app keeps such a row as `tui`. */
internal const val HOST_WORKER_KEY = "hostWorker"

/** [requestId] as sent for a request of [type]: lists, creates and opens opt in to host workers. */
internal fun sessionsListRequestId(type: String, requestId: String): String =
    if (type == "sessions.list" || type == "sessions.create" || type == "sessions.open") "$HOST_WORKER_REQUEST_PREFIX$requestId" else requestId

/**
 * A `worker` row as the `tui` session that opening it returns, marked with [HOST_WORKER_KEY]. The
 * worker is a pi the host runs in a terminal of its own, so everything else treats it as a Mac
 * terminal session.
 */
internal fun withHostWorker(session: JsonObject): JsonObject =
    if (session.optionalText("origin") != "worker") session
    else JsonObject(session + ("origin" to JsonPrimitive("tui")) + (HOST_WORKER_KEY to JsonPrimitive(true)))

internal fun isHostWorker(session: JsonObject): Boolean =
    (session[HOST_WORKER_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
