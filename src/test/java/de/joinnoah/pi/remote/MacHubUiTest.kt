package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    // updatedAt = 1 is long ago: a ready row with it reads as old. Tests of fresh rows pass a recent time.
    private fun session(
        id: String,
        status: String,
        updatedAt: Long = 1L,
        origin: String = "pi",
        parent: String? = null,
    ) = buildJsonObject {
        put("id", id)
        put("title", "Session $id")
        put("status", status)
        put("origin", origin)
        put("updatedAt", updatedAt)
        if (parent != null) put("parentSessionId", parent)
    }

    private fun chat(
        projectId: String,
        projectName: String,
        id: String,
        status: String,
        verified: Boolean = true,
        updatedAt: Long = 1L,
        origin: String = "pi",
        parent: String? = null,
    ) = ProjectChatSummary(projectId, projectName, session(id, status, updatedAt, origin, parent), verified)

    private fun render(
        projects: List<JsonObject>,
        chats: List<ProjectChatSummary> = emptyList(),
        counts: Map<String, ProjectSessions> = emptyMap(),
        hosts: List<PairedHost> = listOf(studio, laptop),
        connected: Boolean = true,
        connection: Int? = null,
        capabilities: Set<String> = emptySet(),
        tickets: TicketsState = TicketsState(),
        fontScale: Float? = null,
        marks: SessionMarksStore? = null,
        pointerMenu: Boolean = false,
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
                tickets = tickets,
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density,
                LocalSessionMarks provides marks,
            ) {
                androidx.compose.material3.MaterialTheme {
                    if (pointerMenu) PointerMenuHost { RemoteNavigation(repository, settings, false, {}) }
                    else RemoteNavigation(repository, settings, false, {})
                }
            }
        }
        compose.waitForIdle()
    }

    /** A store that marks these sessions of the route as finished while the user was away, so unread. */
    private fun unreadMarks(routeId: String, vararg sessionIds: String) =
        SessionMarksStore(compose.activity).also { store ->
            sessionIds.forEach { store.markFinished(routeId, it, now = 5) }
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
    fun needsYouLeadsThenReadyThenWorkingThenProjectsThenRecent() {
        render(
            listOf(project("p1", "Alpha"), project("p2", "Beta")),
            chats =
                listOf(
                    chat("p2", "Beta", "recent", "idle", verified = false),
                    chat("p2", "Beta", "done", "idle"),
                    chat("p1", "Alpha", "working", "running"),
                    chat("p1", "Alpha", "waiting", "waiting"),
                ),
        )

        val needsYou = label(R.string.remote_status_needs_you)
        val ready = label(R.string.remote_status_ready)
        val working = label(R.string.remote_status_working)
        val shared = label(R.string.remote_projects_shared)
        val quick = label(R.string.remote_projects_quick_chats)
        assertTrue(headingTop(needsYou) < headingTop(ready))
        assertTrue(headingTop(ready) < headingTop(working))
        // The unverified idle chat sits under Recent, the verified one under Ready.
        assertTrue(headingTop(quick) < compose.onNodeWithText("Session recent").fetchSemanticsNode().positionInRoot.y)
        assertTrue(compose.onNodeWithText("Session done").fetchSemanticsNode().positionInRoot.y < headingTop(working))
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
        compose.onAllNodesWithText(label(R.string.remote_status_ready)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_projects_quick_chats)).assertCountEquals(0)
    }

    private fun readyHeading() =
        hasText(label(R.string.remote_status_ready)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun anOfflineMacShowsItsChatsUnderRecentNotReady() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "done", "idle", updatedAt = System.currentTimeMillis())),
            connected = false,
        )

        compose.onAllNodes(readyHeading()).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_projects_quick_chats)).assertIsDisplayed()
        compose.onNodeWithText("Session done").assertIsDisplayed()
    }

    @Test
    fun aReadyRowOlderThanADayShowsTheOldChip() {
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "stale", "idle", updatedAt = System.currentTimeMillis() - 2 * OLD_READY_MILLIS),
                    chat("p1", "Alpha", "fresh", "idle", updatedAt = System.currentTimeMillis()),
                ),
        )

        compose.onNodeWithTag("sessionOld-stale", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("sessionOld-fresh", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aFreshReadyRowShowsItsRelativeAge() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "done", "idle", updatedAt = System.currentTimeMillis() - 5 * 60_000L)),
        )

        compose.onNodeWithText("5 minutes ago").assertIsDisplayed()
    }

    @Test
    fun aProjectRowShowsItsUnreadReadyCount() {
        render(
            listOf(project("p1", "Alpha")),
            counts =
                mapOf(
                    "p1" to
                        ProjectSessions(
                            "p1",
                            "Alpha",
                            List(4) { Wire.objectOf("id" to "s$it", "status" to "idle", "origin" to "rpc", "updatedAt" to 10) },
                            verified = true,
                            counts = SessionCounts(running = 1),
                        ),
                ),
            marks = unreadMarks("studio", "s0", "s1", "s2"),
        )

        compose.onNodeWithText("3 new").assertIsDisplayed()
        compose.onNodeWithTag("projectBadgeReady-p1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha, 1 working, 3 new").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w411dp-h1600dp")
    fun threeBadgesKeepTheProjectNameVisibleAtLargeFont() {
        render(
            fontScale = 2f,
            projects = listOf(project("p1", "Alpha")),
            counts =
                mapOf(
                    "p1" to
                        ProjectSessions(
                            "p1",
                            "Alpha",
                            List(3) { Wire.objectOf("id" to "s$it", "status" to "idle", "origin" to "rpc", "updatedAt" to 10) },
                            verified = true,
                            counts = SessionCounts(waiting = 2, running = 1),
                        ),
                ),
            marks = unreadMarks("studio", "s0", "s1", "s2"),
        )

        val name = compose.onNode(hasText("Alpha") and hasAnyAncestor(hasTestTag("projectRow-p1")), useUnmergedTree = true)
        name.assertIsDisplayed()
        assertTrue(name.fetchSemanticsNode().size.width >= 48 * compose.activity.resources.displayMetrics.density)
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
        compose.onAllNodesWithTag("projectBadgeWaiting-p2", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("projectBadgeWorking-p2", useUnmergedTree = true).assertCountEquals(0)
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

    // ---- Zur Abnahme -----------------------------------------------------------------------

    private val ticketProject = TicketProject("p1", "Alpha", listOf("DEV"), true, null, true, null)

    private fun ticket(n: Int, section: TicketSection = TicketSection.ACCEPTANCE, sessions: List<TicketSession> = emptyList()) =
        Ticket(
            "DEV-$n", "Title $n", "https://plane.example/DEV-$n", "Review", TicketStateGroup.STARTED, "feature",
            TicketPriority.NONE, true, section, false,
            if (section == TicketSection.ACCEPTANCE) setOf(TicketAction.ACCEPT, TicketAction.COMMENT) else emptySet(),
            emptyList(), n.toLong(), sessions,
        )

    private fun ticketsState(vararg items: Ticket, loading: Boolean = false, writable: Boolean = true) =
        TicketsState(
            routeId = "studio",
            projects = listOf(ticketProject.copy(writable = writable)),
            pages = mapOf("p1" to TicketsPage("p1", true, null, 1L, items.toList(), null)),
            loading = loading,
        )

    private fun renderTickets(tickets: TicketsState, chats: List<ProjectChatSummary> = emptyList()) =
        render(
            listOf(project("p1", "Alpha")),
            chats = chats,
            capabilities = setOf(TICKETS_CAPABILITY),
            tickets = tickets,
        )

    @Test
    fun acceptanceSectionAboveNeedsYou() {
        renderTickets(ticketsState(ticket(1)), listOf(chat("p1", "Alpha", "waiting", "waiting")))

        assertTrue(
            headingTop(label(R.string.remote_tickets_section_acceptance)) <
                headingTop(label(R.string.remote_status_needs_you))
        )
        compose.onNodeWithText("Title 1").assertIsDisplayed()
        compose.onNodeWithText("DEV-1").assertIsDisplayed()
    }

    @Test
    fun acceptanceSectionAbsentWhenEmpty() {
        renderTickets(ticketsState(ticket(1, TicketSection.WORKING)))
        compose.onAllNodesWithText(label(R.string.remote_tickets_section_acceptance)).assertCountEquals(0)
    }

    @Test
    fun acceptanceSectionAbsentWhileNothingIsKnownYet() {
        renderTickets(ticketsState(ticket(1), loading = true).copy(pages = emptyMap()))
        compose.onAllNodesWithText(label(R.string.remote_tickets_section_acceptance)).assertCountEquals(0)
    }

    @Test
    fun aRefreshOfKnownTicketsKeepsTheSection() {
        renderTickets(ticketsState(ticket(1), loading = true))
        compose.onNodeWithText(label(R.string.remote_tickets_section_acceptance)).assertIsDisplayed()
        compose.onNodeWithText("Title 1").assertIsDisplayed()
        compose.onNodeWithText("DEV-1").assertIsDisplayed()
    }

    @Test
    fun theHomeAcceptButtonNamesItsTicket() {
        renderTickets(ticketsState(ticket(1)))
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.remote_ticket_accept_ref, "DEV-1")))
            .assertIsDisplayed()
    }

    @Test
    fun theHomeAcceptSheetCannotConfirmOnceTheMacIsGone() {
        renderTickets(ticketsState(ticket(1)))
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).performClick()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled()
        repository.state.value = repository.state.value.copy(connected = false)
        compose.waitForIdle()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
    }

    @Test
    fun theHomeWriteNoticeNamesItsTicket() {
        renderTickets(ticketsState(ticket(1)).copy(writeFailure = TicketsFailure.OFFLINE, writeRef = "DEV-1"))
        val text = compose.activity.getString(
            R.string.remote_tickets_notice_ref, "DEV-1", label(R.string.remote_tickets_failure_offline),
        )
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun acceptanceSectionAbsentWithoutCapability() {
        render(listOf(project("p1", "Alpha")), tickets = ticketsState(ticket(1)))
        compose.onAllNodesWithText(label(R.string.remote_tickets_section_acceptance)).assertCountEquals(0)
    }

    @Test
    fun atMostThreeRowsThenAllLink() {
        renderTickets(ticketsState(ticket(1), ticket(2), ticket(3), ticket(4)))

        compose.onAllNodes(hasText("Title", substring = true)).assertCountEquals(3)
        // The newest three show.
        compose.onNodeWithText("Title 4").assertIsDisplayed()
        compose.onNodeWithText("DEV-4").assertIsDisplayed()
        compose.onAllNodesWithText("Title 1").assertCountEquals(0)
        compose.onAllNodesWithText("DEV-1").assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_tickets_all)).performClick()
        compose.waitForIdle()
        // The link leads to the tickets screen, not just to a refresh.
        compose.onNodeWithTag("ticketsList").assertIsDisplayed()
        compose.onNodeWithTag("ticketsReload").assertIsDisplayed()
        assertTrue(repository.ticketRefreshes.isNotEmpty())
    }

    @Test
    fun acceptOnHomeOpensTheSheetAndQueuesTheAccept() {
        renderTickets(ticketsState(ticket(1)))
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_accept_title, "DEV-1")).assertIsDisplayed()
        compose.onNodeWithTag("ticketSheetConfirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("p1", "DEV-1", "Accepted in PocketPi.")), repository.ticketAccepts)
        // Accept no longer detours through the detail page.
        assertEquals(emptyList<Pair<String, String>>(), repository.ticketOpens)
    }

    @Test
    fun homeOffersNoSecondAcceptWhileOneIsPending() {
        renderTickets(ticketsState(ticket(1), ticket(2)).copy(pendingAccept = PendingAccept("p1", "DEV-1", "c", 5_000L)))
        compose.onAllNodesWithText(label(R.string.remote_ticket_accept)).assertCountEquals(0)
    }

    @Test
    fun homeShowsTheUndoBarForAPendingAcceptAndUndoSendsNothing() {
        val pending = PendingAccept("p1", "DEV-1", "c", 5_000L)
        renderTickets(ticketsState(ticket(1)).copy(pendingAccept = pending))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_accepting, "DEV-1")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_undo)).performClick()
        assertEquals(1, repository.ticketUndos)
        assertEquals(emptyList<Triple<String, String, String>>(), repository.ticketAccepts)
    }

    @Test
    fun allLinkFollowsEvenASingleRow() {
        renderTickets(ticketsState(ticket(1)))
        compose.onNodeWithText(label(R.string.remote_tickets_all)).assertIsDisplayed()
    }

    @Test
    fun rowTapOpensTheTicketAndTheButtonOnlyShowsWhenWritable() {
        renderTickets(ticketsState(ticket(1)))
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).assertIsDisplayed()
        compose.onNodeWithText("Title 1").performClick()
        assertEquals(listOf("p1" to "DEV-1"), repository.ticketOpens.distinct())
    }

    @Test
    fun noAcceptButtonForAReadOnlyProject() {
        renderTickets(ticketsState(ticket(1), writable = false))
        compose.onNodeWithText("Title 1").assertIsDisplayed()
        compose.onNodeWithText("DEV-1").assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_ticket_accept)).assertCountEquals(0)
    }

    @Test
    fun chatRowShowsTicketRef() {
        val linked = ticket(433, TicketSection.WORKING, listOf(TicketSession("waiting", "t", SessionLinkStatus.WAITING)))
        renderTickets(ticketsState(linked), listOf(chat("p1", "Alpha", "waiting", "waiting")))
        compose.onNode(hasText("DEV-433", substring = true) and !hasText("Title", substring = true)).assertIsDisplayed()
    }

    @Test
    fun talkBackRowDescriptionIncludesRef() {
        val linked = ticket(433, TicketSection.WORKING, listOf(TicketSession("waiting", "t", SessionLinkStatus.WAITING)))
        renderTickets(ticketsState(linked), listOf(chat("p1", "Alpha", "waiting", "waiting")))
        assertTrue(
            compose.onAllNodes(hasContentDescription("DEV-433", substring = true)).fetchSemanticsNodes().isNotEmpty()
        )
    }

    /** The clickable row of [projectId]'s chat [id], which carries its TalkBack actions. */
    private fun chatRow(projectId: String, id: String) =
        compose.onNode(
            hasAnyAncestor(hasTestTag("projectChatRow-$projectId-$id")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    private fun closeActions(projectId: String, id: String) =
        chatRow(projectId, id).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            .filter { it.label == label(R.string.remote_session_close) }

    private fun confirmClose() {
        compose.onNode(hasText(label(R.string.remote_session_close)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
    }

    @Test
    fun aClosableChatOnTheProjectsPageConfirmsAndClosesItInItsProject() {
        render(
            listOf(project("p1", "Alpha"), project("p2", "Beta")),
            chats = listOf(chat("p1", "Alpha", "one", "idle", origin = "rpc"), chat("p2", "Beta", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )

        val close = closeActions("p2", "two").single()
        compose.runOnUiThread { close.action() }
        compose.waitForIdle()
        confirmClose()

        assertEquals(listOf("p2" to "two"), repository.closedChats)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun aMacStartedChatOffersNoClose() {
        render(
            listOf(project("p2", "Beta")),
            chats = listOf(chat("p2", "Beta", "two", "idle", origin = "pi"), chat("p2", "Beta", "tui", "idle", origin = "tui")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )

        assertTrue(closeActions("p2", "two").isEmpty())
        assertTrue(closeActions("p2", "tui").isEmpty())
    }

    @Test
    fun aMainSessionWhoseSubagentWaitsOffersNoClose() {
        render(
            listOf(project("p2", "Beta")),
            chats =
                listOf(
                    chat("p2", "Beta", "main", "idle", origin = "rpc"),
                    chat("p2", "Beta", "sub", "waiting", origin = "rpc", parent = "main"),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )

        // The main session shows its subagent's question and waits with it.
        assertTrue(closeActions("p2", "main").isEmpty())
    }

    @Test
    fun projectsRowsFollowTheSwipeSetting() {
        settings.setSwipeEndToStart(SwipeAction.NONE)
        settings.setSwipeStartToEnd(SwipeAction.NONE)
        render(
            listOf(project("p2", "Beta")),
            chats = listOf(chat("p2", "Beta", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            pointerMenu = true,
        )

        chatRow("p2", "two").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)

        // The menu still offers Close.
        chatRow("p2", "two").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("menu_session_close").performClick()
        compose.waitForIdle()
        confirmClose()
        assertEquals(listOf("p2" to "two"), repository.closedChats)
    }

    @Test
    fun swipingAProjectsRowOpensTheCloseDialogByDefault() {
        render(
            listOf(project("p2", "Beta")),
            chats = listOf(chat("p2", "Beta", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )

        chatRow("p2", "two").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        confirmClose()
        assertEquals(listOf("p2" to "two"), repository.closedChats)
    }

    @Test
    fun theCloseDialogGoesWhenItsChatLeavesTheProjectsPage() {
        render(
            listOf(project("p2", "Beta")),
            chats = listOf(chat("p2", "Beta", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        compose.runOnUiThread { closeActions("p2", "two").single().action() }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(1)

        repository.state.value = repository.state.value.copy(projectChats = emptyList())
        compose.waitForIdle()

        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(emptyList<Pair<String, String>>(), repository.closedChats)
    }

    @Test
    fun theCloseButtonGreysOutWhenTheChatRunsAgain() {
        render(
            listOf(project("p2", "Beta")),
            chats = listOf(chat("p2", "Beta", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        compose.runOnUiThread { closeActions("p2", "two").single().action() }
        compose.waitForIdle()

        repository.state.value =
            repository.state.value.copy(projectChats = listOf(chat("p2", "Beta", "two", "running", origin = "rpc")))
        compose.waitForIdle()

        compose.onNode(hasText(label(R.string.remote_session_close)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .assertIsNotEnabled()
        assertEquals(emptyList<Pair<String, String>>(), repository.closedChats)
    }

    // "Review all" on the Ready group.

    private fun startReview() {
        compose.onNodeWithTag("readyReviewAll").performClick()
        compose.waitForIdle()
    }

    /** The card's primary button ignores taps for a moment after it shows; let that pass. */
    private fun armCard() {
        compose.mainClock.advanceTimeBy(READY_REVIEW_ARM_MILLIS + 50)
        compose.waitForIdle()
    }

    private fun reviewCardShows(title: String) =
        compose.onNode(hasTestTag("readyReviewCard") and hasContentDescription(title, substring = true)).assertExists()

    @Test
    fun reviewAllWalksTheReadyChatsAndClosesThroughTheProjectsPath() {
        val marks = unreadMarks("studio", "one", "two")
        render(
            listOf(project("p1", "Alpha"), project("p2", "Beta")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "rpc", updatedAt = 20L),
                    chat("p2", "Beta", "two", "idle", origin = "rpc", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            marks = marks,
        )
        compose.onNodeWithText(compose.activity.getString(R.string.remote_ready_review_all, 2)).assertExists()
        startReview()

        reviewCardShows("Session one")
        compose.onNodeWithText(compose.activity.getString(R.string.remote_ready_review_progress, 1, 2)).assertExists()
        // A chat the host runs closes at once: the review itself was the choice.
        armCard()
        compose.onNodeWithTag("readyReviewClose").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1" to "one"), repository.closedChats)
        assertTrue(!sessionUnread(marks.mark("studio", "one"), 20L, open = false))

        reviewCardShows("Session two")
        armCard()
        compose.onNodeWithTag("readyReviewClose").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1" to "one", "p2" to "two"), repository.closedChats)
        compose.onAllNodesWithTag("readyReviewSheet").assertCountEquals(0)
    }

    @Test
    fun keepMarksTheChatSeenAndMovesOn() {
        val marks = unreadMarks("studio", "one", "two")
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "rpc", updatedAt = 20L),
                    chat("p1", "Alpha", "two", "idle", origin = "rpc", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            marks = marks,
        )
        startReview()

        compose.onNodeWithTag("readyReviewKeep").performClick()
        compose.waitForIdle()

        assertTrue(!sessionUnread(marks.mark("studio", "one"), 20L, open = false))
        assertTrue(sessionUnread(marks.mark("studio", "two"), 10L, open = false))
        reviewCardShows("Session two")
        assertEquals(emptyList<Pair<String, String>>(), repository.closedChats)
    }

    @Test
    fun reviewAllIsOfferedForTwoOrMoreReadyChatsOnly() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "one", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        compose.onAllNodesWithTag("readyReviewAll").assertCountEquals(0)
    }

    @Test
    fun theReviewEndsWhenTheConnectionDrops() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "one", "idle", origin = "rpc"), chat("p1", "Alpha", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        startReview()
        compose.onAllNodesWithTag("readyReviewSheet").assertCountEquals(1)

        repository.state.value = repository.state.value.copy(connected = false)
        compose.waitForIdle()

        compose.onAllNodesWithTag("readyReviewSheet").assertCountEquals(0)
    }

    @Test
    fun theCardShowsThePreviewWhenTheRowHasOne() {
        val withPreview =
            ProjectChatSummary(
                "p1", "Alpha",
                buildJsonObject {
                    put("id", "one")
                    put("title", "Session one")
                    put("status", "idle")
                    put("origin", "rpc")
                    put("updatedAt", 20L)
                    put("preview", "The build passes now.")
                },
                verified = true,
            )
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(withPreview, chat("p1", "Alpha", "two", "idle", origin = "rpc", updatedAt = 10L)),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        startReview()

        reviewCardShows("The build passes now.")
        compose.onNodeWithTag("readyReviewKeep").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("readyReviewPreview", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aTerminalChatAsksOnceMoreBeforeTheReviewClosesIt() {
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "tui", "idle", origin = "tui", updatedAt = 20L),
                    chat("p1", "Alpha", "two", "idle", origin = "rpc", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY, SESSION_CLOSE_TERMINAL_CAPABILITY),
        )
        startReview()

        armCard()
        compose.onNodeWithTag("readyReviewClose").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, String>>(), repository.closedChats)
        compose.onNodeWithText(label(R.string.remote_session_close_terminal_confirmation)).assertExists()

        armCard()
        compose.onNodeWithTag("readyReviewConfirmClose").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1" to "tui"), repository.closedChats)
        reviewCardShows("Session two")
    }

    @Test
    fun closeGreysOutWhileThePageLoadsAndTheCardStays() {
        render(
            listOf(project("p1", "Alpha")),
            chats = listOf(chat("p1", "Alpha", "one", "idle", origin = "rpc"), chat("p1", "Alpha", "two", "idle", origin = "rpc")),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        startReview()

        repository.state.value = repository.state.value.copy(loading = true)
        compose.waitForIdle()

        compose.onNodeWithTag("readyReviewClose").assertIsNotEnabled()
        compose.onAllNodesWithTag("readyReviewHide").assertCountEquals(0)
    }

    @Test
    fun aChatThePhoneCannotCloseIsHiddenWithAnUndoInTheSheet() {
        val marks = SessionMarksStore(compose.activity)
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "pi", updatedAt = 30L),
                    chat("p1", "Alpha", "two", "idle", origin = "pi", updatedAt = 20L),
                    chat("p1", "Alpha", "three", "idle", origin = "pi", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            marks = marks,
        )
        startReview()
        compose.onAllNodesWithTag("readyReviewClose").assertCountEquals(0)

        armCard()
        compose.onNodeWithTag("readyReviewHide").performClick()
        compose.waitForIdle()
        armCard()
        compose.onNodeWithTag("readyReviewHide").performClick()
        compose.waitForIdle()
        assertTrue(marks.mark("studio", "one")?.hiddenAt != null)
        assertTrue(marks.mark("studio", "two")?.hiddenAt != null)

        // Undo goes back through the hides, the latest first.
        compose.onNodeWithTag("readyReviewUndo").performClick()
        compose.waitForIdle()
        assertNull(marks.mark("studio", "two")?.hiddenAt)
        compose.onNodeWithTag("readyReviewUndo").performClick()
        compose.waitForIdle()
        assertNull(marks.mark("studio", "one")?.hiddenAt)
        compose.onAllNodesWithTag("readyReviewUndo").assertCountEquals(0)
        reviewCardShows("Session three")
        assertEquals(emptyList<Pair<String, String>>(), repository.closedChats)
    }

    @Test
    fun openEndsTheReviewInTheChat() {
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "rpc", updatedAt = 20L),
                    chat("p1", "Alpha", "two", "idle", origin = "rpc", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        startReview()

        compose.onNodeWithTag("readyReviewOpen").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("readyReviewSheet").assertCountEquals(0)
        assertTrue(repository.activations.any { it.first.projectId == "p1" && it.first.sessionId == "one" })
    }

    @Test
    fun aQuickDoubleTapOnCloseDoesNotCloseTheNextChatUnseen() {
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "rpc", updatedAt = 20L),
                    chat("p1", "Alpha", "two", "idle", origin = "rpc", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
        )
        startReview()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("readyReviewClose").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("readyReviewClose").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("p1" to "one"), repository.closedChats)

        compose.mainClock.advanceTimeBy(READY_REVIEW_ARM_MILLIS + 50)
        compose.onNodeWithTag("readyReviewClose").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("p1" to "one", "p1" to "two"), repository.closedChats)
    }

    @Test
    fun aHideMadeInTheReviewGetsTheUndoSnackbarWhenTheSheetIsDismissed() {
        val marks = SessionMarksStore(compose.activity)
        render(
            listOf(project("p1", "Alpha")),
            chats =
                listOf(
                    chat("p1", "Alpha", "one", "idle", origin = "pi", updatedAt = 20L),
                    chat("p1", "Alpha", "two", "idle", origin = "pi", updatedAt = 10L),
                ),
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            marks = marks,
        )
        startReview()
        armCard()
        compose.onNodeWithTag("readyReviewHide").performClick()
        compose.waitForIdle()

        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()

        compose.onAllNodesWithTag("readyReviewSheet").assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_undo)).assertExists()
    }
}
