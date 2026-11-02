package de.joinnoah.pi.remote

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** One ticket-created native session through the real application and encrypted journal. */
class CombinedNativeJourneyEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: JSONObject
    private lateinit var workflow: JSONObject
    private lateinit var app: RemoteApplication
    private lateinit var repository: RemoteRepository
    private lateinit var client: OkHttpClient
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var originalOwner: JSONObject
    private lateinit var remoteRef: String
    private lateinit var activity: MainActivity
    private val ticketMode = true
    private val proof = JSONObject().put("schemaVersion", 1).put("outcome", "running")
    private lateinit var proofFile: File

    // Keep a private, independently retrievable checkpoint even if instrumentation aborts.
    private fun record(stage: String) {
        proof.put("stage", stage).put("observerOverflow", ProductionEntryBootstrap.observerOverflow)
        val temporary = File(proofFile.parentFile, proofFile.name + ".tmp")
        temporary.outputStream().use { stream ->
            stream.write(proof.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Os.rename(temporary.absolutePath, proofFile.absolutePath)
        val directory = Os.open(proofFile.parentFile!!.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(directory) } finally { Os.close(directory) }
    }

    @Test fun ticketNativeEditorAndDevelopmentJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit combined fixture required", args.getString("combinedNativeJourneyExpected") == "true")
        val runNonce = requireNotNull(args.getString("combinedNativeJourneyRunNonce")) { "Combined journey run nonce required" }
        require(runNonce.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Invalid combined journey run nonce" }
        proof.put("runNonce", runNonce)
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApplication
        proofFile = File(app.filesDir, "combined-native-journey-proof.json")
        try {
        record("setup")
        check(args.getString("productionEntryFixture") == "true" && args.getString("productionEntryFreshOwned") == "true")
        check(args.getString("productionEntryMode") == "seed" && args.getString("productionEntryObserve") == "true")
        check(args.getString("productionEntrySecretFromFixture") == "true" && args.getString("productionEntryDeviceSecret") == null)
        check(ProductionEntryBootstrap.installed)
        val path = requireNotNull(args.getString("productionEntryFixturePath"))
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")).use { it.readBytes().toString(Charsets.UTF_8) })
        check(fixture.getBoolean("combinedNativeJourney") && fixture.getBoolean("ticketWorkflowFixture") && fixture.getBoolean("developmentWorkflowFixture"))
        check(fixture.getBoolean("nativeWorker") && fixture.getBoolean("nativeProductionProof") && fixture.getString("sessionKind") == "actual-pi")
        check(!fixture.optBoolean("pagedEditorFixture", false) && fixture.getBoolean("pagedEditorPositiveOutcomes") && fixture.getString("pagedEditorCase") == "max-unchanged")
        check(!fixture.has("sessionId") || fixture.isNull("sessionId"))
        val relay = fixture.getString("relayUrl").toHttpUrl()
        check(fixture.getString("routeId") == args.getString("productionEntryRoute") && fixture.getString("deviceId") == args.getString("productionEntryDeviceId"))
        check(relay.toString().removeSuffix("/") == requireNotNull(args.getString("productionEntryRelay")).toHttpUrl().toString().removeSuffix("/"))
        for ((key, expectedPath) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
            val url = fixture.getString(key).toHttpUrl()
            require(url.scheme == "https" && url.host == "10.0.2.2" && url.port == relay.port && url.encodedPath == expectedPath && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty())
        }
        workflow = fixture.getJSONObject("workflow")
        client = ProductionEntryBootstrap.http()
        repository = app.repository
        val storedHost = app.stores.pairingFile.load().single()
        assertEquals(fixture.getString("routeId"), storedHost.routeId)
        assertEquals(fixture.getString("deviceId"), storedHost.deviceId)
        check(storedHost.secret == fixture.getString("deviceSecret"))
        assertEquals(relay.toString().removeSuffix("/"), storedHost.relay.toHttpUrl().toString().removeSuffix("/"))
        host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Isolated fixture")
        val journal = app.stores.pagedEditor.load()
        check(journal.drafts.isEmpty() && journal.attempts.isEmpty() && journal.pendingReleases.isEmpty())
        val initial = status()
        val initialHead = initial.getString("initialHead")
        assertEquals(workflow.getString("initialMainHead"), initialHead)
        assertBeforeTicketStart()
        assertTrue(fullStatus().getJSONObject("combined").getBoolean("emptyBeforeTicket"))
        proof.put("preTicket", JSONObject().put("emptyBeforeTicket", true)
            .put("startRequestCount", 0).put("workerCount", 0)
            .put("ticketFieldsAbsent", true).put("ownersAbsent", true)
            .put("initialHead", initialHead).put("commitCount", 0).put("pushCount", 0).put("pullRequestCreateCount", 0))
        record("pre-ticket")
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity = it }
            compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
            assertTrue(repository.state.value.selection?.sessionId == null)
            record("ticket-start")
            startTicketThroughUi(initialHead)
            assertTrue(ProductionEntryBootstrap.factoryRequests.get() > 0 && ProductionEntryBootstrap.socketOpens.get() > 0)
            assertEquals(true, ProductionEntryBootstrap.lastEndpointAllowed)
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
        record("editor")
        exerciseEditor(initialHead)
        record("development-workflow")
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
        compose.waitUntil(5_000) { repository.state.value.bash != null }
        val shellResult = awaitStatus { it.optBoolean("shellVerified") }
        assertEquals(shell, shellResult.getString("shellCommand"))
        assertEquals(0, shellResult.getInt("shellExit"))
        val shellCombined = fullStatus().getJSONObject("combined")
        assertEquals(1, shellCombined.getInt("shellCalls"))
        assertEquals(0, shellCombined.getInt("shellInvalidCalls"))
        proof.put("shell", JSONObject().put("shellCalls", shellCombined.getInt("shellCalls"))
            .put("shellInvalidCalls", shellCombined.getInt("shellInvalidCalls")))
        record("shell-counts-verified")
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
        awaitTag("pushSuccess", 165_000)
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
        awaitTag("prSuccess", 165_000)
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
            .assertTextContains(app.getString(R.string.remote_pr_checks_running), substring = true)
        action("workflow-pass")
        val passed = awaitStatus { it.optBoolean("checksPassed") && it.optString("observedCheckState") == "passed" }
        assertEquals(checkName, passed.getString("controlledCheckName"))
        assertGuards(passed, head, head, 1, 1, 1)
        compose.waitUntil(60_000) { repository.state.value.pullRequest?.let { it.number == number && it.checks == PullRequestChecksState.DONE && it.failedChecks == 0 } == true }
        backToComposer()
        awaitTag("pullRequestChip")
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertTextContains(number.toString(), substring = true)
            .assertTextContains(app.getString(R.string.remote_pr_checks_passed), substring = true)
        val finalObserved = fullStatus()
        val finalCombined = finalObserved.getJSONObject("combined")
        assertEquals(1, finalCombined.getInt("shellCalls"))
        assertEquals(0, finalCombined.getInt("shellInvalidCalls"))
        val finalStatus = finalObserved.getJSONObject("workflow")
        assertGuards(finalStatus, head, head, 1, 1, 1)
        assertAgentTrace(finalStatus)
        if (fixture.optBoolean("journeyBrowser")) exerciseBrowser(head)
        if (fixture.optBoolean("journeyPreview")) exercisePreview(head)

            assertFalse(ProductionEntryBootstrap.observerOverflow)
            scenario.onActivity { assertSame(activity, it) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(1, ProductionEntryBootstrap.activityEvents.count { it.kind == "created" })
                assertSame(activity, ProductionEntryBootstrap.activityEvents.single { it.kind == "created" }.activity)
            }
            proof.put("final", JSONObject().put("head", head).put("remoteHead", head)
                .put("commitCount", 1).put("pushCount", 1).put("pullRequestCreateCount", 1)
                .put("pullRequestNumber", number).put("checksState", "passed")
                .put("ownerStable", true).put("singleActivity", true)
                .put("shellCalls", finalCombined.getInt("shellCalls"))
                .put("shellInvalidCalls", finalCombined.getInt("shellInvalidCalls")))
        }
        proof.put("outcome", "passed")
        record("complete")
        } catch (failure: Throwable) {
            // Exception text can contain fixture payloads or credentials; retain only its type.
            runCatching { proof.put("outcome", "failed").put("failureType", failure.javaClass.name); record(proof.optString("stage", "setup")) }
            throw failure
        }
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
        val startLabel = app.getString(R.string.remote_ticket_start)
        compose.waitUntil(60_000) { compose.onAllNodesWithText(startLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(startLabel).assertIsEnabled().performClick()
        awaitTag("ticketSheetField")
        compose.onNodeWithTag("ticketSheetField").performTextInput(expected.getString("note"))
        assertBeforeTicketStart()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            val observed = statusOrNull()
            if (observed == null) false else {
                val combined = observed.getJSONObject("combined")
                val ticket = observed.getJSONObject("ticket")
                ticket.optString("state") == "succeeded" && ticket.optString("sessionId").isNotBlank() &&
                    observed.getJSONObject("workflow").optBoolean("ownerStable") && combined.optString("state") == "ready"
            }
        }
        val observed = fullStatus()
        val ticket = observed.getJSONObject("ticket")
        assertTrue(observed.getJSONObject("combined").getBoolean("identityVerified"))
        assertEquals(ticket.getString("sessionId"), observed.getJSONObject("combined").getString("sessionId"))
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
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading &&
                state.tickets.start?.let { it.state == TicketStartState.SUCCEEDED && it.sessionId == sessionId && it.projectId == projectId && it.ref == expected.getString("ref") } == true
        }
        assertGuards(current, initialHead, null, 0, 0, 0)
        proof.put("ticket", JSONObject().put("projectId", projectId).put("ref", ticket.getString("ref"))
            .put("startId", ticket.getString("startId")).put("state", ticket.getString("state"))
            .put("sessionId", sessionId).put("branch", ticket.getString("branch"))
            .put("worktree", ticket.getString("worktree")).put("startRequestCount", 1).put("workerCount", 1)
            .put("identityVerified", true).put("owner", JSONObject()
                .put("pid", originalOwner.getLong("pid")).put("sessionId", originalOwner.getString("sessionId"))
                .put("runtimeId", originalOwner.getString("runtimeId")).put("project", originalOwner.getString("project"))))
        record("ticket-ready")
        awaitTag("composerField")
        awaitStatus { it.optBoolean("ticketPromptVerified") }
        val marker = expected.getString("marker")
        require(marker.isNotBlank() && marker != workflow.getString("agentMarker"))
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(marker, substring = true), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag: String, timeoutMillis: Long = 60_000) {

        try {
            compose.waitUntil(timeoutMillis) { exists(tag) }
        } catch (failure: Throwable) {
            val state = repository.state.value
            println("DEVELOPMENT_WORKFLOW timeout=$tag projectsPlaceholder=${exists("projectsPlaceholder")} chatPane=${exists("chatPane")} ticketsList=${exists("ticketsList")} ticketProjects=${state.tickets.projects.size} ticketLoading=${state.tickets.loading}")
            runCatching {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                    File(app.getExternalFilesDir(null), "development-workflow-timeout-$tag.png"))
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
        if (it.code == 503) throw java.io.IOException("Fixture status temporarily unavailable")
        assertTrue(it.isSuccessful)
        JSONObject(requireNotNull(it.body).string()).also { observed ->
            val combined = observed.getJSONObject("combined")
            if (combined.optString("state") == "failed") {
                throw AssertionError("Combined fixture binding failed: ${combined.optString("error", "unknown error")}")
            }
        }
    }
    // Only a transient transport error is retryable; malformed status and failed binding must surface.
    private fun statusOrNull(): JSONObject? = try { fullStatus() } catch (_: java.io.IOException) { null }
    private fun status(): JSONObject = fullStatus().getJSONObject("workflow")
    private fun awaitStatus(predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(60_000) { statusOrNull()?.getJSONObject("workflow")?.let(predicate) == true }
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
        assertEquals(selection, repository.state.value.selection)
        assertEquals(host.routeId, repository.state.value.host?.routeId)
        assertEquals(host.deviceId, repository.state.value.host?.deviceId)
        val combined = fullStatus().getJSONObject("combined")
        assertEquals("ready", combined.getString("state"))
        assertTrue(combined.getBoolean("identityVerified"))
        assertEquals(selection.sessionId, combined.getString("sessionId"))
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
    private fun exerciseEditor(initialHead: String) {
        val source = "😀".repeat(262_144)
        val hash = sha256(source)
        for (name in listOf("Source", "Final")) {
            assertEquals(1_048_576, fixture.getInt("pagedEditor${name}Bytes"))
            assertEquals(hash, fixture.getString("pagedEditor${name}Sha256"))
        }
        val command = fixture.getString("editorProbeCommand")
        compose.waitUntil(120_000) {
            repository.state.value.let { it.selection == selection && it.connected && !it.loading &&
                NATIVE_EDITOR_CAPABILITY in it.capabilities && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities &&
                QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY in it.capabilities && !it.commandsLoading &&
                it.commands.any { c -> c.name == commandName(command) } }
        }
        val editorInput = "$command ${fixture.getString("editorProbeNonce")}"
        val writesBeforeEditor = ProductionEntryBootstrap.writeAttempts().size
        val editorProgress = org.json.JSONArray()
        fun recordEditorProgress(stage: String) {
            val field = runCatching { compose.onNodeWithTag("composerField").fetchSemanticsNode() }.getOrNull()
            val button = runCatching { compose.onNodeWithTag("primaryComposerAction").fetchSemanticsNode() }.getOrNull()
            val text = runCatching { field?.config?.get(SemanticsProperties.EditableText)?.text }.getOrNull()
            val writes = ProductionEntryBootstrap.writeAttempts().drop(writesBeforeEditor)
            // Booleans, counts and resource names only: the gates sendPrompt and active() check.
            val state = repository.state.value
            fun resourceName(id: Int?) = id?.let { runCatching { app.resources.getResourceEntryName(it) }.getOrDefault("unknown") } ?: JSONObject.NULL
            val repositoryGates = JSONObject().put("connected", state.connected).put("loading", state.loading)
                .put("status", state.status).put("sending", state.sending)
                .put("importingAttachments", state.importingAttachments).put("configurationChanging", state.configurationChanging)
                .put("commandsLoading", state.commandsLoading).put("commandCount", state.commands.size)
                .put("commandKnown", state.commands.any { c -> c.name == commandName(command) })
                .put("commandsCapability", COMMANDS_CAPABILITY in state.capabilities)
                .put("commandsUnavailable", COMMANDS_CAPABILITY in state.unavailableCapabilities)
                .put("questionCount", state.questions.size).put("bashRunning", state.bash?.running == true)
                .put("quotePresent", state.quote != null).put("attachmentCount", state.attachments.size)
                .put("selectionMatches", state.selection == selection).put("draftEqualsInput", state.draft == editorInput)
                .put("canSendDraft", canSendDraft(state)).put("canQueuePrompt", canQueuePrompt(state))
                .put("projectSelected", state.selection.projectId != null)
                .put("pendingSend", state.pendingSend?.state?.name ?: JSONObject.NULL).put("followUpCount", state.followUps.size)
                .put("inboundCount", ProductionEntryBootstrap.inboundMessages().size)
                .put("error", resourceName(state.error)).put("commandNotice", resourceName(state.commandNotice))
            editorProgress.put(JSONObject().put("stage", stage).put("repository", repositoryGates)
                .put("fieldPresent", field != null).put("fieldBounds", field?.boundsInRoot?.toString() ?: JSONObject.NULL)
                .put("fieldEqualsInput", text == editorInput).put("fieldEmpty", text?.isEmpty() == true)
                .put("buttonPresent", button != null).put("buttonBounds", button?.boundsInRoot?.toString() ?: JSONObject.NULL)
                .put("buttonEnabled", button != null && !button.config.contains(SemanticsProperties.Disabled))
                .put("commandRequests", writes.count { it.optionalText("type") == "session.command" })
                .put("matchingCommandRequests", writes.filter { it.optionalText("type") == "session.command" && it.optionalText("sessionId") == selection.sessionId && it.optionalText("text") == editorInput }
                    .map { it.optionalText("requestId") }.distinct().size)
                .put("observerOverflow", ProductionEntryBootstrap.observerOverflow))
            proof.put("editorComposerProgress", editorProgress)
            record("editor-composer-$stage")
        }
        try {
            recordEditorProgress("before-input")
            compose.onNodeWithTag("composerField").performTextReplacement(editorInput)
            recordEditorProgress("after-input")
            compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
            recordEditorProgress("after-click")
            // Observation only: an error separates sendPrompt's refusals from active()'s silent drop.
            runCatching {
                compose.waitUntil(3_000) {
                    repository.state.value.error != null || ProductionEntryBootstrap.writeAttempts().drop(writesBeforeEditor)
                        .any { it.optionalText("type") == "session.command" }
                }
            }
            recordEditorProgress("after-click-settled")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(120_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
            recordEditorProgress("question-received")
            assertFalse(ProductionEntryBootstrap.observerOverflow)
            // One logical send; a transport retry may record the same request ID twice.
            assertEquals(1, ProductionEntryBootstrap.writeAttempts().drop(writesBeforeEditor).filter {
                it.optionalText("type") == "session.command" && it.optionalText("sessionId") == selection.sessionId && it.optionalText("text") == editorInput
            }.map { it.optionalText("requestId") }.distinct().size)
        } catch (failure: Throwable) {
            runCatching { recordEditorProgress("failure") }
            runCatching { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.getExternalFilesDir(null), "combined-native-editor-composer-failure.png")) }
            throw failure
        }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), question["native"])
        val id = question.text("id")
        val grant = question.text("editorUploadGrantId")
        val ownerIncarnation = question.text("editorOwnerIncarnationId")
        val sourceDescriptor = PagedEditorWire.parseQuestionSource(question)
        assertEquals(1_048_576, sourceDescriptor.totalBytes)
        assertEquals(hash, sourceDescriptor.sha256)
        val journal = app.stores.pagedEditor
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        compose.onNodeWithText(app.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == source }
        val changed = "XX" + "😀".repeat(262_143)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("XX" + "😀".repeat(1_023))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == changed && journal.load().drafts[key]?.text == changed }
        compose.onNodeWithTag("questionEditorField").performTextReplacement("😀".repeat(1_024))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == source && journal.load().drafts[key]?.text == source }
        compose.onNodeWithText(app.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            statusOrNull()?.let { s -> s.optBoolean("pagedOwnerVerified") && s.optBoolean("pagedCommitDispatched") && s.optBoolean("pagedAckVerified") && s.optBoolean("pagedSdkResultVerified") && s.optBoolean("nativeEditorVerified") && s.optBoolean("nativeEditorValueVerified") && s.optInt("pagedCommitCount", -1) == 1 && s.optJSONObject("nativeProduction")?.optInt("ledgerAfterReleaseCount", -1) == 0 } == true &&
                repository.state.value.questions.none { it.optionalText("id") == id } && journal.load().let { it.drafts.isEmpty() && it.attempts.isEmpty() && it.pendingReleases.isEmpty() }
        }
        val fresh = PagedEditorStore(app).load()
        assertTrue(fresh.drafts.isEmpty() && fresh.attempts.isEmpty() && fresh.pendingReleases.isEmpty())
        assertFalse(ProductionEntryBootstrap.observerOverflow)
        val writes = ProductionEntryBootstrap.writeAttempts()
        val inbound = ProductionEntryBootstrap.inboundMessages()
        val begin = writes.single { it.optionalText("type") == "session.question.editor.upload.begin" && it.optionalText("questionId") == id }
        assertEquals(grant, begin.text("grantId"))
        assertEquals(selection.sessionId, begin.text("sessionId"))
        assertEquals(ownerIncarnation, begin.text("ownerIncarnationId"))
        assertEquals(hash, begin.text("sha256"))
        assertEquals(1_048_576L, begin.long("totalBytes"))
        val sourceReads = writes.filter { it.optionalText("type") == "session.question.details.get" && it.optionalText("questionId") == id }
        assertEquals(22, sourceReads.size)
        sourceReads.forEach {
            assertEquals(selection.sessionId, it.text("sessionId"))
            assertEquals(sourceDescriptor.id, it.text("detailsId"))
        }
        val commit = writes.single { it.optionalText("type") == "session.question.editor.upload.commit" && it.optionalText("questionId") == id }
        assertEquals(selection.sessionId, commit.text("sessionId"))
        assertEquals(ownerIncarnation, commit.text("ownerIncarnationId"))
        val chunks = writes.filter { it.optionalText("type") == "session.question.editor.upload.chunk" && it.optionalText("questionId") == id }
        assertEquals(22, chunks.size)
        chunks.forEach { assertEquals(selection.sessionId, it.text("sessionId")); assertEquals(commit.text("uploadId"), it.text("uploadId")) }
        val release = writes.single { it.optionalText("type") == "session.question.editor.outcome.release" }
        val receipt = release.obj("receipt")
        assertEquals(selection.sessionId, receipt.text("sessionId"))
        assertEquals(id, receipt.text("questionId"))
        assertEquals(grant, receipt.text("grantId"))
        assertEquals(ownerIncarnation, receipt.text("ownerIncarnationId"))
        assertEquals(commit.text("requestId"), receipt.text("commitRequestId"))
        assertEquals(commit.text("uploadId"), receipt.text("uploadId"))
        assertEquals(hash, receipt.text("sha256")); assertEquals(1_048_576L, receipt.long("byteLength"))
        assertTrue(inbound.any { it.optionalText("type") == "result" && it.optionalText("requestId") == release.text("requestId") && it.flag("ok") && it.obj("data").optionalText("kind") == "editor_outcome_released" })
        assertTrue(writes.none { it.optionalText("type") == "question.answer" })
        val final = fullStatus()
        assertEquals(1, final.getInt("pagedBeginCount")); assertEquals(22, final.getInt("pagedChunkCount")); assertEquals(1, final.getInt("sourceSdkResultCount"))
        assertEquals(0, final.getInt("nativeEditorOriginalCalls")); assertEquals(0, final.getInt("pagedQuestionAnswerCount"))
        val production = final.getJSONObject("nativeProduction")
        assertEquals(1, production.getInt("ledgerAppliedCount")); assertEquals(0, production.getInt("ledgerAfterReleaseCount"))
        val capabilityId = production.getJSONObject("capabilityRequest").getString("requestId")
        assertTrue(capabilityId.startsWith("capabilities.v3:"))
        assertEquals(1, writes.count { it.optionalText("type") == "projects.list" && it.optionalText("requestId") == capabilityId })
        assertTrue(inbound.any { it.optionalText("type") == "result" && it.optionalText("requestId") == capabilityId && it.flag("ok") })
        for (optIn in listOf("question_editor.v1", "question_editor_native.v1", QUESTION_PAGED_EDITOR_OPT_IN, QUESTION_EDITOR_POSITIVE_OUTCOME_OPT_IN)) assertTrue(capabilityId.contains(optIn))
        assertEquals(capabilityId, production.getJSONObject("capabilityResponse").getString("requestId"))
        assertTrue(production.getJSONObject("capabilityResponse").getBoolean("ok"))
        assertGuards(status(), initialHead, null, 0, 0, 0)
        proof.put("editor", JSONObject().put("sourceSha256", hash).put("sourceBytes", sourceDescriptor.totalBytes)
            .put("questionId", id).put("sourceReadCount", sourceReads.size).put("chunkCount", chunks.size)
            .put("receiptSha256", receipt.text("sha256")).put("receiptBytes", receipt.long("byteLength"))
            .put("receiptIdentityMatched", true).put("releaseAcknowledged", true)
            .put("ledgerAppliedCount", production.getInt("ledgerAppliedCount"))
            .put("ledgerAfterReleaseCount", production.getInt("ledgerAfterReleaseCount"))
            .put("freshJournalEmpty", true).put("nativeEditorVerified", final.getBoolean("nativeEditorVerified"))
            .put("nativeEditorValueVerified", final.getBoolean("nativeEditorValueVerified")))
        record("editor-complete")
    }
    // The same ticket session's remote browser, driven only through its pane: address, Go, text and Enter.
    private fun exerciseBrowser(head: String) {
        try { exerciseBrowserSteps(head) } catch (failure: Throwable) {
            // Booleans, counts and sizes only; no URL, nonce or page content.
            runCatching {
                val view = repository.state.value.browser
                val viewport = runCatching { compose.onNodeWithTag("browserViewport").fetchSemanticsNode().size.toString() }.getOrNull()
                proof.put("browserFailure", JSONObject().put("viewPresent", view != null).put("opening", view?.opening ?: JSONObject.NULL)
                    .put("browserIdPresent", view?.browserId != null).put("ownsControl", view?.ownsControl ?: JSONObject.NULL)
                    .put("tabCount", view?.tabs?.size ?: 0).put("selectedTabPresent", view?.selectedTabId != null)
                    .put("imagePresent", view?.image != null).put("viewportRevision", view?.viewportRevision ?: JSONObject.NULL)
                    .put("imageViewportRevision", view?.image?.viewportRevision ?: JSONObject.NULL)
                    .put("moving", view?.moving ?: JSONObject.NULL).put("notice", view?.notice?.toString() ?: JSONObject.NULL)
                    .put("inputPending", view?.inputPending ?: JSONObject.NULL).put("viewportNodeSize", viewport ?: JSONObject.NULL))
                record(proof.optString("stage", "browser"))
            }
            runCatching { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.getExternalFilesDir(null), "combined-native-browser-failure.png")) }
            throw failure
        }
    }
    private fun exerciseBrowserSteps(head: String) {
        val pageUrl = fixture.getString("browserPageUrl").toHttpUrl()
        val nonce = fixture.getString("browserNonce")
        require(nonce.matches(Regex("[A-Za-z0-9]{24}")))
        require(pageUrl.scheme == "http" && pageUrl.host == "127.0.0.1" && pageUrl.encodedPath == "/")
        val before = fullStatus().getJSONObject("browser")
        assertTrue(before.getBoolean("enabled"))
        assertEquals(0, before.getInt("opens"))
        assertTrue(before.isNull("submitted"))
        record("browser")
        compose.waitUntil(60_000) {
            repository.state.value.let { it.selection == selection && it.connected && SESSION_BROWSER_CAPABILITY in it.capabilities }
        }
        chatAction("browser")
        compose.waitUntil(60_000) { repository.state.value.browser?.let { !it.opening && it.browserId != null } == true }
        awaitTag("browserAcquire")
        compose.onNodeWithTag("browserAcquire").performClick()
        // A fresh browser has no tab; Go needs one, so open it as a user would.
        if (repository.state.value.browser?.tabs?.isEmpty() == true) {
            awaitTag("browserNewTab")
            compose.onNodeWithTag("browserNewTab").assertIsEnabled().performClick()
        }
        // Go is ignored while the acquire's viewport change is in flight; wait for the frame a user would see.
        compose.waitUntil(60_000) {
            repository.state.value.browser?.let { it.ownsControl && it.selectedTabId != null && it.image?.viewportRevision == it.viewportRevision } == true
        }
        compose.onNodeWithTag("browserAddress").performTextReplacement(pageUrl.toString())
        // The keyboard resizes the remote viewport, and Go is ignored until that change lands.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) {
            ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                repository.state.value.browser?.let { !it.moving && it.image?.viewportRevision == it.viewportRevision } == true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("browserGo").assertIsEnabled().performClick()
        compose.waitUntil(60_000) {
            repository.state.value.browser?.let { view ->
                view.image != null && view.tabs.any { it.id == view.selectedTabId && runCatching { it.url.toHttpUrl() == pageUrl }.getOrDefault(false) }
            } == true
        }
        compose.onNodeWithTag("browserText").performTextInput(nonce)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("browserSendText").performClick()
        compose.waitUntil(30_000) { statusOrNull()?.getJSONObject("browser")?.optString("input") == nonce && repository.state.value.browser?.inputPending == false }
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Enter").performClick()
        compose.waitUntil(30_000) { statusOrNull()?.getJSONObject("browser")?.optString("submitted") == nonce }
        // The page turns green on submit; the frame the phone shows must reflect it.
        compose.waitUntil(30_000) {
            repository.state.value.browser?.image?.jpeg?.let { bytes ->
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height - 1).also { bitmap.recycle() }
                    android.graphics.Color.green(pixel) > 200 && android.graphics.Color.red(pixel) < 60 && android.graphics.Color.blue(pixel) < 60
                }
            } == true
        }
        val observed = fullStatus().getJSONObject("browser")
        // Showing the pane re-attaches the same browser, so opens can exceed one; the fixture nulls
        // the session ID if any open named another session.
        assertEquals(selection.sessionId, observed.getString("sessionId"))
        // Two observed (open and re-attach); more points to a re-open loop.
        assertTrue(observed.getInt("opens") in 1..3)
        assertTrue(observed.getInt("deviceAcquires") >= 1)
        assertTrue(observed.getInt("deviceTextInputs") >= 1)
        assertEquals(nonce, observed.getString("input"))
        assertEquals(nonce, observed.getString("submitted"))
        assertGuards(status(), head, head, 1, 1, 1)
        proof.put("browser", JSONObject().put("sessionMatched", true).put("opens", observed.getInt("opens"))
            .put("deviceAcquires", observed.getInt("deviceAcquires")).put("deviceTextInputs", observed.getInt("deviceTextInputs"))
            .put("submittedMatched", true).put("greenFrame", true))
        record("browser-complete")
    }
    // The same session's dev-server preview: port dialog, then the preview pane's own controls.
    private fun exercisePreview(head: String) {
        val port = fixture.getInt("previewPort")
        val nonce = fixture.getString("previewNonce")
        require(port in 1..65535 && nonce.matches(Regex("[A-Za-z0-9]{24}")))
        val before = fullStatus().getJSONObject("preview")
        assertTrue(before.getBoolean("enabled"))
        assertEquals(port, before.getInt("port"))
        assertEquals(0, before.getInt("opens"))
        record("preview")
        try {
            // A browser pane left open by the previous stage covers the chat header on a phone.
            if (exists("browserClose")) {
                compose.onNodeWithTag("browserClose").performClick()
                compose.waitUntil(30_000) { !exists("browserPane") }
            }
            compose.waitUntil(60_000) { repository.state.value.let { it.selection == selection && it.connected && SESSION_PREVIEW_CAPABILITY in it.capabilities } }
            chatAction("preview")
            awaitTag("previewPortField")
            compose.onNodeWithTag("previewPortField").performTextReplacement(port.toString())
            compose.onNodeWithTag("previewOpen").assertIsEnabled().performClick()
            compose.waitUntil(60_000) { repository.state.value.previewBrowser?.let { !it.opening && it.browserId != null && it.previewPort == port } == true }
            awaitTag("browserAcquire")
            compose.onNodeWithTag("browserAcquire").performClick()
            compose.waitUntil(60_000) {
                repository.state.value.previewBrowser?.let { view ->
                    view.ownsControl && view.image?.viewportRevision == view.viewportRevision &&
                        view.tabs.any { it.id == view.selectedTabId && runCatching { it.url.toHttpUrl().let { u -> u.host == "127.0.0.1" && u.port == port } }.getOrDefault(false) }
                } == true
            }
            compose.onNodeWithTag("browserText").performTextInput(nonce)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("browserSendText").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithTag("browserSendText").performClick()
            compose.waitUntil(30_000) { statusOrNull()?.getJSONObject("preview")?.optString("input") == nonce && repository.state.value.previewBrowser?.inputPending == false }
            compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Enter").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithText("Enter").performClick()
            compose.waitUntil(30_000) { statusOrNull()?.getJSONObject("preview")?.optString("submitted") == nonce }
            // The dev server pushes an update over its WebSocket; the page turns magenta and acknowledges.
            action("preview-update")
            compose.waitUntil(30_000) {
                statusOrNull()?.getJSONObject("preview")?.optInt("updateAck") == 1 &&
                    repository.state.value.previewBrowser?.image?.jpeg?.let { bytes ->
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height - 1).also { bitmap.recycle() }
                            android.graphics.Color.red(pixel) > 200 && android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.green(pixel) < 60
                        }
                    } == true
            }
        } catch (failure: Throwable) {
            runCatching {
                val view = repository.state.value.previewBrowser
                proof.put("previewFailure", JSONObject().put("viewPresent", view != null).put("opening", view?.opening ?: JSONObject.NULL)
                    .put("browserIdPresent", view?.browserId != null).put("portMatches", view?.previewPort == port)
                    .put("ownsControl", view?.ownsControl ?: JSONObject.NULL).put("tabCount", view?.tabs?.size ?: 0)
                    .put("imagePresent", view?.image != null).put("viewportRevision", view?.viewportRevision ?: JSONObject.NULL)
                    .put("imageViewportRevision", view?.image?.viewportRevision ?: JSONObject.NULL).put("notice", view?.notice?.toString() ?: JSONObject.NULL))
                record(proof.optString("stage", "preview"))
            }
            runCatching { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(app.getExternalFilesDir(null), "combined-native-preview-failure.png")) }
            throw failure
        }
        val observed = fullStatus().getJSONObject("preview")
        assertEquals(selection.sessionId, observed.getString("sessionId"))
        assertTrue(observed.getInt("opens") in 1..3)
        assertTrue(observed.getInt("deviceAcquires") >= 1)
        assertTrue(observed.getInt("deviceTextInputs") >= 1)
        assertEquals(nonce, observed.getString("submitted"))
        assertEquals(1, observed.getInt("updateAck"))
        assertGuards(status(), head, head, 1, 1, 1)
        proof.put("preview", JSONObject().put("sessionMatched", true).put("portMatched", true).put("opens", observed.getInt("opens"))
            .put("deviceAcquires", observed.getInt("deviceAcquires")).put("deviceTextInputs", observed.getInt("deviceTextInputs"))
            .put("submittedMatched", true).put("updateAcknowledged", true).put("magentaFrame", true))
        record("preview-complete")
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
