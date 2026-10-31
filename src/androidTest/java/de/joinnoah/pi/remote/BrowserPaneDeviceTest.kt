package de.joinnoah.pi.remote

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** UI-only test: the JPEG is host-captured, while control and acknowledgements are simulated. */
class BrowserPaneDeviceTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private var acquired = 0
    private val inputs = mutableListOf<BrowserInput>()
    private lateinit var image: BrowserImage
    private var view by mutableStateOf<BrowserView?>(null)
    private var sequence = 0L

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/pocketpi-browser-capture.jpg")
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            require(bytes.isNotEmpty()) { "Push the host capture to /data/local/tmp/pocketpi-browser-capture.jpg" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0)
            image = BrowserImage(bytes, bounds.outWidth, bounds.outHeight, bounds.outWidth, bounds.outHeight, 1.0, 0.0, 0.0, 1, 1)
            view = BrowserView("device-test", opening = false, browserId = "b", generation = 1, viewportRevision = 1,
                owner = BrowserOwner("agent", "fixture"), tabs = listOf(BrowserTab("t", "Captured browser", "https://example.test")), selectedTabId = "t", image = image)
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    MaterialTheme {
                      Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        BrowserPane(view, BrowserPaneActions(
                            acquire = { acquired++; view = view?.copy(ownsControl = true); true },
                            input = { input ->
                                inputs.add(input)
                                sequence++
                                view = view?.copy(inputPending = false, receipt = BrowserInputReceipt(sequence, "accepted"))
                                true
                            },
                        ), {})
                      }
                    }
                }
                content
            }
        }
        override fun after() { NavigationTestActivity.contentFactory = { {} } }
    }).around(compose)

    @Test fun capturedImageReadOnlyThenExplicitControlAndInput() {
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remote browser image").performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(inputs.isEmpty()) }
        val screenshot = File(compose.activity.getExternalFilesDir(null), "browser-phone.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        assertTrue(screenshot.length() > 0)
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.runOnIdle { assertEquals(1, acquired) }
        compose.onNodeWithContentDescription("Remote browser image").performTouchInput { click(center) }
        compose.waitUntil(10_000) { inputs.count { it is BrowserInput.Pointer } >= 2 }
        compose.onNodeWithTag("browserText").performTextInput("device test")
        compose.onNodeWithTag("browserSendText").performClick()
        compose.waitUntil(10_000) { inputs.any { it is BrowserInput.Text && it.text == "device test" } }
        compose.runOnIdle {
            val pointer = inputs.filterIsInstance<BrowserInput.Pointer>()
            assertEquals("down", pointer[0].action)
            assertEquals("up", pointer[1].action)
        }
    }
}
