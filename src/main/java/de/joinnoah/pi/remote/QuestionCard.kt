package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@Composable
internal fun QuestionCard(
    question: JsonObject,
    enabled: Boolean,
    pending: Boolean = false,
    onAnswer: (JsonObject) -> Unit,
) {
    val id = question.text("id")
    val kind = question.text("kind")
    fun submit(answer: JsonObject) {
        onAnswer(answer)
    }
    Section(if (kind == "plan") stringResource(R.string.remote_plan_title) else question.optionalText("title") ?: stringResource(R.string.remote_questions)) {
        when (kind) {
            "plan" -> {
                var editing by remember(id) { mutableStateOf(false) }
                var feedback by remember(id) { mutableStateOf("") }
                Text(stringResource(R.string.remote_plan_intro))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        MarkdownText(question.text("plan"))
                    }
                }
                if (editing) {
                    OutlinedTextField(
                        value = feedback,
                        onValueChange = { feedback = it.take(16384) },
                        enabled = enabled && !pending,
                        label = { Text(stringResource(R.string.remote_plan_feedback)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Button(
                        onClick = {
                            submit(Wire.objectOf(
                                "kind" to "plan",
                                "action" to "change",
                                "feedback" to feedback.trim(),
                            ))
                        },
                        enabled = enabled && !pending && feedback.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_send_feedback)) }
                } else {
                    Button(
                        onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "approve")) },
                        enabled = enabled && !pending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_approve)) }
                    OutlinedButton(
                        onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "keep")) },
                        enabled = enabled && !pending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_keep)) }
                    TextButton(onClick = { editing = true }, enabled = enabled && !pending) {
                        Text(stringResource(R.string.remote_plan_change))
                    }
                }
                TextButton(
                    onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "cancel")) },
                    enabled = enabled && !pending,
                ) { Text(stringResource(R.string.remote_cancel)) }
            }
            "confirm" -> {
                Text(question.text("message"))
                Row {
                    Button(
                        onClick = { submit(Wire.objectOf("kind" to "confirm", "value" to true)) },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.remote_yes))
                    }
                    TextButton(
                        onClick = { submit(Wire.objectOf("kind" to "confirm", "value" to false)) },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.remote_no))
                    }
                }
            }
            "select" -> {
                var selected by remember(id) { mutableStateOf<String?>(null) }
                question.getValue("options").jsonArray.forEach { option ->
                    val value = option.jsonPrimitive.content
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = selected == value,
                                enabled = enabled,
                                role = Role.RadioButton,
                            ) { selected = value }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = null, enabled = enabled)
                        Text(value, Modifier.padding(start = 12.dp))
                    }
                }
                Button(
                    onClick = {
                        submit(Wire.objectOf("kind" to "select", "value" to checkNotNull(selected)))
                    },
                    enabled = enabled && selected != null,
                ) { Text(stringResource(R.string.remote_answer)) }
            }
            "input" -> {
                var text by remember(id) { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(16384) },
                    enabled = enabled,
                    label = {
                        Text(
                            question.optionalText("placeholder")
                                ?: stringResource(R.string.remote_answer)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { submit(Wire.objectOf("kind" to "input", "value" to text)) },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.remote_answer))
                }
            }
            "questionnaire" ->
                QuestionnaireForm(
                    question = question,
                    enabled = enabled,
                    pending = pending,
                    onAnswer = ::submit,
                )
            "local_required" -> Text(stringResource(R.string.remote_local_required))
        }
        if (kind != "local_required" && kind != "questionnaire" && kind != "plan")
            TextButton(onClick = { submit(Wire.objectOf("kind" to "cancel")) }, enabled = enabled) {
                Text(stringResource(R.string.remote_cancel))
            }
    }
}

