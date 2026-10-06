package de.joinnoah.pi.remote

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

internal class PreferenceUpgradeState(
    context: Context,
    /** The routes currently paired, used to prune events of routes no longer paired. */
    private val pairedRoutes: () -> Set<String> = {
        try {
            PairingStore(context).load().map { it.routeId }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    },
    private val now: () -> Long = System::currentTimeMillis,
) : UpgradeState {
    private val preferences =
        context.getSharedPreferences("question_upgrades", Context.MODE_PRIVATE)

    override fun latestEvent(tag: String): String? =
        preferences.getString("event:$tag", null)?.substringAfter(EVENT_STAMP_DELIMITER)

    override fun setLatestEvent(tag: String, eventId: String?) {
        // commit(), not apply(): a completion (eventId == null) races the worker it is meant to
        // cancel, and an async write here could lose that race.
        preferences.edit(commit = true) {
            if (eventId == null) remove("event:$tag")
            else putString("event:$tag", "${now()}$EVENT_STAMP_DELIMITER$eventId")
        }
        if (eventId != null) pruneEvents()
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

    /**
     * Caps `event:` keys to the newest [MAX_EVENT_KEYS], dropping any of a route no longer
     * paired along the way: nothing else removes an entry whose session never sent a completion
     * push, so the set would otherwise grow without bound. [forgetRoute] already drops a route's
     * keys immediately on unpair, so this only needs to act once the count actually runs over —
     * [pairedRoutes] decrypts the pairing store, and every push calling this on every write would
     * otherwise pay that cost for nothing on the common, well-behaved path.
     */
    private fun pruneEvents() {
        val all = preferences.all
        val eventKeys = all.keys.filter { it.startsWith("event:") }
        if (eventKeys.size <= MAX_EVENT_KEYS) return
        val paired = pairedRoutes()
        val entries =
            eventKeys.map { key ->
                val routeId = key.removePrefix("event:pi:").substringBefore(':')
                val at = (all[key] as? String)?.substringBefore(EVENT_STAMP_DELIMITER, "")?.toLongOrNull() ?: 0L
                Triple(key, routeId, at)
            }
        val stale = entries.filter { (_, routeId, _) -> routeId !in paired }
        val overflow =
            entries.filter { (_, routeId, _) -> routeId in paired }
                .sortedByDescending { (_, _, at) -> at }
                .drop(MAX_EVENT_KEYS)
        val toRemove = (stale + overflow).map { it.first }
        if (toRemove.isEmpty()) return
        preferences.edit { toRemove.forEach { remove(it) } }
    }

    private companion object {
        const val EVENT_STAMP_DELIMITER = "\u0001"
        const val MAX_EVENT_KEYS = 200
    }
}

internal object QuestionUpgrades {
    private const val KEY_ROUTE = "routeId"
    private const val KEY_SESSION = "sessionId"
    private const val KEY_EVENT = "eventId"

    internal fun routeTag(routeId: String) = "route:$routeId"

    /** Records the newest event of a session; a completion cancels a pending upgrade. */
    fun onEvent(context: Context, payload: PushPayload) {
        // The question's notice takes the completion's place, so naming the completion is moot.
        if (payload.event == PushEvent.QUESTION)
            CompletionUpgrades.cancelCompletion(context, payload.routeId, payload.sessionId)
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
                // A failed upgrade is never retried (Result.success() always, below) — the generic
                // notification already offers Open — so a backoff policy would never apply.
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
        RemoteNotifications.createSyncChannel(context)
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
