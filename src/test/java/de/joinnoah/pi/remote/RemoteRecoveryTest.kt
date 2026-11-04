package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRecoveryTest {
    @Test
    fun foregroundAndValidatedNetworkSignalsAreCoalesced() {
        val recovery = RemoteRecovery()

        assertNull(recovery.foreground(true))
        assertEquals(RemoteRecovery.Signal.FRESH_NETWORK, recovery.network("wifi"))
        assertNull(recovery.network("wifi"))
        assertNull(recovery.foreground(false))
        assertEquals(RemoteRecovery.Signal.FRESH_FOREGROUND, recovery.foreground(true))
    }

    @Test
    fun replacementIsOnlyReportedWhileForeground() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("cellular"))
        recovery.foreground(false)
        assertNull(recovery.network("wifi"))
    }

    @Test
    fun networkReplacementWhileBackgroundIsReportedWhenForegroundReturns() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        recovery.foreground(false)
        recovery.network("cellular")

        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.foreground(true))
    }

    @Test
    fun networkLossThenRecoveryIsReportedAsAReplacement() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        assertNull(recovery.network(null))

        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("cellular"))
    }

    @Test
    fun backgroundNetworkLossThenForegroundRecoveryIsReportedAsAReplacement() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        recovery.foreground(false)
        recovery.network(null)
        assertNull(recovery.foreground(true))

        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("cellular"))
    }

    @Test
    fun theSameNetworkReturningInTheForegroundIsARevalidationNotATeardown() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        assertNull(recovery.network(null))
        assertTrue(recovery.waitingForNetwork)

        assertEquals(RemoteRecovery.Signal.NETWORK_REVALIDATED, recovery.network("wifi"))
        assertFalse(recovery.waitingForNetwork)
    }

    @Test
    fun theSameNetworkReturningInTheBackgroundLeavesTheResumeToTheForeground() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        recovery.foreground(false)
        assertNull(recovery.network(null))
        assertNull(recovery.network("wifi"))

        assertEquals(RemoteRecovery.Signal.FRESH_FOREGROUND, recovery.foreground(true))
    }

    @Test
    fun anotherNetworkIsStillAReplacement() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("lte"))
        assertNull(recovery.network(null))
        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("wifi"))
    }

    @Test
    fun anUnvalidatedNetworkAllowsRecoveryAndItsLaterValidationIsNoReplacement() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        assertEquals(RemoteRecovery.Signal.FRESH_NETWORK, recovery.network("wifi", validated = false))
        assertTrue(recovery.canRecover)
        assertFalse(recovery.validated)

        assertNull(recovery.network("wifi", validated = true))
        assertTrue(recovery.validated)
    }

    @Test
    fun aMovedRouteOnTheSameNetworkIsARevalidation() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("vpn", path = "tun0:10.0.0.2|vpn,wifi")
        // Validation and an unchanged route say nothing new.
        assertNull(recovery.network("vpn", path = "tun0:10.0.0.2|vpn,wifi"))
        assertEquals(
            RemoteRecovery.Signal.NETWORK_REVALIDATED,
            recovery.network("vpn", path = "tun0:10.0.0.2|vpn,cellular"),
        )
        // The first route a network reports is no move.
        recovery.network("other")
        assertNull(recovery.network("other", path = "wlan0:192.168.1.5"))

        // In the background a move is left to the resume.
        recovery.foreground(false)
        assertNull(recovery.network("other", path = "wlan0:192.168.1.6"))
        assertEquals(RemoteRecovery.Signal.FRESH_FOREGROUND, recovery.foreground(true))
    }

    @Test
    fun aNetworkUnblockedAfterTheReturnIsTheResume() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        recovery.foreground(false)
        // Blocked in the background, unblocked only after the app is back.
        assertNull(recovery.network(null))
        assertNull(recovery.foreground(true))

        assertEquals(RemoteRecovery.Signal.FRESH_FOREGROUND, recovery.network("wifi"))
        // Only once: a later loss and return of the same network is a revalidation again.
        assertNull(recovery.network(null))
        assertEquals(RemoteRecovery.Signal.NETWORK_REVALIDATED, recovery.network("wifi"))
    }

    @Test
    fun anotherNetworkAfterANetworklessReturnIsStillAReplacement() {
        val recovery = RemoteRecovery()

        recovery.foreground(true)
        recovery.network("wifi")
        recovery.foreground(false)
        assertNull(recovery.network(null))
        assertNull(recovery.foreground(true))

        assertEquals(RemoteRecovery.Signal.NETWORK_REPLACED, recovery.network("lte"))
    }

    @Test
    fun theFirstNetworkAfterLaunchIsAFreshNetwork() {
        val recovery = RemoteRecovery()

        assertNull(recovery.foreground(true))
        assertEquals(RemoteRecovery.Signal.FRESH_NETWORK, recovery.network("wifi"))
    }
}