@Composable
internal fun QuestionnaireForm(
    question: JsonObject,
    enabled: Boolean,
    pending: Boolean = false,
    compactHeader: Boolean = false,
    onAnswer: (JsonObject) -> Unit,
) {
    val controlsEnabled = enabled && !pending
    val questions = question.array("questions")
    var selected by
        remember(question.text("id")) {
            mutableStateOf(questions.associate { it.text("id") to questionDefaults(it).first })
        }
    var customDefaults by
        remember(question.text("id")) {
            mutableStateOf(questions.associate { it.text("id") to questionDefaults(it).second })
        }
    var custom by remember(question.text("id")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var customSelected by
        remember(question.text("id")) {
            mutableStateOf(
                questions.associate { q -> q.text("id") to questionDefaults(q).second.isNotEmpty() }
            )
        }
    if (compactHeader)
        Text(
            question.optionalText("title") ?: stringResource(R.string.remote_questions),
            style = MaterialTheme.typography.titleMedium,
        )
    questions.forEach { q ->
        val id = q.text("id")
        val recommended = questionDefaults(q).first
        QuestionPrompt(q.text("prompt"))
        q.array("options").forEach { option ->
            val checked = option.text("value") in selected[id].orEmpty()
            val optionModifier =
                Modifier.fillMaxWidth().then(
                    if (q.flag("multiSelect"))
                        Modifier.toggleable(
                            value = checked,
                            enabled = controlsEnabled,
                            role = Role.Checkbox,
                        ) {
                            value ->
                            val choices = selected[id].orEmpty().toMutableSet()
                            if (value) choices.add(option.text("value"))
                            else choices.remove(option.text("value"))
                            selected = selected + (id to choices)
                        }
                    else
                        Modifier.selectable(
                            selected = checked,
                            enabled = controlsEnabled,
                            role = Role.RadioButton,
                        ) {
                            selected = selected + (id to setOf(option.text("value")))
                            custom = custom - id
                            customDefaults = customDefaults - id
                            customSelected = customSelected + (id to false)
                        }
                ).padding(vertical = 8.dp)
            Row(optionModifier) {
                if (q.flag("multiSelect"))
                    Checkbox(checked, onCheckedChange = null, enabled = controlsEnabled)
                else RadioButton(checked, onClick = null, enabled = controlsEnabled)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(option.text("label"))
                    if (option.text("value") in recommended)
                        Text(
                            stringResource(R.string.remote_recommended),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    option.optionalText("description")?.let { description ->
                        if (hasFencedDiagram(description)) MarkdownText(description)
                        else Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        customDefaults[id].orEmpty().forEach { value ->
            TextButton(
                onClick = {
                    customDefaults =
                        customDefaults +
                            (id to customDefaults[id].orEmpty().filterNot { it == value })
                },
                enabled = controlsEnabled,
            ) {
                Text(stringResource(R.string.remote_remove_answer, value))
            }
        }
        if (q.flag("allowOther")) {
            val ownAnswerSelected = customSelected[id] == true
            val ownAnswerModifier =
                if (q.flag("multiSelect"))
                    Modifier.toggleable(
                        value = ownAnswerSelected,
                        enabled = controlsEnabled,
                        role = Role.Checkbox,
                        onValueChange = { selectedOwn ->
                            customSelected = customSelected + (id to selectedOwn)
                            if (!selectedOwn) {
                                custom = custom - id
                                customDefaults = customDefaults - id
                            }
                        }
                    )
                else
                    Modifier.selectable(
                        selected = ownAnswerSelected,
                        enabled = controlsEnabled,
                        role = Role.RadioButton,
                    ) {
                        selected = selected + (id to emptySet())
                        customDefaults = customDefaults - id
                        customSelected = customSelected + (id to true)
                    }
            Row(
                Modifier.fillMaxWidth().then(ownAnswerModifier).padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (q.flag("multiSelect"))
                    Checkbox(
                        checked = ownAnswerSelected,
                        onCheckedChange = null,
                        enabled = controlsEnabled,
                    )
                else RadioButton(selected = ownAnswerSelected, onClick = null, enabled = controlsEnabled)
                Text(stringResource(R.string.remote_other), Modifier.padding(start = 12.dp))
            }
            if (ownAnswerSelected)
                OutlinedTextField(
                    value = custom[id].orEmpty(),
                    onValueChange = { custom = custom + (id to it.take(4096)) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.remote_other)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            if (ownAnswerSelected)
                TextButton(
                    onClick = {
                        custom = custom - id
                        customDefaults = customDefaults - id
                        customSelected = customSelected + (id to false)
                    },
                    enabled = controlsEnabled,
                ) { Text(stringResource(R.string.remote_remove_answer, stringResource(R.string.remote_other))) }
        }
        if (!q.flag("required"))
            TextButton(
                onClick = {
                    selected = selected + (id to emptySet())
                    custom = custom - id
                    customDefaults = customDefaults - id
                    customSelected = customSelected + (id to false)
                },
                enabled = controlsEnabled,
            ) {
                Text(stringResource(R.string.remote_skip))
            }
    }
    val valid = questions.all {
        !it.flag("required") ||
            selected[it.text("id")].orEmpty().isNotEmpty() ||
            custom[it.text("id")].orEmpty().isNotBlank() ||
            customDefaults[it.text("id")].orEmpty().isNotEmpty()
    }
    if (!valid)
        Text(
            stringResource(R.string.remote_question_required),
            style = MaterialTheme.typography.bodySmall,
        )
    Button(
        enabled = controlsEnabled && valid,
        onClick = {
            val answers = questions.map { q ->
                val selections =
                    q.array("options")
                        .mapIndexedNotNull { index, o ->
                            if (o.text("value") in selected[q.text("id")].orEmpty())
                                Wire.objectOf(
                                    "value" to o.text("value"),
                                    "label" to o.text("label"),
                                    "index" to index + 1,
                                    "wasCustom" to false,
                                )
                            else null
                        }
                        .toMutableList()
                customDefaults[q.text("id")].orEmpty().forEach {
                    selections += Wire.objectOf("value" to it, "label" to it, "wasCustom" to true)
                }
                custom[q.text("id")]
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        selections +=
                            Wire.objectOf("value" to it, "label" to it, "wasCustom" to true)
                    }
                Wire.objectOf(
                    "id" to q.text("id"),
                    "skipped" to selections.isEmpty(),
                    "selections" to JsonArray(selections),
                )
            }
            onAnswer(
                Wire.objectOf(
                    "kind" to "questionnaire",
                    "cancelled" to false,
                    "answers" to JsonArray(answers),
                )
            )
        },
    ) {
        Text(
            stringResource(
                if (pending) R.string.remote_answer_sending else R.string.remote_answer
            )
        )
    }
    TextButton(
        onClick = { onAnswer(Wire.objectOf("kind" to "cancel")) },
        enabled = controlsEnabled,
    ) {
        Text(stringResource(R.string.remote_cancel))
    }
}

/**
 * Splits a questionnaire prompt into its question text and an optional fenced tail. The host
 * appends a question's diagram as a ``` block (DEV-949), which [MarkdownText] draws monospaced and
 * horizontally scrollable.
 */
internal fun promptParts(prompt: String): Pair<String, String?> {
    // The host always appends the diagram last, so a code block the question itself contains stays
    // in the heading text instead of pulling the rest of the question into the block.
    val fence = prompt.lastIndexOf("\n\n```")
    return if (fence < 0) prompt to null else prompt.substring(0, fence) to prompt.substring(fence + 2)
}

internal fun hasFencedDiagram(description: String): Boolean {
    val open = Regex("^[ \\t]*```(?:mermaid)?[ \\t\\r]*$")
    val close = Regex("^[ \\t]*```[ \\t\\r]*$")
    var inside = false
    for (line in description.split('\n')) {
        if (inside && close.matches(line)) return true
        if (!inside && open.matches(line)) inside = true
    }
    return false
}

@Composable
private fun QuestionPrompt(prompt: String) {
    val (text, diagram) = promptParts(prompt)
    Text(text, style = MaterialTheme.typography.titleMedium)
    if (diagram != null) MarkdownText(diagram)
}
