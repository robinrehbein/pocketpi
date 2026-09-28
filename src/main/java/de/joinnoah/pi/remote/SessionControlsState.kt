package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

const val CONFIGURATION_CAPABILITY = "session.configuration.v1"
const val COMMANDS_CAPABILITY = "session.commands.v1"
const val RENAME_CAPABILITY = "session.rename.v1"
const val CONTEXT_CAPABILITY = "session.context.v1"
const val ADVISOR_CAPABILITY = "session.advisor.v1"

data class AdvisorChoice(
    val provider: String,
    val id: String,
    val name: String,
    val levels: List<String>,
)

data class SessionAdvisor(
    val sessionId: String,
    val enabled: Boolean,
    val model: String?,
    val reasoning: String,
    val pending: Boolean,
    val contextShared: Boolean,
    val attempts: Int,
    val maxAttempts: Int,
    val remainingAttempts: Int,
    val error: String?,
    val choices: List<AdvisorChoice>,
)

internal fun advisor(data: JsonObject, sessionId: String): SessionAdvisor {
    require(data.text("kind") == "advisor" && data.text("sessionId") == sessionId)
    val choices = data.array("choices").map { item ->
        val choice = item.jsonObject
        AdvisorChoice(
            choice.text("provider"), choice.text("id"), choice.text("name"),
            choice.getValue("levels").jsonArray.map { it.jsonPrimitive.content },
        )
    }
    require(choices.size <= 128)
    return SessionAdvisor(
        sessionId, data.flag("enabled"), data.optionalText("model"), data.text("reasoning"),
        data.flag("pending"), data.flag("contextShared"), data.getValue("attempts").jsonPrimitive.int,
        data.getValue("maxAttempts").jsonPrimitive.int,
        data.getValue("remainingAttempts").jsonPrimitive.int,
        data.optionalText("error"), choices,
    )
}

data class SessionContextUsage(
    val sessionId: String,
    val modelProvider: String?,
    val modelId: String?,
    val usedTokens: Long?,
    val contextWindow: Long,
    val percent: Double?,
    val totals: SessionUsageTotals? = null,
)

data class SessionUsageTotals(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val totalTokens: Long,
    val cost: Double,
)

