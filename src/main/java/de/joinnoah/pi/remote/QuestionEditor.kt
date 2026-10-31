package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonPrimitive

internal const val QUESTION_EDITOR_CAPABILITY = "session.question_editor.v1"

/** Accept only Unicode scalar values and the controls supported by the bounded editor wire. */
internal fun validEditorText(value: String, maxBytes: Int): Boolean {
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (c.code < 0x20 && c != '\n' && c != '\t') return false
        if (Character.isHighSurrogate(c)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
            index++
        } else if (Character.isLowSurrogate(c)) return false
        index++
    }
    return value.toByteArray(Charsets.UTF_8).size <= maxBytes
}

internal fun editorPrefill(question: kotlinx.serialization.json.JsonObject): String? {
    if (question.containsKey("details")) return null
    val prefill = question["prefill"] ?: return ""
    if (prefill !is JsonPrimitive || !prefill.isString) return null
    return prefill.content.takeIf { validEditorText(it, 16384) }
}

/** Compose keys normally compare JSON objects structurally; drafts belong to an incarnation. */
internal class EditorQuestionKey(private val question: kotlinx.serialization.json.JsonObject) {
    override fun equals(other: Any?): Boolean = other is EditorQuestionKey && question === other.question
    override fun hashCode(): Int = System.identityHashCode(question)
}
