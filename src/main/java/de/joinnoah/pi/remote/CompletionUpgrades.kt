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
import kotlinx.coroutines.withTimeoutOrNull
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

/** The upgrade logic without Android: an injected fetch. */
internal class LabelUpgrader(
    private val timeoutMillis: Long = LABEL_TIMEOUT_MILLIS,
    private val fetch: suspend (PairedHost, String, Boolean) -> SessionLabel,
) {
    suspend fun run(host: PairedHost, payload: PushPayload, wanted: () -> Boolean): LabelResult {
        val label =
            withTimeoutOrNull(timeoutMillis) {
                try {
                    fetch(host, payload.sessionId, payload.event == PushEvent.COMPLETE)
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

private fun shortened(text: String): String {
    if (text.length <= PREVIEW_MAX_CHARS) return text
    var end = PREVIEW_MAX_CHARS - 1
    // Never cut a surrogate pair in half.
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.take(end).trimEnd() + "…"
}

internal object CompletionUpgrades {
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
        val result =
            try {
                LabelUpgrader { pairedHost, sessionId, withPreview ->
                        BackgroundRemoteClient().use(pairedHost) {
                            it.sessionLabel(sessionId, withPreview)
                        }
                    }
                    .run(host, payload) {
                        app.settings.state.value.pushEnabled &&
                            app.isPaired(payload.routeId) &&
                            paired() != null &&
                            RemoteNotifications.canPost(context) &&
                            RemoteNotifications.isActive(
                                context,
                                tag,
                                RemoteNotifications.idFor(payload.event),
                            ) &&
                            !app.isShowing(payload.routeId, payload.sessionId)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LabelResult.Unavailable
            }
        if (result is LabelResult.Post) {
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
