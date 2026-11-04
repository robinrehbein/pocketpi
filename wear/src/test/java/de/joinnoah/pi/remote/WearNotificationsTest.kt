package de.joinnoah.pi.remote

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WearNotificationsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WearNotifications.createChannel(context)
    }
    @Test fun `intent round trips opaque targets and distinguishes jobs from ordinary sessions`() {
        val session = NotificationTarget("route", "session")
        val jobs = session.copy(jobs = true, jobId = "job")
        assertEquals(session, WearNotifications.target(WearNotifications.intent(context, session)))
        assertEquals(jobs, WearNotifications.target(WearNotifications.intent(context, jobs)))
        assertNotEquals(WearNotifications.intent(context, session).data, WearNotifications.intent(context, session.copy(jobs = true)).data)
        assertNull(WearNotifications.target(Intent()))
        assertNull(WearNotifications.target(WearNotifications.intent(context, session).putExtra("routeId", "bad id")))
    }
    @Test fun `generic notifications contain no conversation text and stay local to watch`() {
        val payload = PushPayload("route", "session", "event", PushEvent.QUESTION)
        WearNotifications.post(context, payload)
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.remote_notification_question), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(context.getString(R.string.remote_notification_open), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertNotNull(notification.contentIntent)
    }
    @Test fun `completion replaces a generic question and unpair cancels only that route`() {
        WearNotifications.post(context, PushPayload("route", "session", "q", PushEvent.QUESTION))
        WearNotifications.post(context, PushPayload("route", "session", "c", PushEvent.COMPLETE))
        assertEquals(1, manager.activeNotifications.size)
        WearNotifications.post(context, PushPayload("other", "session", "o", PushEvent.COMPLETE))
        WearNotifications.forget(context, "route")
        assertEquals("watch:other:session", manager.activeNotifications.single().tag)
    }
    @Test fun `a failed run says so and why in place of the completion`() {
        WearNotifications.post(context, PushPayload("route", "session", "c", PushEvent.COMPLETE))
        WearNotifications.post(context, PushPayload("route", "session", "f", PushEvent.COMPLETE, failure = RunFailure.WORKER_EXITED))
        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.remote_notification_run_failed), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(context.getString(R.string.remote_notification_failure_worker_exited), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        // A plain completion afterwards is a new run's: it reads as finished again.
        WearNotifications.post(context, PushPayload("route", "session", "c2", PushEvent.COMPLETE))
        assertEquals(context.getString(R.string.remote_notification_complete), manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }
    @Test fun `no permission means no notification`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WearNotifications.post(context, PushPayload("route", "session", "event", PushEvent.COMPLETE))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
