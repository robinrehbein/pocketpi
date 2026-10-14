package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The Mac whose Projects, Sessions or Chat the user opened last, in plain preferences of their own,
 * so a cold launch opens that Mac again (see [RemoteNavigator.coldStart]). The value is a route ID,
 * which only addresses the host at the relay; the pairing's secret stays in the encrypted store.
 */
internal class LastRouteStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    /** The stored route ID, or null when none is stored or the stored value is not a route ID. */
    fun load(): String? = preferences.getString(KEY_ROUTE, null)?.takeIf { isOpaqueId(it) }

    fun save(routeId: String) {
        preferences.edit { putString(KEY_ROUTE, routeId) }
    }

    /** Forgets the stored route, but only when it is [routeId]: another Mac stays remembered. */
    fun forget(routeId: String) {
        if (preferences.getString(KEY_ROUTE, null) == routeId) preferences.edit { remove(KEY_ROUTE) }
    }

    private companion object {
        const val PREFERENCES = "last_route"
        const val KEY_ROUTE = "routeId"
    }
}
