package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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

    private fun show(item: ConversationItem.Bubble, onCancelQueued: (() -> Unit)? = null) =
        compose.setContent {
            MaterialTheme {
                ConversationMessage(
                    item, ThinkingDisplay.STATUS, thinkingActive = false, onQuote = {}, onCancelQueued = onCancelQueued,
                )
            }
        }

    private fun pending(state: PendingSendState) =
        pendingSendItem(PendingSend("m1", "Hello pi", null, emptyList(), state))

    @Test fun queuedPromptShowsItWaitsForTheConnectionAndCanBeCancelled() {
        var cancelled = 0
        show(pending(PendingSendState.QUEUED), onCancelQueued = { cancelled++ })
        compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals("Queued, sends when connected")
        compose.onNodeWithTag("cancelQueued-pending-m1").assertIsDisplayed().performClick()
        assertEquals(1, cancelled)
    }

    @Test fun queuedPromptWithoutACancelActionOffersNone() {
        show(pending(PendingSendState.QUEUED))
        compose.onAllNodesWithTag("cancelQueued-pending-m1").assertCountEquals(0)
    }

    @Test fun sendingPromptOffersNoCancel() {
        show(pending(PendingSendState.SENDING), onCancelQueued = {})
        compose.onAllNodesWithTag("cancelQueued-pending-m1").assertCountEquals(0)
    }

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
