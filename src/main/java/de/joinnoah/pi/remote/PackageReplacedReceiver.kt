package de.joinnoah.pi.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs once after the app is updated, before the user opens it. A Play update does not start the
 * process, so without this the launcher shortcuts an older version published would keep pointing
 * at that version's launch intents until something else started the app. One forced
 * [HomeSurfaces.refresh] republishes the shortcuts (pinned ones included) and redraws the widget
 * with the current [LaunchIntents].
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? RemoteApplication ?: return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { app.home.refresh() }
            } catch (swallowed: Exception) {
                Diagnostics.caught("packageReplaced.onReceive", swallowed)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // Broadcast receivers get about ten seconds.
        const val REFRESH_TIMEOUT_MILLIS = 8_000L
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
            )
    }
}
