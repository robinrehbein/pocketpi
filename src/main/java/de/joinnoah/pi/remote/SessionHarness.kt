package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject

/**
 * Advertised in the `projects.list` capability lists: `sessions.create` accepts `harness`, and
 * session rows may carry it. An older host rejects the unknown field, so only a host that lists
 * this capability gets it.
 */
internal const val SESSION_HARNESS_CAPABILITY = "session.harness.v1"

/** The agent the Mac runs for a session. [wire] is the `harness` value; pi is the default and is never sent. */
enum class SessionHarness(val wire: String) {
    PI("pi"),
    CLAUDE("claude"),
}

/** The harness of a session row: `claude` is Claude Code, anything else (or nothing) is pi. */
internal fun sessionHarness(session: JsonObject): SessionHarness =
    if (session.optionalText("harness") == SessionHarness.CLAUDE.wire) SessionHarness.CLAUDE else SessionHarness.PI

/** Whether the host takes a `harness` other than pi in `sessions.create`. */
internal fun harnessSelectable(capabilities: Set<String>): Boolean = SESSION_HARNESS_CAPABILITY in capabilities

/**
 * The fields of a `sessions.create` for [projectId] that starts [harness]. pi sends no `harness`,
 * so the request stays what every host accepts; another harness goes out only to a host that lists
 * [SESSION_HARNESS_CAPABILITY]. Null when [harness] cannot be asked of this host.
 */
internal fun sessionCreateFields(
    projectId: String,
    harness: SessionHarness,
    capabilities: Set<String>,
): List<Pair<String, Any?>>? =
    when {
        harness == SessionHarness.PI -> listOf("projectId" to projectId)
        harnessSelectable(capabilities) -> listOf("projectId" to projectId, "harness" to harness.wire)
        else -> null
    }
