package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Actual Pi and host events through the encrypted transport into production navigation and screens. */
class PrStatusTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private var nativeExpected = false
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private val showNavigation = mutableStateOf(false)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            val expected = args.getString("prStatusFixtureExpected") == "true"
            val nativeArg = args.getString("prStatusNativeExpected")
            require(nativeArg == null || nativeArg == "true" || nativeArg == "false") { "prStatusNativeExpected must be true or false" }
            nativeExpected = nativeArg == "true"
            val path = args.getString("prStatusFixturePath")
            if (expected) require(!path.isNullOrBlank()) { "prStatusFixturePath is required" }
            assumeTrue("Pass prStatusFixturePath to run the actual Pi PR status integration", !path.isNullOrBlank())
            require(expected) { "Explicit PR status fixture mode is required" }
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.optBoolean("prStatusFixture") && fixture.optString("sessionKind") == "actual-pi") { "Wrong PR status fixture" }
            require(fixture.has("nativeWorker") && fixture.getBoolean("nativeWorker") == nativeExpected) { "Wrong native worker fixture mode" }
            println("PR_STATUS_NATIVE_EXPECTED=$nativeExpected")
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            // Preserve hostname verification; trust only the isolated fixture CA.
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "PR status fixture")
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val pairings = object : PairingStorage {
                override fun load() = listOf(host)
                override fun save(hosts: List<PairedHost>) = Unit
            }
            val drafts = object : DraftStorage {
                private var values = emptyMap<DraftKey, StoredDraft>()
                override fun load() = values
                override fun save(drafts: Map<DraftKey, StoredDraft>) { values = drafts }
            }
            repository = DefaultRemoteRepository(pairings, drafts, SecureRemoteTransport(scope, client), scope)
            val settings = PrStatusSettings()
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    if (showNavigation.value) MaterialTheme {
                        RemoteNavigation(repository, settings, false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
                    }
                }
                content
            }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun actualPiStatusUpdatesAndRestoresAcrossReconnect() {
        awaitPhase("pending")
        val initial = status()
        assertTrue("Session must belong to actual Pi child", initial.getBoolean("ownerPidVerified"))
        assertTrue("Native worker must be verified", nativeWorkerVerified(initial))
        assertTrue("Pi version must be reported", initial.getString("piVersion").isNotBlank())
        // This test activity does not install the app lifecycle/network monitor. These signals
        // enable repository reconnection; the actual WebSocket and host remain real.
        compose.runOnIdle {
            repository.setForeground(true)
            repository.setValidatedNetwork("pr-status-fixture-loopback")
        }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        // A project may reuse the verified list fetched by this connection's host fan-out.
        // sessionsFresh deliberately means fetched again for this activation, not row visibility.
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        try {
            compose.waitUntil(60_000) { repository.state.value.selection == selection && repository.state.value.pullRequest?.checks == PullRequestChecksState.RUNNING }
        } catch (failure: Throwable) {
            val state = repository.state.value
            println("PR_STATUS_NAV routeMatch=${state.selection.routeId == selection.routeId} projectMatch=${state.selection.projectId == selection.projectId} sessionMatch=${state.selection.sessionId == selection.sessionId} connected=${state.connected} loading=${state.loading} sessionsFresh=${state.sessionsFresh} sessionPresent=${state.sessions.any { it.optionalText("id") == selection.sessionId }} prPresent=${state.pullRequest != null} running=${state.pullRequest?.checks == PullRequestChecksState.RUNNING}")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(compose.activity.getExternalFilesDir(null), "pr-status-navigation-timeout.png"))
            throw failure
        }
        awaitTag("pullRequestChip")
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertTextContains(compose.activity.getString(R.string.remote_pr_chip_number, 42L), substring = true)
        screenshot("pending")

        action("fail")
        awaitPhase("failed")
        compose.waitUntil(60_000) { repository.state.value.pullRequest?.let { it.failedChecks == 1 && "fixture-build" in it.failingChecks } == true }
        compose.onNodeWithTag("pullRequestChip").assertTextContains(compose.activity.resources.getQuantityString(R.plurals.remote_pr_checks_failing, 1, 1), substring = true).performClick()
        awaitTag("changesPullRequest")
        compose.onNodeWithTag("changesPullRequestFailing", useUnmergedTree = true).assertTextContains("fixture-build", substring = true)
        compose.onNodeWithTag("changesPullRequestOpen").assertIsDisplayed()
        compose.onNodeWithTag("changesPullRequestChecks").assertIsDisplayed()
        screenshot("failed")

        action("pass")
        awaitPhase("passed")
        compose.waitUntil(60_000) { repository.state.value.pullRequest?.let { it.checks == PullRequestChecksState.DONE && it.failedChecks == 0 } == true }
        awaitNoTag("changesPullRequestFailing")
        compose.onNodeWithTag("changesPullRequest").assertIsDisplayed()
        screenshot("passed")

        action("clear")
        awaitPhase("none")
        compose.waitUntil(60_000) { repository.state.value.pullRequest == null }
        awaitNoTag("changesPullRequest")
        screenshot("cleared")

        action("arm-reconnect")
        awaitPhase("pending-reconnect")
        assertNull(repository.state.value.pullRequest)
        val upgrades = status().getInt("upgrades")
        action("cut-device")
        compose.waitUntil(60_000) { statusOrNull()?.let { it.optString("phase") == "cut" && it.optBoolean("phaseReady") && !it.optBoolean("deviceOnline") && it.optBoolean("upgradesBlocked") && nativeWorkerVerified(it) } == true && !repository.state.value.connected }
        action("allow-reconnect")
        compose.waitUntil(90_000) {
            val current = statusOrNull()
            current?.let { it.optString("phase") == "reconnect-allowed" && it.optBoolean("deviceOnline") && it.optInt("upgrades") > upgrades && nativeWorkerVerified(it) } == true &&
                repository.state.value.connected && repository.state.value.selection == selection &&
                repository.state.value.pullRequest?.checks == PullRequestChecksState.RUNNING
        }
        awaitTag("changesPullRequest")
        compose.onNodeWithTag("changesPullRequest").assertIsDisplayed()
        val recovered = status()
        assertTrue(recovered.getBoolean("ownerPidVerified"))
        assertTrue("Native worker must be verified after reconnect", nativeWorkerVerified(recovered))
        screenshot("recovered")
    }

    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitNoTag(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    private fun awaitPhase(phase: String) = compose.waitUntil(60_000) { statusOrNull()?.let { it.optString("phase") == phase && it.optBoolean("phaseReady") && nativeWorkerVerified(it) } == true }
    private fun action(value: String) {
        val body = JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", 200, it.code) }
    }
    private fun readStatus(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use {
        assertTrue(it.isSuccessful); JSONObject(requireNotNull(it.body).string())
    }
    private fun checkNativeMode(status: JSONObject): JSONObject = status.apply {
        assertTrue("Status must report native worker mode", has("nativeWorker"))
        assertEquals("Wrong native worker status mode", nativeExpected, getBoolean("nativeWorker"))
    }
    private fun nativeWorkerVerified(status: JSONObject) = !nativeExpected || status.optBoolean("nativeWorkerVerified")
    private fun status(): JSONObject = checkNativeMode(readStatus())
    private fun statusOrNull(): JSONObject? = runCatching { readStatus() }.getOrNull()?.let { checkNativeMode(it) }
    private fun screenshot(phase: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("Rendered frame must commit before $phase", committed.await(5, TimeUnit.SECONDS))
        val file = File(compose.activity.getExternalFilesDir(null), "pr-status-$phase.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)); assertTrue(file.length() > 0)
    }
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}

private class PrStatusSettings : SettingsRepository {
    override val state = MutableStateFlow(RemoteSettings())
    override fun setTheme(theme: ThemePalette) { state.value = state.value.copy(theme = theme) }
    override fun setThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
    override fun setPushEnabled(enabled: Boolean) { state.value = state.value.copy(pushEnabled = enabled, pushOptedOut = !enabled) }
    override fun markNotificationPromptShown() { state.value = state.value.copy(notificationPromptShown = true) }
    override fun setThinkingDisplay(display: ThinkingDisplay) { state.value = state.value.copy(thinkingDisplay = display) }
    override fun setHideOfflineSessions(hide: Boolean) { state.value = state.value.copy(hideOfflineSessions = hide) }
    override fun setSwipeEndToStart(action: SwipeAction) { state.value = state.value.copy(swipeEndToStart = action) }
    override fun setSwipeStartToEnd(action: SwipeAction) { state.value = state.value.copy(swipeStartToEnd = action) }
    override fun setEnterSends(enabled: Boolean) { state.value = state.value.copy(enterSends = enabled) }
    override fun recordChatAction(action: ChatAction, now: Long) = Unit
    override fun chatActionUsage(): Map<ChatAction, ActionUsage> = emptyMap()
}
