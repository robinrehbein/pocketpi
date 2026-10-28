package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketDetailViewTest {
    private fun ticket(ref: String) =
        Ticket(
            ref, "T $ref", "https://plane/$ref", "Review", TicketStateGroup.STARTED, null, TicketPriority.NONE,
            true, TicketSection.WORKING, false, emptySet(), emptyList(), 1, emptyList(),
        )

    private fun state(tickets: TicketsState) = RemoteState(tickets = tickets)

    @Test
    fun readyWhenTheRequestedDetailIsLoaded() {
        val detail = TicketDetail(ticket("DEV-1"))
        val view = ticketDetailView(state(TicketsState(routeId = "r", detailRef = "DEV-1", detail = detail)), "DEV-1")
        assertEquals(TicketDetailView.Ready(detail), view)
    }

    @Test
    fun anotherTicketsDetailOrNoRequestIsLoading() {
        val detail = TicketDetail(ticket("DEV-1"))
        val other = state(TicketsState(routeId = "r", detailRef = "DEV-1", detail = detail))
        assertEquals(TicketDetailView.Loading, ticketDetailView(other, "DEV-2"))
        assertEquals(TicketDetailView.Loading, ticketDetailView(state(TicketsState()), "DEV-1"))
        assertEquals(
            TicketDetailView.Loading,
            ticketDetailView(state(TicketsState(routeId = "r", detailRef = "DEV-1", detailLoading = true)), "DEV-1"),
        )
    }

    @Test
    fun unavailableCarriesTheReason() {
        val detail = TicketDetail(null, reason = TicketReason.PLANE_UNREACHABLE)
        val view = ticketDetailView(state(TicketsState(routeId = "r", detailRef = "DEV-1", detail = detail)), "DEV-1")
        assertEquals(TicketDetailView.Unavailable(TicketReason.PLANE_UNREACHABLE), view)
    }

    @Test
    fun failedCarriesTheFailureAndALoadedDetailWins() {
        val failed = TicketsState(routeId = "r", detailRef = "DEV-1", detailFailure = TicketsFailure.OFFLINE)
        assertEquals(TicketDetailView.Failed(TicketsFailure.OFFLINE), ticketDetailView(state(failed), "DEV-1"))
        val detail = TicketDetail(ticket("DEV-1"))
        assertEquals(TicketDetailView.Ready(detail), ticketDetailView(state(failed.copy(detail = detail)), "DEV-1"))
    }
}
