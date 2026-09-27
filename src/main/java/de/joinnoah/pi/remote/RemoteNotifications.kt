package de.joinnoah.pi.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * Builds and posts PocketPi's notifications: one per session, updated in place under a per-session
 * tag. Newer events for a session replace older ones, so no group is needed.
 */
internal object RemoteNotifications {
    const val CHANNEL = "pi_remote"
    const val SYNC_CHANNEL = "pi_remote_sync"
    const val REPLY_KEY = "reply"
    const val ACTION_ANSWER = "de.joinnoah.pi.remote.action.ANSWER"
    const val EXTRA_ROUTE = "routeId"
    const val EXTRA_TARGET = "target"
    const val EXTRA_QUESTION = "questionId"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_IS_REPLY = "isReply"
    private const val NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val localized = localized(context)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        localized.getString(R.string.remote_notification_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply {
                        description = localized.getString(R.string.remote_notification_channel_help)
                    }
            )
        // Only Android 8 to 11 show it, briefly, while a question is read in the background.
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    SYNC_CHANNEL,
                    localized.getString(R.string.remote_notification_sync_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun tag(routeId: String, sessionId: String) = "pi:$routeId:$sessionId"

    /** Opens exactly this session, also from a cold start and from another project or host. */
    fun openIntent(context: Context, routeId: String, sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            sessionIntent(context, routeId, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun postComplete(context: Context, payload: PushPayload) {
        val localized = localized(context)
        post(
            context,
            payload.routeId,
            payload.sessionId,
            base(context, payload.routeId, payload.sessionId)
                .setContentTitle(localized.getString(R.string.remote_notification_complete))
                .setContentText(localized.getString(R.string.remote_notification_open))
                .setPublicVersion(publicVersion(context, R.string.remote_notification_complete))
                .build(),
        )
    }

    /** The question notification without its content, used when the question can't be read. */
    fun postGenericQuestion(context: Context, payload: PushPayload) {
        val localized = localized(context)
        post(
            context,
            payload.routeId,
            payload.sessionId,
            base(context, payload.routeId, payload.sessionId)
                .setContentTitle(localized.getString(R.string.remote_notification_question))
                .setContentText(localized.getString(R.string.remote_notification_open))
                .setPublicVersion(publicVersion(context, R.string.remote_notification_question))
                .build(),
        )
    }

    /**
     * Posts the question. [alert] is false when this upgrades the generic notification that was
     * already shown for the same push, so the phone doesn't buzz twice.
     */
    fun postQuestion(
        context: Context,
        payload: PushPayload,
        question: QuestionNotification,
        alert: Boolean = true,
    ) {
        val localized = localized(context)
        val title =
            question.title
                ?: localized.getString(
                    when (question.kind) {
                        "plan" -> R.string.remote_notification_plan
                        "questionnaire" -> R.string.remote_notification_questionnaire
                        else -> R.string.remote_notification_question
                    }
                )
        val body =
            question.body
                ?: localized.getString(
                    if (question.kind == "local_required")
                        R.string.remote_notification_local_required
                    else R.string.remote_notification_open
                )
        val builder =
            base(context, payload.routeId, payload.sessionId, alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPublicVersion(publicVersion(context, R.string.remote_notification_question))
        question.actions.take(3).forEachIndexed { index, action ->
            builder.addAction(action(context, localized, payload, question, index, action))
        }
        post(context, payload.routeId, payload.sessionId, builder.build())
    }

    fun postSending(context: Context, routeId: String, sessionId: String) {
        post(
            context,
            routeId,
            sessionId,
            base(context, routeId, sessionId, alert = false)
                .setContentTitle(localized(context).getString(R.string.remote_notification_sending))
                .setProgress(0, 0, true)
                .build(),
        )
    }

    /** [failedReply] keeps an inline reply that failed, so it can be sent again from here. */
    fun postOutcome(
        context: Context,
        routeId: String,
        sessionId: String,
        outcome: AnswerOutcome,
        failedReply: FailedReply? = null,
    ) {
        val localized = localized(context)
        val builder = base(context, routeId, sessionId, alert = false)
        when (outcome) {
            AnswerOutcome.SENT ->
                builder
                    .setContentTitle(localized.getString(R.string.remote_notification_sent))
                    .setTimeoutAfter(8_000)
            AnswerOutcome.ALREADY_RESOLVED ->
                builder
                    .setContentTitle(localized.getString(R.string.remote_notification_question))
                    .setContentText(
                        localized.getString(R.string.remote_notification_already_answered)
                    )
            AnswerOutcome.FAILED ->
                builder
                    .setContentTitle(localized.getString(R.string.remote_notification_question))
                    .setContentText(localized.getString(R.string.remote_notification_failed))
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(localized.getString(R.string.remote_notification_failed))
                    )
                    .addAction(
                        0,
                        localized.getString(R.string.remote_notification_open_action),
                        openIntent(context, routeId, sessionId),
                    )
                    .apply {
                        if (failedReply != null && Build.VERSION.SDK_INT >= 31) {
                            addAction(
                                replyAction(context, localized, routeId, sessionId, failedReply.questionId, 0)
                            )
                            setRemoteInputHistory(arrayOf<CharSequence>(failedReply.text))
                        }
                    }
        }
        post(context, routeId, sessionId, builder.build())
    }

    fun postEmptyReply(context: Context, routeId: String, sessionId: String) {
        val localized = localized(context)
        post(
            context,
            routeId,
            sessionId,
            base(context, routeId, sessionId, alert = false)
                .setContentTitle(localized.getString(R.string.remote_notification_question))
                .setContentText(localized.getString(R.string.remote_notification_empty_reply))
                .addAction(
                    0,
                    localized.getString(R.string.remote_notification_open_action),
                    openIntent(context, routeId, sessionId),
                )
                .build(),
        )
    }

    /** True while the session's notification is still showing (not dismissed or opened). */
    fun isActive(context: Context, tag: String): Boolean =
        try {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.tag == tag && it.id == NOTIFICATION_ID
            }
        } catch (_: Exception) {
            false
        }

    /** Removes every notification of an unpaired host. */
    fun cancelRoute(context: Context, routeId: String) {
        val prefix = "pi:$routeId:"
        try {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith(prefix) == true }
                .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
        } catch (_: Exception) {}
    }

    fun cancel(context: Context, routeId: String, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(tag(routeId, sessionId), NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        routeId: String,
        sessionId: String,
        notification: android.app.Notification,
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        try {
            NotificationManagerCompat.from(context)
                .notify(tag(routeId, sessionId), NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun base(
        context: Context,
        routeId: String,
        sessionId: String,
        alert: Boolean = true,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentIntent(openIntent(context, routeId, sessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)

    /** What a locked screen shows: never the question itself, and no actions. */
    private fun publicVersion(context: Context, title: Int) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentTitle(localized(context).getString(title))
            .setContentText(localized(context).getString(R.string.remote_notification_open))
            .build()

    private fun action(
        context: Context,
        localized: Context,
        payload: PushPayload,
        question: QuestionNotification,
        index: Int,
        action: NotificationAction,
    ): NotificationCompat.Action =
        when (action) {
            NotificationAction.Open,
            NotificationAction.AnswerInApp ->
                NotificationCompat.Action.Builder(
                        0,
                        localized.getString(
                            if (action == NotificationAction.Open)
                                R.string.remote_notification_open_action
                            else R.string.remote_notification_answer
                        ),
                        openIntent(context, payload.routeId, payload.sessionId),
                    )
                    .build()
            is NotificationAction.Answer ->
                NotificationCompat.Action.Builder(
                        0,
                        when (val label = action.label) {
                            is ActionLabel.Resource -> localized.getString(label.id)
                            is ActionLabel.Text -> label.text
                        },
                        answerIntent(
                            context,
                            payload.routeId,
                            payload.sessionId,
                            question.questionId,
                            index,
                            action.answer,
                        ),
                    )
                    .setAuthenticationRequired(true)
                    .setShowsUserInterface(false)
                    .build()
            NotificationAction.Reply ->
                replyAction(
                    context,
                    localized,
                    payload.routeId,
                    payload.sessionId,
                    question.questionId,
                    index,
                )
        }

    private fun replyAction(
        context: Context,
        localized: Context,
        routeId: String,
        sessionId: String,
        questionId: String,
        index: Int,
    ): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
                0,
                localized.getString(R.string.remote_notification_reply),
                answerIntent(context, routeId, sessionId, questionId, index, null),
            )
            .addRemoteInput(
                RemoteInput.Builder(REPLY_KEY)
                    .setLabel(localized.getString(R.string.remote_notification_reply_hint))
                    .build()
            )
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()

    private fun answerIntent(
        context: Context,
        routeId: String,
        sessionId: String,
        questionId: String,
        index: Int,
        answer: JsonObject?,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_ANSWER)
                // The data URI keeps each action's PendingIntent distinct.
                .setData(
                    Uri.Builder()
                        .scheme("pocketpi")
                        .authority("answer")
                        .appendPath(routeId)
                        .appendPath(sessionId)
                        .appendPath(questionId)
                        .appendPath(index.toString())
                        .build()
                )
                .putExtra(EXTRA_ROUTE, routeId)
                .putExtra(EXTRA_TARGET, sessionId)
                .putExtra(EXTRA_QUESTION, questionId)
        if (answer != null) intent.putExtra(EXTRA_ANSWER, answer.toString())
        else intent.putExtra(EXTRA_IS_REPLY, true)
        // RemoteInput must be able to add the typed text, so only the reply is mutable (before
        // Android 12 that is the default). The intent is explicit and fill-in never overrides the
        // extras set here, so the typed text is the only thing a sender can supply.
        val mutability =
            when {
                answer != null -> PendingIntent.FLAG_IMMUTABLE
                Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
                else -> 0
            }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    fun localized(context: Context): Context =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.getDefault()) }
        )
}

internal data class FailedReply(val questionId: String, val text: String)

/**
 * An explicit intent that opens one session; shared by notifications, the widget and shortcuts.
 * It reuses the running activity instead of stacking a second one on top.
 */
internal fun sessionIntent(context: Context, routeId: String, sessionId: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

/** Opens the app without choosing a session; reuses the running activity. */
internal fun appIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
