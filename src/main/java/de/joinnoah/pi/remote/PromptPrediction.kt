package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// On-device prompt predictions: plain rules over the conversation plus the user's own frequent
// prompts. No network and no model; everything here is pure and unit-tested.

internal enum class PredictionRule(val text: Int) {
    FIX_TESTS(R.string.remote_prediction_fix_tests),
    FIX_ERROR(R.string.remote_prediction_fix_error),
    IMPLEMENT_PLAN(R.string.remote_prediction_implement_plan),
    RUN_TESTS(R.string.remote_prediction_run_tests),
    PREPARE_PR(R.string.remote_prediction_prepare_pr),
}

internal sealed interface PromptPrediction {
    data class Rule(val rule: PredictionRule) : PromptPrediction

    /** One of the user's own frequent prompts in this project. */
    data class History(val text: String) : PromptPrediction
}

private val editTools =
    setOf("edit", "write", "multiedit", "multi_edit", "apply_patch", "str_replace", "create", "patch")

private val testCommand =
    Regex(
        "\\b(tests?|jest|vitest|pytest|mocha|rspec|phpunit|xctest|testDebugUnitTest|jvmTest|" +
            "connectedCheck|test:unit)\\b",
        RegexOption.IGNORE_CASE,
    )

/** How many edits make a change worth a pull request. */
internal const val PR_EDIT_THRESHOLD = 3

private data class ToolCall(val name: String, val arguments: String, val timestamp: Long?)

private data class ToolEvent(
    val index: Int,
    val edit: Boolean,
    val test: Boolean,
    val failed: Boolean,
    val timestamp: Long?,
)

private fun command(arguments: String): String =
    try {
        Wire.parse(arguments, 64 * 1024).optionalText("command") ?: arguments
    } catch (_: Exception) {
        arguments
    }

private fun toolEvents(messages: List<JsonObject>): Pair<List<ToolEvent>, Int> {
    val calls = mutableMapOf<String, ToolCall>()
    val events = mutableListOf<ToolEvent>()
    var lastUser = -1
    messages.forEachIndexed { index, message ->
        when (message.optionalText("role")) {
            "user" -> lastUser = index
            "assistant" -> {
                val timestamp = (message["timestamp"] as? JsonPrimitive)?.longOrNull
                (message["parts"] as? JsonArray)?.forEach { element ->
                    val part = element as? JsonObject ?: return@forEach
                    if (part.optionalText("type") != "toolCall") return@forEach
                    val id = part.optionalText("id") ?: return@forEach
                    calls[id] =
                        ToolCall(
                            part.optionalText("name").orEmpty(),
                            part.optionalText("arguments").orEmpty(),
                            timestamp,
                        )
                }
            }
            "tool" -> {
                val call = message.optionalText("toolCallId")?.let { calls[it] }
                val name = (message.optionalText("toolName") ?: call?.name).orEmpty().lowercase()
                if (message.optionalText("state") == "streaming") return@forEachIndexed
                events +=
                    ToolEvent(
                        index,
                        edit = name in editTools,
                        test = name == "bash" && call != null && testCommand.containsMatchIn(command(call.arguments)),
                        failed = message.optionalText("state") == "error",
                        timestamp = call?.timestamp,
                    )
            }
        }
    }
    return events to lastUser
}

/**
 * Suggests the next prompt for an idle conversation, or null.
 *
 * In order: a tool that failed in the latest turn → fix it; an approved plan without edits since →
 * implement it; edits since the last test run → run the tests; several edits and a green test run
 * in the latest turn → prepare a PR; otherwise the user's most frequent prompt in this project. A
 * running session, an open question or a fresh session gets no suggestion.
 */
internal fun predictPrompt(
    messages: List<JsonObject>,
    status: String,
    hasQuestions: Boolean,
    planApprovedAt: Long? = null,
    frequentPrompts: List<String> = emptyList(),
): PromptPrediction? {
    if (status == "running" || status == "waiting" || hasQuestions || messages.isEmpty()) return null
    if (messages.last().optionalText("state") == "streaming") return null
    val (events, lastUser) = toolEvents(messages)
    val latest = events.lastOrNull()
    if (latest != null && latest.failed && latest.index > lastUser) {
        return PromptPrediction.Rule(if (latest.test) PredictionRule.FIX_TESTS else PredictionRule.FIX_ERROR)
    }
    if (planApprovedAt != null &&
        events.none { it.edit && (it.timestamp == null || it.timestamp >= planApprovedAt) }
    ) return PromptPrediction.Rule(PredictionRule.IMPLEMENT_PLAN)
    val lastTest = events.indexOfLast { it.test }
    val editsSinceTest = events.drop(lastTest + 1).count { it.edit && !it.failed }
    if (editsSinceTest > 0) return PromptPrediction.Rule(PredictionRule.RUN_TESTS)
    val edits = events.count { it.edit && !it.failed }
    if (lastTest >= 0 && edits >= PR_EDIT_THRESHOLD) {
        val test = events[lastTest]
        if (!test.failed && test.index > lastUser) return PromptPrediction.Rule(PredictionRule.PREPARE_PR)
    }
    val lastPrompt = messages.getOrNull(lastUser)?.optionalText("text")?.let(::normalizePrompt)?.lowercase()
    return frequentPrompts
        .firstOrNull { normalizePrompt(it)?.lowercase() != lastPrompt }
        ?.let { PromptPrediction.History(it) }
}

