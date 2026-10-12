package de.joinnoah.pi.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface PushTokenRequester {
    fun request(success: (String) -> Unit, failure: () -> Unit)
}

/** Call every method from the main dispatcher. */
class PushRegistration(
    private val scope: CoroutineScope,
    private val configured: () -> Boolean,
    private val enabled: () -> Boolean,
    private val requester: PushTokenRequester,
    private val register: (String) -> Unit,
    /** Tells the host to forget this phone's token. */
    private val unregister: () -> Unit = {},
    /** Push was switched off in the app and the host may not have been told yet. */
    private val optedOut: () -> Boolean = { false },
    private val timeoutMillis: Long = 10_000,
) {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var timeout: Job? = null

    init {
        require(timeoutMillis >= 0)
    }

    /**
     * Registers the token when push is on. When push was switched off, the host is told to forget
     * the token again on every start: the switch may have been flipped while it was unreachable,
     * and the repository sends the pending value on each connection.
     */
    fun onStartup() {
        if (configured() && !enabled() && optedOut()) unregister() else fetch()
    }

    fun onEnabled() = fetch()

    fun onRecovery() = fetch()

    /** Drops any token fetch in flight, so a late result is never registered, and unregisters. */
    fun onDisabled() {
        invalidate()
        if (configured()) unregister()
    }

    fun onTokenRotated(token: String) {
        invalidate()
        if (canRegister() && token.isNotBlank()) register(token)
    }

    private fun fetch() {
        if (!canRegister() || activeGeneration != null) return
        val generation = ++nextGeneration
        activeGeneration = generation
        try {
            requester.request(
                success = { token -> complete(generation, token) },
                failure = { complete(generation) },
            )
        } catch (_: Exception) {
            complete(generation)
        }
        if (activeGeneration == generation) {
            timeout =
                scope.launch {
                    delay(timeoutMillis)
                    if (activeGeneration == generation) {
                        activeGeneration = null
                        timeout = null
                    }
                }
        }
    }

    private fun complete(generation: Long, token: String? = null) {
        if (activeGeneration != generation) return
        activeGeneration = null
        timeout?.cancel()
        timeout = null
        if (token != null && token.isNotBlank() && canRegister()) register(token)
    }

    private fun invalidate() {
        nextGeneration++
        activeGeneration = null
        timeout?.cancel()
        timeout = null
    }

    private fun canRegister() = configured() && enabled()
}
