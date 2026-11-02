package de.joinnoah.pi.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun rememberVoiceInputBinding(
    draft: String,
    onDraft: (String) -> Unit,
    engine: VoiceRecognitionEngine? = null,
): VoiceInputBinding {
    val context = LocalContext.current
    val latestDraft by rememberUpdatedState(onDraft)
    val latestDraftText by rememberUpdatedState(draft)
    val recognitionEngine = remember(context.applicationContext, engine) {
        engine ?: AndroidVoiceRecognitionEngine(context.applicationContext)
    }
    return remember(recognitionEngine) {
        VoiceInputBinding(recognitionEngine, { latestDraft(it) }, { latestDraftText })
    }
}

internal class VoiceInputBinding(
    val engine: VoiceRecognitionEngine,
    onDraft: (String) -> Unit,
    currentDraft: () -> String,
) {
    private var version by mutableIntStateOf(0)
    val controller = VoiceInputController(engine, onDraft, currentDraft) { version++ }

    val state: VoiceInputState
        get() {
            version
            return controller.state
        }

    fun userEdited(draft: String) = controller.userEdited(draft)

    fun reconcileDraft(draft: String) = controller.reconcileDraft(draft)

    fun dispose() = controller.dispose()
}

@Composable
internal fun VoiceInputStatus(
    binding: VoiceInputBinding,
    state: RemoteState,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current
    val voiceState = binding.state
    LaunchedEffect(state.draft) { binding.reconcileDraft(state.draft) }
    LaunchedEffect(state.connected, state.loading, state.status) {
        if (!state.connected || state.loading || state.status != "idle") binding.controller.cancel()
    }
    DisposableEffect(state.selection, lifecycle, binding) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) binding.controller.cancel()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            binding.dispose()
        }
    }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        val canStart =
            state.connected && !state.loading && state.status == "idle" && !state.sending &&
                !state.importingAttachments && !state.configurationChanging &&
                state.draft.isBlank() && state.attachments.isEmpty()
        if (canStart &&
            binding.engine.systemAvailable && !binding.engine.onDeviceAvailable
        ) {
            Text(stringResource(R.string.remote_voice_network_notice), style = MaterialTheme.typography.labelSmall)
        }
        when (voiceState) {
            is VoiceInputState.Listening -> {
                Text(stringResource(R.string.remote_voice_listening), style = MaterialTheme.typography.labelSmall)
            }
            VoiceInputState.Finishing ->
                Text(stringResource(R.string.remote_voice_finishing), style = MaterialTheme.typography.labelSmall)
            is VoiceInputState.Error ->
                Text(
                    stringResource(voiceState.error.stringRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> Unit
        }
    }
}

