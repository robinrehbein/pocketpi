package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat screen's own draft changes see the text typed inside the composer's pause: each one
 * flushes the composer first or goes through it, and the debounce never undoes it afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatComposerFlushTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val chat = RemoteSelection("host", "project", "s1")

    init {
        val session = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "rpc")
        }
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        val messages =
            listOf(
                Wire.objectOf("id" to "u", "role" to "user", "text" to "Run the build"),
                Wire.objectOf(
                    "id" to "a",
                    "role" to "assistant",
                    "text" to "",
                    "state" to "complete",
                    "parts" to JsonArray(
                        listOf(Wire.objectOf("type" to "toolCall", "id" to "c", "name" to "bash", "arguments" to "{}"))
                    ),
                ),
                Wire.objectOf(
                    "id" to "tool-1",
                    "role" to "tool",
                    "text" to "boom",
                    "state" to "error",
                    "toolName" to "bash",
                    "toolCallId" to "c",
                ),
            )
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                messages = messages,
                capabilities = setOf(COMMANDS_CAPABILITY, COMPACT_CAPABILITY),
                commands = listOf(RemoteCommand("review", "Review the changes", "extension")),
            )
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = chat)
            chat
        }
    }

    private fun render() {
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertExists()
        compose.mainClock.autoAdvance = false
    }

    private fun field() = compose.onNodeWithTag("composerField")

    private fun shownText(): String =
        field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun typeQuickly(text: String) {
        text.forEach {
            field().performTextInput(it.toString())
            compose.mainClock.advanceTimeBy(20)
        }
    }

    private fun frames() = compose.mainClock.advanceTimeBy(100)

    /** The repository's draft and the field agree on [expected], also once the pause has passed. */
    private fun assertDraftStays(expected: String) {
        frames()
        assertEquals(expected, repository.state.value.draft)
        assertEquals(expected, shownText())
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS * 2)
        assertEquals(expected, repository.state.value.draft)
        assertEquals(expected, shownText())
    }

    @Test
    fun aHostCommandTappedBeforeThePauseCompletesTheTypedPrefix() {
        render()
        typeQuickly("/rev")
        assertEquals("", repository.state.value.draft)
        compose.onNodeWithText("/review").performClick()
        assertEquals("/review", repository.state.value.draft)
        assertDraftStays("/review")
    }

    @Test
    fun aHostCommandTappedAfterAnOlderPushIsNotUndoneByThePause() {
        render()
        typeQuickly("/re")
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS + 100)
        assertEquals("/re", repository.state.value.draft)
        typeQuickly("v")
        compose.onNodeWithText("/review").performClick()
        assertDraftStays("/review")
    }

    @Test
    fun askToFixKeepsTextTypedInsideThePause() {
        render()
        typeQuickly("My own words")
        compose.onNodeWithTag("askToFix").performClick()
        // The prompt only fills a blank draft; the repository held the typing before it looked.
        assertEquals(listOf("tool-1"), repository.quotes)
        assertEquals("My own words", repository.state.value.draft)
        assertDraftStays("My own words")
    }

    @Test
    fun aFilesQuoteExtendsTextTypedInsideThePause() {
        render()
        typeQuickly("Look at")
        compose.runOnUiThread {
            repository.state.value =
                repository.state.value.copy(
                    files = FilesState(
                        "s1",
                        loading = false,
                        listing = FileListing(""),
                        file = OpenFile("App.kt", loading = false, version = "v1", size = 14, content = "fun main() {}\n"),
                    )
                )
        }
        frames()
        compose.onNodeWithTag("filesSendFile").performClick()
        frames()
        val draft = repository.state.value.draft
        assertTrue(draft, draft.startsWith("Look at\n\n") && draft.length > "Look at\n\n".length)
        assertDraftStays(draft)
    }

    @Test
    fun aCompactCommandSentInsideThePauseClearsTheDraft() {
        render()
        typeQuickly("/compact")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_send)).performClick()
        assertEquals(1, repository.compactions)
        assertEquals(0, repository.prompts)
        assertDraftStays("")
    }

    @Test
    fun backInsideThePauseKeepsTheTextInTheChatItWasTypedIn() {
        render()
        typeQuickly("Half a thought")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
        assertEquals("Half a thought", repository.filedDrafts[chat])
    }
}
