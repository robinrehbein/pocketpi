package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusChipTest {
    @Test
    fun theChipNamesEveryStateButALiveConnection() {
        for (line in listOf(
            R.string.remote_connecting,
            R.string.remote_reconnecting,
            R.string.remote_waiting_for_network,
            R.string.remote_waiting_for_mac,
            R.string.remote_approval,
            R.string.remote_offline,
        ))
            assertEquals(line, connectionStatusLine(connected = false, connection = line, hostChosen = true))
        assertNull(connectionStatusLine(connected = true, connection = R.string.remote_connected, hostChosen = true))
        // A slow resume probe checks the live connection: worth saying.
        assertEquals(
            R.string.remote_checking_connection,
            connectionStatusLine(connected = true, connection = R.string.remote_checking_connection, hostChosen = true),
        )
    }

    @Test
    fun withNoMacChosenOfflineSaysNothingButAnAttemptStillShows() {
        assertNull(connectionStatusLine(connected = false, connection = R.string.remote_offline, hostChosen = false))
        assertEquals(
            R.string.remote_connecting,
            connectionStatusLine(connected = false, connection = R.string.remote_connecting, hostChosen = false),
        )
        assertEquals(
            R.string.remote_reconnecting,
            connectionStatusLine(
                RemoteState(connected = false, stale = true, connection = R.string.remote_reconnecting)
            ),
        )
        assertNull(connectionStatusLine(RemoteState()))
    }
}
