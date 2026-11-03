package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class TicketListHierarchyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000_000L
    private val project = TicketProject("p1", "Atlas", listOf("DEV"), true, null, true, null)

    private fun ticket(ref: String, section: TicketSection, title: String = "Title $ref") =
        Ticket(
            ref, title, "https://plane.example/atlas/$ref", "In Progress", TicketStateGroup.STARTED,
            "task", TicketPriority.HIGH, true, section, false, emptySet(), emptyList(), now, emptyList(),
        )

    private fun show(vararg tickets: Ticket, onOpen: (String, String) -> Unit = { _, _ -> }) {
        val page = TicketsPage("p1", true, null, now, tickets.toList(), null)
        val state = RemoteState(
            connected = true,
            tickets = TicketsState(routeId = "studio", projects = listOf(project), pages = mapOf("p1" to page)),
        )
        compose.setContent {
            MaterialTheme {
                TicketsContent(state, now, onBack = {}, onReload = {}, onRetry = {}, onOpen = onOpen)
            }
        }
        compose.waitForIdle()
    }

    private fun group(section: TicketSection) = compose.onNodeWithTag("ticketsGroup:${section.wire}")
    private fun filter(section: TicketSection) = compose.onNodeWithTag("ticketsFilter:${section.wire}")
    private fun card(ref: String) = compose.onNodeWithTag("ticketCard:$ref")

    @Test
    fun filterAbsentForEmptyPage() {
        show()
        compose.onNodeWithTag("ticketsFilter").assertDoesNotExist()
    }

    @Test
    fun filterAbsentForOneNonemptyGroup() {
        show(ticket("DEV-1", TicketSection.WORKING), ticket("DEV-2", TicketSection.WORKING))
        compose.onNodeWithTag("ticketsFilter").assertDoesNotExist()
        card("DEV-1").assertIsDisplayed()
        card("DEV-2").assertIsDisplayed()
    }

    @Test
    fun allAndSectionFiltersShowOnlyTheirTickets() {
        show(ticket("DEV-1", TicketSection.ACCEPTANCE), ticket("DEV-2", TicketSection.WORKING))
        compose.onNodeWithTag("ticketsFilter").assertIsDisplayed()
        compose.onNodeWithTag("ticketsFilter:all").assertIsSelected()
        card("DEV-1").assertIsDisplayed()
        card("DEV-2").assertIsDisplayed()
        filter(TicketSection.TODO).assertDoesNotExist()

        filter(TicketSection.WORKING).performClick()
        filter(TicketSection.WORKING).assertIsSelected()
        card("DEV-1").assertDoesNotExist()
        card("DEV-2").assertIsDisplayed()

        compose.onNodeWithTag("ticketsFilter:all").performClick()
        card("DEV-1").assertIsDisplayed()
        card("DEV-2").assertIsDisplayed()
    }

    @Test
    fun groupsStartOpenAndToggleIndependentlyWithoutNavigating() {
        val opened = mutableListOf<Pair<String, String>>()
        show(ticket("DEV-1", TicketSection.ACCEPTANCE), ticket("DEV-2", TicketSection.WORKING)) { p, r ->
            opened += p to r
        }
        group(TicketSection.ACCEPTANCE).assertHasClickAction()
        card("DEV-1").assertIsDisplayed()
        card("DEV-2").assertIsDisplayed()
        group(TicketSection.ACCEPTANCE).performClick()
        card("DEV-1").assertDoesNotExist()
        card("DEV-2").assertIsDisplayed()
        group(TicketSection.ACCEPTANCE).performClick()
        card("DEV-1").assertIsDisplayed()
        filter(TicketSection.WORKING).performClick()
        assertEquals(emptyList<Pair<String, String>>(), opened)
        card("DEV-2").assertHasClickAction().performClick()
        assertEquals(listOf("p1" to "DEV-2"), opened)
    }

    @Test
    fun groupCountsStayStableWhenCollapsedAndFiltered() {
        show(
            ticket("DEV-1", TicketSection.ACCEPTANCE),
            ticket("DEV-2", TicketSection.WORKING),
            ticket("DEV-3", TicketSection.WORKING),
        )
        val resources = compose.activity.resources
        val id = resources.getIdentifier("remote_tickets_group_count", "plurals", compose.activity.packageName)
        val one = resources.getQuantityString(id, 1, 1)
        val two = resources.getQuantityString(id, 2, 2)
        compose.onNodeWithText(one, substring = true).assertIsDisplayed()
        compose.onNodeWithText(two, substring = true).assertIsDisplayed()
        group(TicketSection.WORKING).performClick()
        compose.onNodeWithText(two, substring = true).assertIsDisplayed()
        filter(TicketSection.ACCEPTANCE).performClick()
        compose.onNodeWithText(one, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("ticketsFilter:all").performClick()
        compose.onNodeWithText(two, substring = true).assertIsDisplayed()
    }

    @Test
    fun missingFilteredSectionDoesNotReactivateWhenTicketsReturn() {
        val acceptance = ticket("DEV-1", TicketSection.ACCEPTANCE)
        val working = ticket("DEV-2", TicketSection.WORKING)
        fun state(items: List<Ticket>) = RemoteState(
            connected = true,
            tickets = TicketsState(
                routeId = "studio", projects = listOf(project),
                pages = mapOf("p1" to TicketsPage("p1", true, null, now, items, null)),
            ),
        )
        val current = mutableStateOf(state(listOf(acceptance, working)))
        compose.setContent {
            MaterialTheme {
                TicketsContent(current.value, now, onBack = {}, onReload = {}, onRetry = {}, onOpen = { _, _ -> })
            }
        }
        filter(TicketSection.WORKING).performClick()
        compose.runOnIdle { current.value = state(listOf(acceptance)) }
        compose.waitForIdle()
        card("DEV-1").assertIsDisplayed()
        compose.runOnIdle { current.value = state(listOf(acceptance, working)) }
        compose.waitForIdle()
        compose.onNodeWithTag("ticketsFilter:all").assertIsSelected()
        card("DEV-1").assertIsDisplayed()
        card("DEV-2").assertIsDisplayed()
    }

    @Test
    fun duplicateReferencesRenderAndOpenBothCards() {
        val opened = mutableListOf<Pair<String, String>>()
        show(
            ticket("DEV-42", TicketSection.TODO, "First occurrence"),
            ticket("DEV-42", TicketSection.TODO, "Second occurrence"),
        ) { p, r -> opened += p to r }
        compose.onNodeWithText("First occurrence").assertIsDisplayed().performClick()
        compose.onNodeWithText("Second occurrence").assertIsDisplayed().performClick()
        assertEquals(listOf("p1" to "DEV-42", "p1" to "DEV-42"), opened)
    }

    @Test
    fun longTitleRemainsReachableAndOpensCorrectTicket() {
        val opened = mutableListOf<Pair<String, String>>()
        val title = "A long ticket title describing the requested change and its acceptance criteria ".repeat(4)
        show(ticket("DEV-42", TicketSection.TODO, title)) { p, r -> opened += p to r }
        card("DEV-42").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(listOf("p1" to "DEV-42"), opened)
    }
}
