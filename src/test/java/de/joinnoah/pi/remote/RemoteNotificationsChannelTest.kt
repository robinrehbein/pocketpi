package de.joinnoah.pi.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteNotificationsChannelOnAndroid13Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun createChannelNeverCreatesTheUnusedSyncChannel() {
        RemoteNotifications.createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            setOf(RemoteNotifications.QUESTIONS_CHANNEL, RemoteNotifications.UPDATES_CHANNEL),
            manager.notificationChannels.map { it.id }.toSet(),
        )
        assertNull(manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL))
    }

    @Test
    fun questionsAlertLoudlyAndUpdatesUseTheDefaultImportance() {
        RemoteNotifications.createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(RemoteNotifications.QUESTIONS_CHANNEL)?.importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(RemoteNotifications.UPDATES_CHANNEL)?.importance,
        )
    }

    @Test
    fun createChannelRemovesTheLegacySingleChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RemoteNotifications.LEGACY_CHANNEL,
                "Sessions",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        RemoteNotifications.createChannel(context)
        assertNull(manager.getNotificationChannel(RemoteNotifications.LEGACY_CHANNEL))
        // Running it again on the next start changes nothing.
        RemoteNotifications.createChannel(context)
        assertEquals(2, manager.notificationChannels.size)
    }

    @Test
    fun onlyQuestionsUseTheQuestionsChannel() {
        PushEvent.entries.forEach { event ->
            assertEquals(
                if (event == PushEvent.QUESTION) RemoteNotifications.QUESTIONS_CHANNEL
                else RemoteNotifications.UPDATES_CHANNEL,
                RemoteNotifications.channelFor(event),
            )
        }
    }

    @Test
    fun createSyncChannelIsANoOpAboveAndroid11() {
        RemoteNotifications.createSyncChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL))
    }

    @Test
    fun deleteSyncChannelRemovesAChannelLeftOverFromAnUpgrade() {
        // Stands in for an install that created the channel while still below Android 12 (a
        // dev/sideload upgrade — a fresh install under the current application ID never has it).
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RemoteNotifications.SYNC_CHANNEL, "Background checks", NotificationManager.IMPORTANCE_LOW)
        )
        RemoteNotifications.deleteSyncChannel(context)
        assertNull(manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL))
    }

    @Test
    fun deleteSyncChannelIsIdempotentWhenThereIsNothingToDelete() {
        RemoteNotifications.deleteSyncChannel(context)
        RemoteNotifications.deleteSyncChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class RemoteNotificationsChannelOnAndroid8Point1Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun createSyncChannelUsesAtLeastImportanceLow() {
        RemoteNotifications.createSyncChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel?.importance)
    }
}
