package de.joinnoah.pi.remote

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.handwriting.handwritingDetector
import androidx.compose.foundation.text.handwriting.handwritingHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/** How long typing pauses before the composer gives its text to the repository. */
internal const val COMPOSER_DRAFT_DEBOUNCE_MILLIS = 300L

/** The largest draft the repository keeps, in UTF-8 bytes; longer edits are refused as before. */
internal const val MAX_DRAFT_BYTES = 128 * 1024

/**
 * The composer's text while the user types. A keystroke changes only this state, so it recomposes
 * the composer and not the whole chat; the text reaches the repository through [push] after a
 * pause ([pushPending]) or at once on [flush]: before a send, on stop and when the chat changes.
 * Draft changes made elsewhere (a quote prompt, a prefill, a restored uncertain draft, the clear
 * after a send) come back through [sync] and replace the text, unless the user typed something
 * the repository has not seen yet: that typing wins, and the next push carries it. Until the chat
 * is selected the text is not [editable], so typing never lands on top of a draft not shown yet.
 *
 * The text lives in [textState], which the text field shows and edits. What the user types or pastes
 * goes through [inputTransformation] first: it refuses a text the field may not take, and holds a
 * long paste back as [longPaste] for the user to attach as a file or paste as text.
 */
@Stable
internal class ComposerDraft(
    /** The chat the text belongs to; every push names it, so a late one never reaches another. */
    val selection: RemoteSelection,
    initial: String,
    private val push: (RemoteSelection, String) -> Unit,
) {
    /** The text the composer field shows and edits. */
    val textState = TextFieldState(initial, TextRange(initial.length))

    /**
     * Filters the user's own edits to [textState]. A programmatic edit ([apply]) does not pass it, so
     * what the composer sets is never refused or held back.
     */
    val inputTransformation = InputTransformation {
        val before = originalText.toString()
        val after = asCharSequence().toString()
        val inserted = insertedText(before, after)
        when {
            // The cursor moved, or only the selection changed.
            after == before -> Unit
            !this@ComposerDraft.editable -> revertAllChanges()
            // A long paste is held before the draft limit is checked, so a paste over the limit can
            // still become an attachment; its text never enters the field unless the user pastes it.
            this@ComposerDraft.canOfferLongPaste && isLongPaste(inserted) -> {
                this@ComposerDraft.holdLongPaste(inserted)
                revertAllChanges()
            }
            this@ComposerDraft.tooLong(after) -> revertAllChanges()
            else -> {
                this@ComposerDraft.suggestionsClosedFor = null
                this@ComposerDraft.onTyped(after)
            }
        }
    }

    /** Told each text the user typed, so voice input can tell its own text from the user's. */
    var onTyped: (String) -> Unit = {}

    /** Whether a long paste is held back for the user's choice; off where the composer takes no attachments. */
    var canOfferLongPaste = true

    val text: String get() = textState.text.toString()

    /** The text and its selection, as the field holds them. */
    val value: TextFieldValue get() = TextFieldValue(text, textState.selection)

    /** False until the chat is selected: edits are refused, so typing never lands on a draft not shown yet. */
    var editable = true

    /**
     * A pasted text too long for the field, held until the user attaches or pastes it. Null when none
     * is held. A newer long paste replaces the held one: the offer always shows the latest paste.
     */
    var longPaste by mutableStateOf<String?>(null)
        private set

    private fun holdLongPaste(text: String) {
        longPaste = text
    }

    /** Drops the held long paste: a send takes what is on screen, so the offer goes with it. */
    fun clearLongPaste() {
        longPaste = null
    }

    /**
     * Puts the held long paste into the field at the cursor and drops the offer. False, and the offer
     * stays, when the field does not take it: the text is never lost by a refused insert.
     */
    fun insertLongPaste(): Boolean {
        val held = longPaste ?: return false
        if (!insert(held)) return false
        longPaste = null
        return true
    }

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
        if (next.text != text && (!editable || tooLong(next.text))) return false
        apply(next)
        return true
    }

    /** Replaces the text with the cursor at its end: an accepted prediction or a voice result. */
    fun setText(text: String): Boolean =
        text == this.text || edit(TextFieldValue(text, TextRange(text.length)))

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
        if (!typing && draft != text) apply(TextFieldValue(draft, TextRange(draft.length)), forgetUndo = true)
    }

    /** The text whose slash suggestions Esc closed, until the text changes. */
    private var suggestionsClosedFor by mutableStateOf<String?>(null)

    val suggestionsClosed: Boolean get() = suggestionsClosedFor == text

    fun closeSuggestions() {
        suggestionsClosedFor = text
    }

    /** Types [typed] over the selection, as a key press in the field would. */
    fun insert(typed: String): Boolean {
        val start = textState.selection.min
        return edit(TextFieldValue(textWith(typed), TextRange(start + typed.length)))
    }

    /** Whether [typed] would go into the field at the cursor: the field is editable and the draft stays within its limit. */
    fun canInsert(typed: String): Boolean = editable && !tooLong(textWith(typed))

    /** The text with [typed] over the selection. */
    private fun textWith(typed: String): String {
        val start = textState.selection.min
        val end = textState.selection.max
        return text.substring(0, start) + typed + text.substring(end)
    }

    /**
     * Sets [next] as the field's text and selection; a changed text brings back the suggestions Esc
     * closed. [forgetUndo] drops the field's undo history for a text the repository changed, so Ctrl+Z
     * cannot bring back a message that was sent. Edits the user makes stay undoable.
     */
    private fun apply(next: TextFieldValue, forgetUndo: Boolean = false) {
        val previous = text
        textState.edit {
            if (previous != next.text) replace(0, length, next.text)
            selection = next.selection
        }
        if (previous != next.text) {
            suggestionsClosedFor = null
            if (forgetUndo) textState.undoState.clearHistory()
        }
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
        shape = MaterialTheme.shapes.small,
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
     * Takes the images and files the keyboard commits, a paste (Ctrl+V, the paste menu) brings, or a long
     * text the user attaches as a file, as one batch; `onDone` runs once the batch has been read. Null
     * leaves every paste to the field.
     */
    onPasteAttachments: ((List<Picked>, onDone: () -> Unit) -> Unit)? = null,
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
    /**
     * The height of the chat area the composer sits in, read lazily so its changes while the keyboard
     * animates stay out of composition. It sizes the field (see [composerLineLimit]) and caps the
     * extras above it (see [composerExtrasLimit]); null keeps the classic six lines.
     */
    chatHeight: (() -> Dp?)? = null,
    /** The large editor is open, see [onEditorExpanded]. */
    editorExpanded: Boolean = false,
    /** Offers the "Expand editor" control when set; it toggles the large editor. */
    onEditorExpanded: ((Boolean) -> Unit)? = null,
) {
    // Everything below reads the text on screen, which runs ahead of the repository's draft
    // while the user types.
    val repositoryState = state
    @Suppress("NAME_SHADOWING")
    val state = remember(repositoryState, draft.text) { repositoryState.copy(draft = draft.text) }
    // A send reads the repository's draft, so it first gets exactly the text on screen.
    // A send also drops a held long paste: the offer is for text that is not sent.
    // The large editor closes with the send, so the next message starts in the ordinary field.
    val flushed: (() -> Unit) -> () -> Unit = { action ->
        {
            draft.flush(force = true)
            draft.clearLongPaste()
            onEditorExpanded?.invoke(false)
            action()
        }
    }
    @Suppress("NAME_SHADOWING") val onSend = flushed(onSend)
    @Suppress("NAME_SHADOWING") val onFollowUp = flushed(onFollowUp)
    @Suppress("NAME_SHADOWING") val onSteer = flushed(onSteer)
    @Suppress("NAME_SHADOWING") val onResumeChild = flushed(onResumeChild)
    val predictions = LocalPromptPredictions.current
    val childControls = childControlsAvailable(state)
    val control = childControl(state)
    val childBusy = control?.phase in setOf(ChildControlPhase.STOPPING, ChildControlPhase.RESUMING)
    var confirmChildStop by remember(state.selection.sessionId) { mutableStateOf(false) }
    // Saved, so a rotation does not silently drop an open confirmation.
    var confirmStop by rememberSaveable(state.selection.sessionId) { mutableStateOf(false) }
    val busy = state.status in setOf("running", "waiting")
    val queuedFollowUps = queuedFollowUpCount(state)
    // Every stop goes through here: the busy row's Stop, Esc, Esc, Ctrl+. and the question's abort.
    // A child asks its parent's confirmation, and a run with queued follow-ups asks first, since
    // stopping discards them; otherwise the stop is at once.
    val stop: () -> Unit = when {
        childControls -> ({ confirmChildStop = true })
        queuedFollowUps > 0 -> ({ confirmStop = true })
        else -> onStop
    }
    // The question's abort is the same stop; without one to confirm, the question asks itself.
    val abort: () -> Unit = if (childControls || queuedFollowUps > 0) stop else onAbort
    LaunchedEffect(queuedFollowUps) { if (queuedFollowUps == 0) confirmStop = false }
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
    if (confirmStop)
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.remote_stop_queued_title)) },
            text = {
                Text(stringResource(
                    if (state.questions.isNotEmpty()) R.string.remote_stop_queued_answers_body
                    else R.string.remote_stop_queued_body
                ))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        onStop()
                    },
                    // The same guards as the busy row's Stop: a stop while answers are being sent
                    // or while offline would be refused, so the dialog must not look accepted.
                    enabled = state.connected && !state.loading && state.answering.isEmpty(),
                    modifier = Modifier.testTag("confirmStop"),
                ) { Text(stringResource(R.string.remote_stop_queued_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.remote_stop_queued_dismiss))
                }
            },
        )
    if (state.questions.isNotEmpty()) {
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
        if (route != null && project != null && recordsPrompt(state)) predictions?.record(route, project, state.draft)
    }
    var addMenu by remember { mutableStateOf(false) }
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
    val shell = shellDraft(state)
    val childResume = offersChildResume(state)
    val childStatus = if (childControls) childControlStatus(state) else null
    val canQueue = canQueueDraft(state)
    val canResumeChild = childResume && state.connected && !state.loading && !state.sending &&
        state.bash?.running != true &&
        !childBusy && state.draft.isNotBlank() && state.attachments.isEmpty() && state.quote == null &&
        !state.draft.trimStart().startsWith("/")
    val send: () -> Unit = if (childResume) onResumeChild else onSend
    val showBusyAction = busyAction != null && hasDraft
    val voiceInput = rememberVoiceInputBinding(state.draft, { draft.setText(it) }, voiceEngine)
    SideEffect {
        draft.onTyped = voiceInput::userEdited
        draft.canOfferLongPaste = onPasteAttachments != null
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One listener for the composer's life; it reads the latest attach callback when content arrives.
    // A keyboard image keeps its read grant after this returns: Compose asks the IME for it
    // (InputContentInfo.requestPermission) before the listener runs and does not release it, and a
    // drop's grant is requested by the receiver node itself. So the queued import can read it later.
    val latestAttach by rememberUpdatedState(onPasteAttachments)
    val receiveContent = remember(context) {
        composerContentListener(context) { items -> latestAttach?.invoke(items) {} }
    }
    val latestSelection by rememberUpdatedState(repositoryState.selection)
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
    // Alt+Enter sends with the mode not chosen, when both are offered; otherwise
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
    // The chat area's height as the extras and the field read it: through state reads that sit in the
    // layout phase or behind derivedStateOf, so a keyboard animation does not recompose the composer.
    val heightOf by rememberUpdatedState(chatHeight)
    val lineLimit by remember(editorExpanded) { derivedStateOf { composerLineLimit(heightOf?.invoke(), editorExpanded) } }
    // The large editor takes half the chat area at least, so its text has room before it scrolls.
    val editorMinHeight: (() -> Dp)? =
        if (editorExpanded && chatHeight != null) ({ (heightOf?.invoke() ?: 0.dp) * 0.5f }) else null
    // The variable extras above the field (quote, attachments, suggestions) take at most 40% of the chat
    // area, the field keeps the rest. The large editor keeps the limit too: it takes half the area
    // itself, and uncapped extras would push it and Send past the top. Stop, the send mode and the
    // long-paste offer stay outside, so they never sit behind a scroll.
    val extrasCapped = chatHeight != null
    // The control to expand the editor appears once the draft has grown past a line or two: a short or
    // empty draft gets no row of its own, so a phone keeps its height until the field needs more.
    val longDraft = draft.text.length > EXPAND_OFFER_CHARS || '\n' in draft.text
    val hasExtras = state.quote != null || state.attachments.isNotEmpty() ||
        state.importingAttachments || state.attachmentProgress != null
    FloatingSurface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    .then(
                        if (extrasCapped)
                            Modifier.maxHeightOf { (heightOf?.invoke() ?: Dp.Infinity).let(::composerExtrasLimit) }
                                .verticalScroll(rememberScrollState())
                        else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasExtras)
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
            }
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
            // While pi runs, the busy row is the one place Stop lives. It also holds the send mode
            // when a draft can be queued, so the choice is visible instead of hidden in a swipe.
            if (busy) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busyAction != null && hasDraft)
                        SendModeChoice(
                            selected = busyAction,
                            steerEnabled = canSteerNow,
                            followUpEnabled = canFollowUpNow,
                            onSelect = { preferredBusyAction = it },
                        )
                    else Spacer(Modifier.weight(1f))
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
            draft.longPaste?.let { held ->
                LongPasteOffer(
                    onAttach = {
                        val attach = onPasteAttachments ?: return@LongPasteOffer
                        scope.launch {
                            // Not cancelled with the composer: once started, the write ends, so the
                            // text is attached, put into the field or still held, never dropped.
                            val file = withContext(Dispatchers.IO + NonCancellable) {
                                runCatching { writePastedText(context, held) }.getOrNull()
                            }
                            // The offer may have gone meanwhile (a send, a newer paste, another chat).
                            if (draft.longPaste != held || draft.selection != latestSelection) {
                                file?.delete()
                                return@launch
                            }
                            if (file == null) {
                                // The write failed: the text goes into the field, as a paste without the offer would.
                                if (draft.insertLongPaste()) voiceInput.userEdited(draft.text)
                            } else {
                                draft.clearLongPaste()
                                attach(listOf(Picked(pastedTextUri(context, file).toString(), photo = false))) {
                                    file.delete()
                                }
                            }
                        }
                    },
                    // Offered only when the field takes the text: a text over the draft limit is attached or nothing.
                    onPaste =
                        if (draft.canInsert(held)) {
                            { if (draft.insertLongPaste()) voiceInput.userEdited(draft.text) }
                        } else {
                            null
                        },
                )
            }
            if (onEditorExpanded != null && (editorExpanded || longDraft))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { onEditorExpanded(!editorExpanded) },
                        modifier = Modifier.testTag("expandEditor"),
                    ) {
                        Icon(
                            if (editorExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (editorExpanded) R.string.remote_editor_collapse else R.string.remote_editor_expand))
                    }
                }
            // Send runs a `!` draft as a shell command; the label says so before it is sent.
            shell?.let { ShellModeLabel(it.excludeFromContext, idle = state.status == "idle") }
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
                                        if (pasted != null) onPasteAttachments(pasted.attachments) {}
                                        else Toast.makeText(context, clipboardEmpty, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("pasteAttachment"),
                                )
                        }
                    }
                val promptLabel = stringResource(R.string.remote_prompt)
                val shellLabel = stringResource(R.string.remote_shell_command)
                // TalkBack names what the button does; the suggestion alone would not say it fills the field.
                val useSuggestion = prediction?.let { stringResource(R.string.remote_suggestion_use, it) }
                TextField(
                    state = draft.textState,
                    // What the user types or pastes passes the draft first: see ComposerDraft.inputTransformation.
                    inputTransformation = draft.inputTransformation,
                    // The visible hint stays a placeholder: a floating label would grow the field and
                    // shift the chat when the first character arrives. The name for TalkBack is the
                    // field's description below, and only while the field is empty. A suggestion takes
                    // the placeholder's place, grey behind the cursor as in pi's terminal; the button at
                    // the end of the field or Tab takes it. It keeps to one line, so the empty field is
                    // no taller than a typed line and the chat does not jump on the first character.
                    placeholder = {
                        Text(
                            prediction ?: promptLabel,
                            maxLines = if (prediction != null) 1 else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("composerPlaceholder"),
                        )
                    },
                    // Only while the field is empty: the draft reaches the state after a pause, and the
                    // button must go with the first typed character, as the grey text does.
                    trailingIcon =
                        prediction?.takeIf { draft.textState.text.isEmpty() }?.let {
                            {
                                IconButton(
                                    onClick = {
                                        acceptPrediction()
                                        // The field takes focus, as with Tab, so the next keystroke goes into it.
                                        focusRequester?.let { runCatching { it.requestFocus() } }
                                    },
                                    modifier = Modifier.testTag("promptSuggestion"),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardTab, useSuggestion)
                                }
                            }
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
                            // Images and files from the keyboard and a paste go to the attachments, not the field.
                            .then(if (onPasteAttachments != null) Modifier.contentReceiver(receiveContent) else Modifier)
                            // The name only while the field is empty: a content description on an
                            // editable node can replace the typed text for TalkBack, so it stops once
                            // there is text to read.
                            .then(
                                if (draft.textState.text.isEmpty()) Modifier.semantics { contentDescription = promptLabel }
                                else Modifier
                            )
                            // The field itself says that Send runs a shell command, not only the label above it.
                            .then(if (shell != null) Modifier.semantics { stateDescription = shellLabel } else Modifier)
                            .handwritingHandler()
                            .onFocusChanged { keyTarget?.composerFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
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
                                        draftEmpty = draft.textState.text.isEmpty(),
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
                                        if (draft.insert("\n")) voiceInput.userEdited(draft.text)
                                        true
                                    }
                                }
                            }
                            .testTag("composerField")
                            .then(if (editorMinHeight != null) Modifier.minHeightOf(editorMinHeight) else Modifier),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = lineLimit),
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.large,
                )
                // Names what the primary button does now, and its key. While pi runs it never stops:
                // with a draft it sends the chosen mode, and without one it stays a disabled Send.
                val primaryTip: Pair<String, KeyChord?> =
                    when {
                        busy && busyAction != null && hasDraft -> stringResource(busyAction.labelRes()) to enterChord
                        voiceInput.state is VoiceInputState.Listening -> stringResource(R.string.remote_voice_finish) to null
                        voiceInput.state is VoiceInputState.Finishing -> stringResource(R.string.remote_voice_cancel) to null
                        busy -> stringResource(R.string.remote_send) to null
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
                        busyAction = if (showBusyAction) busyAction else null,
                        busyActionEnabled = canQueue,
                        onBusyAction = {
                            recordPrompt()
                            if (busyAction == BusyComposerAction.Steer) onSteer() else onFollowUp()
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

/**
 * The field's line limit: one line per 120dp of the chat area, between 3 and 14 lines. Without a
 * known height it keeps the six lines the field always had. The large editor takes the most.
 */
internal fun composerLineLimit(height: Dp?, expanded: Boolean): Int =
    when {
        expanded -> COMPOSER_MAX_LINES
        height == null -> 6
        else -> floor(height.value / 120f).toInt().coerceIn(COMPOSER_MIN_LINES, COMPOSER_MAX_LINES)
    }

/** The most the extras above the field may take of the chat area: 40%. */
internal fun composerExtrasLimit(height: Dp): Dp = height * 0.4f

/** A draft longer than this, or with a line break, is offered the large editor. */
private const val EXPAND_OFFER_CHARS = 80
private const val COMPOSER_MIN_LINES = 3
private const val COMPOSER_MAX_LINES = 14

/**
 * The draft can be sent now; the same rule the primary button follows. A shell draft (see
 * [shellDraft]) needs a command and no other one running.
 */
internal fun canSendDraft(state: RemoteState): Boolean =
    state.connected &&
        !state.loading &&
        state.status == "idle" &&
        !state.sending &&
        !state.importingAttachments &&
        !state.configurationChanging &&
        // Nothing goes out while this phone's shell command runs; the session answers busy.
        state.bash?.running != true &&
        (shellDraft(state)?.let { shellDraftSendable(it, state) }
            ?: (state.draft.isNotBlank() || state.attachments.isNotEmpty()))

/**
 * The draft can go out as a steer, a follow-up or a queued prompt: never a `/` command, nor a shell
 * draft, which runs only through Send once pi is idle, nor anything while a shell command runs.
 */
internal fun canQueueDraft(state: RemoteState): Boolean =
    state.connected && !state.loading && !state.sending &&
        !state.importingAttachments && !state.configurationChanging &&
        state.questions.isEmpty() && (state.draft.isNotBlank() || state.attachments.isNotEmpty()) &&
        !state.draft.trimStart().startsWith("/") && state.followUps.size < 64 &&
        !(childControlsAvailable(state) && state.attachments.isNotEmpty()) &&
        shellDraft(state) == null && state.bash?.running != true

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

internal enum class BusyComposerAction { Steer, FollowUp }

/** The plain verb of a send mode: interrupt the running step, or send once the run is done. */
internal fun BusyComposerAction.labelRes(): Int =
    if (this == BusyComposerAction.Steer) R.string.remote_send_now else R.string.remote_send_when_done

/** The follow-ups a stop would discard: the ones still waiting for the run, or accepted into it. */
internal fun queuedFollowUpCount(state: RemoteState): Int =
    state.followUps.count { it.status == "pending" || it.status == "accepted" }

/** The busy draft's send mode as two plain choices; a mode the host cannot take is disabled. */
@Composable
private fun RowScope.SendModeChoice(
    selected: BusyComposerAction,
    steerEnabled: Boolean,
    followUpEnabled: Boolean,
    onSelect: (BusyComposerAction) -> Unit,
) {
    val modes = listOf(BusyComposerAction.Steer, BusyComposerAction.FollowUp)
    val description = stringResource(R.string.remote_send_mode)
    SingleChoiceSegmentedButtonRow(Modifier.weight(1f).semantics { contentDescription = description }) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                enabled = if (mode == BusyComposerAction.Steer) steerEnabled else followUpEnabled,
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.testTag(if (mode == BusyComposerAction.Steer) "sendNow" else "sendWhenDone"),
                icon = {},
            ) {
                Text(
                    stringResource(mode.labelRes()),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
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

/** At most [limit]'s height, read when the layout runs: a changing limit relays out without recomposing. */
private fun Modifier.maxHeightOf(limit: () -> Dp): Modifier = layout { measurable, constraints ->
    val cap = limit()
    val bounded =
        if (cap.value.isFinite()) constraints.copy(maxHeight = cap.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight))
        else constraints
    val placeable = measurable.measure(bounded)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** At least [min]'s height (as far as the constraints allow), read when the layout runs. */
private fun Modifier.minHeightOf(min: () -> Dp): Modifier = layout { measurable, constraints ->
    val floor = min().roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = floor))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
