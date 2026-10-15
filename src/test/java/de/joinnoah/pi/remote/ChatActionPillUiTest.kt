package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatActionPillUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun label(id: Int) = compose.activity.getString(id)

    private fun menuItem(action: ChatAction) = compose.onNodeWithTag("chatActionMenu_${action.name.lowercase()}")

    @Test
    fun extraActionsOpenAsALabelledMenuAndCloseAfterSelection() {
        val selected = mutableListOf<ChatAction>()
        compose.setContent {
            MaterialTheme {
                ChatActionPill(
                    ChatActionLayout(
                        shown = listOf(ChatAction.CHANGES, ChatAction.FILES),
                        menu = listOf(ChatAction.RENAME, ChatAction.REFRESH, ChatAction.SETTINGS),
                    ),
                    Color.DarkGray,
                    selected::add,
                )
            }
        }

        menuItem(ChatAction.RENAME).assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore")
            .assertContentDescriptionEquals(label(R.string.remote_chat_actions_more))
            .performClick()
        // Every entry names itself in words next to its icon.
        for (action in listOf(ChatAction.RENAME, ChatAction.REFRESH, ChatAction.SETTINGS)) {
            menuItem(action).assertIsDisplayed()
            compose.onNode(
                hasText(label(action.label())).and(hasAnyAncestor(hasTestTag("chatActionMenu_${action.name.lowercase()}"))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
        compose.onNodeWithTag("chatActionsMore")
            .assertContentDescriptionEquals(label(R.string.remote_chat_actions_close))

        menuItem(ChatAction.REFRESH).performClick()
        assertEquals(listOf(ChatAction.REFRESH), selected)
        menuItem(ChatAction.RENAME).assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.SETTINGS).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun menuOpensBelowTheChevronInAFixedOrder() {
        val offered = ChatAction.entries.toSet() - ChatAction.STATUS - ChatAction.CLOSE
        val layout = chatActionLayout(DEFAULT_CHAT_ACTIONS, offered).withClose()
        // Export, Tree, Artifacts and Reload join the menu, in [ChatAction] order with Close last.
        assertTrue(layout.menu.containsAll(listOf(ChatAction.EXPORT, ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.RELOAD)))
        assertEquals(layout.menu.sortedBy { it.ordinal }, layout.menu)
        assertEquals(ChatAction.CLOSE, layout.menu.last())
        compose.setContent {
            MaterialTheme { ChatActionPill(layout, Color.DarkGray, {}) }
        }
        compose.onNodeWithTag("chatActionsMore").performClick()
        val tops = layout.menu.map { action -> menuItem(action).fetchSemanticsNode().boundsInWindow.top }
        assertEquals(tops.sorted(), tops)
        // The menu opens under the chevron, not at an offset of its own.
        val chevron = compose.onNodeWithTag("chatActionsMore").fetchSemanticsNode()
        val chevronBottom = chevron.positionOnScreen.y + chevron.size.height
        val menuTop = menuItem(layout.menu.first()).fetchSemanticsNode().positionOnScreen.y
        assertTrue("menu starts at $menuTop, chevron ends at $chevronBottom", menuTop >= chevronBottom)
    }

    @Test
    fun closeSessionSitsInTheMoreActions() {
        val selected = mutableListOf<ChatAction>()
        compose.setContent {
            MaterialTheme {
                ChatActionPill(
                    ChatActionLayout(shown = listOf(ChatAction.REFRESH, ChatAction.SETTINGS), menu = emptyList()).withClose(),
                    Color.DarkGray,
                    selected::add,
                )
            }
        }
        compose.onNodeWithTag("chatAction_close").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNodeWithText(label(R.string.remote_session_close)).assertIsDisplayed()
        menuItem(ChatAction.CLOSE).performClick()
        assertEquals(listOf(ChatAction.CLOSE), selected)
    }
}
