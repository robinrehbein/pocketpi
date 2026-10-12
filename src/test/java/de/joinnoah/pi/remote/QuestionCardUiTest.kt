package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuestionCardUiTest {
    @get:Rule val compose = createComposeRule()
    private val answers = mutableListOf<JsonObject>()
    private val pending = mutableStateOf(false)
    private val enabled = mutableStateOf(true)
    private val question = mutableStateOf(parse("""{"id":"q","kind":"confirm","message":"Continue?"}"""))

    private fun parse(value: String) = Wire.json.parseToJsonElement(value).jsonObject
    private fun render(value: String) {
        question.value = parse(value)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    QuestionCard(question.value, enabled.value, pending.value) { answers += it }
                }
            }
        }
    }
    private fun change(value: String) = compose.runOnIdle { question.value = parse(value) }
    private fun assertControlsDisabled() {
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }).assertIsNotEnabled()
        }
        compose.onAllNodes(hasSetTextAction()).assertAll(isNotEnabled())
    }

    @Test fun confirmBooleanAndCancelPayloadsAreExact() {
        render("""{"id":"q","kind":"confirm","message":"Continue?"}""")
        compose.onNodeWithText("Yes").performClick()
        compose.onNodeWithText("No").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(
            Wire.objectOf("kind" to "confirm", "value" to true),
            Wire.objectOf("kind" to "confirm", "value" to false),
            Wire.objectOf("kind" to "cancel"),
        ), answers)
    }

    @Test fun selectAndInputStringPayloadsAndInputLimitAreExact() {
        render("""{"id":"s","kind":"select","options":["First","Second"]}""")
        compose.onNodeWithText("Submit answer").assertIsNotEnabled()
        compose.onNodeWithText("Second").performClick()
        compose.onNodeWithText("Submit answer").performClick()
        change("""{"id":"i","kind":"input","placeholder":"Write here"}""")
        compose.onNodeWithText("Submit answer").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("x".repeat(16385))
        compose.onNodeWithText("Submit answer").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(
            Wire.objectOf("kind" to "select", "value" to "Second"),
            Wire.objectOf("kind" to "input", "value" to ""),
            Wire.objectOf("kind" to "input", "value" to "x".repeat(16384)),
            Wire.objectOf("kind" to "cancel"),
        ), answers)
    }

    @Test fun pendingAndDisconnectedDisableEveryDialogControl() {
        render("""{"id":"q","kind":"confirm","message":"Continue?"}""")
        val dialogs = listOf(
            """{"id":"q","kind":"confirm","message":"Continue?"}""",
            """{"id":"s","kind":"select","options":["First"]}""",
            """{"id":"i","kind":"input"}""",
            """{"id":"p","kind":"plan","plan":"A plan"}""",
            questionnaire,
        )
        for (disconnected in listOf(false, true)) {
            compose.runOnIdle { enabled.value = !disconnected; pending.value = !disconnected }
            for (dialog in dialogs) {
                change(dialog)
                assertControlsDisabled()
            }
        }
        assertTrue(answers.isEmpty())
    }

    @Test fun planFeedbackIsDisabledWhilePending() {
        render("""{"id":"p","kind":"plan","plan":"A plan"}""")
        compose.onNodeWithText("Request changes").performClick()
        compose.runOnIdle { pending.value = true }
        assertControlsDisabled()
        assertTrue(answers.isEmpty())
    }

    @Test fun disabledSubmitActionCannotSendAnAnswer() {
        render("""{"id":"q","kind":"confirm","message":"Continue?"}""")
        val action = compose.onNodeWithText("Yes").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.OnClick)!!.action!!
        compose.runOnIdle { pending.value = true }
        compose.waitForIdle()
        compose.runOnIdle { action() }
        assertTrue(answers.isEmpty())
    }

    @Test fun localAndUnknownKindsOnlyShowTheMacHint() {
        render("""{"id":"q","kind":"local_required"}""")
        for (kind in listOf("local_required", "custom", "editor", "future")) {
            change("""{"id":"$kind","kind":"$kind"}""")
            compose.onNodeWithText("Input needed on the Mac").assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText("Cancel").assertDoesNotExist()
        }
        assertTrue(answers.isEmpty())
    }

    @Test fun questionnaireRetainsDefaultsRequiredValidationSkipAndStructuredSelections() {
        render(questionnaire)
        compose.onNodeWithText("Submit answer").performScrollTo().performClick()
        assertEquals(parse("""{"kind":"questionnaire","cancelled":false,"answers":[{"id":"required","skipped":false,"selections":[{"value":"a","label":"Alpha","index":1,"wasCustom":false}]},{"id":"optional","skipped":false,"selections":[{"value":"custom default","label":"custom default","wasCustom":true}]}]}"""), answers.single())
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        compose.onNodeWithText("Submit answer").performScrollTo().performClick()
        assertEquals(parse("""{"id":"optional","skipped":true,"selections":[]}"""), answers.last().array("answers")[1])
        change("""{"id":"empty","kind":"questionnaire","questions":[{"id":"r","prompt":"Required","multiSelect":false,"required":true,"allowOther":false,"defaults":[],"options":[{"value":"b","label":"Beta"}]}]}""")
        compose.onNodeWithText("Submit answer").assertIsNotEnabled()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithText("Submit answer").assertIsEnabled()
    }

    private val questionnaire = """{"id":"form","kind":"questionnaire","questions":[{"id":"required","prompt":"Required","multiSelect":false,"required":true,"allowOther":false,"defaults":["a"],"options":[{"value":"a","label":"Alpha"}]},{"id":"optional","prompt":"Optional","multiSelect":false,"required":false,"allowOther":true,"defaults":["custom default"],"options":[]}]}"""
}
