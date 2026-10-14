package de.robinrehbein.pocketpi.benchmarks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "de.robinrehbein.pocketpi.benchmark"
private const val CONNECTED_FLOW = "de.joinnoah.pi.remote.ConnectedFlowActivity"
private const val MAIN = "de.joinnoah.pi.remote.MainActivity"
private const val SHARE = "de.joinnoah.pi.remote.ShareActivity"
private const val LIVE_RUN = "de.joinnoah.pi.remote.LiveRunService"

/** The launcher icon's intent; a second start brings the existing task back instead. */
private const val LAUNCH = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $TARGET/$MAIN"

// These names and markers come from src/benchmark/assets/connected-flow.json.
private const val HOST = "Fixture Mac"
private const val PROJECT = "PocketPi fixture"
private const val CACHED_CHAT = "Cached chat fixture"
private const val CACHED_TAIL = "CONNECTED_FLOW_CACHED_TAIL"
private const val UNCACHED_CHAT = "Uncached chat fixture"
private const val UNCACHED_TAIL = "CONNECTED_FLOW_UNCACHED_TAIL"

/**
 * Smoke checks of the R8-shrunk benchmark APK, driven from outside like a user: the paths where a
 * missing keep rule shows only at runtime (the wire's JSON, the saved back stack's serializers, the
 * share target and the InternalLaunch alias, the live-run foreground service). Not a measurement;
 * an emulator is fine, and CI runs it: docs/runbooks/macrobenchmarks.md. Each test clears the
 * benchmark app's data first.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var originalIdleTimeout = 0L

    @Before
    fun pairWithFixtureMac() {
        val configurator = Configurator.getInstance()
        originalIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(0)
        check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
        device.executeShellCommand("am start -n $TARGET/$CONNECTED_FLOW --ez prepare true")
        check(device.wait(Until.hasObject(By.desc(Pattern.compile("connected-flow-(prepared|failed)"))), 120_000)) {
            "Preparing the fixture Mac timed out"
        }
        check(device.hasObject(By.desc("connected-flow-prepared"))) { "Preparing the fixture Mac failed. " + device.screenTexts() }
        device.executeShellCommand("am force-stop $TARGET")
    }

    @After
    fun restoreImplicitIdleWait() {
        Configurator.getInstance().setWaitForIdleTimeout(originalIdleTimeout)
        device.executeShellCommand("am force-stop $TARGET")
    }

    /** Launcher start, the fixture Mac's projects and sessions, a chat with its newest message. */
    @Test
    fun pairedAppOpensChat() {
        launch()
        openChat(UNCACHED_CHAT, UNCACHED_TAIL)
    }

    /**
     * The back stack is saved when the app goes to the background and restored by class name in a
     * new process, so the chat is back without navigating to it again.
     */
    @Test
    fun backStackSurvivesProcessDeath() {
        launch()
        openChat(UNCACHED_CHAT, UNCACHED_TAIL)
        device.pressHome()
        device.waitForIdle(1_000)
        // am kill only stops a cached process. Right after Home the app is still the previous
        // app, which Android keeps; another app in between makes it cached.
        device.executeShellCommand("am start -a android.settings.SETTINGS")
        device.waitForIdle(2_000)
        device.pressHome()
        val deadline = System.currentTimeMillis() + 20_000
        while (device.executeShellCommand("pidof $TARGET").isNotBlank()) {
            check(System.currentTimeMillis() < deadline) { "The app's process is still alive. " + device.screenTexts() }
            device.executeShellCommand("am kill $TARGET")
            Thread.sleep(500)
        }
        // Brings the task back with its saved instance state, as the launcher icon does.
        device.executeShellCommand(LAUNCH)
        check(device.wait(Until.hasObject(By.text(UNCACHED_TAIL)), 60_000)) { "The restored chat did not show. " + device.screenTexts() }
    }

    /**
     * Text shared from another app reaches ShareActivity's picker; the session picked there opens
     * through LaunchIntents and the InternalLaunch alias.
     */
    @Test
    fun sharedTextOpensPickedSession() {
        device.executeShellCommand(
            "am start -a android.intent.action.SEND -t text/plain " +
                "--es android.intent.extra.TEXT minified-smoke-share -n $TARGET/$SHARE"
        )
        check(device.wait(Until.hasObject(By.text(CACHED_CHAT)), 30_000)) { "The share picker did not list the chat. " + device.screenTexts() }
        device.tapUntil(CACHED_CHAT, By.text(CACHED_TAIL), "The picked chat did not open", timeoutMs = 30_000)
    }

    /**
     * A session that runs while the app goes to the background starts LiveRunService as a
     * foreground service. ConnectedFlowActivity hands the pause to LiveRun as MainActivity does,
     * and its `stream` command makes the fake relay report the open chat as running; the slowed
     * stream keeps it running for about a minute.
     */
    @Test
    fun runningSessionStaysLiveInBackground() {
        // Without it the service still runs, but Android hides its notification.
        device.executeShellCommand("pm grant $TARGET android.permission.POST_NOTIFICATIONS")
        launch("am start -n $TARGET/$CONNECTED_FLOW")
        openChat(UNCACHED_CHAT, UNCACHED_TAIL)
        // The snapshot is in once the tail shows; give the repository a moment to settle.
        Thread.sleep(3_000)
        device.executeShellCommand(
            "am start --activity-single-top -n $TARGET/$CONNECTED_FLOW --es command stream --el streamIntervalMs 500"
        )
        // The stream runs about a minute. A pause that lands before session.status reached the
        // state starts nothing, so the app comes back and leaves again, at most three times.
        var started = false
        for (attempt in 1..3) {
            Thread.sleep(2_000)
            device.pressHome()
            val deadline = System.currentTimeMillis() + 8_000
            while (!started && System.currentTimeMillis() < deadline) {
                started = liveRunInForeground()
                if (!started) Thread.sleep(250)
            }
            if (started) break
            device.executeShellCommand("am start --activity-single-top -n $TARGET/$CONNECTED_FLOW")
        }
        check(started) {
            "LiveRunService did not run in the foreground:\n" + device.executeShellCommand("dumpsys activity services $TARGET")
        }
    }

    private fun liveRunInForeground(): Boolean {
        val dump = device.executeShellCommand("dumpsys activity services $TARGET")
        val record = dump.substringAfter(LIVE_RUN, missingDelimiterValue = "")
        return record.isNotEmpty() && "isForeground=true" in record.substringBefore("* ServiceRecord")
    }

    private fun launch(command: String = LAUNCH) {
        device.executeShellCommand(command)
        check(device.wait(Until.hasObject(By.text(Pattern.compile("$HOST|$PROJECT"))), 30_000))
        if (!device.hasObject(By.text(PROJECT))) device.tapUntil(HOST, By.text(PROJECT), "The projects did not load")
    }

    private fun openChat(chat: String, tail: String) {
        device.tapUntil(PROJECT, By.text(chat), "The session list did not load")
        device.tapUntil(chat, By.text(tail), "$chat did not open", timeoutMs = 30_000)
    }
}
