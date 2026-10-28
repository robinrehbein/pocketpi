package de.joinnoah.pi.remote

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Tickets screen and the ticket detail, rendered from state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class TicketsScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000_000L
    private val project = TicketProject("p1", "Atlas", listOf("DEV"), true, null, true, null)

    private fun ticket(
        ref: String,
        section: TicketSection = TicketSection.WORKING,
        actions: Set<TicketAction> = emptySet(),
        url: String = "https://plane.example/atlas/$ref",
        sessions: List<TicketSession> = emptyList(),
        prOpen: Boolean = false,
        type: String? = "task",
    ) =
        Ticket(
            ref, "Title $ref", url, "In Progress", TicketStateGroup.STARTED, type, TicketPriority.HIGH,
            true, section, prOpen, actions, emptyList(), now, sessions,
        )

    private fun state(
        tickets: TicketsState,
        connected: Boolean = true,
    ) = RemoteState(connected = connected, tickets = tickets.copy(routeId = "studio"))

    private fun page(vararg items: Ticket, syncedAt: Long = now) =
        mapOf("p1" to TicketsPage("p1", true, null, syncedAt, items.toList(), null))

    private fun list(state: RemoteState) {
        compose.setContent {
            MaterialTheme { TicketsContent(state, now, onBack = {}, onReload = {}, onRetry = {}, onOpen = { _, _ -> }) }
        }
        compose.waitForIdle()
    }

    private fun detail(
        state: RemoteState,
        ref: String = "DEV-1",
        onOpenChat: (String) -> Unit = {},
        onOpenLink: (String) -> Boolean = { true },
    ) {
        compose.setContent {
            MaterialTheme {
                TicketDetailContent(
                    state, "p1", ref, onBack = {}, onRetry = {}, onOpenChat = onOpenChat,
                    onOpenLink = onOpenLink, onAccept = {}, onComment = {}, onMove = { _, _ -> }, onStart = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun detailState(ticket: Ticket, project: TicketProject = this.project, connected: Boolean = true) =
        state(
            TicketsState(projects = listOf(project), detailRef = ticket.ref, detail = TicketDetail(ticket, "Body *text*")),
            connected,
        )

    private fun label(id: Int) = compose.activity.getString(id)

    private fun headingTop(text: String): Float =
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().positionInRoot.y

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun sectionsInOrder() {
        list(
            state(
                TicketsState(
                    projects = listOf(project),
                    pages = page(
                        ticket("DEV-3", TicketSection.TODO),
                        ticket("DEV-2", TicketSection.WORKING),
                        ticket("DEV-1", TicketSection.ACCEPTANCE),
                    ),
                )
            )
        )
        val acceptance = headingTop(label(R.string.remote_tickets_section_acceptance))
        val working = headingTop(label(R.string.remote_tickets_section_working))
        val todo = headingTop(label(R.string.remote_tickets_section_todo))
        assertTrue(acceptance < working && working < todo)
        compose.onNodeWithText("DEV-1", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyLoadingShowsSkeletonNotEmptyText() {
        list(state(TicketsState(loading = true)))
        compose.onNodeWithContentDescription(label(R.string.remote_tickets_loading)).assertIsDisplayed()
        assertTrue(!has(label(R.string.remote_tickets_empty_title)))
        assertTrue(!has(label(R.string.remote_tickets_no_projects_title)))
    }

    @Test
    fun loadedWithoutTicketsSaysSo() {
        list(state(TicketsState(projects = listOf(project), pages = page())))
        compose.onNodeWithText(label(R.string.remote_tickets_empty_title)).assertIsDisplayed()
    }

    @Test
    fun unavailableShowsReason() {
        val down = project.copy(available = false, reason = TicketReason.PLANE_UNREACHABLE)
        list(state(TicketsState(projects = listOf(down))))
        compose.onNodeWithText(label(R.string.remote_tickets_reason_plane_unreachable)).assertIsDisplayed()
    }

    @Test
    fun unavailablePageWithUnknownReasonIsGeneric() {
        val pages = mapOf("p1" to TicketsPage("p1", false, null, null, emptyList(), null))
        list(state(TicketsState(projects = listOf(project), pages = pages)))
        compose.onNodeWithText(label(R.string.remote_tickets_reason_unknown)).assertIsDisplayed()
    }

    @Test
    fun staleLineWhenOld() {
        list(state(TicketsState(projects = listOf(project), pages = page(ticket("DEV-1"), syncedAt = now - 11 * 60_000))))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_stale, 11)).assertIsDisplayed()
    }

    @Test
    fun noStaleLineWhenFreshAndConnected() {
        list(state(TicketsState(projects = listOf(project), pages = page(ticket("DEV-1"), syncedAt = now - 60_000))))
        assertTrue(!has(compose.activity.getString(R.string.remote_tickets_stale, 1)))
    }

    @Test
    fun staleLineWhenDisconnected() {
        list(
            state(
                TicketsState(projects = listOf(project), pages = page(ticket("DEV-1"), syncedAt = now - 2 * 60_000)),
                connected = false,
            )
        )
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_stale, 2)).assertIsDisplayed()
    }

    @Test
    fun rowCaptionCountsChatsAndPullRequests() {
        val sessions =
            listOf(
                TicketSession("s1", "A", SessionLinkStatus.RUNNING),
                TicketSession("s2", "B", SessionLinkStatus.IDLE),
            )
        list(
            state(
                TicketsState(
                    projects = listOf(project),
                    pages = page(ticket("DEV-1", sessions = sessions), ticket("DEV-2", prOpen = true)),
                )
            )
        )
        compose.onNodeWithText("2 chats · 1 working").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_pr_open)).assertIsDisplayed()
    }

    @Test
    fun rowTapOpensTheTicket() {
        var opened: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                TicketsContent(
                    state(TicketsState(projects = listOf(project), pages = page(ticket("DEV-1")))), now,
                    onBack = {}, onReload = {}, onRetry = {}, onOpen = { p, r -> opened = p to r },
                )
            }
        }
        compose.onNodeWithText("Title DEV-1", substring = true).performClick()
        assertEquals("p1" to "DEV-1", opened)
    }

    @Test
    fun detailButtonsFollowActions() {
        detail(detailState(ticket("DEV-1", actions = setOf(TicketAction.COMMENT, TicketAction.MOVE))))
        compose.onNodeWithText(label(R.string.remote_ticket_comment)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_ticket_status)).assertIsDisplayed()
        assertTrue(!has(label(R.string.remote_ticket_accept)))
        assertTrue(!has(label(R.string.remote_ticket_start)))
    }

    @Test
    fun detailShowsAcceptWhenTheHostAllowsIt() {
        detail(detailState(ticket("DEV-1", actions = setOf(TicketAction.ACCEPT, TicketAction.COMMENT))))
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).assertIsDisplayed()
        assertTrue(!has(label(R.string.remote_ticket_status)))
    }

    @Test
    fun readOnlyLineWhenWritesBlocked() {
        val blocked = project.copy(writable = false, writeBlock = TicketWriteBlock.WRITES_OFF)
        detail(detailState(ticket("DEV-1", actions = setOf(TicketAction.COMMENT, TicketAction.ACCEPT)), blocked))
        compose.onNodeWithText(label(R.string.remote_ticket_write_block_writes_off)).assertIsDisplayed()
        assertTrue(!has(label(R.string.remote_ticket_comment)))
        assertTrue(!has(label(R.string.remote_ticket_accept)))
    }

    @Test
    fun missingUserKeyHasItsOwnLine() {
        val blocked = project.copy(writable = false, writeBlock = TicketWriteBlock.MISSING_USER_KEY)
        detail(detailState(ticket("DEV-1", actions = setOf(TicketAction.COMMENT)), blocked))
        compose.onNodeWithText(label(R.string.remote_ticket_write_block_missing_user_key)).assertIsDisplayed()
    }

    @Test
    fun offlineDisablesButtonsAndSaysWhy() {
        detail(detailState(ticket("DEV-1", actions = setOf(TicketAction.COMMENT)), connected = false))
        compose.onNodeWithText(label(R.string.remote_ticket_offline)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_ticket_comment)).assertIsNotEnabled()
    }

    @Test
    fun planeLinkOpensTheTicketsHttpsUrl() {
        val opened = mutableListOf<String>()
        detail(detailState(ticket("DEV-1")), onOpenLink = { opened += it; true })
        compose.onNodeWithContentDescription(label(R.string.remote_ticket_open_plane)).performClick()
        assertEquals(listOf("https://plane.example/atlas/DEV-1"), opened)
    }

    private fun assertNoPlaneLink(url: String) {
        detail(detailState(ticket("DEV-1", url = url)))
        assertTrue(url, compose.onAllNodesWithContentDescription(label(R.string.remote_ticket_open_plane)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noPlaneLinkForPlainHttp() = assertNoPlaneLink("http://plane.example/DEV-1")

    @Test
    fun noPlaneLinkForOtherSchemes() = assertNoPlaneLink("javascript:alert(1)")

    @Test
    fun noPlaneLinkForUserinfo() = assertNoPlaneLink("https://user@plane.example/DEV-1")

    @Test
    fun linkedChatsOpenTheChat() {
        var opened: String? = null
        val sessions = listOf(TicketSession("s9", "Fix it", SessionLinkStatus.WAITING))
        detail(detailState(ticket("DEV-1", sessions = sessions)), onOpenChat = { opened = it })
        compose.onNodeWithText(label(R.string.remote_ticket_chats)).assertIsDisplayed()
        compose.onNodeWithText("Fix it", substring = true).performClick()
        assertEquals("s9", opened)
    }

    @Test
    fun detailLoadingShowsSkeletonNotAnotherTicket() {
        val other = detailState(ticket("DEV-2"))
        detail(other, ref = "DEV-1")
        compose.onNodeWithContentDescription(label(R.string.remote_tickets_loading)).assertIsDisplayed()
        assertTrue(!has("Title DEV-2"))
    }

    @Test
    fun detailUnavailableShowsReason() {
        val unavailable = TicketDetail(null, reason = TicketReason.PLANE_REJECTED)
        detail(state(TicketsState(projects = listOf(project), detailRef = "DEV-1", detail = unavailable)))
        compose.onNodeWithText(label(R.string.remote_tickets_reason_plane_rejected)).assertIsDisplayed()
    }

    @Test
    fun detailShowsDescriptionAndNewestCommentFirst() {
        val comments =
            listOf(
                TicketComment("c1", "Ann", 1, "older note"),
                TicketComment("c2", "Bob", 2, "newer note"),
            )
        val t = ticket("DEV-1")
        detail(state(TicketsState(projects = listOf(project), detailRef = "DEV-1", detail = TicketDetail(t, "Body", comments = comments))))
        val newer = compose.onNodeWithText("newer note", substring = true).fetchSemanticsNode().positionInRoot.y
        val older = compose.onNodeWithText("older note", substring = true).fetchSemanticsNode().positionInRoot.y
        assertTrue(newer < older)
    }

    @Test
    fun germanCopyPassesGlossary() {
        val base = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val german = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale("de")) })
        val chats = german.resources.getQuantityString(R.plurals.remote_tickets_chats_caption, 2, 2)
        val working = german.resources.getQuantityString(R.plurals.remote_tickets_chats_working, 1, 1)
        assertEquals("2 Chats · 1 arbeitet", german.getString(R.string.remote_tickets_caption_join, chats, working))
        assertEquals("Zur Abnahme", german.getString(R.string.remote_tickets_section_acceptance))
        assertEquals("In Plane öffnen", german.getString(R.string.remote_ticket_open_plane))
    }
}
