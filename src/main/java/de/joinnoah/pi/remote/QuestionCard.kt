package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

private class QuestionCallbackFence { var active = false }

@Composable
internal fun QuestionCard(
    question: JsonObject,
    enabled: Boolean,
    pending: Boolean = false,
    block: QuestionBlock? = null,
    details: QuestionDetailsView? = null,
    onLoadDetails: () -> Unit = {},
    editorScope: Any? = null,
    editorAvailable: Boolean = true,
    pagedEditor: PagedEditorView? = null,
    answerQuarantined: Boolean = false,
    journalState: PagedEditorView? = null,
    onLoadPagedEditor: () -> Unit = {},
    onEditPagedEditor: (String) -> Unit = {},
    onSubmitPagedEditor: (String) -> Unit = {},
    onRetryPagedEditorStorage: () -> Unit = {},
    onAnswer: (JsonObject) -> Unit,
) {
    val id = question.text("id")
    val kind = question.text("kind")
    val callbackFence = remember(EditorQuestionKey(question), editorScope) { QuestionCallbackFence() }
    SideEffect { callbackFence.active = true }
    DisposableEffect(callbackFence) { onDispose { callbackFence.active = false } }
    val storage = journalState ?: pagedEditor
    val paged = kind == "editor" && pagedEditor != null && question.containsKey("editorUploadGrantId")
    val baseControlsEnabled = enabled && !pending && (block == null || pagedEditor?.quarantined == true && block == QuestionBlock.UNCERTAIN)
    val controlsEnabled = baseControlsEnabled && (paged || !answerQuarantined) && storage?.storagePending != true && storage?.storageFailed != true
    val cancelEnabled = enabled && !pending && (block == null || block == QuestionBlock.UNCERTAIN && pagedEditor?.quarantined == true)
    val currentControlsEnabled by rememberUpdatedState(controlsEnabled)
    val currentCancelEnabled by rememberUpdatedState(cancelEnabled)
    val currentAnswerBlocked by rememberUpdatedState(answerQuarantined || pagedEditor?.quarantined == true || storage?.storagePending == true || storage?.storageFailed == true)
    val currentStorageRetry by rememberUpdatedState(storage?.storageRetryAvailable == true && !storage.storagePending)
    val currentEditorAvailable by rememberUpdatedState(editorAvailable)
    val currentPagedSendAvailable by rememberUpdatedState(pagedEditor?.let { !it.quarantined && !it.capacityFull && !it.loading && !it.storagePending && !it.storageFailed } == true)
    fun submit(answer: JsonObject) {
        if (!callbackFence.active) return
        val cancel = answer.optionalText("kind") == "cancel" || kind == "plan" && answer.optionalText("kind") == "plan" && answer.optionalText("action") == "cancel"
        if (if (cancel) currentCancelEnabled else currentControlsEnabled && !currentAnswerBlocked) onAnswer(answer)
    }
    key(EditorQuestionKey(question)) {
    Section(if (kind == "plan") stringResource(R.string.remote_plan_title) else question.optionalText("title") ?: stringResource(R.string.remote_questions)) {
        if (storage?.storagePending == true) Text(stringResource(R.string.remote_paged_editor_storage_pending), Modifier.testTag("pagedEditorStoragePending"))
        if (storage?.storageFailed == true) Text(stringResource(R.string.remote_paged_editor_storage_failed), Modifier.testTag("pagedEditorStorageFailure"), color = MaterialTheme.colorScheme.error)
        if (storage?.storageRetryAvailable == true) TextButton(
            onClick = { if (callbackFence.active && currentStorageRetry) onRetryPagedEditorStorage() },
            enabled = !storage.storagePending, modifier = Modifier.testTag("pagedEditorStorageRetry"),
        ) { Text(stringResource(R.string.remote_paged_editor_storage_retry)) }
        if (answerQuarantined && !paged && block != QuestionBlock.UNCERTAIN)
            Text(stringResource(R.string.remote_question_answer_uncertain), Modifier.testTag("questionJournalQuarantine"))
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
                    enabled = cancelEnabled,
                ) { Text(stringResource(R.string.remote_cancel)) }
            }
            "confirm" -> {
                val escalation = remember(question) { confirmEscalation(question) }
                if (escalation != null) EscalationDetails(escalation)
                Text(question.text("message"))
                val hasDetails = question.containsKey("details")
                if (hasDetails) {
                    if (details?.text != null) {
                        val chunks = remember(details.text) {
                            val text = details.text
                            buildList {
                                var start = 0
                                while (start < text.length) {
                                    var end = minOf(start + 4096, text.length)
                                    if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                                    add(text.substring(start, end))
                                    start = end
                                }
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            items(chunks) { Text(it) }
                        }
                    } else {
                        Text(stringResource(if (details?.failed == true) R.string.remote_question_details_failed else R.string.remote_question_details_needed))
                        OutlinedButton(onClick = onLoadDetails, enabled = controlsEnabled && details?.loading != true) {
                            Text(stringResource(if (details?.loading == true) R.string.remote_question_details_loading else if (details?.failed == true) R.string.remote_question_details_retry else R.string.remote_question_details_load))
                        }
                    }
                }
                Row {
                    // An escalation's yes runs the blocked call once; the phone offers no session allow.
                    Button(
                        onClick = { submit(Wire.objectOf("kind" to "confirm", "value" to true)) },
                        enabled = controlsEnabled && (!hasDetails || details?.text != null),
                    ) {
                        Text(
                            stringResource(
                                if (escalation != null) R.string.remote_escalation_yes else R.string.remote_yes
                            )
                        )
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
            "editor" -> {
                val prefill = editorPrefill(question)
                if (pagedEditor != null && question.containsKey("editorUploadGrantId")) {
                  key(EditorQuestionKey(question), editorScope) {
                    val pagedCallbackFence = remember { QuestionCallbackFence() }
                    SideEffect { pagedCallbackFence.active = true }
                    DisposableEffect(pagedCallbackFence) { onDispose { pagedCallbackFence.active = false } }
                    fun active() = pagedCallbackFence.active
                    val verified = pagedEditor.text
                    val currentLoadAvailable by rememberUpdatedState(verified == null && !pagedEditor.loading && !pagedEditor.storagePending && controlsEnabled && editorAvailable)
                    if (pagedEditor.quarantined && block != QuestionBlock.UNCERTAIN) Text(stringResource(R.string.remote_question_answer_uncertain))
                    if (verified == null) {
                        OutlinedButton(onClick = { if (active() && currentLoadAvailable) onLoadPagedEditor() }, enabled = controlsEnabled && editorAvailable && !pagedEditor.loading && !pagedEditor.storagePending) {
                            Text(stringResource(if (pagedEditor.loading) R.string.remote_question_details_loading else R.string.remote_paged_editor_load))
                        }
                        if (pagedEditor.failed) Text(stringResource(R.string.remote_question_details_failed))
                    } else {
                        val editorKey = EditorQuestionKey(question)
                        var text by remember(editorKey, editorScope) { mutableStateOf(verified) }
                        var window by remember(editorKey, editorScope) { mutableStateOf(EditorWindows.atOffset(verified, 0)) }
                        var field by remember(editorKey, editorScope) { mutableStateOf(TextFieldValue(verified.substring(window.start, window.end))) }
                        var section by remember(editorKey, editorScope) { mutableStateOf("1") }
                        var fieldSession by remember(editorKey, editorScope) { mutableIntStateOf(0) }
                        var fieldFocused by remember(editorKey, editorScope) { mutableStateOf(false) }
                        var restoreFieldFocus by remember(editorKey, editorScope) { mutableStateOf(false) }
                        fun restartField() {
                            restoreFieldFocus = fieldFocused
                            fieldSession++
                        }
                        val editEnabled = controlsEnabled && !pagedEditor.loading && !pagedEditor.storagePending
                        val currentEditEnabled by rememberUpdatedState(editEnabled)
                        fun canEdit() = active() && currentEditEnabled
                        val validDraft = remember(text) { validEditorText(text, MAX_EDITOR_UPLOAD_BYTES) }
                        fun showWindow(next: EditorWindow, selection: TextRange = TextRange(next.start), composition: TextRange? = null, requestedSection: Int? = null) {
                            if (!canEdit()) return
                            fun map(offset: Int) = (offset - next.start).coerceIn(0, next.end - next.start)
                            val mappedComposition = composition?.takeIf { it.min >= next.start && it.max <= next.end }?.let { TextRange(map(it.start), map(it.end)) }
                            window = next
                            field = TextFieldValue(text.substring(next.start, next.end), TextRange(map(selection.start), map(selection.end)), mappedComposition)
                            section = (requestedSection ?: ((next.start / EditorWindows.MAX_UNITS) + 1)).toString()
                        }
                        fun replaceWindow(value: TextFieldValue) {
                            if (!canEdit()) return
                            if (value.text == field.text) { field = value; return }
                            val old = window
                            val updated = EditorWindows.replace(text, old, value.text)
                            val absoluteSelection = TextRange(old.start + value.selection.start, old.start + value.selection.end)
                            val absoluteComposition = value.composition?.let { TextRange(old.start + it.start, old.start + it.end) }
                            // A large replacement changes the input connection's entire text basis.
                            // Fence callbacks from that connection before installing the new window.
                            if (value.text.length > EditorWindows.MAX_UNITS) restartField()
                            text = updated
                            onEditPagedEditor(updated)
                            val anchor = if (value.text.length > EditorWindows.MAX_UNITS) maxOf(0, absoluteSelection.end - EditorWindows.MAX_UNITS / 2) else old.start.coerceAtMost(updated.length)
                            showWindow(EditorWindows.atOffset(updated, anchor), absoluteSelection, absoluteComposition)
                        }
                        fun navigate(next: EditorWindow, requestedSection: Int? = null) {
                            if (!canEdit()) return
                            val selection = TextRange(window.start + field.selection.start, window.start + field.selection.end)
                            val composition = field.composition?.let { TextRange(window.start + it.start, window.start + it.end) }
                            restartField()
                            showWindow(next, selection, composition, requestedSection)
                        }
                        key(fieldSession) {
                            val session = fieldSession
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(session) {
                                if (restoreFieldFocus) {
                                    restoreFieldFocus = false
                                    focusRequester.requestFocus()
                                }
                            }
                            OutlinedTextField(value = field, onValueChange = { if (editEnabled && session == fieldSession) replaceWindow(it) }, enabled = editEnabled,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("questionEditorField")
                                    .focusRequester(focusRequester).onFocusChanged { fieldFocused = it.hasFocus }
                                    .then(rememberQuestionFieldKeys(text)), minLines = 4)
                        }
                        Text(stringResource(R.string.remote_paged_editor_position, window.start, window.end, text.length))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { if (canEdit()) navigate(EditorWindows.atOffset(text, 0)) }, enabled = editEnabled && window.start > 0, modifier = Modifier.testTag("pagedEditorFirst")) { Text(stringResource(R.string.remote_paged_editor_first)) }
                            TextButton(onClick = { if (canEdit()) EditorWindows.previous(text, window)?.let { navigate(it) } }, enabled = editEnabled && window.start > 0, modifier = Modifier.testTag("pagedEditorPrevious")) { Text(stringResource(R.string.remote_paged_editor_previous)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { if (canEdit()) EditorWindows.next(text, window)?.let { navigate(it) } }, enabled = editEnabled && window.end < text.length, modifier = Modifier.testTag("pagedEditorNext")) { Text(stringResource(R.string.remote_paged_editor_next)) }
                            TextButton(onClick = { if (canEdit()) navigate(EditorWindows.last(text)) }, enabled = editEnabled && window.end < text.length, modifier = Modifier.testTag("pagedEditorLast")) { Text(stringResource(R.string.remote_paged_editor_last)) }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(value = section, onValueChange = { if (canEdit() && it.length <= 9 && it.all(Char::isDigit)) section = it }, enabled = editEnabled,
                                modifier = Modifier.weight(1f).testTag("pagedEditorSection"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.remote_paged_editor_section, EditorWindows.sectionCount(text))) })
                            val requested = section.toIntOrNull()
                            TextButton(onClick = { if (canEdit()) requested?.let { navigate(EditorWindows.atSection(text, it), it) } }, enabled = editEnabled && requested != null && requested in 1..EditorWindows.sectionCount(text), modifier = Modifier.testTag("pagedEditorJump")) { Text(stringResource(R.string.remote_paged_editor_jump)) }
                        }
                        if (pagedEditor.retentionWarning) Text(stringResource(R.string.remote_paged_editor_retention_warning))
                        if (pagedEditor.capacityFull && !pagedEditor.quarantined) Text(stringResource(R.string.remote_paged_editor_capacity_full))
                        pagedEditor.recoveryDraft?.let { recovery ->
                            TextButton(onClick = { if (canEdit()) { restartField(); text = recovery; onEditPagedEditor(recovery); showWindow(EditorWindows.atOffset(recovery, 0)) } }, enabled = editEnabled) {
                                Text(stringResource(R.string.remote_paged_editor_restore))
                            }
                        }
                        Button(enabled = editEnabled && editorAvailable && !pagedEditor.quarantined && !pagedEditor.capacityFull && !pagedEditor.storagePending && !pagedEditor.storageFailed && validDraft,
                            onClick = { if (active() && currentControlsEnabled && currentPagedSendAvailable && currentEditorAvailable && validEditorText(text, MAX_EDITOR_UPLOAD_BYTES)) onSubmitPagedEditor(text) }) {
                            Text(stringResource(R.string.remote_send))
                        }
                    }
                    TextButton(onClick = { if (active()) submit(Wire.objectOf("kind" to "cancel")) }, enabled = cancelEnabled) {
                        Text(stringResource(R.string.remote_cancel))
                    }
                  }
                } else if (prefill == null) {
                    Text(stringResource(R.string.remote_local_required))
                } else {
                    var text by remember(EditorQuestionKey(question), editorScope) { mutableStateOf(prefill) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (controlsEnabled) text = it },
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("questionEditorField").then(rememberQuestionFieldKeys(text)),
                        minLines = 4,
                    )
                    Button(
                        enabled = controlsEnabled && editorAvailable && validEditorText(text, 65536),
                        onClick = {
                            if (currentEditorAvailable && validEditorText(text, 65536)) submit(Wire.objectOf("kind" to "editor", "value" to text))
                        },
                    ) { Text(stringResource(R.string.remote_send)) }
                }
            }
            "questionnaire" ->
                QuestionnaireForm(
                    question = question,
                    enabled = controlsEnabled,
                    pending = pending,
                    cancelEnabled = cancelEnabled,
                    onAnswer = ::submit,
                )
            else -> Text(stringResource(R.string.remote_local_required))
        }
        if (kind in setOf("confirm", "select", "input", "editor") && !(kind == "editor" && pagedEditor != null && question.containsKey("editorUploadGrantId")))
            TextButton(onClick = { submit(Wire.objectOf("kind" to "cancel")) }, enabled = cancelEnabled) {
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
}

