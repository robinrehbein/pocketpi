package de.joinnoah.pi.remote

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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
class CompletionUpgradesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val host = PairedHost("r", "https://relay.example", "d", "secret", "Mac")
    private val complete = PushPayload("r", "s", "e", PushEvent.COMPLETE)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RemoteNotifications.createChannel(context)
    }

    private fun title() =
        manager.activeNotifications.single().notification.extras
            .getCharSequence(NotificationCompat.EXTRA_TITLE).toString()

    private fun text() =
        manager.activeNotifications.single().notification.extras
            .getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    /** What [LabelUpgradeWorker] does with a result. */
    private fun apply(result: LabelResult) {
        if (result is LabelResult.Post) RemoteNotifications.postComplete(context, complete, result.label)
    }

    @Test
    fun theLabelReplacesTheGenericCompletionInPlace() = runTest {
        RemoteNotifications.postComplete(context, complete)
        assertEquals("pi has finished", title())
        val result =
            LabelUpgrader { _, _, preview ->
                    assertTrue("A completion asks for the preview", preview)
                    SessionLabel("Fix the login bug", "pocketpi", "All tests pass now.")
                }
                .run(host, complete) { true }
        apply(result)
        assertEquals("Fix the login bug finished", title())
        assertEquals("pocketpi", text())
        val notification = manager.activeNotifications.single()
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(
            "pocketpi\nAll tests pass now.",
            notification.notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString(),
        )
        // The lock screen still sees nothing about the session.
        assertEquals(
            "pi has finished",
            notification.notification.publicVersion.extras
                .getCharSequence(NotificationCompat.EXTRA_TITLE).toString(),
        )
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            notification.notification.visibility,
        )
    }

    @Test
    fun aTimeoutKeepsTheGenericNotification() = runTest {
        RemoteNotifications.postComplete(context, complete)
        val result =
            LabelUpgrader { _, _, _ -> awaitCancellation() }.run(host, complete) { true }
        assertEquals(LabelResult.Unavailable, result)
        assertEquals(LABEL_TIMEOUT_MILLIS, currentTime)
        apply(result)
        assertEquals("pi has finished", title())
        assertEquals("Open the session to see its current state.", text())
    }

    @Test
    fun anErrorKeepsTheGenericNotification() = runTest {
        RemoteNotifications.postComplete(context, complete)
        val result =
            LabelUpgrader { _, _, _ -> throw RemoteConnectionException() }.run(host, complete) { true }
        assertEquals(LabelResult.Unavailable, result)
        apply(result)
        assertEquals("pi has finished", title())
    }

    @Test
    fun aDismissedNotificationIsNotBroughtBack() = runTest {
        val result =
            LabelUpgrader { _, _, _ -> SessionLabel("T", "P", null) }.run(host, complete) { false }
        assertEquals(LabelResult.NotWanted, result)
    }

    @Test
    fun aLabelWithoutAnyTextChangesNothing() = runTest {
        val result =
            LabelUpgrader { _, _, _ -> SessionLabel(null, null, null) }.run(host, complete) { true }
        assertEquals(LabelResult.Unavailable, result)
    }

    @Test
    fun aSubagentNoticeKeepsItsTitleAndNamesTheSession() = runTest {
        val payload = PushPayload("r", "s", "e", PushEvent.SUBAGENT_DONE)
        RemoteNotifications.postAttention(context, payload)
        val result =
            LabelUpgrader { _, _, preview ->
                    assertEquals("Only a completion reads the last answer", false, preview)
                    SessionLabel("Refactor parser", "pocketpi", null)
                }
                .run(host, payload) { true }
        RemoteNotifications.postAttention(context, payload, (result as LabelResult.Post).label)
        assertEquals("Subagent finished", title())
        assertEquals("Refactor parser · pocketpi", text())
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun anAttentionNoticeWithoutATitleStaysGeneric() = runTest {
        val payload = PushPayload("r", "s", "e", PushEvent.JOB_DONE)
        val result =
            LabelUpgrader { _, _, _ -> SessionLabel(null, "pocketpi", null) }.run(host, payload) { true }
        assertEquals(LabelResult.Unavailable, result)
    }

    @Test
    fun aNameWithoutAProjectOrPreviewKeepsTheGenericText() {
        RemoteNotifications.postComplete(context, complete, SessionLabel("Only title", null, null))
        assertEquals("Only title finished", title())
        assertEquals("Open the session to see its current state.", text())
    }

    private fun message(role: String, vararg parts: Pair<String, String>) =
        Wire.objectOf(
            "role" to role,
            "parts" to
                JsonArray(
                    parts.map { (type, value) ->
                        Wire.objectOf("type" to type, if (type == "text") "text" to value else "name" to value)
                    }
                ),
        )

    @Test
    fun theAssistantsLastTextBecomesTheCollapsedPreview() {
        val messages =
            listOf(
                message("user", "text" to "go"),
                message("assistant", "text" to "Working on it"),
                message("assistant", "toolCall" to "bash"),
                message("assistant", "text" to "All  done.\n\nTests   pass."),
            )
        assertEquals("All done. Tests pass.", lastAssistantPreview(messages))
    }

    @Test
    fun noAssistantTextAfterTheLastUserMessageMeansNoPreview() {
        assertNull(lastAssistantPreview(listOf(message("assistant", "text" to "old"), message("user", "text" to "new"))))
        assertNull(lastAssistantPreview(listOf(message("assistant", "toolCall" to "bash"))))
        assertNull(lastAssistantPreview(emptyList()))
    }

    @Test
    fun aLongPreviewIsCutAtTheLimitWithoutSplittingACharacter() {
        val long = lastAssistantPreview(listOf(message("assistant", "text" to "a".repeat(500))))!!
        assertEquals(PREVIEW_MAX_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        val emoji = lastAssistantPreview(listOf(message("assistant", "text" to "a".repeat(118) + "😀😀")))!!
        assertTrue(emoji.length <= PREVIEW_MAX_CHARS)
        assertTrue(!Character.isHighSurrogate(emoji[emoji.length - 2]))
    }

    private fun commands(sessions: Map<String, List<JsonObject>>, log: MutableList<String> = mutableListOf()) =
        RemoteCommands { type, fields ->
            log += type
            when (type) {
                "projects.list" ->
                    Wire.objectOf(
                        "kind" to "projects",
                        "items" to
                            JsonArray(sessions.keys.map { Wire.objectOf("id" to it, "name" to "Project $it") }),
                    )
                "sessions.list" ->
                    Wire.objectOf(
                        "kind" to "sessions",
                        "items" to JsonArray(sessions.getValue(fields.single { it.first == "projectId" }.second as String)),
                    )
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to "s",
                        "messages" to JsonArray(listOf(message("assistant", "text" to "Done."))),
                    )
                else -> error(type)
            }
        }

    @Test
    fun theLabelIsFoundInTheProjectThatHoldsTheSession() = runTest {
        val log = mutableListOf<String>()
        val label =
            commands(
                    mapOf(
                        "a" to listOf(Wire.objectOf("id" to "other", "title" to "Other")),
                        "b" to listOf(Wire.objectOf("id" to "s", "title" to " Fix it ")),
                        "c" to listOf(Wire.objectOf("id" to "s", "title" to "never read")),
                    ),
                    log,
                )
                .sessionLabel("s", withPreview = true)
        assertEquals(SessionLabel("Fix it", "Project b", "Done."), label)
        assertEquals(2, log.count { it == "sessions.list" })
    }

    @Test
    fun anUnknownSessionHasNoTitleAndNoPreviewIsReadForAttention() = runTest {
        val log = mutableListOf<String>()
        val label =
            commands(mapOf("a" to emptyList()), log).sessionLabel("s", withPreview = false)
        assertEquals(SessionLabel(null, null, null), label)
        assertTrue("session.snapshot" !in log)
    }
}
