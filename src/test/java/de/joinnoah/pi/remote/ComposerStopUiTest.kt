package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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

    private var sends = 0
    private var stops = 0
    private var aborts = 0
    private var steers = 0
    private var followUps = 0
    private var touchSlop = 0f
    private var voiceStarts = 0
    private val voiceEngine = object : VoiceRecognitionEngine {
        override val systemAvailable = true
        override val onDeviceAvailable = true
        override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) { voiceStarts++ }
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }
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

    private fun render(
        initial: RemoteState,
        predictions: PromptPredictionSource? = null,
        hardwareKeyboard: Boolean = false,
        widthDp: Int = 360,
        fontScale: Float = 1f,
    ) {
        current.value = initial
        compose.setContent {
            val density = LocalDensity.current
            touchSlop = LocalViewConfiguration.current.touchSlop
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalChatKeyTarget provides keys,
                LocalPromptPredictions provides predictions,
                LocalHardwareKeyboard provides hardwareKeyboard,
            ) {
                MaterialTheme {
                    Box(Modifier.width(widthDp.dp)) {
                    ChatComposer(
                        state = current.value,
                        onDraft = {},
                        onSend = { sends++ },
                        onStop = { stops++ },
                        onRemoveQuote = {},
                        onFollowUp = { followUps++ },
                        onSteer = { steers++ },
                        onAbort = { aborts++ },
                        voiceEngine = voiceEngine,
                        voicePermissionGrantedOverride = true,
                    )
                    }
                }
            }
        }
    }

    private val queued = listOf(PendingFollowUp("queued", "Also check the logs", "pending"))

    @Test
    fun sendAndStopRemainAvailableOnNarrowScreenWithLargeFonts() {
        render(state(draft = "Next step"), widthDp = 280, fontScale = 2f)
        val stop = compose.onNodeWithTag("stopRun").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("sendModeMenu").assertDoesNotExist()
        val send = compose.onNodeWithTag("steer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val minSize = 48f * compose.density.density
        org.junit.Assert.assertTrue(stop.width >= minSize && stop.height >= minSize)
        org.junit.Assert.assertTrue(send.width >= minSize && send.height >= minSize)
        org.junit.Assert.assertTrue(stop.right <= send.left)
        compose.onNodeWithTag("steer").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("followUp").performClick()
        assertEquals(1, followUps)
    }

    @Test
    fun touchSwipesOnlySwitchModeAndTapsSendExactlyOnce() {
        render(state(draft = "Next step"))
        compose.onNodeWithTag("steer").performTouchInput { swipeLeft() }
        assertEquals(0, steers)
        assertEquals(0, followUps)
        compose.onNodeWithTag("followUp").performTouchInput { click() }
        assertEquals(1, followUps)
        compose.onNodeWithTag("followUp").performTouchInput { swipeRight() }
        assertEquals(1, followUps)
        compose.onNodeWithTag("steer").performTouchInput { click() }
        assertEquals(1, steers)
    }

    @Test
    fun diagonalBeyondRadialSlopNeverSendsOrSwitchesEvenAfterReturningOrCancel() {
        render(state(draft = "Next step"))
        val diagonal = Offset(touchSlop * 0.8f, touchSlop * 0.8f)
        compose.onNodeWithTag("steer").performTouchInput {
            down(center)
            moveTo(center + diagonal)
            up()
        }
        compose.onNodeWithTag("steer").assertIsDisplayed()
        compose.onNodeWithTag("steer").performTouchInput {
            down(center)
            moveTo(center + diagonal)
            moveTo(center)
            up()
        }
        compose.onNodeWithTag("steer").assertIsDisplayed()
        compose.onNodeWithTag("steer").performTouchInput {
            down(center)
            moveTo(center + diagonal)
            cancel()
        }
        compose.onNodeWithTag("steer").assertIsDisplayed()
        assertEquals(0, steers)
        assertEquals(0, followUps)
    }

    @Test
    fun horizontalIntentAtRadialCrossingSwitchesOnlyOnce() {
        render(state(draft = "Next step"))
        compose.onNodeWithTag("steer").performTouchInput {
            down(center)
            moveTo(center + Offset(touchSlop * 0.95f, touchSlop * 0.35f))
            moveTo(center + Offset(touchSlop * 1.3f, touchSlop * 0.35f))
            up()
        }
        compose.onNodeWithTag("followUp").assertIsDisplayed()
        assertEquals(0, steers)
        assertEquals(0, followUps)
    }

    @Test
    fun unavailableModeDoesNotSwitchOrSendOnSwipe() {
        render(state(draft = "Next step").copy(capabilities = setOf(STEER_CAPABILITY)))
        compose.onNodeWithTag("sendModeMenu").assertDoesNotExist()
        compose.onNodeWithTag("steer").performTouchInput { swipeLeft() }
        assertEquals(0, steers)
        compose.onNodeWithTag("steer").performTouchInput { click() }
        assertEquals(1, steers)
    }

    @Test
    fun cancelledTouchAndSessionChangeNeverSend() {
        render(state(draft = "Next step"))
        compose.onNodeWithTag("steer").performTouchInput { down(center); cancel() }
        assertEquals(0, steers)
        compose.onNodeWithTag("steer").performTouchInput { down(center) }
        compose.runOnIdle {
            current.value = state(draft = "Next step").copy(
                selection = RemoteSelection("host", "project", "other"),
            )
        }
        compose.onNodeWithTag("steer").performTouchInput { up() }
        assertEquals(0, steers)
        assertEquals(0, followUps)
    }

    @Test
    fun idleSendTouchCannotBecomeBusyAction() {
        render(state(status = "idle", draft = "Next step"))
        compose.onNodeWithTag("primaryComposerAction").performTouchInput { down(center) }
        compose.runOnIdle { current.value = current.value.copy(status = "running") }
        compose.onNodeWithTag("steer").performTouchInput { up() }
        assertEquals(0, sends)
        assertEquals(0, steers)
        assertEquals(0, followUps)
        compose.onNodeWithTag("steer").performTouchInput { click() }
        assertEquals(1, steers)
    }

    @Test
    fun voiceTouchCannotBecomeBusyAction() {
        render(state(status = "idle"))
        compose.onNodeWithTag("primaryComposerAction").performTouchInput { down(center) }
        compose.runOnIdle { current.value = current.value.copy(status = "running", draft = "Next step") }
        compose.onNodeWithTag("steer").performTouchInput { up() }
        assertEquals(0, sends)
        assertEquals(0, steers)
        assertEquals(0, followUps)
        assertEquals(0, voiceStarts)
    }

    @Test
    fun unchangedIdleAndVoiceTouchActivateOnce() {
        render(state(status = "idle"))
        compose.onNodeWithTag("primaryComposerAction").performTouchInput { click() }
        assertEquals(1, voiceStarts)
    }

    @Test
    fun unchangedIdleSendTouchActivatesOnce() {
        render(state(status = "idle", draft = "Next step"))
        compose.onNodeWithTag("primaryComposerAction").performTouchInput { click() }
        assertEquals(1, sends)
    }

    @Test
    fun heldKeyboardActivationCannotRerouteWhenModeChanges() {
        render(state(draft = "Next step"), hardwareKeyboard = true)
        compose.onNodeWithTag("steer").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("steer").performKeyInput { keyDown(Key.Spacebar) }
        compose.runOnIdle { current.value = current.value.copy(capabilities = setOf(FOLLOW_UP_CAPABILITY)) }
        compose.onNodeWithTag("followUp").assertIsFocused().performKeyInput { keyUp(Key.Spacebar) }
        assertEquals(0, sends)
        assertEquals(0, steers)
        assertEquals(0, followUps)
        compose.onNodeWithTag("followUp").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, followUps)
    }

    @Test
    fun capabilityChangeDuringPressDoesNotSend() {
        render(state(draft = "Next step"))
        compose.onNodeWithTag("steer").performTouchInput { down(center) }
        compose.runOnIdle {
            current.value = current.value.copy(capabilities = setOf(FOLLOW_UP_CAPABILITY))
        }
        compose.onNodeWithTag("followUp").performTouchInput { up() }
        assertEquals(0, steers)
        assertEquals(0, followUps)
    }

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
    fun sendModeSwipesNameTheChosenModeAndSendWithIt() {
        render(state(draft = "Next step"))
        compose.onNodeWithTag("sendModeMenu").assertDoesNotExist()
        compose.onNodeWithTag("steer").assertContentDescriptionEquals(label(R.string.remote_send_now))
            .performTouchInput { swipeLeft() }
        compose.onNodeWithTag("followUp").assertContentDescriptionEquals(label(R.string.remote_send_when_done))
            .performTouchInput { swipeRight() }
        compose.onNodeWithTag("steer").assertContentDescriptionEquals(label(R.string.remote_send_now))
        assertEquals(0, steers)
        assertEquals(0, followUps)

        compose.onNodeWithTag("steer").performClick()
        assertEquals(1, steers)
        assertEquals(0, followUps)

        compose.onNodeWithTag("steer").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("followUp").assertContentDescriptionEquals(label(R.string.remote_send_when_done))
            .performClick()
        assertEquals(1, steers)
        assertEquals(1, followUps)
        assertEquals(0, stops)
    }

    @Test
    fun accessibilityActionSwitchesToTheNamedSendModeWithoutSending() {
        render(state(draft = "Next step"))
        val action = compose.onNodeWithTag("steer").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single { it.label == label(R.string.remote_send_when_done) }
        compose.runOnIdle { org.junit.Assert.assertTrue(action.action()) }
        compose.onNodeWithTag("followUp")
            .assertContentDescriptionEquals(label(R.string.remote_send_when_done))
        assertEquals(0, sends)
        assertEquals(0, steers)
        assertEquals(0, followUps)
        assertEquals(0, stops)
    }

    @Test
    fun suggestionIsTheGreyPlaceholderWithAnAcceptButtonAndTheFieldKeepsItsLabel() {
        val source = FakePredictions()
        val messages = editedFileMessages()
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun withAKeyboardHoveringTheSuggestionButtonNamesTab() {
        render(
            state(status = "idle", messages = editedFileMessages()),
            predictions = FakePredictions(),
            hardwareKeyboard = true,
        )
        compose.onNodeWithTag("promptSuggestion").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        val tooltip = RuntimeEnvironment.getApplication().getString(
            R.string.remote_shortcut_tooltip,
            label(R.string.remote_suggestion_take),
            label(R.string.remote_key_tab),
        )
        compose.onNodeWithText(tooltip).assertExists()
    }

    @Test
    fun theSuggestionButtonGoesWithTheFirstTypedCharacterBeforeTheDraftIsSaved() {
        val source = FakePredictions()
        val messages = editedFileMessages()
        render(state(status = "idle", messages = messages), predictions = source)
        compose.onNodeWithTag("promptSuggestion").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("composerField").performTextInput("a")
        compose.mainClock.advanceTimeByFrame()
        // The draft reaches the state only after the pause, so a button still shown here would
        // replace the typed "a" with the suggestion.
        compose.onNodeWithTag("promptSuggestion").assertDoesNotExist()
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

    /** A finished turn that edited a file, so the prediction is "Run the tests". */
    private fun editedFileMessages() =
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
}
