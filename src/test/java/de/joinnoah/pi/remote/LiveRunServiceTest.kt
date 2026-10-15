package de.joinnoah.pi.remote

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val STARTED_AT = 1_790_000_000_000L

private fun liveState(
    status: String = "running",
    messages: List<JsonObject> = emptyList(),
    title: String? = "Fix the login",
    connected: Boolean = true,
    session: String = "session",
) =
    RemoteState(
        connected = connected,
        selection = RemoteSelection("route", "project", session),
        session = Wire.objectOf("id" to session, "title" to title, "status" to status),
        project = Wire.objectOf("id" to "project", "name" to "pocketpi"),
        status = status,
        messages = messages,
    )

private fun prompt(timestamp: Long = STARTED_AT) =
    Wire.objectOf("id" to "u1", "role" to "user", "text" to "Run the tests", "timestamp" to timestamp)

private fun toolCall(id: String, name: String, arguments: String) =
    Wire.objectOf("type" to "toolCall", "id" to id, "name" to name, "arguments" to arguments)

private fun reply(vararg parts: JsonObject) =
    Wire.objectOf("id" to "a1", "role" to "assistant", "parts" to JsonArray(parts.toList()))

private fun result(callId: String, state: String?) =
    Wire.objectOf("id" to "t-$callId", "role" to "tool", "toolCallId" to callId, "toolName" to "bash", "text" to "", "state" to state)

private val bash = toolCall("c1", "bash", """{"command":"./gradlew test\nmore"}""")

/** The decisions behind the service, without Android. */
class LiveRunStateTest {
    @Test
    fun onlyALoadedConnectedRunningOrWaitingSessionIsKeptLive() {
        assertEquals(LiveRunTarget("route", "session"), liveRunTarget(liveState("running")))
        assertEquals(LiveRunTarget("route", "session"), liveRunTarget(liveState("waiting")))
        assertNull(liveRunTarget(liveState("idle")))
        assertNull(liveRunTarget(liveState("running", connected = false)))
        assertNull(liveRunTarget(liveState("running").copy(loading = true)))
        assertNull(liveRunTarget(liveState("running").copy(preview = true)))
        assertNull(liveRunTarget(liveState("running").copy(session = null)))
        assertNull(liveRunTarget(liveState("running").copy(selection = RemoteSelection("route", "project"))))
    }

    @Test
    fun aRunEndsWhenItGoesIdleMovesOnOrLosesTheConnection() {
        val target = LiveRunTarget("route", "session")
        assertTrue(liveRunContinues(liveState("waiting"), target))
        assertTrue("a load keeps the old status", liveRunContinues(liveState("idle").copy(loading = true), target))
        assertFalse(liveRunContinues(liveState("idle"), target))
        assertFalse(liveRunContinues(liveState("running", session = "other"), target))
        assertFalse(liveRunContinues(liveState("running", connected = false), target))
    }

    @Test
    fun theCurrentToolIsTheLatestCallWithoutAFinishedResult() {
        val running = currentTool(listOf(prompt(), reply(bash)))
        assertEquals(LiveRunTool("bash", R.string.remote_tool_bash, "./gradlew test"), running)
        assertEquals(running, currentTool(listOf(prompt(), reply(bash), result("c1", "streaming"))))
        assertNull("finished", currentTool(listOf(prompt(), reply(bash), result("c1", "complete"))))
        assertNull("a result without a state is complete", currentTool(listOf(prompt(), reply(bash), result("c1", null))))
        assertNull(
            "pi writes after the call",
            currentTool(listOf(prompt(), reply(bash, Wire.objectOf("type" to "text", "text" to "Done")))),
        )
        assertNull("no reply to the latest prompt yet", currentTool(listOf(reply(bash), prompt())))
        assertEquals(
            LiveRunTool("grep", null, null),
            currentTool(listOf(prompt(), reply(toolCall("c2", "grep", "{}")))),
        )
    }

    @Test
    fun theRunStartsWithTheLatestPromptUnlessItsTimeMakesNoSense() {
        val now = STARTED_AT + 5 * 60_000L
        assertEquals(STARTED_AT, runStartedAt(listOf(prompt(), reply(bash)), fallback = now, now = now))
        assertEquals(now, runStartedAt(listOf(prompt(now + 3_600_000L)), fallback = now, now = now))
        assertEquals(now, runStartedAt(listOf(prompt(now - 2 * 86_400_000L)), fallback = now, now = now))
        assertEquals(now, runStartedAt(emptyList(), fallback = now, now = now))
    }

