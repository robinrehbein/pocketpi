package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuestionGlassUiTest {
    @get:Rule val compose = createComposeRule()

    private val question = Json.parseToJsonElement(
        """{"id":"glass-question","kind":"questionnaire","title":"Choose a path","questions":[{"id":"path","prompt":"Which path?","required":true,"multiSelect":false,"allowOther":false,"options":[{"label":"Continue","value":"continue"}],"defaults":[]}]}""",
    ).jsonObject

    private fun show(pending: Boolean = false, value: JsonObject = question, onAnswer: (JsonObject) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ProvideGlass {
                    FloatingSurface(Modifier.fillMaxWidth()) {
                        Column {
                            QuestionCard(question = value, enabled = true, pending = pending, embedded = true, onAnswer = onAnswer)
                        }
                    }
                }
            }
        }
    }

    @Test fun embeddedQuestionKeepsSelectionAndAnswerPayload() {
        var answer: JsonObject? = null
        show { answer = it }
        compose.onNodeWithText("Choose a path").assertExists()
        compose.onNodeWithText("Continue", substring = true).performClick()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_answer)
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        assertEquals(
            Json.parseToJsonElement("""{"kind":"questionnaire","cancelled":false,"answers":[{"id":"path","skipped":false,"selections":[{"value":"continue","label":"Continue","index":1,"wasCustom":false}]}]}"""),
            answer,
        )
    }

    @Test fun embeddedPendingQuestionCannotSubmit() {
        val validQuestion = Json.parseToJsonElement(question.toString().replace("\"defaults\":[]", "\"defaults\":[\"continue\"]")).jsonObject
        show(pending = true, value = validQuestion)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.remote_answer_sending)
        compose.onNodeWithText(label).assertIsNotEnabled()
    }
}
