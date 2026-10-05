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
}
