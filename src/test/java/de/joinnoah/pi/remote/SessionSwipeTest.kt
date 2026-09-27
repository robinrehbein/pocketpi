package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionSwipeTest {
    @get:Rule val compose = createComposeRule()

    private val item =
        SessionListItem(
            id = "s1",
            title = "Login-Flow umbauen",
            parentSessionId = null,
            depth = 0,
            hasChildren = false,
            availability = SessionAvailability.IDLE,
            preview = null,
            updatedAt = null,
            continuesAsCopy = false,
            daemonOwned = true,
            liveMacTui = false,
        )

    private fun render(swipe: Pair<SwipeAction, SwipeAction>, counts: IntArray) {
        compose.setContent {
            MaterialTheme {
                SessionListRow(
                    item = item,
                    enabled = true,
                    closeAvailable = true,
                    onClick = {},
                    onClose = { counts[0]++ },
                    onRename = { counts[1]++ },
                    swipe = swipe,
                )
            }
        }
    }

    @Test
    fun swipeLeftCloses() {
        val counts = IntArray(2)
        render(SwipeAction.CLOSE to SwipeAction.RENAME, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(1, 0), counts.toList())
    }

    @Test
    fun swipeRightRenames() {
        val counts = IntArray(2)
        render(SwipeAction.CLOSE to SwipeAction.RENAME, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf(0, 1), counts.toList())
    }

    @Test
    fun rowIsSwipeableAgainAfterAnAction() {
        val counts = IntArray(2)
        render(SwipeAction.CLOSE to SwipeAction.RENAME, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2, counts[0])
    }

    @Test
    fun disabledDirectionDoesNothing() {
        val counts = IntArray(2)
        render(SwipeAction.NONE to SwipeAction.NONE, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.onNodeWithText(item.title).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf(0, 0), counts.toList())
    }

    @Test
    fun projectSwipeLeftUnsharesOnce() {
        var unshared = 0
        compose.setContent {
            MaterialTheme {
                ProjectRow(projectId = "p1", name = "noah", unshareEnabled = true, onClick = {}, onUnshare = { unshared++ })
            }
        }
        compose.onNodeWithText("noah").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, unshared)
    }

    @Test
    fun onlyTheConfiguredDirectionActs() {
        val counts = IntArray(2)
        render(SwipeAction.CLOSE to SwipeAction.NONE, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(1, 0), counts.toList())
    }

    @Test
    fun swappedDirectionsFollowTheSetting() {
        val counts = IntArray(2)
        render(SwipeAction.RENAME to SwipeAction.CLOSE, counts)
        compose.onNodeWithText(item.title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(0, 1), counts.toList())
    }
}