@Composable
internal fun QuestionnaireForm(
    question: JsonObject,
    enabled: Boolean,
    pending: Boolean = false,
    compactHeader: Boolean = false,
    cancelEnabled: Boolean = enabled,
    onAnswer: (JsonObject) -> Unit,
) {
    val controlsEnabled = enabled && !pending
    val currentControlsEnabled by rememberUpdatedState(controlsEnabled)
    val currentCancelEnabled by rememberUpdatedState(cancelEnabled && !pending)
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
                onValueChange = { text ->
                    custom = custom + (id to text.take(4096))
                    // The shared Other rule, as on the watch: on a single choice the typed text
                    // replaces the chosen option and the custom defaults; on a multiple choice
                    // they stay. The typed text itself lives in [custom].
                    val kept = QuestionnaireAnswers(selected, customDefaults).other(q, "")
                    selected = kept.selected
                    customDefaults = kept.custom
                },
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
            // The watch sends the same encoding (WatchQuestionAnswers), defaults included. The
            // state can change after the last composition enabled this button, so check again.
            val answers = QuestionnaireAnswers.of(selected, customDefaults, custom)
            if (!answers.valid(questions)) return@Button
            onAnswer(answers.wire(questions))
        },
    ) {
        Text(
            stringResource(
                if (pending) R.string.remote_answer_sending else R.string.remote_answer
            )
        )
    }
    TextButton(
        onClick = { if (currentCancelEnabled) onAnswer(Wire.objectOf("kind" to "cancel")) },
        enabled = cancelEnabled && !pending,
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

/** The tool, subagent and reason of a call auto mode blocked, shown above the confirm's message. */
@Composable
private fun EscalationDetails(escalation: ConfirmEscalation) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.remote_escalation_tool, escalation.tool),
                style = MaterialTheme.typography.labelLarge,
            )
            escalation.child?.let { Text(stringResource(R.string.remote_escalation_child, it)) }
            escalation.reason.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.remote_escalation_reason, it))
            }
        }
    }
}
