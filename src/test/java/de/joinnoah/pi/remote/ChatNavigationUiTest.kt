package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat history and the marker scrollbar inside the whole app, on a phone and a wide tablet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private val session = buildJsonObject {
        put("id", "s1")
        put("title", "First session")
        put("status", "idle")
        put("origin", "rpc")
    }

    private fun message(id: String, role: String, text: String): JsonObject = buildJsonObject {
        put("id", id)
        put("role", role)
        put("text", text)
    }

    private val messages =
        (0 until 30).flatMap {
            listOf(
                message("user-$it", "user", "Question number $it"),
                message("reply-$it", "assistant", "Reply $it with enough words to wrap over more than one line on a phone screen."),
            )
        } +
            buildJsonObject {
                put("id", "tool-1")
                put("role", "tool")
                put("text", "boom")
                put("state", "error")
                put("toolName", "bash")
            }

    init {
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
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
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
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
    }

    private fun label(id: Int) = compose.activity.getString(id)

    /** A wide header has its own History button; a phone's has it in the pill or its menu. */
    private fun openHistory() {
        if (compose.onAllNodesWithContentDescription(label(R.string.remote_chat_history)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("chatActionsMore").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription(label(R.string.remote_chat_history)).performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun scrollingNeverChangesTheMessageWidth() {
        render()
        fun width() =
            compose.onNodeWithText("Reply 29", substring = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        fun composerHeight() =
            compose.onNodeWithTag("floatingComposer").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val before = width()
        val composerBefore = composerHeight()
        // Mid-gesture: the finger is down and has dragged the list a little.
        compose.onNodeWithTag("conversationList").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60f))
        }
        compose.waitForIdle()
        val during = width()
        compose.onNodeWithTag("conversationList").performTouchInput { up() }
        compose.onNodeWithTag("conversationList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        // Scrolled up: "scroll to latest" fades in within its fixed slot.
        compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom)).assertIsDisplayed()
        assertEquals(composerBefore, composerHeight())
        compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom)).performClick()
        compose.waitForIdle()
        assertEquals(before, width())
        assertEquals(before, during)
        assertEquals(composerBefore, composerHeight())
        compose.onNodeWithTag("timelineToggle").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun draggingTheScrollbarUpStopsFollowingNewOutput() {
        repository.state.value = repository.state.value.copy(status = "running")
        render()
        compose.onNodeWithText("Reply 29", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chatScrollbar").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, height - 2f), androidx.compose.ui.geometry.Offset(centerX, height * 0.2f))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Reply 29", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(messages = repository.state.value.messages + message("late", "assistant", "Late output"))
        }
        compose.waitForIdle()
        // Still where the drag left it, with the way back offered.
        compose.onNodeWithText("Late output").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom)).assertIsDisplayed()
        // Dragging to the end follows again.
        compose.onNodeWithTag("chatScrollbar").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, height * 0.2f), androidx.compose.ui.geometry.Offset(centerX, height - 1f))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(R.string.remote_scroll_to_bottom)).assertDoesNotExist()
        compose.onNodeWithText("Late output").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun scrollbarSitsInTheEndPaddingBesideTheMessages() {
        render()
        val bar = compose.onNodeWithTag("chatScrollbar").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("conversationList").getUnclippedBoundsInRoot()
        val reply = compose.onNodeWithText("Reply 29", substring = true).getUnclippedBoundsInRoot()
        assertEquals(CHAT_SCROLLBAR_LANE, bar.right - bar.left)
        assertEquals(list.right, bar.right)
        assertTrue("The bar at ${bar.left} covers a message ending at ${reply.right}", bar.left >= reply.right)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun historyNeverPushesAPhoneHeaderActionIntoTheMenu() {
        render()
        // New session, Refresh and Settings fit the pill as before; History does not take their place.
        compose.onNodeWithContentDescription(label(R.string.remote_settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.remote_refresh)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.remote_new_session)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.remote_chat_history)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.remote_settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun historyOpensAsASheetOnAPhoneAndJumpsToAnEntry() {
        render()
        compose.onNodeWithText("Question number 2").assertDoesNotExist()
        openHistory()
        compose.onNodeWithTag("chatHistorySheet").assertIsDisplayed()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        // It opens where the chat stands, at its end.
        compose.onNode(hasText("Question number 29") and hasAnyAncestor(hasTestTag("chatHistorySheet"))).assertIsDisplayed()
        compose.onNode(hasTestTag("chatHistoryList") and hasAnyAncestor(hasTestTag("chatHistorySheet")))
            .performScrollToNode(hasText("Question number 2"))
        compose.onNode(hasText("Question number 2") and hasAnyAncestor(hasTestTag("chatHistorySheet"))).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatHistorySheet").assertDoesNotExist()
        compose.onNode(hasText("Question number 2") and hasAnyAncestor(hasTestTag("conversationList"))).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun historyOpensInTheInspectorOnAWideTabletAndStaysOpen() {
        render()
        openHistory()
        compose.onNodeWithTag("chatHistorySheet").assertDoesNotExist()
        compose.onNode(hasTestTag("chatHistoryPane") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        compose.onNode(hasTestTag("chatHistoryList") and hasAnyAncestor(hasTestTag("chatHistoryPane")))
            .performScrollToNode(hasText("Question number 1"))
        compose.onNode(hasText("Question number 1") and hasAnyAncestor(hasTestTag("chatHistoryPane"))).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Question number 1") and hasAnyAncestor(hasTestTag("conversationList"))).assertIsDisplayed()
        compose.onNodeWithTag("chatHistoryPane").assertIsDisplayed()
        // Back closes the pane first, like the other inspector panes, and leaves the chat open.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("chatHistoryPane").assertDoesNotExist()
        compose.onNodeWithTag("inspectorPane").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertIsDisplayed()
    }
}