@Composable
internal fun VoiceInput(
    state: RemoteState,
    binding: VoiceInputBinding,
    onSend: () -> Unit,
    busyAction: BusyComposerAction? = null,
    busyActionEnabled: Boolean = false,
    onBusyAction: () -> Unit = {},
    canSwitchBusyAction: Boolean = false,
    onSwitchBusyAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    permissionGrantedOverride: Boolean? = null,
) {
    val context = LocalContext.current
    val recognitionEngine = binding.engine
    val controller = binding.controller
    var pendingPermission by remember { mutableStateOf<VoiceInputStart?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.let { controller.permissionResult(it, granted) }
            pendingPermission = null
        }
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val permissionGranted =
        permissionGrantedOverride
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
    val voiceState = binding.state
    // While pi runs, this button never stops it: Stop lives in the composer's busy row.
    val busy = state.status in setOf("running", "waiting")
    val canChooseBusyAction = busy && busyAction != null
    val canSend = canSendDraft(state)
    val listening = voiceState is VoiceInputState.Listening
    val finishing = voiceState is VoiceInputState.Finishing
    val action = when {
        canChooseBusyAction && busyAction == BusyComposerAction.Steer -> VoiceInputButton.Steer
        canChooseBusyAction -> VoiceInputButton.FollowUp
        listening -> VoiceInputButton.Finish
        finishing -> VoiceInputButton.Cancel
        busy || state.draft.isNotBlank() || state.attachments.isNotEmpty() -> VoiceInputButton.Send
        else -> VoiceInputButton.Mic
    }
    val enabled = (canChooseBusyAction && busyActionEnabled) || listening || finishing || canSend ||
        (voiceState !is VoiceInputState.RequestingPermission && state.connected && !state.loading &&
            state.status == "idle" && !state.sending && !state.importingAttachments &&
            !state.configurationChanging && state.draft.isBlank() && state.attachments.isEmpty())
    val activate: () -> Unit = {
        when {
            canChooseBusyAction -> if (busyActionEnabled) onBusyAction()
            listening -> controller.stop()
            finishing -> controller.cancel()
            canSend -> onSend()
            busy -> Unit
            voiceState is VoiceInputState.RequestingPermission -> Unit
            else -> {
                val start = controller.start(state.selection, state.draft, permissionGranted, localeTag)
                if (controller.state is VoiceInputState.RequestingPermission) {
                    pendingPermission = start
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val latestActivate by rememberUpdatedState(activate)
    val latestSwitch by rememberUpdatedState(onSwitchBusyAction)
    val pressContext = listOf(
        state.selection, state.status, state.connected, state.loading, state.capabilities,
        state.unavailableCapabilities, action, enabled, canSwitchBusyAction,
    )
    var heldKey by remember { mutableStateOf<Key?>(null) }
    var heldContext by remember { mutableStateOf<List<Any?>?>(null) }
    var keyboardPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    fun endKeyboardPress(release: Boolean) {
        val press = keyboardPress ?: return
        keyboardPress = null
        interactionSource.tryEmit(if (release) PressInteraction.Release(press) else PressInteraction.Cancel(press))
    }
    SideEffect {
        if (heldContext != null && heldContext != pressContext) {
            heldContext = null
            endKeyboardPress(release = false)
        }
    }
    DisposableEffect(interactionSource) {
        onDispose { endKeyboardPress(release = false) }
    }
    // Own touch streams in every mode: a press begun on voice or idle Send must not become a
    // busy action on release. Observed context changes cancel the detector; the protocol has no
    // run generation with which to distinguish an unobserved run replacement.
    val touchModifier = if (enabled) {
        Modifier.pointerInput(pressContext) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed) return@awaitEachGesture
                down.consume()
                val origin = down.position
                var dragged = false
                var horizontal = false
                var valid = true
                var activePress: PressInteraction.Press? = PressInteraction.Press(origin)
                activePress?.let { interactionSource.tryEmit(it) }
                fun endPress(release: Boolean) {
                    activePress?.let {
                        interactionSource.tryEmit(if (release) PressInteraction.Release(it) else PressInteraction.Cancel(it))
                    }
                    activePress = null
                }
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) valid = false
                        val delta = change.position - origin
                        if (!dragged && delta.getDistance() > viewConfiguration.touchSlop) {
                            // A diagonal crossing cancels the tap too, but only an unambiguous
                            // horizontal crossing selects another mode. Both decisions are latched.
                            horizontal = abs(delta.x) > 2f * abs(delta.y)
                            dragged = true
                        }
                        if (dragged || !valid) endPress(release = false)
                        val inside = change.position.x >= 0 && change.position.y >= 0 &&
                            change.position.x <= size.width && change.position.y <= size.height
                        val released = !change.pressed
                        change.consume()
                        if (released) {
                            endPress(release = valid && !dragged && inside)
                            if (valid) {
                                if (dragged && horizontal && canChooseBusyAction && canSwitchBusyAction) latestSwitch()
                                else if (!dragged && inside) latestActivate()
                            }
                            break
                        }
                    } while (true)
                } finally {
                    endPress(release = false)
                }
            }
        }
    } else Modifier
    FilledIconButton(
            onClick = activate,
            interactionSource = interactionSource,
            modifier = modifier.then(touchModifier)
                .onFocusChanged {
                    if (!it.isFocused) {
                        heldKey = null
                        heldContext = null
                        endKeyboardPress(release = false)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.key !in setOf(Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)) {
                        false
                    } else {
                        when (event.type) {
                            KeyEventType.KeyDown -> if (heldKey == null) {
                                heldKey = event.key
                                heldContext = if (enabled) pressContext else null
                                if (enabled) {
                                    val press = PressInteraction.Press(Offset.Zero)
                                    keyboardPress = press
                                    interactionSource.tryEmit(press)
                                }
                            }
                            KeyEventType.KeyUp -> if (heldKey == event.key) {
                                val activateHeld = heldContext == pressContext && enabled
                                heldKey = null
                                heldContext = null
                                endKeyboardPress(release = activateHeld)
                                if (activateHeld) activate()
                            }
                        }
                        true
                    }
                },
            enabled = enabled,
        ) {
            AnimatedContent(
                targetState = action,
                label = "voice input action",
            ) { action ->
                when (action) {
                    VoiceInputButton.Steer -> Icon(Icons.Default.ArrowUpward, stringResource(R.string.remote_send_now))
                    VoiceInputButton.FollowUp ->
                        Icon(Icons.AutoMirrored.Filled.ScheduleSend, stringResource(R.string.remote_send_when_done))
                    // A check, not the Stop of the busy row: finishing keeps the dictated text.
                    VoiceInputButton.Finish ->
                        Icon(Icons.Default.Check, stringResource(R.string.remote_voice_finish))
                    VoiceInputButton.Cancel -> Icon(Icons.Default.Close, stringResource(R.string.remote_voice_cancel))
                    VoiceInputButton.Send -> Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.remote_send))
                    VoiceInputButton.Mic -> Icon(Icons.Default.Mic, stringResource(R.string.remote_voice_start))
                    }
                }
            }
    }

private enum class VoiceInputButton {
    Mic,
    Send,
    Steer,
    FollowUp,
    Finish,
    Cancel,
}

private fun VoiceInputError.stringRes(): Int =
    when (this) {
        VoiceInputError.PermissionDenied -> R.string.remote_voice_permission_denied
        VoiceInputError.Unavailable -> R.string.remote_voice_unavailable
        VoiceInputError.Network -> R.string.remote_voice_network_error
        VoiceInputError.Recognition -> R.string.remote_voice_error
    }

