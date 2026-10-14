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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/**
 * Builds and posts PocketPi's notifications: one per session, updated in place under a per-session
 * tag, so newer events for a session replace older ones. Subagent and background job notices use
 * two more ids under the same tag, one for `*.done` and one for `*.stuck`, so a done notice never
 * replaces an unrelated stuck one (or a question).
 *
 * Every notification of a Mac belongs to that Mac's group, whose summary has a tag and id of its
 * own ([summaryTag], [SUMMARY_ID]) and goes away with the group's last notification. A session's
 * completion and question show as a conversation with pi, tied to the session's launcher shortcut
 * ([shortcutId]), which [HomeSurfaces] publishes as long-lived for every notified session.
 */
internal object RemoteNotifications {
    const val QUESTIONS_CHANNEL = "pi_remote_questions"
    const val UPDATES_CHANNEL = "pi_remote_updates"
    /** The one channel of earlier versions; deleted on startup. */
    const val LEGACY_CHANNEL = "pi_remote"
    const val SYNC_CHANNEL = "pi_remote_sync"
    const val REPLY_KEY = "reply"
    const val ACTION_ANSWER = "de.joinnoah.pi.remote.action.ANSWER"
    /** An inline reply to a completion: a new prompt for the idle session. */
    const val ACTION_REPLY = "de.joinnoah.pi.remote.action.REPLY"
    /** The user dismissed one notification of a group, which may leave its summary alone. */
    const val ACTION_DISMISSED = "de.joinnoah.pi.remote.action.DISMISSED"
    const val EXTRA_ROUTE = "routeId"
    const val EXTRA_TARGET = "target"
    const val EXTRA_QUESTION = "questionId"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_IS_REPLY = "isReply"
    const val EXTRA_JOBS = "jobs"
    const val EXTRA_JOB = "jobId"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    private const val NOTIFICATION_ID = 1
    private const val DONE_ID = 2
    private const val STUCK_ID = 3
    /** The group summary of a Mac, under [summaryTag]. */
    internal const val SUMMARY_ID = 4
    private const val AGENT_KEY = "pi"

    /**
     * Mac names by route, for the group summary. Filled by whoever read the pairings anyway (a
     * push, a worker, a notification action), so posting never decrypts them just for a name.
     */
    private val hostNames = ConcurrentHashMap<String, String>()

    fun rememberHost(host: PairedHost) {
        if (host.name.isNotBlank()) hostNames[host.routeId] = host.name.take(64)
    }

    /**
     * Creates the two channels and removes [LEGACY_CHANNEL], the single channel earlier versions
     * used for everything. A channel's importance and sound are the user's settings and can't be
     * migrated, so the old one is deleted instead of being split. Idempotent, so it runs on every
     * startup.
     */
    fun createChannel(context: Context) {
        val localized = localized(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    QUESTIONS_CHANNEL,
                    localized.getString(R.string.remote_notification_channel_questions),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply {
                    description =
                        localized.getString(R.string.remote_notification_channel_questions_help)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                    UPDATES_CHANNEL,
                    localized.getString(R.string.remote_notification_channel_updates),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description =
                        localized.getString(R.string.remote_notification_channel_updates_help)
                }
        )
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
    }

    /** Questions need an answer and alert loudly; everything else is an update. */
    fun channelFor(event: PushEvent): String =
        if (event == PushEvent.QUESTION) QUESTIONS_CHANNEL else UPDATES_CHANNEL

