package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a phone the chat's compact header leaves with a drag toward newer messages and returns with one back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CompactHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")

    /** Opens a chat of [count] messages on a phone, the way a user gets there from the lists. */
    private fun openLongChat(count: Int = 80) {
        val project = Wire.objectOf("id" to "project", "name" to "noah-monorepo")
        val session = Wire.objectOf("id" to "s1", "title" to "First session", "status" to "idle", "origin" to "rpc")
        val repository = NavigationFakeRepository()
        val listed =
            RemoteState(
                hosts = listOf(host),
                hostsLoaded = true,
                host = host,
                connected = true,
                connection = R.string.remote_connected,
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
            )
        repository.state.value = listed
        compose.setContent { RemoteApp(repository, FakeSettingsRepository(), false, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Studio Mac").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("noah-monorepo").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("First session").performClick()
        compose.waitForIdle()
        val messages =
            (1..count).map { index ->
                Wire.objectOf(
                    "id" to "m$index",
                    "role" to if (index % 2 == 0) "assistant" else "user",
                    "text" to "Message $index",
                    "state" to "complete",
                )
            }
        repository.state.value =
            listed.copy(
                selection = RemoteSelection("host", "project", "s1"),
                session = session,
                status = "idle",
                messages = messages,
            )
        compose.waitForIdle()
    }

    private fun headerBottom() = compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().bottom

    @Test
    fun aDragTowardNewerMessagesHidesTheHeaderAndADragBackShowsIt() {
        openLongChat()
        val list = compose.onNodeWithTag("conversationList")
        // The chat opens at its end; a drag back to older messages keeps the header.
        list.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(headerBottom() > 0.dp)
        val top = compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().top

        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("hidden header still reaches ${headerBottom()}", headerBottom() <= 0.dp)

        list.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().top)
    }
}
