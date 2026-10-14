package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
    block: QuestionBlock? = null,
    onAnswer: (JsonObject) -> Unit,
) {
    val id = question.text("id")
    val kind = question.text("kind")
    val controlsEnabled = enabled && !pending && block == null
    val currentControlsEnabled by rememberUpdatedState(controlsEnabled)
    fun submit(answer: JsonObject) {
        if (currentControlsEnabled) onAnswer(answer)
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
                        onValueChange = { if (controlsEnabled) feedback = it.take(16384) },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.remote_plan_feedback)) },
                        modifier = Modifier.fillMaxWidth().then(rememberQuestionFieldKeys(feedback)),
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
                        enabled = controlsEnabled && feedback.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_send_feedback)) }
                } else {
                    Button(
                        onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "approve")) },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_approve)) }
                    OutlinedButton(
                        onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "keep")) },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.remote_plan_keep)) }
                    TextButton(onClick = { if (controlsEnabled) editing = true }, enabled = controlsEnabled) {
                        Text(stringResource(R.string.remote_plan_change))
                    }
                }
                TextButton(
                    onClick = { submit(Wire.objectOf("kind" to "plan", "action" to "cancel")) },
                    enabled = controlsEnabled,
                ) { Text(stringResource(R.string.remote_cancel)) }
            }
            "confirm" -> {
                Text(question.text("message"))
                Row {
                    Button(
                        onClick = { submit(Wire.objectOf("kind" to "confirm", "value" to true)) },
                        enabled = controlsEnabled,
                    ) {
                        Text(stringResource(R.string.remote_yes))
                    }
                    TextButton(
                        onClick = { submit(Wire.objectOf("kind" to "confirm", "value" to false)) },
                        enabled = controlsEnabled,
                    ) {
                        Text(stringResource(R.string.remote_no))
                    }
                }
            }
            "select" -> {
                var selected by remember(id) { mutableStateOf<String?>(null) }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    question.getValue("options").jsonArray.forEach { option ->
                        val value = option.jsonPrimitive.content
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    enabled = controlsEnabled,
                                    role = Role.RadioButton,
                                ) { if (controlsEnabled) selected = value }
                                .padding(vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == value, onClick = null, enabled = controlsEnabled)
                            Text(value, Modifier.padding(start = 12.dp))
                        }
                    }
                }
                Button(
                    onClick = {
                        submit(Wire.objectOf("kind" to "select", "value" to checkNotNull(selected)))
                    },
                    enabled = controlsEnabled && selected != null,
                ) { Text(stringResource(R.string.remote_answer)) }
            }
            "input" -> {
                var text by remember(id) { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (controlsEnabled) text = it.take(16384) },
                    enabled = controlsEnabled,
                    label = {
                        Text(
                            question.optionalText("placeholder")
                                ?: stringResource(R.string.remote_answer)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().then(rememberQuestionFieldKeys(text)),
                )
                Button(
                    onClick = { submit(Wire.objectOf("kind" to "input", "value" to text)) },
                    enabled = controlsEnabled,
                ) {
                    Text(stringResource(R.string.remote_answer))
                }
            }
            "questionnaire" ->
                QuestionnaireForm(
                    question = question,
                    enabled = controlsEnabled,
                    pending = pending,
                    onAnswer = ::submit,
                )
            else -> Text(stringResource(R.string.remote_local_required))
        }
        if (kind in setOf("confirm", "select", "input"))
            TextButton(onClick = { submit(Wire.objectOf("kind" to "cancel")) }, enabled = controlsEnabled) {
                Text(stringResource(R.string.remote_cancel))
            }
        when (block) {
            QuestionBlock.LOCAL_ONLY ->
                Text(
                    stringResource(R.string.remote_question_answer_on_mac),
                    style = MaterialTheme.typography.bodySmall,
                )
            QuestionBlock.UNCERTAIN ->
                Text(
                    stringResource(R.string.remote_question_answer_uncertain),
                    style = MaterialTheme.typography.bodySmall,
                )
            null -> Unit
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
    val currentControlsEnabled by rememberUpdatedState(controlsEnabled)
    val questions = question.array("questions")
    val initial = remember(question.text("id")) { QuestionnaireAnswers.defaults(questions) }
    var selected by remember(question.text("id")) { mutableStateOf(initial.selected) }
    var customDefaults by remember(question.text("id")) { mutableStateOf(initial.custom) }
    var custom by remember(question.text("id")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var customSelected by
        remember(question.text("id")) { mutableStateOf(initial.custom.mapValues { it.value.isNotEmpty() }) }
    if (compactHeader)
        Text(
            question.optionalText("title") ?: stringResource(R.string.remote_questions),
            style = MaterialTheme.typography.titleMedium,
        )
    questions.forEach { q ->
        val id = q.text("id")
        val recommended = questionDefaults(q).first
        QuestionPrompt(q.text("prompt"))
        val ownAnswerSelected = q.flag("allowOther") && customSelected[id] == true
        // Single choices form one radio group, so TalkBack reads each as one of the group.
        Column(
            if (q.flag("multiSelect")) Modifier else Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            }
        }
        if (ownAnswerSelected)
            OutlinedTextField(
                value = custom[id].orEmpty(),
                onValueChange = { custom = custom + (id to it.take(4096)) },
                enabled = controlsEnabled,
                label = { Text(stringResource(R.string.remote_other)) },
                modifier = Modifier.fillMaxWidth().then(rememberQuestionFieldKeys(custom[id].orEmpty())),
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
    val valid = QuestionnaireAnswers.of(selected, customDefaults, custom).valid(questions)
    if (!valid)
        Text(
            stringResource(R.string.remote_question_required),
            style = MaterialTheme.typography.bodySmall,
        )
    Button(
        enabled = controlsEnabled && valid,
        onClick = {
            if (!currentControlsEnabled) return@Button
            // The watch sends the same encoding (WatchQuestionAnswers), defaults included.
            onAnswer(QuestionnaireAnswers.of(selected, customDefaults, custom).wire(questions))
        },
    ) {
        Text(
            stringResource(
                if (pending) R.string.remote_answer_sending else R.string.remote_answer
            )
        )
    }
    TextButton(
        onClick = { if (currentControlsEnabled) onAnswer(Wire.objectOf("kind" to "cancel")) },
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
