package de.joinnoah.pi.remote

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttentionNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RemoteNotifications.createChannel(context)
    }

    private fun push(vararg fields: Pair<String, String>) =
        parsePushPayload(mapOf("routeId" to "r", "target" to "s", "eventId" to "e") + fields)!!

    /** The one attention notification posted under [id] (2 for `*.done`, 3 for `*.stuck`). */
    private fun posted(id: Int) = manager.activeNotifications.single { it.id == id }

    private fun title(notification: android.app.Notification) =
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()

    @Test
    fun eachKindRendersItsOwnTitle() {
        val expected =
            mapOf(
                "subagent.done" to Pair("Subagent finished", 2),
                "subagent.stuck" to Pair("Subagent looks stuck", 3),
                "job.done" to Pair("Background job finished", 2),
                "job.stuck" to Pair("Background job looks stuck", 3),
            )
        for ((kind, expectation) in expected) {
            val (text, id) = expectation
            RemoteNotifications.postAttention(context, push("event" to kind, "jobId" to "job_1"))
            assertEquals(text, title(posted(id).notification))
            RemoteNotifications.cancel(context, "r", "s")
        }
    }

    @Test
    fun aBundleShowsTheNumberOfChildren() {
        RemoteNotifications.postAttention(context, push("event" to "subagent.done", "count" to "3"))
        assertEquals("3 subagents finished", title(posted(2).notification))
        assertEquals("3 subagents finished", title(posted(2).notification.publicVersion))
    }

    @Test
    fun anAttentionNoticeKeepsTheSessionsQuestionNotification() {
        RemoteNotifications.postGenericQuestion(context, push("event" to "question"))
        RemoteNotifications.postAttention(context, push("event" to "job.done", "jobId" to "job_1"))
        assertEquals(setOf(1, 2), manager.activeNotifications.map { it.id }.toSet())
        RemoteNotifications.cancel(context, "r", "s")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun stuckAndDoneNoticesForTheSameSessionBothShow() {
        RemoteNotifications.postAttention(context, push("event" to "job.stuck", "jobId" to "job_1"))
        RemoteNotifications.postAttention(context, push("event" to "job.done", "jobId" to "job_2"))
        assertEquals(setOf(2, 3), manager.activeNotifications.map { it.id }.toSet())
        assertEquals("Background job looks stuck", title(posted(3).notification))
        assertEquals("Background job finished", title(posted(2).notification))
        RemoteNotifications.cancel(context, "r", "s")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun jobIntentsCarryTheJobAndDifferFromTheSessionIntent() {
        val session = sessionIntent(context, "r", "s")
        val job = notificationIntent(context, NotificationTarget("r", "s", jobs = true, jobId = "job_1"))
        val list = notificationIntent(context, NotificationTarget("r", "s", jobs = true))
        assertEquals("s", job.getStringExtra(RemoteNotifications.EXTRA_TARGET))
        assertTrue(job.getBooleanExtra(RemoteNotifications.EXTRA_JOBS, false))
        assertEquals("job_1", job.getStringExtra(RemoteNotifications.EXTRA_JOB))
        assertNull(list.getStringExtra(RemoteNotifications.EXTRA_JOB))
        assertEquals(3, setOf(session.data, job.data, list.data).size)
        assertNotEquals(session.data, job.data)
        val plain = notificationIntent(context, NotificationTarget("r", "s"))
        assertEquals(session.data, plain.data)
        assertFalse(plain.hasExtra(RemoteNotifications.EXTRA_JOBS))
    }
}
