package de.joinnoah.pi.remote

class RemoteRecovery {
    enum class Signal {
        FRESH_FOREGROUND,
        FRESH_NETWORK,
        NETWORK_REPLACED,
    }

    private var foreground = false
    private var network: String? = null
    private var replacedWhileBackground = false
    private var networkLostWhileForeground = false

    fun foreground(value: Boolean): Signal? {
        val changed = foreground != value
        foreground = value
        if (!changed || !value || network == null) return null
        return if (replacedWhileBackground || networkLostWhileForeground) {
            replacedWhileBackground = false
            networkLostWhileForeground = false
            Signal.NETWORK_REPLACED
        } else Signal.FRESH_FOREGROUND
    }

    fun network(identity: String?): Signal? {
        val previous = network
        if (previous == identity) return null
        network = identity
        if (!foreground) {
            if (previous != null && identity != previous) replacedWhileBackground = true
            return null
        }
        if (identity == null) {
            networkLostWhileForeground = previous != null
            return null
        }
        return if (previous == null && !networkLostWhileForeground && !replacedWhileBackground)
            Signal.FRESH_NETWORK
        else {
            replacedWhileBackground = false
            networkLostWhileForeground = false
            Signal.NETWORK_REPLACED
        }
    }

    val canRecover: Boolean
        get() = foreground && network != null
}
