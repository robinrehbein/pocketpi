package de.joinnoah.pi.remote

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material.Text
import kotlinx.serialization.json.*

@Composable
internal fun WatchQuestionScreen(model: WatchViewModel, state: RemoteState, question: JsonObject) {
    val id = question.text("id")
    val kind = question.text("kind")
    val repository = model.repository
    val enabled = state.connected && !state.loading && id !in state.answering
    var page by remember(id) { mutableIntStateOf(0) }
    var index by remember(id) { mutableIntStateOf(0) }
    var text by remember(id) { mutableStateOf("") }
    var selected by remember(id) { mutableStateOf<String?>(null) }
    var approve by remember(id) { mutableStateOf(false) }
    var customInput by remember(id, index) { mutableStateOf("") }
    val questions = if (kind == "questionnaire") question.array("questions") else emptyList()
    var answers by remember(id) { mutableStateOf(WatchQuestionAnswers.defaults(questions)) }
    fun submit(answer: JsonObject) { if (enabled) repository.answer(id, answer) }
    WatchList(
        question.optionalText("title") ?: stringResource(R.string.remote_questions),
        listOf(id, page, index, approve), true, model::back,
    ) {
        if (!enabled) notice(if (id in state.answering) R.string.remote_answer_sending else R.string.remote_offline)
        when (kind) {
            "plan" -> {
                if (approve) {
                    notice(R.string.wear_plan_approve_notice)
                    action(R.string.remote_plan_approve, enabled) {
                        submit(Wire.objectOf("kind" to "plan", "action" to "approve")); approve = false
                    }
                    action(R.string.remote_cancel) { approve = false }
                } else {
                    reader(question.text("plan"), page, { page = it })
                    action(R.string.remote_plan_approve, enabled) { approve = true }
                    action(R.string.remote_plan_keep, enabled) { submit(Wire.objectOf("kind" to "plan", "action" to "keep")) }
                    item { WatchEditor(text, { text = it }, stringResource(R.string.remote_plan_feedback), enabled,
                        dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
                    action(R.string.remote_plan_send_feedback, enabled && text.isNotBlank()) {
                        submit(Wire.objectOf("kind" to "plan", "action" to "change", "feedback" to text.trim()))
                    }
                }
            }
            "confirm" -> {
                reader(question.text("message"), page, { page = it })
                action(R.string.remote_yes, enabled) { submit(Wire.objectOf("kind" to "confirm", "value" to true)) }
                action(R.string.remote_no, enabled) { submit(Wire.objectOf("kind" to "confirm", "value" to false)) }
            }
            "select" -> {
                question.optionalText("message")?.let { paragraphs(it) }
                question.getValue("options").jsonArray.forEach { option ->
                    val value = option.jsonPrimitive.content
                    item { WatchAction(value, { selected = value }, enabled, selected = selected == value) }
                }
                selected?.let { paragraphs(it) }
                action(R.string.remote_answer, enabled && selected != null) {
                    submit(Wire.objectOf("kind" to "select", "value" to checkNotNull(selected)))
                }
            }
            "input" -> {
                question.optionalText("message")?.let { paragraphs(it) }
                item { WatchEditor(text, { text = it }, question.optionalText("placeholder") ?: stringResource(R.string.remote_answer), enabled,
                    dictationError = { repository.reportError(R.string.wear_dictation_unavailable) }) }
                action(R.string.remote_answer, enabled) { submit(Wire.objectOf("kind" to "input", "value" to text)) }
            }
            "questionnaire" -> {
                if (index < questions.size) {
                    val q = questions[index]
                    val qid = q.text("id")
                    item { Text(stringResource(R.string.wear_question_position, index + 1, questions.size)) }
                    paragraphs(q.text("prompt"))
                    q.optionalText("diagram")?.let { paragraphs(it) }
                    q.array("options").forEach { option ->
                        val checked = option.text("value") in answers.selected[qid].orEmpty()
                        item {
                            WatchAction(option.text("label"), {
                                answers = answers.choose(q, option.text("value"))
                                if (!q.flag("multiSelect")) customInput = ""
                            }, enabled,
                                selected = checked)
                        }
                        if (checked) {
                            paragraphs(option.text("label"))
                            option.optionalText("description")?.let { paragraphs(it) }
                        }
                    }
                    answers.custom[qid].orEmpty().forEach { custom ->
                        paragraphs(custom)
                        item { WatchAction(custom, {
                            answers = answers.copy(custom = answers.custom + (qid to answers.custom[qid].orEmpty().filterNot { it == custom }))
                            if (customInput == custom) customInput = ""
                        }, enabled,
                            secondary = stringResource(R.string.wear_remove)) }
                    }
                    if (q.flag("allowOther")) item {
                        WatchEditor(customInput, { text ->
                            answers = answers.editOther(q, text, customInput)
                            customInput = text
                        },
                            stringResource(R.string.remote_other), enabled, 4096,
                            dictationError = { repository.reportError(R.string.wear_dictation_unavailable) })
                    }
                    if (!q.flag("required")) action(R.string.remote_skip, enabled) { answers = answers.skip(qid); customInput = ""; index++ }
                    if (!answers.valid(listOf(q))) notice(R.string.remote_question_required)
                    action(R.string.wear_next, enabled && answers.valid(listOf(q))) { index++ }
                    if (index > 0) action(R.string.wear_previous) { index-- }
                } else {
                    notice(R.string.wear_review_answers)
                    questions.forEachIndexed { position, q ->
                        val qid = q.text("id")
                        val labels = q.array("options").filter { it.text("value") in answers.selected[qid].orEmpty() }.map { it.text("label") } + answers.custom[qid].orEmpty()
                        item { WatchAction(q.optionalText("label") ?: q.text("prompt"), { index = position }, secondary = labels.joinToString(", ").ifEmpty { stringResource(R.string.remote_skip) }) }
                    }
                    action(R.string.remote_answer, enabled && answers.valid(questions)) { submit(answers.wire(questions)) }
                }
            }
            else -> notice(R.string.remote_local_required)
        }
        if (kind != "local_required") action(R.string.wear_cancel_question, enabled) {
            submit(if (kind == "plan") Wire.objectOf("kind" to "plan", "action" to "cancel") else Wire.objectOf("kind" to "cancel"))
        }
    }
}
