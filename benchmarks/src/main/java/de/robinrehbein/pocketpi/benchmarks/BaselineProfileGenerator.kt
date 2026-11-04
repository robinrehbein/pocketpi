package de.robinrehbein.pocketpi.benchmarks

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "de.robinrehbein.pocketpi.benchmark"
private const val CONNECTED_FLOW = "de.joinnoah.pi.remote.ConnectedFlowActivity"

// These names and markers come from src/benchmark/assets/connected-flow.json.
private const val HOST = "Fixture Mac"
private const val PROJECT = "PocketPi fixture"
private const val CHAT = "Uncached chat fixture"
private const val TAIL = "CONNECTED_FLOW_UNCACHED_TAIL"

/**
 * Classes that exist only in the benchmark build (src/benchmark). Their rules would match nothing
 * in release, so they stay out of the profile.
 */
private val BENCHMARK_ONLY = Regex(
    "Lde/joinnoah/pi/remote/(ComposableSingletons\\$)?(ConnectedFlow|FakeRelay|FlowFreshness|FlowTrace|" +
        "RelayFixture|FixtureSession|FixtureSettings|StreamScript|LatencyProfile|FullChatFixture|" +
        "PerformanceFixture)[^;/]*;"
)

private fun isReleaseRule(rule: String): Boolean = !BENCHMARK_ONLY.containsMatchIn(rule.substringBefore("->"))

/**
 * Generates src/main/baseline-prof.txt: cold start through the launcher activity to the
 * projects, the session list, opening a 48-turn chat and scrolling it both ways, all on the
 * fake relay, so the connected screens and the repository behind them are profiled too.
 * Run it against the app's benchmarkProfile build (the benchmark app without R8), so the rules name
 * classes and methods as the source does and R8 maps them onto release; on the shrunk benchmark
 * build they would name merged and inlined code. Needs API 33+. The real transport, which the fake
 * relay replaces, has hand-written rules in src/main/baselineProfiles. See
 * docs/runbooks/macrobenchmarks.md.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    private var originalIdleTimeout = 0L

    @Before
    fun disableImplicitIdleWait() {
        val configurator = Configurator.getInstance()
        originalIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(0)
    }

    @After
    fun restoreImplicitIdleWait() {
        Configurator.getInstance().setWaitForIdleTimeout(originalIdleTimeout)
    }

    /**
     * Clears the app and pairs it with the fixture Mac through ConnectedFlowActivity's prepare
     * pass, so the launcher activity starts paired. Runs before collecting and is not profiled.
     */
    @Before
    fun pairWithFixtureMac() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
        device.executeShellCommand("am start -n $TARGET/$CONNECTED_FLOW --ez prepare true")
        check(device.wait(Until.hasObject(By.desc(Pattern.compile("connected-flow-(prepared|failed)"))), 120_000)) {
            "Preparing the fixture Mac timed out"
        }
        check(device.hasObject(By.desc("connected-flow-prepared"))) { "Preparing the fixture Mac failed. " + device.screenTexts() }
        device.executeShellCommand("am force-stop $TARGET")
    }

    @Test
    fun generate() = rule.collect(packageName = TARGET, filterPredicate = ::isReleaseRule) {
        killProcess()
        pressHome()
        // The launcher intent, so MainActivity's own start is profiled.
        startActivityAndWait()
        waitForProjects()
        device.tapUntil(PROJECT, By.text(CHAT), "The session list did not load")
        device.tapUntil(CHAT, By.text(TAIL), "The chat did not open", timeoutMs = 30_000)
        device.waitForIdle(1_000)
        repeat(4) { swipeConversation(towardHistory = true) }
        repeat(4) { swipeConversation(towardHistory = false) }
        device.waitForIdle(1_000)
    }

    /** A cold start shows the Macs or, once it restores the last Mac, its projects directly. */
    private fun MacrobenchmarkScope.waitForProjects() {
        check(device.wait(Until.hasObject(By.text(Pattern.compile("$HOST|$PROJECT"))), 30_000))
        if (!device.hasObject(By.text(PROJECT))) device.tapUntil(HOST, By.text(PROJECT), "The projects did not load")
    }

    /**
     * A moderate swipe in the middle of the screen, clear of the floating header and composer.
     * MainActivity exposes no test tags as resource IDs, so it uses the display, not the list.
     */
    private fun MacrobenchmarkScope.swipeConversation(towardHistory: Boolean) {
        val x = device.displayWidth / 2
        val upper = device.displayHeight * 2 / 5
        val lower = device.displayHeight * 3 / 5
        check(device.swipe(x, if (towardHistory) upper else lower, x, if (towardHistory) lower else upper, 40))
        Thread.sleep(250)
    }
}
