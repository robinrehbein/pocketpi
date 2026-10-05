package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatActionPillUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun extraActionsOpenAsIconButtonsAndCloseAfterSelection() {
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

        compose.onNodeWithTag("chatActionMenu_rename").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNodeWithTag("chatActionMenu_rename").assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_settings").assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_rename")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.remote_rename_session))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_rename_session))
            .assertDoesNotExist()

        compose.onNodeWithTag("chatActionMenu_refresh").performClick()
        assertEquals(listOf(ChatAction.REFRESH), selected)
        compose.onNodeWithTag("chatActionMenu_rename").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNodeWithTag("chatActionMenu_settings").assertIsDisplayed()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNodeWithTag("chatActionMenu_settings").assertDoesNotExist()
    }
}
