package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Counts actual send attempts while forwarding the secure transport unchanged. */
private class PushAttemptObserver(private val delegate: RemoteTransport) : RemoteTransport {
    val pushes = AtomicInteger()
    @Volatile private var requestId: String? = null
    @Volatile private var sentAt: Long = 0
    @Volatile var replySummary: String = "none"
        private set
    private lateinit var upstream: RemoteTransport.Listener
    private fun allowed(value: String?): String = value?.takeIf { it in setOf(
        "invalid_request", "unsupported", "forbidden", "not_found", "conflict", "busy", "offline",
        "internal", "timeout", "outcome_unknown", "revoked", "rate_limited"
    ) } ?: "other_or_absent"
    private fun boolean(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.toString() ?: "invalid_or_absent"
    override var listener: RemoteTransport.Listener
        get() = upstream
        set(value) {
            upstream = value
            delegate.listener = object : RemoteTransport.Listener by value {
                override fun message(payload: JsonObject) {
                    try {
                        if (payload.optionalText("type") == "result" && payload.optionalText("requestId") == requestId && requestId != null) {
                            val error = payload["error"] as? JsonObject
                            val data = payload["data"] as? JsonObject
                            val kind = if (data?.optionalText("kind") == "git.push") "git.push" else "other_or_absent"
                            val elapsed = android.os.SystemClock.elapsedRealtime() - sentAt
                            replySummary = "elapsedMs=$elapsed,ok=${boolean(payload, "ok")},code=${allowed(error?.optionalText("code"))},reason=${allowed(error?.optionalText("reason"))},kind=$kind,pushed=${data?.let { boolean(it, "pushed") } ?: "invalid_or_absent"}"
                        }
                    } catch (_: Exception) {
                        replySummary = "observation_failed"
                    }
                    value.message(payload)
                }
            }
        }
    override fun connect(host: PairedHost) = delegate.connect(host)
    override fun pair(value: JsonObject) = delegate.pair(value)
    private fun observe(payload: JsonObject) {
        if (payload.optionalText("type") == "session.git.push") {
            pushes.incrementAndGet()
            requestId = payload.optionalText("requestId")
            sentAt = android.os.SystemClock.elapsedRealtime()
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

/** Encrypted push into a disposable HTTPS remote; the registered local session is synthetic. */
class GitPushTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var controller: GitPushReviewController
    private lateinit var observed: PushAttemptObserver

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            val path = args.getString("gitFixturePath")
            if (args.getString("gitPushFixtureExpected") == "true") require(!path.isNullOrBlank()) { "gitFixturePath is required" }
            assumeTrue("Pass gitFixturePath to run the isolated push integration", !path.isNullOrBlank())
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.optBoolean("pushFixture")) { "A push fixture is required" }
            require(fixture.getString("sessionKind") == "fixture-local-peer")
            val expectedDrop = args.getString("gitPushDropExpected")
            require(expectedDrop == "true" || expectedDrop == "false") { "Specify gitPushDropExpected" }
            require(fixture.getBoolean("dropReply") == (expectedDrop == "true")) { "Wrong fixture mode" }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Git fixture")
            val pairings = object : PairingStorage { override fun load() = listOf(host); override fun save(hosts: List<PairedHost>) {} }
            val drafts = object : DraftStorage { private var values = emptyMap<DraftKey, StoredDraft>(); override fun load() = values; override fun save(drafts: Map<DraftKey, StoredDraft>) { values = drafts.toMap() } }
            observed = PushAttemptObserver(SecureRemoteTransport(scope, client))
            repository = DefaultRemoteRepository(pairings, drafts, observed, scope)
            val selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            controller = GitPushReviewController(repository, scope, selection, android.os.SystemClock::elapsedRealtime)
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    val state by repository.state.collectAsState()
                    val review by controller.state.collectAsState()
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            val changes = state.changes
                            if (review.open) GitPushReviewPane(review, GitPushReviewActions(controller::prepare, controller::close, controller::review, controller::backToEdit, controller::confirm, controller::checkRemote, controller::acknowledgeUnknown, controller::resend))
                            else if (changes != null) ChangesPane(changes, ChangesActions(
                                onClose = repository::closeChanges, onSelectBase = repository::selectChangesBase,
                                onReload = repository::reloadChanges, onOpenFile = repository::openChangesFile,
                                onSetComment = repository::setReviewComment, onRemoveComment = repository::removeReviewComment,
                                onUseReview = { false }, onPrefill = { false }),
                                canPushEntry = state.connected && state.capabilitiesKnown && GIT_PUSH_CAPABILITY in state.capabilities,
                                onPreparePush = controller::prepare)
                        }
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

    @Test fun confirmsTargetAndRecoversLostReplyWithoutReplay() {
        val selection = RemoteSelection(fixture.getString("routeId"), fixture.getString("projectId"), fixture.getString("sessionId"))
        compose.runOnIdle {
            repository.setForeground(true)
            // This pane harness does not install the app's normal default-network observer.
            repository.setValidatedNetwork("git-push-fixture-loopback")
            scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) }
        }
        compose.waitUntil(60_000) { repository.state.value.connected && repository.state.value.selection == selection && GIT_PUSH_CAPABILITY in repository.state.value.capabilities }
        compose.runOnIdle { repository.openChanges() }
        compose.waitUntil(60_000) { repository.state.value.changes?.status?.available == true }
        if (compose.onAllNodesWithTag("changesPushPrepare").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("changesMoreActions").performClick()
        compose.onNodeWithTag("changesPushPrepare").performClick()
        compose.waitUntil(60_000) { controller.state.value.preparation != null && !controller.state.value.loading }
        compose.onNodeWithTag("pushReview").performScrollTo().performClick()
        val confirmation = requireNotNull(controller.state.value.confirmation)
        assertEquals(fixture.getString("expectedHead"), confirmation.head)
        assertEquals(fixture.getString("expectedBranch"), confirmation.branch)
        assertEquals(fixture.getString("expectedRemoteHost"), confirmation.target.host)
        assertEquals(fixture.getString("expectedRemoteRepo"), confirmation.target.repo)
        assertEquals(fixture.getString("expectedRemoteBranch"), confirmation.target.branch)
        compose.onNodeWithTag("pushBranch").assertTextEquals(compose.activity.getString(R.string.remote_push_branch, confirmation.branch))
        compose.onNodeWithTag("pushHead").assertTextEquals(compose.activity.getString(R.string.remote_push_head, confirmation.head))
        compose.onNodeWithTag("pushTarget").assertTextEquals(compose.activity.getString(R.string.remote_push_target, confirmation.target.host, confirmation.target.repo))
        compose.onNodeWithTag("pushTarget").performScrollTo().assertIsDisplayed()
        screenshot("target")
        compose.onNodeWithTag("pushHead").performScrollTo().assertIsDisplayed()
        screenshot("head")
        compose.onNodeWithTag("pushConfirm").performScrollTo().assertIsDisplayed()
        screenshot("confirmation")
        compose.onNodeWithTag("pushConfirm").performClick()
        val drop = fixture.getBoolean("dropReply")
        try {
            compose.waitUntil(155_000) { if (drop) controller.state.value.result == GitWriteResult.Push.Unknown else controller.state.value.result is GitWriteResult.Push.Pushed }
        } catch (failure: Throwable) {
            val review = controller.state.value
            val remote = repository.state.value
            val fixtureStatus = runCatching { status() }.getOrNull()
            val fixtureSummary = if (fixtureStatus == null) "unavailable" else
                listOf("remoteMatchesHead", "branchHeadMatches", "indexUnchanged", "stagingUnchanged", "worktreeUnchanged", "replyDropped")
                    .joinToString(",") { key -> "$key=${fixtureStatus.optBoolean(key)}" } +
                    ",pushes=${fixtureStatus.optInt("pushes", -1)},reads=${fixtureStatus.optInt("reads", -1)}"
            throw AssertionError("Push result wait failed: drop=$drop, result=${review.result?.javaClass?.simpleName ?: "none"}, " +
                "error=${review.error?.name ?: "none"}, pending=${review.pending != null}, sending=${review.sending}, " +
                "invalidated=${review.invalidated}, open=${review.open}, connected=${remote.connected}, " +
                "selectionMatches=${remote.selection == selection}, capabilitiesKnown=${remote.capabilitiesKnown}, " +
                "pushCap=${GIT_PUSH_CAPABILITY in remote.capabilities}, statusCap=${GIT_PUSH_STATUS_CAPABILITY in remote.capabilities}, " +
                "sendAttempts=${observed.pushes.get()}, reply=${observed.replySummary}, fixture=$fixtureSummary", failure)
        }
        if (drop) {
            assertNotNull(controller.state.value.pending)
            compose.onNodeWithTag("pushUnknown").performScrollTo().assertIsDisplayed()
            screenshot("unknown")
            try {
                compose.waitUntil(60_000) { repository.state.value.connected && controller.state.value.canCheckRemote }
            } catch (failure: Throwable) {
                val review = controller.state.value
                val remote = repository.state.value
                throw AssertionError("Recovery availability wait failed: connected=${remote.connected}, " +
                    "selectionMatches=${remote.selection == selection}, capabilitiesKnown=${remote.capabilitiesKnown}, " +
                    "statusCap=${GIT_PUSH_STATUS_CAPABILITY in remote.capabilities}, " +
                    "statusUnavailable=${GIT_PUSH_STATUS_CAPABILITY in remote.unavailableCapabilities}, " +
                    "canCheck=${review.canCheckRemote}, pending=${review.pending != null}, open=${review.open}, " +
                    "sending=${review.sending}, checking=${review.checking}, invalidated=${review.invalidated}, " +
                    "result=${review.result?.javaClass?.simpleName ?: "none"}, error=${review.error?.name ?: "none"}, " +
                    "sendAttempts=${observed.pushes.get()}", failure)
            }
            val readsBefore = status().getInt("reads")
            compose.onNodeWithTag("pushCheckRemote").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(95_000) { controller.state.value.recovery?.state == GitPushStatus.MATCHES }
            assertTrue("Explicit recovery must query the remote", status().getInt("reads") > readsBefore)
            compose.onNodeWithTag("pushRecovery").performScrollTo().assertIsDisplayed()
            screenshot("recovery")
        } else {
            val result = controller.state.value.result as GitWriteResult.Push.Pushed
            assertEquals(confirmation.head, result.commit)
            assertEquals(confirmation.branch, result.branch)
            compose.onNodeWithTag("pushSuccess").assertIsDisplayed()
            screenshot("success")
        }
        val status = status()
        for (key in listOf("remoteMatchesHead", "branchHeadMatches", "indexUnchanged", "stagingUnchanged", "worktreeUnchanged")) assertTrue(key, status.getBoolean(key))
        assertEquals(1, status.getInt("pushes"))
        assertEquals(1, observed.pushes.get())
        if (drop) { assertTrue(status.getBoolean("replyDropped")); assertTrue(status.getInt("reads") >= 1) }
    }

    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { response -> assertTrue(response.isSuccessful); JSONObject(requireNotNull(response.body).string()) }
    private fun screenshot(phase: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("A rendered frame must commit before the $phase screenshot", committed.await(5, TimeUnit.SECONDS))
        val file = File(compose.activity.getExternalFilesDir(null), "git-push-$phase.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
        assertTrue(file.length() > 0)
    }
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
