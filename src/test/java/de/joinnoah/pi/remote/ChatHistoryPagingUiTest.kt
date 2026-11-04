package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatHistoryPagingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(enabled: Boolean = true): () -> Int {
        var requests = 0
        compose.setContent {
            val list = rememberLazyListState()
            val keys = (1..30).map { "message-$it" }
            AutoLoadChatHistory(
                chat = "chat", list = list, cursor = "older", enabled = enabled,
                programmatic = { false }, messageKeys = keys, messageStartIndex = { 1 },
                userScrollRevision = { 0L },
                load = { requests++ }, stopFollowing = {}, setProgrammatic = {},
            )
            LazyColumn(Modifier.height(300.dp).testTag("history"), state = list) {
                item(key = "load") { Text("Load older", Modifier.height(48.dp)) }
                items(keys, key = { it }) { Text(it, Modifier.height(80.dp)) }
            }
            LaunchedEffect(Unit) { list.scrollToItem(4) }
        }
        compose.waitForIdle()
        return { requests }
    }

    @Test fun openingAndProgrammaticScrollDoNotLoadButUserScrollDoesOnce() {
        val requests = show()
        assertEquals(0, requests())
        compose.onNodeWithTag("history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, requests())
        compose.onNodeWithTag("history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("An unchanged cursor must not produce a retry loop", 1, requests())
    }

    @Test fun animatedBackwardJumpDoesNotConsumeTheCursor() {
        var requests = 0
        var jump by androidx.compose.runtime.mutableStateOf(false)
        var programmatic by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val list = rememberLazyListState()
            val keys = (1..30).map { "message-$it" }
            AutoLoadChatHistory(
                chat = "chat", list = list, cursor = "older", enabled = true,
                programmatic = { programmatic }, messageKeys = keys, messageStartIndex = { 1 },
                userScrollRevision = { 0L },
                load = { requests++ }, stopFollowing = {}, setProgrammatic = { programmatic = it },
            )
            LazyColumn(Modifier.height(300.dp).testTag("history"), state = list) {
                item(key = "load") { Text("Load older", Modifier.height(48.dp)) }
                items(keys, key = { it }) { Text(it, Modifier.height(80.dp)) }
            }
            LaunchedEffect(jump) {
                programmatic = true
                try {
                    if (jump) list.animateScrollToItem(3) else list.scrollToItem(20)
                } finally {
                    programmatic = false
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { jump = true }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithTag("history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, requests)
    }

    @Test fun prependRestoresMessageWhenHeaderIsVisible() = checkPrepend(lastPage = false)

    @Test fun lastPageRestoresMessageWhenHeaderRemovalNormalizesTheList() = checkPrepend(lastPage = true)

    @Test fun userMovementWhileFetchingCancelsAnchorRestore() = checkPrepend(lastPage = false, userMoved = true)

    private fun checkPrepend(lastPage: Boolean, userMoved: Boolean = false) {
        var revision = 0L
        var restorations = 0
        var cursor by androidx.compose.runtime.mutableStateOf<String?>("older")
        var keys by androidx.compose.runtime.mutableStateOf((1..30).map { "message-$it" })
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var anchorOffset: Int? = null
        compose.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = 0, initialFirstVisibleItemScrollOffset = 100)
            AutoLoadChatHistory(
                chat = "chat", list = list, cursor = cursor, enabled = true,
                programmatic = { false }, messageKeys = keys,
                messageStartIndex = { if (cursor == null) 0 else 1 },
                userScrollRevision = { revision },
                load = { anchorOffset = list.layoutInfo.visibleItemsInfo.first { it.key == "message-1" }.offset },
                stopFollowing = {}, setProgrammatic = { if (it) restorations++ },
            )
            LazyColumn(Modifier.fillMaxWidth().height(300.dp).testTag("history"), state = list) {
                if (cursor != null) item(key = "load") { Text("Load older", Modifier.height(180.dp)) }
                items(keys, key = { it }) {
                    Text(it, Modifier.height(if (it.startsWith("new")) 16.dp else 80.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(100, list.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("history").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, 50f))
            moveTo(androidx.compose.ui.geometry.Offset(centerX, 75f), delayMillis = 100)
            moveTo(androidx.compose.ui.geometry.Offset(centerX, 90f), delayMillis = 100)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            org.junit.Assert.assertTrue("Gesture must actually scroll the header", list.firstVisibleItemScrollOffset < 100)
        }
        compose.onNodeWithTag("history").performTouchInput { advanceEventTime(200); up() }
        compose.waitForIdle()
        org.junit.Assert.assertNotNull("User scroll should request a page", anchorOffset)
        compose.runOnIdle {
            if (userMoved) revision++
            keys = (1..10).map { "new-$it" } + keys
            cursor = if (lastPage) null else "next"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(if (userMoved) 0 else 1, restorations)
            if (!userMoved) {
                assertEquals(anchorOffset, list.layoutInfo.visibleItemsInfo.first { it.key == "message-1" }.offset)
            }
        }
    }

    @Test fun disabledHistoryDoesNotLoadOnUserScroll() {
        val requests = show(enabled = false)
        compose.onNodeWithTag("history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, requests())
    }
}
