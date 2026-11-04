package de.joinnoah.pi.remote

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A link in a chat message goes through RemoteApp's [AdjacentUriHandler]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdjacentLinkWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()

    private val adjacentFlags = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK

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
        val message = buildJsonObject {
            put("id", "reply")
            put("role", "assistant")
            put("text", "Open [the docs](https://example.com/docs) now")
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
                messages = listOf(message),
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    /** Taps the link in the message and returns the intent it started. */
    private fun tapLink(): Intent {
        compose.setContent {
            RemoteApp(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "s1", 1))
        }
        compose.waitForIdle()
        compose.onNode(hasClickAction() and hasParent(hasText("Open the docs now")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val intent = checkNotNull(shadowOf(compose.activity).nextStartedActivity) { "The link started nothing" }
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/docs", intent.dataString)
        return intent
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun aLinkOnAWideWindowOpensBesidePocketPi() {
        assertEquals(adjacentFlags, tapLink().flags and adjacentFlags)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aLinkOnAPhoneOpensAsBefore() {
        assertEquals(0, tapLink().flags and adjacentFlags)
    }

    @Test
    @Config(qualifiers = "sw411dp-w915dp-h411dp-land")
    fun aLinkOnAPhoneInLandscapeOpensAsBefore() {
        assertEquals(0, tapLink().flags and adjacentFlags)
    }
}
