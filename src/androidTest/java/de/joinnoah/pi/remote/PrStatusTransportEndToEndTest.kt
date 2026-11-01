package de.joinnoah.pi.remote

import android.os.Bundle

import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
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
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
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

private fun pagedDiagnostic(event: String) = println("PAGED_DIAG elapsedMs=${SystemClock.elapsedRealtime()} $event")
private fun pagedTextSummary(value: String?): String {
    if (value == null) return "absent"
    val bytes = value.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return "chars=${value.length},bytes=${bytes.size},sha256=$digest"
}

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
    private var pagedEditorExpected = false
    private var pagedEditorCase: String? = null
    private lateinit var observedTransport: PresenceObservingTransport
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private val mainProbe = Executors.newSingleThreadScheduledExecutor()
    private val mainProbePending = AtomicBoolean(false)
    private val mainProbeEnabled = AtomicBoolean(false)
    private val pagedEditCalls = AtomicInteger()
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
            val pagedArg = args.getString("pagedEditorExpected")
            require(pagedArg == null || pagedArg == "true" || pagedArg == "false") { "pagedEditorExpected must be true or false" }
            pagedEditorExpected = pagedArg == "true"
            pagedEditorCase = args.getString("pagedEditorCase")
            require(if (pagedEditorExpected) (editorExpected || nativeEditorExpected) && !nativeEditorReconnectExpected && !nativeEditorHandoffExpected && pagedEditorCase in setOf("max-unchanged", "max-edited", "empty-expand", "empty-final", "cancel", "lost-ack", "duplicate-commit") else pagedEditorCase == null) { "Paged editor requires a matching supported case and editor mode" }
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
                if (!pagedEditorExpected) require(fixture.has("editorPrefill") && fixture.has("editorExpectedValue")) { "Editor fixture values are required" }
            }
            if (nativeEditorExpected) {
                require(fixture.getBoolean("nativeEditorFixture")) { "Native editor fixture is required" }
                require(fixture.getString("nativeEditorOutcome") in setOf("submit", "cancel", "local")) { "Wrong native editor outcome" }
                require(fixture.optBoolean("nativeEditorReconnect") == nativeEditorReconnectExpected) { "Wrong native editor reconnect fixture mode" }
                require(!nativeEditorReconnectExpected || fixture.getString("nativeEditorOutcome") == "submit") { "Native editor reconnect requires submit outcome" }
                require(fixture.optBoolean("nativeEditorHandoff") == nativeEditorHandoffExpected) { "Wrong native editor handoff fixture mode" }
                require(!nativeEditorHandoffExpected || (fixture.getString("nativeEditorOutcome") == "submit" && fixture.getBoolean("handoffDeviceDistinct"))) { "Native editor handoff requires submit and a distinct paired device" }
                require(fixture.getString("editorProbeCommand").isNotBlank() && fixture.getString("editorProbeNonce").isNotBlank())
                if (!pagedEditorExpected) require(fixture.has("editorPrefill") && fixture.has("editorExpectedValue")) { "Native editor fixture values are required" }
            }
            if (pagedEditorExpected) {
                require(fixture.optBoolean("pagedEditorFixture") && fixture.optString("pagedEditorCase") == pagedEditorCase) { "Wrong paged editor fixture" }
                require(fixture.getString(if (nativeEditorExpected) "nativeEditorOutcome" else "editorOutcome") == if (pagedEditorCase == "cancel") "cancel" else "submit")
                verifyPagedMetadata("Source", pagedSource())
                verifyPagedMetadata("Final", pagedFinal())
            } else require(!fixture.optBoolean("pagedEditorFixture")) { "Paged fixture requires explicit opt-in" }
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
            observedTransport = PresenceObservingTransport(SecureRemoteTransport(scope, client), requireNotNull(selection.sessionId), pagedEditorExpected, pagedEditorCase == "cancel")
            repository = DefaultRemoteRepository(pairings, drafts, observedTransport, scope, pagedEditorTestGate = pagedEditorExpected)
            val observingRepository = object : RemoteRepository by repository {
                override fun editPagedEditor(question: JsonObject, draft: String) {
                    pagedEditCalls.incrementAndGet()
                    fun flags(): String {
                        val state = repository.state.value
                        val view = question.optionalText("id")?.let { state.pagedEditors[it] }
                        return "selectionMatches=${state.selection == selection} connected=${state.connected} loading=${state.loading} generation=${state.editorQuestionGeneration} " +
                            "sameQuestion=${state.questions.any { it === question }} sourcePaged=${question.containsKey("editorSource")} " +
                            "grantPresent=${question.containsKey("editorUploadGrantId")} capability=${QUESTION_PAGED_EDITOR_CAPABILITY in state.capabilities} " +
                            "unavailable=${QUESTION_PAGED_EDITOR_CAPABILITY in state.unavailableCapabilities} viewLoading=${view?.loading} " +
                            "view=${pagedTextSummary(view?.text)}"
                    }
                    if (pagedEditorExpected) pagedDiagnostic("editor-edit-before input=${pagedTextSummary(draft)} ${flags()}")
                    repository.editPagedEditor(question, draft)
                    if (pagedEditorExpected) pagedDiagnostic("editor-edit-after input=${pagedTextSummary(draft)} ${flags()}")
                }
                override fun acquireNativeEditorPresence(selection: RemoteSelection): () -> Unit {
                    if (pagedEditorExpected) pagedDiagnostic("presence-owner-acquire selectionMatches=${selection == this@PrStatusTransportEndToEndTest.selection}")
                    val release = repository.acquireNativeEditorPresence(selection)
                    return {
                        if (pagedEditorExpected) pagedDiagnostic("presence-owner-dispose")
                        release()
                    }
                }
            }
            if (pagedEditorExpected) scope.launch {
                var previous: Pair<Boolean, Boolean>? = null
                repository.state.collect { state ->
                    val current = (state.selection == selection) to state.connected
                    if (current != previous) {
                        previous = current
                        pagedDiagnostic("state selectionMatches=${current.first} connected=${current.second}")
                    }
                }
            }
            val settings = PrStatusSettings()
            NavigationTestActivity.contentFactory = {
                val content: @Composable () -> Unit = {
                    if (showNavigation.value) MaterialTheme {
                        RemoteNavigation(observingRepository, settings, false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
                    }
                }
                content
            }
        }
        override fun after() {
            mainProbeEnabled.set(false)
            mainProbe.shutdownNow()
            if (pagedEditorExpected) pagedDiagnostic("fixture-teardown foreground=false")
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
            if (pagedEditorExpected) pagedDiagnostic("fixture-foreground=true")
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
        if (pagedEditorExpected) exercisePagedEditor()
        else if (editorExpected) exerciseEditor()
        else if (nativeEditorExpected) exerciseNativeEditor()
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

    private fun pagedSource(): String = when (pagedEditorCase) {
        "max-unchanged", "max-edited", "cancel", "lost-ack", "duplicate-commit" -> "😀".repeat(262_144)
        "empty-expand" -> ""
        "empty-final" -> "draft"
        else -> error("Unsupported paged editor case")
    }

    private fun pagedFinal(): String = when (pagedEditorCase) {
        "max-unchanged", "cancel", "lost-ack", "duplicate-commit" -> pagedSource()
        "max-edited" -> "é\\\n".repeat(1_024) + "😀".repeat(261_120)
        "empty-expand" -> "é\\\n".repeat(262_144)
        "empty-final" -> ""
        else -> error("Unsupported paged editor case")
    }

    private fun verifyPagedMetadata(label: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("$label UTF-8 byte count", bytes.size, fixture.getInt("pagedEditor${label}Bytes"))
        assertEquals("$label SHA-256", digest, fixture.getString("pagedEditor${label}Sha256"))
    }

    // Diagnostics only: position is read from the rendered range label, never the editor text.
    private fun pagedEditorObservation(stage: String, question: JsonObject, id: String, expected: String? = null) {
        if (pagedEditorCase != "max-edited") return
        val state = repository.state.value
        val current = state.questions.firstOrNull { it.optionalText("id") == id }
        val draft = state.pagedEditors[id]?.text
        val firstDifference = if (draft == null || expected == null) null else {
            val common = minOf(draft.length, expected.length)
            (0 until common).firstOrNull { draft[it] != expected[it] }
                ?: if (draft.length != expected.length) common else -1
        }
        val window = runCatching {
            val ranges = compose.onAllNodes(hasText("–", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .mapNotNull { Regex("([0-9]+)–([0-9]+)").find(it.text)?.value }
            ranges.singleOrNull() ?: "unavailable"
        }.getOrElse { "unavailable" }
        val field = runCatching {
            compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.EditableText)?.text
        }.getOrNull()
        pagedDiagnostic("editor-observation stage=$stage sameQuestion=${current === question} " +
            "questionPresent=${current != null} sameGrant=${current?.optionalText("editorUploadGrantId") == question.optionalText("editorUploadGrantId")} " +
            "sameSource=${current?.get("editorSource") == question["editorSource"]} generation=${state.editorQuestionGeneration} " +
            "window=$window field=${pagedTextSummary(field)} draft=${pagedTextSummary(draft)} " +
            "expected=${pagedTextSummary(expected)} firstDifferenceUtf16=$firstDifference calls=${pagedEditCalls.get()}")
    }

    private fun exercisePagedEditor() {
        val command = fixture.getString("editorProbeCommand")
        val nonce = fixture.getString("editorProbeNonce")
        val probeName = requireNotNull(commandName(command))
        require(command.startsWith("/") && command.none { it.isWhitespace() } && nonce.isNotBlank() && nonce.none { it.isWhitespace() })
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading && state.capabilitiesKnown &&
                QUESTION_EDITOR_CAPABILITY in state.capabilities && QUESTION_PAGED_EDITOR_CAPABILITY in state.capabilities &&
                QUESTION_PAGED_EDITOR_CAPABILITY !in state.unavailableCapabilities &&
                (!nativeEditorExpected || NATIVE_EDITOR_CAPABILITY in state.capabilities && observedTransport.presenceAccepted.get()) &&
                COMMANDS_CAPABILITY in state.capabilities && !state.commandsLoading && state.commands.any { it.name == probeName } &&
                observedTransport.pagedV3Accepted.get()
        }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $nonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        hideIme()
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id")
        assertTrue("Host must issue a grant", question.text("editorUploadGrantId").matches(Regex("[A-Za-z0-9_-]{1,256}")))
        assertEquals("Source representation must match the case", pagedEditorCase in setOf("max-unchanged", "max-edited", "cancel", "lost-ack", "duplicate-commit"), question.containsKey("editorSource"))
        startPagedMainProbe()
        pagedDiagnostic("load-click-before")
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        pagedDiagnostic("load-click-after")
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        pagedDiagnostic("source-ready")
        val loaded = requireNotNull(repository.state.value.pagedEditors[id]?.text)
        assertTrue("Question incarnation must remain current", repository.state.value.questions.any { it === question })
        assertEquals("Exact loaded source", pagedSource(), loaded)
        verifyPagedMetadata("Source", loaded)
        pagedDiagnostic("field-query-before")
        compose.onNodeWithTag("questionEditorField").performScrollTo().assertIsDisplayed()
        pagedDiagnostic("field-query-after")
        // Avoid sending a megabyte through Binder: the loaded state above is byte/digest checked.
        // The field is rendered and its actual semantics expose the beginning and end markers.
        val field = compose.onNodeWithTag("questionEditorField")
        val marker = when (pagedEditorCase) { "empty-expand" -> ""; "empty-final" -> "draft"; else -> "😀😀" }
        val renderedText = requireNotNull(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
        assertTrue("Rendered first window must be bounded and expose its marker", renderedText.length <= EditorWindows.MAX_UNITS && renderedText.startsWith(marker))
        if (pagedEditorCase in setOf("max-unchanged", "max-edited", "cancel", "lost-ack", "duplicate-commit")) {
            pagedEditorObservation("before-last", question, id)
            compose.onNodeWithTag("pagedEditorLast").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("pagedEditorLast").assertIsNotEnabled()
            pagedEditorObservation("after-last", question, id)
            val lastText = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
            assertTrue("Rendered last window must be bounded and expose its marker", lastText.length <= EditorWindows.MAX_UNITS && lastText.endsWith(marker))
            pagedEditorObservation("before-first", question, id)
            compose.onNodeWithTag("pagedEditorFirst").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("pagedEditorFirst").assertIsNotEnabled()
            pagedEditorObservation("after-first", question, id)
        }
        screenshot("paged-editor-before")
        val expectedFinal = pagedFinal()
        val editCallsBefore = pagedEditCalls.get()
        val stateBeforeInput = repository.state.value
        pagedDiagnostic("editor-action-before calls=$editCallsBefore connected=${stateBeforeInput.connected} loading=${stateBeforeInput.loading} " +
            "sameQuestion=${stateBeforeInput.questions.any { it === question }} generation=${stateBeforeInput.editorQuestionGeneration}")
        pagedEditorObservation("before-replacement", question, id, expectedFinal)
        if (pagedEditorCase == "empty-final") {
            field.performTextReplacement("")
            field.assertTextEquals("")
            compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == "" }
        }
        if (pagedEditorCase == "max-edited") field.performTextReplacement("é\\\n".repeat(1_024))
        if (pagedEditorCase == "empty-expand") field.performTextReplacement(expectedFinal)
        compose.waitForIdle()
        val renderedAfter = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        val stateAfterInput = repository.state.value
        val draftAfter = stateAfterInput.pagedEditors[id]?.text
        pagedDiagnostic("editor-after-input callsBefore=$editCallsBefore callsAfter=${pagedEditCalls.get()} connected=${stateAfterInput.connected} loading=${stateAfterInput.loading} " +
            "sameQuestion=${stateAfterInput.questions.any { it === question }} generation=${stateAfterInput.editorQuestionGeneration} " +
            "field=${pagedTextSummary(renderedAfter)} draft=${pagedTextSummary(draftAfter)} expected=${pagedTextSummary(expectedFinal)}")
        pagedEditorObservation("after-replacement", question, id, expectedFinal)
        check(draftAfter == expectedFinal) { "Editor draft did not match expected value after UI input; see anonymous PAGED_DIAG summaries" }
        val finalDraft = requireNotNull(repository.state.value.pagedEditors[id]?.text)
        assertEquals("Exact full editor draft", expectedFinal, finalDraft)
        verifyPagedMetadata("Final", finalDraft)
        if (pagedEditorCase in setOf("max-edited", "empty-expand")) {
            compose.onNodeWithTag("pagedEditorFirst").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("pagedEditorFirst").assertIsNotEnabled()
            compose.onNodeWithTag("pagedEditorLast").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("pagedEditorLast").assertIsNotEnabled()
            val lastText = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
            val endMarker = if (pagedEditorCase == "max-edited") "😀😀" else "é\\\n"
            assertTrue("Edited last window remains bounded and exact", lastText.length <= EditorWindows.MAX_UNITS && lastText.endsWith(endMarker))
            compose.onNodeWithTag("pagedEditorFirst").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("pagedEditorFirst").assertIsNotEnabled()
            val firstText = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
            assertTrue("Edited first window remains bounded and exact", firstText.length <= EditorWindows.MAX_UNITS && firstText.startsWith("é\\\n"))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        if (pagedEditorCase == "cancel") {
            exercisePagedCancel(id)
        } else if (pagedEditorCase == "lost-ack") {
            exercisePagedLostAck(id, question.text("editorUploadGrantId"))
        } else if (pagedEditorCase == "duplicate-commit") {
            exercisePagedDuplicate(id, question.text("editorUploadGrantId"))
        } else compose.waitUntil(120_000) {
            statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") &&
                it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                observedTransport.pagedCommitAccepted(id, question.text("editorUploadGrantId"), fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256")) &&
                repository.state.value.questions.none { it.optionalText("id") == id }
        }
        mainProbeEnabled.set(false)
        screenshot("paged-editor-after")
    }

    private fun exercisePagedDuplicate(id: String, grantId: String) {
        compose.waitUntil(120_000) { statusOrNull()?.optBoolean("pagedCommitHeld") == true && observedTransport.firstCommitId.get() != null }
        val duplicate = observedTransport.sendDuplicate(scope)
        var barrierFailure: Throwable? = null
        try {
            compose.waitUntil(5_000) { observedTransport.commitSendFinished(duplicate) }
            observedTransport.requireCommitSendSucceeded(duplicate)
            val held = status()
            assertTrue("Owner remains held after the second guarded phone send completes", held.getBoolean("pagedCommitHeld") && !held.getBoolean("pagedGateReleased") && !held.getBoolean("pagedGateTimedOut"))
        } catch (error: Throwable) {
            barrierFailure = error
            throw error
        } finally {
            try { action("release-paged-commit") }
            catch (release: Throwable) {
                if (barrierFailure != null) barrierFailure.addSuppressed(release) else throw release
            }
        }
        compose.waitUntil(120_000) {
            statusOrNull()?.let { it.optBoolean("pagedGateReleased") && !it.optBoolean("pagedGateTimedOut") &&
                it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") &&
                it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                observedTransport.pagedCommitAccepted(id, grantId, fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256")) &&
                observedTransport.commitNotFound(duplicate) && repository.state.value.questions.none { it.optionalText("id") == id }
        }
        val afterTerminal = observedTransport.sendDuplicate(scope)
        compose.waitUntil(5_000) { observedTransport.commitSendFinished(afterTerminal) }
        observedTransport.requireCommitSendSucceeded(afterTerminal)
        compose.waitUntil(30_000) { observedTransport.commitNotFound(afterTerminal) }
        observeNoPagedRetry()
        assertTrue("Original byte and hash proof remains valid", observedTransport.pagedCommitAccepted(id, grantId, fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256")))
        assertEquals("Exactly one owner dispatch", 1, status().getInt("pagedCommitCount"))
        assertTrue("Exactly one matching SDK settlement", status().getBoolean("pagedSdkResultVerified"))
        assertEquals("Original plus two distinct phone duplicate requests", 3, observedTransport.uploadCounts().third)
    }

    private fun observeNoPagedRetry() {
        val before = observedTransport.uploadCounts()
        val start = SystemClock.elapsedRealtime()
        compose.waitUntil(5_000) { SystemClock.elapsedRealtime() - start >= 3_000 }
        assertEquals("No automatic upload replay", before, observedTransport.uploadCounts())
    }

    private fun exercisePagedCancel(id: String) {
        compose.waitUntil(60_000) { observedTransport.firstChunkAccepted.get() && observedTransport.secondChunkHeld.get() }
        val collecting = statusWithFreshUploadStats().getJSONObject("pagedUploadStats")
        assertEquals("One actual Host reservation must exist", 1, collecting.getInt("count"))
        assertEquals(1_048_576, collecting.getInt("bytes"))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_cancel)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            val s = statusWithFreshUploadStats()
            val stats = s.getJSONObject("pagedUploadStats")
            s.optBoolean("pagedSdkResultVerified") && s.optInt("pagedCommitCount", -1) == 0 &&
                stats.getInt("count") == 0 && stats.getInt("bytes") == 0 && repository.state.value.questions.none { it.optionalText("id") == id }
        }
        observedTransport.releaseSecondChunk.complete(Unit)
        observeNoPagedRetry()
        assertEquals("Only first chunk may reach the transport", Triple(1, 1, 0), observedTransport.uploadCounts())
        assertTrue("No owner commit may occur", !status().getBoolean("pagedCommitDispatched") && status().getInt("pagedCommitCount") == 0)
    }

    private fun exercisePagedLostAck(id: String, grantId: String) {
        compose.waitUntil(120_000) {
            statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") &&
                it.optBoolean("pagedAckVerified") && it.optBoolean("pagedLostAckInjected") &&
                it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                observedTransport.pagedCommitUnknown(id, grantId, fixture.getInt("pagedEditorFinalBytes"), fixture.getString("pagedEditorFinalSha256")) &&
                repository.state.value.questions.none { it.optionalText("id") == id }
        }
        observeNoPagedRetry()
        assertEquals("One actual commit", 1, observedTransport.uploadCounts().third)
        val after = status()
        assertEquals("One source settlement", 1, after.getInt("pagedCommitCount"))
        assertTrue("The source must still have exactly one matching SDK result", after.getBoolean("pagedSdkResultVerified"))
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
    private fun startPagedMainProbe() {
        val handler = Handler(Looper.getMainLooper())
        mainProbeEnabled.set(true)
        mainProbe.scheduleAtFixedRate({
            if (mainProbeEnabled.get() && mainProbePending.compareAndSet(false, true)) {
                val posted = SystemClock.elapsedRealtime()
                handler.post {
                    val delayMs = SystemClock.elapsedRealtime() - posted
                    if (delayMs >= 250) pagedDiagnostic("main-queue-delay-ms=$delayMs")
                    mainProbePending.set(false)
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
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
    private fun statusWithFreshUploadStats(): JSONObject {
        fun checkAge() {
            val monotonicAge = SystemClock.elapsedRealtime() - observedTransport.beginElapsed.get()
            val wallAge = System.currentTimeMillis() - observedTransport.beginWall.get()
            val ackMonotonicAge = SystemClock.elapsedRealtime() - observedTransport.firstAckElapsed.get()
            val ackWallAge = System.currentTimeMillis() - observedTransport.firstAckWall.get()
            check(observedTransport.beginElapsed.get() >= 0 && observedTransport.beginWall.get() >= 0 &&
                observedTransport.firstAckElapsed.get() >= observedTransport.beginElapsed.get() && observedTransport.firstAckWall.get() >= observedTransport.beginWall.get() &&
                monotonicAge in 0 until 5_000 && wallAge in 0 until 5_000 && ackMonotonicAge in 0 until 5_000 && ackWallAge in 0 until 5_000) { "Upload stats observation exceeded the fresh-transfer window" }
            pagedDiagnostic("stats-age beginElapsedMs=$monotonicAge beginWallMs=$wallAge ackElapsedMs=$ackMonotonicAge ackWallMs=$ackWallAge")
        }
        checkAge()
        val result = client.newCall(Request.Builder().url(fixture.getString("statusUrl") + "?stats=1").build()).execute().use {
            assertTrue(it.isSuccessful); JSONObject(requireNotNull(it.body).string())
        }
        checkAge()
        return checkNativeMode(result)
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
internal class PresenceObservingTransport(
    private val delegate: RemoteTransport,
    private val sessionId: String,
    private val pagedDiagnostics: Boolean,
    private val holdSecondChunk: Boolean,
    private val holdPositiveRelease: Boolean = false,
) : RemoteTransport {
    val presenceAccepted = AtomicBoolean(false)
    val inactivePresenceAccepted = AtomicBoolean(false)
    val staleAnswerResult = AtomicReference<JsonObject?>(null)
    val pagedV3Accepted = AtomicBoolean(false)
    val acceptedSnapshotQuestions = AtomicReference<Set<String>?>(null)
    val questionAnswerAttempts = AtomicInteger()
    val positiveOutcomeQueries = AtomicInteger()
    val positiveOutcomeReleaseAttempts = AtomicInteger()
    val positiveOutcomeApplied = AtomicReference<JsonObject?>(null)
    val positiveOutcomeReleased = AtomicBoolean(false)
    val positiveReleaseHeld = AtomicBoolean(false)
    val releasePositiveRelease = CompletableDeferred<Unit>()
    val positiveOutcomeReleaseReceipt = AtomicReference<JsonObject?>(null)
    private val positiveOutcomeRequests = ConcurrentHashMap<String, JsonObject>()
    private val positiveOutcomeReleaseRequests = ConcurrentHashMap.newKeySet<String>()
    private val pendingSnapshots = ConcurrentHashMap.newKeySet<String>()
    val firstChunkAccepted = AtomicBoolean(false)
    val secondChunkHeld = AtomicBoolean(false)
    val releaseSecondChunk = CompletableDeferred<Unit>()
    val beginElapsed = AtomicLong(-1)
    val beginWall = AtomicLong(-1)
    val firstAckElapsed = AtomicLong(-1)
    val firstAckWall = AtomicLong(-1)
    private val uploadBegins = AtomicInteger()
    private val uploadChunks = AtomicInteger()
    private val uploadCommits = AtomicInteger()
    private val pagedV3Requests = ConcurrentHashMap.newKeySet<String>()
    private data class UploadSnapshot(val bytes: Int, val valid: Boolean, val sha256: String)
    private data class UploadObservation(val begin: JsonObject, val digest: MessageDigest = MessageDigest.getInstance("SHA-256"), var bytes: Int = 0, var valid: Boolean = true, var snapshot: UploadSnapshot? = null)
    private data class PendingCommit(val upload: UploadObservation, val request: JsonObject, val snapshot: UploadSnapshot)
    private data class CommitObservation(val pending: PendingCommit, val result: JsonObject)
    private val pendingBegins = ConcurrentHashMap<String, JsonObject>()
    private val observedUploads = ConcurrentHashMap<String, UploadObservation>()
    private val pendingCommits = ConcurrentHashMap<String, PendingCommit>()
    private val observedCommit = AtomicReference<CommitObservation?>(null)
    val firstCommitId = AtomicReference<String?>(null)
    private val firstCommitPayload = AtomicReference<JsonObject?>(null)
    private val commitLock = Any()
    private val knownCommitIds = mutableSetOf<String>()
    private val commitResults = mutableMapOf<String, CommitObservation>()
    private val commitSendResults = mutableMapOf<String, Result<Unit>>()
    private data class UploadTrace(val alias: Int, val kind: String, val offset: Long?)
    private val uploadTraceSequence = AtomicInteger()
    private val uploadRequests = ConcurrentHashMap<String, UploadTrace>()
    private var staleAnswerRequestId: String? = null
    private val pendingPresence = ConcurrentHashMap<String, Boolean>()
    private lateinit var downstreamListener: RemoteTransport.Listener
    override var listener: RemoteTransport.Listener
        get() = downstreamListener
        set(value) {
            downstreamListener = value
            delegate.listener = object : RemoteTransport.Listener by value {
                override fun ready(capabilities: Set<String>) {
                    if (pagedDiagnostics) pagedDiagnostic("transport-ready")
                    clearPresence()
                    value.ready(capabilities)
                }
                override fun failed(reconnect: Boolean, error: Int) {
                    if (pagedDiagnostics) pagedDiagnostic("transport-failed reconnect=$reconnect")
                    clearPresence()
                    value.failed(reconnect, error)
                }
                override fun closed(code: Int) {
                    if (pagedDiagnostics) pagedDiagnostic("transport-closed code=$code")
                    clearPresence()
                    value.closed(code)
                }
                override fun broken() {
                    if (pagedDiagnostics) pagedDiagnostic("transport-broken")
                    clearPresence()
                    value.broken()
                }
                override fun message(payload: JsonObject) {
                    val resultId = payload.optionalText("requestId")
                    if (payload.optionalText("type") == "result" && resultId != null) {
                        positiveOutcomeRequests.remove(resultId)?.let { expected -> runCatching {
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("positiveOutcomeDiagnostic", "query-result ok=${payload.flag("ok")} kind=${payload.obj(if (payload.flag("ok")) "data" else "error").optionalText(if (payload.flag("ok")) "kind" else "code")}") })
                            val data = payload.obj("data")
                            if (payload.flag("ok") && data.keys == setOf("kind", "status", "receipt") && data.text("kind") == "editor_outcome" && data.text("status") == "applied" && data.obj("receipt") == expected) positiveOutcomeApplied.set(data)
                        } }
                        if (positiveOutcomeReleaseRequests.remove(resultId)) runCatching {
                            if (payload.flag("ok") && payload.obj("data").keys == setOf("kind") && payload.obj("data").text("kind") == "editor_outcome_released") positiveOutcomeReleased.set(true)
                        }
                        if (pendingSnapshots.remove(resultId)) runCatching {
                            if (payload.flag("ok")) {
                                val data = payload.obj("data")
                                check(data.text("kind") == "snapshot" && data.text("sessionId") == sessionId)
                                val questions = data["pendingQuestions"] as JsonArray
                                acceptedSnapshotQuestions.set(questions.map { (it as JsonObject).text("id") }.toSet())
                            }
                        }
                        uploadRequests.remove(resultId)?.let { trace ->
                            val ok = runCatching { payload.flag("ok") }.getOrDefault(false)
                            val rawKind = if (ok) runCatching { payload.obj("data").optionalText("kind") }.getOrNull() else null
                            val kind = rawKind?.takeIf { it in setOf("editor_upload_begun", "editor_upload_ack", "accepted") } ?: "other"
                            val rawCode = if (!ok) runCatching { payload.obj("error").optionalText("code") }.getOrNull() else null
                            val code = rawCode?.takeIf { it in setOf("invalid_request", "forbidden", "unsupported", "outcome_unknown", "not_found", "unavailable") } ?: "other"
                            pagedDiagnostic("upload-result alias=${trace.alias} kind=${trace.kind} offset=${trace.offset} ok=$ok resultKind=$kind errorCode=$code")
                            if (trace.kind == "chunk" && trace.offset == 0L && ok) runCatching {
                                val data = payload.obj("data")
                                val upload = observedUploads[data.text("uploadId")]
                                if (upload != null && data.text("kind") == "editor_upload_ack" && data.text("sessionId") == sessionId &&
                                    data.text("questionId") == upload.begin.text("questionId") && data.long("nextOffset") == EDITOR_UPLOAD_CHUNK_BYTES.toLong()) {
                                    firstAckElapsed.compareAndSet(-1, SystemClock.elapsedRealtime())
                                    firstAckWall.compareAndSet(-1, System.currentTimeMillis())
                                    firstChunkAccepted.set(true)
                                }
                            }
                        }
                        pendingBegins.remove(resultId)?.let { begin ->
                            runCatching {
                                if (payload.flag("ok")) {
                                    val data = payload.obj("data")
                                    if (data.text("kind") == "editor_upload_begun" && data.text("sessionId") == sessionId && data.text("questionId") == begin.text("questionId")) {
                                        observedUploads[data.text("uploadId")] = UploadObservation(begin)
                                    }
                                }
                            }
                        }
                        pendingCommits.remove(resultId)?.let { pending ->
                            val receipt = CommitObservation(pending, payload)
                            if (resultId == firstCommitId.get()) observedCommit.compareAndSet(null, receipt)
                            synchronized(commitLock) {
                                if (resultId in knownCommitIds) commitResults[resultId] = receipt
                            }
                        }
                    }
                    if (payload.optionalText("type") == "result" && resultId != null && pagedV3Requests.remove(resultId) && payload.flag("ok")) {
                        pagedV3Accepted.set(true)
                    }
                    if (payload.optionalText("type") == "result" && payload.optionalText("requestId") == staleAnswerRequestId) {
                        staleAnswerResult.set(payload)
                        return
                    }
                    runCatching {
                        val active = if (payload.optionalText("type") == "result") pendingPresence.remove(payload.text("requestId")) else null
                        if (active != null && payload.flag("ok")) {
                            val data = payload.obj("data")
                            if (pagedDiagnostics) pagedDiagnostic("presence-result active=$active accepted=${data.optionalText("kind") == "accepted" && data.optionalText("sessionId") == sessionId}")
                            if (data.optionalText("kind") == "accepted" && data.optionalText("sessionId") == sessionId) {
                                if (active) presenceAccepted.set(true) else inactivePresenceAccepted.set(true)
                            }
                        } else if (active != null && pagedDiagnostics) pagedDiagnostic("presence-result active=$active accepted=false")
                    }
                    value.message(payload)
                }
            }
        }
    override fun connect(host: PairedHost) {
        if (pagedDiagnostics) pagedDiagnostic("transport-connect")
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
    private fun observe(payload: JsonObject) {
        val type = payload.optionalText("type")
        if (pagedDiagnostics && type in setOf("session.question.editor.upload.begin", "session.question.editor.upload.chunk", "session.question.editor.upload.commit")) {
            val kind = requireNotNull(type).substringAfterLast('.')
            val offset = if (kind == "chunk") payload.long("offset") else null
            val trace = UploadTrace(uploadTraceSequence.incrementAndGet(), kind, offset)
            uploadRequests[payload.text("requestId")] = trace
            val dataChars = if (kind == "chunk") payload.text("data").length else 0
            val plainBytes = payload.toString().toByteArray(Charsets.UTF_8).size
            pagedDiagnostic("upload-send alias=${trace.alias} kind=$kind offset=$offset plaintextBytes=$plainBytes dataChars=$dataChars")
        }
        if (type == "session.question.editor.outcome.get") {
            positiveOutcomeQueries.incrementAndGet()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("positiveOutcomeDiagnostic", "query-send") })
            positiveOutcomeRequests[payload.text("requestId")] = payload.obj("receipt")
        }
        if (type == "session.question.editor.outcome.release") {
            positiveOutcomeReleaseAttempts.incrementAndGet()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("positiveOutcomeDiagnostic", "release-send") })
            positiveOutcomeReleaseRequests.add(payload.text("requestId"))
            positiveOutcomeReleaseReceipt.set(payload.obj("receipt"))
        }
        if (type == "session.snapshot" && payload.optionalText("sessionId") == sessionId) {
            acceptedSnapshotQuestions.set(null)
            pendingSnapshots.add(payload.text("requestId"))
        }
        when (payload.optionalText("type")) {
            "session.question.editor.upload.begin" -> {
                uploadBegins.incrementAndGet()
                beginElapsed.compareAndSet(-1, SystemClock.elapsedRealtime())
                beginWall.compareAndSet(-1, System.currentTimeMillis())
                pendingBegins[payload.text("requestId")] = payload
            }
            "session.question.editor.upload.chunk" -> {
                uploadChunks.incrementAndGet()
                observedUploads[payload.text("uploadId")]?.let { upload -> synchronized(upload) {
                    runCatching {
                        val bytes = Wire.decode(payload.text("data"))
                        if (payload.text("sessionId") != sessionId || payload.text("questionId") != upload.begin.text("questionId") || payload.long("offset") != upload.bytes.toLong()) upload.valid = false
                        if (upload.snapshot != null) upload.valid = false
                        else { upload.digest.update(bytes); upload.bytes += bytes.size }
                    }.onFailure { upload.valid = false }
                } }
            }
            "session.question.editor.upload.commit" -> {
                uploadCommits.incrementAndGet()
                observedUploads[payload.text("uploadId")]?.let { upload -> synchronized(upload) {
                    val snapshot = upload.snapshot ?: UploadSnapshot(upload.bytes, upload.valid, upload.digest.digest().joinToString("") { "%02x".format(it) }).also { upload.snapshot = it }
                    val requestId = payload.text("requestId")
                    if (firstCommitId.compareAndSet(null, requestId)) {
                        firstCommitPayload.set(payload)
                        synchronized(commitLock) { check(knownCommitIds.add(requestId)) }
                    }
                    synchronized(commitLock) {
                        if (requestId in knownCommitIds) pendingCommits[requestId] = PendingCommit(upload, payload, snapshot)
                    }
                } }
            }
        }
        if (payload.optionalText("type") == "projects.list") {
            val id = payload.optionalText("requestId")
            if (id != null && id.startsWith("capabilities.v3:") && id.contains("question_editor_paged.v1")) pagedV3Requests.add(id)
        }
        if (payload.optionalText("type") == "session.editor.presence" && payload.optionalText("sessionId") == sessionId) {
            val active = payload["active"] == JsonPrimitive(true)
            if (pagedDiagnostics) pagedDiagnostic("presence-send active=$active")
            pendingPresence[payload.text("requestId")] = active
            if (!active) { presenceAccepted.set(false); inactivePresenceAccepted.set(false) }
        }
    }
    override fun send(payload: JsonObject) {
        if (payload.optionalText("type") == "question.answer") questionAnswerAttempts.incrementAndGet()
        observe(payload)
        delegate.send(payload)
    }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        if (holdPositiveRelease && payload.optionalText("type") == "session.question.editor.outcome.release") {
            positiveReleaseHeld.set(true)
            releasePositiveRelease.await()
        }
        if (payload.optionalText("type") == "question.answer") questionAnswerAttempts.incrementAndGet()
        if (holdSecondChunk && payload.optionalText("type") == "session.question.editor.upload.chunk" && payload.long("offset") == EDITOR_UPLOAD_CHUNK_BYTES.toLong()) {
            secondChunkHeld.set(true)
            releaseSecondChunk.await()
        }
        delegate.sendGuarded(payload) {
            beforePossibleWrite()
            observe(payload)
        }
    }
    fun sendDuplicate(scope: CoroutineScope): String {
        val original = requireNotNull(firstCommitPayload.get())
        val id = Wire.random()
        synchronized(commitLock) { check(knownCommitIds.size < 3 && knownCommitIds.add(id)) }
        val duplicate = JsonObject(original.toMutableMap().apply { put("requestId", JsonPrimitive(id)) })
        scope.launch {
            val result = runCatching { delegate.sendGuarded(duplicate) { observe(duplicate) } }
            synchronized(commitLock) { commitSendResults[id] = result }
        }
        return id
    }
    fun commitSendFinished(id: String): Boolean = synchronized(commitLock) { id in commitSendResults }
    fun requireCommitSendSucceeded(id: String) {
        synchronized(commitLock) { requireNotNull(commitSendResults[id]).getOrThrow() }
    }
    fun commitNotFound(id: String): Boolean = synchronized(commitLock) { commitResults[id] }?.let { receipt -> synchronized(receipt.pending.upload) { runCatching {
        val original = requireNotNull(firstCommitPayload.get())
        val first = requireNotNull(observedCommit.get())
        id != firstCommitId.get() && receipt.pending.request.text("requestId") == id &&
            receipt.pending.request.filterKeys { it != "requestId" } == original.filterKeys { it != "requestId" } &&
            receipt.pending.snapshot == first.pending.snapshot && receipt.pending.upload.valid &&
            !receipt.result.flag("ok") && receipt.result.obj("error").text("code") == "not_found"
    }.getOrDefault(false) } } == true
    fun uploadCounts(): Triple<Int, Int, Int> = Triple(uploadBegins.get(), uploadChunks.get(), uploadCommits.get())
    fun pagedCommitAccepted(questionId: String, grantId: String, bytes: Int, sha256: String): Boolean {
        val receipt = observedCommit.get() ?: return false
        return synchronized(receipt.pending.upload) {
            runCatching {
                val upload = receipt.pending.upload
                val begin = upload.begin
                val commit = receipt.pending.request
                val result = receipt.result
                upload.valid && receipt.pending.snapshot.valid && receipt.pending.snapshot.bytes == bytes && receipt.pending.snapshot.sha256 == sha256 && begin.long("totalBytes") == bytes.toLong() &&
                    begin.text("sha256") == sha256 && begin.text("sessionId") == sessionId && begin.text("questionId") == questionId && begin.text("grantId") == grantId &&
                    commit.text("sessionId") == sessionId && commit.text("questionId") == questionId &&
                    result.flag("ok") && result.obj("data").text("kind") == "accepted" && result.obj("data").text("sessionId") == sessionId
            }.getOrDefault(false)
        }
    }
    fun pagedCommitUnknown(questionId: String, grantId: String, bytes: Int, sha256: String): Boolean {
        val receipt = observedCommit.get() ?: return false
        return synchronized(receipt.pending.upload) {
            runCatching {
                val upload = receipt.pending.upload
                val begin = upload.begin
                val commit = receipt.pending.request
                val result = receipt.result
                upload.valid && receipt.pending.snapshot.valid && receipt.pending.snapshot.bytes == bytes && receipt.pending.snapshot.sha256 == sha256 && begin.long("totalBytes") == bytes.toLong() &&
                    begin.text("sha256") == sha256 && begin.text("sessionId") == sessionId && begin.text("questionId") == questionId && begin.text("grantId") == grantId &&
                    commit.text("sessionId") == sessionId && commit.text("questionId") == questionId &&
                    !result.flag("ok") && result.obj("error").text("code") == "internal" && result.obj("error").text("reason") == "outcome_unknown"
            }.getOrDefault(false)
        }
    }
    override fun close() {
        if (pagedDiagnostics) pagedDiagnostic("transport-close")
        clearPresence()
        delegate.close()
    }
    private fun clearPresence() {
        presenceAccepted.set(false)
        inactivePresenceAccepted.set(false)
        pendingPresence.clear()
        positiveOutcomeRequests.clear()
        positiveOutcomeReleaseRequests.clear()
        positiveOutcomeApplied.set(null)
        positiveOutcomeReleased.set(false)
        pendingSnapshots.clear()
        acceptedSnapshotQuestions.set(null)
        pagedV3Accepted.set(false)
        pagedV3Requests.clear()
        if (pagedDiagnostics && uploadRequests.isNotEmpty()) {
            uploadRequests.values.sortedBy { it.alias }.forEach { pagedDiagnostic("upload-unanswered alias=${it.alias} kind=${it.kind} offset=${it.offset}") }
        }
        uploadRequests.clear()
    }
}

internal class PrStatusSettings : SettingsRepository {
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
