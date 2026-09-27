package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class ForkControlUiTest {
    @get:Rule val compose = createComposeRule()

    private val forks = mutableListOf<Pair<String, ForkMode>>()

    private fun bubble(role: String = "user", text: String = "Try again") =
        ConversationItem.Bubble("$role-1-text-0", "$role-1", role, null, text, null, false, null)

    private fun render(
        item: ConversationItem,
        fork: MessageFork? =
            MessageFork(ready = true, stopFirst = false) { id, mode -> forks += id to mode },
    ) {
        compose.setContent {
            MaterialTheme {
                ConversationMessage(item, "status", thinkingActive = false, onQuote = {}, fork = fork)
            }
        }
    }

    @Test
    fun retryAsksForConfirmationThatWarnsAboutFilesBeforeForking() {
        render(bubble())
        compose.onNodeWithContentDescription("Rewind from this message").performClick()
        compose.onNodeWithTag("forkRetry").performClick()
        assertTrue(forks.isEmpty())
        compose.onNodeWithText("Files on the Mac are not rewound", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("forkConfirm").performClick()
        assertEquals(listOf("user-1" to ForkMode.RETRY), forks)
    }

    @Test
    fun editForksWithEditModeAndCancelSendsNothing() {
        render(bubble())
        compose.onNodeWithTag("forkControl").performClick()
        compose.onNodeWithTag("forkEdit").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(forks.isEmpty())
        compose.onNodeWithTag("forkControl").performClick()
        compose.onNodeWithTag("forkEdit").performClick()
        compose.onNodeWithTag("forkConfirm").performClick()
        assertEquals(listOf("user-1" to ForkMode.EDIT), forks)
    }

    @Test
    fun runningChatDisablesBothChoicesWithAStopFirstHint() {
        render(bubble(), MessageFork(ready = false, stopFirst = true) { id, mode -> forks += id to mode })
        compose.onNodeWithTag("forkControl").performClick()
        compose.onNodeWithText("Stop first").assertIsDisplayed()
        compose.onNodeWithTag("forkRetry").assertIsNotEnabled()
        compose.onNodeWithTag("forkEdit").assertIsNotEnabled()
    }

    @Test
    fun slashCommandsOfferOnlyEdit() {
        render(bubble(text = "/review"))
        compose.onNodeWithTag("forkControl").performClick()
        compose.onNodeWithTag("forkRetry").assertDoesNotExist()
        compose.onNodeWithTag("forkEdit").assertIsEnabled()
    }

    @Test
    fun assistantBubblesAndChatsWithoutForkHaveNoControl() {
        render(bubble(role = "assistant"))
        compose.onNodeWithTag("forkControl").assertDoesNotExist()
    }

    @Test
    fun userBubbleWithoutForkHasNoControl() {
        render(bubble(), fork = null)
        compose.onNodeWithTag("forkControl").assertDoesNotExist()
    }

    @Test
    fun accessibilityActionOpensTheSameMenu() {
        render(bubble())
        val action =
            SemanticsMatcher("has rewind action") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any {
                    it.label == "Rewind from this message"
                }
            }
        val node = compose.onNode(action).fetchSemanticsNode()
        compose.runOnIdle {
            node.config[SemanticsActions.CustomActions]
                .single { it.label == "Rewind from this message" }
                .action()
        }
        compose.onNodeWithTag("forkEdit").assertIsDisplayed()
    }
}
