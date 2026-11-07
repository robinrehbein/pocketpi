package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a phone the compact header stays hidden until the reader restores the controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class CompactHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")

    /** Opens a chat of [count] messages on a phone, the way a user gets there from the lists. */
    private fun openLongChat(count: Int = 80, fontScale: Float = 1f): NavigationFakeRepository {
        val project = Wire.objectOf("id" to "project", "name" to "noah-monorepo")
        val session = Wire.objectOf("id" to "s1", "title" to "First session", "status" to "idle", "origin" to "rpc")
        val repository = NavigationFakeRepository()
        val listed =
            RemoteState(
                hosts = listOf(host),
                hostsLoaded = true,
                host = host,
                connected = true,
                connection = R.string.remote_connected,
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
            )
        repository.state.value = listed
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RemoteApp(repository, FakeSettingsRepository(), false, {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Studio Mac").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("noah-monorepo").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("First session").performClick()
        compose.waitForIdle()
        val messages =
            (1..count).map { index ->
                Wire.objectOf(
                    "id" to "m$index",
                    "role" to if (index % 2 == 0) "assistant" else "user",
                    "text" to "Message $index",
                    "state" to "complete",
                )
            }
        repository.state.value =
            listed.copy(
                selection = RemoteSelection("host", "project", "s1"),
                session = session,
                status = "idle",
                messages = messages,
            )
        compose.waitForIdle()
        return repository
    }

    private fun headerBottom() = compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().bottom

    private fun hideControls() {
        val list = compose.onNodeWithTag("conversationList")
        val listBounds = list.getUnclippedBoundsInRoot()
        val composer = compose.onNodeWithTag("floatingComposer").getUnclippedBoundsInRoot()
        val edge = with(compose.density) { (composer.top - listBounds.top).toPx() }
        val bottom = with(compose.density) { (listBounds.bottom - listBounds.top).toPx() }
        list.performTouchInput {
            down(Offset(100f, edge - 160f))
            moveTo(Offset(100f, edge - 60f))
            moveTo(Offset(100f, bottom - 1f))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun gestureHidesBothUntilExplicitRestoreDespiteScrolling() {
        openLongChat()
        val list = compose.onNodeWithTag("conversationList")
        val top = compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().top
        // Scroll to older messages without crossing the composer's resting edge: a full-height
        // swipeDown is itself the hide gesture and would record an already-hidden header as baseline.
        list.performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.6f) }
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().top)
        hideControls()
        assertTrue(headerBottom() <= 0.dp)
        compose.onNodeWithTag("restoreChatControls").assertExists()
        compose.onNodeWithTag("composerField").assertDoesNotExist()
        val restore = compose.onNodeWithTag("restoreChatControls").getUnclippedBoundsInRoot()
        val latest = compose.onNodeWithTag("scrollToLatestSlot").getUnclippedBoundsInRoot()
        assertTrue("Restore overlaps latest", latest.bottom <= restore.top || restore.bottom <= latest.top || latest.left >= restore.right || restore.left >= latest.right)
        list.performTouchInput { swipeDown() }
        repeat(4) { list.performTouchInput { swipeUp() } }
        compose.waitForIdle()
        assertTrue(headerBottom() <= 0.dp)
        compose.onNodeWithTag("restoreChatControls").performClick()
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("restoreChatControls").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h891dp")
    fun largeTextOnNarrowScreenKeepsRestoreAndLatestApart() {
        openLongChat(fontScale = 2f)
        compose.onNodeWithTag("conversationList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        hideControls()
        val restore = compose.onNodeWithTag("restoreChatControls").getUnclippedBoundsInRoot()
        val latest = compose.onNodeWithTag("scrollToLatestSlot").getUnclippedBoundsInRoot()
        assertTrue("Restore overlaps latest at large font scale", latest.bottom <= restore.top)
    }

    @Test
    fun tabCannotFocusHiddenComposer() {
        openLongChat()
        hideControls()
        // The unmerged tree retains the field for this focus assertion even while its ancestor
        // clears the public semantics. Tab repeatedly traverses the actual hardware focus chain.
        repeat(12) {
            compose.onNodeWithTag("restoreChatControls").performKeyInput { pressKey(Key.Tab) }
            compose.onNodeWithTag("composerField", useUnmergedTree = true).assertIsNotFocused()
        }
    }

    @Test
    fun incomingDraftRestoresBothControls() {
        val repository = openLongChat()
        hideControls()
        assertTrue(headerBottom() <= 0.dp)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(draft = "Draft") }
        compose.waitForIdle()
        assertTrue(headerBottom() > 0.dp)
        compose.onNodeWithTag("restoreChatControls").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertExists()
    }

    @Test
    fun safetyGateRestoresBothControls() {
        val repository = openLongChat()
        hideControls()
        assertTrue(headerBottom() <= 0.dp)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(connected = false) }
        compose.waitForIdle()
        assertTrue(headerBottom() > 0.dp)
        compose.onNodeWithTag("restoreChatControls").assertDoesNotExist()
        compose.onNodeWithTag("composerField").assertExists()
    }
}
