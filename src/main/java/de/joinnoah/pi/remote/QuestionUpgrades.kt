package de.joinnoah.pi.remote

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

// Turns the generic question notification into the real question. Every push posts the generic
// notification at once; an expedited worker then reads the pending question over the encrypted
// channel and replaces the notification in place (same tag, without alerting again).
//
// - One unique work per session: a newer push replaces the pending work, a completion cancels it.
// - A result is posted only while its push is still the session's latest event, and only if the
//   generic notification is still showing, push is on, the host is still paired and the session
//   is not open in the app. It never brings back a notification the user dismissed.
// - After a host could not be reached, fetches for it give up much sooner for a while.

/** Durable state shared by pushes and workers, which may run in a later process. */
internal interface UpgradeState {
    fun latestEvent(tag: String): String?

    fun setLatestEvent(tag: String, eventId: String?)

    fun unreachableAt(routeId: String): Long?

    fun setUnreachableAt(routeId: String, at: Long?)
}

/**
 * Records a push as its session's newest event. Returns true when a pending upgrade must be
 * cancelled: a completion means there is no question left to show.
 */
internal fun recordPushEvent(state: UpgradeState, payload: PushPayload): Boolean {
    val tag = RemoteNotifications.tag(payload.routeId, payload.sessionId)
    return if (payload.event == PushEvent.COMPLETE) {
        state.setLatestEvent(tag, null)
        true
    } else {
        state.setLatestEvent(tag, payload.eventId)
        false
    }
}

internal sealed interface UpgradeResult {
    data class Post(val content: QuestionNotification) : UpgradeResult

    /** A newer push for the session arrived, or it completed. */
    data object Superseded : UpgradeResult

    data object Unreachable : UpgradeResult

    /** No pending question left, or none that fits a notification. */
    data object NoQuestion : UpgradeResult

    /** The notification is gone, push was turned off, the host was unpaired or the chat is open. */
    data object NotWanted : UpgradeResult
}

