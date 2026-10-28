package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Mac's Projects page: its name, the chats that need the user first, badges and the Mac's overflow. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Tall enough that the lazy list composes every section, the recent chats at the end included.
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class MacHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val studio = PairedHost("studio", "wss://relay.example", "device-studio", "secret", "Studio")
    private val laptop = PairedHost("laptop", "wss://relay.example", "device-laptop", "secret", "Laptop")

    @Before
    fun storeStudioAsLastMac() {
        context().getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
        LastRouteStore(context()).save("studio")
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun project(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private fun session(id: String, status: String) = buildJsonObject {
        put("id", id)
        put("title", "Session $id")
        put("status", status)
        put("origin", "pi")
        put("updatedAt", 1L)
    }

    private fun chat(projectId: String, projectName: String, id: String, status: String, verified: Boolean = true) =
        ProjectChatSummary(projectId, projectName, session(id, status), verified)

    private fun render(
        projects: List<JsonObject>,
        chats: List<ProjectChatSummary> = emptyList(),
        counts: Map<String, ProjectSessions> = emptyMap(),
        hosts: List<PairedHost> = listOf(studio, laptop),
        connected: Boolean = true,
        connection: Int? = null,
        capabilities: Set<String> = emptySet(),
    ) {
        repository.state.value =
            RemoteState(
                hosts = hosts,
                hostsLoaded = true,
                host = studio,
                selection = RemoteSelection("studio"),
                connected = connected,
                capabilities = capabilities,
                connection = connection ?: if (connected) R.string.remote_connected else R.string.remote_offline,
                projects = projects,
                projectChats = chats,
                projectSessions = counts,
            )
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                RemoteNavigation(repository, settings, false, {})
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    /** The top of a section heading; a chat row can repeat a heading's words as its status. */
    private fun headingTop(text: String): Float =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().positionInRoot.y

    @Test
    fun theTitleNamesTheMacAndItsConnection() {
        render(listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("projectsMacTitle").assertTextEquals("Studio")
        compose.onNodeWithText(label(R.string.remote_connected)).assertIsDisplayed()
    }

    @Test
    fun theTitleSaysWhatTheConnectionChipSaysWhileTheMacIsOffline() {
        render(listOf(project("p1", "Alpha")), connected = false)

        compose.onNodeWithTag("projectsMacTitle").assertTextEquals("Studio")
        // The chip above says "Offline", and the status under the title says the same.
        compose.onNode(underTheTitle(label(R.string.remote_offline))).assertIsDisplayed()
        compose.onAllNodes(underTheTitle(label(R.string.remote_design_not_connected))).assertCountEquals(0)
    }

    /** A text under the Mac's name on its Projects page, where the status line is. */
    private fun underTheTitle(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("projectsMacHeading"))

    /** A text in the Mac's connection details dialog. */
    private fun inDetails(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("macDetailsBody"))

    @Test
    fun theTitleSaysConnectingWhileTheConnectionIsMade() {
        render(listOf(project("p1", "Alpha")), connected = false, connection = R.string.remote_connecting)

        // The chip above and the status under the title say the same thing; the title never says "Not connected".
        compose.onNode(underTheTitle(label(R.string.remote_connecting))).assertIsDisplayed()
        compose.onAllNodes(underTheTitle(label(R.string.remote_design_not_connected))).assertCountEquals(0)
    }

    @Test
    fun theTitleSaysCheckingWhileTheConnectionIsChecked() {
        render(listOf(project("p1", "Alpha")), connected = true, connection = R.string.remote_checking_connection)

        compose.onNode(underTheTitle(label(R.string.remote_checking_connection))).assertIsDisplayed()
        compose.onAllNodes(underTheTitle(label(R.string.remote_connected))).assertCountEquals(0)
    }

    @Test
    fun theDetailsDialogSaysWhatTheTitleSays() {
        render(listOf(project("p1", "Alpha")), connected = false, connection = R.string.remote_reconnecting)

        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macDetails").performClick()

        compose.onNode(inDetails(label(R.string.remote_reconnecting))).assertIsDisplayed()
        compose.onAllNodes(inDetails(label(R.string.remote_design_not_connected))).assertCountEquals(0)
    }

    @Test
    fun waitingChatsLeadThenWorkingChatsThenSharedProjectsThenRecentChats() {
        render(
            listOf(project("p1", "Alpha"), project("p2", "Beta")),
            chats =
                listOf(
                    chat("p2", "Beta", "recent", "idle"),
                    chat("p1", "Alpha", "working", "running"),
                    chat("p1", "Alpha", "waiting", "waiting"),
                ),
        )

        val needsYou = label(R.string.remote_status_needs_you)
        val working = label(R.string.remote_status_working)
        val shared = label(R.string.remote_projects_shared)
        val quick = label(R.string.remote_projects_quick_chats)
        assertTrue(headingTop(needsYou) < headingTop(working))
        assertTrue(headingTop(working) < headingTop(shared))
        assertTrue(headingTop(shared) < headingTop(quick))
    }

    @Test
    fun emptyGroupsAreHidden() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "waiting", "waiting")),
        )

        // The row's own status reads the same words, so match the group heading.
        compose.onNode(hasText(label(R.string.remote_status_needs_you)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_status_working)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_projects_quick_chats)).assertCountEquals(0)
    }

    @Test
    fun projectRowsShowTheCountsOfConfirmedProjectsOnly() {
        render(
            listOf(project("p1", "Alpha"), project("p2", "Beta")),
            counts =
                mapOf(
                    "p1" to
                        ProjectSessions(
                            projectId = "p1",
                            projectName = "Alpha",
                            sessions = emptyList(),
                            verified = true,
                            counts = SessionCounts(waiting = 2, running = 1),
                        ),
                    // A cached list carries no counts, so its row shows no badge.
                    "p2" to ProjectSessions("p2", "Beta", emptyList(), verified = false, counts = null),
                ),
        )

        compose.onNodeWithText("2 need you").assertIsDisplayed()
        compose.onNodeWithText("1 working").assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha, 2 need you, 1 working").assertIsDisplayed()
        compose.onAllNodesWithTag("projectBadgeWaiting-p2").assertCountEquals(0)
        compose.onAllNodesWithTag("projectBadgeWorking-p2").assertCountEquals(0)
        compose.onNodeWithTag("projectRow-p2").assertIsDisplayed()
    }

    @Test
    fun theOverflowHoldsTheMacActionsAndDisconnectKeepsThePairing() {
        render(listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macDisconnect").performClick()
        compose.onNode(hasText(label(R.string.remote_disconnect)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle {
            assertEquals(1, repository.disconnects)
            assertTrue(repository.removed.isEmpty())
            // A cold launch does not reconnect to the Mac the user just disconnected.
            assertNull(LastRouteStore(context()).load())
        }
    }

    @Test
    fun macOverflowShowsTicketsOnlyWithCapability() {
        render(projects = listOf(project("p", "App")))
        compose.onNodeWithTag("macOverflow").performClick()
        compose.onAllNodesWithTag("macTickets").assertCountEquals(0)
    }

    @Test
    fun macOverflowShowsTicketsWithCapabilityAndOpensThem() {
        render(projects = listOf(project("p", "App")), capabilities = setOf(TICKETS_CAPABILITY))
        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macTickets").performClick()
        compose.waitForIdle()
        // The navigator asks once; the opened page asks too while the fake state holds no tickets yet
        // (the real loader marks itself loading first, so the page then stays quiet). Neither forces.
        assertTrue(repository.ticketRefreshes.isNotEmpty() && repository.ticketRefreshes.none { it })
    }

    @Test
    fun removingThePairingConfirmsAndForgetsTheLastMac() {
        render(listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macRemove").performClick()
        compose.onNode(hasText(label(R.string.remote_remove)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("studio"), repository.removed)
            assertNull(LastRouteStore(context()).load())
        }
        // The fake keeps the selection on the removed Mac, as a Mac that never answered does. The page
        // still leaves the removed Mac: its Projects page is gone and the hosts list is what shows.
        compose.onAllNodesWithTag("projectsMacHeading").assertCountEquals(0)
        compose.onAllNodesWithTag("macOverflow").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, repository.disconnects) }
    }

    @Test
    fun connectionDetailsShowTheRelayAndTheDevice() {
        render(listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macDetails").performClick()

        compose.onNodeWithTag("macDetailsBody").assertIsDisplayed()
        compose.onNodeWithText("wss://relay.example").assertIsDisplayed()
        compose.onNodeWithText("device-studio").assertIsDisplayed()
        compose.onNodeWithTag("macDetailsClose").performClick()
        compose.onAllNodesWithTag("macDetailsBody").assertCountEquals(0)
    }

    @Test
    fun aPullRevalidatesTheLists() {
        render(listOf(project("p1", "Alpha")))

        compose.onNodeWithTag("listPullRefresh").performTouchInput { swipeDown(startY = 200f, endY = 900f) }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, repository.revalidations) }
    }
}
