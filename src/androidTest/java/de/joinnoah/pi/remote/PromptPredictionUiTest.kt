package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PromptPredictionUiTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val suggestion get() = context.getString(R.string.remote_prediction_run_tests)

    @Test
    fun emptyComposerShowsTheSuggestionAsAChipAndOneTapAcceptsIt() {
        val harness = show()
        compose.onNodeWithTag("promptSuggestion").assertTextContains(suggestion)
        compose.onNodeWithTag("promptSuggestion").performClick()
        compose.waitForIdle()
        // An accepted suggestion reaches the repository after the composer's pause.
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(suggestion, harness.state.draft)
        compose.onNode(hasSetTextAction()).assertTextContains(suggestion)
        val selection = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(suggestion.length), selection)
    }

    @Test
    fun tabStillAcceptsTheSuggestion() {
        val harness = show()
        // Key events go to the focused node only; a user presses Tab while typing in the field.
        compose.onNodeWithTag("composerField").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        assertEquals(suggestion, harness.state.draft)
    }

    @Test
    fun typingHidesTheSuggestionAndSendingRecordsThePrompt() {
        val harness = show()
        compose.onNode(hasSetTextAction()).performTextInput("Deploy it")
        compose.onNodeWithTag("promptSuggestion").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.remote_send)).performClick()
        assertEquals(listOf("r/p" to "Deploy it"), harness.source.recorded)
    }

    @Test
    fun turningPredictionsOffRemovesTheChipAndKeepsThePlainLabel() {
        val harness = show()
        compose.runOnIdle { harness.source.enabled.value = false }
        compose.onNodeWithTag("promptSuggestion").assertDoesNotExist()
        compose.onNodeWithTag("composerPlaceholder", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.remote_prompt))
    }

    private fun show(): Harness {
        val messages =
            listOf(
                Wire.objectOf("id" to "u", "role" to "user", "text" to "Change it", "state" to "complete"),
                Wire.objectOf(
                    "id" to "a",
                    "role" to "assistant",
                    "text" to "",
                    "state" to "complete",
                    "parts" to JsonArray(
                        listOf(Wire.objectOf("type" to "toolCall", "id" to "c", "name" to "edit", "arguments" to "{}"))
                    ),
                ),
                Wire.objectOf(
                    "id" to "t",
                    "role" to "tool",
                    "text" to "ok",
                    "state" to "complete",
                    "toolName" to "edit",
                    "toolCallId" to "c",
                ),
            )
        val harness =
            Harness(
                RemoteState(
                    connected = true,
                    status = "idle",
                    selection = RemoteSelection("r", "p", "s"),
                    messages = messages,
                )
            )
        compose.setContent {
            CompositionLocalProvider(LocalPromptPredictions provides harness.source) {
                MaterialTheme {
                    ChatComposer(
                        state = harness.state,
                        onDraft = { harness.state = harness.state.copy(draft = it) },
                        onSend = { harness.state = harness.state.copy(draft = "") },
                        onStop = {},
                        onRemoveQuote = {},
                        voicePermissionGrantedOverride = false,
                        hardwareKeyboard = true,
                    )
                }
            }
        }
        return harness
    }

    private class Harness(initial: RemoteState) {
        var state by mutableStateOf(initial)
        val source = FakeSource()
    }

    private class FakeSource : PromptPredictionSource {
        override val enabled = MutableStateFlow(true)
        override val history = MutableStateFlow(PromptHistory())
        val recorded = mutableListOf<Pair<String, String>>()

        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override fun clear() {
            history.value = PromptHistory()
        }

        override fun record(routeId: String, projectId: String, text: String) {
            recorded += projectKey(routeId, projectId) to text
        }

        override fun recordPlanApproval(routeId: String, sessionId: String) {}
    }
}
