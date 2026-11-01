package de.joinnoah.pi.remote

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** An actual composer and terminal against the isolated native-worker fixture. */
class MainActivityCustomTerminalEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun publicCustomDialogThroughTerminal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productionEntryCustomDialog") == "true")
        check(args.getString("productionEntryFixture") == "true" && args.getString("productionEntryFreshOwned") == "true")
        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        check(ProductionEntryBootstrap.installed)
        val label = requireNotNull(args.getString("productionEntryLabel"))
        require(label.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val path = requireNotNull(args.getString("productionEntryFixturePath"))
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $path")
        ).use { it.readBytes().toString(Charsets.UTF_8) })
        val route = fixture.getString("routeId")
        val project = fixture.getString("projectId")
        val session = fixture.getString("sessionId")
        check(route == args.getString("productionEntryRoute") && project == args.getString("productionEntryProjectId") && session == args.getString("productionEntrySessionId"))
        check(fixture.getBoolean("nativeWorker"))
        val dialog = fixture.getJSONObject("customDialog")
        val command = dialog.getString("command")
        val followup = dialog.getString("followupCommand")
        val nonce = dialog.getString("nonce")
        val title = dialog.getString("title")
        check(command == "/probe-custom $nonce" && followup == "/probe-custom-followup $nonce")
        check(dialog.getString("expectedValue") == "second")
        val relay = fixture.getString("relayUrl").toHttpUrl()
        val statusUrl = fixture.getString("statusUrl").toHttpUrl()
        require(statusUrl.scheme == "https" && statusUrl.host == "10.0.2.2" && statusUrl.port == relay.port &&
            statusUrl.encodedPath == "/fixture-status" && statusUrl.username.isEmpty() && statusUrl.password.isEmpty() && statusUrl.query == null && statusUrl.fragment == null)
        fun status(): JSONObject = ProductionEntryBootstrap.http().newCall(Request.Builder().url(statusUrl).build()).execute().use {
            check(it.isSuccessful); JSONObject(checkNotNull(it.body).string()).getJSONObject("customDialog")
        }
        fun waitFor(message: String, predicate: () -> Boolean) {
            try { compose.waitUntil(90_000) { predicate() } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Timed out: $message", e) }
        }
        fun tagExists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun textExists(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        val repository = app.repository
        val device = UiDevice.getInstance(instrumentation)
        val proof = JSONObject().put("label", label).put("routeId", route).put("projectId", project).put("sessionId", session)
        val proofFile = File(app.filesDir, "production-entry-custom-$label.json")
        fun record(stage: String) { proof.put("stage", stage); proofFile.writeText(proof.toString()) }
        fun submit(value: String) {
            waitFor("composer ready") { tagExists("composerField") && tagExists("primaryComposerAction") && repository.state.value.connected }
            compose.onNodeWithTag("composerField").performTextReplacement(value)
            waitFor("composer draft propagated") { repository.state.value.draft == value }
            compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            record("launch")
            scenario = ActivityScenario.launch(LaunchIntents.session(app, route, session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("connected selected session") {
                val s = repository.state.value
                s.selection == RemoteSelection(route, project, session) && s.connected && !s.loading && tagExists("composerField")
            }
            submit(command)
            waitFor("custom component opened") { status().optBoolean("opened") }
            assertTrue("Fixture must start with a local attachment", status().getBoolean("localAttachedInitially"))
            record("opened")
            val terminalTitle = app.getString(R.string.remote_terminal_title)
            waitFor("terminal action") { textExists(terminalTitle) }
            compose.onNodeWithText(terminalTitle).performClick()
            waitFor("terminal attachment or takeover") {
                val terminal = repository.state.value.terminal
                terminal != null && !terminal.attaching
            }
            assertEquals("A locally held terminal is required to exercise confirmation", "mac", repository.state.value.terminal?.holder)
            val takeover = app.getString(R.string.remote_terminal_takeover)
            waitFor("takeover action") { textExists(takeover) }
            compose.onNodeWithText(takeover).performClick()
            waitFor("takeover confirmation") { textExists(app.getString(R.string.remote_terminal_takeover_confirm)) }
            val confirmTakeover = hasText(takeover) and hasClickAction() and hasAnyAncestor(isDialog())
            compose.onAllNodes(confirmTakeover).assertCountEquals(1)
            compose.onNode(confirmTakeover).assertIsEnabled().performClick()
            record("takeover-requested")
            waitFor("terminal attached") {
                repository.state.value.terminal?.let { !it.attaching && it.holder == null && it.generation != null && it.ended == null } == true
            }
            waitFor("local holder revoked in same worker") {
                val s = status()
                s.optBoolean("localRevoked") && s.optBoolean("workerVerified")
            }
            proof.put("takeOverConfirmed", true)
            record("takeover-confirmed")
            val webRef = AtomicReference<WebView>()
            fun find(view: View): WebView? {
                if (view is WebView && view.isShown) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            waitFor("terminal WebView") {
                checkNotNull(scenario).onActivity { webRef.set(find(it.window.decorView)) }
                webRef.get() != null
            }
            val web = checkNotNull(webRef.get())
            fun evaluate(script: String): String {
                val done = CountDownLatch(1)
                val result = AtomicReference<String>()
                instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); done.countDown() } }
                check(done.await(10, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
                return checkNotNull(result.get())
            }
            val visibleScript = "document.querySelector('.xterm-rows')?.textContent || ''"
            waitFor("custom text in rendered xterm rows") {
                val rows = JSONObject("{\"value\":" + evaluate(visibleScript) + "}").optString("value")
                rows.contains(title) && rows.contains(nonce) && rows.contains("First choice") && rows.contains("Second choice")
            }
            waitFor("host rendered custom component") { status().optBoolean("rendered") }
            val painted = CountDownLatch(1)
            instrumentation.runOnMainSync {
                web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { web.invalidate(); web.postOnAnimation { web.postOnAnimation { painted.countDown() } } }
                })
            }
            assertTrue("Terminal visual state", painted.await(10, TimeUnit.SECONDS))
            val pixels = AtomicReference<Triple<Int, Int, Int>>()
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
                try {
                    web.draw(Canvas(bitmap))
                    File(app.filesDir, "production-entry-custom-$label-software.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    val values = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    pixels.set(Triple(
                        values.count { ((it shr 16) and 255) > 100 && ((it shr 8) and 255) > 100 && (it and 255) > 100 },
                        values.count { (it ushr 24) == 255 && ((it shr 16) and 255) < 64 && ((it shr 8) and 255) < 64 && (it and 255) < 64 },
                        values.size,
                    ))
                } finally { bitmap.recycle() }
            }
            proof.put("renderedRowsVerified", true).put("glyphPixels", pixels.get().first).put("darkPixels", pixels.get().second).put("webPixels", pixels.get().third)
            record("software-rendered")
            fun rectJson(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
            val geometry = JSONObject()
            val visible = AtomicReference<Rect>()
            checkNotNull(scenario).onActivity { activity ->
                val location = IntArray(2)
                web.getLocationOnScreen(location)
                val local = Rect()
                val global = Rect()
                val localVisible = web.getLocalVisibleRect(local)
                val globalVisible = web.getGlobalVisibleRect(global)
                val screenRect = if (localVisible) Rect(local).apply { offset(location[0], location[1]) } else Rect()
                visible.set(screenRect)
                val ancestors = JSONArray()
                var current: View? = web
                while (current != null) {
                    val v = current
                    val point = IntArray(2)
                    v.getLocationOnScreen(point)
                    val bounds = Rect(point[0], point[1], point[0] + v.width, point[1] + v.height)
                    val entry = JSONObject().put("class", v.javaClass.simpleName).put("bounds", rectJson(bounds))
                        .put("shown", v.isShown).put("visibility", v.visibility).put("alpha", v.alpha)
                    if (v is ViewGroup) entry.put("clipChildren", v.clipChildren).put("clipToPadding", v.clipToPadding)
                    ancestors.put(entry)
                    current = v.parent as? View
                }
                geometry.put("width", web.width).put("height", web.height)
                    .put("locationX", location[0]).put("locationY", location[1])
                    .put("localVisible", localVisible).put("localVisibleRect", rectJson(local))
                    .put("globalVisible", globalVisible).put("globalVisibleRect", rectJson(global))
                    .put("screenVisibleRect", rectJson(screenRect)).put("scrollX", web.scrollX).put("scrollY", web.scrollY)
                    .put("windowSecure", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    .put("ancestors", ancestors)
            }
            val domScript = """(() => {
                const info = e => { if (!e) return null; const r = e.getBoundingClientRect(), s = getComputedStyle(e);
                    return {rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},
                        display:s.display,visibility:s.visibility,opacity:s.opacity,color:s.color,background:s.backgroundColor,
                        overflow:s.overflow,scrollTop:e.scrollTop,scrollLeft:e.scrollLeft}; };
                const rows = document.querySelector('.xterm-rows');
                const terms = [${JSONObject.quote(title)}, ${JSONObject.quote(nonce)}, 'First choice', 'Second choice'];
                return JSON.stringify({viewport:{width:innerWidth,height:innerHeight,devicePixelRatio,scrollX,scrollY},
                    terminal:info(document.getElementById('terminal')), screen:info(document.querySelector('.xterm-screen')),
                    rows:info(rows), matchedRows:terms.map(term => ({found:!!rows && rows.textContent.includes(term),
                        elements:rows ? Array.from(rows.children).filter(e => e.textContent.includes(term)).map(info) : []}))});
            })()""".trimIndent()
            geometry.put("dom", JSONObject(JSONObject("{\"value\":" + evaluate(domScript) + "}").getString("value")))
            val geometryFile = File(app.filesDir, "production-entry-custom-$label-geometry.json")
            geometryFile.writeText(geometry.toString())
            val screenFile = File(app.filesDir, "production-entry-custom-$label.png")
            val cropFile = File(app.filesDir, "production-entry-custom-$label-screen-crop.png")
            record("screen-check")
            try {
                compose.waitUntil(15_000) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    if (screenshot == null) false else try {
                        val bounds = Rect(visible.get())
                        val intersects = bounds.intersect(0, 0, screenshot.width, screenshot.height)
                        var bright = 0
                        var dark = 0
                        var total = 0
                        if (intersects && bounds.width() > 0 && bounds.height() > 0) {
                            val crop = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
                            try {
                                val values = IntArray(crop.width * crop.height)
                                crop.getPixels(values, 0, crop.width, 0, 0, crop.width, crop.height)
                                total = values.size
                                bright = values.count { ((it shr 16) and 255) > 100 && ((it shr 8) and 255) > 100 && (it and 255) > 100 }
                                dark = values.count { (it ushr 24) == 255 && ((it shr 16) and 255) < 64 && ((it shr 8) and 255) < 64 && (it and 255) < 64 }
                                cropFile.outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            } finally { if (crop !== screenshot) crop.recycle() }
                        }
                        screenFile.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        geometry.put("screenshotCrop", rectJson(bounds)).put("screenBrightPixels", bright)
                            .put("screenDarkPixels", dark).put("screenPixels", total)
                        geometryFile.writeText(geometry.toString())
                        bright > 50 && total > 0 && dark > total / 2
                    } finally { screenshot.recycle() }
                }
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Visible terminal screenshot lacks foreground glyph pixels and dark background; see geometry and screen crop", e)
            }
            assertTrue("Terminal software bitmap must contain glyph pixels", pixels.get().first > 50)
            assertTrue("Terminal software bitmap must have a dark opaque background", pixels.get().second > pixels.get().third / 2)
            proof.put("screenPixelsVerified", true)
            record("rendered")
            compose.onNodeWithText("↓").performScrollTo().performClick()
            waitFor("down input settled") { repository.state.value.terminal?.inputPending == false }
            compose.onNodeWithText("Enter").performScrollTo().performClick()
            waitFor("native custom result") { status().optBoolean("resultVerified") }
            record("result-verified")
            compose.onNodeWithText(app.getString(R.string.remote_terminal_close)).performClick()
            waitFor("terminal released") { repository.state.value.terminal?.let { it.ended != null } != false }
            submit(followup)
            waitFor("same worker followup") {
                val s = status()
                s.optBoolean("followupVerified") && s.optBoolean("workerVerified") && s.optBoolean("valid")
            }
            val finalStatus = status()
            listOf("opened", "rendered", "resultVerified", "followupVerified", "workerVerified", "valid", "localAttachedInitially", "localRevoked", "staleLocalInputRejected").forEach { assertTrue(it, finalStatus.getBoolean(it)) }
            proof.put("hostStatus", JSONObject().apply { listOf("opened", "rendered", "resultVerified", "followupVerified", "workerVerified", "valid", "localAttachedInitially", "localRevoked", "staleLocalInputRejected").forEach { put(it, finalStatus.getBoolean(it)) } })
            proof.put("completed", true)
            record("complete")
        } catch (failure: Throwable) {
            runCatching { proof.put("failureType", failure.javaClass.simpleName.take(80)); record("failure") }
            runCatching { device.takeScreenshot(File(app.filesDir, "production-entry-custom-$label-failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(app.filesDir, "production-entry-custom-$label-failure.xml")) }
            throw failure
        } finally { scenario?.close() }
    }
}
