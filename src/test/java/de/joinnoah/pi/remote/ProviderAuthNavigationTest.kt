package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAuthNavigationTest {
    private val projects = listOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))

    @Test
    fun `providers open on top of projects of the same host only`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = projects.toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openProviders("other")
        assertEquals(projects, stack)
        navigation.openProviders("host")
        assertEquals(projects + RemoteNavKey.Providers("host"), stack)
        assertEquals(listOf("host"), repository.providersBrowsed)
    }

    @Test
    fun `back leaves the providers and restore keeps them on top`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = (projects + RemoteNavKey.Providers("host")).toMutableList()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.restore()
        runCurrent()
        assertEquals(projects + RemoteNavKey.Providers("host"), stack)
        navigation.back()
        runCurrent()
        assertEquals(projects, stack)
    }

    @Test
    fun `the providers key saves only the route`() {
        assertEquals(RemoteSelection("host"), RemoteNavKey.Providers("host").selection())
    }

    @Test
    fun `beside the open chat the providers open over it and back returns to it`() = runTest {
        for (open in listOf(RemoteSelection("host", "project"), RemoteSelection("host", "project", "s1"))) {
            val repository = NavigationFakeRepository()
            val stack = open.keys().toMutableList<NavKey>()
            val navigation = RemoteNavigator(repository, stack, backgroundScope)
            navigation.openProviders("other")
            assertEquals(open.keys(), stack)
            navigation.openProviders("host")
            runCurrent()
            assertEquals(open.keys() + RemoteNavKey.Providers("host"), stack)
            assertEquals(emptyList<Pair<RemoteSelection, ActivationMode>>(), repository.activations)
            assertEquals(listOf("host"), repository.providersBrowsed)
            navigation.back()
            runCurrent()
            assertEquals(open.keys(), stack)
            assertEquals(open to ActivationMode.RESTORE, repository.activations.last())
        }
    }

    @Test
    fun `a reconnect that narrows the selection keeps the providers on top`() = runTest {
        val open = RemoteSelection("host", "project", "s1")
        val repository = NavigationFakeRepository()
        val stack = (open.keys() + RemoteNavKey.Providers("host")).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.reconcile(RemoteState(selection = RemoteSelection("host", "project")))
        runCurrent()
        assertEquals(RemoteSelection("host", "project").keys() + RemoteNavKey.Providers("host"), stack)
    }

    @Test
    fun `settings pushed over the providers pops back to them`() = runTest {
        val open = RemoteSelection("host", "project", "s1")
        val repository = NavigationFakeRepository()
        val stack = (open.keys() + RemoteNavKey.Providers("host")).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.settings()
        navigation.back()
        assertEquals(open.keys() + RemoteNavKey.Providers("host"), stack)
    }

    @Test
    fun restore_after_process_death_keeps_the_providers_over_the_chat() = runTest {
        val open = RemoteSelection("host", "project", "s1")
        val repository = NavigationFakeRepository()
        repository.activation = { _, _ -> open }
        val stack = (open.keys() + RemoteNavKey.Providers("host")).toMutableList<NavKey>()
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.restore()
        runCurrent()
        assertEquals(open.keys() + RemoteNavKey.Providers("host"), stack)
    }
}
