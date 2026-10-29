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
    CONTINUE(R.string.remote_prediction_continue),
    ACCEPT_OFFER(R.string.remote_prediction_accept_offer),
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

private val gitPublish = Regex("\\b(git\\s+push|gh\\s+pr\\s+create)\\b", RegexOption.IGNORE_CASE)

/** How many edits make a change worth a pull request. */
internal const val PR_EDIT_THRESHOLD = 3

private data class ToolCall(val name: String, val arguments: String, val timestamp: Long?)

private data class ToolEvent(
    val index: Int,
    val edit: Boolean,
    val test: Boolean,
    val publish: Boolean,
    val failed: Boolean,
    val timestamp: Long?,
)

private fun command(arguments: String): String =
    try {
        Wire.parse(arguments, 64 * 1024).optionalText("command") ?: arguments
    } catch (_: Exception) {
        arguments
    }

private fun backgrounded(arguments: String): Boolean =
    runsInBackground(parsedToolArguments(arguments))

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
                // A background command has only started: its result says nothing about tests or a push.
                val shell = if (name == "bash" && call != null && !backgrounded(call.arguments)) command(call.arguments) else null
                events +=
                    ToolEvent(
                        index,
                        edit = name in editTools,
                        test = shell != null && testCommand.containsMatchIn(shell),
                        publish = shell != null && gitPublish.containsMatchIn(shell),
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
 * In order: a turn the host was stopped during → continue; a tool that failed in the latest turn → fix
 * it; a reply that ends by offering a next step ("Shall I …?") → accept it; an approved plan
 * without edits since → implement it; edits since the last test run → run the tests; several edits
 * and a green test run in the latest turn, not yet pushed → prepare a PR; otherwise the user's
 * most frequent prompt in this project. A running session, an open question or a fresh session
 * gets no suggestion.
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
    val reply = messages.withIndex().lastOrNull { it.index > lastUser && it.value.optionalText("role") == "assistant" }
    // pi's own errors and the user's Stop both arrive as an empty error reply and can't be told
    // apart, so only the host's interruption notice, which has text, suggests continuing.
    if (reply?.value?.optionalText("state") == "error" && !reply.value.optionalText("text").isNullOrBlank()) {
        return PromptPrediction.Rule(PredictionRule.CONTINUE)
    }
    val latest = events.lastOrNull()
    val fix =
        if (latest != null && latest.failed && latest.index > lastUser)
            PromptPrediction.Rule(if (latest.test) PredictionRule.FIX_TESTS else PredictionRule.FIX_ERROR)
        else null
    // A failure the reply came after may be what the reply offers to handle, so the offer wins.
    if (fix != null && (reply == null || latest!!.index > reply.index)) return fix
    if (reply != null && offersNextStep(reply.value.optionalText("text").orEmpty())) {
        return PromptPrediction.Rule(PredictionRule.ACCEPT_OFFER)
    }
    if (fix != null) return fix
    if (planApprovedAt != null &&
        events.none { it.edit && (it.timestamp == null || it.timestamp >= planApprovedAt) }
    ) return PromptPrediction.Rule(PredictionRule.IMPLEMENT_PLAN)
    val lastTest = events.indexOfLast { it.test }
    val editsSinceTest = events.drop(lastTest + 1).count { it.edit && !it.failed }
    if (editsSinceTest > 0) return PromptPrediction.Rule(PredictionRule.RUN_TESTS)
    val edits = events.count { it.edit && !it.failed }
    if (lastTest >= 0 && edits >= PR_EDIT_THRESHOLD) {
        val test = events[lastTest]
        // The test run itself may be the push ("./gradlew test && git push"), so it counts too.
        val published = events.drop(lastTest).any { it.publish && !it.failed }
        if (!test.failed && test.index > lastUser && !published) return PromptPrediction.Rule(PredictionRule.PREPARE_PR)
    }
    val lastPrompt = messages.getOrNull(lastUser)?.optionalText("text")?.let(::normalizePrompt)?.lowercase()
    return frequentPrompts
        .firstOrNull { normalizePrompt(it)?.lowercase() != lastPrompt }
        ?.let { PromptPrediction.History(it) }
}

// ---- What the latest reply asks, and which language the conversation is in ----

// An offer starts a sentence: at the start of the reply, after . ! ? or :, or on a new line
// (bullets and quote marks allowed).
private val offerOpening =
    Regex(
        "(?:^|[.!?:]\\s+|\\n)[\\s>*_-]*(shall i|should i|do you want me to|want me to|" +
            "would you like me to|shall we|should we|can i go ahead|ok to|okay to|soll ich|sollen wir|" +
            "möchtest du,? dass ich|willst du,? dass ich|darf ich|kann ich|wollen wir)\\b",
        RegexOption.IGNORE_CASE,
    )

private val alternatives = Regex("\\b(or|oder)\\b", RegexOption.IGNORE_CASE)

private val word = Regex("[\\p{L}']+")

/**
 * True when [text] ends with a yes/no offer of a next step, such as "Shall I commit this?" or
 * "Soll ich z. B. die Tests anpassen?": the last offer opening in the reply runs to its final "?"
 * without another question in between. A question between alternatives ("… or …?") has no
 * single answer, so it does not count.
 */
internal fun offersNextStep(text: String): Boolean {
    val trimmed = text.trimEnd().trimEnd('*', '_', '`', ' ')
    if (!trimmed.endsWith("?")) return false
    val opening = offerOpening.findAll(trimmed).lastOrNull() ?: return false
    val offer = trimmed.substring(opening.groups[1]!!.range.first, trimmed.length - 1)
    return '?' !in offer && !alternatives.containsMatchIn(offer)
}

private val germanWords =
    setOf(
        "und", "ist", "nicht", "ich", "du", "das", "der", "die", "den", "dem", "mit", "für", "auf",
        "bitte", "mach", "mache", "noch", "auch", "wir", "ein", "eine", "es", "kannst", "kann",
        "soll", "jetzt", "dann", "wie", "warum", "ja", "nein", "aber", "bei", "zu", "im", "von",
        "wenn", "dass", "hast", "habe", "sind", "wird",
    )

private val englishWords =
    setOf(
        "the", "and", "is", "not", "i", "you", "it", "with", "for", "on", "please", "make", "also",
        "we", "can", "should", "now", "then", "how", "what", "why", "yes", "but", "this", "that",
        "to", "of", "if", "do", "run",
    )

/**
 * The language of the user's recent prompts as a language tag ("de" or "en"), or null when they
 * give no clear signal. Rule suggestions use it, so they match how the user talks to pi rather
 * than the phone's language.
 */
internal fun conversationLanguage(messages: List<JsonObject>): String? {
    var german = 0
    var english = 0
    messages.asReversed()
        .asSequence()
        .filter { it.optionalText("role") == "user" }
        .map { it.optionalText("text").orEmpty().trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("/") }
        .take(3)
        .forEach { text ->
            if (text.any { it in "äöüß" }) german += 2
            word.findAll(text).forEach {
                if (it.value in germanWords) german++
                if (it.value in englishWords) english++
            }
        }
    return when {
        german > english -> "de"
        english > german -> "en"
        else -> null
    }
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
