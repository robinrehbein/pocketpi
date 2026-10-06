package de.joinnoah.pi.remote

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Names what finished. A completion, subagent or job push carries no text, so the notification
// posts generic at once; an expedited worker then asks the Mac for the session's title and project
// over the encrypted channel and replaces the notification in place, silently. Like a question
// upgrade it never brings back a dismissed notification, and on a timeout or any error the
// generic notification simply stays. Nothing it reads is logged.

/** The whole lookup is bounded by this, a little above a question upgrade. */
internal const val LABEL_TIMEOUT_MILLIS = 8_500L

/** The preview is cut to this many characters. */
internal const val PREVIEW_MAX_CHARS = 120

internal sealed interface LabelResult {
    data class Post(val label: SessionLabel) : LabelResult

    /** Timed out, failed, or the host knows nothing worth showing: the generic notice stays. */
    data object Unavailable : LabelResult

    /** The notification is gone, push was turned off, the host was unpaired or the chat is open. */
    data object NotWanted : LabelResult
}

internal const val NAME_CACHE_MILLIS = 10 * 60_000L

/**
 * Titles and projects read recently, in memory only, so a burst of pushes for one session walks the
 * projects once. Titles can change; ten minutes of staleness is fine for a notification.
 */
internal class SessionNameCache(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 256,
) {
    private class Entry(val name: SessionName, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(routeId: String, sessionId: String): SessionName? {
        val key = "$routeId\u0000$sessionId"
        val entry = entries[key] ?: return null
        if (now() - entry.at !in 0 until NAME_CACHE_MILLIS) {
            entries.remove(key)
            return null
        }
        return entry.name
    }

    fun put(routeId: String, sessionId: String, name: SessionName) {
        if (entries.size >= maxEntries) entries.clear()
        entries["$routeId\u0000$sessionId"] = Entry(name, now())
    }
}

/**
 * One label lookup per route at a time: a burst of finishing subagents would otherwise open a
 * connection each. Waiters queue inside their own timeout, so they give up (generic notice) or
 * find the cache filled. Question upgrades never use this and are never delayed by it.
 */
internal class LabelGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withRoute(routeId: String, block: suspend () -> T): T =
        locks.getOrPut(routeId) { Mutex() }.withLock { block() }
}

