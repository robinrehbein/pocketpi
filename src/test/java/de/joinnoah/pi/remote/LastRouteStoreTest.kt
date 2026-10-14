package de.joinnoah.pi.remote

import android.content.Context
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
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

/** What the Mac pages keep per Mac: the last one opened, when each was used, and "Not now" on its card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastRouteStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStore() {
        context.getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun host(routeId: String) =
        PairedHost(routeId, "wss://relay.example", "device-$routeId", "secret-$routeId", "Mac $routeId")

    @Test
    fun notNowIsRememberedForThatMacOnly() {
        val store = LastRouteStore(context)

        store.dismissNotificationCard("a")

        assertTrue(LastRouteStore(context).notificationCardDismissed("a"))
        assertFalse(LastRouteStore(context).notificationCardDismissed("b"))
    }

    @Test
    fun lastUseIsKeptPerMac() {
        val store = LastRouteStore(context)

        store.save("a", now = 100L)
        store.save("b", now = 200L)

        assertEquals(100L, LastRouteStore(context).lastUsedAt("a"))
        assertEquals(200L, LastRouteStore(context).lastUsedAt("b"))
        assertNull(LastRouteStore(context).lastUsedAt("c"))
        assertEquals("b", LastRouteStore(context).load())
    }

    @Test
    fun disconnectingKeepsWhatTheMacKeepsAndOnlyForgetsTheColdStart() {
        val store = LastRouteStore(context)
        store.save("a", now = 100L)
        store.dismissNotificationCard("a")

        store.forget("a")

        assertNull(LastRouteStore(context).load())
        assertEquals(100L, LastRouteStore(context).lastUsedAt("a"))
        assertTrue(LastRouteStore(context).notificationCardDismissed("a"))
    }

    @Test
    fun pruningForgetsTheMacsWhosePairingIsGone() {
        val store = LastRouteStore(context)
        store.save("kept", now = 200L)
        store.dismissNotificationCard("kept")
        // The Mac opened last is the one a cold launch would open, so its pairing going clears the stored route.
        store.save("gone", now = 100L)
        store.dismissNotificationCard("gone")

        store.prune(listOf("kept"))

        val after = LastRouteStore(context)
        assertNull(after.load())
        assertNull(after.lastUsedAt("gone"))
        assertFalse(after.notificationCardDismissed("gone"))
        assertEquals(200L, after.lastUsedAt("kept"))
        assertTrue(after.notificationCardDismissed("kept"))
    }

    @Test
    fun removingAPairingClearsNotNowOnlyAfterTheHostsWereRead() = runTest {
        val store = LastRouteStore(context)
        store.dismissNotificationCard("a")
        store.dismissNotificationCard("b")
        val navigation = RemoteNavigator(NavigationFakeRepository(), mutableListOf<NavKey>(RemoteNavKey.Hosts), backgroundScope, store)

        navigation.forgetGoneHosts(RemoteState(hosts = listOf(host("b")), hostsLoaded = false))
        assertTrue(LastRouteStore(context).notificationCardDismissed("a"))

        navigation.forgetGoneHosts(RemoteState(hosts = listOf(host("b")), hostsLoaded = true))

        assertFalse(LastRouteStore(context).notificationCardDismissed("a"))
        assertTrue(LastRouteStore(context).notificationCardDismissed("b"))
    }

    @Test
    fun aRestoreRemembersTheMacButIsNoOpen() {
        val store = LastRouteStore(context)

        store.save("a", now = 100L, used = false)

        assertEquals("a", LastRouteStore(context).load())
        assertNull(LastRouteStore(context).lastUsedAt("a"))
    }
}
