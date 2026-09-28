package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.robolectric.shadows.ShadowToast
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
                ProjectRow(projectId = "p1", name = "noah", unshare = ProjectUnshare.AVAILABLE, onClick = {}, onUnshare = { unshared++ })
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

    private fun renderProject(unshare: ProjectUnshare, onUnshare: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ProjectRow(projectId = "p1", name = "noah", unshare = unshare, onClick = {}, onUnshare = onUnshare)
            }
        }
    }

    private val updateHint: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.remote_project_unshare_update_host)

    @Test
    fun projectUnshareFollowsConnectionAndCapability() {
        val capable = setOf(PROJECT_UNSHARE_CAPABILITY)
        assertEquals(ProjectUnshare.AVAILABLE, projectUnshare(connected = true, loading = false, capable))
        assertEquals(ProjectUnshare.NEEDS_HOST_UPDATE, projectUnshare(connected = true, loading = false, emptySet()))
        assertEquals(ProjectUnshare.NONE, projectUnshare(connected = false, loading = false, emptySet()))
        assertEquals(ProjectUnshare.NONE, projectUnshare(connected = false, loading = false, capable))
        assertEquals(ProjectUnshare.NONE, projectUnshare(connected = true, loading = true, emptySet()))
    }

    @Test
    fun projectSwipeOnAnOutdatedHostShowsTheUpdateHint() {
        var unshared = 0
        renderProject(ProjectUnshare.NEEDS_HOST_UPDATE) { unshared++ }
        compose.onNodeWithText("noah").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(updateHint, ShadowToast.getTextOfLatestToast())
        assertEquals(0, unshared)
    }

    @Test
    fun projectLongPressOnAnOutdatedHostShowsTheUpdateHint() {
        renderProject(ProjectUnshare.NEEDS_HOST_UPDATE)
        compose.onNodeWithText("noah").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(updateHint, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun offlineProjectRowHasNoUnshareActionOrHint() {
        var unshared = 0
        renderProject(ProjectUnshare.NONE) { unshared++ }
        compose.onNodeWithText("noah").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("noah").performTouchInput { longClick() }
        compose.waitForIdle()
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, unshared)
        compose.onNodeWithTag("projectRow-p1")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
    }

    @Test
    fun projectLongPressWithUnshareAvailableShowsNoHint() {
        renderProject(ProjectUnshare.AVAILABLE)
        compose.onNodeWithText("noah").performTouchInput { longClick() }
        compose.waitForIdle()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