/** The upgrade logic without Android: an injected fetch. */
internal class LabelUpgrader(
    private val timeoutMillis: Long = LABEL_TIMEOUT_MILLIS,
    private val cache: SessionNameCache = SessionNameCache(),
    private val gate: LabelGate = LabelGate(),
    /** Host, session, whether to read the preview, and the cached name if there is one. */
    private val fetch: suspend (PairedHost, String, Boolean, SessionName?) -> SessionLabel,
) {
    suspend fun run(host: PairedHost, payload: PushPayload, wanted: () -> Boolean): LabelResult {
        val label =
            withTimeoutOrNull(timeoutMillis) {
                try {
                    gate.withRoute(payload.routeId) {
                        val known = cache.get(payload.routeId, payload.sessionId)
                        fetch(host, payload.sessionId, payload.event == PushEvent.COMPLETE, known)
                            .also {
                                if (known == null && (it.title != null || it.project != null))
                                    cache.put(
                                        payload.routeId,
                                        payload.sessionId,
                                        SessionName(it.title, it.project),
                                    )
                            }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        if (label == null || (label.title == null && label.project == null && label.preview == null))
            return LabelResult.Unavailable
        // A subagent or job notice already says what happened; only the session's title adds to it.
        if (payload.event != PushEvent.COMPLETE && label.title == null) return LabelResult.Unavailable
        if (!wanted()) return LabelResult.NotWanted
        return LabelResult.Post(label)
    }
}

/**
 * The end of the last assistant answer after the last user message, as one short line, or null.
 * Only text parts count; tool calls and thinking never reach a notification.
 */
internal fun lastAssistantPreview(messages: List<JsonObject>): String? {
    for (message in messages.asReversed()) {
        when (message.optionalText("role")) {
            "user" -> return null
            "assistant" -> {
                val text =
                    ((message["parts"] as? JsonArray).orEmpty())
                        .mapNotNull { it as? JsonObject }
                        .filter { it.optionalText("type") == "text" }
                        .joinToString(" ") { it.optionalText("text").orEmpty() }
                        .replace(Regex("\\s+"), " ")
                        .trim()
                if (text.isNotEmpty()) return shortened(text)
            }
        }
    }
    return null
}

/** The END of the answer, which is where its result is: a leading ellipsis, [PREVIEW_MAX_CHARS] at most. */
private fun shortened(text: String): String {
    if (text.length <= PREVIEW_MAX_CHARS) return text
    var start = text.length - (PREVIEW_MAX_CHARS - 1)
    // Never cut a surrogate pair in half.
    if (Character.isLowSurrogate(text[start])) start++
    return "\u2026" + text.substring(start).trimStart()
}

internal object CompletionUpgrades {
    internal val names = SessionNameCache()
    internal val gate = LabelGate()
    private val keys = listOf("routeId", "target", "eventId", "event", "parent", "jobId", "count")

    fun workName(payload: PushPayload) =
        "label:${RemoteNotifications.tag(payload.routeId, payload.sessionId)}:" +
            RemoteNotifications.idFor(payload.event)

    fun schedule(context: Context, payload: PushPayload) {
        val data =
            mapOf(
                "routeId" to payload.routeId,
                "target" to payload.sessionId,
                "eventId" to payload.eventId,
                "event" to payload.event.wire,
                "parent" to payload.parent,
                "jobId" to payload.jobId,
                "count" to payload.count.toString(),
            )
        val request =
            OneTimeWorkRequestBuilder<LabelUpgradeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(QuestionUpgrades.routeTag(payload.routeId))
                .setInputData(workDataOf(*data.map { (k, v) -> k to v }.toTypedArray()))
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(payload), ExistingWorkPolicy.REPLACE, request)
    }

    /** A question replaced the completion notice, which is no longer the one to name. */
    fun cancelCompletion(context: Context, routeId: String, sessionId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(
                "label:${RemoteNotifications.tag(routeId, sessionId)}:" +
                    RemoteNotifications.idFor(PushEvent.COMPLETE)
            )
    }

    internal fun payload(data: androidx.work.Data): PushPayload? =
        parsePushPayload(keys.mapNotNull { key -> data.getString(key)?.let { key to it } }.toMap())
}

class LabelUpgradeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val payload = CompletionUpgrades.payload(inputData) ?: return Result.success()
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
        val wanted = {
            app.settings.state.value.pushEnabled &&
                app.isPaired(payload.routeId) &&
                paired() != null &&
                RemoteNotifications.canPost(context) &&
                RemoteNotifications.isActive(context, tag, RemoteNotifications.idFor(payload.event)) &&
                !app.isShowing(payload.routeId, payload.sessionId)
        }
        val result =
            try {
                LabelUpgrader(cache = CompletionUpgrades.names, gate = CompletionUpgrades.gate) {
                        pairedHost, sessionId, withPreview, known ->
                        // A known name with no preview to read needs no connection at all.
                        if (known != null && !withPreview) SessionLabel(known.title, known.project, null)
                        else
                            BackgroundRemoteClient().use(pairedHost) {
                                it.sessionLabel(sessionId, withPreview, known)
                            }
                    }
                    .run(host, payload, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LabelResult.Unavailable
            }
        // Checked again right before posting: the lookup may have outlived the notification, and
        // a question or newer push may have replaced it in the meantime.
        if (result is LabelResult.Post && wanted()) {
            if (payload.event == PushEvent.COMPLETE)
                RemoteNotifications.postComplete(context, payload, result.label)
            else RemoteNotifications.postAttention(context, payload, result.label)
        }
        // The generic notification already offers Open, so a failed lookup is not retried.
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
                        .getString(R.string.remote_notification_checking_session)
                )
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    private companion object {
        const val FOREGROUND_ID = 7_402
    }
}
