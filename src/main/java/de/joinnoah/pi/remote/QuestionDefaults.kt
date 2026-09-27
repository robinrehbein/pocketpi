package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

fun questionDefaults(question: JsonObject): Pair<Set<String>, List<String>> {
    val options = question.array("options")
    val choices = linkedSetOf<String>()
    val custom = linkedSetOf<String>()
    for (entry in question.getValue("defaults").jsonArray) {
        val value = entry.jsonPrimitive.content
        val option = options.firstOrNull { it.text("value") == value || it.text("label") == value }
        if (option != null) choices += option.text("value")
        else if (question.flag("allowOther")) custom += value
    }
    return choices to custom.toList()
}
