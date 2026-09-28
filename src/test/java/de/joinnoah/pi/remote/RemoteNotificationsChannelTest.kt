package de.joinnoah.pi.remote

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
        assertEquals(1, manager.notificationChannels.size)
        assertNull(manager.getNotificationChannel(RemoteNotifications.SYNC_CHANNEL))
    }

    @Test
    fun createSyncChannelIsANoOpAboveAndroid11() {
        RemoteNotifications.createSyncChannel(context)
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
