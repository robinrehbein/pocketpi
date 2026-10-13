package de.joinnoah.pi.remote

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Builds every intent PocketPi hands out to open itself (notifications, the widget and launcher
 * shortcuts) and decides which session a launch of MainActivity may open.
 *
 * MainActivity is exported for the launcher and the watch handoff, so any app can start it with any
 * extras. The app's own intents therefore go to [ALIAS], a non-exported activity-alias of it: only
 * PocketPi, and the system on its behalf (a PendingIntent, a launcher shortcut), can start that, and
 * only an intent addressed to it may name a session through extras. The watch handoff stays on the
 * exported `pocketpi://session?routeId&sessionId` link (see [parseWatchHandoff]), which older watch
 * builds depend on. Either way the session only opens when its host is paired here; any other
 * route leaves the app where it was, which is the hosts list on a cold start.
 *
 * Notifications posted by a version before the alias keep their old intents and open the app
 * without a session. Pinned and dynamic shortcuts are republished once per version (see
 * [HomeSurfaces]) and the widget is redrawn, so both carry alias intents again.
 */
internal object LaunchIntents {
    /** The non-exported activity-alias of MainActivity, declared in AndroidManifest.xml. */
    const val ALIAS = "de.joinnoah.pi.remote.InternalLaunch"

    private fun alias(context: Context): Intent =
        Intent()
            .setComponent(ComponentName(context.packageName, ALIAS))
            // Reuses the running activity instead of stacking a second one on top.
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Opens exactly this session, also from a cold start and from another project or host. */
    fun session(context: Context, routeId: String, sessionId: String): Intent =
        alias(context)
            .setAction(Intent.ACTION_VIEW)
            .setData(
                Uri.Builder()
                    .scheme("pocketpi")
                    .authority("session")
                    .appendPath(routeId)
                    .appendPath(sessionId)
                    .build()
            )
            .putExtra(RemoteNotifications.EXTRA_ROUTE, routeId)
            .putExtra(RemoteNotifications.EXTRA_TARGET, sessionId)

    /**
     * [session] for a notification [target]. A jobs target gets its own data URI, so its
     * PendingIntent never shares extras with the plain session one of the same session.
     */
    fun notification(context: Context, target: NotificationTarget): Intent {
        val intent = session(context, target.routeId, target.sessionId)
        if (!target.jobs) return intent
        val data =
            checkNotNull(intent.data).buildUpon().appendPath("jobs").apply {
                target.jobId?.let(::appendPath)
            }
        return intent
            .setData(data.build())
            .putExtra(RemoteNotifications.EXTRA_JOBS, true)
            .apply { target.jobId?.let { putExtra(RemoteNotifications.EXTRA_JOB, it) } }
    }

    /** Opens the app without choosing a session. */
    fun app(context: Context): Intent =
        alias(context).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** A notification's tap target for [session]. */
    fun open(context: Context, routeId: String, sessionId: String): PendingIntent =
        pending(context, session(context, routeId, sessionId))

    /** A notification's tap target for [target], with its background jobs when it names them. */
    fun open(context: Context, target: NotificationTarget): PendingIntent =
        pending(context, notification(context, target))

    private fun pending(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The session a launch of MainActivity asks to open, or null to open the app as it is. */
    fun target(intent: Intent): NotificationTarget? {
        // Reopening from Recents replays the task's original intent; it must not reopen a session
        // from an old notification, widget row or shortcut.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        parseWatchHandoff(intent)?.let { (route, session) -> return NotificationTarget(route, session) }
        if (intent.component?.className != ALIAS) return null
        // The same identifier check as the watch handoff: the session IDs come from the host.
        val route = intent.getStringExtra(RemoteNotifications.EXTRA_ROUTE)?.takeIf(::isOpaqueId) ?: return null
        val session = intent.getStringExtra(RemoteNotifications.EXTRA_TARGET)?.takeIf(::isOpaqueId) ?: return null
        return NotificationTarget(
            route,
            session,
            intent.getBooleanExtra(RemoteNotifications.EXTRA_JOBS, false),
            // Nullable on purpose: the strict push-id check, not the looser String one.
            intent.getStringExtra(RemoteNotifications.EXTRA_JOB).takeIf(::isOpaqueId),
        )
    }
}
