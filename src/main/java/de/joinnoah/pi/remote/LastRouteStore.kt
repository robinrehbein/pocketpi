package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The Mac whose Projects, Sessions or Chat the user opened last, in plain preferences of their own,
 * so a cold launch opens that Mac again (see [RemoteNavigator.coldStart]). The value is a route ID,
 * which only addresses the host at the relay; the pairing's secret stays in the encrypted store.
 *
 * Each Mac also keeps, under its route ID, when it was last opened and whether "Not now" dismissed the
 * notification card on its Projects page. Both are forgotten once the Mac's pairing is gone (see [prune]).
 */
internal class LastRouteStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    /** The stored route ID, or null when none is stored or the stored value is not a route ID. */
    fun load(): String? = preferences.getString(KEY_ROUTE, null)?.takeIf { isOpaqueId(it) }

    /**
     * Remembers [routeId] as the Mac opened last. When it was opened is kept only for [used] opens: a restore
     * or a reconnect is not the user opening the Mac again.
     */
    fun save(routeId: String, now: Long = System.currentTimeMillis(), used: Boolean = true) {
        preferences.edit {
            putString(KEY_ROUTE, routeId)
            if (used) putLong(usedKey(routeId), now)
        }
    }

    /** When [routeId] was last opened, or null when it was not since it was paired. */
    fun lastUsedAt(routeId: String): Long? =
        if (preferences.contains(usedKey(routeId))) preferences.getLong(usedKey(routeId), 0L) else null

    /** Forgets the stored route, but only when it is [routeId]: another Mac stays remembered. */
    fun forget(routeId: String) {
        if (preferences.getString(KEY_ROUTE, null) == routeId) preferences.edit { remove(KEY_ROUTE) }
    }

    /** Whether "Not now" was pressed on the notification card of [routeId]'s Projects page. */
    fun notificationCardDismissed(routeId: String): Boolean = preferences.getBoolean(dismissedKey(routeId), false)

    fun dismissNotificationCard(routeId: String) {
        preferences.edit { putBoolean(dismissedKey(routeId), true) }
    }

    /**
     * Forgets what is kept for the Macs that are not in [routes], and the stored route when it is not
     * one of them. Only a list that was read says which pairings are gone, so callers check that first.
     */
    fun prune(routes: Collection<String>) {
        val kept = routes.toSet()
        val gone = preferences.all.keys.mapNotNull(::routeOfKey).filter { it !in kept }.distinct()
        val staleRoute = load()?.takeIf { it !in kept }
        if (gone.isEmpty() && staleRoute == null) return
        preferences.edit {
            gone.forEach {
                remove(usedKey(it))
                remove(dismissedKey(it))
            }
            if (staleRoute != null) remove(KEY_ROUTE)
        }
    }

    private fun routeOfKey(key: String): String? =
        when {
            key.startsWith(USED_PREFIX) -> key.removePrefix(USED_PREFIX)
            key.startsWith(DISMISSED_PREFIX) -> key.removePrefix(DISMISSED_PREFIX)
            else -> null
        }

    private fun usedKey(routeId: String) = USED_PREFIX + routeId

    private fun dismissedKey(routeId: String) = DISMISSED_PREFIX + routeId

    private companion object {
        const val PREFERENCES = "last_route"
        const val KEY_ROUTE = "routeId"
        const val USED_PREFIX = "used:"
        const val DISMISSED_PREFIX = "notificationCardDismissed:"
    }
}
