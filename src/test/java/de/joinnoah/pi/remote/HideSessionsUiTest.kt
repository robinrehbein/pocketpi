package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hiding a finished session on this phone: the Projects page menu, the undo bar and the list footer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class HideSessionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val studio = PairedHost("studio", "wss://relay.example", "device-studio", "secret", "Studio")
    private lateinit var store: SessionMarksStore

    @Before
    fun freshStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(SessionMarksStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        LastRouteStore(context).save("studio")
        store = SessionMarksStore(context)
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun terminalChat(id: String, updatedAt: Long): ProjectChatSummary =
        ProjectChatSummary(
            "p1", "Alpha",
            Wire.objectOf("id" to id, "title" to "Session $id", "origin" to "tui", "status" to "idle", "updatedAt" to updatedAt),
            verified = true,
        )

    private fun renderProjects(chats: List<ProjectChatSummary>) {
        repository.state.value = projectsState(chats)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSessionMarks provides store) {
                    PointerMenuHost { RemoteNavigation(repository, settings, false, {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun projectsState(chats: List<ProjectChatSummary>) =
        RemoteState(
            hosts = listOf(studio),
            hostsLoaded = true,
            host = studio,
            selection = RemoteSelection("studio"),
            connected = true,
            connection = R.string.remote_connected,
            projects = listOf(Wire.objectOf("id" to "p1", "name" to "Alpha")),
            projectChats = chats,
        )

    @Test
    fun hidingFromTheProjectsMenuRemovesTheRowAndUndoBringsItBack() {
        renderProjects(listOf(terminalChat("s1", 100)))
        compose.onNodeWithText("Session s1").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_session_hide").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Session s1").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.remote_session_hidden_snackbar)).assertIsDisplayed()
        assertEquals(100L, store.mark("studio", "s1")?.hiddenUpdatedAt)

        compose.onNodeWithText(label(R.string.remote_undo)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Session s1").assertIsDisplayed()
        assertNull(store.mark("studio", "s1")?.hiddenUpdatedAt)
    }

    @Test
    fun swipingAProjectsPageRowDoesNotHideIt() {
        renderProjects(listOf(terminalChat("s1", 100)))
        compose.onNodeWithText("Session s1").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("Session s1").assertIsDisplayed()
        assertNull(store.mark("studio", "s1")?.hiddenUpdatedAt)
    }

    @Test
    fun aNewerUpdateShowsAHiddenChatAgain() {
        store.hide("studio", "s1", updatedAt = 100, now = 200)
        renderProjects(listOf(terminalChat("s1", 100)))
        compose.onNodeWithText("Session s1").assertDoesNotExist()

        repository.state.value = projectsState(listOf(terminalChat("s1", 101)))
        compose.waitForIdle()

        compose.onNodeWithText("Session s1").assertIsDisplayed()
    }

    @Test
    fun theFooterCountsHiddenSessionsAndTogglesThem() {
        var shown by mutableStateOf(false)
        compose.setContent {
            MaterialTheme { HiddenSessionsFooter(count = 2, shown = shown, onToggle = { shown = !shown }) }
        }
        compose.onNodeWithText("2 hidden sessions ·").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_sessions_show_hidden)).assertIsDisplayed()

        compose.onNodeWithTag("hiddenSessionsFooter").performClick()
        compose.waitForIdle()

        assertTrue(shown)
        compose.onNodeWithText(label(R.string.remote_sessions_hide_hidden)).assertIsDisplayed()
    }

    @Test
    fun aShownHiddenRowOffersShowAgainInsteadOfHide() {
        val events = mutableListOf<String>()
        val item = sessionListItem(
            Wire.objectOf("id" to "s1", "title" to "Old run", "origin" to "tui", "status" to "idle"),
            connected = true, loading = false, confirmed = true,
        ).copy(hidden = true)
        compose.setContent {
            MaterialTheme {
                PointerMenuHost {
                    SessionListRow(
                        item = item, enabled = true, closeAvailable = false, onClick = {}, onClose = {},
                        onHide = { events += "hide" }, onUnhide = { events += "unhide" }, hidden = true,
                    )
                }
            }
        }
        compose.onNodeWithText(label(R.string.remote_session_hidden_label), substring = true).assertIsDisplayed()
        compose.onNodeWithText("Old run").performTouchInput { longClick() }
        compose.onNodeWithTag("menu_session_hide").assertDoesNotExist()
        compose.onNodeWithTag("menu_session_unhide").performClick()
        assertEquals(listOf("unhide"), events)
    }
}
