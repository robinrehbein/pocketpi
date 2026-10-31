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

/**
 * Sessions that run the pi Remote extension take follow-up, steer and reload from the phone, and a
 * host worker also takes close. Only the `capabilities.v2:` route may advertise it.
 */
internal const val HOST_WORKER_V2_CAPABILITY = "session.host_worker.v2"

/** The host's `unsupported` message when this session's pi predates a control (the protocol's fixed sentence). */
internal const val EXTENSION_CONTROL_REFUSAL_MESSAGE = "Reload the pi remote extension to use this control"

/** A control that needs the session's pi to know it; a refusal hides it for that session only. */
enum class ExtensionControl {
    FOLLOW_UP,
    STEER,
    RELOAD,
}

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

/**
 * The selected terminal session or host worker offers [control]. The app cannot know which extension
 * a hand-started pi runs, so this is optimistic until the host refuses. A subagent child stays on
 * the child controls.
 */
internal fun extensionControls(state: RemoteState, control: ExtensionControl): Boolean =
    state.session?.optionalText("origin") == "tui" && !isChildSession(state) &&
        hostWorkerV2(state.capabilities, state.unavailableCapabilities) &&
        control !in state.sessionControlUnsupported[state.selection.sessionId].orEmpty()

/** The host runs [session]'s process, so it can end it: a session it started or a marked worker. */
internal fun hostOwned(state: RemoteState, session: JsonObject? = state.session): Boolean =
    session != null &&
        (session.optionalText("origin") == "rpc" ||
            (isHostWorker(session) && hostWorkerV2(state.capabilities, state.unavailableCapabilities)))

/** [hostOwned] for a list row. */
internal fun hostOwned(item: SessionListItem, capabilities: Set<String>): Boolean =
    item.daemonOwned || (item.hostWorker && hostWorkerV2(capabilities, emptySet()))

private fun hostWorkerV2(capabilities: Set<String>, unavailable: Set<String>): Boolean =
    HOST_WORKER_V2_CAPABILITY in capabilities && HOST_WORKER_V2_CAPABILITY !in unavailable

/** The host refused because this session's pi has to reload its Remote extension first. */
internal val Throwable.isExtensionControlRefusal: Boolean
    get() = isUnsupported && (this as? RemoteRequestException)?.hostMessage == EXTENSION_CONTROL_REFUSAL_MESSAGE

/** The chat explains why a terminal session takes no input while it runs, unless it does. */
internal fun showsTuiInfo(state: RemoteState): Boolean =
    state.session?.optionalText("origin") == "tui" && !canFollowUp(state)
