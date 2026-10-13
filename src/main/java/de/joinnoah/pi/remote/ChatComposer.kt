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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** How long typing pauses before the composer gives its text to the repository. */
internal const val COMPOSER_DRAFT_DEBOUNCE_MILLIS = 300L

/** The largest draft the repository keeps, in UTF-8 bytes; longer edits are refused as before. */
private const val MAX_DRAFT_BYTES = 128 * 1024

/**
 * The composer's text while the user types. A keystroke changes only this state, so it recomposes
 * the composer and not the whole chat; the text reaches the repository through [push] after a
 * pause ([pushPending]) or at once on [flush]: before a send, on stop and when the chat changes.
 * Draft changes made elsewhere (a quote prompt, a prefill, a restored uncertain draft, the clear
 * after a send) come back through [sync] and replace the text, unless the user typed something
 * the repository has not seen yet: that typing wins, and the next push carries it.
 */
@Stable
internal class ComposerDraft(
    /** The chat the text belongs to; every push names it, so a late one never reaches another. */
    val selection: RemoteSelection,
    initial: String,
    private val push: (RemoteSelection, String) -> Unit,
) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set

    val text: String get() = value.text

    /** The repository's draft as [sync] last saw it. */
    private var observed = initial

    /** The text the repository holds as far as this composer knows: its last push or a synced change. */
    private var known = initial

    /** Pushed texts whose echo has not come back through [sync] yet, oldest first. */
    private val echoes = ArrayDeque<String>()

    /** A keyboard edit; a text over the draft limit is refused, as the repository would. */
    fun edit(next: TextFieldValue) {
        if (next.text != value.text && tooLong(next.text)) return
        value = next
    }

    /** Replaces the text with the cursor at its end: an accepted prediction or a voice result. */
    fun setText(text: String) {
        if (text != value.text) edit(TextFieldValue(text, TextRange(text.length)))
    }

    /** Replaces the text and pushes it at once, for a change the chat makes (a picked command). */
    fun replace(text: String) {
        setText(text)
        flush()
    }

    /** Pushes typing the repository has not seen; the debounce calls this after a pause. */
    fun pushPending() {
        if (text != known) send(text)
    }

    /**
     * Pushes the text unless the repository already holds it. [force] pushes it anyway, so a send
     * that follows reads exactly the text on screen.
     */
    fun flush(force: Boolean = false) {
        if (force) echoes.clear()
        if (force || text != known || text != observed) send(text)
    }

    private fun send(text: String) {
        known = text
        echoes.addLast(text)
        while (echoes.size > MAX_ECHOES) echoes.removeFirst()
        push(selection, text)
    }

    /** The repository's draft is now [draft]. */
    fun sync(draft: String) {
        if (draft == observed) return
        observed = draft
        // The repository's state arrives a frame late and conflated; an echo of an earlier push
        // must not undo what the user typed since.
        val echo = echoes.lastIndexOf(draft)
        if (echo >= 0) {
            repeat(echo + 1) { echoes.removeFirst() }
            return
        }
        echoes.clear()
        val typing = text != known
        known = draft
        if (!typing && draft != text) value = TextFieldValue(draft, TextRange(draft.length))
    }

    private fun tooLong(text: String) =
        text.length * 3 > MAX_DRAFT_BYTES && text.encodeToByteArray().size > MAX_DRAFT_BYTES

    private companion object {
        const val MAX_ECHOES = 16
    }
}

/**
 * The composer text of [selection], starting from the repository's [draft]. It follows later
 * changes of [draft], pushes typing through [push] after [COMPOSER_DRAFT_DEBOUNCE_MILLIS], and
 * flushes on stop and when [selection] changes or the composer leaves the screen.
 */
