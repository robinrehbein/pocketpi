package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTrackerTest {
    private val none = NetworkTracker.Report(null, false, null)

    private fun NetworkTracker.usable(
        network: String,
        validated: Boolean = true,
        underlying: String? = null,
    ) = capabilities(network, internet = true, notSuspended = true, validated = validated, underlying = underlying)

    @Test
    fun aNetworkCountsOnceItCanCarryTrafficValidatedOrNot() {
        val tracker = NetworkTracker()

        assertEquals(none, tracker.capabilities("wifi", internet = false, notSuspended = true, validated = false, underlying = null))
        assertEquals(none, tracker.capabilities("wifi", internet = true, notSuspended = false, validated = true, underlying = null))
        assertEquals(NetworkTracker.Report("wifi", false, null), tracker.usable("wifi", validated = false))
        assertEquals(NetworkTracker.Report("wifi", true, null), tracker.usable("wifi"))
    }

    @Test
    fun aBlockedNetworkIsNoNetworkUntilItIsUnblocked() {
        val tracker = NetworkTracker()
        tracker.usable("wifi")

        assertEquals(none, tracker.blocked("wifi", true))
        assertEquals(none, tracker.usable("wifi"))
        assertEquals(NetworkTracker.Report("wifi", true, null), tracker.blocked("wifi", false))
    }

    @Test
    fun thePathJoinsTheLinksAndAVpnsUnderlyingTransports() {
        val tracker = NetworkTracker()
        tracker.usable("vpn", underlying = "1")

        // Addresses in any order give the same path.
        assertEquals(
            NetworkTracker.Report("vpn", true, "tun0:10.0.0.2,fd00::2|1"),
            tracker.links("vpn", "tun0", listOf("fd00::2", "10.0.0.2")),
        )
        assertEquals(NetworkTracker.Report("vpn", true, "tun0:10.0.0.2,fd00::2|0"), tracker.usable("vpn", underlying = "0"))
        assertEquals(NetworkTracker.Report("vpn", true, "tun0:10.0.0.2,fd00::2"), tracker.usable("vpn"))
    }

    @Test
    fun anotherNetworkStartsFromNothing() {
        val tracker = NetworkTracker()
        tracker.usable("wifi")
        tracker.links("wifi", "wlan0", listOf("192.168.1.5"))
        tracker.blocked("wifi", true)

        // What was known about the old network does not carry over.
        assertEquals(none, tracker.links("lte", "rmnet0", listOf("10.1.2.3")))
        assertEquals(NetworkTracker.Report("lte", false, "rmnet0:10.1.2.3"), tracker.usable("lte", validated = false))
    }

    @Test
    fun onlyTheCurrentNetworkIsLost() {
        val tracker = NetworkTracker()
        tracker.usable("wifi")
        tracker.usable("lte")

        assertNull(tracker.lost("wifi"))
        assertEquals(none, tracker.lost("lte"))
        assertNull(tracker.lost("lte"))
    }
}
