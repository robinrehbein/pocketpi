package de.joinnoah.pi.remote

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun QuotePreview(
    quote: MessageQuote,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    quote.author
                        ?: stringResource(
                            if (quote.role == "user") R.string.remote_user
                            else if (quote.role == "tool") R.string.remote_tool
                            else R.string.remote_model_unknown
                        ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    quote.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRemove != null)
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, stringResource(R.string.remote_remove_quote))
                }
        }
    }
}

@Composable
internal fun ChatComposer(
    state: RemoteState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveQuote: () -> Unit,
    onFollowUp: () -> Unit = {},
    onSteer: () -> Unit = {},
    onDismissFollowUp: (String) -> Unit = {},
    onAbort: () -> Unit = onStop,
    onAnswer: (String, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> },
    leading: @Composable RowScope.() -> Unit = {},
    suggestions: @Composable ColumnScope.() -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onCancelAttachments: () -> Unit = {},
    onPickPhotos: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    voiceEngine: VoiceRecognitionEngine? = null,
    voicePermissionGrantedOverride: Boolean? = null,
    focusRequester: FocusRequester? = null,
) {
    val predictions = LocalPromptPredictions.current
    if (state.questions.isNotEmpty()) {
        QuestionComposer(state, onAbort) { questionId, answer ->
            val route = state.selection.routeId
            val session = state.selection.sessionId
            if (route != null && session != null && isPlanApproval(answer))
                predictions?.recordPlanApproval(route, session)
            onAnswer(questionId, answer)
        }
        return
    }
    // Remembers what the user sends, per project, for prompt predictions.
    val recordPrompt = {
        val route = state.selection.routeId
        val project = state.selection.projectId
        if (route != null && project != null) predictions?.record(route, project, state.draft)
    }
    var addMenu by remember { mutableStateOf(false) }
    var preferredBusyAction by remember(state.selection.sessionId) {
        mutableStateOf(BusyComposerAction.Steer)
    }
    val canSteerNow = state.status == "running" && canSteer(state)
    val canFollowUpNow = state.status == "running" && canFollowUp(state)
    val busyAction = when {
        !canSteerNow && !canFollowUpNow -> null
        canSteerNow && canFollowUpNow -> preferredBusyAction
        canSteerNow -> BusyComposerAction.Steer
        else -> BusyComposerAction.FollowUp
    }
    val hasDraft = state.draft.isNotBlank() || state.attachments.isNotEmpty()
    val canQueue = state.connected && !state.loading && !state.sending &&
        !state.importingAttachments && !state.configurationChanging &&
        state.questions.isEmpty() && hasDraft &&
        !state.draft.trimStart().startsWith("/") && state.followUps.size < 64
    val showBusyAction = busyAction != null && hasDraft
    val voiceInput = rememberVoiceInputBinding(state.draft, onDraft, voiceEngine)
    // The draft text stays the source of truth; only the selection is local, so an accepted
    // suggestion can put the cursor at its end.
    var field by remember(state.selection.sessionId) {
        mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length)))
    }
    val shown = field.copy(text = state.draft)
    val prediction =
        rememberPromptPrediction(state).takeIf {
            state.draft.isEmpty() && state.attachments.isEmpty() && state.quote == null &&
                voiceInput.state !is VoiceInputState.Listening
        }
    val acceptPrediction: () -> Unit = {
        if (prediction != null && state.draft.isEmpty()) {
            field = TextFieldValue(prediction, TextRange(prediction.length))
            voiceInput.userEdited(prediction)
            onDraft(prediction)
        }
    }
    val latestAccept = rememberUpdatedState(acceptPrediction)
    FloatingSurface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.quote?.let { QuotePreview(it, Modifier.fillMaxWidth(), onRemoveQuote) }
            if (state.attachments.isNotEmpty()) {
                AttachmentPreviewStrip(state.attachments, onRemoveAttachment)
            }
            if (state.importingAttachments || state.attachmentProgress != null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (state.importingAttachments) R.string.remote_attachment_importing
                                else R.string.remote_attachment_uploading
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (state.attachmentProgress == null)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        else
                            LinearProgressIndicator(
                                progress = { state.attachmentProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                    }
                    IconButton(onClick = onCancelAttachments) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.remote_cancel_attachment_work),
                        )
                    }
                }
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(
                        max = if (state.questions.any { it.text("kind") == "questionnaire" }) 360.dp
                        else 180.dp
                    )
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = suggestions,
            )
            VoiceInputStatus(voiceInput, state)
            if (state.followUps.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 112.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("followUpJournal"),
                ) {
                    state.followUps.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    when (entry.status) {
                                        "delivered" -> R.string.remote_follow_up_delivered
                                        "uncertain" -> R.string.remote_follow_up_uncertain
                                        "cancelled" -> R.string.remote_follow_up_cancelled
                                        else -> if (entry.delivery == "steer") R.string.remote_steer_accepted
                                            else R.string.remote_follow_up_pending
                                    },
                                    entry.text.take(100),
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (entry.status !in setOf("pending", "accepted")) {
                                IconButton(
                                    onClick = { onDismissFollowUp(entry.requestId) },
                                    modifier = Modifier.testTag("dismissFollowUp-${entry.requestId}"),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        stringResource(R.string.remote_follow_up_dismiss, entry.text.take(100)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (showBusyAction) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InputChip(
                        selected = true,
                        enabled = canSteerNow && canFollowUpNow,
                        onClick = { preferredBusyAction = busyAction.other() },
                        label = {
                            Text(stringResource(
                                if (busyAction == BusyComposerAction.Steer)
                                    R.string.remote_steer_short else R.string.remote_follow_up_short
                            ))
                        },
                        trailingIcon = {
                            if (canSteerNow && canFollowUpNow)
                                Icon(Icons.Default.SwapHoriz, stringResource(R.string.remote_switch_delivery))
                        },
                        modifier = Modifier.testTag("busyDeliveryMode"),
                    )
                    IconButton(
                        onClick = onStop,
                        enabled = state.connected && !state.loading,
                        modifier = Modifier.testTag("stopRun"),
                    ) {
                        Icon(Icons.Default.Stop, stringResource(R.string.remote_stop))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                leading()
                if (ATTACHMENTS_CAPABILITY in state.capabilities)
                    Box {
                        IconButton(
                            onClick = { addMenu = true },
                            enabled =
                                !state.loading &&
                                    !state.sending &&
                                    !state.importingAttachments &&
                                    state.attachments.size < 5,
                        ) {
                            Icon(Icons.Default.Add, stringResource(R.string.remote_add_attachment))
                        }
                        DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_pick_photos)) },
                                onClick = {
                                    addMenu = false
                                    onPickPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remote_pick_files)) },
                                onClick = {
                                    addMenu = false
                                    onPickFiles()
                                },
                            )
                        }
                    }
                TextField(
                    value = shown,
                    onValueChange = {
                        field = it
                        if (it.text != state.draft) {
                            voiceInput.userEdited(it.text)
                            onDraft(it.text)
                        }
                    },
                    placeholder = {
                        Text(
                            prediction ?: stringResource(R.string.remote_prompt),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("composerPlaceholder"),
                        )
                    },
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    modifier =
                        Modifier.weight(1f)
                            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                            .promptPrediction(
                                prediction,
                                stringResource(R.string.remote_prediction_use),
                                prediction?.let {
                                    stringResource(R.string.remote_prediction_announce, it)
                                }.orEmpty(),
                            ) { latestAccept.value() }
                            .testTag("composerField"),
                    maxLines = 6,
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(24.dp),
                )
                VoiceInput(
                    state = state,
                    binding = voiceInput,
                    onSend = {
                        recordPrompt()
                        onSend()
                    },
                    onStop = onStop,
                    busyAction = if (showBusyAction) busyAction else null,
                    busyActionEnabled = canQueue,
                    onBusyAction = {
                        recordPrompt()
                        if (busyAction == BusyComposerAction.Steer) onSteer() else onFollowUp()
                    },
                    onBusyActionSwipe = { swipeLeft ->
                        if (canSteerNow && canFollowUpNow)
                            preferredBusyAction = if (swipeLeft) BusyComposerAction.FollowUp
                            else BusyComposerAction.Steer
                    },
                    modifier = Modifier.padding(bottom = 6.dp).size(48.dp)
                        .testTag(
                            when (if (showBusyAction) busyAction else null) {
                                BusyComposerAction.Steer -> "steer"
                                BusyComposerAction.FollowUp -> "followUp"
                                null -> "primaryComposerAction"
                            }
                        ),
                    permissionGrantedOverride = voicePermissionGrantedOverride,
                )
            }
        }
    }
}

internal enum class BusyComposerAction { Steer, FollowUp;
    fun other() = if (this == Steer) FollowUp else Steer
}

@Composable
private fun QuestionComposer(
    state: RemoteState,
    onAbort: () -> Unit,
    onAnswer: (String, kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val abortEnabled =
        state.connected &&
            !state.loading &&
            state.answering.isEmpty() &&
            state.selection.sessionId != null
    FloatingSurface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.questions.forEach { question ->
                    val questionId = question.text("id")
                    QuestionCard(
                        question = question,
                        enabled = state.connected && questionId !in state.answering,
                        pending = questionId in state.answering,
                        onAnswer = { answer -> onAnswer(questionId, answer) },
                    )
                }
            }
            OutlinedButton(
                onClick = onAbort,
                enabled = abortEnabled,
                modifier = Modifier.fillMaxWidth().testTag("abortRun"),
            ) {
                Text(stringResource(R.string.remote_abort_run))
            }
        }
    }
}
