package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceInputUiTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun microphonePartialsUpdateTheEditableDraftAndFinalResultDoesNotSendAutomatically() {
        val engine = FakeEngine()
        val harness = showComposer(engine)

        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).performClick()
        compose.runOnIdle { engine.listener.onPartial("editable partial") }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextContains("editable partial")

        compose.runOnIdle { engine.listener.onFinal("editable transcription") }
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertTextContains("editable transcription")
        assertEquals(0, harness.sends)
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        assertEquals(1, harness.sends)
    }

    @Test
    fun typedEditWinsWhenRecognitionDeliversALateFinalResult() {
        val engine = FakeEngine()
        val harness = showComposer(engine)

        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).performClick()
        harness.onKeyboardEdit = { engine.listener.onFinal("late speech") }
        compose.onNode(hasSetTextAction()).performTextInput("typed on keyboard")
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertTextContains("typed on keyboard")
        assertEquals(0, harness.sends)
        assertEquals(1, engine.cancels)
    }

    @Test
    fun actionRetainsStopAndSendEligibilityRules() {
        val engine = FakeEngine()
        val busy = showComposer(engine, RemoteState(connected = true, status = "running"))
        compose.onNodeWithContentDescription(label(R.string.remote_stop)).performClick()
        assertEquals(1, busy.stops)

        val attachment =
            LocalAttachment("attachment", "notes.txt", "file", "text/plain", 4, "a".repeat(64))
        compose.runOnIdle {
            busy.state = RemoteState(connected = true, status = "idle", attachments = listOf(attachment))
        }
        compose.onNodeWithContentDescription(label(R.string.remote_send)).performClick()
        assertEquals(1, busy.sends)

        compose.runOnIdle { busy.state = RemoteState(connected = false) }
        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).assertIsNotEnabled()
        compose.runOnIdle {
            busy.state = RemoteState(connected = true, status = "idle", draft = "send later", sending = true)
        }
        compose.onNodeWithContentDescription(label(R.string.remote_send)).assertIsNotEnabled()
        compose.runOnIdle {
            busy.state =
                RemoteState(
                    connected = true,
                    status = "idle",
                    draft = "send later",
                    configurationChanging = true,
                )
        }
        compose.onNodeWithContentDescription(label(R.string.remote_send)).assertIsNotEnabled()
    }

    @Test
    fun listeningActionFinishesDictationAndFinishingActionCancels() {
        val engine = FakeEngine()
        showComposer(engine)

        compose.onNodeWithContentDescription(label(R.string.remote_voice_start)).performClick()
        compose.onNodeWithContentDescription(label(R.string.remote_voice_finish)).performClick()
        assertEquals(1, engine.stops)

        compose.runOnIdle { engine.listener.onEndOfSpeech() }
        compose.onNodeWithContentDescription(label(R.string.remote_voice_cancel)).performClick()
        assertEquals(1, engine.cancels)
    }

    @Test
    fun systemProviderNoticeUsesTheComposerWidthBeforeRecording() {
        showComposer(FakeEngine(onDeviceAvailable = false))

        compose
            .onNodeWithText(label(R.string.remote_voice_network_notice))
            .assertWidthIsAtLeast(100.dp)
    }

    private fun showComposer(
        engine: FakeEngine,
        initial: RemoteState = RemoteState(connected = true, status = "idle"),
    ): Harness {
        val harness = Harness(initial)
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    state = harness.state,
                    onDraft = {
                        harness.onKeyboardEdit?.invoke()
                        harness.state = harness.state.copy(draft = it)
                    },
                    onSend = { harness.sends++ },
                    onStop = { harness.stops++ },
                    onRemoveQuote = {},
                    voiceEngine = engine,
                    voicePermissionGrantedOverride = true,
                )
            }
        }
        return harness
    }

    private fun label(id: Int) = context.getString(id)

    private class Harness(initial: RemoteState) {
        var state by mutableStateOf(initial)
        var sends = 0
        var stops = 0
        var onKeyboardEdit: (() -> Unit)? = null
    }

    private class FakeEngine : VoiceRecognitionEngine {
        constructor(onDeviceAvailable: Boolean = true) {
            this.onDeviceAvailable = onDeviceAvailable
        }

        override val systemAvailable = true
        override val onDeviceAvailable: Boolean
        lateinit var listener: VoiceRecognitionListener
        var cancels = 0
        var stops = 0

        override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) {
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }

        override fun destroy() = Unit
    }
}
