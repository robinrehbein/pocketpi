package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

const val CONFIGURATION_CAPABILITY = "session.configuration.v1"
const val COMMANDS_CAPABILITY = "session.commands.v1"
const val RENAME_CAPABILITY = "session.rename.v1"
const val CONTEXT_CAPABILITY = "session.context.v1"
const val COMPACT_CAPABILITY = "session.compact.v1"
const val ADVISOR_CAPABILITY = "session.advisor.v1"
const val SETTINGS_CAPABILITY = "session.settings.v1"

internal fun advisorControlAvailable(state: RemoteState): Boolean =
    ADVISOR_CAPABILITY in state.capabilities && ADVISOR_CAPABILITY !in state.unavailableCapabilities

/** The queue modes the host accepts for [SessionSettings.steeringMode] and [SessionSettings.followUpMode]. */
const val QUEUE_MODE_ONE_AT_A_TIME = "one-at-a-time"
const val QUEUE_MODE_ALL = "all"
private val QUEUE_MODES = setOf(QUEUE_MODE_ONE_AT_A_TIME, QUEUE_MODE_ALL)

/** The host-owned pi settings of a session; they also apply to later sessions on that Mac. */
data class SessionSettings(
    val autoCompaction: Boolean,
    val steeringMode: String,
    val followUpMode: String,
)

/** Reads the optional `settings` object; anything missing, malformed or unknown reads as absent. */
internal fun sessionSettings(value: JsonElement?): SessionSettings? {
    val data = value as? JsonObject ?: return null
    fun text(key: String): String? =
        (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in QUEUE_MODES }
    val auto = (data["autoCompaction"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    return SessionSettings(auto ?: return null, text("steeringMode") ?: return null, text("followUpMode") ?: return null)
}

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
    /** Null for sessions the host does not own, and when the host reports nothing usable. */
    val settings: SessionSettings? = null,
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
        sessionSettings(data["settings"]),
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
    selectCommandName(text, command.name)

internal fun selectCommandName(text: String, name: String): String =
    "/" + name + text.dropWhile { !it.isWhitespace() }

/** The Compact action is offered: the host advertises [COMPACT_CAPABILITY] and did not withdraw it. */
internal fun compactAvailable(state: RemoteState): Boolean =
    COMPACT_CAPABILITY in state.capabilities && COMPACT_CAPABILITY !in state.unavailableCapabilities

/** Compacting can start now: the session is idle and no compaction is running or showing. */
internal fun canCompact(state: RemoteState, nowMillis: Long): Boolean =
    state.connected && !state.loading && state.status == "idle" &&
        !state.sending && state.answering.isEmpty() && !state.configurationChanging &&
        !state.compactionRequesting && !compactionVisible(state.compaction, nowMillis)

/**
 * The session settings sheet is offered: the host advertises [SETTINGS_CAPABILITY] next to the
 * configuration controls and reported settings for this session (terminal sessions have none).
 */
internal fun sessionSettingsAvailable(state: RemoteState): Boolean =
    SETTINGS_CAPABILITY in state.capabilities && SETTINGS_CAPABILITY !in state.unavailableCapabilities &&
        configurationControlsAvailable(state.capabilities, state.unavailableCapabilities) &&
        state.configuration?.settings != null

/** The selected session can be renamed from the phone. */
internal fun canRenameSession(state: RemoteState): Boolean =
    state.connected && !state.loading &&
        RENAME_CAPABILITY in state.capabilities &&
        state.session?.text("origin") in setOf("tui", "rpc") &&
        state.status != "offline"

/**
 * pi's built-in slash commands are not in the host's catalog, so the app maps the ones it has a
 * control for. [commandName] is what follows the slash.
 */
internal enum class LocalCommand(val commandName: String, val description: Int) {
    NEW("new", R.string.remote_local_command_new),
    COMPACT("compact", R.string.remote_local_command_compact),
    MODEL("model", R.string.remote_local_command_model),
    SETTINGS("settings", R.string.remote_local_command_settings),
    NAME("name", R.string.remote_local_command_name),
}

/**
 * The [LocalCommand]s that can run now, in list order. [inChat] is false outside a chat route,
 * where a new session has no project to open in. A command draft is sent only while the session
 * is idle, so nothing is offered before.
 */
internal fun availableLocalCommands(
    state: RemoteState,
    inChat: Boolean,
    nowMillis: Long,
): List<LocalCommand> {
    val sendable =
        state.connected && !state.loading && state.status == "idle" && !state.sending &&
            !state.importingAttachments && !state.configurationChanging
    if (!sendable) return emptyList()
    return LocalCommand.entries.filter { command ->
        when (command) {
            LocalCommand.NEW -> inChat
            LocalCommand.COMPACT -> compactAvailable(state) && canCompact(state, nowMillis)
            LocalCommand.MODEL ->
                state.connected && !state.loading && !state.configurationChanging &&
                    configurationControlsAvailable(state.capabilities, state.unavailableCapabilities)
            LocalCommand.SETTINGS -> !state.configurationLoading && sessionSettingsAvailable(state)
            LocalCommand.NAME -> canRenameSession(state)
        }
    }
}

/**
 * A draft that names an available [LocalCommand]; [argument] is the text after the name with its
 * whitespace collapsed.
 */
internal data class LocalInvocation(val command: LocalCommand, val argument: String)

/** The longest `/name` argument, the byte limit the rename request enforces. */
private const val LOCAL_NAME_MAX_BYTES = 4096

/**
 * The invocation the draft asks for, or null when it is no available local command. A quote or
 * attachments never run one: the send then fails as for any command with extra context. Only
 * `/name` takes an argument, so other commands with text after them are left to the normal send,
 * as is a title longer than a rename accepts.
 */
internal fun localInvocation(state: RemoteState, available: List<LocalCommand>): LocalInvocation? {
    if (state.quote != null || state.attachments.isNotEmpty()) return null
    val name = commandName(state.draft) ?: return null
    val command = available.firstOrNull { it.commandName.equals(name, ignoreCase = true) } ?: return null
    val argument = state.draft.drop(1 + name.length).trim().replace(Regex("\\s+"), " ")
    if (argument.isNotEmpty() && command != LocalCommand.NAME) return null
    if (argument.encodeToByteArray().size > LOCAL_NAME_MAX_BYTES) return null
    return LocalInvocation(command, argument)
}

/** Host commands that a local command of the same name does not shadow. */
internal fun mergeCommandSuggestions(
    local: List<LocalCommand>,
    host: List<RemoteCommand>,
    prefix: String,
): Pair<List<LocalCommand>, List<RemoteCommand>> =
    local.filter { it.commandName.startsWith(prefix, ignoreCase = true) } to
        host.filter { command ->
            command.name.startsWith(prefix, ignoreCase = true) &&
                local.none { it.commandName.equals(command.name, ignoreCase = true) }
        }

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
