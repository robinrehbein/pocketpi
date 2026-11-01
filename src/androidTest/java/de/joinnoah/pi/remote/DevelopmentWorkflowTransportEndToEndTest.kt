package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/** One opt-in, private actual-Pi workflow through production navigation and encrypted transport. */
class DevelopmentWorkflowTransportEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var workflow: JSONObject
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DefaultRemoteRepository
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var originalOwner: JSONObject
    private var ticketMode = false
    private lateinit var remoteRef: String

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            val expected = args.getString("developmentWorkflowExpected") == "true"
            val path = args.getString("developmentWorkflowFixturePath")
            if (expected) require(!path.isNullOrBlank()) { "developmentWorkflowFixturePath is required" }
            assumeTrue("Explicit development workflow fixture required", !path.isNullOrBlank())
            require(expected) { "developmentWorkflowExpected=true is required" }
            fixture = JSONObject(readFixtureFile(requireNotNull(path)).toString(Charsets.UTF_8))
            require(fixture.optBoolean("developmentWorkflowFixture") && fixture.optBoolean("nativeWorker") && fixture.optString("sessionKind") == "actual-pi")
            ticketMode = fixture.optBoolean("ticketWorkflowFixture")
            if (ticketMode) require(!fixture.has("sessionId") || fixture.isNull("sessionId"))
            require((args.getString("ticketWorkflowExpected") == "true") == ticketMode) { "ticketWorkflowExpected must match fixture mode" }
            workflow = fixture.getJSONObject("workflow")
            listOf("fileName", "originalText", "editedText", "agentPrompt", "agentMarker", "agentToolSha256", "shellCommand", "shellMarker", "commitMessage", "prTitle", "prBody", "initialRemoteRef", "initialMainHead").forEach { require(workflow.has(it)) }
            require(workflow.getString("fileName").isNotBlank() && workflow.getString("shellCommand").isNotBlank())
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixtureFile(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Development fixture")
            if (!ticketMode) selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val pairings = object : PairingStorage {
                override fun load() = listOf(host)
                override fun save(hosts: List<PairedHost>) = Unit
            }
            val drafts = object : DraftStorage {
                private var values = emptyMap<DraftKey, StoredDraft>()
                override fun load() = values
                override fun save(drafts: Map<DraftKey, StoredDraft>) { values = drafts.toMap() }
            }
            repository = DefaultRemoteRepository(pairings, drafts, SecureRemoteTransport(scope, client), scope)
            val observingRepository = object : RemoteRepository by repository {
                override fun runBash(command: String, excludeFromContext: Boolean): Boolean {
                    val accepted = repository.runBash(command, excludeFromContext)
                    println("DEVELOPMENT_WORKFLOW run-bash called=true accepted=$accepted commandMatches=${command == workflow.getString("shellCommand")} excluded=$excludeFromContext")
                    return accepted
                }
            }
            val settings = DevelopmentWorkflowSettings()
            NavigationTestActivity.contentFactory = {
                {
                    if (showNavigation.value) MaterialTheme {
                        RemoteNavigation(observingRepository, settings, false, {}, if (ticketMode) null else RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
                    }
                }
            }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun actualNativeSessionEditsTestsCommitsPushesAndShowsPrChecks() {
        val initial = if (ticketMode) status() else awaitStatus { it.optBoolean("ownerStable") && it.optString("initialHead").isNotBlank() }
        val initialHead = initial.getString("initialHead")
        assertEquals(initialHead, initial.getString("head"))
        assertEquals(initialHead, workflow.getString("initialMainHead"))
        assertTrue(initial.has("initialRemoteHead") && initial.isNull("initialRemoteHead"))
        assertTrue(initial.has("remoteHead") && initial.isNull("remoteHead"))
        if (ticketMode) {
            assertBeforeTicketStart()
        } else {
            originalOwner = initial.getJSONObject("initialOwner")
            remoteRef = workflow.getString("initialRemoteRef")
            assertEquals("refs/heads/fixture-feature", remoteRef)
            assertGuards(initial, initialHead, null, 0, 0, 0)
        }
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("development-fixture-loopback") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        if (ticketMode) {
            startTicketThroughUi(initialHead)
        } else {
            activate(RemoteSelection(host.routeId))
            compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
            activate(RemoteSelection(host.routeId, selection.projectId))
            compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
            compose.runOnIdle { showNavigation.value = true }
            compose.waitUntil(60_000) { repository.state.value.selection == selection && repository.state.value.connected && !repository.state.value.loading }
            awaitTag("composerField")
        }

        chatAction("files")
        awaitTag("filesPane")
        val name = workflow.getString("fileName")
        compose.waitUntil(60_000) { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
        val entry = if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name"
        compose.onNodeWithTag(entry).performScrollTo().performClick()
        awaitTag("filesEdit")
        compose.onNodeWithTag("filesEdit").performClick()
        awaitTag("filesEditorText")
        compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText"))
            .performTextReplacement(workflow.getString("editedText"))
        compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
        val fileSaved = awaitStatus { it.optBoolean("fileSaved") }
        assertEquals(sha256(workflow.getString("editedText")), fileSaved.getString("fileSha256"))
        assertGuards(status(), initialHead, null, 0, 0, 0)
        // Phone overlays the chat; back navigates the real Files UI until the composer is visible.
        backToComposer()
        // The fixture's deterministic local provider exercises a real Pi agent turn; it does not
        // qualify the output quality of an external model.
        val agentPrompt = workflow.getString("agentPrompt")
        val agentMarker = workflow.getString("agentMarker")
        require(agentPrompt.isNotBlank() && agentMarker.isNotBlank() && !agentPrompt.contains(agentMarker))
        require(!agentPrompt.startsWith("!"))
        assertFalse(status().optBoolean("agentVerified"))
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput(agentPrompt)
        compose.onNodeWithTag("composerField").assertTextEquals(agentPrompt)
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        val agentResult = awaitStatus { it.optBoolean("agentVerified") }
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(agentMarker, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(agentMarker, substring = true), useUnmergedTree = true).assertIsDisplayed()
        assertAgentTrace(agentResult)
        assertGuards(agentResult, initialHead, null, 0, 0, 0)
        val shell = workflow.getString("shellCommand")
        require(!shell.contains('\n') && !shell.contains('\r') && workflow.getString("shellMarker").isNotBlank())
        compose.onNodeWithTag("composerField").performTextInput("!$shell")
        compose.waitForIdle()
        val beforeShell = repository.state.value
        println("DEVELOPMENT_WORKFLOW before-shell connected=${beforeShell.connected} loading=${beforeShell.loading} sessionMatches=${beforeShell.session?.optionalText("id") == selection.sessionId} origin=${beforeShell.session?.optionalText("origin")} bashCap=${SESSION_BASH_CAPABILITY in beforeShell.capabilities} workerCap=${SESSION_BASH_WORKER_CAPABILITY in beforeShell.capabilities} bashUnavailable=${SESSION_BASH_CAPABILITY in beforeShell.unavailableCapabilities} workerUnavailable=${SESSION_BASH_WORKER_CAPABILITY in beforeShell.unavailableCapabilities} attachments=${beforeShell.attachments.size} quote=${beforeShell.quote != null} draftLength=${beforeShell.draft.length} shellRecognized=${shellDraft(beforeShell) != null}")
        println("DEVELOPMENT_WORKFLOW send-gates canSend=${canSendDraft(beforeShell)} status=${beforeShell.status} sending=${beforeShell.sending} importing=${beforeShell.importingAttachments} changing=${beforeShell.configurationChanging} childResume=${offersChildResume(beforeShell)} fits=${bashCommandFits(shell)}")
        check(canSendDraft(beforeShell)) { "Recognized shell is not sendable" }
        check(canRunShellCommands(beforeShell)) { "Native workflow shell capabilities are unavailable; see boundary diagnostics" }
        compose.onNodeWithTag("composerField").assertTextEquals("!$shell")
        compose.waitUntil(5_000) { exists("shellMode") }
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.waitForIdle()
        val afterShell = repository.state.value
        println("DEVELOPMENT_WORKFLOW after-shell bashPresent=${afterShell.bash != null} sent=${afterShell.bash?.sent} running=${afterShell.bash?.running} result=${afterShell.bash?.result != null} failure=${afterShell.bash?.failure}")
        screenshot("after-shell-send")
        compose.waitUntil(5_000) { repository.state.value.bash != null }
        val shellResult = awaitStatus { it.optBoolean("shellVerified") }
        assertEquals(shell, shellResult.getString("shellCommand"))
        assertEquals(0, shellResult.getInt("shellExit"))
        assertTrue(shellResult.getBoolean("shellMarkerObserved"))
        assertTrue(shellResult.getString("shellOutput").contains(workflow.getString("shellMarker")))
        compose.waitUntil(60_000) { repository.state.value.bash?.result != null }
        awaitTag("shellOutput")
        compose.onNode(hasText(workflow.getString("shellMarker"), substring = true) and hasAnyAncestor(hasTestTag("shellOutput")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("shellStatus").assertTextContains("0", substring = true)
        assertGuards(status(), initialHead, null, 0, 0, 0)

        chatAction("changes")
        awaitTag("changesPane")
        changesAction("changesCommitPrepare")
        awaitTag("commitFile:$name")
        compose.onNodeWithTag("commitFile:$name").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("commitMessage").performScrollTo().performTextInput(workflow.getString("commitMessage"))
        compose.onNodeWithTag("commitReview").performScrollTo().performClick()
        awaitTag("commitConfirm")
        compose.onNodeWithTag("commitConfirmedMessage").assertTextEquals(workflow.getString("commitMessage"))
        assertGuards(status(), initialHead, null, 0, 0, 0)
        compose.onNodeWithTag("commitConfirm").performScrollTo().assertIsEnabled().performClick()
        awaitTag("commitSuccess")
        val committed = awaitStatus { it.optBoolean("commitVerified") && it.optInt("commitCount") == 1 }
        val head = committed.getString("head")
        assertEquals(head, committed.getString("commitHead"))
        assertEquals(sha256(workflow.getString("editedText")), committed.getString("commitFileSha256"))
        assertEquals(workflow.getString("commitMessage"), committed.getString("commitMessage"))
        val paths = committed.getJSONArray("commitPaths")
        assertEquals(1, paths.length()); assertEquals(name, paths.getString(0))
        assertNotEquals(initialHead, head)
        assertGuards(committed, head, null, 1, 0, 0)
        compose.onNodeWithTag("commitSuccess").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("commitClose").performScrollTo().performClick()

        awaitTag("changesPane")
        changesAction("changesPushPrepare")
        awaitTag("pushReview")
        compose.onNodeWithTag("pushReview").performScrollTo().assertIsEnabled().performClick()
        awaitTag("pushConfirm")
        compose.onNodeWithTag("pushHead").assertTextContains(head, substring = true)
        compose.onNodeWithTag("pushTarget").performScrollTo().assertIsDisplayed()
        assertGuards(status(), head, null, 1, 0, 0)
        compose.onNodeWithTag("pushConfirm").performScrollTo().assertIsEnabled().performClick()
        awaitTag("pushSuccess")
        assertGuards(awaitStatus { it.optBoolean("pushVerified") && it.optInt("pushCount") == 1 }, head, head, 1, 1, 0)
        compose.onNodeWithTag("pushSuccess").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pushClose").performScrollTo().performClick()

        awaitTag("changesPane")
        changesAction("changesPullRequestPrepare")
        awaitTag("prBase")
        compose.onNodeWithTag("prBase").performScrollTo().performClick()
        compose.onNode(hasText("main") and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("prTitle").performScrollTo().performTextReplacement(workflow.getString("prTitle"))
        compose.onNodeWithTag("prBody").performScrollTo().performTextReplacement(workflow.getString("prBody"))
        compose.onNodeWithTag("prDraft").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("prReview").performScrollTo().assertIsEnabled().performClick()
        awaitTag("prConfirm")
        compose.onNodeWithTag("prHead").assertTextContains(head, substring = true)
        compose.onNodeWithTag("prTarget").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("prTitle").assertTextContains(workflow.getString("prTitle"), substring = true)
        compose.onNodeWithTag("prBody").assertTextContains(workflow.getString("prBody"), substring = true)
        assertGuards(status(), head, head, 1, 1, 0)
        compose.onNodeWithTag("prConfirm").performScrollTo().assertIsEnabled().performClick()
        awaitTag("prSuccess")
        val created = awaitStatus { it.optBoolean("pullRequestVerified") && it.optInt("pullRequestCreateCount") == 1 }
        assertGuards(created, head, head, 1, 1, 1)
        assertEquals("main", created.getString("pullRequestBase"))
        assertEquals(remoteRef.removePrefix("refs/heads/"), created.getString("pullRequestBranch"))
        assertEquals(head, created.getString("pullRequestHead"))
        assertEquals(workflow.getString("prTitle"), created.getString("pullRequestTitle"))
        assertEquals(workflow.getString("prBody"), created.getString("pullRequestBody"))
        assertTrue(created.has("pullRequestDraft") && !created.isNull("pullRequestDraft"))
        assertFalse(created.getBoolean("pullRequestDraft"))
        val number = created.getLong("pullRequestNumber")
        assertTrue(number > 0)
        compose.onNodeWithTag("prSuccess").performScrollTo().assertIsDisplayed().assertTextContains(number.toString(), substring = true)
        compose.onNodeWithTag("prClose").performScrollTo().performClick()
        val pending = awaitStatus { it.optString("observedCheckState") == "pending" }
        val checkName = pending.getString("controlledCheckName")
        assertTrue(checkName.isNotBlank())
        assertGuards(pending, head, head, 1, 1, 1)
        compose.waitUntil(60_000) { repository.state.value.pullRequest?.let { it.number == number && it.checks == PullRequestChecksState.RUNNING } == true }
        backToComposer()
        awaitTag("pullRequestChip")
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed()
            .assertTextContains(number.toString(), substring = true)
            .assertTextContains(compose.activity.getString(R.string.remote_pr_checks_running), substring = true)
        screenshot("pending")
        action("workflow-pass")
        val passed = awaitStatus { it.optBoolean("checksPassed") && it.optString("observedCheckState") == "passed" }
        assertEquals(checkName, passed.getString("controlledCheckName"))
        assertGuards(passed, head, head, 1, 1, 1)
        compose.waitUntil(60_000) { repository.state.value.pullRequest?.let { it.number == number && it.checks == PullRequestChecksState.DONE && it.failedChecks == 0 } == true }
        backToComposer()
        awaitTag("pullRequestChip")
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertTextContains(number.toString(), substring = true)
            .assertTextContains(compose.activity.getString(R.string.remote_pr_checks_passed), substring = true)
        screenshot("success")
        val finalStatus = status()
        assertGuards(finalStatus, head, head, 1, 1, 1)
        assertAgentTrace(finalStatus)
    }

    private fun assertBeforeTicketStart() {
        val observed = fullStatus()
        val ticket = observed.getJSONObject("ticket")
        assertEquals(0, ticket.getInt("startRequestCount"))
        assertEquals(0, ticket.getInt("workerCount"))
        for (key in listOf("projectId", "ref", "note", "startId", "state", "sessionId", "worktree", "branch")) {
            assertTrue("Expected null ticket.$key before start", ticket.has(key) && ticket.isNull(key))
        }
        val before = observed.getJSONObject("workflow")
        for (key in listOf("initialOwner", "currentOwner")) assertTrue(before.has(key) && before.isNull(key))
        assertEquals(workflow.getString("initialMainHead"), before.getString("head"))
        assertEquals(workflow.getString("initialMainHead"), before.getString("baseRemoteHead"))
        assertTrue(before.has("remoteHead") && before.isNull("remoteHead"))
        assertEquals(0, before.getInt("commitCount"))
        assertEquals(0, before.getInt("pushCount"))
        assertEquals(0, before.getInt("pullRequestCreateCount"))
    }

    private fun startTicketThroughUi(initialHead: String) {
        val expected = fixture.getJSONObject("ticket")
        val projectId = fixture.getString("projectId")
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) {
            exists("macOverflow") || compose.onAllNodesWithText(host.name).fetchSemanticsNodes().isNotEmpty()
        }
        if (!exists("macOverflow")) compose.onNodeWithText(host.name).performClick()
        awaitTag("macOverflow")
        compose.waitUntil(60_000) { canUseTickets(repository.state.value) }
        compose.onNodeWithTag("macOverflow").performClick()
        compose.onNodeWithTag("macTickets").performClick()
        awaitTag("ticketsList")
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(expected.getString("title"), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(expected.getString("title"), substring = true)).performClick()
        val startLabel = compose.activity.getString(R.string.remote_ticket_start)
        compose.waitUntil(60_000) { compose.onAllNodesWithText(startLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(startLabel).assertIsEnabled().performClick()
        awaitTag("ticketSheetField")
        compose.onNodeWithTag("ticketSheetField").performTextInput(expected.getString("note"))
        assertBeforeTicketStart()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            runCatching {
                val observed = fullStatus()
                val ticket = observed.getJSONObject("ticket")
                ticket.optString("state") == "succeeded" && ticket.optString("sessionId").isNotBlank() &&
                    observed.getJSONObject("workflow").optBoolean("ownerStable")
            }.getOrDefault(false)
        }
        val observed = fullStatus()
        val ticket = observed.getJSONObject("ticket")
        val current = observed.getJSONObject("workflow")
        assertEquals(1, ticket.getInt("startRequestCount"))
        assertEquals(1, ticket.getInt("workerCount"))
        assertEquals(projectId, ticket.getString("projectId"))
        assertEquals(expected.getString("ref"), ticket.getString("ref"))
        assertEquals(expected.getString("note"), ticket.getString("note"))
        assertTrue(ticket.getString("startId").isNotBlank())
        assertEquals("succeeded", ticket.getString("state"))
        assertTrue(ticket.getBoolean("moved"))
        val sessionId = ticket.getString("sessionId")
        selection = RemoteSelection(host.routeId, projectId, sessionId)
        remoteRef = "refs/heads/" + ticket.getString("branch")
        assertEquals(workflow.getString("initialRemoteRef"), remoteRef)
        originalOwner = current.getJSONObject("initialOwner")
        assertEquals(sessionId, originalOwner.getString("sessionId"))
        assertEquals(ticket.getString("worktree"), originalOwner.getString("project"))
        assertGuards(current, initialHead, null, 0, 0, 0)
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading &&
                state.tickets.start?.let { it.state == TicketStartState.SUCCEEDED && it.sessionId == sessionId && it.projectId == projectId && it.ref == expected.getString("ref") } == true
        }
        awaitTag("composerField")
        awaitStatus { it.optBoolean("ticketPromptVerified") }
        val marker = expected.getString("marker")
        require(marker.isNotBlank() && marker != workflow.getString("agentMarker"))
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(marker, substring = true), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag: String) {
        val diagnostic = InstrumentationRegistry.getArguments().getString("workflowNavigationDiagnostics") == "true"
        try {
            compose.waitUntil(if (diagnostic && tag == "ticketsList") 10_000 else 60_000) { exists(tag) }
        } catch (failure: Throwable) {
            val state = repository.state.value
            println("DEVELOPMENT_WORKFLOW timeout=$tag projectsPlaceholder=${exists("projectsPlaceholder")} chatPane=${exists("chatPane")} ticketsList=${exists("ticketsList")} ticketProjects=${state.tickets.projects.size} ticketLoading=${state.tickets.loading}")
            runCatching {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                    File(compose.activity.getExternalFilesDir(null), "development-workflow-timeout-$tag.png"))
            }
            throw failure
        }
    }
    private fun chatAction(action: String) {
        val direct = "chatAction_$action"
        if (exists(direct)) compose.onNodeWithTag(direct).performClick()
        else {
            compose.onNodeWithTag("chatActionsMore").performClick()
            compose.onNodeWithTag("chatActionMenu_$action").performClick()
        }
    }
    private fun changesAction(tag: String) {
        compose.waitUntil(60_000) { repository.state.value.changes?.status?.available == true && (exists(tag) || exists("changesMoreActions")) }
        if (!exists(tag)) compose.onNodeWithTag("changesMoreActions").performClick()
        compose.onNodeWithTag(tag).performClick()
    }
    private fun backToComposer() {
        // Background chat semantics can remain "displayed" beneath the file editor. Close the
        // actual foreground panes instead of using that flag as an occlusion test.
        repeat(6) {
            val close = when {
                exists("filesEditorBack") -> "filesEditorBack"
                exists("filesClose") -> "filesClose"
                exists("filesBack") -> "filesBack"
                exists("changesClose") -> "changesClose"
                else -> null
            }
            if (close == null) {
                compose.onNodeWithTag("composerField").assertIsDisplayed()
                check(!exists("filesPane") && !exists("changesPane")) { "Composer remains covered by a foreground pane" }
                return
            }
            compose.onNodeWithTag(close).performClick()
            compose.waitForIdle()
        }
        error("Could not close the foreground pane through its UI controls")
    }
    private fun fullStatus(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use {
        assertTrue(it.isSuccessful); JSONObject(requireNotNull(it.body).string())
    }
    private fun status(): JSONObject = fullStatus().getJSONObject("workflow")
    private fun awaitStatus(predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(60_000) { runCatching { predicate(status()) }.getOrDefault(false) }
        return status().also { assertTrue("Fixture owner changed", it.getBoolean("ownerStable")) }
    }
    private fun assertAgentTrace(value: JSONObject) {
        val events = value.getJSONArray("agentEvents")
        val workflowKinds = listOf("prompt", "model_call", "tool_execution_end", "model_call", "tool_execution_end", "model_call", "agent_end")
        val kinds = listOf("provider_ready") +
            (if (ticketMode) listOf("ticket_prompt", "ticket_model_call", "ticket_agent_end") else emptyList()) + workflowKinds
        assertEquals(kinds.size, events.length())
        for ((index, kind) in kinds.withIndex()) {
            val event = events.getJSONObject(index)
            assertEquals(kind, event.getString("event"))
            assertEquals(originalOwner.getLong("pid"), event.getLong("pid"))
            assertEquals(originalOwner.getString("sessionId"), event.getString("sessionId"))
        }
        if (ticketMode) {
            val digest = fixture.getJSONObject("ticket").getString("promptSha256")
            assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
            for (index in 1..3) assertEquals(digest, events.getJSONObject(index).getString("promptSha256"))
            assertTrue(events.getJSONObject(1).getBoolean("matched"))
            for (index in 2..3) {
                assertEquals(1, events.getJSONObject(index).getInt("callCount"))
                assertEquals(1, events.getJSONObject(index).getInt("globalCallCount"))
            }
            assertTrue(events.getJSONObject(3).getBoolean("markerEmitted"))
            assertTrue(events.getJSONObject(3).getBoolean("success"))
        }
        fun workflowEvent(index: Int): JSONObject = events.getJSONObject(index + if (ticketMode) 3 else 0)
        assertTrue(workflowEvent(1).getBoolean("matched"))
        for ((index, count) in listOf(2 to 1, 4 to 2, 6 to 3)) {
            assertEquals(count, workflowEvent(index).getInt("callCount"))
            if (ticketMode) assertEquals(count + 1, workflowEvent(index).getInt("globalCallCount"))
        }
        val firstCall = workflowEvent(2)
        for (key in listOf("contextMatched", "contextToolCallId", "contextResultSha256")) {
            assertTrue(firstCall.has(key) && firstCall.isNull(key))
        }
        for ((index, toolCallId, expectedHash) in listOf(
            Triple(4, "workflow-read", sha256(workflow.getString("editedText"))),
            Triple(6, "workflow-bash", workflow.getString("agentToolSha256")),
        )) {
            val call = workflowEvent(index)
            assertTrue(call.getBoolean("contextMatched"))
            assertEquals(toolCallId, call.getString("contextToolCallId"))
            assertEquals(expectedHash, call.getString("contextResultSha256"))
        }
        for ((index, tool) in listOf(3 to "read", 5 to "bash")) {
            val event = workflowEvent(index)
            assertEquals(tool, event.getString("tool"))
            assertFalse(event.getBoolean("isError"))
            assertTrue(event.getBoolean("resultMatched"))
            assertTrue(event.getString("resultSha256").matches(Regex("[0-9a-f]{64}")))
        }
        assertEquals(sha256(workflow.getString("editedText")), workflowEvent(3).getString("resultSha256"))
        assertEquals(0, workflowEvent(5).getInt("exitCode"))
        assertEquals(workflow.getString("agentToolSha256"), workflowEvent(5).getString("resultSha256"))
        assertTrue(workflowEvent(7).getBoolean("markerEmitted"))
        assertTrue(workflowEvent(7).getBoolean("success"))
    }
    private fun assertGuards(value: JSONObject, head: String, remoteHead: String?, commits: Int, pushes: Int, creates: Int) {
        assertTrue("Fixture owner changed", value.getBoolean("ownerStable"))
        if (ticketMode) {
            val ticket = fullStatus().getJSONObject("ticket")
            assertEquals(1, ticket.getInt("startRequestCount"))
            assertEquals(1, ticket.getInt("workerCount"))
            assertEquals("succeeded", ticket.getString("state"))
            assertEquals(selection.sessionId, ticket.getString("sessionId"))
            assertEquals(remoteRef, "refs/heads/" + ticket.getString("branch"))
            assertEquals(originalOwner.getString("project"), ticket.getString("worktree"))
        }
        val initialOwner = value.getJSONObject("initialOwner")
        val currentOwner = value.getJSONObject("currentOwner")
        assertTrue(initialOwner.getLong("pid") > 0)
        assertEquals(selection.sessionId, initialOwner.getString("sessionId"))
        assertTrue(initialOwner.getString("runtimeId").isNotBlank())
        assertTrue(initialOwner.getString("project").isNotBlank())
        for (key in listOf("pid", "sessionId", "runtimeId", "project")) {
            assertEquals(originalOwner.get(key), initialOwner.get(key))
            assertEquals(originalOwner.get(key), currentOwner.get(key))
        }
        assertEquals(head, value.getString("head"))
        assertEquals(remoteRef, value.getString("remoteRef"))
        assertEquals(workflow.getString("initialMainHead"), value.getString("baseRemoteHead"))
        assertTrue(value.has("remoteHead"))
        assertEquals(remoteHead, if (value.isNull("remoteHead")) null else value.getString("remoteHead"))
        assertEquals(commits, value.getInt("commitCount")); assertEquals(pushes, value.getInt("pushCount")); assertEquals(creates, value.getInt("pullRequestCreateCount"))
    }
    private fun action(value: String) {
        val body = JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals(200, it.code) }
    }
    private fun screenshot(phase: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        val file = File(compose.activity.getExternalFilesDir(null), "development-workflow-$phase.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)); assertTrue(file.length() > 0)
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun readFixtureFile(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        return ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}

private class DevelopmentWorkflowSettings : SettingsRepository {
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
