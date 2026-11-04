package de.joinnoah.pi.remote

import android.content.Intent
import android.net.Uri

/** Routing identifiers only. The receiving phone still verifies its own saved host pairing. */
internal fun watchHandoffUri(selection: RemoteSelection): Uri? {
    val route = selection.routeId?.takeIf(::isOpaqueId) ?: return null
    val session = selection.sessionId?.takeIf(::isOpaqueId) ?: return null
    return Uri.Builder().scheme("pocketpi").authority("session")
        .appendQueryParameter("routeId", route).appendQueryParameter("sessionId", session).build()
}

internal fun parseWatchHandoff(intent: Intent): Pair<String, String>? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme != "pocketpi" || uri.host != "session" || uri.port != -1 || uri.userInfo != null ||
        !uri.path.isNullOrEmpty() || uri.fragment != null || uri.toString().length > 4096) return null
    if (uri.queryParameterNames != setOf("routeId", "sessionId")) return null
    fun identifier(key: String) = uri.getQueryParameters(key).singleOrNull()?.takeIf(::isOpaqueId)
    val route = identifier("routeId") ?: return null
    val session = identifier("sessionId") ?: return null
    return route to session
}
