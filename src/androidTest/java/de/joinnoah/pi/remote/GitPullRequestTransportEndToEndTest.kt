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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Counts actual commands across both secure transports without exposing their payloads. */
private class PrAttemptObserver(private val delegate: RemoteTransport, private val creates: AtomicInteger, private val pushes: AtomicInteger) : RemoteTransport {
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = value }
    override fun connect(host: PairedHost) = delegate.connect(host)
    override fun pair(value: JsonObject) = delegate.pair(value)
    private fun observe(payload: JsonObject) {
        when (payload.optionalText("type")) {
            "session.git.pull_request" -> creates.incrementAndGet()
            "session.git.push" -> pushes.incrementAndGet()
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

/** Synthetic Pi peer and controlled provider over real encrypted transport, never real GitHub. */
class GitPullRequestTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private lateinit var fixture: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var controller: GitPullRequestReviewController
    private val creates = AtomicInteger()
    private val pushes = AtomicInteger()
    private val suffix = UUID.randomUUID().toString()
    private val draftFile = "git-pr-e2e-$suffix.enc"
    private val draftAlias = "git-pr-e2e-$suffix"
    private data class Screen(val repo: DefaultRemoteRepository, val controller: GitPullRequestReviewController)
    private val screen = mutableStateOf<Screen?>(null)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            val path = args.getString("gitFixturePath")
            if (args.getString("gitPrFixtureExpected") == "true") require(!path.isNullOrBlank()) { "gitFixturePath is required" }
            assumeTrue("Pass gitFixturePath to run the isolated PR integration", !path.isNullOrBlank())
            require(args.getString("gitPrFixtureExpected") == "true") { "Explicit PR fixture mode is required" }
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.optBoolean("prFixture") && fixture.getString("sessionKind") == "fixture-local-peer")
            val expectedDrop = args.getString("gitPrDropExpected")
            require(expectedDrop == "true" || expectedDrop == "false") { "Specify gitPrDropExpected" }
            require(fixture.getBoolean("prDropReply") == (expectedDrop == "true")) { "Wrong fixture mode" }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Git fixture")
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            makeRepository()
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    val active = screen.value
                    if (active != null) {
                        val state by active.repo.state.collectAsState()
                        val review by active.controller.state.collectAsState()
                        val repo = active.repo; val ctl = active.controller
                        MaterialTheme {
                            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                val changes = state.changes
                                if (review.open) GitPullRequestReviewPane(review, GitPullRequestReviewActions(ctl::prepare, ctl::close, ctl::setBase, ctl::setTitle, ctl::setBody, ctl::setDraft, ctl::review, ctl::backToEdit, ctl::confirm, ctl::checkRemote, ctl::acknowledgeUnknown))
                                else if (changes != null) ChangesPane(changes, ChangesActions(
                                    onClose = repo::closeChanges, onSelectBase = repo::selectChangesBase,
                                    onReload = repo::reloadChanges, onOpenFile = repo::openChangesFile,
                                    onSetComment = repo::setReviewComment, onRemoveComment = repo::removeReviewComment,
                                    onUseReview = { false }, onPrefill = { false }),
                                    canPullRequestEntry = state.connected && state.capabilitiesKnown &&
                                        (SESSION_GIT_PULL_REQUEST_CAPABILITY in state.capabilities || review.pending != null),
                                    pullRequestPending = review.pending != null, onPreparePullRequest = ctl::prepare)
                            }
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
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.noBackupFilesDir, draftFile).delete()
            File(context.noBackupFilesDir, "$draftFile.bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(draftAlias)) deleteEntry(draftAlias) }
        }
    }).around(compose)

    private fun makeRepository() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pairings = object : PairingStorage { override fun load() = listOf(host); override fun save(hosts: List<PairedHost>) {} }
        val drafts = DraftStore(InstrumentationRegistry.getInstrumentation().targetContext, draftFile, draftAlias)
        repository = DefaultRemoteRepository(pairings, drafts, PrAttemptObserver(SecureRemoteTransport(scope, client), creates, pushes), scope)
        controller = GitPullRequestReviewController(repository, scope, selection)
        screen.value = Screen(repository, controller)
    }

    private fun connectAndOpenChanges() {
        compose.runOnIdle {
            repository.setForeground(true)
            repository.setValidatedNetwork("git-pr-fixture-loopback")
            scope.launch { repository.activate(selection, ActivationMode.USER_OPEN) }
        }
        compose.waitUntil(60_000) { repository.state.value.connected && repository.state.value.selection == selection && repository.state.value.capabilitiesKnown }
        compose.runOnIdle { repository.openChanges() }
        compose.waitUntil(60_000) { repository.state.value.changes?.status?.available == true }
        if (compose.onAllNodesWithTag("changesPullRequestPrepare").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("changesMoreActions").performClick()
        compose.onNodeWithTag("changesPullRequestPrepare").assertIsDisplayed().performClick()
    }

    @Test fun confirmsAndRecoversAcrossEncryptedRepositoryReconstruction() {
        connectAndOpenChanges()
        compose.waitUntil(60_000) { controller.state.value.preparation != null && !controller.state.value.loading }
        compose.onNodeWithTag("prBase").performScrollTo().performClick()
        compose.onNode(hasText(fixture.getString("expectedBase")) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("prTitle").performScrollTo().performTextReplacement(fixture.getString("expectedTitle"))
        compose.onNodeWithTag("prBody").performScrollTo().performTextReplacement(fixture.getString("expectedBody"))
        // Make the choice explicit even if the default changes.
        if (controller.state.value.draft.draft) compose.onNodeWithTag("prDraft").performScrollTo().performClick()
        compose.onNodeWithTag("prReview").performScrollTo().performClick()
        val intent = requireNotNull(controller.state.value.confirmation)
        assertEquals(fixture.getString("expectedHead"), intent.head)
        assertEquals(fixture.getString("expectedBranch"), intent.branch)
        assertEquals(fixture.getString("expectedRemoteHost"), intent.target.host)
        assertEquals(fixture.getString("expectedRemoteRepo"), intent.target.repo)
        assertEquals(fixture.getString("expectedBranch"), intent.target.branch)
        assertEquals(fixture.getString("expectedBase"), intent.base)
        assertEquals(fixture.getString("expectedTitle"), intent.title)
        assertEquals(fixture.getString("expectedBody"), intent.body)
        assertFalse(intent.draft)
        compose.onNodeWithTag("prTarget").assertTextEquals(compose.activity.getString(R.string.pr_target, intent.target.host, intent.target.repo)).performScrollTo().assertIsDisplayed(); screenshot("target")
        compose.onNodeWithTag("prHead").assertTextEquals(compose.activity.getString(R.string.pr_head, intent.head)).performScrollTo().assertIsDisplayed(); screenshot("head")
        compose.onNodeWithTag("prTitle").assertTextEquals(compose.activity.getString(R.string.pr_title_value, intent.title)).performScrollTo().assertIsDisplayed(); screenshot("title")
        compose.onNodeWithTag("prBody").assertTextEquals(compose.activity.getString(R.string.pr_body_value, intent.body)).performScrollTo().assertIsDisplayed(); screenshot("body")
        compose.onNodeWithTag("prConfirm").performScrollTo().assertIsDisplayed(); screenshot("confirmation")
        compose.onNodeWithTag("prConfirm").performClick()
        val drop = fixture.getBoolean("prDropReply")
        compose.waitUntil(155_000) { if (drop) controller.state.value.result == GitWriteResult.PullRequest.Unknown else controller.state.value.result is GitWriteResult.PullRequest.Available }
        if (drop) {
            assertEquals(intent, controller.state.value.pending)
            compose.onNodeWithTag("prUnknown").performScrollTo().assertIsDisplayed(); screenshot("unknown")
            assertTrue(File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, draftFile).length() > 0)
            // Recreate repository, controller, scope and encrypted store; this is not OS process death.
            compose.runOnIdle { repository.setForeground(false); repository.disconnect(); scope.cancel(); makeRepository() }
            connectAndOpenChanges()
            compose.waitUntil(60_000) { controller.state.value.pending == intent && controller.state.value.canCheckRemote }
            assertEquals(GitWriteResult.PullRequest.Unknown, controller.state.value.result)
            assertEquals(1, creates.get()); assertEquals(1, status().getInt("prCreates"))
            val lookups = status().getInt("prLookups")
            compose.onNodeWithTag("prCheckRemote").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(95_000) { controller.state.value.recovery?.state == GitPullRequestStatus.FOUND }
            val found = requireNotNull(controller.state.value.recovery?.pullRequest)
            assertEquals(fixture.getLong("expectedPrNumber"), found.number); assertEquals(fixture.getString("expectedPrUrl"), found.url); assertEquals(intent.base, found.base)
            assertTrue(status().getInt("prLookups") > lookups)
            compose.onNodeWithTag("prRecovery").performScrollTo().assertIsDisplayed(); screenshot("recovery")
        } else {
            val result = controller.state.value.result as GitWriteResult.PullRequest.Available
            assertTrue(result.created); assertEquals(fixture.getLong("expectedPrNumber"), result.number); assertEquals(fixture.getString("expectedPrUrl"), result.url); assertEquals(intent.base, result.base)
            compose.onNodeWithTag("prSuccess").performScrollTo().assertIsDisplayed(); screenshot("success")
        }
        val final = status()
        for (key in listOf("prExists", "prHeadMatches", "branchHeadMatches", "indexUnchanged", "stagingUnchanged", "worktreeUnchanged")) assertTrue(key, final.getBoolean(key))
        assertEquals(1, final.getInt("prCreates")); assertEquals(1, creates.get()); assertEquals(0, pushes.get())
        if (drop) assertTrue(final.getBoolean("prReplyDropped"))
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
        assertTrue("Rendered frame must commit before $phase", committed.await(5, TimeUnit.SECONDS))
        val file = File(compose.activity.getExternalFilesDir(null), "git-pr-$phase.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)); assertTrue(file.length() > 0)
    }
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}
