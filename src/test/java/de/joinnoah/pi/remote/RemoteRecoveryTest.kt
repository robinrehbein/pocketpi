package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
