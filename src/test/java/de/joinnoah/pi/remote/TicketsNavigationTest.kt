package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketsNavigationTest {
    private val projects = listOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))

    @Test
    fun openTicketsPushesOverHost() = runTest {
        val repository = NavigationFakeRepository()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTickets("other")
        assertEquals(projects, stack)
        navigation.openTickets("host")
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
        assertEquals(listOf(false), repository.ticketRefreshes)
        navigation.openTickets("host")
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
        assertEquals(RemoteSelection("host"), RemoteNavKey.Tickets("host").selection())
        assertEquals(RemoteSelection("host"), RemoteNavKey.Ticket("host", "p", "DEV-1").selection())
    }

    @Test
    fun openTicketFromTicketsPushesDetail() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (projects + RemoteNavKey.Tickets("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTicket("other", "p", "DEV-1")
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
        navigation.openTicket("host", "p", "DEV-1")
        assertEquals(projects + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-1"), stack)
        assertEquals(listOf("p" to "DEV-1"), repository.ticketOpens)
        navigation.openTicket("host", "p", "DEV-2")
        assertEquals(1, repository.ticketOpens.size)
        navigation.back()
        runCurrent()
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
    }

    @Test
    fun ticketKeysKeptAcrossRouteLikeProviders() = runTest {
        val repository = NavigationFakeRepository()
        val open = RemoteSelection("host", "project", "s1")
        val kept = open.keys() + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-1")
        val stack = kept.toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.reconcile(RemoteState(selection = RemoteSelection("host", "project")))
        runCurrent()
        assertEquals(
            RemoteSelection("host", "project").keys() + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-1"),
            stack,
        )
        navigation.restore()
        runCurrent()
        assertEquals(RemoteNavKey.Ticket("host", "p", "DEV-1"), stack.last())
    }
}
