package de.joinnoah.pi.remote

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A completion whose run ended without an answer (`push.run_failure.v1`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunFailurePushTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/push-run-failure-v1.json")!!.readText()).jsonObject

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RemoteNotifications.createChannel(context)
    }

    private fun data(entry: JsonObject): Map<String, String> =
        entry.getValue("data").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun entries(name: String) = fixture.getValue(name).jsonArray.map { it.jsonObject }

    private fun posted(): Notification =
        checkNotNull(manager.activeNotifications.find { it.tag == "pi:route-1:session-1" && it.id == 1 }).notification

    private fun style(notification: Notification) =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))

    @Test
    fun theFixturesPushesParseWithTheirCategory() {
        assertEquals("push.run_failure.v1", fixture.getValue("capability").jsonPrimitive.content)
        assertEquals(
            listOf("agent_error", "worker_exited"),
            fixture.getValue("failures").jsonArray.map { it.jsonPrimitive.content },
        )
        val parsed = entries("hostValid").map { parsePushPayload(data(it)) }
        assertEquals(
            listOf(RunFailure.AGENT_ERROR, RunFailure.WORKER_EXITED, null),
            parsed.map { it?.failure },
        )
        for (payload in parsed) {
            assertEquals(PushEvent.COMPLETE, payload?.event)
            assertEquals(NotificationTarget("route-1", "session-1"), notificationTarget(payload!!))
        }
        // A category a later Mac adds is still a failed run, shown without its reason.
        assertEquals(RunFailure.UNKNOWN, parsePushPayload(data(entries("phoneLater").single()))?.failure)
    }

    @Test
    fun aFailureOnlyCountsOnACompletionAndAMalformedOneIsLeftOut() {
        val base = mapOf("routeId" to "r", "target" to "s", "eventId" to "e")
        assertNull(parsePushPayload(base + ("event" to "question") + ("failure" to "agent_error"))?.failure)
        assertNull(parsePushPayload(base + ("event" to "job.done") + ("failure" to "agent_error"))?.failure)
        // Never shown: text, paths and oversized values degrade to a plain completion.
        for (bad in listOf("", "Provider said 401", "/Users/robin/key", "x".repeat(65))) {
            val payload = parsePushPayload(base + ("failure" to bad))
            assertEquals(bad, PushEvent.COMPLETE, payload?.event)
            assertNull(bad, payload?.failure)
        }
        // An older relay sends no kind; its completion keeps the category too.
        assertEquals(RunFailure.WORKER_EXITED, parsePushPayload(base + ("failure" to "worker_exited"))?.failure)
    }

    @Test
    fun theNoticeSaysTheRunFailedAndWhyAndKeepsTheReply() {
        val expected =
            mapOf(
                "complete-agent-error" to
                    "The model or pi reported an error. Open the session to see it and continue.",
                "complete-worker-exited" to "pi exited during the run. Open the session to continue.",
            )
        for (entry in entries("hostValid").filter { it.getValue("name").jsonPrimitive.content in expected }) {
            val payload = parsePushPayload(data(entry))!!
            RemoteNotifications.postComplete(context, payload)
            val notification = posted()
            val reason = expected.getValue(entry.getValue("name").jsonPrimitive.content)
            assertEquals("pi stopped with an error", style(notification).conversationTitle)
            assertEquals(reason, style(notification).messages.single().text)
            assertEquals(reason, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(RemoteNotifications.UPDATES_CHANNEL, notification.channelId)
            // The lock screen sees only that a run failed, not which session or why.
            assertEquals(
                "pi stopped with an error",
                notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            assertNotNull(notification.actions.orEmpty().find { it.remoteInputs?.isNotEmpty() == true })
        }
    }

    @Test
    fun theNamedNoticeKeepsTheReasonInsteadOfTheAnswersEnd() {
        val payload = PushPayload("route-1", "session-1", "e", PushEvent.COMPLETE, failure = RunFailure.UNKNOWN)
        RemoteNotifications.postComplete(context, payload, SessionLabel("Fix the login bug", "pocketpi", "Half an answer"))
        val style = style(posted())
        assertEquals("Fix the login bug stopped with an error", style.conversationTitle)
        assertEquals(
            "The run ended without an answer. Open the session to see why.\npocketpi",
            style.messages.single().text,
        )
    }

    @Test
    fun theLabelUpgradeCarriesTheCategory() {
        for (failure in RunFailure.entries + listOf(null)) {
            val payload = PushPayload("r", "s", "e", PushEvent.COMPLETE, failure = failure)
            assertEquals(payload, CompletionUpgrades.payload(CompletionUpgrades.inputData(payload)))
        }
    }
}
