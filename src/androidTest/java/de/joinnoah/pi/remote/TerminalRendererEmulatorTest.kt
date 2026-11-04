package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the bundled renderer and native origin-scoped bridge in a real WebView. */
class TerminalRendererEmulatorTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun terminalOutputIsAcknowledgedAndCannotBecomePageCode() {
        val ready = AtomicBoolean(false)
        val written = AtomicInteger()
        val failed = AtomicInteger()
        var renderer: TerminalRenderer? = null
        compose.setContent {
            AndroidView(
                factory = { context ->
                    createTerminalWebView(context, object : TerminalRendererCallbacks {
                        override fun onReady() { ready.set(true) }
                        override fun onWritten() { written.incrementAndGet() }
                        override fun onSize(cols: Int, rows: Int) = Unit
                        override fun onFailure() { failed.incrementAndGet() }
                    }).also { renderer = it }.view
                },
                onRelease = { renderer?.dispose() },
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
        compose.waitUntil(15_000) { ready.get() || failed.get() > 0 }
        assertEquals("The offline renderer must load its bundled assets", 0, failed.get())
        assertTrue(ready.get())
        val terminal = checkNotNull(renderer)
        instrumentation.runOnMainSync {
            assertTrue(terminal.write("\u001b[2J\u001b[HPocketPi remote terminal\r\n\u001b[32mRendered locally\u001b[0m\r\n"))
            assertFalse("Only one output batch may be in flight", terminal.write("must not overtake"))
        }
        compose.waitUntil(10_000) { written.get() == 1 || failed.get() > 0 }
        assertEquals(1, written.get())
        val hostileText = "\r\n<img src=x onerror='window.__terminalInjected=true'>\r\n" +
            "</script><script>window.__terminalInjected=true</script>\r\n"
        instrumentation.runOnMainSync { assertTrue(terminal.write(hostileText)) }
        compose.waitUntil(10_000) { written.get() == 2 || failed.get() > 0 }
        assertEquals(2, written.get())
        assertEquals("\"PocketPi remote terminal\"", evaluate(terminal.view, "terminal.buffer.active.getLine(0).translateToString().trim()"))
        assertEquals("\"rgb(16, 16, 16)\"", evaluate(terminal.view, "getComputedStyle(document.body).backgroundColor"))
        assertEquals("\"undefined\"", evaluate(terminal.view, "typeof window.__terminalInjected"))
        assertEquals("0", evaluate(terminal.view, "document.querySelectorAll('img, iframe').length"))
        assertEquals("\"undefined\"", evaluate(terminal.view, "typeof window.RTCPeerConnection"))
        assertEquals(0, failed.get())
        assertEquals("The terminal container must not clip its rows to zero height", "true", evaluate(terminal.view, "document.getElementById('terminal').getBoundingClientRect().height >= 200"))

        val painted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            terminal.view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    terminal.view.invalidate()
                    terminal.view.postOnAnimation { terminal.view.postOnAnimation { painted.countDown() } }
                }
            })
        }
        assertTrue(painted.await(10, TimeUnit.SECONDS))
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(5_000)
        val layout = evaluate(terminal.view, "JSON.stringify({width:innerWidth,height:innerHeight,screen:document.querySelector('.xterm-screen').getBoundingClientRect().toJSON(),rows:document.querySelector('.xterm-rows')?.textContent})")
        assertEquals("Visible terminal rows must contain the parsed text: $layout", "true", evaluate(terminal.view, "document.querySelector('.xterm-rows').textContent.includes('PocketPi')"))
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(terminal.view.width, terminal.view.height, Bitmap.Config.ARGB_8888)
            terminal.view.draw(android.graphics.Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val glyphPixels = pixels.count { color ->
                ((color shr 16) and 255) > 100 && ((color shr 8) and 255) > 100 && (color and 255) > 100
            }
            val backgroundPixels = pixels.count { color ->
                (color ushr 24) == 255 && ((color shr 16) and 255) < 64 &&
                    ((color shr 8) and 255) < 64 && (color and 255) < 64
            }
            assertTrue("The same rendered bitmap must contain the dark terminal background", backgroundPixels > pixels.size / 2)
            assertTrue("The WebView must paint foreground glyphs, not only its background", glyphPixels > 50)
            File(instrumentation.targetContext.getExternalFilesDir(null), "terminal-view.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "terminal-renderer.png")
        output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        UiDevice.getInstance(instrumentation).executeShellCommand(
            "cp ${output.absolutePath} /sdcard/Download/pocketpi-terminal-renderer.png"
        )
        UiDevice.getInstance(instrumentation).executeShellCommand(
            "cp ${File(instrumentation.targetContext.getExternalFilesDir(null), "terminal-view.png").absolutePath} /sdcard/Download/pocketpi-terminal-view.png"
        )
        assertTrue("The terminal must visibly paint: package=${device.currentPackageName}, layout=$layout", (screenshot.getPixel(screenshot.width / 2, screenshot.height / 2) and 0x00ffffff) < 0x444444)
        screenshot.recycle()
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("WebView evaluation did not complete", done.await(10, TimeUnit.SECONDS))
        return checkNotNull(result)
    }
}
