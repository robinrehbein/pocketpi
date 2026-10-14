package de.joinnoah.pi.remote

import android.content.Context
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cold start on the last Mac, and the Mac each page remembers (see [LastRouteStore]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MacHubNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStore() {
        context.getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun host(routeId: String) =
        PairedHost(routeId, "wss://relay.example", "device-$routeId", "secret-$routeId", "Mac $routeId")

    private fun state(vararg routes: String, loaded: Boolean = true) =
        RemoteState(hosts = routes.map(::host), hostsLoaded = loaded)

    @Test
    fun `a stored Mac that is still paired is the one a cold launch opens, above Hosts`() = runTest {
        LastRouteStore(context).save("b")
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        assertTrue(navigation.coldStart(state("a", "b")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("b")), stack)
        assertEquals(listOf(RemoteSelection("b") to ActivationMode.RESTORE), repository.activations)
        assertEquals("b", LastRouteStore(context).load())
    }

    @Test
    fun `a stored Mac that is gone is ignored and forgotten only once the paired Macs are read`() = runTest {
        LastRouteStore(context).save("gone")
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        // Until the list is read, nothing says whether the stored Mac is still paired.
        assertFalse(navigation.coldStart(state("a", "b", loaded = false)))
        assertEquals("gone", LastRouteStore(context).load())
        assertEquals(listOf(RemoteNavKey.Hosts), stack)

        assertTrue(navigation.coldStart(state("a", "b")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts), stack)
        assertTrue(repository.activations.isEmpty())
        assertNull(LastRouteStore(context).load())
    }

    @Test
    fun `a single paired Mac opens on its Projects without a stored one`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        assertTrue(navigation.coldStart(state("only")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("only")), stack)
    }

    @Test
    fun `a single paired Mac opens even when the stored one is gone, and the stored one is forgotten`() = runTest {
        LastRouteStore(context).save("gone")
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(NavigationFakeRepository(), stack, backgroundScope, LastRouteStore(context))

        assertTrue(navigation.coldStart(state("only")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("only")), stack)
        assertEquals("only", LastRouteStore(context).load())
    }

    @Test
    fun `several paired Macs and nothing stored keep the hosts list`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        assertTrue(navigation.coldStart(state("a", "b")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts), stack)
        assertTrue(repository.activations.isEmpty())
    }

    @Test
    fun `a cold start leaves a stack the user has moved on from alone`() = runTest {
        LastRouteStore(context).save("b")
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("a"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        assertTrue(navigation.coldStart(state("a", "b")))
        runCurrent()

        assertEquals(listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("a")), stack)
        assertTrue(repository.activations.isEmpty())
    }

    @Test
    fun `opening a page remembers its Mac for the next cold launch`() = runTest {
        val repository = NavigationFakeRepository()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        val navigation = RemoteNavigator(repository, stack, backgroundScope, LastRouteStore(context))

        navigation.open(RemoteNavKey.Projects("b"))
        runCurrent()
        assertEquals("b", LastRouteStore(context).load())

        navigation.open(RemoteNavKey.Sessions("c", "project"))
        runCurrent()
        assertEquals("c", LastRouteStore(context).load())
    }

    @Test
    fun `forgetting a Mac keeps another remembered`() {
        val store = LastRouteStore(context)
        store.save("a")

        store.forget("b")
        assertEquals("a", store.load())

        store.forget("a")
        assertNull(store.load())
    }

    @Test
    fun `a stored value that is not a route ID is not used`() {
        LastRouteStore(context).save("not a route")
        assertNull(LastRouteStore(context).load())
    }

    @Test
    fun `the cold start Mac is the stored one while paired, else the only one, else none`() {
        assertEquals("b", coldStartRoute(listOf("a", "b"), "b"))
        assertEquals(null, coldStartRoute(listOf("a", "b"), "c"))
        assertEquals(null, coldStartRoute(listOf("a", "b"), null))
        assertEquals("a", coldStartRoute(listOf("a"), "c"))
        assertEquals("a", coldStartRoute(listOf("a"), null))
        assertEquals(null, coldStartRoute(emptyList(), "a"))
        assertEquals(null, coldStartRoute(emptyList(), null))
    }
}
