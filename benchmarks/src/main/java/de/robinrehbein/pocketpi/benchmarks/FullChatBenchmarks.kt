package de.robinrehbein.pocketpi.benchmarks

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val FULL_TARGET = "de.robinrehbein.pocketpi.benchmark"
private const val FULL_FIXTURE = "de.joinnoah.pi.remote.FullChatFixtureActivity"
private const val TAIL_READY = "FULL_CHAT_TAIL_READY"
private const val TAIL_COMPLETE = "FULL_CHAT_STREAM_COMPLETE"
private const val STREAM_CHECKPOINT = "FULL_CHAT_STREAM_CHECKPOINT"

@RunWith(AndroidJUnit4::class)
class FullChatBenchmarks {
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

    private fun MacrobenchmarkScope.openChat() {
        startActivityAndWait(Intent().apply {
            component = ComponentName(FULL_TARGET, FULL_FIXTURE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        check(device.wait(Until.hasObject(By.desc("full-chat-ready")), 10_000))
        check(device.wait(Until.hasObject(By.text(TAIL_READY)), 10_000))
        // Initial auto-follow and asynchronous touched-line projection can update layout after
        // the first accessible tail. Keep this settling interval outside the measured phase.
        Thread.sleep(2_000)
        device.waitForIdle(1_000)
    }

    private fun MacrobenchmarkScope.swipeConversation(towardHistory: Boolean) {
        // The floating header/composer overlap the list bounds. Inject a moderate-speed
        // gesture in its unobscured middle instead of UiObject2's full-list default swipe.
        val bounds = checkNotNull(device.findObject(By.res("conversationList"))).visibleBounds
        val upper = bounds.top + bounds.height() / 4
        val lower = bounds.top + bounds.height() / 2
        check(device.swipe(bounds.centerX(), if (towardHistory) upper else lower,
            bounds.centerX(), if (towardHistory) lower else upper, 40))
        Thread.sleep(250)
    }

    private fun MacrobenchmarkScope.streamAndWait(following: Boolean) {
        checkNotNull(device.findObject(By.desc("full-chat-stream"))).click()
        check(device.wait(Until.hasObject(By.desc("full-chat-checkpoint")), 30_000))
        if (following) {
            check(device.wait(Until.hasObject(By.text(STREAM_CHECKPOINT)), 10_000))
        } else {
            device.waitForIdle(1_000)
            check(!device.hasObject(By.text(STREAM_CHECKPOINT)))
        }
        checkNotNull(device.findObject(By.desc("full-chat-continue"))).click()
        check(device.wait(Until.hasObject(By.desc("full-chat-complete")), 30_000))
        device.waitForIdle(1_000)
    }

    @Test
    fun fullChatScroll() = benchmark.measureRepeated(
        packageName = FULL_TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = { openChat() },
    ) {
        repeat(4) { swipeConversation(towardHistory = true) }
        check(device.wait(Until.gone(By.text(TAIL_READY)), 5_000))
        repeat(4) { swipeConversation(towardHistory = false) }
        device.waitForIdle(1_000)
    }

    @Test
    fun fullChatStreamingAutoFollow() = benchmark.measureRepeated(
        packageName = FULL_TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = { openChat() },
    ) {
        streamAndWait(following = true)
        // A separate final bubble proves the production auto-follow reached the new tail.
        check(device.wait(Until.hasObject(By.text(TAIL_COMPLETE)), 10_000))
    }

    @Test
    fun fullChatStreamingAfterScrollUp() = benchmark.measureRepeated(
        packageName = FULL_TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            openChat()
            repeat(4) { swipeConversation(towardHistory = true) }
            check(device.wait(Until.gone(By.text(TAIL_READY)), 5_000))
            Thread.sleep(1_000)
            device.waitForIdle(1_000)
        },
    ) {
        streamAndWait(following = false)
        // Updates must not drag a reader of older turns back to the newest message.
        check(!device.hasObject(By.text(TAIL_COMPLETE)))
    }
}
