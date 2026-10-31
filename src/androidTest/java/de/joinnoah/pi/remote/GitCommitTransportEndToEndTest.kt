package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in encrypted transport against a disposable Git repo and synthetic registered session. */
class GitCommitTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var controller: GitCommitReviewController

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val path = InstrumentationRegistry.getArguments().getString("gitFixturePath")
            if (InstrumentationRegistry.getArguments().getString("gitFixtureExpected") == "true") require(!path.isNullOrBlank())
            assumeTrue("Pass gitFixturePath to run the isolated Git integration", !path.isNullOrBlank())
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.getString("sessionKind") == "fixture-local-peer")
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Git fixture")
            val pairings = object : PairingStorage { override fun load() = listOf(host); override fun save(hosts: List<PairedHost>) {} }
            val drafts = object : DraftStorage { private var values = emptyMap<DraftKey, StoredDraft>(); override fun load() = values; override fun save(drafts: Map<DraftKey, StoredDraft>) { values = drafts.toMap() } }
            repository = DefaultRemoteRepository(pairings, drafts, SecureRemoteTransport(scope, client), scope)
            val selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            controller = GitCommitReviewController(repository, scope, selection)
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    val state by repository.state.collectAsState()
                    val review by controller.state.collectAsState()
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            val changes = state.changes
                            if (review.open) GitCommitReviewPane(review, GitCommitReviewActions(controller::close, controller::toggle, controller::setMessage, controller::review, controller::backToEdit, controller::confirm, controller::acknowledgeUnknown))
                            else if (changes != null) ChangesPane(changes, ChangesActions(
                                onClose = repository::closeChanges, onSelectBase = repository::selectChangesBase,
                                onReload = repository::reloadChanges, onOpenFile = repository::openChangesFile,
                                onSetComment = repository::setReviewComment, onRemoveComment = repository::removeReviewComment,
                                onUseReview = { false }, onPrefill = { false }),
                                canCommitEntry = state.connected && GIT_WRITE_CAPABILITY in state.capabilities,
                                onPrepareCommit = controller::prepare)
                        }
                    }
                }
                content
            }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun commitsOnlyExplicitlySelectedFile() {
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle { scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.connected && repository.state.value.selection == selection && GIT_WRITE_CAPABILITY in repository.state.value.capabilities }
        compose.runOnIdle { repository.openChanges() }
        compose.waitUntil(60_000) { repository.state.value.changes?.status?.available == true }
        // The header can move actions to its overflow on a phone.
        if (compose.onAllNodesWithTag("changesCommitPrepare").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("changesMoreActions").performClick()
        compose.onNodeWithTag("changesCommitPrepare").performClick()
        compose.waitUntil(60_000) { controller.state.value.snapshot != null && !controller.state.value.loading }
        compose.onNodeWithTag("commitFile:a.txt").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("commitFile:b.txt").assertIsOff()
        compose.onNodeWithTag("commitMessage").performScrollTo().performTextInput("Fixture selective commit")
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("commitFile:a.txt").performScrollTo().assertIsDisplayed()
        screenshot("selection")
        compose.onNodeWithTag("commitReview").performScrollTo().performClick()
        compose.onNodeWithTag("commitConfirmedMessage").assertTextEquals("Fixture selective commit")
        compose.onNodeWithTag("commitConfirm").performScrollTo().assertIsDisplayed()
        screenshot("confirmation")
        compose.onNodeWithTag("commitConfirm").performScrollTo().performClick()
        compose.waitUntil(60_000) { controller.state.value.result is GitWriteResult.Commit.Committed }
        compose.onNodeWithTag("commitSuccess").assertExists()
        val status = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { response -> assertTrue(response.isSuccessful); JSONObject(requireNotNull(response.body).string()) }
        for (key in listOf("committed", "exactlyA", "aCorrect", "bUnchanged", "bDirty")) assertTrue(key, status.getBoolean(key))
        screenshot("success")
    }

    private fun screenshot(phase: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("A rendered frame must commit before the $phase screenshot", committed.await(5, TimeUnit.SECONDS))
        val file = File(compose.activity.getExternalFilesDir(null), "git-commit-$phase.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
        assertTrue(file.length() > 0)
    }
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
