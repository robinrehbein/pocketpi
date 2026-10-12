package de.joinnoah.pi.remote

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.wear.compose.material.MaterialTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w240dp-h240dp-round", application = Application::class)
class WatchUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private fun model(repository: WatchFakeRepository, screen: String = "HOSTS"): WatchViewModel {
        val saved = if (screen == "HOSTS") SavedStateHandle() else SavedStateHandle(mapOf("screen" to screen, "route" to "r", "project" to "p", "session" to "s"))
        return WatchViewModel(repository, saved).also { store.put("watch", it) }
    }
    @After fun cleanup() { compose.runOnIdle { store.clear() } }
    @Test fun `round watch opens hosts projects sessions and composer`() {
        val repository = WatchFakeRepository()
        val model = model(repository)
        compose.setContent { MaterialTheme { WearApp(model, DefaultSettingsRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext())) } }
        compose.onNodeWithText("Mac").performClick()
        compose.onNodeWithText("Project").performClick()
        compose.onNodeWithText("Sessions").performClick()
        compose.onNodeWithText("Session").performClick()
        compose.onNodeWithText("Write a message").performClick()
        compose.onNodeWithContentDescription("Tap to type").performTextInput("Watch draft")
        compose.runOnIdle {
            assertEquals("Watch draft", repository.state.value.draft)
            assertEquals(0, repository.prompts)
        }
    }
    @Test fun `git base keeps discovered branch label while status is cleared`() {
        val repository = WatchFakeRepository()
        val model = model(repository)
        val state = repository.state.value.copy(
            changes = ChangesState("s", loading = false, status = null, devBase = GitDevBase("origin/main")),
        )
        compose.setContent {
            MaterialTheme {
                WatchList("Changes", "changes", false, {}) {
                    watchChanges(state, model, 0, {})
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("main"))
        compose.onNodeWithText("main").assertExists()
        compose.onNodeWithText("dev").assertDoesNotExist()
    }
    @Test fun `questionnaire uses separate questions and review before sending`() {
        val repository = WatchFakeRepository()
        val questions = JsonArray((1..2).map { number -> Wire.objectOf(
            "id" to "q$number", "prompt" to "Pick $number", "required" to true, "multiSelect" to false, "allowOther" to false,
            "defaults" to JsonArray(emptyList()),
            "options" to JsonArray(listOf(Wire.objectOf("label" to "Answer $number", "value" to "a$number"))),
        ) })
        val question = Wire.objectOf("id" to "question", "kind" to "questionnaire", "title" to "Questions", "questions" to questions)
        val model = model(repository, "CHAT")
        compose.setContent {
            val state by repository.state.collectAsState()
            MaterialTheme { WatchQuestionScreen(model, state.copy(connected = true), question) }
        }
        compose.onNodeWithText("Pick 1").assertExists()
        compose.onNodeWithText("Pick 2").assertDoesNotExist()
        compose.onNodeWithText("Answer 1").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Pick 2").assertExists()
        compose.onNodeWithText("Answer 2").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Tap a question to review its complete answers. Submit when ready.").assertExists()
        compose.runOnIdle { assertNull(repository.answer) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Submit answer"))
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals("questionnaire", repository.answer?.text("kind")) }
    }
}
