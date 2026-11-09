package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Run-bound statistics of a subagent card (`subagent_metadata.v1`, noah TOOLING-1264). Granted per
 * connection on route v3 only, independently of `session_activity.v1`; hosts without it send none.
 */
const val SUBAGENT_METADATA_CAPABILITY = "subagent_metadata.v1"

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private const val MAX_COST_USD = 1_000_000.0
private const val MAX_METADATA_TEXT_BYTES = 256
private val THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
private val METADATA_KEYS = setOf(
    "run", "model", "responseModel", "thinkingLevel", "requestedModel", "requestedThinkingLevel",
    "turns", "maxTurns", "toolUses", "tokens", "costUsd", "durationMs",
)
private val MODEL_KEYS = setOf("provider", "id", "name")
private val TOKEN_KEYS = setOf("input", "output", "cacheRead", "cacheWrite")

internal data class MetadataModel(val provider: String, val id: String, val name: String?) {
    val label: String get() = name ?: id
}

/** Each counter is independently optional: a missing one is unknown, never zero. */
internal data class MetadataTokens(val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?)

/**
 * Every field but [run] is optional, and missing means unknown. [model] is the selected model,
 * [responseModel] the one that answered. Tokens and cost count the agent's lifetime subtree,
 * [toolUses] only its own calls, [turns] and [durationMs] the current run.
 */
internal data class AgentMetadata(
    val run: Long,
    val model: MetadataModel? = null,
    val responseModel: MetadataModel? = null,
    val thinkingLevel: String? = null,
    val requestedModel: String? = null,
    val requestedThinkingLevel: String? = null,
    val turns: Long? = null,
    val maxTurns: Long? = null,
    val toolUses: Long? = null,
    val tokens: MetadataTokens? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
)

/** A nonnegative safe integer, as JavaScript's `Number.isSafeInteger(n) && n >= 0`. */
private fun metadataCount(value: JsonElement?): Long? =
    (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_INTEGER }

private fun metadataText(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_TEXT_BYTES }

private fun metadataModel(value: JsonElement?): MetadataModel? {
    val model = value as? JsonObject ?: return null
    if (!MODEL_KEYS.containsAll(model.keys)) return null
    val name = if (model.containsKey("name")) metadataText(model["name"]) ?: return null else null
    return MetadataModel(metadataText(model["provider"]) ?: return null, metadataText(model["id"]) ?: return null, name)
}

private fun metadataTokens(value: JsonElement?): MetadataTokens? {
    val tokens = value as? JsonObject ?: return null
    if (tokens.isEmpty() || !TOKEN_KEYS.containsAll(tokens.keys)) return null
    if (tokens.values.any { metadataCount(it) == null }) return null
    return MetadataTokens(
        metadataCount(tokens["input"]), metadataCount(tokens["output"]),
        metadataCount(tokens["cacheRead"]), metadataCount(tokens["cacheWrite"]),
    )
}

/**
 * [value] as the host's `validSubagentMetadata` accepts it, or null when it is invalid: a missing
 * or unsafe `run`, an unknown key at any level, an empty `tokens`, a zero `maxTurns`, a blank or
 * over-long model string, an unknown thinking level or a cost outside 0..1e6.
 */
internal fun parseAgentMetadata(value: JsonElement?): AgentMetadata? {
    val metadata = value as? JsonObject ?: return null
    if (!METADATA_KEYS.containsAll(metadata.keys)) return null
    fun <T : Any> optional(key: String, parse: (JsonElement?) -> T?): Result<T?> =
        if (!metadata.containsKey(key)) Result.success(null)
        else parse(metadata[key])?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(key))
    val level = { v: JsonElement? -> metadataText(v)?.takeIf { it in THINKING_LEVELS } }
    val cost = { v: JsonElement? ->
        (v as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..MAX_COST_USD }
    }
    return AgentMetadata(
        run = metadataCount(metadata["run"]) ?: return null,
        model = optional("model", ::metadataModel).getOrElse { return null },
        responseModel = optional("responseModel", ::metadataModel).getOrElse { return null },
        thinkingLevel = optional("thinkingLevel", level).getOrElse { return null },
        requestedModel = optional("requestedModel", ::metadataText).getOrElse { return null },
        requestedThinkingLevel = optional("requestedThinkingLevel", level).getOrElse { return null },
        turns = optional("turns", ::metadataCount).getOrElse { return null },
        maxTurns = optional("maxTurns") { v -> metadataCount(v)?.takeIf { it > 0 } }.getOrElse { return null },
        toolUses = optional("toolUses", ::metadataCount).getOrElse { return null },
        tokens = optional("tokens", ::metadataTokens).getOrElse { return null },
        costUsd = optional("costUsd", cost).getOrElse { return null },
        durationMs = optional("durationMs", ::metadataCount).getOrElse { return null },
    )
}

/**
 * What a card shows of [AgentMetadata]; every null part is hidden. A missing cost never reads as
 * $0, a missing limit never as unlimited and a missing level never as `off`. [responseModel] shows
 * only when it differs from the selected model, [requestedThinkingLevel] only when it differs from
 * the effective one. [totalTokens] needs all four counters; otherwise [tokenParts] lists the known
 * ones, so a partial sum never reads as a total.
 */
internal data class AgentMetadataDisplay(
    val model: String?,
    val responseModel: String?,
    val requestedModel: String?,
    val thinkingLevel: String?,
    val requestedThinkingLevel: String?,
    val turns: Long?,
    val maxTurns: Long?,
    val totalTokens: Long?,
    val tokenParts: MetadataTokens?,
    val costUsd: Double?,
    val durationMs: Long?,
    val toolUses: Long?,
    /** The resume this card belongs to; null for the first run. */
    val resume: Long?,
) {
    val hasModelLine: Boolean
        get() = model != null || responseModel != null || requestedModel != null || thinkingLevel != null || requestedThinkingLevel != null
    val hasStatsLine: Boolean
        get() = turns != null || maxTurns != null || totalTokens != null || tokenParts != null ||
            costUsd != null || durationMs != null || toolUses != null || resume != null
}

internal fun agentMetadataDisplay(metadata: AgentMetadata): AgentMetadataDisplay {
    val tokens = metadata.tokens
    val total = tokens?.run {
        if (input != null && output != null && cacheRead != null && cacheWrite != null) input + output + cacheRead + cacheWrite else null
    }
    val served = metadata.responseModel?.takeIf {
        val selected = metadata.model
        selected == null || selected.provider != it.provider || selected.id != it.id
    }
    return AgentMetadataDisplay(
        model = metadata.model?.label,
        responseModel = served?.label,
        requestedModel = metadata.requestedModel,
        thinkingLevel = metadata.thinkingLevel,
        requestedThinkingLevel = metadata.requestedThinkingLevel?.takeIf { it != metadata.thinkingLevel },
        turns = metadata.turns,
        maxTurns = metadata.maxTurns,
        totalTokens = total,
        tokenParts = tokens?.takeIf { total == null },
        costUsd = metadata.costUsd,
        durationMs = metadata.durationMs,
        toolUses = metadata.toolUses,
        resume = metadata.run.takeIf { it > 0 },
    )
}
