package de.robinrehbein.pocketpi.benchmarks

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "de.robinrehbein.pocketpi.benchmark"
private const val FIXTURE = "de.joinnoah.pi.remote.PerformanceFixtureActivity"

@RunWith(AndroidJUnit4::class)
class PocketPiBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
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

    @Test
    fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
            pressHome()
        },
    ) {
        // Measures real MainActivity TTID with isolated, unpaired app data.
        startActivityAndWait()
    }

    @Test
    fun syntheticSessionSwitch() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            startActivityAndWait(Intent().apply {
                component = ComponentName(TARGET, FIXTURE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            check(device.wait(Until.hasObject(By.desc("fixture-session-0")), 5_000))
            device.waitForIdle(1_000)
        },
    ) {
        repeat(6) { index ->
            checkNotNull(device.findObject(By.desc("fixture-switch"))).click()
            check(device.wait(Until.hasObject(By.desc("fixture-session-${(index + 1) % 2}")), 5_000))
            device.waitForIdle(1_000)
        }
    }

    @Test
    fun syntheticMarkdownStreaming() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            startActivityAndWait(Intent().apply {
                component = ComponentName(TARGET, FIXTURE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            check(device.wait(Until.hasObject(By.desc("fixture-idle")), 5_000))
            device.waitForIdle(1_000)
        },
    ) {
        checkNotNull(device.findObject(By.desc("fixture-stream"))).click()
        // Completion persists: a fast device cannot finish between two transient-state polls.
        check(device.wait(Until.hasObject(By.desc("fixture-stream-complete")), 15_000))
        device.waitForIdle(1_000)
    }
}
