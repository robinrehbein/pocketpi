package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The route IDs of the paired hosts in plain preferences, so a push checks its route without
 * decrypting the pairing store each time. A route ID only addresses the host at the relay; the
 * pairing's secret stays in the encrypted store. The cache is rewritten on every load and save of
 * the pairings ([RouteCachingPairings]). While it is missing, or a save is unfinished, a push reads
 * the store once and writes the cache again.
 */
internal class PairedRouteCache(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    /** The cached routes, or null when there is no complete cache. */
    fun routes(): Set<String>? =
        locked {
            if (!preferences.getBoolean(KEY_COMPLETE, false)) null
            else preferences.getStringSet(KEY_ROUTES, null)?.toSet()
        }

    /** The cached routes, or the routes of [load] when there is no cache, which they then become. */
    fun routes(load: () -> List<PairedHost>): Set<String> =
        locked { routes() ?: load().mapTo(mutableSetOf()) { it.routeId }.also(::write) }

    // commit, not apply: a process killed right after a pairing changed must not find the old routes.
    fun write(routes: Collection<String>) {
        locked {
            val written =
                preferences.edit().putStringSet(KEY_ROUTES, routes.toSet()).putBoolean(KEY_COMPLETE, true).commit()
            // The cache stays incomplete on disk, so the next process reads the store once more.
            if (!written) Diagnostics.caught("routes.write", java.io.IOException("Preferences not written"))
        }
    }

    fun clear() {
        locked { preferences.edit(commit = true) { clear() } }
    }

    /** Every cache in the process shares one lock, so a rebuild never writes back routes a save replaced. */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    private companion object {
        const val PREFERENCES = "paired_routes"
        const val KEY_ROUTES = "routes"
        const val KEY_COMPLETE = "complete"
        val lock = Any()
    }
}

/**
 * [pairings] with [cache] kept in step: every load writes the routes it read, and a save clears
 * the cache first, so a save that fails or a process killed meanwhile leaves no stale routes, only
 * a cache the next push rebuilds. [onHosts] sees the hosts of every load and save, so notifications
 * know each Mac's name without a push reading the store.
 */
internal class RouteCachingPairings(
    private val pairings: PairingStorage,
    private val cache: PairedRouteCache,
    private val onHosts: (List<PairedHost>) -> Unit = {},
) : PairingStorage {
    override fun load(): List<PairedHost> =
        cache.locked { pairings.load().also { hosts -> cache.write(hosts.map { it.routeId }) } }.also(onHosts)

    override fun save(hosts: List<PairedHost>) {
        cache.locked {
            cache.clear()
            pairings.save(hosts)
            cache.write(hosts.map { it.routeId })
        }
        onHosts(hosts)
    }
}
