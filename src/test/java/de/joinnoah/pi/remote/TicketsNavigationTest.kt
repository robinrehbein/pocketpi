package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketsNavigationTest {
    private val projects = listOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))

    private fun repo(enabled: Boolean = true) =
        NavigationFakeRepository().also {
            if (enabled) it.state.value = RemoteState(connected = true, capabilities = setOf(TICKETS_CAPABILITY))
        }

    @Test
    fun openTicketsPushesOverHost() = runTest {
        val repository = repo()
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
        val repository = repo()
        val stack = (projects + RemoteNavKey.Tickets("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTicket("other", "p", "DEV-1")
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
        navigation.openTicket("host", "p", "DEV-1")
        assertEquals(projects + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-1"), stack)
        assertEquals(listOf("p" to "DEV-1"), repository.ticketOpens)
        navigation.back()
        runCurrent()
        assertEquals(projects + RemoteNavKey.Tickets("host"), stack)
    }

    @Test
    fun ticketKeysKeptAcrossRouteLikeProviders() = runTest {
        val repository = repo()
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

    @Test
    fun openTicketFromProjectsPushesDetailOverProjects() = runTest {
        val repository = repo()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTicket("other", "p", "DEV-1")
        assertEquals(projects, stack)
        navigation.openTicket("host", "p", "DEV-1")
        assertEquals(projects + RemoteNavKey.Ticket("host", "p", "DEV-1"), stack)
        navigation.back()
        runCurrent()
        assertEquals(projects, stack)
    }

    @Test
    fun openTicketFromChatPushesDetailOverChat() = runTest {
        val repository = repo()
        val open = RemoteSelection("host", "project", "s1").keys()
        val stack = open.toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTicket("host", "p", "DEV-1")
        assertEquals(open + RemoteNavKey.Ticket("host", "p", "DEV-1"), stack)
        navigation.back()
        runCurrent()
        assertEquals(open, stack)
    }

    @Test
    fun openTicketWhileTicketOnTopReplacesIt() = runTest {
        val repository = repo()
        val stack = (projects + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-1")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTicket("host", "p", "DEV-2")
        assertEquals(projects + RemoteNavKey.Tickets("host") + RemoteNavKey.Ticket("host", "p", "DEV-2"), stack)
        assertEquals(listOf("p" to "DEV-2"), repository.ticketOpens)
    }

    @Test
    fun ticketsOpenNothingWithoutTheCapability() = runTest {
        val repository = repo(enabled = false)
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openTickets("host")
        navigation.openTicket("host", "p", "DEV-1")
        assertEquals(projects, stack)
        assertEquals(emptyList<Pair<String, String>>(), repository.ticketOpens)
        assertEquals(emptyList<Boolean>(), repository.ticketRefreshes)
    }
}
