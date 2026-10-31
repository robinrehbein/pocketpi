package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorComposerUiTest {
    @get:Rule val compose = createComposeRule()
    private val answers = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private val question = Wire.objectOf("id" to "e", "kind" to "editor", "prefill" to "original")
    private val state = mutableStateOf(RemoteState(
        selection = RemoteSelection("host", "project", "session"), connected = true,
        status = "waiting", questions = listOf(question), capabilitiesKnown = true,
        capabilities = setOf(QUESTION_EDITOR_CAPABILITY),
    ))

    private fun render() {
        compose.setContent { MaterialTheme {
            ChatComposer(state.value, onSend = {}, onStop = {}, onRemoveQuote = {},
                onAnswer = { _, answer -> answers += answer })
        } }
    }

    @Test fun editorCapabilityRevocationDisablesSendAndRegrantRestoresItWhileCancelWorks() {
        render()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(capabilities = emptySet()) }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertEquals("cancel", answers.single().text("kind"))
        compose.runOnIdle { state.value = state.value.copy(capabilities = setOf(QUESTION_EDITOR_CAPABILITY),
            unavailableCapabilities = setOf(QUESTION_EDITOR_CAPABILITY)) }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(unavailableCapabilities = emptySet()) }
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test fun conflatedAuthorityRoundTripResetsDraftEvenWhenFinalSelectionAndConnectionMatch() {
        render()
        compose.onNode(hasSetTextAction()).performTextReplacement("unsent")
        // The collector sees only the final state after a disconnect/reconnect or A-B-A selection.
        compose.runOnIdle {
            val before = state.value
            state.value = before.copy(connected = false, editorQuestionGeneration = before.editorQuestionGeneration + 1)
            state.value = before.copy(editorQuestionGeneration = before.editorQuestionGeneration + 2)
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("original")
    }
}
