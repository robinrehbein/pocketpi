package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "New chat with summary" on the real chat screen: the offer, the progress, the move and the new chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HandoverScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private fun worker(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("status", "idle")
        put("origin", "tui")
        put(HOST_WORKER_KEY, true)
    }

    private val old = worker("old-session", "Old chat")
    private val new = worker("new-session", "New chat")

    private fun start(sessionId: String) {
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        val session = if (sessionId == "old-session") old else new
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(old, new),
                session = session,
                capabilities = setOf(COMPACT_CAPABILITY, SESSION_HANDOVER_CAPABILITY, HOST_WORKER_V2_CAPABILITY),
                contextUsage = SessionContextUsage(sessionId, null, null, 160_000, 200_000, 80.0),
            )
        val chat = RemoteSelection("host", "project", sessionId)
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = chat)
            chat
        }
        // Opening another chat shows that chat's session, as the real repository does.
        repository.activation = { selection, _ ->
            val shown = listOf(old, new).firstOrNull { it.text("id") == selection.sessionId }
            if (shown != null) repository.state.value = repository.state.value.copy(session = shown)
            selection
        }
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", sessionId, 1))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertExists()
    }

    private fun string(id: Int) = compose.activity.getString(id)

    private fun openStatusSheet() {
        compose.onNodeWithTag("chatStatusChip").performClick()
        compose.waitForIdle()
    }

    private fun handover(value: SessionHandover?) {
        repository.state.value = repository.state.value.copy(handover = value)
        compose.waitForIdle()
    }

    @Test
    fun oneTapStartsItAndTheProgressShows() {
        start("old-session")
        openStatusSheet()
        compose.onNodeWithTag("contextOfferHandover").performClick()
        compose.waitForIdle()
        assertEquals(1, repository.handovers)

        repository.state.value = repository.state.value.copy(handoverRequesting = true)
        compose.waitForIdle()
        compose.onAllNodesWithText(string(R.string.remote_handover_summarizing)).assertCountEquals(1)
        repository.state.value = repository.state.value.copy(handoverRequesting = false)
        handover(SessionHandover("h1", HandoverState.SWITCHING, live = true))
        compose.onAllNodesWithText(string(R.string.remote_handover_switching)).assertCountEquals(1)
        // The offer waits while it runs.
        compose.onAllNodesWithText(compose.activity.getString(R.string.remote_context_full_title, 80)).assertCountEquals(0)
    }

    @Test
    fun doneOpensTheNewChatInPlaceOfTheOldOne() {
        start("old-session")
        handover(SessionHandover("h1", HandoverState.SUMMARIZING, live = true))
        handover(SessionHandover("h1", HandoverState.DONE, "new-session", live = true))
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "new-session"), repository.state.value.selection)
        compose.onAllNodesWithText(string(R.string.remote_handover_continued)).assertCountEquals(1)
        compose.onAllNodesWithText(string(R.string.remote_handover_open_previous)).assertCountEquals(1)
        // Back leads where the old chat's Back led: the session list.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
    }

    @Test
    fun theOldChatOpenedAgainOffersTheNewChatAndStays() {
        start("old-session")
        handover(SessionHandover("h1", HandoverState.DONE, "new-session", live = true))
        assertEquals(RemoteSelection("host", "project", "new-session"), repository.state.value.selection)
        assertEquals(listOf("h1"), repository.followedHandovers)
        compose.onNodeWithText(string(R.string.remote_handover_open_previous)).performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "old-session"), repository.state.value.selection)
        openStatusSheet()
        compose.onNodeWithTag("handoverAction").assertTextEquals(string(R.string.remote_handover_open_new))
        // The followed done moves nobody again; the button does.
        assertEquals(RemoteSelection("host", "project", "old-session"), repository.state.value.selection)
        compose.onNodeWithTag("handoverAction").performClick()
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project", "new-session"), repository.state.value.selection)
    }

    @Test
    fun aDoneOnlyTheSnapshotReportsDoesNotMove() {
        start("old-session")
        handover(SessionHandover("h1", HandoverState.DONE, "new-session"))
        assertEquals(RemoteSelection("host", "project", "old-session"), repository.state.value.selection)
        compose.onAllNodesWithText(string(R.string.remote_handover_continued)).assertCountEquals(1)
    }

    @Test
    fun continueThatCannotSendLeavesTheComposerEmpty() {
        start("new-session")
        repository.state.value = repository.state.value.copy(
            messages = listOf(seed),
            handedOverFrom = HandedOverFrom("old-session", true),
            contextUsage = null,
        )
        compose.waitForIdle()
        // This fake's prompt() does not send.
        compose.onNodeWithTag("handoverContinue").performClick()
        compose.waitForIdle()
        assertEquals(1, repository.prompts)
        assertEquals("", repository.state.value.draft)
    }

    @Test
    fun tooLargeOffersCompactInstead() {
        start("old-session")
        handover(SessionHandover("h1", HandoverState.FAILED, error = HandoverError.TOO_LARGE, live = true))
        compose.onAllNodesWithText(string(R.string.remote_handover_failed_title)).assertCountEquals(1)
        openStatusSheet()
        compose.onNodeWithTag("handoverAction").performClick()
        compose.waitForIdle()
        assertEquals(1, repository.compactions)
        // Closed, the outcome does not return.
        compose.onAllNodesWithTag("handoverBanner").assertCountEquals(0)
    }

    private val seed = Wire.objectOf(
        "id" to "seed-1",
        "role" to "user",
        "text" to "## Goal\nShip the handover.",
        "state" to "complete",
        "handover" to Wire.objectOf("sourceSessionId" to "old-session"),
    )

    @Test
    fun theNewChatShowsTheSummaryCardAndContinueSendsThePrompt() {
        start("new-session")
        repository.sendsPrompts = true
        repository.state.value = repository.state.value.copy(
            messages = listOf(seed),
            handedOverFrom = HandedOverFrom("old-session", true),
            contextUsage = null,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("handoverSummary").assertExists()
        compose.onNodeWithTag("handoverContinue").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HANDOVER_CONTINUE_PROMPT), repository.sentPrompts)
    }

    @Test
    fun typingHidesContinue() {
        start("new-session")
        repository.state.value = repository.state.value.copy(
            messages = listOf(seed),
            handedOverFrom = HandedOverFrom("old-session", true),
            contextUsage = null,
        )
        compose.waitForIdle()
        compose.onAllNodesWithTag("handoverContinue").assertCountEquals(1)
        compose.onNodeWithTag("composerField").performTextInput("My own next step")
        compose.waitForIdle()
        compose.onAllNodesWithTag("handoverContinue").assertCountEquals(0)
    }
}
