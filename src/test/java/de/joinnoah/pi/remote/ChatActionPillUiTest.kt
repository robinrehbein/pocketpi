package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithTag("chatAction_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_changes").assertDoesNotExist()
        menuItem(ChatAction.RENAME).assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.SETTINGS).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun menuMorphsFromTheChevronEdgeInAFixedOrder() {
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
        // Measure the full content order, including rows initially below the scroll viewport.
        val tops = layout.menu.map { action -> menuItem(action).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        // The full menu also contains the action that was visible as the shortcut.
        menuItem(ChatAction.CHANGES).assertIsDisplayed()
        menuItem(ChatAction.CLOSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun terminalIsALabelledMenuRowThatRunsOnceClosesAndBecomesTheShortcut() {
        val selected = mutableListOf<ChatAction>()
        compose.setContent {
            MaterialTheme {
                ChatActionPill(
                    chatActionLayout(DEFAULT_CHAT_ACTIONS, setOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.FILES, ChatAction.TERMINAL)),
                    Color.DarkGray,
                    selected::add,
                )
            }
        }
        // The default shortcut never falls to Terminal: it is last in the fallback order.
        compose.onNodeWithTag("chatAction_changes").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_terminal").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_terminal_title)).assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNode(
            hasText(label(R.string.remote_terminal_title)).and(hasAnyAncestor(hasTestTag("chatActionMenu_terminal"))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        menuItem(ChatAction.TERMINAL).performClick()
        compose.waitForIdle()
        assertEquals(listOf(ChatAction.TERMINAL), selected)
        menuItem(ChatAction.TERMINAL).assertDoesNotExist()
        compose.onNodeWithTag("chatAction_terminal")
            .assertIsDisplayed()
            .assertContentDescriptionEquals(label(R.string.remote_terminal_title))
    }

    @Test
    fun externalUsageUpdateSupersedesTheOptimisticMenuSelection() {
        val usage = mutableStateOf(mapOf(ChatAction.CHANGES to ActionUsage(100.0, 10)))
        compose.setContent {
            MaterialTheme {
                ChatActionPill(
                    ChatActionLayout(listOf(ChatAction.CHANGES), listOf(ChatAction.FILES, ChatAction.REFRESH)),
                    Color.DarkGray, {}, usage = usage.value,
                )
            }
        }
        compose.onNodeWithTag("chatAction_changes").assertIsDisplayed()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.REFRESH).performClick()
        // No store callback: the local selection still updates immediately.
        compose.onNodeWithTag("chatAction_refresh").assertIsDisplayed()
        compose.runOnIdle {
            usage.value = usage.value + (ChatAction.FILES to ActionUsage(1.0, 20))
        }
        compose.onNodeWithTag("chatAction_files").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_refresh").assertDoesNotExist()
    }

    @Test
    fun callbacksAlreadyDispatchedDuringExitCannotRunAnActionTwice() {
        val selected = mutableListOf<ChatAction>()
        compose.setContent {
            MaterialTheme {
                ChatActionPill(ChatActionLayout(listOf(ChatAction.CHANGES), listOf(ChatAction.REFRESH)), Color.DarkGray, selected::add)
            }
        }
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        val rowClick = menuItem(ChatAction.REFRESH).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val quickClick = compose.onNodeWithTag("chatAction_changes").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            rowClick()
            // Simulate callbacks already dispatched before pointer/semantics removal recomposes.
            rowClick()
            quickClick()
            assertEquals(listOf(ChatAction.REFRESH), selected)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("chatAction_refresh").performClick()
        assertEquals(listOf(ChatAction.REFRESH, ChatAction.REFRESH), selected)
    }

    @Test
    fun popupHostNeverAddsASpacedRowChildDuringTheTransition() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.testTag("spacedHeader"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Before", Modifier.testTag("beforePill"))
                    ChatActionPill(ChatActionLayout(listOf(ChatAction.CHANGES), listOf(ChatAction.REFRESH)), Color.DarkGray, {})
                    Text("After", Modifier.testTag("afterPill"))
                }
            }
        }
        val tags = listOf("spacedHeader", "beforePill", "afterPill")
        fun bounds() = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val closed = bounds()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertEquals(closed, bounds())
        compose.mainClock.advanceTimeBy(400)
        menuItem(ChatAction.REFRESH).assertIsDisplayed()
        assertEquals(closed, bounds())
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertEquals(closed, bounds())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(closed, bounds())
    }

    @Test
    fun openingAndClosingDoesNotChangeTheHeaderLayoutSlot() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ChatActionPill(chatActionLayout(DEFAULT_CHAT_ACTIONS, ChatAction.entries.toSet()).withClose(), Color.DarkGray, {})
                    Text("Content below header", Modifier.testTag("belowHeader"))
                }
            }
        }
        val before = compose.onNodeWithTag("belowHeader").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chatActionsMore").performClick()
        menuItem(ChatAction.CHANGES).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("belowHeader").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("chatActionsMore").performClick()
        assertEquals(before, compose.onNodeWithTag("belowHeader").getUnclippedBoundsInRoot())
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
        compose.onNodeWithTag("chatAction_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_close").assertDoesNotExist()
    }
}
