package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false)
        }
        val beforeTextReceipt = repository.state.value.browser?.receipt
        compose.onNodeWithTag("browserSendText").performClick()
        compose.waitUntil(30_000) { status()?.optString("input") == submitted }
        var beforeSubmit: Long? = null
        try {
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
        val screenshot = File(compose.activity.getExternalFilesDir(null), "browser-transport-e2e.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(screenshot))
        assertTrue(screenshot.length() > 0)
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
