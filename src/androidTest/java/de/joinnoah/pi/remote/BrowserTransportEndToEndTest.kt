package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in integration test against the isolated local peer and real host Chromium fixture. */
class BrowserTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val path = InstrumentationRegistry.getArguments().getString("browserFixturePath")
            assumeTrue("Pass browserFixturePath to run the isolated browser integration test", !path.isNullOrBlank())
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fixture", cert)
            }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            // Keep OkHttp's standard hostname verifier; this client trusts only the fixture CA.
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager)
                .callTimeout(2, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"),
                fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Isolated fixture")
            val pairings = object : PairingStorage {
                private var hosts = listOf(host)
                override fun load() = hosts
                override fun save(hosts: List<PairedHost>) { this.hosts = hosts }
            }
            val drafts = object : DraftStorage {
                private var drafts = emptyMap<DraftKey, StoredDraft>()
                override fun load() = drafts
                override fun save(drafts: Map<DraftKey, StoredDraft>) { this.drafts = drafts }
            }
            repository = DefaultRemoteRepository(pairings, drafts, SecureRemoteTransport(scope, client), scope)
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    val state by repository.state.collectAsState()
                    val actions = remember {
                        BrowserPaneActions(open = repository::openBrowser, acquire = repository::acquireBrowser,
                            refresh = repository::refreshBrowser, newTab = repository::newBrowserTab,
                            navigate = repository::navigateBrowser, selectTab = repository::selectBrowserTab,
                            closeTab = repository::closeBrowserTab, handoff = repository::handoffBrowser,
                            resize = repository::resizeBrowser, input = repository::sendBrowserInput,
                            visible = repository::setBrowserVisible)
                    }
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            BrowserPane(state.browser, actions, repository::closeBrowser)
                        }
                    }
                }
                content
            }
        }

        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.disconnect() }
            }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }).around(compose)

    @Test
    fun realTransportControlsChromiumAndSubmitsForm() {
        assumeTrue("Use the synthetic peer fixture for this transport flow", fixture.optString("sessionKind") != "actual-pi")
        val defaultClient = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        try {
            val failure = runCatching {
                defaultClient.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().close()
            }.exceptionOrNull()
            assertTrue("The platform trust store must reject the fixture CA", failure is SSLHandshakeException)
        } finally {
            defaultClient.dispatcher.cancelAll()
            defaultClient.connectionPool.evictAll()
            defaultClient.dispatcher.executorService.shutdown()
        }
        assertNotNull("The fixture CA must permit the HTTPS status request", status())
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle { scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) } }
        try {
            compose.waitUntil(60_000) { repository.canOpenBrowser() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val state = repository.state.value
            val browserCapability = SESSION_BROWSER_CAPABILITY in state.capabilities
            val otherGates = state.connected && state.selection.sessionId != null && state.capabilitiesKnown && browserCapability
            // Route revision is private; only infer it if all other gates permit opening.
            val v3 = if (otherGates) repository.canOpenBrowser().toString() else "unknown"
            throw AssertionError("Browser readiness: connected=${state.connected}, " +
                "hasProject=${state.selection.projectId != null}, hasSession=${state.selection.sessionId != null}, " +
                "capabilitiesKnown=${state.capabilitiesKnown}, browserCapability=$browserCapability, v3=$v3, " +
                "loading=${state.loading}, errorPresent=${state.error != null}", failure)
        }
        assertTrue("The v3 recovery capability must be negotiated", SESSION_BROWSER_RECOVERY_CAPABILITY in repository.state.value.capabilities)
        compose.runOnIdle { assertTrue(repository.openBrowser()) }
        compose.waitUntil(60_000) {
            repository.state.value.browser?.let { !it.opening && it.browserId != null } == true
        }
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(30_000) { repository.state.value.browser?.ownsControl == true }
        val previousTabIds = repository.state.value.browser?.tabs?.map { it.id }?.toSet().orEmpty()
        compose.onNodeWithText(compose.activity.getString(R.string.browser_new_tab)).performClick()
        try {
            compose.waitUntil(60_000) {
                repository.state.value.browser?.let {
                    it.selectedTabId != null && it.selectedTabId !in previousTabIds && it.image != null
                } == true
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val browser = repository.state.value.browser
            throw AssertionError("Browser frame readiness: hasTabs=${browser?.tabs?.isNotEmpty() == true}, " +
                "hasSelectedTab=${browser?.selectedTabId != null}, hasImage=${browser?.image != null}, " +
                "imageMatchesViewport=${browser?.image?.viewportRevision == browser?.viewportRevision && browser?.image != null}, " +
                "noticePresent=${browser?.notice != null}, ownsControl=${browser?.ownsControl == true}", failure)
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        val pageUrl = fixture.getString("pageUrl").toHttpUrl().toString()
        compose.runOnIdle { assertTrue(repository.navigateBrowser(pageUrl)) }
        compose.waitUntil(60_000) {
            val browser = repository.state.value.browser
            browser?.tabs?.any { it.id == browser.selectedTabId && it.url == pageUrl } == true && browser.image != null
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("browserSendText").fetchSemanticsNodes().isNotEmpty() }
        val beforeTap = requireNotNull(status())
        val pointerDowns = beforeTap.getInt("pointerDowns")
        val pointerUps = beforeTap.getInt("pointerUps")
        // Verify the pointer events at the actual page, independently of its autofocus.
        compose.onNodeWithContentDescription("Remote browser image").performTouchInput { click(center) }
        compose.waitUntil(30_000) {
            status()?.let { it.getInt("pointerDowns") > pointerDowns && it.getInt("pointerUps") > pointerUps } == true
        }
        val viewportBeforeText = repository.state.value.browser?.viewportRevision
        val submitted = "PocketPi browser integration ${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("browserText").performTextInput(submitted)
        // This test exercises browser transport, so settle the IME-driven layout change before sending.
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(30_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            val browser = repository.state.value.browser
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && browser?.ownsControl == true &&
                !browser.inputPending && browser.image?.viewportRevision == browser.viewportRevision &&
                runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        val beforeTextReceipt = repository.state.value.browser?.receipt
        compose.onNodeWithTag("browserSendText").performClick()
        var beforeSubmit: Long? = null
        try {
            compose.waitUntil(30_000) { status()?.optString("input") == submitted }
            compose.waitUntil(30_000) {
                repository.state.value.browser?.let {
                    !it.inputPending && it.receipt != null && it.receipt != beforeTextReceipt && it.receipt.status == "accepted"
                } == true
            }
            compose.waitUntil(30_000) {
                runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false)
            }
            val beforeEnterReceipt = repository.state.value.browser?.receipt
            beforeSubmit = repository.state.value.browser?.image?.frameId
            compose.onNodeWithText("Enter").performClick()
            compose.waitUntil(30_000) {
                repository.state.value.browser?.let {
                    !it.inputPending && it.receipt != null && it.receipt != beforeEnterReceipt && it.receipt.status == "accepted"
                } == true
            }
            compose.waitUntil(30_000) { status()?.optString("submitted") == submitted }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val browser = repository.state.value.browser
            val screenshot = File(compose.activity.getExternalFilesDir(null), "browser-transport-e2e-failure.png")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot)
            throw AssertionError("Browser submit readiness: ownsControl=${browser?.ownsControl == true}, " +
                "notice=${browser?.notice}, viewportChanged=${browser?.viewportRevision != viewportBeforeText}, " +
                "imagePresent=${browser?.image != null}, inputPending=${browser?.inputPending == true}, " +
                "receiptPresent=${browser?.receipt != null}, receiptAccepted=${browser?.receipt?.status == "accepted"}", failure)
        }
        compose.waitUntil(30_000) {
            val image = repository.state.value.browser?.image
            if (image == null || image.frameId == beforeSubmit) false else {
                val bitmap = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size)
                if (bitmap == null) false else try {
                    val pixel = bitmap.getPixel(0, 0)
                    Color.green(pixel) > 200 && Color.red(pixel) < 80 && Color.blue(pixel) < 80
                } finally { bitmap.recycle() }
            }
        }
        // Wait for Compose to display the green frame too, before taking the device screenshot.
        compose.waitUntil(30_000) {
            runCatching {
                val pixels = compose.onNodeWithContentDescription("Remote browser image").captureToImage().toPixelMap()
                var green = 0
                for (x in 1..8) for (y in 1..8) {
                    val pixel = pixels[x * (pixels.width - 1) / 9, y * (pixels.height - 1) / 9]
                    if (pixel.green > 0.78f && pixel.red < 0.32f && pixel.blue < 0.32f) green++
                }
                green >= 4
            }.getOrDefault(false)
        }
        assertEquals(submitted, status()?.getString("submitted"))
        // Follow an actual page link while retaining a separate, unsent Android draft.
        val unsent = "unsent navigation draft ${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("browserText").performTextInput(unsent)
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        val beforeNavigation = requireNotNull(repository.state.value.browser)
        assertTrue(beforeNavigation.ownsControl)
        val oldImage = requireNotNull(beforeNavigation.image)
        val beforeLinkStatus = requireNotNull(status())
        val oldVisits = beforeLinkStatus.getInt("nextVisits")
        val beforeRecovery = beforeLinkStatus.getJSONObject("recoveryDiagnostics")
        val acquireRequests = beforeRecovery.getInt("acquireRequests")
        val sameLeaseEvents = beforeRecovery.getInt("sameLeaseDocumentEvents")
        val differentLeaseEvents = beforeRecovery.getInt("differentLeaseDocumentEvents")
        val imageNode = compose.onNodeWithContentDescription("Remote browser image")
        val bounds = imageNode.fetchSemanticsNode().size
        val fit = requireNotNull(browserImageFit(bounds.width.toFloat(), bounds.height.toFloat(), oldImage.width, oldImage.height))
        // Inverse of browserImagePoint: the fixture link covers CSS (8,8)..(168,48).
        val link = Offset(fit.left + 64f / oldImage.cssWidth * fit.width, fit.top + 28f / oldImage.cssHeight * fit.height)
        imageNode.performTouchInput { click(link) }
        compose.waitUntil(60_000) {
            val browser = repository.state.value.browser
            browser != null && browser.ownsControl && browser.owner == beforeNavigation.owner &&
                browser.generation == beforeNavigation.generation && browser.documentEpoch > beforeNavigation.documentEpoch &&
                browser.viewportRevision > beforeNavigation.viewportRevision && browser.image?.viewportRevision == browser.viewportRevision &&
                status()?.let { fixtureStatus ->
                    fixtureStatus.optInt("nextVisits", 0) > oldVisits &&
                        fixtureStatus.optJSONObject("recoveryDiagnostics")?.optInt("sameLeaseDocumentEvents", 0)?.let { it > sameLeaseEvents } == true
                } == true
        }
        val afterRecovery = requireNotNull(status()).getJSONObject("recoveryDiagnostics")
        assertEquals("Navigation must not reacquire control", acquireRequests, afterRecovery.getInt("acquireRequests"))
        assertEquals("Navigation must retain the original lease", differentLeaseEvents, afterRecovery.getInt("differentLeaseDocumentEvents"))
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        assertEquals(submitted, status()?.getString("submitted"))
        compose.waitUntil(30_000) {
            val image = repository.state.value.browser?.image
            if (image == null) false else {
                val bitmap = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size)
                if (bitmap == null) false else try {
                    val pixel = bitmap.getPixel(0, 0)
                    Color.blue(pixel) > 200 && Color.red(pixel) < 80 && Color.green(pixel) < 100
                } finally { bitmap.recycle() }
            }
        }
        compose.waitUntil(30_000) {
            runCatching {
                val pixels = compose.onNodeWithContentDescription("Remote browser image").captureToImage().toPixelMap()
                var blue = 0
                for (x in 1..8) for (y in 1..8) {
                    val pixel = pixels[x * (pixels.width - 1) / 9, y * (pixels.height - 1) / 9]
                    if (pixel.blue > 0.78f && pixel.red < 0.32f && pixel.green < 0.4f) blue++
                }
                blue >= 4
            }.getOrDefault(false)
        }
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        val beforeWheel = requireNotNull(repository.state.value.browser)
        val beforeWheelImage = requireNotNull(beforeWheel.image)
        val wheelStatus = requireNotNull(status())
        val scrollEvents = wheelStatus.getInt("scrollEvents")
        // Each vertical wheel unit is forwarded as 48 CSS pixels; allow one pixel of rounding.
        // Fixture telemetry survives earlier runs; use this document's current frame as the baseline.
        val targetScrollY = beforeWheelImage.scrollY + 20 * 0.5 * 48 - 1
        val wheelAcquireRequests = wheelStatus.getJSONObject("recoveryDiagnostics").getInt("acquireRequests")
        // Compose emits real Android ACTION_SCROLL events, which the pane forwards as wheel input.
        compose.onNodeWithContentDescription("Remote browser image").performMouseInput {
            moveTo(center)
            repeat(20) { scroll(0.5f); advanceEventTime(16) }
        }
        compose.waitUntil(60_000) {
            val browser = repository.state.value.browser
            val currentStatus = status()
            browser != null && browser.ownsControl && browser.owner == beforeWheel.owner &&
                browser.generation == beforeWheel.generation && browser.documentEpoch == beforeWheel.documentEpoch &&
                browser.image?.let { it.frameId != beforeWheelImage.frameId && it.scrollY >= targetScrollY } == true &&
                currentStatus != null && currentStatus.optInt("scrollEvents", 0) > scrollEvents &&
                currentStatus.optDouble("lastScrollY", 0.0) >= targetScrollY
        }
        val afterWheel = requireNotNull(status())
        assertEquals(wheelAcquireRequests, afterWheel.getJSONObject("recoveryDiagnostics").getInt("acquireRequests"))
        assertEquals(submitted, afterWheel.getString("input"))
        assertEquals(submitted, afterWheel.getString("submitted"))
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        val screenshot = File(compose.activity.getExternalFilesDir(null), "browser-transport-e2e.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        assertTrue(screenshot.length() > 0)
    }

    @Test
    fun actualPiAndAndroidShareLoginAcrossExplicitHandoffs() {
        assumeTrue("Pass an actual Pi shared-browser fixture", fixture.optString("sessionKind") == "actual-pi")
        val initial = requireNotNull(status()).getJSONObject("combined")
        assertEquals("agent-ready", initial.getString("stage"))
        assertEquals("1.4.0", initial.getString("piVersion"))
        assertTrue("The session must belong to the actual Pi child", initial.getBoolean("ownerPidVerified"))
        assertEquals(0, initial.getInt("toolErrors"))
        val browserId = initial.getString("browserId")
        val tabId = initial.getString("tabId")
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle { scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.canOpenBrowser() }
        compose.runOnIdle { assertTrue(repository.openBrowser()) }
        compose.waitUntil(30_000) {
            repository.state.value.browser?.let { !it.opening && it.browserId == browserId && it.selectedTabId == tabId } == true
        }
        assertFalse("Pi initially owns the shared browser", repository.state.value.browser?.ownsControl == true)
        val agentGeneration = requireNotNull(repository.state.value.browser).generation
        fixtureAction("handoff-to-device")
        compose.waitUntil(30_000) { status()?.optJSONObject("combined")?.optString("stage") == "handed-to-device" }
        val agentId = requireNotNull(status()).getJSONObject("combined").getString("agentParticipantId")
        compose.onNodeWithText(compose.activity.getString(R.string.browser_refresh)).performClick()
        compose.waitUntil(30_000) { (repository.state.value.browser?.generation ?: 0) > agentGeneration }
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(30_000) {
            repository.state.value.browser?.let { it.ownsControl && it.browserId == browserId && it.selectedTabId == tabId && it.image?.viewportRevision == it.viewportRevision } == true
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        val loginValue = fixture.getString("loginValue")
        compose.onNodeWithTag("browserText").performTextInput(loginValue)
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(30_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            val browser = repository.state.value.browser
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && browser?.ownsControl == true &&
                !browser.inputPending && browser.image?.viewportRevision == browser.viewportRevision &&
                runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("browserSendText").performClick()
        compose.waitUntil(30_000) {
            repository.state.value.browser?.let { !it.inputPending && it.receipt?.status == "accepted" } == true
        }
        val beforeLogin = requireNotNull(repository.state.value.browser)
        compose.onNodeWithTag("browserText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Enter").performClick()
        compose.waitUntil(60_000) {
            val browser = repository.state.value.browser
            status()?.optJSONObject("combined")?.optBoolean("loginAccepted") == true &&
                browser?.ownsControl == true && browser.browserId == browserId && browser.selectedTabId == tabId &&
                browser.documentEpoch > beforeLogin.documentEpoch && browser.image?.viewportRevision == browser.viewportRevision
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        val loginScreenshot = File(compose.activity.getExternalFilesDir(null), "browser-shared-login.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(loginScreenshot))
        assertTrue(loginScreenshot.length() > 0)
        // Return ownership through the actual pane, then let Pi prove that its same tab has the cookie.
        compose.onNodeWithText(compose.activity.getString(R.string.browser_refresh)).performClick()
        compose.waitUntil(30_000) { repository.state.value.browser?.participants?.any { it.kind == "agent" && it.id == agentId } == true }
        compose.onNodeWithText(compose.activity.getString(R.string.browser_handoff)).performClick()
        compose.onNodeWithText("agent: $agentId").performClick()
        compose.waitUntil(30_000) { repository.state.value.browser?.ownsControl == false }
        fixtureAction("verify-login")
        compose.waitUntil(30_000) { status()?.optJSONObject("combined")?.optString("stage") == "verified" }
        val final = requireNotNull(status()).getJSONObject("combined")
        assertEquals(browserId, final.getString("browserId"))
        assertEquals(tabId, final.getString("tabId"))
        assertTrue(final.getBoolean("cookieObserved"))
        assertEquals(1, final.getInt("verifyCount"))
        assertEquals(0, final.getInt("toolErrors"))
    }

    private fun fixtureAction(action: String) {
        val body = JSONObject().put("action", action).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use {
            assertEquals("The fixture trigger is admitted exactly once", 202, it.code)
        }
    }

    private fun status(): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use {
            check(it.isSuccessful)
            JSONObject(requireNotNull(it.body).string())
        }
    }.getOrNull()

    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
