package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_ESCALATION_TOOL_BYTES = 256
internal const val MAX_ESCALATION_REASON_BYTES = 4096

/**
 * What auto mode blocked, from the `escalation` field of a `confirm` question: the tool, the
 * classifier's masked reason (possibly empty) and, for a subagent's call, the child's label. A yes
 * runs the call once; the phone never offers a session allow.
 */
internal data class ConfirmEscalation(val tool: String, val reason: String, val child: String?)

/**
 * Reads the escalation of a `confirm` question, or null when there is none. A malformed field also
 * gives null: the title and message carry the same facts, so the question still shows as a plain
 * confirm instead of being dropped.
 */
internal fun confirmEscalation(question: JsonObject): ConfirmEscalation? {
    if ((question["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "confirm") return null
    val value = question["escalation"] as? JsonObject ?: return null
    if (!value.keys.all { it in setOf("tool", "reason", "child") }) return null
    fun field(key: String, limit: Int, allowEmpty: Boolean): String? =
        (value[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { (allowEmpty || it.isNotEmpty()) && it.toByteArray(Charsets.UTF_8).size <= limit }
    val tool = field("tool", MAX_ESCALATION_TOOL_BYTES, allowEmpty = false) ?: return null
    val reason = field("reason", MAX_ESCALATION_REASON_BYTES, allowEmpty = true) ?: return null
    val child =
        if ("child" in value) field("child", MAX_ESCALATION_TOOL_BYTES, allowEmpty = false) ?: return null
        else null
    return ConfirmEscalation(tool, reason, child)
}