    @Test
    fun aPromptFromAClockAheadGivesTheSameStartOnEveryCall() {
        val fallback = STARTED_AT
        val now = STARTED_AT + 5 * 60_000L
        val messages = listOf(prompt(now + 30_000L), reply(bash))
        assertEquals(fallback, runStartedAt(messages, fallback, now))
        assertEquals(fallback, runStartedAt(messages, fallback, now + 1_000L))
        assertEquals(
            liveRunContent(liveState(messages = messages), null, fallback, now),
            liveRunContent(liveState(messages = messages), null, fallback, now + 1_000L),
        )
    }

    @Test
    fun aWaitingSessionShowsNoTool() {
        val content = liveRunContent(liveState("waiting", listOf(prompt(), reply(bash))), null, 0, STARTED_AT)
        assertTrue(content.waiting)
        assertNull(content.tool)
        assertEquals("Fix the login", content.title)
        assertEquals("pocketpi", content.project)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRunServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val state = MutableStateFlow(liveState())
    private lateinit var originalStates: (Context) -> StateFlow<RemoteState>

    @Before
    fun setUp() {
        originalStates = LiveRun.states
        LiveRun.reset()
        LiveRun.states = { state }
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        LiveRun.reset()
        LiveRun.states = originalStates
    }

    private fun startedService(): Intent? = shadowOf(context as Application).nextStartedService

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    /** The app pauses with [state] and the service it asked for starts. */
    private fun running(): ServiceController<LiveRunService> {
        assertTrue(LiveRun.onPause(context))
        val intent = checkNotNull(startedService())
        return Robolectric.buildService(LiveRunService::class.java, intent).create().startCommand(0, 1)
    }

    private fun shownNotification(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(RemoteNotifications.LIVE_RUN_ID)

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun assertStopped(controller: ServiceController<LiveRunService>, reason: LiveRunStop) {
        assertEquals(reason, LiveRun.lastStop)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
    }

    // MainActivity

    @Test
    fun theActivityStartsTheServiceOnPauseOnlyWhileTheSessionRunsOrWaits() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, LaunchIntents.app(context)).create().start().resume()
        controller.pause()
        assertEquals(LiveRunService::class.java.name, startedService()?.component?.className)
        assertTrue(LiveRun.wanted)
        controller.resume()
        assertFalse("back in front", LiveRun.wanted)

        state.value = liveState("waiting")
        controller.pause()
        assertEquals(LiveRunService::class.java.name, startedService()?.component?.className)
        controller.resume()

        state.value = liveState("idle")
        controller.pause()
        assertNull(startedService())
        assertFalse(LiveRun.wanted)
    }

