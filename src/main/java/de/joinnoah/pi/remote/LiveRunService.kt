package de.joinnoah.pi.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * Keeps a running session live while PocketPi is in the background. Android freezes a cached
 * process, so a minimized DeX window or a trip to another app used to kill the socket mid-run: the
 * app reloaded on return and showed nothing in between but the final push.
 *
 * MainActivity asks for LiveRunService in onPause, while the app still counts as in front: a
 * foreground service started from onStop, or from ProcessLifecycleOwner's later onStop, can be
 * refused on Android 12+. The service keeps the process, and with it the repository's existing
 * connection, alive and shows an ongoing notification with the session, its current tool and the
 * elapsed time. It ends when the run does (idle, another session, a lost connection), after
 * LIVE_RUN_CAP_MINUTES, when the user taps Stop or swipes the notification away, or when the app
 * comes back to the front. A socket it kept delivering frames takes the cheap resume tier on
 * return (DefaultRemoteRepository.resumeTier).
 */

/** The longest a run is kept live in the background, for the battery's sake. */
internal const val LIVE_RUN_CAP_MINUTES = 30

/** A selected session's status that means pi is still at work. */
private val LIVE_STATUSES = setOf("running", "waiting")

/** The session a live run follows. */
internal data class LiveRunTarget(val routeId: String, val sessionId: String)

/**
 * The selected session, when it is worth keeping live: loaded, on a live connection and running
 * or waiting for an answer.
 */
internal fun liveRunTarget(state: RemoteState): LiveRunTarget? {
    val routeId = state.selection.routeId ?: return null
    val sessionId = state.selection.sessionId ?: return null
    if (state.session == null || state.preview || state.loading || !state.connected) return null
    if (state.status !in LIVE_STATUSES) return null
    return LiveRunTarget(routeId, sessionId)
}

/**
 * Whether the run of [target] is still live in [state]. A lost connection ends it: in the
 * background the repository does not reconnect, so nothing would arrive any more. While a load
 * runs, the status is the old one and keeps it going.
 */
internal fun liveRunContinues(state: RemoteState, target: LiveRunTarget): Boolean =
    state.selection.routeId == target.routeId &&
        state.selection.sessionId == target.sessionId &&
        state.connected &&
        (state.loading || state.status in LIVE_STATUSES)

/** The tool pi runs right now: a readable [label] where [toolSummary] has one, else the raw [name]. */
internal data class LiveRunTool(val name: String, val label: Int?, val detail: String?)

/** What the live-run notification shows; [startedAt] is wall-clock millis. */
internal data class LiveRunContent(
    val target: LiveRunTarget?,
    val title: String?,
    val project: String?,
    val waiting: Boolean,
    val tool: LiveRunTool?,
    val startedAt: Long,
)

internal fun liveRunContent(
    state: RemoteState,
    target: LiveRunTarget?,
    fallbackStart: Long,
    now: Long = System.currentTimeMillis(),
): LiveRunContent {
    val waiting = state.status == "waiting" || state.questions.isNotEmpty()
    return LiveRunContent(
        target = target,
        title = state.session?.safeText("title")?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TEXT),
        project = state.project?.safeText("name")?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TEXT),
        waiting = waiting,
        tool = if (waiting) null else currentTool(state.messages),
        startedAt = runStartedAt(state.messages, fallbackStart, now),
    )
}

/**
 * The tool call of the latest reply that has no finished result yet, or null while pi thinks or
 * writes (the reply's last part is text) or before it replied to the latest prompt.
 */
