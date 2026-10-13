package de.joinnoah.pi.remote

/**
 * Follows the default network as the network callback reports it, and says what to pass to
 * [RemoteRepository.setNetwork]. Pure bookkeeping, so the callback stays a thin adapter.
 *
 * A network counts once it can carry traffic (INTERNET capable, not suspended, not blocked),
 * validated or not, so a network Android never validates still reconnects. Its path (interface,
 * addresses and a VPN's underlying transports) lets a moved route be probed, not torn down.
 */
internal class NetworkTracker {
    /** The arguments of one [RemoteRepository.setNetwork] call; [identity] null: no network. */
    data class Report(val identity: String?, val validated: Boolean, val path: String?)

    private var current: String? = null
    private var usable = false
    private var validated = false
    private var blocked = false
    private var links: String? = null
    private var underlying: String? = null

    /**
     * New capabilities of [network]. [underlying]: for a VPN, the transports of the networks it
     * runs on, which change when it roams between Wi-Fi and LTE; null for any other network.
     */
    fun capabilities(
        network: String,
        internet: Boolean,
        notSuspended: Boolean,
        validated: Boolean,
        underlying: String?,
    ): Report =
        change(network) {
            usable = internet && notSuspended
            this.validated = validated
            this.underlying = underlying
        }

    /** New link properties of [network]: its interface and the addresses that identify its route. */
    fun links(network: String, interfaceName: String?, addresses: List<String>): Report =
        change(network) { links = (interfaceName ?: "") + ":" + addresses.sorted().joinToString(",") }

    /** [network] was blocked for this app (data saver, an always-on VPN without its tunnel) or unblocked. */
    fun blocked(network: String, blocked: Boolean): Report = change(network) { this.blocked = blocked }

    /** [network] is gone: no network if it was the current one, otherwise nothing to report. */
    fun lost(network: String): Report? {
        if (current != network) return null
        current = null
        return Report(null, false, null)
    }

    private fun change(network: String, block: () -> Unit): Report {
        if (current != network) {
            // Another default network: nothing known about it yet.
            current = network
            usable = false
            validated = false
            blocked = false
            links = null
            underlying = null
        }
        block()
        return report()
    }

    private fun report(): Report {
        val network = current
        return if (network == null || !usable || blocked) Report(null, false, null)
        else Report(network, validated, links?.let { listOfNotNull(it, underlying).joinToString("|") })
    }
}
