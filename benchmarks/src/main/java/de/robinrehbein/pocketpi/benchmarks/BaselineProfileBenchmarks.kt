package de.robinrehbein.pocketpi.benchmarks

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
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

private const val ACTIVATE = "PocketPi.activate"

/**
 * What the baseline profile buys: the path BaselineProfileGenerator records (a paired cold start
 * of MainActivity to the projects, the session list and a 48-turn chat), measured once without
 * any ahead-of-time compilation and once with only the profile compiled, as Play installs it.
 * Run both on the same device and build; docs/runbooks/macrobenchmarks.md.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class BaselineProfileBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private var originalIdleTimeout = 0L

    @Before
    fun pairWithFixtureMac() {
        val configurator = Configurator.getInstance()
        originalIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(0)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
        prepare(device)
    }

    /**
     * The connected-flow prepare pass: pairs with the fixture Mac (or reconnects) and stores the
     * cached chat as the last one, so every iteration opens the uncached chat from the same state.
     */
    private fun prepare(device: UiDevice) {
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
    }

    @Test
    fun startupWithoutCompilation() = startupToChat(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() =
        startupToChat(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun startupToChat(mode: CompilationMode) = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), TraceSectionMetric(ACTIVATE, TraceSectionMetric.Mode.Sum)),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            // The previous iteration left the uncached chat in the navigation snapshot.
            prepare(device)
            pressHome()
        },
    ) {
        // The launcher intent, as the generator starts the app.
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text(Pattern.compile("$HOST|$PROJECT"))), 30_000))
        if (!device.hasObject(By.text(PROJECT))) checkNotNull(device.findObject(By.text(HOST))).click()
        check(device.wait(Until.hasObject(By.text(PROJECT)), 60_000)) { "The projects did not load. " + device.screenTexts() }
        checkNotNull(device.findObject(By.text(PROJECT))).click()
        check(device.wait(Until.hasObject(By.text(CHAT)), 60_000)) { "The session list did not load. " + device.screenTexts() }
        checkNotNull(device.findObject(By.text(CHAT))).click()
        check(device.wait(Until.hasObject(By.text(TAIL)), 60_000)) { "The chat did not open. " + device.screenTexts() }
    }
}
