package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

internal enum class QuestionnaireState { PENDING, ANSWERED, CANCELLED }

internal data class QuestionnaireEntry(
    val id: String,
    val prompt: String,
    val answers: List<String>,
    val skipped: Boolean,
    val note: String? = null,
)

internal data class QuestionnairePresentation(
    val toolCallId: String,
    val requestId: String?,
    val state: QuestionnaireState,
    val questions: List<QuestionnaireEntry>,
)

private fun JsonObject.qString(key: String, max: Int, nonBlank: Boolean = false): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (!value.isString) return null
    return value.content.takeIf { it.toByteArray(Charsets.UTF_8).size <= max && (!nonBlank || it.isNotBlank()) }
}

private fun JsonObject.qBoolean(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (!value.isString) value.booleanOrNull else null
}

/** Parses only the provisional structured host field, never message text. */
internal fun questionnairePresentation(message: JsonObject): QuestionnairePresentation? {
    val role = message.qString("role", 32) ?: return null
    if (role != "tool" && role != "user") return null
    if (message.qBoolean("truncated") == true) return null
    val q = message["questionnaire"] as? JsonObject ?: return null
    if (q.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return null
    val version = q["version"] as? JsonPrimitive ?: return null
    if (version.isString || version.content != "1") return null
    val toolCallId = q.qString("toolCallId", 256, true)?.takeIf(::isOpaqueId) ?: return null
    if (role == "tool" && message.qString("toolCallId", 256) != toolCallId) return null
    val requestId = if ("requestId" in q) q.qString("requestId", 256, true)?.takeIf(::isOpaqueId) ?: return null else null
    val state = when (q.qString("state", 32)) {
        "pending" -> QuestionnaireState.PENDING
        "answered" -> QuestionnaireState.ANSWERED
        "cancelled" -> QuestionnaireState.CANCELLED
        else -> return null
    }
    if (role == "user" && (state != QuestionnaireState.ANSWERED || requestId == null)) return null
    if ("truncated" in q && q.qBoolean("truncated") != false) return null
    val rawQuestions = q["questions"] as? JsonArray ?: return null
    if (rawQuestions.size !in 1..12) return null
    val questions = mutableListOf<QuestionnaireEntry>()
    val ids = mutableSetOf<String>()
    for (raw in rawQuestions) {
        val question = raw as? JsonObject ?: return null
        val id = question.qString("id", 256, true) ?: return null
        val prompt = question.qString("prompt", 2048, true) ?: return null
        if (!ids.add(id)) return null
        if ("label" in question && question.qString("label", 512) == null) return null
        if ("options" in question) {
            val options = question["options"] as? JsonArray ?: return null
            if (options.size > 20) return null
            for (rawOption in options) {
                val option = rawOption as? JsonObject ?: return null
                if (option.qString("label", 512) == null || option.qString("value", 512) == null) return null
            }
        }
        questions.add(QuestionnaireEntry(id, prompt, emptyList(), false))
    }
    val answers = if ("answers" in q) q["answers"] as? JsonArray ?: return null else JsonArray(emptyList())
    if (state != QuestionnaireState.ANSWERED && answers.isNotEmpty()) return null
    if (answers.size > questions.size) return null
    val parsed = mutableMapOf<String, QuestionnaireEntry>()
    for (raw in answers) {
        val answer = raw as? JsonObject ?: return null
        val id = answer.qString("id", 256, true) ?: return null
        if (id !in ids || id in parsed) return null
        val skipped = answer.qBoolean("skipped") ?: return null
        val selections = answer["selections"] as? JsonArray ?: return null
        if (selections.size > 20 || (skipped && selections.isNotEmpty())) return null
        val labels = mutableListOf<String>()
        for (rawSelection in selections) {
            val selection = rawSelection as? JsonObject ?: return null
            val label = selection.qString("label", 8192) ?: return null
            if (selection.qString("value", 8192) == null) return null
            if ("wasCustom" in selection && selection.qBoolean("wasCustom") == null) return null
            if ("index" in selection) {
                val index = selection["index"] as? JsonPrimitive ?: return null
                if (index.isString || index.intOrNull !in 1..20) return null
            }
            labels.add(label)
        }
        val note = if ("note" in answer) answer.qString("note", 2048) ?: return null else null
        val original = questions.first { it.id == id }
        parsed[id] = original.copy(answers = labels, skipped = skipped, note = note)
    }
    return QuestionnairePresentation(toolCallId, requestId, state, questions.map { parsed[it.id] ?: it })
}