    /**
     * The app ships under a fresh application ID, so a plain install never had the sync channel;
     * this only matters for a dev/sideload upgrade of an older install, or a future OS upgrade of
     * a device that created it below Android 12. Deleting it where it's never (re-)created keeps
     * such an install from carrying a dead channel forever. Cheap and idempotent, so it can just
     * run on every startup.
     */
    fun deleteSyncChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        NotificationManagerCompat.from(context).deleteNotificationChannel(SYNC_CHANNEL)
    }

    /**
     * Only Android 8 to 11 show this, briefly, while a question is read in the background: from
     * Android 12 expedited work no longer needs a foreground service, so the channel would never be
     * shown there.
     */
    fun createSyncChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) return
        val localized = localized(context)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    SYNC_CHANNEL,
                    localized.getString(R.string.remote_notification_sync_channel),
                    // This channel only ever backs the foreground-service notification below
                    // Android 12; on Android 8.0/8.1 (API 26-27) IMPORTANCE_MIN can keep that
                    // notification from showing at all, so LOW is the floor here.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun tag(routeId: String, sessionId: String) = "pi:$routeId:$sessionId"

    /**
     * The group of a Mac's notifications and the tag of its summary. Route IDs never contain a
     * colon, so it can't equal a session's [tag], and the session prefix `pi:<route>:` never
     * matches it.
     */
    fun summaryTag(routeId: String) = "pi:$routeId"

    private fun groupKey(routeId: String) = summaryTag(routeId)

    /**
     * The generic completion notice. With a [label] (read from the Mac afterwards) it names the
     * session instead, replacing the notice in place without alerting again. The lock screen
     * version stays generic either way.
     */
    fun postComplete(context: Context, payload: PushPayload, label: SessionLabel? = null) {
        val localized = localized(context)
        val generic = localized.getString(R.string.remote_notification_complete)
        val title =
            label?.title?.let { localized.getString(R.string.remote_notification_complete_named, it) }
                ?: generic
        val open = localized.getString(R.string.remote_notification_open)
        val message =
            listOfNotNull(label?.project, label?.preview).joinToString("\n").ifEmpty { open }
        val builder =
            base(
                    context,
                    payload.routeId,
                    payload.sessionId,
                    alert = label == null,
                    channel = UPDATES_CHANNEL,
                )
                .setContentTitle(title)
                .setContentText(label?.project ?: label?.preview ?: open)
                .conversation(
                    payload.routeId,
                    payload.sessionId,
                    conversationStyle(context, localized, title, message),
                )
                .setPublicVersion(publicVersion(context, generic, UPDATES_CHANNEL))
        // The session is idle now, so a reply starts its next run. Like an answer it needs an
        // unlocked phone, which an action can only require from Android 12 on.
        if (Build.VERSION.SDK_INT >= 31)
            builder.addAction(completionReplyAction(context, localized, payload.routeId, payload.sessionId))
        post(context, payload.routeId, payload.sessionId, builder.build())
    }

    /**
     * A background subagent or job finished or stalled; the payload carries no text. A [label]
     * adds the session's name below the title and keeps the notice quiet.
     */
    fun postAttention(context: Context, payload: PushPayload, label: SessionLabel? = null) {
        val localized = localized(context)
        val title =
            when (val value = attentionTitle(payload)) {
                is AttentionTitle.Single -> localized.getString(value.id)
                is AttentionTitle.Bundle ->
                    localized.resources.getQuantityString(value.plural, value.count, value.count)
            }
        val target = notificationTarget(payload)
        val named = label?.let { listOfNotNull(it.title, it.project).joinToString(" \u00b7 ") }
        val notification =
            base(
                    context,
                    payload.routeId,
                    payload.sessionId,
                    alert = label == null,
                    channel = UPDATES_CHANNEL,
                )
                .setContentIntent(LaunchIntents.open(context, target))
                .setContentTitle(title)
                .setContentText(
                    named?.takeIf { it.isNotEmpty() }
                        ?: localized.getString(R.string.remote_notification_open)
                )
                // A status update about background work, not a chat message.
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPublicVersion(publicVersion(context, title, UPDATES_CHANNEL))
                .build()
        post(context, payload.routeId, payload.sessionId, notification, attentionId(payload.event))
    }

    /** The id the notice of [event] is posted under for its session. */
    fun idFor(event: PushEvent): Int =
        if (event.attention) attentionId(event) else NOTIFICATION_ID

    /** `*.done` and `*.stuck` post under separate ids, so one never replaces the other. */
    private fun attentionId(event: PushEvent): Int =
        if (event == PushEvent.SUBAGENT_STUCK || event == PushEvent.JOB_STUCK) STUCK_ID else DONE_ID

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
                .conversation(
                    payload.routeId,
                    payload.sessionId,
                    conversationStyle(
                        context,
                        localized,
                        localized.getString(R.string.remote_notification_question),
                        localized.getString(R.string.remote_notification_open),
                    ),
                )
                .setPublicVersion(
                    publicVersion(context, localized.getString(R.string.remote_notification_question))
                )
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
                .conversation(
                    payload.routeId,
                    payload.sessionId,
                    conversationStyle(context, localized, title, body),
                )
                .setPublicVersion(
                    publicVersion(context, localized.getString(R.string.remote_notification_question))
                )
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
                        LaunchIntents.open(context, routeId, sessionId),
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
                    LaunchIntents.open(context, routeId, sessionId),
                )
                .build(),
        )
    }

    /**
     * The conversation the session's notification shows now, to add a reply to; null when it is
     * gone or shows no conversation.
     */
    fun conversation(context: Context, routeId: String, sessionId: String): NotificationCompat.MessagingStyle? =
        try {
            val notificationTag = tag(routeId, sessionId)
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .find { it.tag == notificationTag && it.id == NOTIFICATION_ID }
                ?.let {
                    NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                        it.notification
                    )
                }
        } catch (_: Exception) {
            null
        }

    /**
     * Shows [text] as the user's message below [previous], the conversation the reply was typed
     * into. While [sending], it says so and offers nothing to tap but the notification itself.
     */
    fun postReply(
        context: Context,
        routeId: String,
        sessionId: String,
        previous: NotificationCompat.MessagingStyle?,
        text: String,
        sending: Boolean,
    ) {
        val localized = localized(context)
        val generic = localized.getString(R.string.remote_notification_complete)
        val title = previous?.conversationTitle ?: generic
        val style =
            NotificationCompat.MessagingStyle(user(localized))
                .setConversationTitle(title)
                .setGroupConversation(previous?.isGroupConversation ?: true)
        previous?.messages?.forEach { style.addMessage(it) }
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        val builder =
            base(context, routeId, sessionId, alert = false, channel = UPDATES_CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .conversation(routeId, sessionId, style)
                .setPublicVersion(publicVersion(context, generic, UPDATES_CHANNEL))
        if (sending) builder.setSubText(localized.getString(R.string.remote_notification_reply_sending))
        post(context, routeId, sessionId, builder.build())
    }

    /**
     * The result of a reply. A sent one stays in the conversation; a failed one can be sent again
     * from here; an uncertain one only opens the session, where the user can see whether pi got
     * it, and is never offered for sending again.
     */
    fun postReplyOutcome(
        context: Context,
        routeId: String,
        sessionId: String,
        outcome: ReplyOutcome,
        previous: NotificationCompat.MessagingStyle?,
        text: String,
    ) {
        if (outcome == ReplyOutcome.SENT) {
            postReply(context, routeId, sessionId, previous, text, sending = false)
            return
        }
        val failed = outcome == ReplyOutcome.FAILED
        postReplyProblem(
            context,
            routeId,
            sessionId,
            if (failed) R.string.remote_notification_reply_failed_title
            else R.string.remote_notification_reply_uncertain_title,
            if (failed) R.string.remote_notification_reply_failed
            else R.string.remote_notification_reply_uncertain,
            text,
            canSendAgain = failed,
        )
    }

    fun postEmptyCompletionReply(context: Context, routeId: String, sessionId: String) =
        postReplyProblem(
            context,
            routeId,
            sessionId,
            R.string.remote_notification_reply_failed_title,
            R.string.remote_notification_reply_empty,
            text = null,
            canSendAgain = true,
        )

    private fun postReplyProblem(
        context: Context,
        routeId: String,
        sessionId: String,
        title: Int,
        message: Int,
        text: String?,
        canSendAgain: Boolean,
    ) {
        val localized = localized(context)
        val builder =
            base(context, routeId, sessionId, alert = false, channel = UPDATES_CHANNEL)
                .setContentTitle(localized.getString(title))
                .setContentText(localized.getString(message))
                .setStyle(NotificationCompat.BigTextStyle().bigText(localized.getString(message)))
                .setPublicVersion(publicVersion(context, localized.getString(title), UPDATES_CHANNEL))
        if (canSendAgain && Build.VERSION.SDK_INT >= 31)
            builder.addAction(completionReplyAction(context, localized, routeId, sessionId))
        builder.addAction(
            0,
            localized.getString(R.string.remote_notification_open_action),
            LaunchIntents.open(context, routeId, sessionId),
        )
        // Keeps what was typed in view, so nothing is lost when it has to be written again.
        if (text != null) builder.setRemoteInputHistory(arrayOf<CharSequence>(text))
        post(context, routeId, sessionId, builder.build())
    }

    /** True while the session's notification is still showing (not dismissed or opened). */
    fun isActive(context: Context, tag: String, id: Int = NOTIFICATION_ID): Boolean =
        try {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.tag == tag && it.id == id
            }
        } catch (_: Exception) {
            false
        }

    /** Removes every notification of an unpaired host, its group summary included. */
    fun cancelRoute(context: Context, routeId: String) {
        hostNames.remove(routeId)
        val prefix = "pi:$routeId:"
        try {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith(prefix) == true }
                .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
        } catch (_: Exception) {}
        try {
            NotificationManagerCompat.from(context).cancel(summaryTag(routeId), SUMMARY_ID)
        } catch (_: Exception) {}
    }

    /**
     * Opening the session clears its own notification here, and on purpose also its subagent and
     * background job done/stuck notices: once the session is open there is nothing left in them
     * to see.
     */
    fun cancel(context: Context, routeId: String, sessionId: String) {
        val manager = NotificationManagerCompat.from(context)
        val notificationTag = tag(routeId, sessionId)
        manager.cancel(notificationTag, NOTIFICATION_ID)
        manager.cancel(notificationTag, DONE_ID)
        manager.cancel(notificationTag, STUCK_ID)
        cancelEmptySummary(context, routeId) { tag, _ -> tag == notificationTag }
    }

    /**
     * The user dismissed the notification [id] of a session. The system keeps a group summary
     * whose children are gone, so the last child takes it along here.
     */
    fun onDismissed(context: Context, routeId: String, sessionId: String, id: Int) {
        val notificationTag = tag(routeId, sessionId)
        cancelEmptySummary(context, routeId) { tag, other -> tag == notificationTag && other == id }
    }

    /**
     * Cancels the summary of [routeId] unless a notification of that Mac other than the [gone] ones
     * still shows. Those are left out explicitly: a cancel may not have reached the list yet.
     */
    private fun cancelEmptySummary(
        context: Context,
        routeId: String,
        gone: (String, Int) -> Boolean,
    ) {
        try {
            if (!hasOtherChildren(context, routeId, gone))
                NotificationManagerCompat.from(context).cancel(summaryTag(routeId), SUMMARY_ID)
        } catch (_: Exception) {
            // Unknown children: the summary stays, and the next cancel tries again.
        }
    }

    private fun hasOtherChildren(context: Context, routeId: String, gone: (String, Int) -> Boolean) =
        "pi:$routeId:".let { prefix ->
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                val tag = it.tag
                tag != null && tag.startsWith(prefix) && !gone(tag, it.id)
            }
        }

    private fun post(
        context: Context,
        routeId: String,
        sessionId: String,
        notification: android.app.Notification,
        id: Int = NOTIFICATION_ID,
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val notificationTag = tag(routeId, sessionId)
        if (notification.deleteIntent == null)
            notification.deleteIntent = dismissIntent(context, routeId, sessionId, id)
        try {
            NotificationManagerCompat.from(context).notify(notificationTag, id, notification)
            postSummary(context, routeId, notificationTag, id, notification.timeoutAfter)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    /**
     * Posts or refreshes the summary of a Mac's group, silently: only the children alert. When
     * the notification just posted is the group's only one and times out, the summary times out
     * with it, so it is never left behind alone.
     */
    private fun postSummary(
        context: Context,
        routeId: String,
        childTag: String,
        childId: Int,
        childTimeout: Long,
    ) {
        val localized = localized(context)
        val active =
            try {
                context.getSystemService(NotificationManager::class.java).activeNotifications.toList()
            } catch (_: Exception) {
                null
            }
        val prefix = "pi:$routeId:"
        val alone =
            active != null &&
                active.none { it.tag?.startsWith(prefix) == true && !(it.tag == childTag && it.id == childId) }
        // A process that has not read the pairings yet keeps the name the summary already shows.
        val name =
            hostNames[routeId]
                ?: active
                    ?.find { it.tag == summaryTag(routeId) && it.id == SUMMARY_ID }
                    ?.notification
                    ?.extras
                    ?.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)
                    ?.toString()
        val builder =
            NotificationCompat.Builder(context, UPDATES_CHANNEL)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle(name ?: localized.getString(R.string.remote_notification_group_mac))
                .setSubText(name)
                .setContentIntent(LaunchIntents.openApp(context))
                .setGroup(groupKey(routeId))
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPublicVersion(
                    NotificationCompat.Builder(context, UPDATES_CHANNEL)
                        .setSmallIcon(R.drawable.ic_remote)
                        .setContentTitle(localized.getString(R.string.remote_notification_group_mac))
                        .build()
                )
        if (alone && childTimeout > 0) builder.setTimeoutAfter(childTimeout)
        try {
            NotificationManagerCompat.from(context).notify(summaryTag(routeId), SUMMARY_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked since the child was posted.
        }
    }

    private fun dismissIntent(context: Context, routeId: String, sessionId: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_DISMISSED)
                // The data URI keeps each notification's PendingIntent distinct.
                .setData(
                    Uri.Builder()
                        .scheme("pocketpi")
                        .authority("dismissed")
                        .appendPath(routeId)
                        .appendPath(sessionId)
                        .appendPath(id.toString())
                        .build()
                )
                .putExtra(EXTRA_ROUTE, routeId)
                .putExtra(EXTRA_TARGET, sessionId)
                .putExtra(EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun base(
        context: Context,
        routeId: String,
        sessionId: String,
        alert: Boolean = true,
        channel: String = QUESTIONS_CHANNEL,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentIntent(LaunchIntents.open(context, routeId, sessionId))
            .setGroup(groupKey(routeId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)

    /** What a locked screen shows: never the question itself, and no actions. */
    private fun publicVersion(
        context: Context,
        title: CharSequence,
        channel: String = QUESTIONS_CHANNEL,
    ) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_remote)
            .setContentTitle(title)
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
            NotificationAction.AnswerInApp,
            NotificationAction.ReviewInApp ->
                NotificationCompat.Action.Builder(
                        0,
                        localized.getString(
                            when (action) {
                                NotificationAction.Open -> R.string.remote_notification_open_action
                                NotificationAction.ReviewInApp ->
                                    R.string.remote_notification_review_plan
                                else -> R.string.remote_notification_answer
                            }
                        ),
                        LaunchIntents.open(context, payload.routeId, payload.sessionId),
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

    /** Every session notification's other side: pi, as one person across all sessions. */
    private fun agent(context: Context, localized: Context): Person =
        Person.Builder()
            .setName(localized.getString(R.string.remote_notification_agent))
            .setKey(AGENT_KEY)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setBot(true)
            .build()

    private fun user(localized: Context): Person =
        Person.Builder().setName(localized.getString(R.string.remote_notification_you)).build()

    /**
     * A session's notification as a conversation: [message] from pi below [title], which names the
     * session or what happened. It is a group conversation only so that every Android version shows
     * the title, not just the sender.
     */
    private fun conversationStyle(
        context: Context,
        localized: Context,
        title: CharSequence,
        message: CharSequence,
    ): NotificationCompat.MessagingStyle =
        NotificationCompat.MessagingStyle(user(localized))
            .setConversationTitle(title)
            .setGroupConversation(true)
            .addMessage(message, System.currentTimeMillis(), agent(context, localized))

    /**
     * Ties the notification to the session's launcher shortcut, which makes it a conversation in
     * the shade once [HomeSurfaces] published that shortcut as long-lived.
     */
    private fun NotificationCompat.Builder.conversation(
        routeId: String,
        sessionId: String,
        style: NotificationCompat.MessagingStyle,
    ): NotificationCompat.Builder {
        val id = shortcutId(routeId, sessionId)
        return setStyle(style).setShortcutId(id).setLocusId(LocusIdCompat(id))
    }

    private fun completionReplyAction(
        context: Context,
        localized: Context,
        routeId: String,
        sessionId: String,
    ): NotificationCompat.Action {
        val intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .setData(
                    Uri.Builder()
                        .scheme("pocketpi")
                        .authority("reply")
                        .appendPath(routeId)
                        .appendPath(sessionId)
                        .build()
                )
                .putExtra(EXTRA_ROUTE, routeId)
                .putExtra(EXTRA_TARGET, sessionId)
        // Mutable so RemoteInput can add the typed text, as for an inline answer: the intent is
        // explicit and fill-in never overrides the extras set here.
        val mutability = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return NotificationCompat.Action.Builder(
                0,
                localized.getString(R.string.remote_notification_reply),
                PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutability,
                ),
            )
            .addRemoteInput(
                RemoteInput.Builder(REPLY_KEY)
                    .setLabel(localized.getString(R.string.remote_notification_reply_complete_hint))
                    .build()
            )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
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
