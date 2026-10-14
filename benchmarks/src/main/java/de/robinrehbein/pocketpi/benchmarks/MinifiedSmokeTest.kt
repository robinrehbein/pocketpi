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
 * share target and the InternalLaunch alias). Not a measurement; an emulator is fine:
 * docs/runbooks/macrobenchmarks.md.
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
        check(device.hasObject(By.desc("connected-flow-prepared"))) { "Preparing the fixture Mac failed" }
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
        device.executeShellCommand("am kill $TARGET")
        val deadline = System.currentTimeMillis() + 10_000
        while (device.executeShellCommand("pidof $TARGET").isNotBlank()) {
            check(System.currentTimeMillis() < deadline) { "The app's process is still alive" }
            Thread.sleep(200)
        }
        // Brings the task back with its saved instance state, as the launcher icon does.
        device.executeShellCommand(LAUNCH)
        check(device.wait(Until.hasObject(By.text(UNCACHED_TAIL)), 60_000)) { "The restored chat did not show" }
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
        check(device.wait(Until.hasObject(By.text(CACHED_CHAT)), 30_000)) { "The share picker did not list the chat" }
        checkNotNull(device.findObject(By.text(CACHED_CHAT))).click()
        check(device.wait(Until.hasObject(By.text(CACHED_TAIL)), 60_000)) { "The picked chat did not open" }
    }

    private fun launch() {
        device.executeShellCommand(LAUNCH)
        check(device.wait(Until.hasObject(By.text(Pattern.compile("$HOST|$PROJECT"))), 30_000))
        if (!device.hasObject(By.text(PROJECT))) checkNotNull(device.findObject(By.text(HOST))).click()
        check(device.wait(Until.hasObject(By.text(PROJECT)), 60_000)) { "The projects did not load" }
    }

    private fun openChat(chat: String, tail: String) {
        checkNotNull(device.findObject(By.text(PROJECT))).click()
        check(device.wait(Until.hasObject(By.text(chat)), 60_000)) { "The session list did not load" }
        checkNotNull(device.findObject(By.text(chat))).click()
        check(device.wait(Until.hasObject(By.text(tail)), 60_000)) { "$chat did not open" }
    }
}