/** The upgrade logic without Android: injected fetch, clock and state. */
internal class QuestionUpgrader(
    private val state: UpgradeState,
    private val backgroundAnswers: Boolean,
    private val now: () -> Long,
    private val fetch: suspend (PairedHost, String) -> List<JsonObject>,
) {
    suspend fun run(host: PairedHost, payload: PushPayload, wanted: () -> Boolean): UpgradeResult {
        val tag = RemoteNotifications.tag(payload.routeId, payload.sessionId)
        if (state.latestEvent(tag) != payload.eventId) return UpgradeResult.Superseded
        val timeout = fetchTimeoutMillis(state.unreachableAt(payload.routeId), now())
        var failed = false
        val pending =
            withTimeoutOrNull(timeout) {
                try {
                    fetch(host, payload.sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RemoteCommandException) {
                    // The host answered, so it is reachable; the session is gone or offline.
                    emptyList()
                } catch (_: Exception) {
                    failed = true
                    null
                }
            }
        if (pending == null || failed) {
            state.setUnreachableAt(payload.routeId, now())
            return UpgradeResult.Unreachable
        }
        state.setUnreachableAt(payload.routeId, null)
        val content =
            notifiedQuestion(pending)?.let { questionNotification(it, backgroundAnswers) }
                ?: return UpgradeResult.NoQuestion
        if (state.latestEvent(tag) != payload.eventId) return UpgradeResult.Superseded
        if (!wanted()) return UpgradeResult.NotWanted
        return UpgradeResult.Post(content)
    }
}

internal class PreferenceUpgradeState(context: Context) : UpgradeState {
    private val preferences =
        context.getSharedPreferences("question_upgrades", Context.MODE_PRIVATE)

    override fun latestEvent(tag: String): String? = preferences.getString("event:$tag", null)

    override fun setLatestEvent(tag: String, eventId: String?) =
        preferences.edit {
            if (eventId == null) remove("event:$tag") else putString("event:$tag", eventId)
        }

    override fun unreachableAt(routeId: String): Long? =
        preferences.getLong("unreachable:$routeId", -1L).takeIf { it >= 0 }

    override fun setUnreachableAt(routeId: String, at: Long?) =
        preferences.edit {
            if (at == null) remove("unreachable:$routeId") else putLong("unreachable:$routeId", at)
        }

    fun forgetRoute(routeId: String) =
        preferences.edit {
            preferences.all.keys
                .filter { it.startsWith("event:pi:$routeId:") || it == "unreachable:$routeId" }
                .forEach { remove(it) }
        }
}

internal object QuestionUpgrades {
    private const val KEY_ROUTE = "routeId"
    private const val KEY_SESSION = "sessionId"
    private const val KEY_EVENT = "eventId"

    private fun routeTag(routeId: String) = "route:$routeId"

    /** Records the newest event of a session; a completion cancels a pending upgrade. */
    fun onEvent(context: Context, payload: PushPayload) {
        if (recordPushEvent(PreferenceUpgradeState(context), payload)) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(RemoteNotifications.tag(payload.routeId, payload.sessionId))
        }
    }

    fun schedule(context: Context, payload: PushPayload) {
        val tag = RemoteNotifications.tag(payload.routeId, payload.sessionId)
        val request =
            OneTimeWorkRequestBuilder<QuestionUpgradeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag(routeTag(payload.routeId))
                .setInputData(
                    workDataOf(
                        KEY_ROUTE to payload.routeId,
                        KEY_SESSION to payload.sessionId,
                        KEY_EVENT to payload.eventId,
                    )
                )
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)
    }

    fun forgetRoute(context: Context, routeId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(routeTag(routeId))
        PreferenceUpgradeState(context).forgetRoute(routeId)
    }

    internal fun payload(data: androidx.work.Data): PushPayload? {
        val route = data.getString(KEY_ROUTE)
        val session = data.getString(KEY_SESSION)
        val event = data.getString(KEY_EVENT)
        if (!isOpaqueId(route) || !isOpaqueId(session) || !isOpaqueId(event)) return null
        return PushPayload(route!!, session!!, event!!, PushEvent.QUESTION)
    }
}

class QuestionUpgradeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val payload = QuestionUpgrades.payload(inputData) ?: return Result.success()
        val context = applicationContext
        val app = context as? RemoteApplication ?: return Result.success()
        val paired = {
            try {
                PairingStore(context).load().find { it.routeId == payload.routeId }
            } catch (_: Exception) {
                null
            }
        }
        val host = paired() ?: return Result.success()
        val tag = RemoteNotifications.tag(payload.routeId, payload.sessionId)
        val upgrader =
            QuestionUpgrader(
                PreferenceUpgradeState(context),
                backgroundAnswers = Build.VERSION.SDK_INT >= 31,
                now = System::currentTimeMillis,
            ) { pairedHost, sessionId ->
                BackgroundRemoteClient().use(pairedHost) { it.pendingQuestions(sessionId) }
            }
        val result =
            try {
                upgrader.run(host, payload) {
                    app.settings.state.value.pushEnabled &&
                        app.isPaired(payload.routeId) &&
                        paired() != null &&
                        RemoteNotifications.canPost(context) &&
                        RemoteNotifications.isActive(context, tag) &&
                        !app.isShowing(payload.routeId, payload.sessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpgradeResult.Unreachable
            }
        if (result is UpgradeResult.Post) {
            RemoteNotifications.postQuestion(context, payload, result.content, alert = false)
        }
        // The generic notification already offers Open, so a failed upgrade is not retried.
        return Result.success()
    }

    /** Only used below Android 12, where expedited work runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        RemoteNotifications.createChannel(context)
        val notification =
            NotificationCompat.Builder(context, RemoteNotifications.SYNC_CHANNEL)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle(
                    RemoteNotifications.localized(context)
                        .getString(R.string.remote_notification_checking)
                )
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    private companion object {
        const val FOREGROUND_ID = 7_401
    }
}
