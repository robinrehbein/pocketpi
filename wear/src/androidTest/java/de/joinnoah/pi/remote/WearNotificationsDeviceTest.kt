package de.joinnoah.pi.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Posts the watch's question, completion and failed-run notifications on a Wear emulator and reads
 * them back from the system. Run with `-Pandroid.testInstrumentationRunnerArguments.keepNotifications=true`
 * to leave the cards in the stream for screenshots (docs/wear-os.md, "Emulator smoke test").
 */
@RunWith(AndroidJUnit4::class)
class WearNotificationsDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val keep = InstrumentationRegistry.getArguments().getString("keepNotifications") == "true"

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        WearNotifications.createChannel(context)
        WearNotifications.forget(context, ROUTE)
    }

    @After
    fun tearDown() {
        if (!keep) WearNotifications.forget(context, ROUTE)
    }

    @Test
    fun postsQuestionCompletionAndFailedRuns() {
        // One session each: a question and a completion of the same session share one notification.
        val cases = listOf(
            PushPayload(ROUTE, "question", "event-question", PushEvent.QUESTION)
                to (R.string.remote_notification_question to R.string.remote_notification_open),
            PushPayload(ROUTE, "complete", "event-complete", PushEvent.COMPLETE)
                to (R.string.remote_notification_complete to R.string.remote_notification_open),
            PushPayload(ROUTE, "agent-error", "event-agent-error", PushEvent.COMPLETE, failure = RunFailure.AGENT_ERROR)
                to (R.string.remote_notification_run_failed to R.string.remote_notification_failure_agent_error),
            PushPayload(ROUTE, "worker-exited", "event-worker-exited", PushEvent.COMPLETE, failure = RunFailure.WORKER_EXITED)
                to (R.string.remote_notification_run_failed to R.string.remote_notification_failure_worker_exited),
        )
        cases.forEach { (payload, _) -> WearNotifications.post(context, payload) }
        for ((payload, texts) in cases) {
            val posted = awaitPosted("watch:$ROUTE:${payload.sessionId}")
            assertEquals(context.getString(texts.first), posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(context.getString(texts.second), posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertNotNull(posted.notification.contentIntent)
        }
    }

    private fun awaitPosted(tag: String): StatusBarNotification {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            manager.activeNotifications.firstOrNull { it.tag == tag }?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "No notification tagged $tag" }
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val ROUTE = "wear-device-test"
    }
}