internal class VoiceRecognitionWatchdog(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancel: (() -> Unit) -> Unit,
) {
    private var pending: (() -> Unit)? = null
    private var phase: Phase? = null

    fun awaitStart(onTimeout: () -> Unit) = arm(Phase.Starting, onTimeout)

    fun speechStarted(onTimeout: () -> Unit) = refreshListening(onTimeout)

    fun partialResult(onTimeout: () -> Unit) = refreshListening(onTimeout)

    fun audioActivity(onTimeout: () -> Unit) {
        if (phase == Phase.Listening) arm(Phase.Listening, onTimeout)
    }

    fun awaitFinal(onTimeout: () -> Unit) = arm(Phase.Finishing, onTimeout)

    fun clear() {
        pending?.let(cancel)
        pending = null
        phase = null
    }

    private fun refreshListening(onTimeout: () -> Unit) {
        if (phase == Phase.Starting || phase == Phase.Listening) arm(Phase.Listening, onTimeout)
    }

    private fun arm(nextPhase: Phase, onTimeout: () -> Unit) {
        clear()
        lateinit var callback: () -> Unit
        callback = {
            if (pending === callback) {
                pending = null
                phase = null
                onTimeout()
            }
        }
        pending = callback
        phase = nextPhase
        schedule(WATCHDOG_TIMEOUT_MS, callback)
    }

    private enum class Phase {
        Starting,
        Listening,
        Finishing,
    }

    private companion object {
        const val WATCHDOG_TIMEOUT_MS = 30_000L
    }
}

internal class AndroidVoiceRecognitionEngine(private val context: Context) : VoiceRecognitionEngine {
    private var recognizer: SpeechRecognizer? = null
    private var listener: VoiceRecognitionListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scheduledWatchdogs = mutableMapOf<() -> Unit, Runnable>()
    private val watchdog =
        VoiceRecognitionWatchdog(
            { delay, callback ->
                val runnable = Runnable {
                    scheduledWatchdogs.remove(callback)
                    callback()
                }
                scheduledWatchdogs[callback] = runnable
                handler.postDelayed(runnable, delay)
            },
            { callback -> scheduledWatchdogs.remove(callback)?.let(handler::removeCallbacks) },
        )

    override val systemAvailable: Boolean
        get() =
            context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .isNotEmpty() && SpeechRecognizer.isRecognitionAvailable(context)

    override val onDeviceAvailable: Boolean
        get() =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) {
        checkMainThread()
        this.listener = listener
        if (
            (start.provider == VoiceInputProvider.OnDevice && !onDeviceAvailable) ||
                (start.provider == VoiceInputProvider.System && !systemAvailable)
        ) {
            listener.onError(VoiceInputError.Unavailable)
            return
        }
        val next =
            try {
                if (start.provider == VoiceInputProvider.OnDevice && onDeviceAvailable)
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else if (start.provider == VoiceInputProvider.System)
                    SpeechRecognizer.createSpeechRecognizer(context)
                else {
                    listener.onError(VoiceInputError.Unavailable)
                    return
                }
            } catch (_: Exception) {
                listener.onError(VoiceInputError.Unavailable)
                return
            }
        recognizer = next
        next.setRecognitionListener(
            object : RecognitionListener {
                private fun current() = recognizer === next

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() {
                    if (current()) watchdog.speechStarted { listener.onError(VoiceInputError.Recognition) }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (current()) watchdog.audioActivity { listener.onError(VoiceInputError.Recognition) }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (!current()) return
                    watchdog.awaitFinal { listener.onError(VoiceInputError.Recognition) }
                    listener.onEndOfSpeech()
                }

                override fun onError(error: Int) {
                    if (!current()) return
                    watchdog.clear()
                    listener.onError(error.toVoiceInputError())
                }

                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    watchdog.clear()
                    listener.onFinal(results.bestRecognition())
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!current()) return
                    watchdog.partialResult { listener.onError(VoiceInputError.Recognition) }
                    listener.onPartial(partialResults.bestRecognition())
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        )
        try {
            watchdog.awaitStart { listener.onError(VoiceInputError.Recognition) }
            next.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, start.localeTag)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
            )
        } catch (_: Exception) {
            listener.onError(VoiceInputError.Unavailable)
        }
    }

    override fun stop() {
        checkMainThread()
        watchdog.awaitFinal { listener?.onError(VoiceInputError.Recognition) }
        recognizer?.stopListening()
    }

    override fun cancel() {
        checkMainThread()
        watchdog.clear()
        recognizer?.cancel()
    }

    override fun destroy() {
        checkMainThread()
        watchdog.clear()
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizer must run on the main thread." }
    }
}

private fun Bundle?.bestRecognition(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun Int.toVoiceInputError(): VoiceInputError =
    when (this) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceInputError.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.PermissionDenied
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER -> VoiceInputError.Unavailable
        else -> VoiceInputError.Recognition
    }
