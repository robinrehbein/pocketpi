package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The answers to a questionnaire, whichever device collects them: the option values chosen per
 * question id in [selected], and the answers of the question's own words per id in [custom]
 * (custom defaults first, then what was typed). The phone's [QuestionnaireForm] and the watch
 * both send [wire], so a question gets the same answer from either.
 */
internal data class QuestionnaireAnswers(
    val selected: Map<String, Set<String>> = emptyMap(),
    val custom: Map<String, List<String>> = emptyMap(),
) {
    /** Every required question has a chosen option or a non-blank answer of its own. */
    fun valid(questions: List<JsonObject>): Boolean = questions.all { q ->
        !q.flag("required") || selected[q.text("id")].orEmpty().isNotEmpty() || customValues(q.text("id")).isNotEmpty()
    }

    /**
     * The `questionnaire` answer: per question the chosen options in their listed order with their
     * 1-based index, then each distinct non-blank answer of its own. A question without either is
     * skipped.
     */
    fun wire(questions: List<JsonObject>): JsonObject {
        require(valid(questions))
        return Wire.objectOf(
            "kind" to "questionnaire",
            "cancelled" to false,
            "answers" to JsonArray(questions.map { q ->
                val id = q.text("id")
                val chosen = selected[id].orEmpty()
                val selections = q.array("options").mapIndexedNotNull { index, option ->
                    if (option.text("value") in chosen)
                        Wire.objectOf(
                            "value" to option.text("value"),
                            "label" to option.text("label"),
                            "index" to index + 1,
                            "wasCustom" to false,
                        )
                    else null
                } + customValues(id).map { Wire.objectOf("value" to it, "label" to it, "wasCustom" to true) }
                Wire.objectOf("id" to id, "skipped" to selections.isEmpty(), "selections" to JsonArray(selections))
            }),
        )
    }

    private fun customValues(id: String): List<String> = custom[id].orEmpty().filter(String::isNotBlank).distinct()

    companion object {
        /** The answers a questionnaire starts with: its default options and custom defaults. */
        fun defaults(questions: List<JsonObject>): QuestionnaireAnswers {
            val defaults = questions.associate { it.text("id") to questionDefaults(it) }
            return QuestionnaireAnswers(
                defaults.mapValues { it.value.first },
                defaults.mapValues { it.value.second },
            )
        }

        /**
         * The phone form's state as answers: its chosen options, the custom defaults it still
         * keeps and the text typed under Other, in that order.
         */
        fun of(
            selected: Map<String, Set<String>>,
            customDefaults: Map<String, List<String>>,
            typed: Map<String, String>,
        ): QuestionnaireAnswers =
            QuestionnaireAnswers(
                selected,
                (customDefaults.keys + typed.keys).associateWith { id ->
                    customDefaults[id].orEmpty() + listOfNotNull(typed[id])
                },
            )
    }
}
