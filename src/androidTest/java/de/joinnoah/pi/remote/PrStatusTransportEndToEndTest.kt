package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private var inputExpected = false
    private var layoutDiagnostics = false
    private var questionDetailsExpected = false
    private var editorExpected = false
    private var nativeEditorExpected = false
    private var nativeEditorReconnectExpected = false
    private var nativeEditorHandoffExpected = false
    private lateinit var observedTransport: PresenceObservingTransport
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
            val inputArg = args.getString("prStatusInputExpected")
            require(inputArg == null || inputArg == "true" || inputArg == "false") { "prStatusInputExpected must be true or false" }
            inputExpected = inputArg == "true"
            require(!inputExpected || nativeExpected) { "prStatusInputExpected requires prStatusNativeExpected=true" }
            val layoutArg = args.getString("prStatusLayoutDiagnostics")
            require(layoutArg == null || layoutArg == "true" || layoutArg == "false") { "prStatusLayoutDiagnostics must be true or false" }
            layoutDiagnostics = layoutArg == "true"
            require(!layoutDiagnostics || inputExpected) { "prStatusLayoutDiagnostics requires prStatusInputExpected=true" }
            val questionArg = args.getString("questionDetailsExpected")
            require(questionArg == null || questionArg == "true" || questionArg == "false") { "questionDetailsExpected must be true or false" }
            questionDetailsExpected = questionArg == "true"
            val editorArg = args.getString("editorExpected")
            require(editorArg == null || editorArg == "true" || editorArg == "false") { "editorExpected must be true or false" }
            editorExpected = editorArg == "true"
            require(!editorExpected || (!nativeExpected && !inputExpected && !questionDetailsExpected)) { "Editor fixture requires external RPC without input or question-details mode" }
            val nativeEditorArg = args.getString("nativeEditorExpected")
            require(nativeEditorArg == null || nativeEditorArg == "true" || nativeEditorArg == "false") { "nativeEditorExpected must be true or false" }
            nativeEditorExpected = nativeEditorArg == "true"
            require(!nativeEditorExpected || (nativeExpected && !inputExpected && !questionDetailsExpected && !editorExpected)) { "Native editor fixture requires native worker without other fixture modes" }
            val nativeEditorReconnectArg = args.getString("nativeEditorReconnectExpected")
            require(nativeEditorReconnectArg == null || nativeEditorReconnectArg == "true" || nativeEditorReconnectArg == "false") { "nativeEditorReconnectExpected must be true or false" }
            nativeEditorReconnectExpected = nativeEditorReconnectArg == "true"
            require(!nativeEditorReconnectExpected || nativeEditorExpected) { "Native editor reconnect requires nativeEditorExpected=true" }
            val nativeEditorHandoffArg = args.getString("nativeEditorHandoffExpected")
            require(nativeEditorHandoffArg == null || nativeEditorHandoffArg == "true" || nativeEditorHandoffArg == "false") { "nativeEditorHandoffExpected must be true or false" }
            nativeEditorHandoffExpected = nativeEditorHandoffArg == "true"
            require(!nativeEditorHandoffExpected || (nativeEditorExpected && !nativeEditorReconnectExpected)) { "Native editor handoff requires native editor mode without reconnect mode" }
            val path = args.getString("prStatusFixturePath")
            if (expected) require(!path.isNullOrBlank()) { "prStatusFixturePath is required" }
            assumeTrue("Pass prStatusFixturePath to run the actual Pi PR status integration", !path.isNullOrBlank())
            require(expected) { "Explicit PR status fixture mode is required" }
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.optBoolean("prStatusFixture") && fixture.optString("sessionKind") == "actual-pi") { "Wrong PR status fixture" }
            require(fixture.has("nativeWorker") && fixture.getBoolean("nativeWorker") == nativeExpected) { "Wrong native worker fixture mode" }
            if (questionDetailsExpected) {
                require(fixture.getBoolean("questionDetailsFixture")) { "Question details fixture is required" }
                require(fixture.getString("questionDetailsKind") in setOf("confirm", "approval")) { "Wrong question details kind" }
                require(fixture.getString("questionProbeCommand").isNotBlank() && fixture.getString("questionProbeNonce").isNotBlank())
                require(fixture.getString("questionExpectedText").toByteArray(Charsets.UTF_8).size > 49_152)
            }
            if (editorExpected) {
                require(fixture.getBoolean("editorFixture")) { "Editor fixture is required" }
                require(fixture.getBoolean("hostRpc")) { "Host-owned RPC is required for the editor fixture" }
                require(fixture.getString("editorOutcome") in setOf("submit", "cancel")) { "Wrong editor outcome" }
                require(fixture.getString("editorProbeCommand").isNotBlank() && fixture.getString("editorProbeNonce").isNotBlank())
                require(fixture.has("editorPrefill") && fixture.has("editorExpectedValue")) { "Editor fixture values are required" }
            }
            if (nativeEditorExpected) {
                require(fixture.getBoolean("nativeEditorFixture")) { "Native editor fixture is required" }
                require(fixture.getString("nativeEditorOutcome") in setOf("submit", "cancel", "local")) { "Wrong native editor outcome" }
                require(fixture.optBoolean("nativeEditorReconnect") == nativeEditorReconnectExpected) { "Wrong native editor reconnect fixture mode" }
                require(!nativeEditorReconnectExpected || fixture.getString("nativeEditorOutcome") == "submit") { "Native editor reconnect requires submit outcome" }
                require(fixture.optBoolean("nativeEditorHandoff") == nativeEditorHandoffExpected) { "Wrong native editor handoff fixture mode" }
                require(!nativeEditorHandoffExpected || (fixture.getString("nativeEditorOutcome") == "submit" && fixture.getBoolean("handoffDeviceDistinct"))) { "Native editor handoff requires submit and a distinct paired device" }
                require(fixture.getString("editorProbeCommand").isNotBlank() && fixture.getString("editorProbeNonce").isNotBlank())
                require(fixture.has("editorPrefill") && fixture.has("editorExpectedValue")) { "Native editor fixture values are required" }
            }
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
            observedTransport = PresenceObservingTransport(SecureRemoteTransport(scope, client), requireNotNull(selection.sessionId))
            repository = DefaultRemoteRepository(pairings, drafts, observedTransport, scope)
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
        if (inputExpected) {
            assertFalse("Input must not be verified before sending", initial.getBoolean("inputVerified"))
            val command = fixture.getString("probeCommand")
            val nonce = fixture.getString("probeNonce")
            require(command.isNotBlank() && nonce.isNotBlank() && !command.contains('\n') && !command.contains('\r') && nonce.none { it.isWhitespace() })
            val probeName = requireNotNull(commandName(command))
            logInputState("before-catalog", probeName)
            try {
                compose.waitUntil(60_000) {
                    val state = repository.state.value
                    state.selection == selection && state.connected && !state.loading &&
                        COMMANDS_CAPABILITY in state.capabilities && COMMANDS_CAPABILITY !in state.unavailableCapabilities &&
                        !state.commandsLoading && state.commands.any { it.name == probeName }
                }
            } catch (failure: Throwable) {
                logInputState("catalog-timeout", probeName)
                runCatching { screenshot("input-timeout") }
                throw failure
            }
            awaitTag("composerField")
            compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $nonce")
            logInputState("before-click", probeName)
            compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
            try {
                compose.waitUntil(60_000) { statusOrNull()?.optBoolean("inputVerified") == true }
            } catch (failure: Throwable) {
                logInputState("input-timeout", probeName)
                throw failure
            }
        }
        if (questionDetailsExpected) exerciseQuestionDetails()
        if (editorExpected) exerciseEditor()
        if (nativeEditorExpected) exerciseNativeEditor()
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
        if (layoutDiagnostics) diagnoseRecoveredLayout()
        else {
            // Reinserted keyed items preserve the reader's scroll position. Bring the card
            // into view like a user would before checking its rendering.
            compose.onNodeWithTag("changesList").performScrollToIndex(0)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("changesPullRequest").assertIsDisplayed()
        val recovered = status()
        assertTrue(recovered.getBoolean("ownerPidVerified"))
        assertTrue("Native worker must be verified after reconnect", nativeWorkerVerified(recovered))
        if (editorExpected) assertTrue("Host-owned RPC must be verified after reconnect", recovered.getBoolean("hostRpcVerified"))
        screenshot("recovered")
    }

    private fun exerciseEditor() {
        val command = fixture.getString("editorProbeCommand")
        val nonce = fixture.getString("editorProbeNonce")
        val probeName = requireNotNull(commandName(command))
        require(nonce.isNotBlank() && nonce.none { it.isWhitespace() })
        val prefill = fixture.getString("editorPrefill")
        val value = fixture.getString("editorExpectedValue")
        val outcome = fixture.getString("editorOutcome")
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading && state.capabilitiesKnown &&
                QUESTION_EDITOR_CAPABILITY in state.capabilities && QUESTION_EDITOR_CAPABILITY !in state.unavailableCapabilities &&
                COMMANDS_CAPABILITY in state.capabilities && COMMANDS_CAPABILITY !in state.unavailableCapabilities &&
                !state.commandsLoading && state.commands.any { it.name == probeName }
        }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $nonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        hideIme()
        compose.waitUntil(60_000) {
            repository.state.value.questions.any { it.text("kind") == "editor" } &&
                statusOrNull()?.let { it.optBoolean("editorVerified") && it.optBoolean("hostRpcVerified") } == true
        }
        val question = repository.state.value.questions.single { it.text("kind") == "editor" }
        val questionId = question.text("id")
        assertTrue("Editor prefill must match the fixture", editorPrefill(question) == prefill)
        compose.onNodeWithTag("questionEditorField").performScrollTo().assertIsDisplayed().assertTextEquals(prefill)
        screenshot("editor-before")
        compose.onNodeWithTag("questionEditorField").performTextReplacement(value)
        compose.onNodeWithTag("questionEditorField").assertTextEquals(value)
        val button = if (outcome == "submit") R.string.remote_send else R.string.remote_cancel
        compose.onNodeWithText(compose.activity.getString(button)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(60_000) {
            val current = statusOrNull()
            current?.let { it.optBoolean("editorVerified") && it.optBoolean("hostRpcVerified") && it.optBoolean("editorAnswered") &&
                it.optBoolean("editorValueVerified") == (outcome == "submit") &&
                it.optBoolean("editorCancelled") == (outcome == "cancel") } == true &&
                repository.state.value.questions.none { it.text("id") == questionId }
        }
        screenshot("editor-after")
    }
    private fun exerciseNativeEditor() {
        val command = fixture.getString("editorProbeCommand")
        val nonce = fixture.getString("editorProbeNonce")
        val prefill = fixture.getString("editorPrefill")
        val value = fixture.getString("editorExpectedValue")
        val outcome = fixture.getString("nativeEditorOutcome")
        require(command.startsWith("/") && command.none { it.isWhitespace() } && nonce.isNotBlank() && nonce.none { it.isWhitespace() })
        require(value != prefill) { "Edited value must differ from prefill" }
        val probeName = requireNotNull(commandName(command))
        awaitTag("composerField")
        compose.onNodeWithTag("composerField").assertIsDisplayed()
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading && state.capabilitiesKnown &&
                QUESTION_EDITOR_CAPABILITY in state.capabilities && NATIVE_EDITOR_CAPABILITY in state.capabilities &&
                QUESTION_EDITOR_CAPABILITY !in state.unavailableCapabilities && NATIVE_EDITOR_CAPABILITY !in state.unavailableCapabilities &&
                COMMANDS_CAPABILITY in state.capabilities && COMMANDS_CAPABILITY !in state.unavailableCapabilities &&
                !state.commandsLoading && state.commands.any { it.name == probeName } && observedTransport.presenceAccepted.get()
        }
        compose.onNodeWithTag("composerField").performTextInput("$command $nonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        hideIme()
        compose.waitUntil(60_000) {
            repository.state.value.questions.any { it.text("kind") == "editor" } &&
                statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                    it.optBoolean("nativeEditorPending") && it.optInt("nativeEditorOriginalCalls", -1) == 0 } == true
        }
        val question = repository.state.value.questions.single { it.text("kind") == "editor" }
        val questionId = question.text("id")
        assertEquals("Native marker must be boolean true", JsonPrimitive(true), question["native"])
        assertEquals("Native editor prefill must match exactly", prefill, editorPrefill(question))
        compose.onNodeWithTag("questionEditorField").performScrollTo().assertIsDisplayed().assertTextEquals(prefill)
        screenshot("native-editor-before")
        if (nativeEditorReconnectExpected) reconnectPendingNativeEditor(questionId, prefill)
        if (nativeEditorHandoffExpected) {
            handoffNativeEditor(questionId, value)
        } else if (outcome == "local") {
            action("native-editor-local-key")
            compose.waitUntil(60_000) {
                statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                    it.optBoolean("nativeEditorLocalReady") && it.optInt("nativeEditorOriginalCalls", -1) == 1 } == true &&
                    repository.state.value.questions.none { it.text("id") == questionId }
            }
            awaitNoTag("questionEditorField")
            observedTransport.sendStaleEditorAnswer(questionId, value)
            compose.waitUntil(60_000) { observedTransport.staleAnswerResult.get() != null }
            val rejection = requireNotNull(observedTransport.staleAnswerResult.get())
            assertFalse("Host must reject the stale editor answer", rejection.flag("ok"))
            assertEquals("Host must report the revoked question as resolved", "already_resolved", rejection.obj("error").text("code"))
            val localHold = status()
            assertTrue("Native worker identity must remain verified", nativeWorkerVerified(localHold))
            assertTrue("Local editor must remain verified and ready", localHold.getBoolean("nativeEditorVerified") && localHold.getBoolean("nativeEditorLocalReady"))
            assertFalse("Stale answer must not resolve the local editor", localHold.getBoolean("nativeEditorAnswered"))
            assertEquals("Exactly one original editor call must remain", 1, localHold.getInt("nativeEditorOriginalCalls"))
            action("native-editor-local-submit")
        } else {
            if (outcome == "submit") {
                compose.onNodeWithTag("questionEditorField").performTextReplacement(value)
                compose.onNodeWithTag("questionEditorField").assertTextEquals(value)
            }
            val button = if (outcome == "submit") R.string.remote_send else R.string.remote_cancel
            compose.onNodeWithText(compose.activity.getString(button)).performScrollTo().assertIsEnabled().performClick()
        }
        compose.waitUntil(60_000) {
            statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                it.optBoolean("nativeEditorAnswered") && it.optBoolean("nativeEditorValueVerified") == (outcome != "cancel") &&
                it.optBoolean("nativeEditorCancelled") == (outcome == "cancel") &&
                it.optInt("nativeEditorOriginalCalls", -1) == if (outcome == "local") 1 else 0 } == true &&
                repository.state.value.questions.none { it.text("id") == questionId }
        }
        screenshot("native-editor-after")
    }
    private fun handoffNativeEditor(questionId: String, value: String) {
        action("native-editor-handoff-start")
        compose.waitUntil(60_000) {
            statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                it.optBoolean("nativeEditorHandoffReady") && it.optBoolean("nativeEditorPending") &&
                !it.optBoolean("nativeEditorAnswered") && it.optInt("nativeEditorOriginalCalls", -1) == 0 } == true
        }
        compose.runOnIdle { showNavigation.value = false }
        awaitNoTag("composerField")
        compose.waitUntil(60_000) { observedTransport.inactivePresenceAccepted.get() && repository.state.value.connected }
        observedTransport.sendStaleEditorAnswer(questionId, value)
        compose.waitUntil(60_000) { observedTransport.staleAnswerResult.get() != null }
        val rejection = requireNotNull(observedTransport.staleAnswerResult.get())
        assertFalse("Host must reject the absent reader's answer", rejection.flag("ok"))
        assertEquals("Host must deny an absent reader's native editor lease", "forbidden", rejection.obj("error").text("code"))
        val pending = status()
        assertTrue("Distinct reader must still hold the verified pending editor", nativeWorkerVerified(pending) &&
            pending.getBoolean("nativeEditorVerified") && pending.getBoolean("nativeEditorHandoffReady") && pending.getBoolean("nativeEditorPending"))
        assertFalse("Absent reader must not answer the editor", pending.getBoolean("nativeEditorAnswered"))
        assertEquals("Original editor must not have run", 0, pending.getInt("nativeEditorOriginalCalls"))
        action("native-editor-handoff-submit")
        compose.waitUntil(60_000) {
            statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                it.optBoolean("nativeEditorHandoffAnswerAccepted") && it.optBoolean("nativeEditorAnswered") &&
                it.optBoolean("nativeEditorValueVerified") && it.optInt("nativeEditorOriginalCalls", -1) == 0 } == true
        }
        compose.runOnIdle { showNavigation.value = true }
        awaitTag("composerField")
        compose.onNodeWithTag("composerField").assertIsDisplayed()
        compose.waitUntil(60_000) { repository.state.value.questions.none { it.optionalText("id") == questionId } }
        awaitNoTag("questionEditorField")
    }
    private fun reconnectPendingNativeEditor(questionId: String, prefill: String) {
        val upgrades = status().getInt("upgrades")
        val started = SystemClock.elapsedRealtime()
        action("native-editor-cut")
        compose.waitUntil(12_000) {
            statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                it.optBoolean("nativeEditorReconnectCut") && !it.optBoolean("deviceOnline") && it.optBoolean("upgradesBlocked") &&
                !it.optBoolean("nativeEditorAnswered") && it.optInt("nativeEditorOriginalCalls", -1) == 0 } == true &&
                !repository.state.value.connected
        }
        check(SystemClock.elapsedRealtime() - started < 15_000) { "Native editor reconnect grace expired before allowing reconnect" }
        action("native-editor-allow-reconnect")
        val remaining = 15_000 - (SystemClock.elapsedRealtime() - started)
        check(remaining > 0) { "Native editor reconnect grace expired" }
        compose.waitUntil(remaining) {
            val state = repository.state.value
            val matching = state.questions.filter { it.optionalText("id") == questionId && it.optionalText("kind") == "editor" }
            statusOrNull()?.let { nativeWorkerVerified(it) && it.optBoolean("nativeEditorVerified") &&
                it.optBoolean("nativeEditorReconnectAllowed") && it.optBoolean("deviceOnline") && it.optInt("upgrades") > upgrades &&
                it.optBoolean("nativeEditorPending") && !it.optBoolean("nativeEditorAnswered") &&
                it.optInt("nativeEditorOriginalCalls", -1) == 0 } == true && state.connected && state.selection == selection &&
                observedTransport.presenceAccepted.get() && matching.size == 1 && matching.single()["native"] == JsonPrimitive(true) &&
                editorPrefill(matching.single()) == prefill
        }
        compose.onNodeWithTag("questionEditorField").performScrollTo().assertIsDisplayed().assertTextEquals(prefill)
        screenshot("native-editor-reconnected")
    }
    private fun exerciseQuestionDetails() {
        val command = fixture.getString("questionProbeCommand")
        val nonce = fixture.getString("questionProbeNonce")
        require(command.startsWith("/") && command.none { it.isWhitespace() } && nonce.none { it.isWhitespace() })
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.capabilitiesKnown && QUESTION_DETAILS_CAPABILITY in state.capabilities &&
                QUESTION_DETAILS_CAPABILITY !in state.unavailableCapabilities && !state.commandsLoading &&
                state.commands.any { it.name == command.removePrefix("/") }
        }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $nonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        hideIme()
        compose.waitUntil(60_000) {
            repository.state.value.questions.any { it.text("kind") == "confirm" && it.containsKey("details") } &&
                statusOrNull()?.optBoolean("questionVerified") == true
        }
        val question = repository.state.value.questions.single { it.text("kind") == "confirm" && it.containsKey("details") }
        val questionId = question.text("id")
        val yesLabel = compose.activity.getString(if (confirmEscalation(question) != null) R.string.remote_escalation_yes else R.string.remote_yes)
        val noLabel = compose.activity.getString(R.string.remote_no)
        val loadLabel = compose.activity.getString(R.string.remote_question_details_load)
        compose.onNodeWithText(yesLabel).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(noLabel).performScrollTo().assertIsEnabled()
        screenshot("details-before")
        compose.onNodeWithText(loadLabel).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(60_000) { repository.state.value.questionDetails[questionId]?.text == fixture.getString("questionExpectedText") }
        assertTrue("Full question must exceed 48 KiB", requireNotNull(repository.state.value.questionDetails[questionId]?.text).toByteArray(Charsets.UTF_8).size > 49_152)
        assertTrue("Question and selection must remain current", repository.state.value.selection == selection && repository.state.value.questions.any { it === question })
        compose.onNodeWithText(yesLabel).performScrollTo().assertIsEnabled()
        screenshot("details-loaded")
        compose.onNodeWithText(yesLabel).performClick()
        compose.waitUntil(60_000) {
            val current = statusOrNull()
            current?.let { it.optBoolean("questionVerified") && it.optBoolean("questionAnswered") && it.optBoolean("questionAccepted") } == true &&
                repository.state.value.questions.none { it.text("id") == questionId }
        }
        if (fixture.getString("questionDetailsKind") == "approval") {
            assertEquals("Approval local UI calls", if (nativeExpected) 1 else 0, status().getInt("questionLocalUiCalls"))
        }
        screenshot("details-answered")
    }
    private fun hideIme() {
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(60_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
        }
    }
    private fun diagnoseRecoveredLayout() {
        screenshot("recovered-unscrolled")
        logChangesLayout("recovered-unscrolled")
        compose.onNodeWithTag("changesList").performScrollToIndex(0)
        compose.waitForIdle()
        logChangesLayout("recovered-top-ime")
        screenshot("recovered-top-ime")
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(60_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
        }
        logChangesLayout("recovered-top-no-ime")
        screenshot("recovered-top-no-ime")
    }
    private fun logChangesLayout(stage: String) {
        val header = compose.onNodeWithTag("changesHeaderPill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("changesPullRequest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cardDisplayed = compose.onNodeWithTag("changesPullRequest").isDisplayed()
        val range = compose.onNodeWithTag("changesList").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        compose.runOnIdle {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            println("PR_STATUS_LAYOUT stage=$stage header=$header card=$card cardDisplayed=$cardDisplayed scrollValue=${range?.value?.invoke()} scrollMax=${range?.maxValue?.invoke()} imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}")
        }
    }
    private fun logInputState(stage: String, probeName: String) {
        val state = repository.state.value
        val fixtureStatus = statusOrNull()
        println("PR_STATUS_INPUT stage=$stage selectionMatch=${state.selection == selection} connected=${state.connected} loading=${state.loading} idle=${state.status == "idle"} running=${state.status == "running"} sending=${state.sending} commandsLoading=${state.commandsLoading} commandsCapability=${COMMANDS_CAPABILITY in state.capabilities} commandsUnavailable=${COMMANDS_CAPABILITY in state.unavailableCapabilities} probeKnown=${state.commands.any { it.name == probeName }} draftPresent=${state.draft.isNotBlank()} commandNoticePresent=${state.commandNotice != null} errorPresent=${state.error != null} fixtureStatusPresent=${fixtureStatus != null} inputVerified=${fixtureStatus?.optBoolean("inputVerified")} nativeWorkerVerified=${fixtureStatus?.optBoolean("nativeWorkerVerified")}")
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

/** Observes the real encrypted transport's decoded messages without changing either direction. */
private class PresenceObservingTransport(
    private val delegate: RemoteTransport,
    private val sessionId: String,
) : RemoteTransport {
    val presenceAccepted = AtomicBoolean(false)
    val inactivePresenceAccepted = AtomicBoolean(false)
    val staleAnswerResult = AtomicReference<JsonObject?>(null)
    private var staleAnswerRequestId: String? = null
    private val pendingPresence = ConcurrentHashMap<String, Boolean>()
    private lateinit var downstreamListener: RemoteTransport.Listener
    override var listener: RemoteTransport.Listener
        get() = downstreamListener
        set(value) {
            downstreamListener = value
            delegate.listener = object : RemoteTransport.Listener by value {
                override fun ready(capabilities: Set<String>) {
                    clearPresence()
                    value.ready(capabilities)
                }
                override fun failed(reconnect: Boolean, error: Int) {
                    clearPresence()
                    value.failed(reconnect, error)
                }
                override fun closed(code: Int) {
                    clearPresence()
                    value.closed(code)
                }
                override fun broken() {
                    clearPresence()
                    value.broken()
                }
                override fun message(payload: JsonObject) {
                    if (payload.optionalText("type") == "result" && payload.optionalText("requestId") == staleAnswerRequestId) {
                        staleAnswerResult.set(payload)
                        return
                    }
                    runCatching {
                        val active = if (payload.optionalText("type") == "result") pendingPresence.remove(payload.text("requestId")) else null
                        if (active != null && payload.flag("ok")) {
                            val data = payload.obj("data")
                            if (data.optionalText("kind") == "accepted" && data.optionalText("sessionId") == sessionId) {
                                if (active) presenceAccepted.set(true) else inactivePresenceAccepted.set(true)
                            }
                        }
                    }
                    value.message(payload)
                }
            }
        }
    override fun connect(host: PairedHost) {
        clearPresence()
        delegate.connect(host)
    }
    override fun pair(value: JsonObject) = delegate.pair(value)
    /** Send one deliberately stale, unprivileged wire request, outside the UI. */
    fun sendStaleEditorAnswer(questionId: String, value: String) {
        check(staleAnswerRequestId == null)
        val requestId = Wire.random()
        staleAnswerRequestId = requestId
        delegate.send(Wire.objectOf(
            "type" to "question.answer", "requestId" to requestId, "sessionId" to sessionId,
            "questionId" to questionId, "answer" to Wire.objectOf("kind" to "editor", "value" to value),
        ))
    }
    override fun send(payload: JsonObject) {
        if (payload.optionalText("type") == "session.editor.presence" && payload.optionalText("sessionId") == sessionId) {
            val active = payload["active"] == JsonPrimitive(true)
            pendingPresence[payload.text("requestId")] = active
            if (!active) { presenceAccepted.set(false); inactivePresenceAccepted.set(false) }
        }
        delegate.send(payload)
    }
    override fun close() {
        clearPresence()
        delegate.close()
    }
    private fun clearPresence() {
        presenceAccepted.set(false)
        inactivePresenceAccepted.set(false)
        pendingPresence.clear()
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
