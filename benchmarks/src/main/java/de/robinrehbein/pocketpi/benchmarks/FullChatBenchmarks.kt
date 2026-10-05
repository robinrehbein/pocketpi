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
import androidx.test.uiautomator.Direction
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

    private fun MacrobenchmarkScope.conversationList() =
        checkNotNull(device.findObject(By.res("conversationList"))).apply {
            // The real floating header/composer overlap the list's accessibility bounds.
            // Gesture only in its unobscured middle, not through the composer or insights pill.
            val height = visibleBounds.height()
            setGestureMargins(16, height / 5, 16, height * 2 / 5)
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
        val list = conversationList()
        repeat(4) { list.swipe(Direction.DOWN, 0.7f) }
        device.waitForIdle(1_000)
        check(!device.hasObject(By.text(TAIL_READY)))
        repeat(4) { list.swipe(Direction.UP, 0.7f) }
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
            val list = conversationList()
            repeat(4) { list.swipe(Direction.DOWN, 0.7f) }
            Thread.sleep(1_000)
            device.waitForIdle(1_000)
            check(!device.hasObject(By.text(TAIL_READY)))
        },
    ) {
        streamAndWait(following = false)
        // Updates must not drag a reader of older turns back to the newest message.
        check(!device.hasObject(By.text(TAIL_COMPLETE)))
    }
}
