package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The empty lists say what to do next, and the Mac's host card says when it was used. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class EmptyStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val studio = PairedHost("studio", "wss://relay.example", "device-studio", "secret", "Studio")

    @Before
    fun storeStudioAsLastMac() {
        context().getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
        LastRouteStore(context()).save("studio")
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun label(id: Int) = compose.activity.getString(id)

    private fun project(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private fun chat(id: String, status: String): ProjectChatSummary =
        ProjectChatSummary(
            "p1",
            "Alpha",
            buildJsonObject {
                put("id", id)
                put("title", "Session $id")
                put("status", status)
                put("origin", "pi")
                put("updatedAt", 1L)
            },
            verified = true,
        )

    private fun render(
        projects: List<JsonObject> = emptyList(),
        chats: List<ProjectChatSummary> = emptyList(),
        capabilities: Set<String> = emptySet(),
        sessions: List<JsonObject> = emptyList(),
        pushConfigured: Boolean = false,
    ) {
        repository.state.value =
            RemoteState(
                hosts = listOf(studio),
                hostsLoaded = true,
                host = studio,
                selection = RemoteSelection("studio"),
                connected = true,
                connection = R.string.remote_connected,
                projects = projects,
                projectChats = chats,
                capabilities = capabilities,
                sessions = sessions,
            )
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, settings, pushConfigured, {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theHeadlineOfAnEmptyStateIsAHeadingAndItsActionShows() {
        compose.setContent {
            MaterialTheme {
                EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = "Nothing here",
                    body = "Add something to start.",
                    action = { Button(onClick = {}) { Text("Do it") } },
                )
            }
        }

        compose.onNode(hasText("Nothing here") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onNodeWithText("Add something to start.").assertIsDisplayed()
        compose.onNodeWithText("Do it").assertIsDisplayed()
    }

    @Test
    fun anEmptyProjectsListOffersOpenFolderOnlyWhenFoldersCanBeOpened() {
        render(capabilities = emptySet())

        compose.onNodeWithText(label(R.string.remote_empty_projects_title)).assertIsDisplayed()
        compose.onAllNodesWithTag("emptyOpenFolder").assertCountEquals(0)
    }

    @Test
    fun anEmptyProjectsListNamesOpenFolderWhenTheMacCanOpenFolders() {
        render(capabilities = setOf(PROJECT_OPEN_CAPABILITY))

        compose.onNodeWithTag("emptyOpenFolder").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_empty_projects_open_folder)).assertIsDisplayed()
    }

    @Test
    fun anEmptySessionListOffersNewSessionInlineAndAsTheButton() {
        render(projects = listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("projectRow-p1").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(label(R.string.remote_empty_sessions_title)).assertIsDisplayed()
        compose.onNodeWithTag("emptyNewSession").assertIsDisplayed()
        compose.onNodeWithTag("extendedNewSession").assertIsDisplayed()

        compose.onNodeWithTag("emptyNewSession").performClick()
        compose.waitForIdle()
        assertEquals(1, repository.createdSessions)
    }

    @Test
    fun theEmptySessionStateLeavesOnceASessionArrives() {
        render(
            projects = listOf(project("p1", "Alpha")),
        )
        compose.onNodeWithTag("projectRow-p1").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("emptyNewSession").assertCountEquals(1)
        repository.state.value = repository.state.value.copy(
            sessions = listOf(
                buildJsonObject {
                    put("id", "s1")
                    put("title", "First")
                    put("status", "idle")
                    put("origin", "pi")
                    put("updatedAt", 1L)
                },
            ),
        )
        compose.waitForIdle()

        compose.onAllNodesWithTag("emptyNewSession").assertCountEquals(0)
        compose.onAllNodesWithTag("extendedNewSession").assertCountEquals(0)
    }

    @Test
    fun quickAccessShowsAllBeyondThreeChatsAndFoldsBack() {
        val recent = (1..4).map { chat("r$it", "idle") }
        render(projects = listOf(project("p1", "Alpha")), chats = recent)

        compose.onNodeWithText("Session r3").assertIsDisplayed()
        compose.onAllNodesWithText("Session r4").assertCountEquals(0)

        compose.onNodeWithTag("quickChatsShowAllRecent").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Session r4").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_projects_show_fewer)).assertIsDisplayed()

        compose.onNodeWithTag("quickChatsShowAllRecent").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Session r4").assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_projects_show_all)).assertIsDisplayed()
    }

    @Test
    fun theShowAllOfTheWorkingGroupSitsUnderItsOwnChatsAndExpandsOnlyThem() {
        val running = (1..4).map { chat("w$it", "running") }
        render(projects = listOf(project("p1", "Alpha")), chats = running)

        compose.onAllNodesWithTag("quickChatsShowAllRecent").assertCountEquals(0)
        compose.onAllNodesWithText("Session w4").assertCountEquals(0)

        compose.onNodeWithTag("quickChatsShowAllRunning").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Session w4").assertIsDisplayed()
    }

    @Test
    fun quickAccessHasNoShowAllWhileThreeChatsFit() {
        render(projects = listOf(project("p1", "Alpha")), chats = (1..3).map { chat("r$it", "idle") })

        compose.onAllNodesWithTag("quickChatsShowAllRecent").assertCountEquals(0)
        compose.onAllNodesWithTag("quickChatsShowAllRunning").assertCountEquals(0)
    }

    @Test
    fun notNowHidesTheNotificationCardAndTheChoiceIsKept() {
        render(projects = listOf(project("p1", "Alpha")), pushConfigured = true)

        compose.onNodeWithTag("notificationNotNow").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("notificationNotNow").assertCountEquals(0)
        assertTrue(LastRouteStore(context()).notificationCardDismissed("studio"))
    }

    @Test
    fun theHostCardsSayWhenTheMacWasLastUsedOrHowToConnectWithoutItsRelay() {
        val now = System.currentTimeMillis()
        val laptop = PairedHost("laptop", "wss://relay.example", "device-laptop", "secret", "Laptop")
        compose.setContent {
            MaterialTheme {
                Column {
                    HostCard(studio, connected = false, lastUsedAt = now - 5 * 60_000L, onOpen = {}, onRemove = {})
                    HostCard(laptop, connected = false, lastUsedAt = null, onOpen = {}, onRemove = {})
                    HostCard(laptop, connected = true, lastUsedAt = null, onOpen = {}, onRemove = {})
                }
            }
        }

        compose.onNodeWithText("Last used 5 minutes ago").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_host_tap_to_connect)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_connected)).assertIsDisplayed()
        compose.onAllNodesWithText(studio.relay).assertCountEquals(0)
    }
}
