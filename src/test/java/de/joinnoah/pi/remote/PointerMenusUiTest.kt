package de.joinnoah.pi.remote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PointerMenusUiTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val session =
        SessionListItem(
            id = "s1",
            title = "Login-Flow umbauen",
            parentSessionId = null,
            depth = 0,
            hasChildren = false,
            availability = SessionAvailability.IDLE,
            preview = null,
            updatedAt = null,
            continuesAsCopy = false,
            daemonOwned = true,
            liveMacTui = false,
        )

    private fun host(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme(typography = RemoteTypography) { PointerMenuHost(content) } }
    }

    private fun clipboardText(): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun renderSession(events: MutableList<String>, swipe: Pair<SwipeAction, SwipeAction> = SwipeAction.CLOSE to SwipeAction.RENAME) =
        host {
            SessionListRow(
                item = session,
                enabled = true,
                closeAvailable = true,
                onClick = { events += "open" },
                onClose = { events += "close" },
                onRename = { events += "rename" },
                swipe = swipe,
            )
        }

    @Test
    fun rightClickOnASessionRowOpensItsMenu() {
        val events = mutableListOf<String>()
        renderSession(events)
        compose.onNodeWithText(session.title).performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_session_open").assertIsDisplayed()
        compose.onNodeWithTag("menu_session_close").assertIsDisplayed()
        compose.onNodeWithTag("menu_session_copy_id").assertIsDisplayed()
        // The right-click opened the menu only; it did not also open the chat.
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("menu_session_rename").performClick()
        assertEquals(listOf("rename"), events)
        compose.onNodeWithTag("pointerMenu").assertDoesNotExist()
    }

    @Test
    fun shiftF10OpensTheMenuOfTheFocusedRow() {
        val events = mutableListOf<String>()
        renderSession(events)
        compose.onNodeWithText(session.title).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(session.title).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        compose.onNodeWithTag("menu_session_copy_id").performClick()
        compose.waitForIdle()
        assertEquals("s1", clipboardText())
    }

    @Test
    fun rightClickOnAToolCardOffersItsActions() {
        val opened = mutableListOf<String>()
        val tool =
            ConversationItem.Activity(
                "tool-1", "tool-1", "bash", """{"command":"git status\ngit diff"}""", "clean", "complete", false,
            )
        host { ConversationMessage(tool, "status", thinkingActive = false, onQuote = {}, onOpenTool = { opened += it.id }) }
        compose.onNodeWithTag("toolCard").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_tool_open").assertIsDisplayed()
        compose.onNodeWithTag("menu_tool_peek").assertIsDisplayed()
        compose.onNodeWithTag("menu_tool_copy_output").assertIsDisplayed()
        compose.onNodeWithTag("menu_tool_copy_command").performClick()
        compose.waitForIdle()
        assertEquals("git status\ngit diff", clipboardText())
        // The right-click did not also open the tool.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun rightClickOnAFileTileOffersOpenSendAndCopy() {
        val events = mutableListOf<String>()
        host {
            FileEntryTile(
                entry = FileEntry("notes.kt", FileEntryType.FILE),
                preview = FileTilePreview(loading = false, content = "val x = 1", complete = true),
                onOpen = { events += "open" },
                onRequestPreview = {},
                onPeek = { events += "peek" },
                path = "src/notes.kt",
                onSend = { events += "send" },
            )
        }
        compose.onNodeWithText("notes.kt").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_file_open").assertIsDisplayed()
        compose.onNodeWithTag("menu_file_copy_path").assertIsDisplayed()
        compose.onNodeWithTag("menu_file_copy_contents").performClick()
        compose.waitForIdle()
        assertEquals("val x = 1", clipboardText())
        compose.onNodeWithText("notes.kt").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_file_send").performClick()
        assertEquals(listOf("send"), events)
    }

    @Test
    fun rightClickOnAProjectRowOffersANewSession() {
        val events = mutableListOf<String>()
        host {
            ProjectRow(
                projectId = "p1",
                name = "noah",
                unshare = ProjectUnshare.AVAILABLE,
                onClick = { events += "open" },
                onUnshare = { events += "unshare" },
                onNewSession = { events += "new" },
            )
        }
        compose.onNodeWithText("noah").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_project_open").assertIsDisplayed()
        compose.onNodeWithTag("menu_project_unshare").assertIsDisplayed()
        compose.onNodeWithTag("menu_project_new_session").performClick()
        assertEquals(listOf("new"), events)
    }

    private fun userBubble() = ConversationItem.Bubble("user-1-text-0", "user-1", "user", null, "Try again", null, false, null)

    @Test
    fun rightClickOnAMessageOffersQuoteCopyForkAndRetry() {
        val quotes = mutableListOf<String>()
        val forks = mutableListOf<Pair<String, ForkMode>>()
        host {
            ConversationMessage(
                userBubble(), "status", thinkingActive = false, onQuote = { quotes += it },
                fork = MessageFork(ready = true, stopFirst = false) { id, mode -> forks += id to mode },
            )
        }
        compose.onNodeWithTag("messageBubble-user-1-text-0").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_message_copy").assertIsDisplayed()
        compose.onNodeWithTag("menu_message_fork").assertIsDisplayed()
        compose.onNodeWithTag("menu_message_quote").performClick()
        assertEquals(listOf("user-1"), quotes)
        compose.onNodeWithTag("messageBubble-user-1-text-0").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_message_edit_retry").performClick()
        compose.onNodeWithTag("forkConfirm").performClick()
        assertEquals(listOf("user-1" to ForkMode.EDIT), forks)
    }

    @Test
    fun retryIsDisabledWhileTheChatCannotFork() {
        host {
            ConversationMessage(
                userBubble(), "status", thinkingActive = false, onQuote = {},
                fork = MessageFork(ready = false, stopFirst = true) { _, _ -> },
            )
        }
        compose.onNodeWithTag("messageBubble-user-1-text-0").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_message_edit_retry").assertIsNotEnabled()
    }

    @Test
    fun hoveringAMessageShowsCopyQuoteAndFork() {
        val quotes = mutableListOf<String>()
        host {
            ConversationMessage(
                userBubble(), "status", thinkingActive = false, onQuote = { quotes += it },
                fork = MessageFork(ready = true, stopFirst = false) { _, _ -> },
            )
        }
        compose.onNodeWithTag("hoverActions").assertDoesNotExist()
        compose.onNodeWithTag("messageBubble-user-1-text-0").performMouseInput { moveTo(center) }
        compose.onNodeWithTag("hover_copy").assertIsDisplayed()
        compose.onNodeWithTag("hover_fork").assertIsDisplayed()
        // A click with the same mouse: a touch would end the hover first.
        compose.onNodeWithTag("hover_quote").performMouseInput { click() }
        assertEquals(listOf("user-1"), quotes)
        compose.onNodeWithTag("messageBubble-user-1-text-0").performMouseInput { exit() }
        compose.onNodeWithTag("hoverActions").assertDoesNotExist()
    }

    @Test
    fun theMessageMenuStaysClosedWhileTextIsSelected() {
        host {
            ConversationMessage(
                ConversationItem.Bubble("a-1-text-0", "a-1", "assistant", "pi", "Selectable words here", null, false, null),
                "status",
                thinkingActive = false,
                onQuote = {},
            )
        }
        // A drag with the mouse selects the text, and the selection takes focus.
        compose.onNodeWithText("Selectable words here", useUnmergedTree = true).performMouseInput {
            moveTo(centerLeft)
            press()
            moveTo(centerRight)
            release()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("messageBubble-a-1-text-0").performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_message_quote").assertDoesNotExist()
    }

    @Test
    fun linkMenuOpensNextToPocketPiThroughTheAdjacentHandler() {
        val adjacent = mutableListOf<String>()
        val fullScreen = mutableListOf<String>()
        val openers =
            LinkOpeners(
                object : UriHandler { override fun openUri(uri: String) { adjacent += uri } },
                object : UriHandler { override fun openUri(uri: String) { fullScreen += uri } },
            )
        compose.setContent {
            MaterialTheme(typography = RemoteTypography) {
                CompositionLocalProvider(LocalLinkOpeners provides openers) {
                    PointerMenuHost {
                        ConversationMessage(
                            ConversationItem.Bubble("a-1-text-0", "a-1", "assistant", "pi", "[docs](https://example.com/docs)", null, false, null),
                            "status",
                            thinkingActive = false,
                            onQuote = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("docs", useUnmergedTree = true).performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_message_quote").assertDoesNotExist()
        compose.onNodeWithTag("menu_link_open_full_screen").assertIsDisplayed()
        compose.onNodeWithTag("menu_link_open_adjacent").performClick()
        assertEquals(listOf("https://example.com/docs"), adjacent)
        assertTrue(fullScreen.isEmpty())
        // Away from the link the bubble's own menu opens.
        compose.onNodeWithTag("messageBubble-a-1-text-0").performMouseInput { rightClick(topLeft) }
        compose.onNodeWithTag("menu_message_quote").assertIsDisplayed()
    }
}
