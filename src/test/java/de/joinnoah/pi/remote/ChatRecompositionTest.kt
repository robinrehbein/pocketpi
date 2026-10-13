package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typing in a long chat recomposes the composer, not the message bubbles above it. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatRecompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    /** Counts executed (not skipped) bodies of composables whose trace name starts with a prefix. */
    private class Counter : CompositionTracer {
        var counting = false
        val counts = mutableMapOf<String, Int>()

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (counting) {
                val name = info.substringBefore(' ')
                counts[name] = (counts[name] ?: 0) + 1
            }
        }

        override fun traceEventEnd() = Unit

        override fun isTraceInProgress() = true

        fun count(name: String) = counts["de.joinnoah.pi.remote.$name"] ?: 0
    }

    private val counter = Counter()

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
            (0 until 20).flatMap {
                listOf(
                    buildJsonObject {
                        put("id", "user-$it")
                        put("role", "user")
                        put("text", "Question number $it")
                    },
                    buildJsonObject {
                        put("id", "reply-$it")
                        put("role", "assistant")
                        put("text", "Reply $it")
                    },
                )
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
                messages = messages,
                // User bubbles get the rewind control, which once made every bubble recompose.
                capabilities = setOf(SESSION_FORK_CAPABILITY),
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    @After
    fun stopTracing() {
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = false
        })
    }

    @Test
    fun chatRowsCarryAContentTypeAndTheirOwnKeys() {
        val user = ConversationItem.Bubble("u", "u", "user", null, "Hi", null, false, null)
        assertEquals("bubble-user", conversationContentType(user))
        assertEquals("bubble", conversationContentType(user.copy(role = "assistant")))
        assertEquals("thinking", conversationContentType(ConversationItem.Thinking("t", "t", "", false)))
        assertEquals(
            "activity",
            conversationContentType(ConversationItem.Activity("a", "a", "bash", null, null, "done", false)),
        )
        assertEquals(
            "subagent",
            conversationContentType(ConversationItem.Subagent("s", "s", null, emptyList(), false, null, false)),
        )
        // Fixed keys, distinct from each other and from the list's other own row.
        assertEquals(3, setOf(CHAT_SKELETON_KEY, LOAD_OLDER_KEY, "waiting-for-output").size)
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
        compose.onNodeWithText("Reply 19").assertExists()
    }

    @Test
    fun typingTwentyCharactersRecomposesNoBubble() {
        Composer.setTracer(counter)
        render()
        compose.mainClock.autoAdvance = false
        counter.counting = true
        "Twenty characters!!!".forEach {
            compose.onNodeWithTag("composerField").performTextInput(it.toString())
            compose.mainClock.advanceTimeBy(50)
        }
        assertTrue("The composer did not recompose", counter.count("ChatComposer") >= 20)
        assertEquals(0, counter.count("ConversationMessage"))
        assertEquals(0, counter.count("RemoteScreen"))
        assertEquals("", repository.state.value.draft)
        // After the pause the repository gets the text; the chat recomposes once and the
        // bubbles, whose inputs did not change, still skip.
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS + 100)
        assertEquals("Twenty characters!!!", repository.state.value.draft)
        assertTrue(counter.count("RemoteScreen") >= 1)
        assertEquals(0, counter.count("ConversationMessage"))
    }

    @Test
    fun aSendRightAfterTypingSendsTheTypedText() {
        render()
        compose.mainClock.autoAdvance = false
        "Ship it".forEach {
            compose.onNodeWithTag("composerField").performTextInput(it.toString())
            compose.mainClock.advanceTimeBy(50)
        }
        assertEquals("", repository.state.value.draft)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_send)).performClick()
        assertEquals(1, repository.prompts)
        assertEquals("Ship it", repository.state.value.draft)
    }
}
