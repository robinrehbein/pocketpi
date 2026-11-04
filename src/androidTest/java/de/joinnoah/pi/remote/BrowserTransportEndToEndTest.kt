package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Context
import android.util.Log
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
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.serialization.json.JsonObject

/** Counts send attempts without changing the real transport or its listener callbacks. */
private class BrowserAttemptObserver(private val delegate: RemoteTransport) : RemoteTransport {
    val inputAttempts = AtomicInteger()
    val acquireAttempts = AtomicInteger()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = value }
    override fun connect(host: PairedHost) = delegate.connect(host)
    override fun pair(value: JsonObject) = delegate.pair(value)
    private fun observe(payload: JsonObject) {
        when (payload.optionalText("type")) {
            "browser.input" -> inputAttempts.incrementAndGet()
            "browser.acquire" -> acquireAttempts.incrementAndGet()
        }
    }
    override fun send(payload: JsonObject) {
        observe(payload)
        delegate.send(payload)
    }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) = delegate.sendGuarded(payload) {
        beforePossibleWrite()
        observe(payload)
    }
    override fun close() = delegate.close()
}

/** Opt-in integration test against the isolated local peer and real host Chromium fixture. */
class BrowserTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var observedTransport: BrowserAttemptObserver

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val path = InstrumentationRegistry.getArguments().getString("browserFixturePath")
            if (InstrumentationRegistry.getArguments().getString("browserReconnectExpected") == "true" ||
                InstrumentationRegistry.getArguments().getString("browserDeviceOnlyExpected") == "true" ||
                InstrumentationRegistry.getArguments().getString("browserPreviewExpected") == "true" ||
                InstrumentationRegistry.getArguments().getString("packagedLinuxExpected") == "true")
                require(!path.isNullOrBlank()) { "The reconnect test requires browserFixturePath" }
            assumeTrue("Pass browserFixturePath to run the isolated browser integration test", !path.isNullOrBlank())
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            if (InstrumentationRegistry.getArguments().getString("packagedLinuxExpected") == "true") require(fixture.optBoolean("packagedLinux")) { "The packaged Linux test requires its fixture" }
            if (InstrumentationRegistry.getArguments().getString("browserPreviewExpected") == "true") require(fixture.optBoolean("preview")) { "The preview test requires a preview fixture" }
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
            observedTransport = BrowserAttemptObserver(SecureRemoteTransport(scope, client))
            repository = DefaultRemoteRepository(pairings, drafts, observedTransport, scope)
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    val state by repository.state.collectAsState()
                    val actions = remember {
                        if (fixture.optBoolean("preview")) BrowserPaneActions(
                            acquire = repository::acquirePreview, refresh = repository::refreshPreview,
                            newTab = repository::newPreviewTab, navigate = repository::navigatePreview,
                            selectTab = repository::selectPreviewTab, closeTab = repository::closePreviewTab,
                            handoff = repository::handoffPreview, resize = repository::resizePreview,
                            input = repository::sendPreviewInput, visible = repository::setPreviewVisible)
                        else BrowserPaneActions(open = repository::openBrowser, acquire = repository::acquireBrowser,
                            refresh = repository::refreshBrowser, newTab = repository::newBrowserTab,
                            navigate = repository::navigateBrowser, selectTab = repository::selectBrowserTab,
                            closeTab = repository::closeBrowserTab, handoff = repository::handoffBrowser,
                            resize = repository::resizeBrowser, input = repository::sendBrowserInput,
                            visible = repository::setBrowserVisible)
                    }
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            if (fixture.optBoolean("preview")) BrowserPane(state.previewBrowser, actions, repository::closePreview)
                            else BrowserPane(state.browser, actions, repository::closeBrowser)
                        }
                    }
                }
                content
            }
        }

        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    if (fixture.optBoolean("reconnect") || fixture.optBoolean("preview") || fixture.optBoolean("packagedLinux")) repository.setForeground(false)
                    repository.disconnect()
                }
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
    fun packagedLinuxHostRendersInputsReconnectsAndRevokes() {
        val expected = InstrumentationRegistry.getArguments().getString("packagedLinuxExpected") == "true"
        assumeTrue("Select the packaged Linux fixture explicitly", expected)
        assertTrue(fixture.getBoolean("packagedLinux"))
        val preview = fixture.optBoolean("preview")
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        fun view() = if (preview) repository.state.value.previewBrowser else repository.state.value.browser
        fun input(value: BrowserInput) = if (preview) repository.sendPreviewInput(value) else repository.sendBrowserInput(value)
        compose.runOnIdle {
            repository.setForeground(true)
            repository.setValidatedNetwork("packaged-linux-fixture")
            scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) }
        }
        compose.waitUntil(60_000) { if (preview) repository.canOpenPreview() else repository.canOpenBrowser() }
        assertFalse(if (preview) repository.canOpenBrowser() else repository.canOpenPreview())
        compose.runOnIdle { assertTrue(if (preview) repository.openPreview(fixture.getInt("previewPort")) else repository.openBrowser()) }
        compose.waitUntil(60_000) { view()?.let { !it.opening && it.browserId != null } == true }
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(60_000) { view()?.ownsControl == true }
        if (!preview) {
            if (view()?.tabs.isNullOrEmpty()) {
                compose.runOnIdle { assertTrue(repository.newBrowserTab(fixture.getString("pageUrl"))) }
            } else {
                compose.runOnIdle { assertTrue(repository.navigateBrowser(fixture.getString("pageUrl"))) }
            }
        }
        compose.waitUntil(60_000) { view()?.let { it.selectedTabId != null && it.tabs.any { tab -> tab.id == it.selectedTabId && (preview || tab.url == fixture.getString("pageUrl").toHttpUrl().toString()) } && it.image != null && it.image.viewportRevision == it.viewportRevision } == true }
        val initial = requireNotNull(view())
        val browserId = requireNotNull(initial.browserId)
        val tabId = requireNotNull(initial.selectedTabId)
        val initialImage = requireNotNull(initial.image)
        assertNotNull(BitmapFactory.decodeByteArray(initialImage.jpeg, 0, initialImage.jpeg.size)?.also { it.recycle() })
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Remote browser image").fetchSemanticsNodes().isNotEmpty() }
        val value = "Linux fixture ${java.util.UUID.randomUUID()}"
        val initialPosts = requireNotNull(status()).getInt("inputPosts")
        compose.onNodeWithTag("browserText").performTextInput(value)
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(30_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("browserSendText").performClick()
        try {
            compose.waitUntil(30_000) { status()?.let { it.optString("lastinput") == value && it.optInt("inputPosts") > initialPosts } == true && view()?.inputPending == false }
        } catch (failure: Exception) {
            throw AssertionError("Linux text not observed: attempts=${observedTransport.inputAttempts.get()}, receipt=${view()?.receipt}, pending=${view()?.inputPending}, moving=${view()?.moving}, viewport=${view()?.viewportRevision}, posts=${status()?.optInt("inputPosts")}", failure)
        }
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Enter").performClick()
        try {
            compose.waitUntil(30_000) { status()?.optString("submitted") == value }
        } catch (failure: Exception) {
            throw AssertionError("Linux Enter not observed: attempts=${observedTransport.inputAttempts.get()}, receipt=${view()?.receipt}, pending=${view()?.inputPending}, moving=${view()?.moving}, viewport=${view()?.viewportRevision}, submitCount=${status()?.optInt("submitCount")}", failure)
        }
        fixtureAction("update")
        compose.waitUntil(30_000) {
            val image = view()?.image
            val bitmap = image?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
            val pixel = bitmap?.getPixel(0, 0)
            bitmap?.recycle()
            status()?.optInt("updateAck") == 1 && pixel != null && Color.red(pixel) > 230 && Color.green(pixel) < 30 && Color.blue(pixel) > 230
        }
        val unsent = "Unsent Linux ${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("browserText").performTextReplacement(unsent)
        val inputAttempts = observedTransport.inputAttempts.get()
        val acquireAttempts = observedTransport.acquireAttempts.get()
        val posts = requireNotNull(status()).getInt("inputPosts")
        fixtureAction("cut-device")
        compose.waitUntil(30_000) { !repository.state.value.connected && view()?.ownsControl == false }
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        fixtureAction("allow-device")
        compose.waitUntil(60_000) { repository.state.value.connected && repository.state.value.selection == selection && view()?.let { it.browserId == browserId && it.selectedTabId == tabId && !it.ownsControl && it.image != null } == true }
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        assertEquals(inputAttempts, observedTransport.inputAttempts.get())
        assertEquals(acquireAttempts, observedTransport.acquireAttempts.get())
        assertEquals(posts, requireNotNull(status()).getInt("inputPosts"))
        assertEquals(1, requireNotNull(status()).getInt("cuts"))
        assertTrue(requireNotNull(status()).getBoolean("hostConnected"))
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithContentDescription("Remote browser image").assertIsDisplayed(); true }.getOrDefault(false)
        }
        val screenshot = File(compose.activity.getExternalFilesDir(null), if (preview) "linux-preview-restored.png" else "linux-browser-restored.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        assertTrue(screenshot.length() > 0)
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(30_000) { view()?.let { it.ownsControl && it.image?.viewportRevision == it.viewportRevision } == true }
        assertEquals(acquireAttempts + 1, observedTransport.acquireAttempts.get())
        assertEquals(inputAttempts, observedTransport.inputAttempts.get())
        assertEquals(posts, requireNotNull(status()).getInt("inputPosts"))
        val beforeRevoke = observedTransport.inputAttempts.get()
        fixtureAction("revoke")
        compose.runOnIdle { if (preview) repository.refreshPreview() else repository.refreshBrowser() }
        compose.waitUntil(30_000) { status()?.optBoolean("revoked") == true && (!repository.state.value.connected || view()?.ownsControl == false) }
        compose.runOnIdle { assertFalse(input(BrowserInput.Text("denied"))) }
        assertEquals(beforeRevoke, observedTransport.inputAttempts.get())
    }

    @Test
    fun previewOnlyGrantRendersUpdatesReconnectsAndRevokes() {
        val expected = InstrumentationRegistry.getArguments().getString("browserPreviewExpected") == "true"
        if (expected) assertTrue("The fixture must explicitly enable preview", fixture.optBoolean("preview"))
        assumeTrue("Use --preview for this integration flow", fixture.optBoolean("preview"))
        assertTrue("Preview mode must be explicitly requested", expected)
        val port = fixture.getInt("previewPort")
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle {
            repository.setForeground(true)
            repository.setValidatedNetwork("preview-fixture-loopback")
            scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) }
        }
        compose.waitUntil(60_000) { repository.canOpenPreview() }
        assertFalse("The preview grant must not enable the general browser", repository.canOpenBrowser())
        compose.runOnIdle { assertTrue(repository.openPreview(port)) }
        compose.waitUntil(60_000) { repository.state.value.previewBrowser?.let { !it.opening && it.browserId != null && it.previewPort == port } == true }
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(30_000) { repository.state.value.previewBrowser?.let { it.ownsControl && it.image != null && it.image.viewportRevision == it.viewportRevision } == true }
        val initial = requireNotNull(repository.state.value.previewBrowser)
        val browserId = requireNotNull(initial.browserId)
        val tabId = requireNotNull(initial.selectedTabId)
        assertEquals("http://127.0.0.1:$port/", initial.tabs.single().url)
        assertNull(repository.state.value.browser)
        val submitted = "Preview transport ${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("browserText").performTextInput(submitted)
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(30_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("browserSendText").performClick()
        compose.waitUntil(30_000) { status()?.optString("input") == submitted && repository.state.value.previewBrowser?.inputPending == false }
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Enter").performClick()
        compose.waitUntil(30_000) { status()?.optString("submitted") == submitted }
        fixtureAction("preview-update")
        compose.waitUntil(30_000) {
            val image = repository.state.value.previewBrowser?.image
            val bitmap = image?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
            val pixel = bitmap?.getPixel(0, 0)
            bitmap?.recycle()
            status()?.optJSONObject("preview")?.optInt("updateAck") == 1 && pixel != null &&
                Color.red(pixel) > 230 && Color.green(pixel) < 30 && Color.blue(pixel) > 230
        }
        val unsent = "Unsent preview ${java.util.UUID.randomUUID()}"
        compose.onNodeWithTag("browserText").performTextReplacement(unsent)
        val baseline = requireNotNull(status()).getJSONObject("preview")
        val inputs = baseline.getInt("inputRequests")
        val acquires = baseline.getInt("acquireRequests")
        fixtureAction("preview-cut")
        compose.waitUntil(30_000) { !repository.state.value.connected && repository.state.value.previewBrowser?.notice == BrowserNotice.DISCONNECTED }
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        fixtureAction("preview-allow")
        compose.waitUntil(60_000) {
            repository.state.value.connected && repository.state.value.previewBrowser?.let {
                it.browserId == browserId && it.selectedTabId == tabId && it.previewPort == port && !it.ownsControl && it.image != null
            } == true
        }
        compose.onNodeWithTag("browserText").assertTextContains(unsent)
        compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
        val restored = requireNotNull(status()).getJSONObject("preview")
        assertEquals(inputs, restored.getInt("inputRequests"))
        assertEquals(acquires, restored.getInt("acquireRequests"))
        assertEquals(1, restored.getInt("cuts"))
        val screenshot = File(compose.activity.getExternalFilesDir(null), "preview-transport-restored.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        compose.onNodeWithTag("browserAcquire").performClick()
        compose.waitUntil(30_000) { repository.state.value.previewBrowser?.ownsControl == true }
        assertEquals(inputs, requireNotNull(status()).getJSONObject("preview").getInt("inputRequests"))
        fixtureAction("preview-revoke")
        compose.runOnIdle { repository.refreshPreview() }
        compose.waitUntil(30_000) { repository.state.value.previewBrowser?.ownsControl == false }
        assertTrue(requireNotNull(status()).getJSONObject("preview").getBoolean("revoked"))
        compose.runOnIdle { assertFalse(repository.sendPreviewInput(BrowserInput.Text("denied"))) }
        assertEquals(inputs, requireNotNull(status()).getJSONObject("preview").getInt("inputRequests"))
        assertNull(repository.state.value.browser)
    }

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
        val deviceOnlyExpected = InstrumentationRegistry.getArguments().getString("browserDeviceOnlyExpected") == "true"
        val reconnectExpected = InstrumentationRegistry.getArguments().getString("browserReconnectExpected") == "true" || deviceOnlyExpected
        if (deviceOnlyExpected) {
            assertEquals("The device-only test requires actual Pi", "actual-pi", fixture.optString("sessionKind"))
            assertTrue("The fixture must enable device-only mode", fixture.optBoolean("deviceOnly"))
            assertTrue("The device-only test requires reconnect", fixture.optBoolean("reconnect"))
        }
        val deviceOnly = fixture.optBoolean("deviceOnly")
        if (deviceOnly) {
            assertTrue("Device-only mode must be explicitly requested", deviceOnlyExpected)
        }
        if (reconnectExpected) {
            assertEquals("The reconnect test requires actual Pi", "actual-pi", fixture.getString("sessionKind"))
            assertTrue("The fixture must enable the reconnect scenario", fixture.getBoolean("reconnect"))
        }
        assumeTrue("Pass an actual Pi shared-browser fixture", fixture.optString("sessionKind") == "actual-pi")
        val initial = requireNotNull(status()).getJSONObject("combined")
        assertEquals(if (deviceOnly) "session-ready" else "agent-ready", initial.getString("stage"))
        assertEquals("1.4.0", initial.getString("piVersion"))
        assertTrue("The session must belong to the actual Pi child", initial.getBoolean("ownerPidVerified"))
        assertEquals(0, initial.getInt("toolErrors"))
        val initialBrowserId = if (deviceOnly) null else initial.getString("browserId")
        val initialTabId = if (deviceOnly) null else initial.getString("tabId")
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle { scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.canOpenBrowser() }
        if (fixture.optBoolean("reconnect")) {
            // This test activity does not run the application's lifecycle/network monitor. The
            // fixture keeps its TLS control endpoint reachable while cutting only the WebSocket.
            compose.runOnIdle {
                repository.setForeground(true)
                repository.setValidatedNetwork("fixture-loopback")
            }
        }
        compose.runOnIdle { assertTrue(repository.openBrowser()) }
        compose.waitUntil(30_000) {
            repository.state.value.browser?.let { !it.opening && it.browserId != null } == true
        }
        val browserId: String
        val tabId: String
        if (deviceOnly) {
            val opened = requireNotNull(repository.state.value.browser)
            browserId = requireNotNull(opened.browserId)
            assertNull("A new device-only browser must be free", opened.owner)
            assertFalse("Opening must not implicitly acquire control", opened.ownsControl)
            compose.onNodeWithTag("browserAcquire").performClick()
            compose.waitUntil(30_000) { repository.state.value.browser?.ownsControl == true }
            if (repository.state.value.browser!!.tabs.isEmpty()) {
                compose.runOnIdle { assertTrue(repository.newBrowserTab("about:blank")) }
                compose.waitUntil(30_000) { repository.state.value.browser?.tabs?.size == 1 }
            }
            val beforeTabs = repository.state.value.browser!!.tabs.map { it.id }.toSet()
            compose.runOnIdle { assertTrue(repository.newBrowserTab(fixture.getString("pageUrl").toHttpUrl().toString())) }
            compose.waitUntil(30_000) {
                repository.state.value.browser?.let { view ->
                    view.tabs.size > beforeTabs.size && view.selectedTabId != null && view.selectedTabId !in beforeTabs
                } == true
            }
            val openedLogin = requireNotNull(repository.state.value.browser)
            tabId = requireNotNull(openedLogin.selectedTabId)
            assertTrue("A decoy tab must precede the login tab", openedLogin.tabs.size >= 2)
            assertNotEquals("The login tab must not be first", tabId, openedLogin.tabs.first().id)
        } else {
            browserId = requireNotNull(initialBrowserId)
            tabId = requireNotNull(initialTabId)
            if (fixture.optBoolean("reconnect")) {
                compose.waitUntil(30_000) {
                    repository.state.value.browser?.let { it.browserId == browserId && it.tabs.any { tab -> tab.id == tabId } } == true
                }
                assertNotEquals("The login tab must not be first", tabId, repository.state.value.browser!!.tabs.first().id)
                compose.runOnIdle { repository.selectBrowserTab(tabId) }
            }
            compose.waitUntil(30_000) {
                repository.state.value.browser?.let { it.browserId == browserId && it.selectedTabId == tabId } == true
            }
            assertFalse("Pi initially owns the shared browser", repository.state.value.browser?.ownsControl == true)
            val agentGeneration = requireNotNull(repository.state.value.browser).generation
            fixtureAction("handoff-to-device")
            compose.waitUntil(30_000) { status()?.optJSONObject("combined")?.optString("stage") == "handed-to-device" }
            compose.onNodeWithText(compose.activity.getString(R.string.browser_refresh)).performClick()
            compose.waitUntil(30_000) { (repository.state.value.browser?.generation ?: 0) > agentGeneration }
            compose.onNodeWithTag("browserAcquire").performClick()
        }
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
        if (fixture.optBoolean("reconnect")) {
            val pending = fixture.getString("pendingValue")
            val unsent = "Unsent after reconnect ${java.util.UUID.randomUUID()}"
            if (deviceOnly) {
                compose.onNodeWithText(compose.activity.getString(R.string.browser_refresh)).performClick()
                compose.waitUntil(30_000) {
                    status()?.optJSONObject("reconnect")?.optBoolean("deviceOnlyParticipantsVerified") == true &&
                        repository.state.value.browser?.participants?.let { participants ->
                            participants.size == 1 && participants.single().kind == "device"
                        } == true
                }
                val participants = requireNotNull(repository.state.value.browser).participants
                assertEquals("Only the device may participate before the cut", 1, participants.size)
                assertEquals("device", participants.single().kind)
            }
            fixtureAction("arm-input-cut")
            compose.onNodeWithTag("browserText").performTextInput(pending)
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
            fun pendingDiagnostics(): String {
                val browser = repository.state.value.browser
                val snapshot = status()
                val reconnect = snapshot?.optJSONObject("reconnect")
                val combined = snapshot?.optJSONObject("combined")
                val refused = runCatching {
                    compose.onAllNodesWithTag("browserSendRefused").fetchSemanticsNodes().isNotEmpty()
                }.getOrNull()
                return "ownsControl=${browser?.ownsControl}, inputPending=${browser?.inputPending}, " +
                    "notice=${browser?.notice}, moving=${browser?.moving}, receiptSequence=${browser?.receipt?.sequence}, " +
                    "receiptStatus=${browser?.receipt?.status}, imageMatchesViewport=${browser?.image?.let { it.viewportRevision == browser.viewportRevision }}, " +
                    "generation=${browser?.generation}, documentEpoch=${browser?.documentEpoch}, sendRefused=$refused, " +
                    "stage=${reconnect?.optString("stage")}, inputResultHeld=${reconnect?.optBoolean("inputResultHeld")}, " +
                    "inputApplied=${reconnect?.optBoolean("inputApplied")}, appliedCount=${reconnect?.optInt("appliedCount")}, " +
                    "deviceInputRequests=${reconnect?.optInt("deviceInputRequests")}, acquireRequests=${reconnect?.optInt("acquireRequests")}, " +
                    "loginAccepted=${combined?.optBoolean("loginAccepted")}"
            }
            Log.i("BrowserE2E", "Before pending send: ${pendingDiagnostics()}")
            compose.onNodeWithTag("browserSendText").performClick()
            Log.i("BrowserE2E", "After pending send: ${pendingDiagnostics()}")
            try {
                compose.waitUntil(30_000) {
                    status()?.optJSONObject("reconnect")?.let { it.optBoolean("inputResultHeld") && it.optBoolean("inputApplied") } == true &&
                        repository.state.value.browser?.inputPending == true
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val diagnostic = pendingDiagnostics()
                Log.e("BrowserE2E", "Pending send timeout: $diagnostic")
                throw AssertionError("Pending send was not held and applied: $diagnostic", failure)
            }
            compose.onNodeWithTag("browserText").performTextReplacement(unsent)
            fixtureAction("cut-device")
            compose.waitUntil(30_000) {
                val reconnect = status()?.optJSONObject("reconnect")
                !repository.state.value.connected && repository.state.value.browser?.ownsControl == false &&
                    reconnect?.optBoolean("oldOwnerGone") == true && reconnect.optBoolean("oldPeerGone") &&
                    reconnect.optString("stage") == "device-offline"
            }
            compose.onNodeWithTag("browserText").assertTextContains(unsent)
            compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
            val baseline = requireNotNull(status()).getJSONObject("reconnect")
            val inputCount = baseline.getInt("deviceInputRequests")
            val acquireCount = baseline.getInt("acquireRequests")
            fixtureAction("allow-reconnect")
            compose.waitUntil(60_000) {
                val browser = repository.state.value.browser
                repository.state.value.connected && repository.state.value.selection == selection &&
                    browser?.browserId == browserId && browser.selectedTabId == tabId && !browser.ownsControl &&
                    browser.notice == BrowserNotice.UNCERTAIN && browser.image != null
            }
            fixtureAction("observe-reconnect")
            compose.waitUntil(30_000) { status()?.optJSONObject("reconnect")?.optString("stage") == "reconnected-readonly" }
            val restored = requireNotNull(status()).getJSONObject("reconnect")
            assertTrue(restored.getBoolean("newPeerDifferent"))
            assertEquals(inputCount, restored.getInt("deviceInputRequests"))
            assertEquals(acquireCount, restored.getInt("acquireRequests"))
            assertEquals(1, restored.getInt("appliedCount"))
            compose.onNodeWithTag("browserText").assertTextContains(unsent)
            compose.onNodeWithText(compose.activity.getString(R.string.browser_uncertain)).assertExists()
            compose.onNodeWithTag("browserSendText").assertIsNotEnabled()
            if (deviceOnly) assertTrue(baseline.getBoolean("noPiParticipantBeforeCut"))
            val reconnectScreenshot = File(compose.activity.getExternalFilesDir(null), if (deviceOnly) "browser-device-only-reconnect.png" else "browser-reconnect-restored.png")
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(reconnectScreenshot))
            assertTrue(reconnectScreenshot.length() > 0)
            compose.onNodeWithTag("browserAcquire").performClick()
            compose.waitUntil(30_000) {
                repository.state.value.browser?.let { it.ownsControl && it.image?.viewportRevision == it.viewportRevision } == true
            }
            assertEquals(inputCount, requireNotNull(status()).getJSONObject("reconnect").getInt("deviceInputRequests"))
            compose.onNodeWithTag("browserText").assertTextContains(unsent)
        }
        // The device-only fixture first joins Pi after observing the offline gap.
        val agentId = requireNotNull(status()).getJSONObject("combined").getString("agentParticipantId")
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
        if (reconnectExpected) {
            val reconnect = requireNotNull(status()).getJSONObject("reconnect")
            assertEquals(1, reconnect.getInt("cuts"))
            if (deviceOnly) assertTrue(reconnect.getBoolean("noPiParticipantBeforeCut"))
            assertTrue(reconnect.getBoolean("oldOwnerGone"))
            assertTrue(reconnect.getBoolean("oldPeerGone"))
            assertTrue(reconnect.getBoolean("newPeerDifferent"))
            assertEquals(1, reconnect.getInt("appliedCount"))
            assertEquals("reconnected-readonly", reconnect.getString("stage"))
        }
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
