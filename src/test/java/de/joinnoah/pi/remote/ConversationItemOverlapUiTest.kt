package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ConversationItemOverlapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tallImageBubbleReservesSpaceBeforeToolCards() = imageBubbleReservesSpace(grow = false)

    @Test fun growingImageBubbleSettlesBeforeToolCards() = imageBubbleReservesSpace(grow = true)

    @Test fun toolsInsertedBeforeQueuedImageSettleWithoutOverlap() = imageBubbleReservesSpace(grow = false, insertBefore = true)

    @Test fun expandedToolSettlesBeforeFollowingCard() {
        val tools = (0..1).map {
            ConversationItem.Activity("tool-$it", "tool-$it", "bash", "{\"command\":\"git status\"}", "Output line\n".repeat(8), "done", false)
        }
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tools, key = { it.id }, contentType = ::conversationContentType) { item ->
                        ConversationItemColumn(item, Modifier.conversationItemMotion(this)) {
                            ConversationMessage(item, ThinkingDisplay.STATUS, false, onQuote = {}, onOpenTool = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onAllNodesWithTag("toolCard")[1].getUnclippedBoundsInRoot().top
        compose.onAllNodesWithTag("toolCardToggle")[0].performClick()
        compose.waitForIdle()
        val first = compose.onAllNodesWithTag("toolCard")[0].getUnclippedBoundsInRoot()
        val next = compose.onAllNodesWithTag("toolCard")[1].getUnclippedBoundsInRoot()
        assertTrue("Expanding the tool must move the following card down", next.top > before)
        assertTrue("Expanded tool must settle before the following card", first.bottom <= next.top)
    }

    private fun imageBubbleReservesSpace(grow: Boolean, insertBefore: Boolean = false) {
        val bubble = ConversationItem.Bubble(
            id = "image", sourceId = "image", role = "user", author = null,
            text = "Continue.\n".repeat(8), quote = null, truncated = false, timestamp = null,
            attachments = listOf(RemoteAttachment("photo", "photo.jpg", "image", "image/jpeg", 2048, "a".repeat(64), Long.MAX_VALUE)),
        )
        val currentBubble = mutableStateOf(if (grow) bubble.copy(text = "Continue.", attachments = emptyList()) else bubble)
        val tools = (0..3).map {
            ConversationItem.Activity("tool-$it", "tool-$it", "bash", "{\"command\":\"git status\"}", null, "done", false)
        }
        val inserted = mutableStateOf(!insertBefore)
        val source = SentImageSource("route", "session", true, true, true) { _, _ -> AttachmentReadResult.Unsupported }
        compose.setContent {
            MaterialTheme {
                ProvideGlass {
                    LazyColumn(Modifier.fillMaxSize().glassSource(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rows = if (insertBefore) (if (inserted.value) tools else emptyList()) + currentBubble.value
                            else listOf(currentBubble.value) + tools
                        items(rows, key = { it.id }, contentType = ::conversationContentType) { item ->
                            ConversationItemColumn(item, Modifier.conversationItemMotion(this)) {
                                ConversationMessage(item, ThinkingDisplay.STATUS, false, onQuote = {}, images = source)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (grow || insertBefore) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                currentBubble.value = bubble
                inserted.value = true
            }
            compose.mainClock.advanceTimeByFrame()
            // Placement motion may temporarily cross other rows; only settled overlap is a
            // regression. Disabling that motion did not fix the device's persistent overlap.
            compose.mainClock.advanceTimeBy(2_000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        assertSeparated(insertBefore)
    }

    private fun assertSeparated(insertBefore: Boolean) {
        val bubbleBounds = compose.onNodeWithTag("messageBubble-image").getUnclippedBoundsInRoot()
        val card = compose.onAllNodesWithTag("toolCard")[0].getUnclippedBoundsInRoot()
        if (insertBefore) {
            val lastCard = compose.onAllNodesWithTag("toolCard")[3].getUnclippedBoundsInRoot()
            assertTrue("Tool card $lastCard must end before queued bubble $bubbleBounds", lastCard.bottom <= bubbleBounds.top)
        } else assertTrue("Bubble $bubbleBounds must end before tool card $card", bubbleBounds.bottom <= card.top)
    }
}
