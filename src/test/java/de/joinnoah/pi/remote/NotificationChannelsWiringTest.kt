package de.joinnoah.pi.remote

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationChannelsWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val payload = PushPayload("r", "s", "e", PushEvent.QUESTION)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RemoteNotifications.createChannel(context)
    }

    private fun channelOfOnlyNotification() = manager.activeNotifications.single().notification.channelId

    @Test
    fun firebaseDisplaysItsOwnMessagesOnTheUpdatesChannel() {
        val metaData =
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
        assertEquals(
            RemoteNotifications.UPDATES_CHANNEL,
            metaData.getString("com.google.firebase.messaging.default_notification_channel_id"),
        )
    }

    @Test
    fun genericQuestionAndUpgradedQuestionUseTheQuestionsChannel() {
        RemoteNotifications.postGenericQuestion(context, payload)
        assertEquals(RemoteNotifications.QUESTIONS_CHANNEL, channelOfOnlyNotification())
        RemoteNotifications.postQuestion(
            context,
            payload,
            QuestionNotification("q", "confirm", "Delete?", "Sure?", listOf(NotificationAction.Open)),
            alert = false,
        )
        assertEquals(RemoteNotifications.QUESTIONS_CHANNEL, channelOfOnlyNotification())
    }

    @Test
    fun completionAndAttentionUseTheUpdatesChannel() {
        RemoteNotifications.postComplete(context, payload.copy(event = PushEvent.COMPLETE))
        assertEquals(RemoteNotifications.UPDATES_CHANNEL, channelOfOnlyNotification())
        manager.cancelAll()
        RemoteNotifications.postAttention(context, payload.copy(event = PushEvent.SUBAGENT_DONE))
        assertEquals(RemoteNotifications.UPDATES_CHANNEL, channelOfOnlyNotification())
    }
}
