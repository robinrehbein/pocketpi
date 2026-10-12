package de.joinnoah.pi.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

internal object WearNotifications {
    private const val CHANNEL = "pocketpi_watch"
    private const val OPEN = "de.robinrehbein.pocketpi.WATCH_NOTIFICATION"
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.remote_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
    fun target(intent: Intent): NotificationTarget? {
        if (intent.action != OPEN) return null
        val route = intent.getStringExtra("routeId")?.takeIf { isOpaqueId(it as String?) } ?: return null
        val session = intent.getStringExtra("sessionId")?.takeIf { isOpaqueId(it as String?) } ?: return null
        return NotificationTarget(route, session, intent.getBooleanExtra("jobs", false), intent.getStringExtra("jobId")?.takeIf { isOpaqueId(it as String?) })
    }
    fun intent(context: Context, target: NotificationTarget): Intent = Intent(context, WearActivity::class.java)
        .setAction(OPEN).setData(Uri.Builder().scheme("pocketpi-watch").authority("notification")
            .appendQueryParameter("route", target.routeId).appendQueryParameter("session", target.sessionId)
            .appendQueryParameter("jobs", target.jobs.toString()).appendQueryParameter("job", target.jobId).build())
        .putExtra("routeId", target.routeId).putExtra("sessionId", target.sessionId)
        .putExtra("jobs", target.jobs).putExtra("jobId", target.jobId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun post(context: Context, payload: PushPayload) {
        if (!RemoteNotifications.canPost(context)) return
        val title = when (val value = attentionTitle(payload)) {
            is AttentionTitle.Single -> context.getString(value.id)
            is AttentionTitle.Bundle -> context.resources.getQuantityString(value.plural, value.count, value.count)
        }
        val content = context.getString(R.string.remote_notification_open)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote).setContentTitle(title).setContentText(content)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setLocalOnly(true).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, intent(context, notificationTarget(payload)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(tag(payload.routeId, payload.sessionId), if (payload.event.attention) payload.event.ordinal + 1 else 1, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between checking it and posting.
        }
    }
    private fun tag(route: String, session: String) = "watch:$route:$session"
    fun cancel(context: Context, route: String, session: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == tag(route, session) }.forEach { manager.cancel(it.tag, it.id) }
    }
    fun forget(context: Context, route: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("watch:$route:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }
}

/** Generic event text only; questionnaire contents are read after opening the encrypted connection. */
class WearMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { (application as WearApplication).onPushToken(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = parsePushPayload(message.data) ?: return
        val app = application as WearApplication
        if (!app.settings.state.value.pushEnabled || app.isShowing(payload.routeId, payload.sessionId)) return
        val paired = runCatching { PairingStore(this).load().any { it.routeId == payload.routeId } }.getOrDefault(false)
        if (!paired || !RemoteNotifications.canPost(this)) return
        synchronized(deliveryLock) {
            val preferences = getSharedPreferences("watch_push_events", MODE_PRIVATE)
            val events = preferences.getStringSet("events", emptySet()).orEmpty()
            if (payload.eventId in events) return
            preferences.edit { putStringSet("events", (events.toList().takeLast(63) + payload.eventId).toSet()) }
        }
        app.deliverPush(payload)
    }
    private companion object { val deliveryLock = Any() }
}
