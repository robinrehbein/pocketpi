package de.joinnoah.pi.remote

internal enum class VoiceInputProvider {
    OnDevice,
    System,
}

internal enum class VoiceInputError {
    PermissionDenied,
    Unavailable,
    Network,
    Recognition,
}

internal sealed interface VoiceInputState {
    data object Idle : VoiceInputState

    data class RequestingPermission(val provider: VoiceInputProvider) : VoiceInputState

    data class Listening(val preview: String = "") : VoiceInputState

    data object Finishing : VoiceInputState

    data class Error(val error: VoiceInputError) : VoiceInputState
}

internal data class VoiceInputStart(
    val generation: Long,
    val selection: RemoteSelection,
    val draft: String,
    val localeTag: String,
    val provider: VoiceInputProvider,
)

internal interface VoiceRecognitionListener {
    fun onPartial(text: String)

    fun onEndOfSpeech()

    fun onFinal(text: String)

    fun onError(error: VoiceInputError)
}

internal interface VoiceRecognitionEngine {
    val systemAvailable: Boolean
    val onDeviceAvailable: Boolean

    fun start(start: VoiceInputStart, listener: VoiceRecognitionListener)

    fun stop()

    fun cancel()

    fun destroy()
}

/**
 * Owns the short-lived speech session and fences asynchronous recognizer and permission callbacks.
 * It never sends a prompt; recognizer text only replaces the draft it published for this session.
 */
internal class VoiceInputController(
    private val engine: VoiceRecognitionEngine,
    private val onDraft: (String) -> Unit,
    private val currentDraft: () -> String = { "" },
    private val onStateChanged: () -> Unit = {},
) {
    var state: VoiceInputState = VoiceInputState.Idle
        private set

    private var generation = 0L
    private var active: VoiceInputStart? = null
    private var publishedDrafts = emptySet<String>()
    private var latestPublishedDraft: String? = null

    fun start(
        selection: RemoteSelection,
        draft: String,
        permissionGranted: Boolean,
        localeTag: String,
    ): VoiceInputStart {
        cancel()
        val start =
            VoiceInputStart(
                generation = ++generation,
                selection = selection,
                draft = draft,
                localeTag = localeTag,
                provider = if (engine.onDeviceAvailable) VoiceInputProvider.OnDevice else VoiceInputProvider.System,
            )
        active = start
        publishedDrafts = setOf(draft)
        latestPublishedDraft = null
        when {
            draft.isNotBlank() -> finish()
            !start.isAvailable() -> fail(start, VoiceInputError.Unavailable)
            !permissionGranted -> update(VoiceInputState.RequestingPermission(start.provider))
            else -> begin(start)
        }
        return start
    }

    fun permissionResult(start: VoiceInputStart, granted: Boolean) {
        if (active != start || state !is VoiceInputState.RequestingPermission) return
        if (granted) begin(start) else fail(start, VoiceInputError.PermissionDenied)
    }

    fun userEdited(@Suppress("UNUSED_PARAMETER") draft: String) {
        if (active != null) cancel()
    }

    fun reconcileDraft(draft: String) {
        if (active == null) return
        val actualDraft = currentDraft()
        if (draft != actualDraft && draft in publishedDrafts) return
        if (actualDraft !in publishedDrafts) cancel()
    }

    fun stop() {
        if (active == null || state !is VoiceInputState.Listening) return
        update(VoiceInputState.Finishing)
        engine.stop()
    }

    fun timedOut() {
        val start = active ?: return
        engine.cancel()
        fail(start, VoiceInputError.Recognition)
    }

    fun cancel() {
        if (active == null) return
        active = null
        clearPublishedDrafts()
        generation++
        engine.cancel()
        engine.destroy()
        update(VoiceInputState.Idle)
    }

    fun dispose() = cancel()

    private fun begin(start: VoiceInputStart) {
        if (active != start) return
        update(VoiceInputState.Listening())
        engine.start(
            start,
            object : VoiceRecognitionListener {
                override fun onPartial(text: String) {
                    if (
                        active != start ||
                            (state !is VoiceInputState.Listening && state !is VoiceInputState.Finishing) ||
                            text.isBlank()
                    ) return
                    if (!canReplaceDraft(start)) return
                    publishDraft(start, text)
                    if (active == start && state is VoiceInputState.Listening)
                        update(VoiceInputState.Listening(text))
                }

                override fun onEndOfSpeech() {
                    if (active == start && state is VoiceInputState.Listening)
                        update(VoiceInputState.Finishing)
                }

                override fun onFinal(text: String) {
                    if (active != start) return
                    if (text.isBlank()) {
                        fail(start, VoiceInputError.Recognition)
                        return
                    }
                    if (!canReplaceDraft(start)) return
                    publishDraft(start, text)
                    if (active == start) complete(VoiceInputState.Idle)
                }

                override fun onError(error: VoiceInputError) = fail(start, error)
            },
        )
    }

    private fun fail(start: VoiceInputStart, error: VoiceInputError) {
        if (active != start) return
        complete(VoiceInputState.Error(error))
    }

    private fun finish() {
        active = null
        clearPublishedDrafts()
        update(VoiceInputState.Idle)
    }

    private fun canReplaceDraft(start: VoiceInputStart): Boolean {
        if (active != start) return false
        if (currentDraft() in publishedDrafts) return true
        cancel()
        return false
    }

    private fun publishDraft(start: VoiceInputStart, text: String) {
        if (active != start || text == latestPublishedDraft) return
        publishedDrafts += text
        latestPublishedDraft = text
        onDraft(text)
    }

    private fun complete(nextState: VoiceInputState) {
        active = null
        clearPublishedDrafts()
        engine.destroy()
        update(nextState)
    }

    private fun clearPublishedDrafts() {
        publishedDrafts = emptySet()
        latestPublishedDraft = null
    }

    private fun update(value: VoiceInputState) {
        state = value
        onStateChanged()
    }

    private fun VoiceInputStart.isAvailable(): Boolean =
        when (provider) {
            VoiceInputProvider.OnDevice -> engine.onDeviceAvailable
            VoiceInputProvider.System -> engine.systemAvailable
        }
}
