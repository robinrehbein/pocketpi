package de.joinnoah.pi.remote

import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Builds every intent PocketPi hands out to open itself (notifications, the widget, launcher
 * shortcuts and the share sheet's hand-off) and decides which session a launch of MainActivity may
 * open.
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
 * [HomeSurfaces]) and the widget is redrawn, so both carry alias intents again; that first publish
 * runs right after the update ([PackageReplacedReceiver]), before the user can tap a shortcut.
 *
 * The extras ignored on the exported component use the same keys as the FCM data. The app expects
 * data-only pushes and [RemoteMessagingService] posts every notification itself; a push with a
 * `notification` block would instead get a tray notification from the Firebase SDK while the app
 * is in the background, and tapping it would open the app root, not the session.
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

    /** The action of [share]'s hand-off from ShareActivity; only the alias honours it. */
    const val ACTION_SHARE = "de.joinnoah.pi.remote.action.SHARE"
    private const val EXTRA_SHARE_IMAGES = "de.joinnoah.pi.remote.extra.SHARE_IMAGES"
    private const val EXTRA_SHARE_TARGET = "de.joinnoah.pi.remote.extra.SHARE_TARGET"
    private const val EXTRA_SHARE_ROUTE = "de.joinnoah.pi.remote.extra.SHARE_ROUTE"
    private const val EXTRA_SHARE_PROJECT = "de.joinnoah.pi.remote.extra.SHARE_PROJECT"
    private const val EXTRA_SHARE_SESSION = "de.joinnoah.pi.remote.extra.SHARE_SESSION"

    /**
     * Hands a checked share ([ShareIntake]) from ShareActivity to MainActivity. The files travel
     * as ClipData with a read grant, so MainActivity holds the grant once ShareActivity is gone.
     * NEW_TASK, since ShareActivity runs in the sending app's task: the app opens in its own.
     */
    fun share(context: Context, request: ShareRequest): Intent {
        val content = request.content
        val intent =
            alias(context)
                .setAction(ACTION_SHARE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SHARE_IMAGES, content.files.map { it.image }.toBooleanArray())
        content.text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        content.files.firstOrNull()?.let { first ->
            val clip = ClipData.newRawUri("", first.uri)
            content.files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when (val target = request.target) {
            is ShareTarget.Session ->
                intent
                    .putExtra(EXTRA_SHARE_TARGET, "session")
                    .putExtra(EXTRA_SHARE_ROUTE, target.routeId)
                    .putExtra(EXTRA_SHARE_PROJECT, target.projectId)
                    .putExtra(EXTRA_SHARE_SESSION, target.sessionId)
            is ShareTarget.NewSession ->
                intent
                    .putExtra(EXTRA_SHARE_TARGET, "new")
                    .putExtra(EXTRA_SHARE_ROUTE, target.routeId)
                    .putExtra(EXTRA_SHARE_PROJECT, target.projectId)
            ShareTarget.Pick -> intent.putExtra(EXTRA_SHARE_TARGET, "pick")
        }
        return intent
    }

    /**
     * The share a launch of MainActivity hands over, or null. Only [share]'s intent on the alias
     * counts; other apps reach PocketPi's share sheet through ShareActivity, which checks what
     * they send. The values are checked again all the same.
     */
    fun share(intent: Intent): ShareRequest? {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        if (intent.component?.className != ALIAS || intent.action != ACTION_SHARE) return null
        val images = intent.getBooleanArrayExtra(EXTRA_SHARE_IMAGES) ?: return null
        val clip = intent.clipData
        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        if (uris.size != images.size || uris.size > ShareIntake.MAX_FILES || uris.any { it.scheme != "content" }) return null
        val text =
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() && it.toByteArray().size <= ShareIntake.MAX_TEXT_BYTES }
        val content = SharedContent(text, uris.mapIndexed { index, uri -> SharedFile(uri, images[index]) })
        if (content.empty) return null
        // Nullable on purpose: the strict push-id check for routes and sessions, the looser one for projects.
        val route = intent.getStringExtra(EXTRA_SHARE_ROUTE).takeIf(::isOpaqueId)
        val project = intent.getStringExtra(EXTRA_SHARE_PROJECT)?.takeIf { isOpaqueId(it) }
        val target =
            when (intent.getStringExtra(EXTRA_SHARE_TARGET)) {
                "session" ->
                    ShareTarget.Session(
                        route ?: return null,
                        project,
                        intent.getStringExtra(EXTRA_SHARE_SESSION).takeIf(::isOpaqueId) ?: return null,
                    )
                "new" -> ShareTarget.NewSession(route ?: return null, project ?: return null)
                "pick" -> ShareTarget.Pick
                else -> return null
            }
        return ShareRequest(content, target)
    }

    /** Opens the app without choosing a session. */
    fun app(context: Context): Intent =
        alias(context).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** A notification's tap target for [session]. */
    fun open(context: Context, routeId: String, sessionId: String): PendingIntent =
        pending(context, session(context, routeId, sessionId))

    /** A notification's tap target that opens the app without choosing a session. */
    fun openApp(context: Context): PendingIntent = pending(context, app(context))

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
