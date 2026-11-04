package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h800dp")
class NewSessionDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun systemBackCollapsesPickerBeforeDismissingSheet() {
        val models = listOf(RemoteModel("provider", "id", "Model", setOf("text")))
        var dismissed = 0
        compose.setContent { MaterialTheme { NewSessionSheet(null, models, {}, { dismissed++ }) } }
        val picker = compose.onNodeWithTag("newSessionModelPicker")
        picker.assertContentDescriptionEquals(
            compose.activity.getString(R.string.remote_new_session_model) + ": " +
                compose.activity.getString(R.string.remote_new_session_model_default),
        )
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            compose.activity.getString(R.string.remote_chat_a11y_collapsed)))
        picker.performClick()
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            compose.activity.getString(R.string.remote_chat_a11y_expanded)))
        compose.onNodeWithTag("newSessionModels").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("newSessionModels").assertDoesNotExist()
        compose.onNodeWithTag("newSessionPrompt").assertIsDisplayed()
        assertEquals(0, dismissed)
        pressBack()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test fun largeCatalogStartsCollapsedAndKeepsProviderIdentity() {
        val models = listOf(
            RemoteModel("one", "same", "Shared", setOf("text")),
            RemoteModel("two", "same", "Shared", setOf("text")),
        ) + (0 until 80).map { RemoteModel("provider", "id$it", "Model $it", setOf("text")) }
        var choice: NewSessionChoice? = null
        compose.setContent { MaterialTheme { NewSessionSheet(null, models, { choice = it }, {}) } }
        compose.onNodeWithTag("newSessionModels").assertDoesNotExist()
        compose.onNodeWithTag("newSessionPrompt").assertIsDisplayed()
        compose.onNodeWithTag("newSessionStart").assertIsDisplayed()
        compose.onNodeWithTag("newSessionModelPicker").performClick()
        compose.onNodeWithText("Shared · two").performClick()
        compose.onNodeWithTag("newSessionModels").assertDoesNotExist()
        compose.onNodeWithTag("newSessionStart").performClick()
        assertEquals(models[1], choice?.model)
    }
}
