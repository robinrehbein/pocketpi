package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ConversationGlassOverlapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrolledBubbleDoesNotRemainBehindToolCards() = checkGlassOverlap(insertBefore = false)

    @Test fun newToolsDoNotSlideThroughExistingBubble() = checkGlassOverlap(insertBefore = true)

    @Test fun removedBubbleDoesNotGhostBehindReplacementTools() = checkGlassOverlap(insertBefore = true, replaceBubble = true)

    private fun checkGlassOverlap(insertBefore: Boolean, replaceBubble: Boolean = false) {
        assertTrue("This regression needs system animations enabled", ValueAnimator.areAnimatorsEnabled())
        val bubble = ConversationItem.Bubble(
            "image", "image", "user", null, "Continue.\n".repeat(16), null, false, null,
        )
        val tools = (0..5).map {
            ConversationItem.Activity("tool-$it", "tool-$it", "bash", "{\"command\":\"git status\"}", null, "done", false)
        }
        val rows = mutableStateOf<List<ConversationItem>>(if (insertBefore) listOf(bubble) else listOf(bubble) + tools)
        var background = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            MaterialTheme(colorScheme = remoteColorScheme(false)) {
                background = MaterialTheme.colorScheme.background
                ProvideGlass {
                    val state = rememberLazyListState()
                    LaunchedEffect(Unit) { if (!insertBefore) state.scrollToItem(1) }
                    Box(Modifier.fillMaxSize().background(background).testTag("chatFixture")) {
                        LazyColumn(
                            Modifier.fillMaxSize().glassSource(), state = state,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 250.dp),
                        ) {
                            items(rows.value, key = { it.id }, contentType = ::conversationContentType) { item ->
                                ConversationItemColumn(item, Modifier.conversationItemMotion(this)) {
                                    ConversationMessage(item, ThinkingDisplay.STATUS, false, onQuote = {})
                                }
                            }
                        }
                        FloatingSurface(Modifier.align(Alignment.TopCenter)) { Text("Header", Modifier.padding(16.dp)) }
                        FloatingSurface(Modifier.align(Alignment.BottomCenter)) { Text("Composer", Modifier.padding(32.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (insertBefore) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { rows.value = if (replaceBubble) tools.take(4) else tools.take(4) + bubble }
            compose.mainClock.advanceTimeByFrame()
            repeat(8) {
                compose.mainClock.advanceTimeByFrame()
                if (replaceBubble) assertGapShowsBackground(background)
                else assertToolsBeforeBubble()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        if (insertBefore && !replaceBubble) assertToolsBeforeBubble()
        assertGapShowsBackground(background, beforeBubble = insertBefore && !replaceBubble)
    }

    private fun assertToolsBeforeBubble() {
        val bubbleBounds = compose.onNodeWithTag("messageBubble-image").getUnclippedBoundsInRoot()
        val cards = compose.onAllNodesWithTag("toolCard")
        val count = cards.fetchSemanticsNodes().size
        assertTrue("Fixture expects at least one composed tool above the bubble", count > 0)
        repeat(count) { index ->
            val cardBounds = cards[index].getUnclippedBoundsInRoot()
            assertTrue("Tool $cardBounds must not overlap bubble $bubbleBounds", cardBounds.bottom <= bubbleBounds.top)
        }
    }

    private fun assertGapShowsBackground(background: androidx.compose.ui.graphics.Color, beforeBubble: Boolean = false) {
        val cards = compose.onAllNodesWithTag("toolCard").fetchSemanticsNodes()
        val fixture = compose.onNodeWithTag("chatFixture")
        val origin = fixture.fetchSemanticsNode().boundsInRoot.top
        // Anchoring a tall bubble can leave only one tool composed on a small phone. The
        // insertion boundary is tool-to-bubble, not a gap between two potentially absent tools.
        val gapStart = if (beforeBubble) cards.maxOf { it.boundsInRoot.bottom } else cards[0].boundsInRoot.bottom
        val gapEnd = if (beforeBubble) compose.onNodeWithTag("messageBubble-image").fetchSemanticsNode().boundsInRoot.top
            else cards[1].boundsInRoot.top
        assertTrue("Expected a visible gap between adjacent items", gapEnd > gapStart)
        val gapY = ((gapStart + gapEnd) / 2 - origin).toInt()
        val pixels = fixture.captureToImage().toPixelMap()
        val pixel = pixels[pixels.width * 3 / 4, gapY]
        assertTrue("Gap must show page background $background, not stale bubble: $pixel",
            abs(pixel.red - background.red) < 0.03f && abs(pixel.green - background.green) < 0.03f && abs(pixel.blue - background.blue) < 0.03f)
    }
}
