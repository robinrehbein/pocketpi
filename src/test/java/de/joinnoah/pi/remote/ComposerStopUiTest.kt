package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The composer's stop, send mode and prompt suggestion, as the busy row and the chip show them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerStopUiTest {
    @get:Rule val compose = createComposeRule()

    private var stops = 0
    private var aborts = 0
    private var steers = 0
    private var followUps = 0
    private val keys = ChatKeyTarget()
    private val current = mutableStateOf(RemoteState())

    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun state(
        status: String = "running",
        draft: String = "",
        followUps: List<PendingFollowUp> = emptyList(),
        question: Boolean = false,
        messages: List<JsonObject> = emptyList(),
    ) =
        RemoteState(
            selection = RemoteSelection("host", "project", "live"),
            connected = true,
            status = if (question) "waiting" else status,
            draft = draft,
            followUps = followUps,
            messages = messages,
            session = Wire.objectOf("id" to "live", "origin" to "rpc"),
            capabilities = setOf(STEER_CAPABILITY, FOLLOW_UP_CAPABILITY),
            questions =
                if (question) listOf(Wire.objectOf("id" to "q1", "kind" to "plan", "plan" to "Step one"))
                else emptyList(),
        )

    private fun render(initial: RemoteState, predictions: PromptPredictionSource? = null) {
        current.value = initial
        compose.setContent {
            CompositionLocalProvider(
                LocalChatKeyTarget provides keys,
                LocalPromptPredictions provides predictions,
            ) {
                MaterialTheme {
                    ChatComposer(
                        state = current.value,
                        onDraft = {},
                        onSend = {},
                        onStop = { stops++ },
                        onRemoveQuote = {},
                        onFollowUp = { followUps++ },
                        onSteer = { steers++ },
                        onAbort = { aborts++ },
                        voicePermissionGrantedOverride = false,
                    )
                }
            }
        }
    }

    private val queued = listOf(PendingFollowUp("queued", "Also check the logs", "pending"))

    @Test
    fun stopLivesOnlyInTheBusyRowAndSendNeverBecomesStop() {
        render(state())
        compose.onNodeWithTag("stopRun").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(label(R.string.remote_stop)).assertCountEquals(1)
        compose.onNodeWithTag("primaryComposerAction").assertIsNotEnabled()
        compose.onNodeWithTag("stopRun").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun stoppingWithQueuedFollowUpsAsksFirstAndDismissKeepsTheRun() {
        render(state(followUps = queued))
        compose.onNodeWithTag("stopRun").performClick()
        compose.onNodeWithText(label(R.string.remote_stop_queued_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_stop_queued_dismiss)).performClick()
        compose.onNodeWithTag("confirmStop").assertDoesNotExist()
        assertEquals(0, stops)

        compose.onNodeWithTag("stopRun").performClick()
        compose.onNodeWithTag("confirmStop").assertIsDisplayed().performClick()
        assertEquals(1, stops)
    }

    @Test
    fun ctrlPeriodStopsThroughTheConfirmationWhenFollowUpsAreQueued() {
        render(state(followUps = queued))
        compose.runOnIdle { keys.stopNow() }
        compose.onNodeWithTag("confirmStop").assertIsDisplayed()
        assertEquals(0, stops)
        compose.onNodeWithTag("confirmStop").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun escapeStopsAtOnceWhenNothingIsQueued() {
        render(state())
        compose.runOnIdle { keys.stopNow() }
        compose.onNodeWithTag("confirmStop").assertDoesNotExist()
        assertEquals(1, stops)
    }

    @Test
    fun questionAbortWithQueuedFollowUpsUsesTheSameConfirmation() {
        render(state(followUps = queued, question = true))
        compose.onNodeWithTag("abortRun").performClick()
        // The one confirmation names both losses: the queued messages and the pending answers.
        compose.onNodeWithText(label(R.string.remote_stop_queued_answers_body)).assertIsDisplayed()
        compose.onNodeWithTag("confirmStop").assertIsDisplayed().performClick()
        assertEquals(0, aborts)
        assertEquals(1, stops)
    }

    @Test
    fun questionAbortWithNothingQueuedLeavesTheConfirmationToTheChat() {
        render(state(question = true))
        compose.onNodeWithTag("abortRun").performClick()
        compose.onNodeWithTag("confirmStop").assertDoesNotExist()
        assertEquals(1, aborts)
        assertEquals(0, stops)
    }

    @Test
    fun sendModeChoiceShowsBothVerbsAndSendsWithTheChosenMode() {
        render(state(draft = "Next step"))
        compose.onNodeWithText(label(R.string.remote_send_now)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_send_when_done)).assertIsDisplayed()

        compose.onNodeWithTag("steer").performClick()
        assertEquals(1, steers)
        assertEquals(0, followUps)

        compose.onNodeWithTag("sendWhenDone").performClick()
        compose.onNodeWithTag("followUp").performClick()
        assertEquals(1, steers)
        assertEquals(1, followUps)
        assertEquals(0, stops)
    }

    @Test
    fun suggestionIsATapTargetChipAndTheFieldKeepsItsLabel() {
        val source = FakePredictions()
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
        val suggestion = label(R.string.remote_prediction_run_tests)
        render(state(status = "idle", messages = messages), predictions = source)
        compose.onNodeWithTag("promptSuggestion").assertContentDescriptionEquals(
            RuntimeEnvironment.getApplication().getString(R.string.remote_suggestion_use, suggestion)
        )
        // The suggestion is the grey placeholder; the field keeps the plain prompt as its name.
        compose.onNodeWithTag("composerPlaceholder", useUnmergedTree = true).assertTextEquals(suggestion)
        compose.onNodeWithTag("composerField").assertContentDescriptionEquals(label(R.string.remote_prompt))

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("promptSuggestion").performClick()
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS)
        compose.onNode(hasSetTextAction()).assertTextContains(suggestion)
    }

    @Test
    fun composerFieldIsNamedOnlyWhileEmptySoTypedTextIsWhatTalkBackReads() {
        render(state(status = "idle"))
        val name = label(R.string.remote_prompt)
        compose.onNodeWithTag("composerPlaceholder", useUnmergedTree = true).assertTextEquals(name)
        compose.onNodeWithTag("composerField").assertContentDescriptionEquals(name)
        compose.onNodeWithTag("composerField").performTextInput("Deploy it")
        compose.waitForIdle()
        compose.onNodeWithTag("composerPlaceholder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertTextEquals("Deploy it")
        compose.onNodeWithTag("composerField").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)
        )
    }

    private class FakePredictions : PromptPredictionSource {
        override val enabled = MutableStateFlow(true)
        override val history = MutableStateFlow(PromptHistory())

        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override fun clear() {
            history.value = PromptHistory()
        }

        override fun record(routeId: String, projectId: String, text: String) = Unit

        override fun recordPlanApproval(routeId: String, sessionId: String) = Unit
    }
}
