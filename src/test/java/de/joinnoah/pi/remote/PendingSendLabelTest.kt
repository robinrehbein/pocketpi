package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingSendLabelTest {
    @get:Rule val compose = createComposeRule()

    private val tag = "pendingSendLabel-pending-m1"

    private fun show(item: ConversationItem.Bubble) =
        compose.setContent {
            MaterialTheme {
                ConversationMessage(item, "hidden", thinkingActive = false, onQuote = {})
            }
        }

    private fun pending(state: PendingSendState) =
        pendingSendItem(PendingSend("m1", "Hello pi", null, emptyList(), state))

    @Test fun promptOnItsWayShowsSending() {
        show(pending(PendingSendState.SENDING))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals("Sending…")
    }

    @Test fun unconfirmedPromptShowsNotConfirmed() {
        show(pending(PendingSendState.NOT_CONFIRMED))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals("Not confirmed")
    }

    @Test fun acknowledgedPromptHasNoLabelYet() {
        show(pending(PendingSendState.CONFIRMED))
        compose.onAllNodesWithTag(tag).assertCountEquals(0)
    }

    @Test fun timelineMessageHasNoLabel() {
        show(
            ConversationItem.Bubble(
                id = "m1", sourceId = "m1", role = "user", author = null, text = "Hello pi",
                quote = null, truncated = false, timestamp = null,
            )
        )
        compose.onAllNodesWithTag(tag).assertCountEquals(0)
    }
}