// ---- The user's own prompt history, per project ----

internal data class PromptUse(val text: String, val count: Int, val lastUsed: Long)

internal data class PromptHistory(
    /** Keyed by "routeId/projectId". */
    val projects: Map<String, List<PromptUse>> = emptyMap(),
    /** Keyed by "routeId/sessionId": when the user approved a plan there. */
    val planApprovals: Map<String, Long> = emptyMap(),
)

internal const val MAX_PROMPTS_PER_PROJECT = 30
internal const val MAX_PROMPT_PROJECTS = 20
internal const val MAX_PLAN_APPROVALS = 20
internal const val MAX_PROMPT_LENGTH = 160
internal const val MIN_PROMPT_USES = 2

/** The form a prompt is stored and compared in, or null when it is not worth remembering. */
internal fun normalizePrompt(text: String): String? {
    val value = text.trim().replace(Regex("\\s+"), " ")
    if (value.length < 3 || value.length > MAX_PROMPT_LENGTH || value.startsWith("/")) return null
    if (text.trim().contains('\n')) return null
    return value
}

internal fun PromptHistory.record(key: String, text: String, now: Long): PromptHistory {
    val prompt = normalizePrompt(text) ?: return this
    val uses = projects[key].orEmpty()
    val existing = uses.find { it.text.equals(prompt, ignoreCase = true) }
    val updated =
        (uses.filter { it !== existing } +
                PromptUse(existing?.text ?: prompt, (existing?.count ?: 0) + 1, now))
            .sortedWith(compareByDescending<PromptUse> { it.count }.thenByDescending { it.lastUsed })
            .take(MAX_PROMPTS_PER_PROJECT)
    val bounded =
        (projects + (key to updated)).entries
            .sortedByDescending { entry -> entry.value.maxOfOrNull { it.lastUsed } ?: 0L }
            .take(MAX_PROMPT_PROJECTS)
            .associate { it.key to it.value }
    return copy(projects = bounded)
}

internal fun PromptHistory.frequent(key: String): List<String> =
    projects[key].orEmpty()
        .filter { it.count >= MIN_PROMPT_USES }
        .sortedWith(compareByDescending<PromptUse> { it.count }.thenByDescending { it.lastUsed })
        .map { it.text }

internal fun PromptHistory.approvePlan(key: String, now: Long): PromptHistory =
    copy(
        planApprovals =
            (planApprovals - key + (key to now)).entries
                .sortedByDescending { it.value }
                .take(MAX_PLAN_APPROVALS)
                .associate { it.key to it.value }
    )

internal fun PromptHistory.forgetRoute(routeId: String): PromptHistory {
    val prefix = "$routeId/"
    return PromptHistory(
        projects.filterKeys { !it.startsWith(prefix) },
        planApprovals.filterKeys { !it.startsWith(prefix) },
    )
}

/** Keeps only the prompts and plan approvals of hosts that are still paired. */
internal fun PromptHistory.onlyRoutes(routes: Set<String>): PromptHistory =
    PromptHistory(
        projects.filterKeys { it.substringBefore('/') in routes },
        planApprovals.filterKeys { it.substringBefore('/') in routes },
    )

internal fun PromptHistory.json(): JsonObject =
    Wire.objectOf(
        "version" to 1,
        "projects" to JsonObject(
            projects.mapValues { (_, uses) ->
                JsonArray(uses.map { Wire.objectOf("text" to it.text, "count" to it.count, "lastUsed" to it.lastUsed) })
            }
        ),
        "planApprovals" to JsonObject(planApprovals.mapValues { JsonPrimitive(it.value) }),
    )

internal fun promptHistory(value: JsonObject): PromptHistory {
    require(value.long("version") == 1L)
    return PromptHistory(
        value.obj("projects").entries.take(MAX_PROMPT_PROJECTS).associate { (key, uses) ->
            key to uses.jsonArray.take(MAX_PROMPTS_PER_PROJECT).map {
                val use = it.jsonObject
                PromptUse(
                    use.text("text").also { text -> require(text.length <= MAX_PROMPT_LENGTH) },
                    use.long("count").toInt(),
                    use.long("lastUsed"),
                )
            }
        },
        value.obj("planApprovals").entries.take(MAX_PLAN_APPROVALS).associate { (key, time) ->
            key to time.jsonPrimitive.longOrNull!!
        },
    )
}
