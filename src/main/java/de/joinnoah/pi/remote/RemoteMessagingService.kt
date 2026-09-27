package de.joinnoah.pi.remote

import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RemoteMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.Main).launch {
            (application as RemoteApplication).onPushToken(token)
        }
    }

    /** Runs on a worker thread. It never waits for the Mac, so a burst of pushes stays quick. */
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = parsePushPayload(message.data) ?: return
        val paired =
            try {
                PairingStore(this).load().any { it.routeId == payload.routeId }
            } catch (_: Exception) {
                false
            }
        if (!paired) return
        val app = application as RemoteApplication
        if (!app.settings.state.value.pushEnabled) return
        if (!firstDelivery(payload.eventId)) return
        app.onPushEvent(payload)
        QuestionUpgrades.onEvent(applicationContext, payload)
        if (!RemoteNotifications.canPost(this)) return
        // The open chat already shows the question or the result.
        if (app.isShowing(payload.routeId, payload.sessionId)) return
        if (payload.event == PushEvent.COMPLETE) {
            RemoteNotifications.postComplete(this, payload)
            return
        }
        // Shown at once, then replaced in place by the question itself, which is read over the
        // encrypted channel so its text never passes the relay or Firebase.
        RemoteNotifications.postGenericQuestion(this, payload)
        QuestionUpgrades.schedule(applicationContext, payload)
    }

    private fun firstDelivery(event: String): Boolean {
        synchronized(lock) {
            val preferences = getSharedPreferences("delivered_notifications", MODE_PRIVATE)
            val seen = preferences.getStringSet("events", emptySet()).orEmpty()
            if (event in seen) return false
            preferences.edit {
                putStringSet("events", (seen.toList().takeLast(63) + event).toSet())
            }
            return true
        }
    }

    private companion object {
        val lock = Any()
    }
}

internal fun notificationTitle(event: String?): Int =
    if (event == "question") R.string.remote_notification_question
    else R.string.remote_notification_complete
