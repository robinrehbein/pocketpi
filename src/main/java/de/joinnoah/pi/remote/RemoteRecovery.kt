package de.joinnoah.pi.remote

/**
 * Turns app foreground changes and default-network reports into recovery signals.
 *
 * A network is reported by its identity once it can carry traffic (INTERNET capable, not
 * suspended or blocked), whether or not Android validated it, so a network that never validates
 * still lets the app reconnect. Its path fingerprints the route (interface, addresses and, for a
 * VPN, the networks underneath); a change of it on the same network is a revalidation, not a
 * replacement.
 */
class RemoteRecovery {
    enum class Signal {
        FRESH_FOREGROUND,
        FRESH_NETWORK,
        NETWORK_REPLACED,
        /** The network in use came back or moved its route: probe the socket, do not tear it down. */
        NETWORK_REVALIDATED,
    }

    private var foreground = false
    private var network: String? = null
    private var path: String? = null
    /** The last network that was available, kept while there is none. */
    private var lastIdentity: String? = null
    private var replacedWhileBackground = false
    /**
     * The app returned while no network was usable, as when Android unblocks a backgrounded app's
     * network only after its return: the network's return is that resume.
     */
    private var resumeWaitsForNetwork = false

    /** Whether Android validated the current network. */
    var validated = false
        private set

    fun foreground(value: Boolean): Signal? {
        val changed = foreground != value
        foreground = value
        if (changed) resumeWaitsForNetwork = value && network == null && lastIdentity != null
        if (!changed || !value || network == null) return null
        return if (replacedWhileBackground) {
            replacedWhileBackground = false
            Signal.NETWORK_REPLACED
        } else Signal.FRESH_FOREGROUND
    }

    fun network(identity: String?, validated: Boolean = identity != null, path: String? = null): Signal? {
        val previous = network
        val previousPath = this.path
        network = identity
        this.validated = identity != null && validated
        this.path = path
        if (identity == null) return null
        val last = lastIdentity
        lastIdentity = identity
        if (previous == identity) {
            // Validation alone changes nothing about the route; a moved route is worth a probe.
            val moved = previousPath != null && path != null && previousPath != path
            return if (moved && foreground) Signal.NETWORK_REVALIDATED else null
        }
        // Another network than the last one: the socket's route is gone.
        val replaced = last != null && last != identity
        if (!foreground) {
            if (replaced) replacedWhileBackground = true
            return null
        }
        val resumed = resumeWaitsForNetwork
        resumeWaitsForNetwork = false
        return when {
            replaced || replacedWhileBackground -> {
                replacedWhileBackground = false
                Signal.NETWORK_REPLACED
            }
            // The resume of a return that found no network, now that there is one.
            resumed -> Signal.FRESH_FOREGROUND
            // The same network came back after a short loss: the socket may well have survived.
            last != null -> Signal.NETWORK_REVALIDATED
            else -> Signal.FRESH_NETWORK
        }
    }

    /** A push wake window is open: the background may reconnect, see [wake]. */
    private var waking = false

    /**
     * Opens or closes a push wake window. While it is open a usable network lets the app
     * reconnect in the background as it would in the foreground; [waitingForNetwork] still means
     * the foreground only.
     */
    fun wake(open: Boolean) {
        waking = open
    }

    /** Whether a usable network exists, in the foreground or not. */
    val hasNetwork: Boolean
        get() = network != null

    /** Whether the app is in front. */
    val isForeground: Boolean
        get() = foreground

    val canRecover: Boolean
        get() = (foreground || waking) && network != null

    /** In the foreground without a usable network: a reconnect waits for one, not for a timer. */
    val waitingForNetwork: Boolean
        get() = foreground && network == null
}
