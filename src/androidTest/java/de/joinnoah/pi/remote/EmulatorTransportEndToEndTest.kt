package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

/** Opt-in: encrypted device transport to an isolated host-owned target AVD, distinct from this app device. */
class EmulatorTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            val path = args.getString("emulatorFixturePath")
            if (args.getString("emulatorExpected") == "true") require(!path.isNullOrBlank()) { "emulatorExpected requires emulatorFixturePath" }
            assumeTrue("Pass emulatorFixturePath to run the isolated target AVD integration test", !path.isNullOrBlank())
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(2, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Isolated emulator fixture")
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
                    val actions = remember { EmulatorPaneActions(repository::selectEmulatorProfile, repository::acquireEmulator, repository::handoffEmulator, repository::stopEmulator, repository::sendEmulatorInput, repository::refreshEmulator, repository::setEmulatorVisible) }
                    MaterialTheme { Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { state.emulator?.let { EmulatorPane(it, actions, repository::closeEmulator) } } }
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

    @Test fun encryptedTransportSharesTargetWithAgentAndRevokesDevice() {
        assertTrue("Fixture must verify the target AVD differs from the app device", requireNotNull(status()).getBoolean("targetDistinct"))
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        var primaryFailure: Throwable? = null
        try {
            compose.runOnIdle {
                repository.setForeground(true)
                repository.setValidatedNetwork("emulator-fixture-loopback")
                scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) }
            }
            compose.waitUntil(60_000) { repository.canOpenEmulator() }
            compose.runOnIdle { assertTrue(repository.openEmulator()) }
            val profileId = fixture.getString("profileId")
            compose.waitUntil(30_000) { repository.state.value.emulator?.let { !it.opening && it.profiles.any { p -> p.id == profileId } } == true }
            val profileLabel = requireNotNull(repository.state.value.emulator).profiles.single { it.id == profileId }.label
            compose.onNodeWithText(profileLabel).performClick()
            compose.waitUntil(180_000) { repository.state.value.emulator?.let { !it.opening && it.profileId == profileId && it.emulatorId != null && it.image != null } == true }
            val reader = requireNotNull(repository.state.value.emulator)
            val emulatorId = requireNotNull(reader.emulatorId)
            assertFalse("Opening joins as a reader", reader.ownsControl)
            assertNull(repository.state.value.browser)
            assertNull(repository.state.value.previewBrowser)
            val before = requireNotNull(status())
            val acquisitions = before.getInt("deviceAcquireRequests")
            val inputs = before.getInt("deviceInputRequests")
            compose.runOnIdle { assertFalse(repository.sendEmulatorInput(EmulatorInput.Key("Home"))); assertTrue(repository.acquireEmulator()) }
            try { compose.waitUntil(60_000) { repository.state.value.emulator?.let { it.ownsControl && it.generation > reader.generation && it.image?.revision == it.geometry?.revision } == true } }
            catch (failure: Exception) {
                val current = repository.state.value
                val v = current.emulator
                throw AssertionError("Acquisition: connected=${current.connected}, owns=${v?.ownsControl}, generation=${v?.generation}, geometry=${v?.geometry?.revision}, image=${v?.image?.revision}, notice=${v?.notice}", failure)
            }
            assertEquals(acquisitions + 1, requireNotNull(status()).getInt("deviceAcquireRequests"))
            fixtureAction("prepare-home")
            compose.onNodeWithText("Home").performClick()
            compose.waitUntil(30_000) { status()?.let { it.optInt("deviceInputRequests") == inputs + 1 && it.optBoolean("homeObserved") && it.optString("deviceInputStatus") == "accepted" } == true && repository.state.value.emulator?.inputPending == false }

            fixtureAction("agent-join")
            compose.waitUntil(30_000) { status()?.optBoolean("agentSnapshotObserved") == true }
            val agent = requireNotNull(status()).getString("agentParticipantId")
            compose.runOnIdle { assertTrue(repository.refreshEmulator()) }
            compose.waitUntil(30_000) { repository.state.value.emulator?.participants?.any { it.kind == "agent" && it.id == agent } == true }
            val deviceGeneration = requireNotNull(repository.state.value.emulator).generation
            compose.runOnIdle { assertTrue(repository.handoffEmulator(agent)) }
            compose.waitUntil(30_000) { status()?.optBoolean("agentOwnsControl") == true && repository.state.value.emulator?.ownsControl == false }
            compose.runOnIdle { assertFalse(repository.sendEmulatorInput(EmulatorInput.Key("Home"))) }
            fixtureAction("agent-handoff-to-device")
            compose.waitUntil(60_000) { repository.state.value.emulator?.let { it.emulatorId == emulatorId && it.generation > deviceGeneration && it.ownsControl && it.image?.revision == it.geometry?.revision } == true }
            if (fixture.optBoolean("reconnect")) {
                val controlled = requireNotNull(repository.state.value.emulator)
                val incarnation = requireNotNull(controlled.incarnation)
                val generation = controlled.generation
                fixtureAction("prepare-home")
                compose.waitUntil(30_000) { status()?.optBoolean("homeObserved") == false }
                fixtureAction("arm-input-cut")
                val armed = requireNotNull(status())
                val cut = armed.getJSONObject("reconnect")
                assertEquals("armed", cut.getString("stage"))
                val inputBaseline = cut.getInt("inputBaseline")
                val acquireBaseline = cut.getInt("acquireBaseline")
                assertEquals(armed.getInt("deviceInputRequests") + 1, inputBaseline)
                assertEquals(armed.getInt("deviceAcquireRequests"), acquireBaseline)
                compose.onNodeWithText("Home").performClick()
                compose.waitUntil(60_000) {
                    val snapshot = status()
                    val reconnect = snapshot?.optJSONObject("reconnect")
                    snapshot?.optBoolean("homeObserved") == true && reconnect?.optString("stage") == "offline" &&
                        reconnect.optInt("appliedInputs") == 1 && !repository.state.value.connected &&
                        repository.state.value.emulator?.ownsControl == false
                }
                compose.runOnIdle { assertFalse(repository.sendEmulatorInput(EmulatorInput.Key("Home"))) }
                val offline = requireNotNull(status())
                assertEquals(inputBaseline, offline.getInt("deviceInputRequests"))
                assertEquals(acquireBaseline, offline.getInt("deviceAcquireRequests"))
                fixtureAction("allow-reconnect")
                compose.waitUntil(120_000) {
                    val state = repository.state.value
                    state.connected && state.selection == selection && state.emulator?.let {
                        it.profileId == profileId && it.emulatorId == emulatorId && it.incarnation == incarnation &&
                            it.generation >= generation && !it.ownsControl && !it.inputPending &&
                            it.image != null && it.image.revision == it.geometry?.revision
                    } == true
                }
                compose.runOnIdle { assertFalse(repository.sendEmulatorInput(EmulatorInput.Key("Home"))) }
                fixtureAction("observe-reconnect")
                compose.waitUntil(30_000) { status()?.optJSONObject("reconnect")?.optString("stage") == "reader" }
                val restored = requireNotNull(status())
                val evidence = restored.getJSONObject("reconnect")
                assertEquals(1, evidence.getInt("cuts"))
                assertEquals(1, evidence.getInt("appliedInputs"))
                assertTrue(evidence.getBoolean("oldOwnerGone"))
                assertTrue(evidence.getBoolean("newPeerDifferent"))
                assertTrue(evidence.getBoolean("sameEmulator"))
                assertTrue(evidence.getBoolean("noReplay"))
                assertEquals(inputBaseline, restored.getInt("deviceInputRequests"))
                assertEquals(acquireBaseline, restored.getInt("deviceAcquireRequests"))
                val restoredGeneration = requireNotNull(repository.state.value.emulator).generation
                compose.runOnIdle { assertTrue(repository.acquireEmulator()) }
                compose.waitUntil(60_000) {
                    repository.state.value.emulator?.let {
                        it.ownsControl && it.generation > restoredGeneration && it.image != null && it.image.revision == it.geometry?.revision
                    } == true
                }
                val reacquired = requireNotNull(status())
                assertEquals(inputBaseline, reacquired.getInt("deviceInputRequests"))
                assertEquals(acquireBaseline + 1, reacquired.getInt("deviceAcquireRequests"))
            }
            val beforeRevoke = requireNotNull(status()).getInt("deviceInputRequests")
            fixtureAction("revoke")
            compose.runOnIdle { repository.refreshEmulator() }
            compose.waitUntil(60_000) { status()?.optBoolean("revoked") == true && (!repository.state.value.connected || repository.state.value.emulator?.ownsControl == false) }
            compose.runOnIdle { assertFalse(repository.sendEmulatorInput(EmulatorInput.Key("Home"))) }
            assertEquals(beforeRevoke, requireNotNull(status()).getInt("deviceInputRequests"))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                compose.runOnIdle { repository.closeEmulator() }
                fixtureAction("cleanup")
                compose.waitUntil(60_000) { status()?.optBoolean("targetStopped") == true }
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun fixtureAction(action: String) {
        val body = JSONObject().put("action", action).toString().toRequestBody("application/json".toMediaType())
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build().newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action must be admitted", 202, it.code) }
    }
    private fun status(): JSONObject? = runCatching {
        client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    }.getOrNull()
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
