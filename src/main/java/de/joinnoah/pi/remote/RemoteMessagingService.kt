package de.joinnoah.pi.remote

import android.content.SharedPreferences
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
        receivePush(payload, ServicePushHost(application as RemoteApplication))
    }

    private inner class ServicePushHost(private val app: RemoteApplication) : PushHost {
        private val service = this@RemoteMessagingService

        override fun paired(routeId: String): Boolean =
            try {
                // On a cache hit the Mac's name comes from the app's own pairing load.
                routeId in
                    app.stores.let { stores ->
                        stores.routes.routes {
                            stores.pairingFile.load().onEach(RemoteNotifications::rememberHost)
                        }
                    }
            } catch (swallowed: Exception) {
                Diagnostics.caught("push.onMessageReceived", swallowed)
                false
            }

        override fun pushEnabled() = postsPushes(app.settings.state.value)

        override fun firstDelivery(eventId: String) =
            PushDeliveries(getSharedPreferences(DELIVERED_PREFERENCES, MODE_PRIVATE)).first(eventId)

        override fun canPost() = RemoteNotifications.canPost(service)

        override fun isShowing(routeId: String, sessionId: String) = app.isShowing(routeId, sessionId)

        override fun recordState(payload: PushPayload) {
            app.onPushEvent(payload)
            QuestionUpgrades.onEvent(applicationContext, payload)
        }

        override fun postAttention(payload: PushPayload) {
            RemoteNotifications.postAttention(service, payload)
            CompletionUpgrades.schedule(applicationContext, payload)
        }

        override fun postComplete(payload: PushPayload) {
            RemoteNotifications.postComplete(service, payload)
            CompletionUpgrades.schedule(applicationContext, payload)
        }

        override fun postQuestion(payload: PushPayload) {
            // Shown at once, then replaced in place by the question itself, which is read over the
            // encrypted channel so its text never passes the relay or Firebase.
            RemoteNotifications.postGenericQuestion(service, payload)
            QuestionUpgrades.schedule(applicationContext, payload)
        }
    }

    private companion object {
        const val DELIVERED_PREFERENCES = "delivered_notifications"
    }
}

/** What a push needs from the app; [RemoteMessagingService] provides it. */
internal interface PushHost {
    fun paired(routeId: String): Boolean

    fun pushEnabled(): Boolean

    /** False for an event already posted; records it otherwise. */
    fun firstDelivery(eventId: String): Boolean

    fun canPost(): Boolean

    fun isShowing(routeId: String, sessionId: String): Boolean

    /** A session's state changed: the home surfaces and the question upgrades learn of it. */
    fun recordState(payload: PushPayload)

    fun postAttention(payload: PushPayload)

    fun postComplete(payload: PushPayload)

    fun postQuestion(payload: PushPayload)
}

/** What one push does. A push for an unpaired host or with push switched off is not recorded as delivered. */
internal fun receivePush(payload: PushPayload, host: PushHost) {
    if (!host.paired(payload.routeId)) return
    if (!host.pushEnabled()) return
    if (!host.firstDelivery(payload.eventId)) return
    if (payload.event.attention) {
        // A child or shell says nothing about its session's state or questions.
        if (!host.canPost()) return
        if (host.isShowing(payload.routeId, payload.sessionId)) return
        host.postAttention(payload)
        return
    }
    host.recordState(payload)
    if (!host.canPost()) return
    // The open chat already shows the question or the result.
    if (host.isShowing(payload.routeId, payload.sessionId)) return
    if (payload.event == PushEvent.COMPLETE) host.postComplete(payload) else host.postQuestion(payload)
}

/**
 * The event IDs of the latest pushes, so a push delivered twice posts once. Written with commit, not
 * apply: a process killed right after posting must still know the event when FCM delivers it again.
 */
internal class PushDeliveries(private val preferences: SharedPreferences) {
    fun first(event: String): Boolean =
        synchronized(lock) {
            val seen = preferences.getStringSet(KEY_EVENTS, emptySet()).orEmpty()
            if (event in seen) return false
            val written = preferences.edit().putStringSet(KEY_EVENTS, (seen.toList().takeLast(63) + event).toSet()).commit()
            if (!written) Diagnostics.caught("push.firstDelivery", java.io.IOException("Preferences not written"))
            true
        }

    private companion object {
        const val KEY_EVENTS = "events"
        val lock = Any()
    }
}

/** With push switched off in the app, an incoming push posts nothing and starts no lookup. */
internal fun postsPushes(settings: RemoteSettings): Boolean = settings.pushEnabled

internal fun notificationTitle(event: String?): Int =
    if (event == "question") R.string.remote_notification_question
    else R.string.remote_notification_complete