@Composable
internal fun rememberComposerDraft(
    selection: RemoteSelection,
    draft: String,
    push: (RemoteSelection, String) -> Unit,
): ComposerDraft {
    val latestPush by rememberUpdatedState(push)
    val composer = remember(selection) { ComposerDraft(selection, draft) { target, text -> latestPush(target, text) } }
    LaunchedEffect(composer, draft) { composer.sync(draft) }
    LaunchedEffect(composer) {
        snapshotFlow { composer.text }.collectLatest {
            delay(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
            composer.pushPending()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { composer.flush() }
    DisposableEffect(composer) { onDispose { composer.flush() } }
    return composer
}

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
    onStopChild: () -> Unit = {},
    onResumeChild: () -> Unit = {},
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
    /** A hardware keyboard is attached; only then do Enter and Ctrl+Enter send. */
    hardwareKeyboard: Boolean = false,
    /** The "Enter sends" setting; off, a hardware Enter inserts a newline as on screen. */
    enterSends: Boolean = false,
    /** Where typed text goes, naming the chat it was typed in; see [RemoteRepository.draft]. */
    onDraftFor: (RemoteSelection, String) -> Unit = { _, text -> onDraft(text) },
    /** The text being typed; the chat hoists it so its own draft changes can flush it first. */
    draft: ComposerDraft = rememberComposerDraft(state.selection, state.draft, onDraftFor),
) {
    // Everything below reads the text on screen, which runs ahead of the repository's draft
    // while the user types.
    val repositoryState = state
    @Suppress("NAME_SHADOWING")
    val state = remember(repositoryState, draft.text) { repositoryState.copy(draft = draft.text) }
    // A send reads the repository's draft, so it first gets exactly the text on screen.
    val flushed: (() -> Unit) -> () -> Unit = { action -> { draft.flush(force = true); action() } }
    @Suppress("NAME_SHADOWING") val onSend = flushed(onSend)
    @Suppress("NAME_SHADOWING") val onFollowUp = flushed(onFollowUp)
    @Suppress("NAME_SHADOWING") val onSteer = flushed(onSteer)
    @Suppress("NAME_SHADOWING") val onResumeChild = flushed(onResumeChild)
    val predictions = LocalPromptPredictions.current
    val childControls = childControlsAvailable(state)
    val control = childControl(state)
    val childBusy = control?.phase in setOf(ChildControlPhase.STOPPING, ChildControlPhase.RESUMING)
    var confirmChildStop by remember(state.selection.sessionId) { mutableStateOf(false) }
    // In a child the host can stop, Stop goes through the parent's subagent manager after a
    // confirmation, also while the composer offers a resume or asks a question; elsewhere it
    // aborts the run as before.
    if (confirmChildStop)
        AlertDialog(
            onDismissRequest = { confirmChildStop = false },
            title = { Text(stringResource(R.string.remote_child_stop_title)) },
            text = { Text(stringResource(R.string.remote_child_stop_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmChildStop = false
                        onStopChild()
                    },
                    modifier = Modifier.testTag("confirmChildStop"),
                ) { Text(stringResource(R.string.remote_child_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmChildStop = false }) {
                    Text(stringResource(R.string.remote_child_stop_dismiss))
                }
            },
        )
    if (state.questions.isNotEmpty()) {
        val abort: () -> Unit = if (childControls) ({ confirmChildStop = true }) else onAbort
        QuestionComposer(state, abort, abortBlocked = childControls && childBusy) { questionId, answer ->
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
    val childResume = offersChildResume(state)
    val childStatus = if (childControls) childControlStatus(state) else null
    val canQueue = state.connected && !state.loading && !state.sending &&
        !state.importingAttachments && !state.configurationChanging &&
        state.questions.isEmpty() && hasDraft &&
        !state.draft.trimStart().startsWith("/") && state.followUps.size < 64 &&
        !(childControls && state.attachments.isNotEmpty())
    val canResumeChild = childResume && state.connected && !state.loading && !state.sending &&
        !childBusy && state.draft.isNotBlank() && state.attachments.isEmpty() && state.quote == null &&
        !state.draft.trimStart().startsWith("/")
    val stop: () -> Unit = if (childControls) ({ confirmChildStop = true }) else onStop
    val send: () -> Unit = if (childResume) onResumeChild else onSend
    val showBusyAction = busyAction != null && hasDraft
    val voiceInput = rememberVoiceInputBinding(state.draft, draft::setText, voiceEngine)
    val shown = draft.value
    val prediction =
        rememberPromptPrediction(state).takeIf {
            state.draft.isEmpty() && state.attachments.isEmpty() && state.quote == null &&
                voiceInput.state !is VoiceInputState.Listening
        }
    val acceptPrediction: () -> Unit = {
        if (prediction != null && state.draft.isEmpty()) {
            voiceInput.userEdited(prediction)
            draft.setText(prediction)
        }
    }
    val latestAccept = rememberUpdatedState(acceptPrediction)
    // A hardware Enter sends what the primary button would send, but never stops a run or starts
    // voice input: with nothing to send it does nothing.
    val keyboardSend: () -> Unit = {
        when {
            showBusyAction -> if (canQueue) {
                recordPrompt()
                if (busyAction == BusyComposerAction.Steer) onSteer() else onFollowUp()
            }
            canSendDraft(state) && voiceInput.state !is VoiceInputState.Listening -> {
                recordPrompt()
                send()
            }
        }
    }
    val latestKeyboardSend = rememberUpdatedState(keyboardSend)
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
                    // Let horizontally scrolling controls reach the surface edge, rather than
                    // clipping them at the composer's inner padding.
                    .layout { measurable, constraints ->
                        val inset = 8.dp.roundToPx()
                        val placeable = measurable.measure(
                            constraints.offset(horizontal = inset * 2)
                        )
                        layout(constraints.constrainWidth(placeable.width - inset * 2), placeable.height) {
                            placeable.placeRelative(-inset, 0)
                        }
                    }
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
            if (childControls && (childResume || childStatus != null))
                ChildControlRow(
                    status = childStatus,
                    reason = control?.reason,
                    resume = childResume,
                    resumeEnabled = canResumeChild,
                    onResume = onResumeChild,
                )
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
                        onClick = stop,
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
                        if (it.text != draft.text) voiceInput.userEdited(it.text)
                        draft.edit(it)
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
                            .onPreviewKeyEvent { event ->
                                when (
                                    composerEnter(
                                        event.key,
                                        event.type == KeyEventType.KeyDown,
                                        ctrl = event.isCtrlPressed || event.isMetaPressed,
                                        shift = event.isShiftPressed,
                                        alt = event.isAltPressed,
                                        hardwareKeyboard = hardwareKeyboard,
                                        enterSends = enterSends,
                                    )
                                ) {
                                    ComposerEnter.DEFAULT -> false
                                    ComposerEnter.SEND -> {
                                        latestKeyboardSend.value()
                                        true
                                    }
                                    ComposerEnter.NEWLINE -> {
                                        val text = shown.text
                                        val start = shown.selection.min
                                        val next = text.substring(0, start) + "\n" + text.substring(shown.selection.max)
                                        voiceInput.userEdited(next)
                                        draft.edit(TextFieldValue(next, TextRange(start + 1)))
                                        true
                                    }
                                }
                            }
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
                        send()
                    },
                    onStop = stop,
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

/** The draft can be sent now; the same rule the primary button follows. */
internal fun canSendDraft(state: RemoteState): Boolean =
    state.connected &&
        !state.loading &&
        state.status == "idle" &&
        !state.sending &&
        !state.importingAttachments &&
        !state.configurationChanging &&
        (state.draft.isNotBlank() || state.attachments.isNotEmpty())

/** What the child control row says about the selected subagent child, or null for nothing. */
internal fun childControlStatus(state: RemoteState): Int? {
    val running = state.status in setOf("running", "waiting")
    return when (childControl(state)?.phase) {
        ChildControlPhase.STOPPING -> R.string.remote_child_stopping
        ChildControlPhase.RESUMING -> R.string.remote_child_resuming
        ChildControlPhase.STOPPED_BY_YOU ->
            if (running) null else R.string.remote_child_stopped_by_you
        // After the resumed run ends, the next message resumes the child again.
        ChildControlPhase.RESUMED -> if (running) null else R.string.remote_child_idle
        ChildControlPhase.NOT_RUNNING -> R.string.remote_child_not_running
        ChildControlPhase.REFUSED -> R.string.remote_child_refused_plain
        ChildControlPhase.NOT_FOUND -> R.string.remote_child_not_found
        ChildControlPhase.UNCERTAIN -> R.string.remote_child_uncertain
        null -> if (running) null else R.string.remote_child_idle
    }
}

@Composable
private fun ChildControlRow(
    status: Int?,
    reason: String?,
    resume: Boolean,
    resumeEnabled: Boolean,
    onResume: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("childControls"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == null) Spacer(Modifier.weight(1f))
        else
            Text(
                if (status == R.string.remote_child_refused_plain && reason != null)
                    stringResource(R.string.remote_child_refused, reason)
                else stringResource(status),
                modifier =
                    Modifier.weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("childControlStatus"),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        if (resume)
            TextButton(
                onClick = onResume,
                enabled = resumeEnabled,
                modifier = Modifier.testTag("resumeChild"),
            ) { Text(stringResource(R.string.remote_child_resume)) }
    }
}

internal enum class BusyComposerAction { Steer, FollowUp;
    fun other() = if (this == Steer) FollowUp else Steer
}

@Composable
private fun QuestionComposer(
    state: RemoteState,
    onAbort: () -> Unit,
    /** A child stop or resume is in flight; like the strip's Stop, the abort waits for it. */
    abortBlocked: Boolean = false,
    onAnswer: (String, kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val abortEnabled =
        !abortBlocked &&
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
