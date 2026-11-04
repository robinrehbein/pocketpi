package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The copy and the semantics of the session controls' sheets and slash suggestions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun label(id: Int) = compose.activity.getString(id)

    private fun controls(
        state: RemoteState,
        localCommands: List<LocalCommand> = emptyList(),
        reloading: Boolean = false,
        modelPickerRequested: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                SessionControls(
                    state = state,
                    refreshConfiguration = {}, refreshContextUsage = {}, compactContext = {}, refreshAdvisor = {},
                    setAdvisor = { _, _, _ -> }, setModel = { _, _ -> }, setThinking = {},
                    refreshCommands = {}, selectCommand = {},
                    localCommands = localCommands,
                    reloading = reloading,
                    modelPickerRequested = modelPickerRequested,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsScopeNoteSitsUnderTheTitle() {
        compose.setContent {
            MaterialTheme {
                SessionSettingsSheetBody(
                    SessionSettings(autoCompaction = true, steeringMode = QUEUE_MODE_ONE_AT_A_TIME, followUpMode = QUEUE_MODE_ALL),
                    enabled = true,
                    busy = false,
                    working = false,
                    onChange = { _, _, _ -> },
                )
            }
        }
        val title = compose.onNodeWithText(label(R.string.remote_session_settings_title)).fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithTag("sessionSettingsScope")
            .assert(hasText(label(R.string.remote_session_settings_scope)))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val firstControl = compose.onNodeWithTag("settingsAutoCompaction").fetchSemanticsNode().boundsInRoot
        assertTrue("scope $scope should follow the title $title", scope.top >= title.bottom)
        assertTrue("scope $scope should come before the controls $firstControl", scope.bottom <= firstControl.top)
    }

    @Test
    fun theModelPickerSaysWhenTheModelCanChange() {
        val model = RemoteModel("provider", "first", "First model", setOf("text"))
        controls(
            RemoteState(
                connected = true,
                status = "running",
                selection = RemoteSelection("host", "project", "session"),
                capabilities = setOf(CONFIGURATION_CAPABILITY),
                configuration = SessionConfiguration(model, "medium", listOf("low", "medium"), listOf(model), false),
            ),
            modelPickerRequested = true,
        )
        compose.onNodeWithText(label(R.string.remote_model_busy_note)).assertIsDisplayed()
    }

    @Test
    fun dimmedSlashCommandsSayTheyAreUnavailable() {
        val state =
            RemoteState(
                connected = true,
                status = "idle",
                draft = "/",
                selection = RemoteSelection("host", "project", "session"),
                session = Wire.objectOf("id" to "session", "origin" to "tui"),
                capabilities = setOf(RELOAD_CAPABILITY, SESSION_TREE_CAPABILITY),
            )
        controls(state, localCommands = listOf(LocalCommand.TREE, LocalCommand.RELOAD))
        val unavailable = label(R.string.remote_slash_command_unavailable)
        // A terminal session lists /reload only for its explanation.
        compose.onNode(hasText("/reload"), useUnmergedTree = false)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, unavailable))
        compose.onNode(hasText("/tree"), useUnmergedTree = false)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
