package de.robinrehbein.pocketpi.benchmarks

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
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
private const val CACHED_CHAT = "Cached chat fixture"
private const val CACHED_TAIL = "CONNECTED_FLOW_CACHED_TAIL"
private const val UNCACHED_CHAT = "Uncached chat fixture"
private const val UNCACHED_TAIL = "CONNECTED_FLOW_UNCACHED_TAIL"

// Trace sections of the production repository and chat projection (Diagnostics.kt).
private const val ACTIVATE = "PocketPi.activate"
private const val PUBLISH_TIMELINE = "PocketPi.publishTimeline"
private const val CONVERSATION_ITEMS = "PocketPi.conversationItems"

/**
 * Cold start and chat opens through the real app: RemoteApplication's repository with its
 * lifecycle and network wiring, the normal UI, and FakeRelayTransport in place of the relay.
 * The latency profile is the instrumentation argument `pocketpi.relayProfile` (lan, lte or bad;
 * lte by default). See docs/runbooks/macrobenchmarks.md.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ConnectedFlowBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val profile =
        InstrumentationRegistry.getArguments().getString("pocketpi.relayProfile") ?: "lte"
    private var prepared = false
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

    private fun connectedFlow(prepare: Boolean = false) = Intent().apply {
        component = ComponentName(TARGET, CONNECTED_FLOW)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("profile", profile)
        if (prepare) putExtra("prepare", true)
    }

    /**
     * Clears the app when [clear] is set, then pairs with the fixture Mac (or reconnects to it) and
     * visits the fixture chat through the production repository, so its stores hold a pairing, a
     * draft and a navigation snapshot whose last chat is the cached chat. Not measured.
     */
    private fun MacrobenchmarkScope.prepare(clear: Boolean) {
        killProcess()
        if (clear) check(device.executeShellCommand("pm clear $TARGET").trim() == "Success")
        startActivityAndWait(connectedFlow(prepare = true))
        check(device.wait(Until.hasObject(By.desc(Pattern.compile("connected-flow-(prepared|failed)"))), 120_000)) {
            "Preparing the fixture Mac timed out"
        }
        check(device.hasObject(By.desc("connected-flow-prepared"))) { "Preparing the fixture Mac failed" }
        killProcess()
    }

    /** Once per test: a cleared app, prepared as above. */
    private fun MacrobenchmarkScope.prepareOnce() {
        if (prepared) return
        prepare(clear = true)
        prepared = true
    }

    /** From launch to the fixture projects; a cold start on the Macs list taps the Mac first. */
    private fun MacrobenchmarkScope.waitForProjects() {
        check(device.wait(Until.hasObject(By.text(Pattern.compile("$HOST|$PROJECT"))), 30_000))
        if (!device.hasObject(By.text(PROJECT))) checkNotNull(device.findObject(By.text(HOST))).click()
        check(device.wait(Until.hasObject(By.text(PROJECT)), 60_000))
    }

    /**
     * Waits until ConnectedFlowActivity reports [screen] settled: connected, not loading and no
     * request left with the fake relay, so no late reply lands in the measured block.
     */
    private fun MacrobenchmarkScope.waitSettled(screen: String) {
        check(device.wait(Until.hasObject(By.desc("connected-flow-$screen-settled")), 60_000)) {
            "The $screen screen did not settle"
        }
    }

    /** A fresh process on the fixture project's settled session list; no chat opened in it yet. */
    private fun MacrobenchmarkScope.openSessions(chat: String) {
        killProcess()
        startActivityAndWait(connectedFlow())
        waitForProjects()
        checkNotNull(device.findObject(By.text(PROJECT))).click()
        waitSettled("sessions")
        check(device.wait(Until.hasObject(By.text(chat)), 10_000))
        device.waitForIdle(1_000)
    }

    /**
     * Opens [chat] and waits for its [tail], the newest message: it shows once the snapshot is in
     * and the list follows it. Then checks that the open found the chat in the repository's chat
     * cache exactly when [cached] says so, so neither scenario silently measures the other.
     */
    private fun MacrobenchmarkScope.openChat(chat: String, tail: String, cached: Boolean?) {
        checkNotNull(device.findObject(By.text(chat))).click()
        check(device.wait(Until.hasObject(By.text(tail)), 60_000))
        if (cached == null) return
        check(device.wait(Until.hasObject(By.desc(Pattern.compile("connected-flow-cache-(hit|miss)"))), 10_000)) {
            "The chat open reported no cache result"
        }
        val expected = "connected-flow-cache-" + if (cached) "hit" else "miss"
        check(device.hasObject(By.desc(expected))) {
            if (cached) "$chat was not in the chat cache" else "$chat came from the chat cache, not the host"
        }
    }

    @Test
    fun coldStartToProjects() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), TraceSectionMetric(ACTIVATE, TraceSectionMetric.Mode.Sum)),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            prepareOnce()
            pressHome()
        },
    ) {
        // TTID now; StartupTimingMetric adds TTFD once the app reports it fully drawn.
        startActivityAndWait(connectedFlow())
        waitForProjects()
    }

    @Test
    fun openUncachedChat() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = chatOpenMetrics(),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            // Each open below leaves the uncached chat in the stored navigation snapshot, and a
            // cold start restores that chat into the chat cache. Every iteration after the first
            // therefore prepares again, which stores the cached chat as the last one.
            if (prepared) prepare(clear = false) else prepareOnce()
            openSessions(UNCACHED_CHAT)
        },
    ) {
        openChat(UNCACHED_CHAT, UNCACHED_TAIL, cached = false)
    }

    @Test
    fun openCachedChat() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = chatOpenMetrics(),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            prepareOnce()
            openSessions(CACHED_CHAT)
            // Opening it once in this process puts the chat in the repository's chat cache.
            openChat(CACHED_CHAT, CACHED_TAIL, cached = null)
            waitSettled("chat")
            // A first Back may only close the keyboard of the composer holding the draft.
            repeat(3) {
                if (device.hasObject(By.text(CACHED_TAIL))) {
                    device.pressBack()
                    device.wait(Until.gone(By.text(CACHED_TAIL)), 3_000)
                }
            }
            check(!device.hasObject(By.text(CACHED_TAIL)))
            waitSettled("sessions")
            check(device.wait(Until.hasObject(By.text(CACHED_CHAT)), 10_000))
            device.waitForIdle(1_000)
        },
    ) {
        openChat(CACHED_CHAT, CACHED_TAIL, cached = true)
    }

    private fun chatOpenMetrics() = listOf(
        TraceSectionMetric(ACTIVATE, TraceSectionMetric.Mode.First),
        TraceSectionMetric(PUBLISH_TIMELINE, TraceSectionMetric.Mode.Sum),
        TraceSectionMetric(CONVERSATION_ITEMS, TraceSectionMetric.Mode.Sum),
        FrameTimingMetric(),
    )
}