    @Test
    fun aConfigurationChangeStartsNoService() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, LaunchIntents.app(context)).create().start().resume()
        // The activity handles rotation and resizing itself; a language change recreates it.
        controller.configurationChange(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(if (locales[0].language == "de") java.util.Locale.ENGLISH else java.util.Locale.GERMAN)
            }
        )
        assertNull(startedService())
        assertFalse(LiveRun.wanted)
    }

    @Test
    fun withTheSettingOffNoServiceStarts() {
        assertTrue("on by default", LiveRunSettings(context).enabled.value)
        LiveRunSettings(context).setEnabled(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java, LaunchIntents.app(context)).create().start().resume()
        controller.pause()
        assertNull(startedService())
        assertFalse(LiveRun.onPause(context))
        assertFalse(LiveRunSettings(context).enabled.value)
    }

    // The service

    @Test
    fun theServiceGoesForegroundWithTheSessionAndAddsStopLater() {
        // Robolectric's clock is not today's: the prompt's time has to be near it to count.
        val start = System.currentTimeMillis() - 60_000L
        state.value = liveState(messages = listOf(prompt(start), reply(bash)))
        val controller = running()
        val notification = checkNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(RemoteNotifications.LIVE_RUN_ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertEquals("Fix the login", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Run command: ./gradlew test", notification.text())
        assertEquals(start, notification.`when`)
        assertNull("no action yet, so Android may hold it back", notification.actions)
        idle(Duration.ofMillis(LiveRunService.ACTIONS_DELAY_MILLIS))
        assertEquals(1, shownNotification()?.actions?.size)
        assertNull(LiveRun.lastStop)
    }

    @Test
    fun theNotificationFollowsTheRun() {
        val controller = running()
        assertEquals("Working…", shadowOf(controller.get()).lastForegroundNotification.text())
        state.value = liveState(messages = listOf(prompt(), reply(bash)))
        idle()
        assertEquals(
            "nothing new is posted while Android may still hold the first one back",
            "Working…",
            shownNotification()?.text(),
        )
        idle(Duration.ofMillis(LiveRunService.ACTIONS_DELAY_MILLIS))
        assertEquals("the latest content comes with Stop", "Run command: ./gradlew test", shownNotification()?.text())
        state.value = liveState(messages = listOf(prompt(), reply(toolCall("c2", "read", """{"path":"README.md"}"""))))
        idle()
        assertEquals("Read file: README.md", shownNotification()?.text())
        state.value = liveState("waiting")
        idle()
        assertEquals("Needs you", shownNotification()?.text())
        assertNull(LiveRun.lastStop)
    }

    @Test
    fun theServiceStopsWhenTheSessionGoesIdle() {
        val controller = running()
        state.value = liveState("idle")
        idle()
        assertStopped(controller, LiveRunStop.IDLE)
    }

    @Test
    fun theServiceStopsWhenTheConnectionIsLost() {
        val controller = running()
        state.value = liveState(connected = false)
        idle()
        assertStopped(controller, LiveRunStop.IDLE)
    }

    @Test
    fun theServiceStopsAfterTheCap() {
        val controller = running()
        idle(Duration.ofMinutes(LIVE_RUN_CAP_MINUTES.toLong()).minusSeconds(1))
        assertNull(LiveRun.lastStop)
        idle(Duration.ofSeconds(1))
        assertStopped(controller, LiveRunStop.CAP)
    }

    @Test
    fun stopInTheNotificationStopsTheService() {
        val controller = running()
        controller.withIntent(Intent(context, LiveRunService::class.java).setAction(LiveRunService.ACTION_STOP)).startCommand(0, 2)
        assertStopped(controller, LiveRunStop.STOPPED)
        assertEquals(2, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun swipingTheNotificationAwayStopsTheService() {
        val controller = running()
        controller.withIntent(Intent(context, LiveRunService::class.java).setAction(LiveRunService.ACTION_DISMISSED)).startCommand(0, 2)
        assertStopped(controller, LiveRunStop.DISMISSED)
    }

    @Test
    fun theAppComingBackStopsTheService() {
        val controller = running()
        LiveRun.onResume()
        assertStopped(controller, LiveRunStop.FOREGROUND)
        assertFalse(LiveRun.wanted)
        // Nothing follows the state any more.
        state.value = liveState("idle")
        idle()
        assertEquals(LiveRunStop.FOREGROUND, LiveRun.lastStop)
    }

    @Test
    fun aResumeBeforeTheServiceStartedStillGoesForegroundAndThenStops() {
        assertTrue(LiveRun.onPause(context))
        LiveRun.onResume()
        val controller =
            Robolectric.buildService(LiveRunService::class.java, checkNotNull(startedService())).create().startCommand(0, 1)
        assertEquals(
            "a startForegroundService must reach startForeground",
            RemoteNotifications.LIVE_RUN_ID,
            shadowOf(controller.get()).lastForegroundNotificationId,
        )
        assertStopped(controller, LiveRunStop.FOREGROUND)
    }

    @Test
    fun aResumeWhileANewerStartIsPendingLeavesTheServiceForThatStart() {
        val controller = running()
        // The app comes back and pauses again: a second start is on its way.
        LiveRun.onResume()
        assertTrue(LiveRun.onPause(context))
        val second = checkNotNull(startedService())
        LiveRun.onResume()
        // The service has seen start #1 only; stopping for it leaves start #2 to arrive.
        assertEquals(LiveRunStop.FOREGROUND, LiveRun.lastStop)
        assertEquals(1, shadowOf(controller.get()).stopSelfId)
        controller.withIntent(second).startCommand(0, 2)
        assertStopped(controller, LiveRunStop.FOREGROUND)
        assertEquals(2, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun aPendingStartTriesStartForegroundBeforeItLooksAtWanted() {
        val controller = running()
        LiveRun.onResume()
        assertTrue(LiveRun.onPause(context))
        val second = checkNotNull(startedService())
        LiveRun.onResume()
        // The shadow clears the notification on stop, so a throwing startForeground shows the
        // call: start #2 reaches it although the app no longer wants the service.
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed"))
        controller.withIntent(second).startCommand(0, 2)
        assertEquals(LiveRunStop.REFUSED, LiveRun.lastStop)
        assertEquals(2, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun aRefusedForegroundStartStops() {
        assertTrue(LiveRun.onPause(context))
        val controller = Robolectric.buildService(LiveRunService::class.java, checkNotNull(startedService())).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed"))
        controller.startCommand(0, 1)
        assertEquals(LiveRunStop.REFUSED, LiveRun.lastStop)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}

/**
 * The ongoing notification on Android 16: a Live Update request with ProgressStyle. Robolectric's
 * Android 16 does not start on this JDK, so the Android 16 branch runs on 15, where
 * NotificationCompat keeps the style and the request in the compat extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRunNotificationOnAndroid16Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun itAsksForALiveUpdateWithIndeterminateProgress() {
        val content =
            LiveRunContent(
                LiveRunTarget("route", "session"),
                "Fix the login",
                "pocketpi",
                waiting = false,
                tool = LiveRunTool("bash", R.string.remote_tool_bash, "./gradlew test"),
                startedAt = STARTED_AT,
            )
        val notification = RemoteNotifications.liveRun(context, content, actions = true, sdk = 36)
        val style = NotificationCompat.Style.extractStyleFromNotification(notification)
        assertTrue(style is NotificationCompat.ProgressStyle)
        assertTrue((style as NotificationCompat.ProgressStyle).isProgressIndeterminate)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertOngoingWithContent(notification)
        assertEquals(1, notification.actions.size)
    }
}

/** Below Android 16: a plain ongoing notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRunNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun itIsAPlainOngoingNotification() {
        val content =
            LiveRunContent(
                LiveRunTarget("route", "session"),
                "Fix the login",
                "pocketpi",
                waiting = false,
                tool = LiveRunTool("bash", R.string.remote_tool_bash, "./gradlew test"),
                startedAt = STARTED_AT,
            )
        val notification = RemoteNotifications.liveRun(context, content, actions = false)
        assertNull(notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNull(NotificationCompat.Style.extractStyleFromNotification(notification))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertOngoingWithContent(notification)
        assertNull(notification.actions)
    }

    @Test
    fun anUntitledSessionAndAnUnknownToolStillReadWell() {
        val content = LiveRunContent(null, null, null, waiting = false, tool = LiveRunTool("grep", null, null), startedAt = STARTED_AT)
        val notification = RemoteNotifications.liveRun(context, content, actions = false)
        assertEquals("pi is working", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Tool: grep", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(RemoteNotifications.LIVE_RUN_CHANNEL, notification.channelId)
    }
}

private fun assertOngoingWithContent(notification: Notification) {
    assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertEquals(RemoteNotifications.LIVE_RUN_CHANNEL, notification.channelId)
    assertEquals("Fix the login", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    assertEquals("Run command: ./gradlew test", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    assertEquals("pocketpi", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
    // The elapsed time: a chronometer counting from the run's start.
    assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    assertEquals(STARTED_AT, notification.`when`)
    assertNotNull(notification.deleteIntent)
    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    assertNotNull(notification.publicVersion)
}

/** The setting's row in Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRunSettingRowTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRowSwitchesTheSettingOffAndOn() {
        compose.setContent { MaterialTheme { LiveRunSettingRow() } }
        compose.onNodeWithTag("liveRunSwitch").assertIsOn().performClick()
        compose.onNodeWithTag("liveRunSwitch").assertIsOff()
        assertFalse(LiveRunSettings(context).enabled.value)
        compose.onNodeWithTag("liveRunSwitch").performClick()
        assertTrue(LiveRunSettings(context).enabled.value)
    }
}