internal fun currentTool(messages: List<JsonObject>): LiveRunTool? {
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        when (message.safeText("role")) {
            "user" -> return null
            "assistant" -> {
                val parts = (message["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val last =
                    parts.lastOrNull {
                        it.safeText("type") == "toolCall" || !it.safeText("text").isNullOrBlank()
                    } ?: return null
                if (last.safeText("type") != "toolCall") return null
                val later = messages.subList(index + 1, messages.size)
                val open =
                    parts.lastOrNull { part ->
                        val id = part.safeText("id")
                        part.safeText("type") == "toolCall" &&
                            later.none { it.safeText("toolCallId") == id && it.safeText("state") !in OPEN_TOOL_STATES }
                    } ?: return null
                val name = open.safeText("name")?.takeIf(String::isNotBlank) ?: return null
                val summary = toolSummary(name, open.safeText("arguments"))
                return LiveRunTool(name.take(MAX_TEXT), summary.labelRes, summary.detail?.take(MAX_TEXT))
            }
        }
    }
    return null
}

/** A tool result in one of these states still runs; one without a state is complete. */
private val OPEN_TOOL_STATES = setOf("streaming", "running")

/**
 * When the run started: the latest prompt's time, or [fallback] when it has none or one that
 * makes no sense here (later than [now], from a Mac clock that runs ahead, or more than a day
 * ago). The result never depends on [now] otherwise, so the notification's content stays the same
 * between calls and the chronometer does not jump.
 */
internal fun runStartedAt(messages: List<JsonObject>, fallback: Long, now: Long): Long {
    val prompt = messages.lastOrNull { it.safeText("role") == "user" } ?: return fallback
    val timestamp =
        (prompt["timestamp"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return fallback
    return timestamp.takeIf { it in (now - DAY_MILLIS)..now } ?: fallback
}

private fun JsonObject.safeText(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val MAX_TEXT = 200
private const val DAY_MILLIS = 24 * 60 * 60_000L

/** Why the service ended, for tests and diagnostics. */
internal enum class LiveRunStop {
    /** The app came back to the front, or never left. */
    FOREGROUND,
    /** The run ended, the selection moved on or the connection was lost. */
    IDLE,
    CAP,
    /** Stop in the notification. */
    STOPPED,
    /** The notification was swiped away. */
    DISMISSED,
    /** Android refused the foreground service. */
    REFUSED,
}

/** The app's side of [LiveRunService]. Main thread only, like the activity and service callbacks. */
internal object LiveRun {
    /** The app asked for the service at its last pause and has not come back to the front since. */
    var wanted = false
        private set

    /** The running service, if any. */
    internal var service: LiveRunService? = null

    /** The state the service follows; a test replaces it. */
    internal var states: (Context) -> StateFlow<RemoteState> = {
        (it.applicationContext as RemoteApplication).repository.state
    }

    /** Why the last service ended; null until one did. */
    internal var lastStop: LiveRunStop? = null

    /**
     * The activity pauses: starts the service when the selected session runs and the setting is
     * on. Returns whether it asked for it.
     */
    fun onPause(context: Context): Boolean {
        if (!LiveRunSettings(context).enabled.value || liveRunTarget(states(context).value) == null) return false
        wanted = true
        return try {
            ContextCompat.startForegroundService(context, Intent(context, LiveRunService::class.java))
            true
        } catch (_: IllegalStateException) {
            // Refused (ForegroundServiceStartNotAllowedException and its relatives): the app is
            // no longer in front after all.
            wanted = false
            false
        } catch (_: SecurityException) {
            wanted = false
            false
        }
    }

    /** The activity is in front again: the service is not needed, also one still on its way. */
    fun onResume() {
        wanted = false
        service?.finish(LiveRunStop.FOREGROUND)
    }

    /** Forgets everything; tests only. */
    internal fun reset() {
        wanted = false
        service = null
        lastStop = null
    }
}

/** The foreground service of a live run; see the comment at the top of this file. */
class LiveRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var target: LiveRunTarget? = null
    private var startedAt = 0L
    private var shown: LiveRunContent? = null
    private var actions = false
    private var run: Job? = null
    private var latestStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveRun.service = this
    }

    override fun onDestroy() {
        scope.cancel()
        if (LiveRun.service === this) LiveRun.service = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        when (intent?.action) {
            ACTION_STOP -> finish(LiveRunStop.STOPPED)
            ACTION_DISMISSED -> finish(LiveRunStop.DISMISSED)
            else -> start()
        }
        // A process killed meanwhile has lost the connection; there is nothing to restart for.
        return START_NOT_STICKY
    }

    private fun start() {
        val state = LiveRun.states(this).value
        val current = target ?: liveRunTarget(state)
        val now = System.currentTimeMillis()
        val content = liveRunContent(state, current, if (target != null) startedAt else now, now)
        // Every start through startForegroundService has to reach startForeground, also one the
        // app no longer wants: stopping a service before that crashes the app.
        if (!enterForeground(content)) return finish(LiveRunStop.REFUSED)
        if (!LiveRun.wanted) return finish(LiveRunStop.FOREGROUND)
        if (current == null) return finish(LiveRunStop.IDLE)
        if (target != null) return
        target = current
        startedAt = now
        shown = content
        run =
            scope.launch {
                launch {
                    delay(LIVE_RUN_CAP_MINUTES * 60_000L)
                    finish(LiveRunStop.CAP)
                }
                launch {
                    delay(ACTIONS_DELAY_MILLIS)
                    actions = true
                    shown?.let(::show)
                }
                LiveRun.states(this@LiveRunService).collect(::update)
            }
    }

    private fun update(state: RemoteState) {
        val target = target ?: return
        if (!liveRunContinues(state, target)) return finish(LiveRunStop.IDLE)
        val content = liveRunContent(state, target, startedAt)
        // Streamed text changes the state many times a second; the notification only when what it
        // shows does, or Android drops the updates of an app that posts too often.
        if (content == shown) return
        shown = content
        // Until Stop is added, the first notification may still be held back; an update now
        // could show it early. The one with Stop shows the latest content.
        if (actions) show(content)
    }

    private fun show(content: LiveRunContent) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(RemoteNotifications.LIVE_RUN_ID, RemoteNotifications.liveRun(this, content, actions))
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    // The phone's manifest gives the service its type; the wear app compiles this code but never
    // declares or starts the service.
    @SuppressLint("ForegroundServiceType")
    private fun enterForeground(content: LiveRunContent): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                RemoteNotifications.LIVE_RUN_ID,
                RemoteNotifications.liveRun(this, content, actions),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app had left the front by now.
            false
        } catch (_: SecurityException) {
            false
        }

    /**
     * Removes the notification and stops, unless a newer start is on its way: that one has to
     * reach startForeground first, and decides again then.
     */
    internal fun finish(reason: LiveRunStop) {
        run?.cancel()
        run = null
        target = null
        shown = null
        actions = false
        LiveRun.lastStop = reason
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(latestStart)
    }

    companion object {
        const val ACTION_STOP = "de.joinnoah.pi.remote.action.LIVE_RUN_STOP"
        const val ACTION_DISMISSED = "de.joinnoah.pi.remote.action.LIVE_RUN_DISMISSED"

        /** How long the notification shows without Stop; see [RemoteNotifications.liveRun]. */
        internal const val ACTIONS_DELAY_MILLIS = 10_000L

        internal fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
                context,
                0,
                Intent(context, LiveRunService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
