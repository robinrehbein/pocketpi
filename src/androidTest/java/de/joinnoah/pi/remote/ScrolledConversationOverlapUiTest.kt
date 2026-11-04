package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Investigation of mixed chat rows while reading above the live tail, not a proven device repro. */
class ScrolledConversationOverlapUiTest {
    @get:Rule val compose = createComposeRule()
    private var visibleRows: () -> List<LazyListItemInfo> = { emptyList() }
    private var stage = "initial"
    private var variant = ""
    private var rebuildFixture: () -> Unit = {}

    @Test fun midHistoryUpdatesSettleWithMotion() = exercise(disablePlacement = false)
    @Test fun midHistoryUpdatesSettleWithoutPlacement() = exercise(disablePlacement = true)

    private fun exercise(disablePlacement: Boolean) {
        variant = if (disablePlacement) "no-placement" else "motion"
        fun group(prefix: String, count: Int): List<ConversationItem> = (0 until count).flatMap { index ->
            val id = "$prefix-$index"
            listOf(
                ConversationItem.Thinking("$id-thinking", id, "**Checking test job logs $id**", false),
                ConversationItem.Activity("$id-tool", id, "read", "{\"path\":\"src/main/App.kt\"}", "Source content", "done", false),
                ConversationItem.Thinking("$id-next", id, "**Inspecting test helper $id**\n\n**Adding prepend test $id**\n\n**Updating anchor restoration $id**", false),
                ConversationItem.Bubble("$id-user", id, "user", null, "Continue with the investigation $id", null, false, 1_793_779_460_000L),
            )
        }
        val original = group("history", 25)
        val rows = mutableStateOf(original)
        val target = mutableStateOf(20)
        val statusOnly = mutableStateOf(false)
        val renderEpoch = mutableStateOf(0)
        rebuildFixture = { renderEpoch.value++ }
        compose.setContent {
            MaterialTheme(colorScheme = remoteColorScheme(false)) {
                ProvideGlass {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
                    visibleRows = { listState.layoutInfo.visibleItemsInfo }
                    LaunchedEffect(target.value) { listState.scrollToItem(target.value) }
                    key(renderEpoch.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("scrolledFixture")) {
                        LazyColumn(
                            Modifier.fillMaxSize().glassSource(), state = listState,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 80.dp, bottom = 250.dp, start = 16.dp, end = 32.dp),
                        ) {
                            val display = if (statusOnly.value) ThinkingDisplay.STATUS else ThinkingDisplay.TEXT
                            val visible = rows.value.filter { conversationItemVisible(it, display, false) }
                            items(visible, key = { it.id }, contentType = ::conversationContentType) { item ->
                                val motion = if (disablePlacement) Modifier.animateItem(placementSpec = null, fadeOutSpec = null)
                                    else Modifier.conversationItemMotion(this)
                                ConversationItemColumn(item, motion.testTag("scrolled-row-${item.id}")) {
                                    ConversationMessage(item, display, false, onQuote = {}, hideThinkingStatus = true)
                                }
                            }
                        }
                        FloatingSurface(Modifier.align(Alignment.TopCenter)) { Text("Header", Modifier.padding(16.dp)) }
                        FloatingSurface(Modifier.align(Alignment.BottomCenter)) { Text("Composer", Modifier.padding(32.dp)) }
                    }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertSeparated()
        repeat(4) { update ->
            stage = "update-$update"
            compose.runOnIdle {
                val grown = original.map { item ->
                    if (item is ConversationItem.Thinking && item.id.endsWith("next"))
                        item.copy(text = item.text + "\n\nMore streamed details. ".repeat(update + 1))
                    else item
                }
                rows.value = group("older-$update", 3) + grown + group("live-$update", update + 1)
            }
            compose.waitForIdle()
            assertSeparated()
        }
        // Remove completed thinking rows, then bring their full text back while still in history.
        stage = "hide-thinking"
        compose.runOnIdle { statusOnly.value = true }
        compose.waitForIdle()
        assertSeparated()
        stage = "restore-thinking"
        compose.runOnIdle { statusOnly.value = false }
        compose.waitForIdle()
        assertSeparated()
        assertDrawingMatchesFreshComposition()
        stage = "bottom"
        compose.runOnIdle { target.value = rows.value.lastIndex }
        compose.waitForIdle()
        assertSeparated()
    }

    private fun assertDrawingMatchesFreshComposition() {
        val fixture = compose.onNodeWithTag("scrolledFixture")
        val before = fixture.captureToImage()
        val bounds = fixture.getUnclippedBoundsInRoot()
        val density = before.width / (bounds.right - bounds.left).value
        val slotsBefore = compose.runOnIdle { visibleRows().map { Triple(it.key.toString(), it.offset, it.size) } }
        compose.runOnIdle { rebuildFixture() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val slotsAfter = compose.runOnIdle { visibleRows().map { Triple(it.key.toString(), it.offset, it.size) } }
        assertTrue("Rebuilding unchanged fixture must retain row placement: $slotsBefore vs $slotsAfter", slotsBefore == slotsAfter)
        val after = compose.onNodeWithTag("scrolledFixture").captureToImage()
        val first = before.toPixelMap()
        val second = after.toPixelMap()
        var different = 0
        var sampled = 0
        // Exclude the frosted overlays/noise. The opaque row content should redraw identically.
        for (y in (80 * density).toInt() until (before.height - 250 * density).toInt()) {
            for (x in (16 * density).toInt() until (before.width - 32 * density).toInt()) {
                sampled++
                val a = first[x, y]
                val b = second[x, y]
                if (abs(a.red - b.red) > .08f || abs(a.green - b.green) > .08f || abs(a.blue - b.blue) > .08f) different++
            }
        }
        if (different > sampled / 1000) {
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "overlap-evidence").apply { mkdirs() }
            File(directory, "$variant-before-rebuild.png").outputStream().use { before.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            File(directory, "$variant-after-rebuild.png").outputStream().use { after.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue("Unchanged content differs after rebuilding layers: $different / $sampled pixels", different <= sampled / 1000)
    }

    private fun assertSeparated() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val slots = compose.runOnIdle { visibleRows().map { Triple(it.key.toString(), it.offset, it.size) } }
        val visibleKeys = slots.map { it.first }.toSet()
        val nodes = compose.onAllNodes(
            SemanticsMatcher("conversation row") { node -> node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) && node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("scrolled-row-") },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().filter {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("scrolled-row-") in visibleKeys && it.boundsInRoot.height > 0f
        }.sortedBy { it.boundsInRoot.top }
        assertTrue("Fixture must expose multiple rows", nodes.size > 1)
        nodes.zipWithNext().forEach { (first, second) ->
            if (first.boundsInRoot.bottom > second.boundsInRoot.top + 1f) {
                val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "overlap-evidence").apply { mkdirs() }
                File(directory, "$variant-$stage.txt").writeText("Slots: $slots\n" + nodes.joinToString("\n") { "${it.config}: ${it.boundsInRoot}" })
                File(directory, "$variant-$stage.png").outputStream().use { output ->
                    compose.onNodeWithTag("scrolledFixture").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            assertTrue("Settled rows overlap: ${first.config} ${first.boundsInRoot} and ${second.config} ${second.boundsInRoot}",
                first.boundsInRoot.bottom <= second.boundsInRoot.top + 1f)
        }
    }
}
