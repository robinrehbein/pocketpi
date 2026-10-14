package de.joinnoah.pi.remote

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.handwriting.handwritingDetector
import androidx.compose.foundation.text.handwriting.handwritingHandler
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
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
 * the repository has not seen yet: that typing wins, and the next push carries it. Until the chat
 * is selected the text is not [editable], so typing never lands on top of a draft not shown yet.
 */
@Stable
internal class ComposerDraft(
    /** The chat the text belongs to; every push names it, so a late one never reaches another. */
    val selection: RemoteSelection,
    initial: String,
    private val push: (RemoteSelection, String) -> Unit,
) {
    private var current by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))

    var value: TextFieldValue
        get() = current
        private set(next) {
            // A changed text brings back the suggestions Esc closed.
            if (next.text != current.text) suggestionsClosedFor = null
            current = next
        }

    val text: String get() = value.text

    /** False until the chat is selected: edits are refused, so typing never lands on a draft not shown yet. */
    var editable = true

    /** The repository's draft as [sync] last saw it. */
    private var observed = initial

    /** The text the repository holds as far as this composer knows: its last push or a synced change. */
    private var known = initial

    /** Pushed texts whose echo has not come back through [sync] yet, oldest first. */
    private val echoes = ArrayDeque<String>()

    /**
     * A keyboard edit; false when it is refused: while the chat is not [editable], or for a text
     * over the draft limit, as the repository would refuse it. A selection-only change always applies.
     */
    fun edit(next: TextFieldValue): Boolean {
        if (next.text != value.text && (!editable || tooLong(next.text))) return false
        value = next
        return true
    }

    /** Replaces the text with the cursor at its end: an accepted prediction or a voice result. */
    fun setText(text: String): Boolean =
        text == value.text || edit(TextFieldValue(text, TextRange(text.length)))

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

    /** The text whose slash suggestions Esc closed, until the text changes. */
    private var suggestionsClosedFor by mutableStateOf<String?>(null)

    val suggestionsClosed: Boolean get() = suggestionsClosedFor == text

    fun closeSuggestions() {
        suggestionsClosedFor = text
    }

    /** Types [typed] over the selection, as a key press in the field would. */
    fun insert(typed: String): Boolean {
        val start = value.selection.min
        val next = text.substring(0, start) + typed + text.substring(value.selection.max)
        return edit(TextFieldValue(next, TextRange(start + typed.length)))
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
 * flushes on stop, when [selection] changes or the composer leaves the screen, and when it becomes
 * [editable] once the chat is selected. [push] must file a late write under the selection it
 * names, also once that chat is no longer shown (see [RemoteRepository.draft]).
 */
@Composable
internal fun rememberComposerDraft(
    selection: RemoteSelection,
    draft: String,
    editable: Boolean,
    push: (RemoteSelection, String) -> Unit,
): ComposerDraft {
    val latestPush by rememberUpdatedState(push)
    val composer = remember(selection) { ComposerDraft(selection, draft) { target, text -> latestPush(target, text) } }
    SideEffect { composer.editable = editable }
    LaunchedEffect(composer, draft) { composer.sync(draft) }
    // Text the repository does not hold yet (a push it could not take) goes again once it can.
    LaunchedEffect(composer, editable) { if (editable) composer.flush() }
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
    /** Where typed text goes when the caller does not hoist [draft]. */
    onDraft: (String) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveQuote: () -> Unit,
    onFollowUp: () -> Unit = {},
    onSteer: () -> Unit = {},
    onStopChild: () -> Unit = {},
    onResumeChild: () -> Unit = {},
    onAbort: () -> Unit = onStop,
    onAnswer: (String, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> },
    leading: @Composable RowScope.() -> Unit = {},
    suggestions: @Composable ColumnScope.() -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onCancelAttachments: () -> Unit = {},
    onPickPhotos: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    /**
     * Takes the images and files a paste brings (Ctrl+V, or "Paste image or file" in the + menu) as one
     * batch; null leaves every paste to the field.
     */
    onPasteAttachments: ((List<Picked>) -> Unit)? = null,
    /** Takes a photo for the draft; null where there is no camera. */
    onTakePhoto: (() -> Unit)? = null,
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
    draft: ComposerDraft = rememberComposerDraft(state.selection, state.draft, !state.loading, onDraftFor),
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
    // The layout's keys (Esc, Ctrl+., typing with nothing focused) reach the composer through this.
    val keyTarget = LocalChatKeyTarget.current
    if (keyTarget != null)
        LaunchedEffect(keyTarget.stopHint) {
            // The hint lasts as long as the second Esc may come.
            if (!keyTarget.stopHint) return@LaunchedEffect
            delay(ESCAPE_STOP_WINDOW_MILLIS)
            keyTarget.stopHint = false
        }
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
        val abortEnabled = !(childControls && childBusy) && questionAbortEnabled(state)
        if (keyTarget != null)
            SideEffect {
                keyTarget.running = abortEnabled
                keyTarget.stop = abort
                keyTarget.composerFocused = false
                keyTarget.composerBlank = true
                keyTarget.transientOpen = { false }
                keyTarget.type = {}
            }
        QuestionComposer(
            state, abort, abortBlocked = childControls && childBusy, stopHint = keyTarget?.stopHint == true,
        ) { questionId, answer ->
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
    val context = LocalContext.current
    val clipboardEmpty = stringResource(R.string.remote_drop_clipboard_empty)
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
    val voiceInput = rememberVoiceInputBinding(state.draft, { draft.setText(it) }, voiceEngine)
    val shown = draft.value
    // Esc closes the prediction until something is typed.
    var predictionClosed by remember(state.selection) { mutableStateOf(false) }
    val draftEmpty = state.draft.isEmpty()
    LaunchedEffect(draftEmpty) { if (!draftEmpty) predictionClosed = false }
    val prediction =
        rememberPromptPrediction(state).takeIf {
            state.draft.isEmpty() && state.attachments.isEmpty() && state.quote == null &&
                voiceInput.state !is VoiceInputState.Listening && !predictionClosed
        }
    val acceptPrediction: () -> Unit = {
        if (prediction != null && state.draft.isEmpty()) {
            if (draft.setText(prediction)) voiceInput.userEdited(prediction)
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
    // Alt+Enter sends with the choice the chip does not show, when both are offered; otherwise
    // the key stays the field's.
    val otherBusyActionOffered = showBusyAction && canQueue && canSteerNow && canFollowUpNow
    val otherBusyAction: () -> Unit = {
        if (otherBusyActionOffered) {
            recordPrompt()
            if (busyAction == BusyComposerAction.Steer) onFollowUp() else onSteer()
        }
    }
    val latestOtherBusyAction = rememberUpdatedState(otherBusyAction)
    // Up in an empty composer brings back this session's own earlier messages.
    val recall = remember(state.selection) { MessageRecall() }
    val recallable by rememberUpdatedState(repositoryState.messages)
    val recallStep: (Int) -> Boolean = { step ->
        val recalling = recall.recalling(draft.text)
        val text = recall.step(recallableMessages(recallable), draft.text, step)
        if (text != null && draft.setText(text)) voiceInput.userEdited(text)
        text != null || recalling
    }
    // Enter sends with the setting on, Ctrl+Enter always.
    val enterChord = KeyChord(Key.Enter, ctrl = !enterSends)
    if (keyTarget != null) {
        val running = state.status == "running" && state.connected && !state.loading
        SideEffect {
            keyTarget.running = running
            keyTarget.stop = stop
            keyTarget.composerBlank = !hasDraft
            // The suggestions count only while they show (SessionControls reports it).
            keyTarget.transientOpen = { (keyTarget.slashShown && !draft.suggestionsClosed) || prediction != null }
            keyTarget.closeTransient = {
                if (keyTarget.slashShown && !draft.suggestionsClosed) draft.closeSuggestions() else predictionClosed = true
            }
            keyTarget.type = { typed ->
                if (draft.insert(typed)) voiceInput.userEdited(draft.text)
                focusRequester?.let { runCatching { it.requestFocus() } }
            }
            keyTarget.draftEdited = { voiceInput.userEdited(draft.text) }
        }
        LaunchedEffect(keyTarget, running) { if (!running) keyTarget.stopHint = false }
    }
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
            if (keyTarget?.stopHint == true) EscapeStopHint()
            if (childControls && (childResume || childStatus != null))
                ChildControlRow(
                    status = childStatus,
                    reason = control?.reason,
                    resume = childResume,
                    resumeEnabled = canResumeChild,
                    onResume = onResumeChild,
                )
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
                    ShortcutTooltip(stringResource(R.string.remote_stop), Shortcut.STOP) {
                        IconButton(
                            onClick = stop,
                            enabled = state.connected && !state.loading,
                            modifier = Modifier.testTag("stopRun"),
                        ) {
                            Icon(Icons.Default.Stop, stringResource(R.string.remote_stop))
                        }
                    }
                }
            }
            Row(
                // Writing with the S Pen anywhere on the row, beside the field too, writes into it.
                Modifier.handwritingDetector { focusRequester?.let { runCatching { it.requestFocus() } } },
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                leading()
                if (ATTACHMENTS_CAPABILITY in state.capabilities)
                    Box {
                        ShortcutTooltip(stringResource(R.string.remote_add_attachment)) {
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
                            if (onTakePhoto != null)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remote_take_photo)) },
                                    onClick = {
                                        addMenu = false
                                        onTakePhoto()
                                    },
                                    modifier = Modifier.testTag("takePhoto"),
                                )
                            if (onPasteAttachments != null)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remote_drop_paste_image)) },
                                    onClick = {
                                        addMenu = false
                                        val pasted = clipboardIntake(context)
                                        if (pasted != null) onPasteAttachments(pasted.attachments)
                                        else Toast.makeText(context, clipboardEmpty, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("pasteAttachment"),
                                )
                        }
                    }
                TextField(
                    value = shown,
                    onValueChange = {
                        val typed = it.text != draft.text
                        if (draft.edit(it) && typed) voiceInput.userEdited(it.text)
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
                            .handwritingHandler()
                            .promptPrediction(
                                prediction,
                                stringResource(R.string.remote_prediction_use),
                                prediction?.let {
                                    stringResource(R.string.remote_prediction_announce, it)
                                }.orEmpty(),
                            ) { latestAccept.value() }
                            .onFocusChanged { keyTarget?.composerFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                // A paste of images or files attaches them; any other paste,
                                // text above all, stays the field's.
                                if (
                                    onPasteAttachments != null &&
                                        isPasteKey(
                                            event.key,
                                            event.type == KeyEventType.KeyDown,
                                            ctrl = event.isCtrlPressed || event.isMetaPressed,
                                            alt = event.isAltPressed,
                                            shift = event.isShiftPressed,
                                        )
                                ) {
                                    val pasted = clipboardIntake(context)
                                    if (pasted != null) {
                                        onPasteAttachments(pasted.attachments)
                                        if (pasted.text.isNotEmpty() && draft.insert(pasted.text))
                                            voiceInput.userEdited(draft.text)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                if (
                                    hardwareKeyboard && otherBusyActionOffered &&
                                        event.type == KeyEventType.KeyDown &&
                                        resolveShortcut(event, KeyContext.COMPOSER) == Shortcut.OTHER_BUSY_ACTION
                                ) {
                                    latestOtherBusyAction.value()
                                    return@onPreviewKeyEvent true
                                }
                                val target = keyTarget
                                val names = target?.slashNames.orEmpty()
                                val highlight = target?.slashHighlight ?: 0
                                val recalling = recall.recalling(draft.text)
                                val completion =
                                    composerKey(
                                        event.key,
                                        event.type == KeyEventType.KeyDown,
                                        ctrl = event.isCtrlPressed || event.isMetaPressed,
                                        alt = event.isAltPressed,
                                        shift = event.isShiftPressed,
                                        hardwareKeyboard = hardwareKeyboard,
                                        // A recalled "/…" message keeps Up and Down for the history.
                                        suggestionsOpen =
                                            target != null && target.slashShown && !draft.suggestionsClosed &&
                                                names.isNotEmpty() && !recalling,
                                        highlightTyped =
                                            commandName(draft.text)?.equals(names.getOrNull(highlight), ignoreCase = true) == true,
                                        predictionShown = prediction != null,
                                        draftEmpty = draft.text.isEmpty(),
                                        recalling = recalling,
                                    )
                                when (completion) {
                                    ComposerKey.DEFAULT -> Unit
                                    ComposerKey.SUGGESTION_UP, ComposerKey.SUGGESTION_DOWN -> {
                                        val step = if (completion == ComposerKey.SUGGESTION_UP) -1 else 1
                                        target?.slashHighlight = moveHighlight(highlight, names.size, step)
                                        return@onPreviewKeyEvent true
                                    }
                                    ComposerKey.SUGGESTION_RUN -> {
                                        target?.runSlash?.invoke(highlight)
                                        return@onPreviewKeyEvent true
                                    }
                                    ComposerKey.ACCEPT_PREDICTION -> {
                                        latestAccept.value()
                                        return@onPreviewKeyEvent true
                                    }
                                    ComposerKey.PREVIOUS_MESSAGE, ComposerKey.NEXT_MESSAGE ->
                                        return@onPreviewKeyEvent recallStep(if (completion == ComposerKey.PREVIOUS_MESSAGE) 1 else -1)
                                }
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
                                        if (draft.edit(TextFieldValue(next, TextRange(start + 1))))
                                            voiceInput.userEdited(next)
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
                // Names what the primary button does now, and its key.
                val busy = state.status in setOf("running", "waiting")
                val primaryTip: Pair<String, KeyChord?> =
                    when {
                        busy && showBusyAction ->
                            stringResource(
                                if (busyAction == BusyComposerAction.Steer) R.string.remote_steer else R.string.remote_follow_up
                            ) to enterChord
                        busy -> stringResource(R.string.remote_stop) to tooltipChord(Shortcut.STOP)
                        voiceInput.state is VoiceInputState.Listening -> stringResource(R.string.remote_voice_finish) to null
                        voiceInput.state is VoiceInputState.Finishing -> stringResource(R.string.remote_voice_cancel) to null
                        hasDraft -> stringResource(R.string.remote_send) to enterChord
                        else -> stringResource(R.string.remote_voice_start) to null
                    }
                ShortcutTooltip(primaryTip.first, chord = primaryTip.second) {
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

/** "Press Esc again to stop pi", after the first Esc while pi runs. */
@Composable
private fun EscapeStopHint() {
    Text(
        stringResource(R.string.remote_escape_stop_hint),
        modifier =
            Modifier.padding(horizontal = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("escapeStopHint"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

internal enum class BusyComposerAction { Steer, FollowUp;
    fun other() = if (this == Steer) FollowUp else Steer
}

/** The question composer's abort can run, apart from a child stop or resume in flight. */
private fun questionAbortEnabled(state: RemoteState): Boolean =
    state.connected && !state.loading && state.answering.isEmpty() && state.selection.sessionId != null

@Composable
private fun QuestionComposer(
    state: RemoteState,
    onAbort: () -> Unit,
    /** A child stop or resume is in flight; like the strip's Stop, the abort waits for it. */
    abortBlocked: Boolean = false,
    /** The first Esc asked for a second one to abort ([ChatKeyTarget.stopHint]). */
    stopHint: Boolean = false,
    onAnswer: (String, kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val abortEnabled = !abortBlocked && questionAbortEnabled(state)
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
                        block = state.questionBlocks[questionId],
                        onAnswer = { answer -> onAnswer(questionId, answer) },
                    )
                }
            }
            if (stopHint) EscapeStopHint()
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

/**
 * The height in pixels between the end of a list's last item and the start of its after-padding,
 * which is where the last item stops when the list is scrolled to its end; 0 while items reach
 * that far, so above 0 the list cannot scroll. A list without items is empty from where its
 * content starts.
 */
internal fun emptySpaceBelowList(info: androidx.compose.foundation.lazy.LazyListLayoutInfo): Int {
    val contentEnd = info.viewportEndOffset - info.afterContentPadding
    val last = info.visibleItemsInfo.lastOrNull() ?: return contentEnd.coerceAtLeast(0)
    if (last.index != info.totalItemsCount - 1) return 0
    return (contentEnd - (last.offset + last.size)).coerceAtLeast(0)
}
