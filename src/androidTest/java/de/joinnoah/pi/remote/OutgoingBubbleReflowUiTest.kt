package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Exercise the initial preview -> live history reflow with the real bubble and glass layers. */
class OutgoingBubbleReflowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enablingForkAfterPreviewDoesNotLeaveOldBubblePixels() = checkReflow(relocate = false)

    @Test fun previewReflowWithChangingInsetsDoesNotLeaveOldBubblePixels() = checkReflow(relocate = true)

    @Test fun reusedUserRowDoesNotRetainPreviousDrawing() = checkReflow(relocate = false, reuse = true)

    private fun checkReflow(relocate: Boolean, reuse: Boolean = false) {
        val ready = mutableStateOf(false)
        val scrollTarget = mutableStateOf(0)
        val prompt = ConversationItem.Bubble(
            "prompt", "prompt", "user", null,
            "kann ich die Session hier also ohne Probleme schließen", null, false, 1_793_779_460_000L,
        )
        var background = Color.Unspecified
        var bubbleColor = Color.Unspecified
        compose.setContent {
            MaterialTheme(colorScheme = remoteColorScheme(false)) {
                background = MaterialTheme.colorScheme.background
                bubbleColor = MaterialTheme.colorScheme.primaryContainer
                ProvideGlass {
                    val listState = rememberLazyListState()
                    LaunchedEffect(scrollTarget.value) { listState.scrollToItem(scrollTarget.value) }
                    Box(Modifier.fillMaxSize().background(background).testTag("reflowFixture")) {
                        LazyColumn(
                            Modifier.fillMaxSize().glassSource(), state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 32.dp, top = if (relocate && ready.value) 144.dp else 120.dp, bottom = 250.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val rows = if (reuse && !ready.value) listOf(prompt) + (1..24).map {
                                prompt.copy(id = "other-$it", sourceId = "other-$it", text = "Other user message $it. ".repeat(8))
                            } else listOf(prompt)
                            items(rows, key = { it.id }, contentType = ::conversationContentType) { item ->
                                ConversationItemColumn(item, Modifier.conversationItemMotion(this)) {
                                    ConversationMessage(
                                        item, ThinkingDisplay.STATUS, false, onQuote = {},
                                        fork = if (ready.value) MessageFork(true, false) { _, _ -> } else null,
                                    )
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
        val before = compose.onNodeWithTag("messageBubble-prompt").getUnclippedBoundsInRoot()
        if (reuse) {
            compose.runOnIdle { scrollTarget.value = 24 }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            ready.value = true
            scrollTarget.value = 0
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("messageBubble-prompt").getUnclippedBoundsInRoot()
        assertEquals(1, compose.onAllNodesWithTag("messageBubble-prompt").fetchSemanticsNodes().size)
        // A duplicate drawing can survive even when the semantics tree contains only one item.
        // Scan outside the actual row, not just semantics bounds or one background pixel.
        val fixture = compose.onNodeWithTag("reflowFixture")
        val origin = fixture.fetchSemanticsNode().boundsInRoot
        val image = fixture.captureToImage().toPixelMap()
        val fixtureBounds = fixture.getUnclippedBoundsInRoot()
        val density = image.width / (fixtureBounds.right - fixtureBounds.left).value
        var currentPixels = 0
        var ghostPixels = 0
        val bottom = (maxOf(before.bottom.value, after.bottom.value) * density + 40 * density).toInt().coerceAtMost(image.height - 150)
        val top = (minOf(before.top.value, after.top.value) * density).toInt().coerceAtLeast(0)
        val rowBottom = after.bottom.value * density
        val surfaceLeft = compose.onNodeWithTag("forkControl").fetchSemanticsNode().boundsInRoot.right - origin.left
        for (y in top until bottom) for (x in 0 until image.width) {
            val pixel = image[x, y]
            val matches = abs(pixel.red - bubbleColor.red) < .02f && abs(pixel.green - bubbleColor.green) < .02f && abs(pixel.blue - bubbleColor.blue) < .02f
            if (!matches) continue
            if (y >= after.top.value * density && y <= rowBottom && x >= surfaceLeft && x <= after.right.value * density) currentPixels++
            if (y < after.top.value * density - 2 * density || y > rowBottom + 2 * density || x < surfaceLeft - 2 * density) ghostPixels++
        }
        assertTrue("Fixture must contain the expected current bubble fill", currentPixels > 100)
        assertTrue("Outgoing bubble must not leave pixels outside its current surface: $ghostPixels ghost pixels", ghostPixels < 4)
    }
}
