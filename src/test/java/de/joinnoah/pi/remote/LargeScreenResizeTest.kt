package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A window resized live, as split screen and DeX do now that MainActivity is no longer recreated:
 * one composition goes through every layout bucket and keeps what the user had open.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1700dp-h1000dp")
class LargeScreenResizeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private fun message(id: String, role: String, text: String): JsonObject = buildJsonObject {
        put("id", id)
        put("role", role)
        put("text", text)
    }

    init {
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        val session = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "rpc")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                messages =
                    (0 until 30).flatMap {
                        listOf(
                            message("user-$it", "user", "Question number $it"),
                            message("reply-$it", "assistant", "Reply $it with enough words to wrap over more than one line."),
                        )
                    },
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    private val width = mutableStateOf(720.dp)

    private fun render() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.value, 800.dp))) {
                val configuration =
                    Configuration(LocalConfiguration.current).apply {
                        keyboard = Configuration.KEYBOARD_NOKEYS
                        hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                    }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    MaterialTheme { RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1)) }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Resizes the window and checks that the layout really changed bucket. */
    private fun resize(to: Dp, changesOpen: Boolean = false) {
        width.value = to
        compose.waitForIdle()
        val layout = paneLayout(to, 800.dp)
        // Open changes beside the chat moved the list out of the way where it got cramped.
        if (!layout.twoPane) compose.onNodeWithTag("sessionListPane").assertDoesNotExist()
        else if (!changesOpen || layout.inspector == InspectorMode.OVERLAY) compose.onNodeWithTag("sessionListPane").assertIsDisplayed()
        if (changesOpen && layout.inspector != InspectorMode.OVERLAY) compose.onNodeWithTag("inspectorPane").assertIsDisplayed()
        if (layout.inspector == InspectorMode.OVERLAY) compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
    }

    private fun assertReadingPositionKept() =
        compose.onNode(hasText("Question number 5") and hasAnyAncestor(hasTestTag("conversationList"))).assertIsDisplayed()

    @Test
    fun aLiveResizeKeepsTheDraftTheReadingPositionAndTheOpenChanges() {
        render()
        compose.onNodeWithTag("composerField").performTextInput("half typed")
        compose.onNodeWithTag("conversationList").performScrollToNode(hasText("Question number 5"))
        assertReadingPositionKept()
        compose.runOnIdle { repository.openChanges() }
        compose.onNodeWithTag("changesPane").assertIsDisplayed()
        // Two panes with Changes in the inspector beside the chat (on demand, then always there),
        // and back down to a phone-wide split screen.
        for (size in listOf(960.dp, 1180.dp, 1600.dp, 600.dp)) {
            resize(size, changesOpen = true)
            compose.onNodeWithTag("changesPane").assertIsDisplayed()
            assertNotNull("Changes closed at $size", repository.state.value.changes)
            assertEquals("half typed", repository.state.value.draft)
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        compose.onNodeWithText("half typed").assertIsDisplayed()
        assertReadingPositionKept()
    }

    @Test
    fun theReadingPositionSurvivesEveryWidthBesideTheChat() {
        render()
        compose.onNodeWithTag("conversationList").performScrollToNode(hasText("Question number 5"))
        for (size in listOf(960.dp, 1180.dp, 1600.dp, 600.dp, 720.dp)) {
            resize(size)
            assertReadingPositionKept()
        }
    }
}
