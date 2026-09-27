package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputControllerTest {
    private val selection = RemoteSelection("host", "project", "session")

    @Test
    fun partialSpeechUpdatesTheDraftAndFinalSpeechReplacesThePartial() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val drafts = mutableListOf<String>()
        val controller =
            VoiceInputController(
                engine,
                {
                    currentDraft = it
                    drafts += it
                },
                { currentDraft },
            )

        controller.start(selection, "", permissionGranted = true, localeTag = "de-DE")
        engine.listener.onPartial("Hallo")
        engine.listener.onPartial("Hallo")
        assertEquals(VoiceInputState.Listening("Hallo"), controller.state)
        assertEquals("Hallo", currentDraft)

        engine.listener.onFinal("Hallo Welt")
        engine.listener.onFinal("ignored duplicate")

        assertEquals(listOf("Hallo", "Hallo Welt"), drafts)
        assertEquals("Hallo Welt", currentDraft)
        assertEquals(VoiceInputState.Idle, controller.state)
        assertEquals(1, engine.destroyed)
    }

    @Test
    fun blankPartialAndFinalKeepTheLastRecognizedDraftAndReportARecognitionError() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val controller = VoiceInputController(engine, { currentDraft = it }, { currentDraft })

        controller.start(selection, "", permissionGranted = true, localeTag = "de-DE")
        engine.listener.onPartial("keep this")
        engine.listener.onPartial("   ")
        engine.listener.onFinal("")

        assertEquals("keep this", currentDraft)
        assertEquals(VoiceInputState.Error(VoiceInputError.Recognition), controller.state)
    }

    @Test
    fun recognitionErrorsAndTimeoutsKeepTheLastRecognizedDraft() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val controller = VoiceInputController(engine, { currentDraft = it }, { currentDraft })

        controller.start(selection, "", permissionGranted = true, localeTag = "de-DE")
        engine.listener.onPartial("keep after error")
        engine.listener.onError(VoiceInputError.Network)

        assertEquals("keep after error", currentDraft)
        assertEquals(VoiceInputState.Error(VoiceInputError.Network), controller.state)

        val timeoutEngine = FakeVoiceRecognitionEngine()
        var timeoutDraft = ""
        val timeoutController =
            VoiceInputController(timeoutEngine, { timeoutDraft = it }, { timeoutDraft })
        timeoutController.start(selection, "", permissionGranted = true, localeTag = "de-DE")
        timeoutEngine.listener.onPartial("keep after timeout")
        timeoutController.timedOut()

        assertEquals("keep after timeout", timeoutDraft)
        assertEquals(VoiceInputState.Error(VoiceInputError.Recognition), timeoutController.state)
    }

    @Test
    fun draftReconciliationDoesNotCancelAControllerPublishedPartial() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        lateinit var controller: VoiceInputController
        controller =
            VoiceInputController(
                engine,
                {
                    currentDraft = it
                    controller.reconcileDraft(it)
                },
                { currentDraft },
            )

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onPartial("still listening")

        assertEquals(VoiceInputState.Listening("still listening"), controller.state)
        assertEquals(0, engine.cancelled)
    }

    @Test
    fun delayedReconciliationOfAnOlderPartialDoesNotCancelANewerPublishedPartial() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val controller = VoiceInputController(engine, { currentDraft = it }, { currentDraft })

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onPartial("first partial")
        engine.listener.onPartial("second partial")
        controller.reconcileDraft("first partial")

        assertEquals("second partial", currentDraft)
        assertEquals(VoiceInputState.Listening("second partial"), controller.state)
        assertEquals(0, engine.cancelled)
    }

    @Test
    fun explicitUserEditCancelsEvenWhenItMatchesAnEarlierPublishedPartial() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val controller = VoiceInputController(engine, { currentDraft = it }, { currentDraft })

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onPartial("first partial")
        engine.listener.onPartial("second partial")
        controller.userEdited("first partial")

        assertEquals(VoiceInputState.Idle, controller.state)
        assertEquals(1, engine.cancelled)
    }

    @Test
    fun latePermissionAndRecognitionCallbacksCannotWriteAfterNavigation() {
        val engine = FakeVoiceRecognitionEngine()
        val drafts = mutableListOf<String>()
        val controller = VoiceInputController(engine, drafts::add)

        val permission = controller.start(selection, "", permissionGranted = false, localeTag = "en-US")
        controller.cancel()
        controller.permissionResult(permission, granted = true)

        assertTrue(engine.started.isEmpty())
        assertTrue(drafts.isEmpty())
        assertEquals(VoiceInputState.Idle, controller.state)
    }

    @Test
    fun userTypingCancelsRecognitionAndPreservesTheKeyboardDraft() {
        val engine = FakeVoiceRecognitionEngine()
        val drafts = mutableListOf<String>()
        val controller = VoiceInputController(engine, drafts::add)

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        controller.userEdited("typed by keyboard")
        engine.listener.onFinal("speech result")

        assertTrue(drafts.isEmpty())
        assertEquals(1, engine.cancelled)
        assertEquals(1, engine.destroyed)
        assertEquals(VoiceInputState.Idle, controller.state)
    }

    @Test
    fun onDeviceProviderIsPreferredWithoutFallingBackAfterAnError() {
        val engine = FakeVoiceRecognitionEngine(onDeviceAvailable = true)
        val controller = VoiceInputController(engine, {})

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onError(VoiceInputError.Network)

        assertEquals(listOf(VoiceInputProvider.OnDevice), engine.started.map { it.provider })
        assertEquals(VoiceInputState.Error(VoiceInputError.Network), controller.state)
    }

    @Test
    fun deniedPermissionLeavesTheDraftUntouched() {
        val engine = FakeVoiceRecognitionEngine()
        val drafts = mutableListOf<String>()
        val controller = VoiceInputController(engine, drafts::add)

        val request = controller.start(selection, "", permissionGranted = false, localeTag = "en-US")
        controller.permissionResult(request, granted = false)

        assertEquals(VoiceInputState.Error(VoiceInputError.PermissionDenied), controller.state)
        assertTrue(drafts.isEmpty())
        assertEquals(1, engine.destroyed)
    }

    @Test
    fun unavailableSystemProviderDoesNotStartRecognition() {
        val engine = FakeVoiceRecognitionEngine(systemAvailable = false)
        val controller = VoiceInputController(engine, {})

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")

        assertEquals(VoiceInputState.Error(VoiceInputError.Unavailable), controller.state)
        assertTrue(engine.started.isEmpty())
    }

    @Test
    fun externalDraftChangeWinsOverAResultThatArrivesBeforeCompositionDisposes() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val committed = mutableListOf<String>()
        val controller = VoiceInputController(engine, committed::add, { currentDraft })

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        currentDraft = "kept keyboard draft"
        engine.listener.onFinal("late speech")

        assertTrue(committed.isEmpty())
        assertEquals(VoiceInputState.Idle, controller.state)
    }

    @Test
    fun cancellingAfterAPartialKeepsTheRecognizedDraftAndFencesALateFinalResult() {
        val engine = FakeVoiceRecognitionEngine()
        var currentDraft = ""
        val committed = mutableListOf<String>()
        val controller =
            VoiceInputController(
                engine,
                {
                    currentDraft = it
                    committed += it
                },
                { currentDraft },
            )

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onPartial("keep after cancel")
        controller.cancel()
        engine.listener.onFinal("late result")

        assertEquals("keep after cancel", currentDraft)
        assertEquals(listOf("keep after cancel"), committed)
        assertEquals(VoiceInputState.Idle, controller.state)
    }

    @Test
    fun aCancelledGenerationCannotCompleteTheNextSession() {
        val engine = FakeVoiceRecognitionEngine()
        val committed = mutableListOf<String>()
        val controller = VoiceInputController(engine, committed::add)

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        val first = engine.listener
        controller.cancel()
        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        first.onFinal("stale")
        engine.listener.onFinal("current")

        assertEquals(listOf("current"), committed)
    }

    @Test
    fun timeoutCancelsRecognitionWithoutWritingTheDraft() {
        val engine = FakeVoiceRecognitionEngine()
        val committed = mutableListOf<String>()
        val controller = VoiceInputController(engine, committed::add)

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        controller.timedOut()

        assertEquals(1, engine.cancelled)
        assertEquals(VoiceInputState.Error(VoiceInputError.Recognition), controller.state)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun partialAfterEndOfSpeechRetainsFinishingUntilTheFinalResultArrives() {
        val engine = FakeVoiceRecognitionEngine()
        var draft = ""
        val controller = VoiceInputController(engine, { draft = it }, { draft })

        controller.start(selection, "", permissionGranted = true, localeTag = "en-US")
        engine.listener.onEndOfSpeech()
        engine.listener.onPartial("still recognizing")

        assertEquals("still recognizing", draft)
        assertEquals(VoiceInputState.Finishing, controller.state)

        engine.listener.onFinal("final transcription")

        assertEquals("final transcription", draft)
        assertEquals(VoiceInputState.Idle, controller.state)
    }

    @Test
    fun watchdogKeepsTheStartDeadlineUntilSpeechBeginsAndDoesNotResetTheFinalDeadline() {
        val scheduled = mutableListOf<() -> Unit>()
        var expired = 0
        val watchdog = VoiceRecognitionWatchdog({ _, callback -> scheduled += callback }) {}

        watchdog.awaitStart { expired++ }
        val start = scheduled.single()
        watchdog.speechStarted { expired++ }
        start()
        assertEquals(0, expired)

        val listening = scheduled.last()
        watchdog.partialResult { expired++ }
        listening()
        assertEquals(0, expired)

        watchdog.awaitFinal { expired++ }
        watchdog.partialResult { expired++ }
        scheduled.last().invoke()
        assertEquals(1, expired)
    }

    @Test
    fun watchdogDoesNotExtendStartupForRmsOrRearmAfterClear() {
        val scheduled = mutableListOf<() -> Unit>()
        var expired = 0
        val watchdog = VoiceRecognitionWatchdog({ _, callback -> scheduled += callback }) {}

        watchdog.awaitStart { expired++ }
        watchdog.audioActivity { expired++ }
        scheduled.single().invoke()
        assertEquals(1, expired)

        watchdog.clear()
        val scheduledCount = scheduled.size
        watchdog.audioActivity { expired++ }
        watchdog.partialResult { expired++ }
        assertEquals(scheduledCount, scheduled.size)
    }

    private class FakeVoiceRecognitionEngine(
        override val onDeviceAvailable: Boolean = false,
        override val systemAvailable: Boolean = true,
    ) : VoiceRecognitionEngine {
        lateinit var listener: VoiceRecognitionListener
        val started = mutableListOf<VoiceInputStart>()
        var cancelled = 0
        var destroyed = 0

        override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) {
            started += start
            this.listener = listener
        }

        override fun stop() = Unit

        override fun cancel() {
            cancelled++
        }

        override fun destroy() {
            destroyed++
        }
    }
}