/** Reads the optional session totals; anything malformed yields null rather than an error. */
internal fun usageTotals(value: JsonElement?): SessionUsageTotals? {
    val data = value as? JsonObject ?: return null
    fun count(key: String): Long? =
        (data[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
    val cost =
        (data["cost"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0 } ?: return null
    return SessionUsageTotals(
        count("input") ?: return null,
        count("output") ?: return null,
        count("cacheRead") ?: return null,
        count("cacheWrite") ?: return null,
        count("totalTokens") ?: return null,
        cost,
    )
}

internal fun contextUsage(data: JsonObject, sessionId: String): SessionContextUsage {
    require(data.text("kind") == "context" && data.text("sessionId") == sessionId)
    val window = data.getValue("contextWindow").jsonPrimitive.long
    require(window > 0)
    val used = data["usedTokens"]?.jsonPrimitive?.longOrNull
    require(used == null || used >= 0)
    val percent = data["percent"]?.jsonPrimitive?.doubleOrNull
    require(percent == null || (used != null && percent.isFinite() && percent >= 0))
    val model = data["model"] as? JsonObject
    return SessionContextUsage(
        sessionId,
        model?.text("provider"),
        model?.text("id"),
        used,
        window,
        percent,
        usageTotals(data["totals"]),
    )
}

data class RemoteModel(
    val provider: String,
    val id: String,
    val name: String,
    val input: Set<String>,
    val reasoning: Boolean = false,
)

data class SessionConfiguration(
    val model: RemoteModel?,
    val thinkingLevel: String,
    val thinkingLevels: List<String>,
    val models: List<RemoteModel>,
    val modelsTruncated: Boolean,
)

data class RemoteCommand(val name: String, val description: String?, val source: String)

internal fun configurationControlsAvailable(
    capabilities: Set<String>,
    unavailableCapabilities: Set<String>,
): Boolean =
    CONFIGURATION_CAPABILITY in capabilities &&
        CONFIGURATION_CAPABILITY !in unavailableCapabilities

internal fun configurationControlsNoticeVisible(state: RemoteState): Boolean =
    state.connected &&
        !state.loading &&
        !configurationControlsAvailable(state.capabilities, state.unavailableCapabilities)

internal fun thinkingControlAvailable(configuration: SessionConfiguration?): Boolean =
    configuration?.thinkingLevel?.isNotBlank() == true &&
        configuration.thinkingLevels.any(String::isNotBlank)

internal fun configuration(data: JsonObject, sessionId: String): SessionConfiguration {
    require(data.text("kind") == "configuration" && data.text("sessionId") == sessionId)
    fun model(value: JsonObject): RemoteModel =
        RemoteModel(
            value.text("provider"),
            value.text("id"),
            value.optionalText("name") ?: value.text("id"),
            value.getValue("input").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            value["reasoning"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    val models = data.array("models")
    require(models.size <= 256)
    val levels =
        data.getValue("thinkingLevels").jsonArray.map { it.jsonPrimitive.content }.filter(String::isNotBlank)
    require(levels.size <= 16)
    return SessionConfiguration(
        (data["model"] as? JsonObject)?.let(::model),
        data.text("thinkingLevel"),
        levels,
        models.map(::model),
        data.flag("modelsTruncated"),
    )
}

internal fun commands(data: JsonObject, sessionId: String): List<RemoteCommand> {
    require(data.text("kind") == "commands" && data.text("sessionId") == sessionId)
    val values = data.array("items")
    require(values.size <= 256)
    return values.map {
        RemoteCommand(it.text("name"), it.optionalText("description"), it.text("source"))
    }
}

internal fun commandName(text: String): String? =
    text.takeIf { it.startsWith("/") }?.drop(1)?.takeWhile { !it.isWhitespace() }

internal fun selectCommand(text: String, command: RemoteCommand): String =
    "/" + command.name + text.dropWhile { !it.isWhitespace() }

/** Steer, follow-up, stop and resume for subagent children (`session.subagent_control.v1`). */
const val SUBAGENT_CONTROL_CAPABILITY = "session.subagent_control.v1"

/** Where a phone stop or resume of one subagent child stands. */
enum class ChildControlPhase {
    STOPPING,
    STOPPED_BY_YOU,
    RESUMING,
    RESUMED,
    /** A steer or follow-up met a child that is not running; the composer offers a resume. */
    NOT_RUNNING,
    REFUSED,
    NOT_FOUND,
    /** The parent's reply was lost: the stop or resume may have run. */
    UNCERTAIN,
}

data class ChildControl(
    /** Null once a stale outcome is dropped: the entry only keeps [agentId] and shows nothing. */
    val phase: ChildControlPhase?,
    /** The host's reason, only for [ChildControlPhase.REFUSED]. */
    val reason: String? = null,
    /** The agent that runs after an accepted resume; a resume from disk starts under a new ID. */
    val agentId: String? = null,
)

/** The selected session is a subagent child, whose parent link the host supplied. */
internal fun isChildSession(state: RemoteState): Boolean =
    state.session?.optionalText("parentSessionId")?.isNotBlank() == true

/** The host can steer, stop and resume the selected child from the phone. */
internal fun childControlsAvailable(state: RemoteState): Boolean =
    isChildSession(state) && SUBAGENT_CONTROL_CAPABILITY in state.capabilities &&
        SUBAGENT_CONTROL_CAPABILITY !in state.unavailableCapabilities &&
        state.selection.sessionId !in state.childControlUnsupported

/** Phases that describe one reply and go stale once the child's status changes. */
internal val STALE_CHILD_CONTROL_PHASES =
    setOf(
        ChildControlPhase.NOT_RUNNING,
        ChildControlPhase.REFUSED,
        ChildControlPhase.NOT_FOUND,
        ChildControlPhase.UNCERTAIN,
    )

/** Largest resume message the host accepts, in UTF-8 bytes (`session.subagent.resume`). */
internal const val CHILD_RESUME_MAX_BYTES = 128 * 1024

/** The stop or resume state of the selected child, if any. */
internal fun childControl(state: RemoteState): ChildControl? =
    state.selection.sessionId?.let { state.childControls[it] }

/** The composer sends a resume instead of a prompt: the child is not running or said so. */
internal fun offersChildResume(state: RemoteState): Boolean =
    childControlsAvailable(state) &&
        (state.status !in setOf("running", "waiting") ||
            childControl(state)?.phase == ChildControlPhase.NOT_RUNNING)

internal data class SubagentControlResult(
    val status: String,
    val agentId: String?,
    val reason: String?,
)

/** Reads a `subagent.control` result for [sessionId]; an unknown status reads as unknown outcome. */
internal fun subagentControlResult(data: JsonObject, sessionId: String): SubagentControlResult {
    require(data.text("kind") == "subagent.control" && data.text("sessionId") == sessionId)
    val status = data.text("status")
    return SubagentControlResult(
        status.takeIf { it in setOf("accepted", "not_found", "refused") } ?: "unknown",
        data.optionalText("agentId")?.takeIf { status == "accepted" },
        data.optionalText("reason")?.takeIf { status == "refused" }?.take(500),
    )
}
