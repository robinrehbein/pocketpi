package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** Opt-in proof against a real Pi owner and the Android repository. */
class PagedEditorResumeEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var terminationKind: String
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var gate: ResumeCommitObserver
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source = ""
    private val finalValue = "é\\\n".repeat(262_144)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private owner-loss fixture required", args.getString("pagedResumeExpected") == "true")
            terminationKind = args.getString("pagedOwnerLossKindExpected") ?: "abrupt"
            require(terminationKind in setOf("abrupt", "graceful"))
            nonce = requireNotNull(args.getString("pagedResumeNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getBoolean("pagedEditorOwnerLossProof") && fixture.getBoolean("pagedEditorResumeProof"))
            require(fixture.getString("ownerLossKind") == terminationKind)
            require(terminationKind != "graceful" || !native)
            require(fixture.getString("pagedEditorCase") == "empty-expand")
            require(!fixture.optBoolean("pagedEditorPositiveOutcomes"))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((name, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(name).toHttpUrl()
                require(url.scheme == "https" && url.host == relay.host && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            verifyMetadata("Source", source); verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-resume-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Owner-loss fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            gate = ResumeCommitObserver(SecureRemoteTransport(scope, client))
            transport = PresenceObservingTransport(gate, requireNotNull(selection.sessionId), true, false)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, transport, scope, pagedEditorTestGate = true, pagedEditorStorage = stores.pagedEditor, positiveOutcomeTestGate = false)
            NavigationTestActivity.contentFactory = { { if (showNavigation.value) MaterialTheme {
                RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
            } } }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun resumeSameSessionAcceptsOnlyFreshEditorWhileRetainingOldRecovery() {
        val before = status()
        assertTrue(before.getBoolean("ownerPidVerified"))
        assertEquals("abrupt", terminationKind)
        assertEquals(terminationKind, before.getString("ownerLossKind"))
        assertEquals(0, before.getInt("sourceSdkResultCount"))
        if (native) assertTrue(before.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-owner-loss-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) {
            val state = repository.state.value
            state.selection == selection && state.connected && !state.loading && QUESTION_PAGED_EDITOR_CAPABILITY in state.capabilities && transport.pagedV3Accepted.get() && (!native || transport.presenceAccepted.get())
        }
        val command = fixture.getString("editorProbeCommand")
        val name = requireNotNull(commandName(command))
        compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == name } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command ${fixture.getString("editorProbeNonce")}")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id")
        val grant = question.text("editorUploadGrantId")
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        assertEquals(source, repository.state.value.pagedEditors[id]?.text)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(finalValue)
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == finalValue && persisted().drafts[key]?.text == finalValue }
        assertTrue(persisted().attempts.isEmpty())
        val uploadStartedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(45_000) { statusOrNull()?.optString("ownerLossPhase") == "held" && gate.possibleWrite.get() && gate.request.get() != null }
        val held = statusWithStats()
        val request = requireNotNull(gate.request.get())
        assertEquals(id, request.text("questionId")); assertEquals(selection.sessionId, request.text("sessionId"))
        assertEquals(Triple(1, transport.uploadCounts().second, 1), transport.uploadCounts())
        assertTrue(transport.uploadCounts().second > 0)
        assertEquals(1_048_576, held.getJSONObject("pagedUploadStats").getInt("bytes"))
        assertEquals(1, held.getJSONObject("pagedUploadStats").getInt("count"))
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val heldAttempt = persisted().attempts.values.single()
        assertEquals(key, heldAttempt.key); assertEquals(request.text("requestId"), heldAttempt.commitRequestId)
        assertEquals(grant, heldAttempt.grantId)
        assertEquals(finalValue, heldAttempt.text)
        assertEquals(terminationKind, held.getString("ownerLossKind"))
        assertTrue(held.getBoolean("ownerLossHistoricalOwnerPidVerifiedBeforeLoss"))
        assertTrue(held.getInt("ownerLossOwnerPid") > 0)
        assertEquals(0, held.getInt("sourceSdkResultCount"))
        action("lose-editor-owner")
        awaitPhase("lost")
        val lost = statusWithStats()
        assertTrue(lost.getBoolean("ownerLossOwnerExited")); assertTrue(lost.getBoolean("ownerLossPeerDetached")); assertTrue(lost.getBoolean("ownerLossCaptureInvalid"))
        assertEquals(held.getInt("ownerLossOwnerPid"), lost.getInt("ownerLossOwnerPid"))
        assertFalse(lost.getBoolean("ownerPidVerified"))
        assertSdkResultAfterLoss(lost)
        action("resume-editor-owner")
        compose.waitUntil(120_000) {
            statusOrNull()?.optJSONObject("resume")?.let {
                check(it.isNull("failure")) { "Resume fixture failed: ${it.optString("failure")}" }
                it.optBoolean("registered") && it.optBoolean("ownerVerified") && it.optBoolean("bridgeVerified") && it.optBoolean("sameFileVerified")
            } == true
        }
        val resumed = status().getJSONObject("resume")
        assertTrue(resumed.getBoolean("requested") && resumed.getBoolean("registered"))
        assertTrue(resumed.getInt("pid") > 0 && resumed.getInt("pid") != lost.getInt("ownerLossOwnerPid"))
        assertEquals(resumed.getString("oldFile"), resumed.getString("newFile"))
        assertEquals(selection.sessionId, resumed.getString("sessionId"))
        assertEquals(0, resumed.getInt("commitCount"))
        assertEquals(0, status().getInt("resumeOldSdkResultCount"))
        assertEquals(selection.sessionId, repository.state.value.selection.sessionId)
        action("release-editor-owner-ack")
        awaitPhase("released")
        compose.waitUntil(45_000) { gate.result.get() != null && statusOrNull(true)?.getJSONObject("pagedUploadStats")?.let { it.optInt("count", -1) == 0 && it.optLong("bytes", -1) == 0L } == true }
        val released = statusWithStats()
        val result = requireNotNull(gate.result.get())
        assertEquals(request.text("requestId"), result.text("requestId"))
        assertFalse(result.flag("ok"))
        val resultError = result.obj("error")
        val resultCode = resultError.text("code")
        val sanitizedResult = JSONObject().put("type", result.text("type")).put("requestId", result.text("requestId")).put("ok", false)
            .put("error", JSONObject().put("code", resultCode).apply { resultError.optionalText("reason")?.let { put("reason", it) } })
        assertTrue(resultCode.isNotEmpty())
        assertTrue(released.getBoolean("ownerLossAckReturned"))
        val ackBefore = released.getJSONObject("ownerLossLastAckBefore")
        val ackAfter = released.getJSONObject("ownerLossLastAckAfter")
        assertEquals(1_048_576, ackBefore.getInt("nextOffset"))
        assertEquals(ackBefore.getInt("nextOffset"), ackAfter.getInt("nextOffset"))
        assertEquals(ackBefore.getString("sha256"), ackAfter.getString("sha256"))
        assertEquals(1, gate.sendCount.get())
        assertSdkResultAfterLoss(released)
        assertEquals(0, released.getInt("pagedCommitCount"))
        assertFalse(transport.pagedCommitAccepted(id, grant, finalValue.toByteArray(Charsets.UTF_8).size, digest(finalValue)))
        val cleanupElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        assertTrue("Cleanup must precede the upload inactivity expiry", cleanupElapsedMs in 0L until 50_000L)
        val countsBefore = transport.uploadCounts()
        val attemptsBefore = persisted().attempts
        assertEquals(1, attemptsBefore.size)
        assertEquals(heldAttempt, attemptsBefore.values.single())
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertEquals(countsBefore, transport.uploadCounts())
            assertEquals(0, transport.questionAnswerAttempts.get())
            assertEquals(attemptsBefore, persisted().attempts)
            val current = status()
            assertEquals(0, current.getInt("pagedCommitCount")); assertSdkResultAfterLoss(current)
            SystemClock.sleep(200)
        }
        val lossFinalBeforeResume = statusWithStats()
        val lossProofElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        assertTrue("Old loss proof must finish before upload expiry", lossProofElapsedMs in 0L until 50_000L)
        assertTrue(lossFinalBeforeResume.getLong("ownerLossElapsedMs") in 0L until 50_000L)
        assertEquals(0, lossFinalBeforeResume.getJSONObject("pagedUploadStats").getInt("count"))
        assertEquals(0, lossFinalBeforeResume.getJSONObject("pagedUploadStats").getInt("bytes"))
        // The actual replacement was registered in the same session before releasing the old ACK.
        assertEquals(selection, repository.state.value.selection)
        val newId = requireNotNull(selection.sessionId)
        val freshNonce = fixture.getString("resumeProbeNonce")
        val freshValue = fixture.getString("resumeExpectedValue")
        assertEquals(32, freshValue.toByteArray(Charsets.UTF_8).size)
        assertNotEquals(fixture.getString("editorProbeNonce"), freshNonce)
        assertNotEquals(finalValue, freshValue)
        assertEquals(fixture.getInt("resumeExpectedBytes"), freshValue.toByteArray(Charsets.UTF_8).size)
        assertEquals(fixture.getString("resumeExpectedSha256"), digest(freshValue))
        compose.waitUntil(60_000) { repository.state.value.connected && repository.state.value.commands.any { it.name == name } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").performTextReplacement("$command $freshNonce")
        try {
            compose.waitUntil(60_000) { canSendDraft(repository.state.value) }
        } catch (e: Throwable) {
            val current = repository.state.value
            val diagnostic = JSONObject().put("status", current.status).put("connected", current.connected)
                .put("loading", current.loading).put("sending", current.sending)
                .put("importingAttachments", current.importingAttachments).put("configurationChanging", current.configurationChanging)
                .put("bashRunning", current.bash?.running == true).put("draftLength", current.draft.length)
                .put("questions", org.json.JSONArray(current.questions.map { it.optionalText("id") }))
                .put("transport", org.json.JSONArray(gate.observations.map { JSONObject(it.toString()) }))
            File(directory, "not-ready.json").writeText(diagnostic.toString(2))
            throw AssertionError("Resumed composer not ready: ${current.status}, loading=${current.loading}, sending=${current.sending}, draftLength=${current.draft.length}", e)
        }
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.optionalText("id") != id && it.containsKey("editorUploadGrantId") } }
        val freshQuestion = repository.state.value.questions.single { it.optionalText("kind") == "editor" && it.optionalText("id") != id }
        val freshId = freshQuestion.text("id")
        val freshGrant = freshQuestion.text("editorUploadGrantId")
        assertNotEquals(id, freshId); assertNotEquals(grant, freshGrant)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[freshId]?.text != null }
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(freshValue)
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[freshId]?.text == freshValue }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.let { it.optBoolean("resumeSdkVerified") && it.optJSONObject("resume")?.optBoolean("commitReceiptVerified") == true } == true && gate.secondResult.get() != null }
        val freshRequest = requireNotNull(gate.secondRequest.get())
        val freshResult = requireNotNull(gate.secondResult.get())
        assertEquals(newId, freshRequest.text("sessionId")); assertEquals(freshId, freshRequest.text("questionId"))
        assertNotEquals(request.text("requestId"), freshRequest.text("requestId"))
        assertNotEquals(request.text("uploadId"), freshRequest.text("uploadId"))
        assertEquals(freshRequest.text("requestId"), freshResult.text("requestId"))
        assertTrue(freshResult.flag("ok")); assertEquals("accepted", freshResult.obj("data").text("kind"))
        val newStatus = status()
        val resume = newStatus.getJSONObject("resume")
        assertTrue(newStatus.getBoolean("resumeProbeStarted") && newStatus.getBoolean("resumeSdkVerified"))
        assertEquals(0, newStatus.getInt("resumeOldSdkResultCount"))
        assertEquals(1, resume.getInt("commitCount"))
        assertEquals(freshId, resume.getString("questionId"))
        if (native) assertNotEquals(newStatus.getString("ownerLossRawLeaseId"), resume.getString("leaseId"))
        assertTrue(resume.getBoolean("captureVerified"))
        compose.waitUntil(60_000) { persisted().attempts == attemptsBefore }
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val countsAfterNew = transport.uploadCounts()
        val noReplayUntil = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < noReplayUntil) {
            assertEquals(countsAfterNew, transport.uploadCounts())
            assertEquals(attemptsBefore, persisted().attempts)
            assertEquals(finalValue, persisted().drafts[key]?.text)
            assertEquals(0, status().getInt("resumeOldSdkResultCount"))
            SystemClock.sleep(200)
        }
        // The Hosts entry reads the encrypted local journal even after navigating away.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithTag("openEditorRecovery").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("recoveryEntryDraft") and hasAnyDescendant(hasText(id, substring = true)), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("recoveryEntryUncertain").assertIsDisplayed().performClick()
        compose.onNodeWithTag("recoveryUncertainWarning").assertIsDisplayed()
        fun preview(): String = requireNotNull(compose.onNodeWithTag("recoveryPreview").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)).joinToString("") { it.text }
        val first = preview()
        assertTrue(first.isNotEmpty() && first.length <= 2048 && finalValue.startsWith(first))
        compose.onNodeWithTag("recoveryLast").performScrollTo().performClick()
        val last = preview()
        assertTrue(last.isNotEmpty() && last.length <= 2048 && finalValue.endsWith(last))
        compose.onNodeWithTag("recoveryBackToList").performScrollTo().performClick()
        compose.onNode(hasTestTag("recoveryEntryDraft") and hasAnyDescendant(hasText(id, substring = true)), useUnmergedTree = true).performClick()
        assertTrue(preview().length <= 2048 && finalValue.startsWith(preview()))
        assertEquals(attemptsBefore, persisted().attempts)
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val finalStatus = statusWithStats()
        val attempt = attemptsBefore.values.single()
        val attemptJson = JSONObject().put("attemptId", attempt.attemptId).put("commitRequestId", attempt.commitRequestId).put("uploadId", attempt.uploadId)
            .put("routeId", key.routeId).put("sessionId", key.sessionId).put("questionId", id).put("grantId", attempt.grantId).put("createdAt", attempt.createdAt)
            .put("bytes", attempt.text.toByteArray(Charsets.UTF_8).size).put("sha256", digest(attempt.text))
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("native", native)
            .put("before", before).put("held", held).put("lost", lost).put("released", released).put("final", finalStatus)
            .put("cleanupElapsedMs", cleanupElapsedMs).put("lossProofElapsedMs", lossProofElapsedMs).put("lossFinalBeforeResume", lossFinalBeforeResume)
            .put("sessionId", selection.sessionId).put("questionId", id).put("grantId", grant).put("journalAttempt", attemptJson)
            .put("commitRequest", JSONObject(request.toString())).put("commitResult", sanitizedResult)
            .put("resumed", resumed)
            .put("newSessionId", newId).put("newQuestionId", freshId).put("newGrantId", freshGrant).put("newProbeNonce", freshNonce)
            .put("newCommitRequest", JSONObject(freshRequest.toString())).put("newCommitResult", JSONObject(freshResult.toString()))
            .put("newBytes", freshValue.toByteArray(Charsets.UTF_8).size).put("newSha256", digest(freshValue))
            .put("attemptPreserved", true).put("draftRetained", true).put("recoveryUiObserved", true)
            .put("recoveryFirstLength", first.length).put("recoveryLastLength", last.length)
            .put("transport", org.json.JSONArray(gate.observations.map { JSONObject(it.toString()) }))
            .put("uploadCountsBeforeObservation", countsJson(countsAfterNew)).put("uploadCountsAfterObservation", countsJson(transport.uploadCounts()))
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedResumeProof", "nonce=$nonce pid=${Process.myPid()} old=$id new=$freshId") })
    }

    private fun assertSdkResultAfterLoss(status: JSONObject) {
        assertEquals(terminationKind, status.getString("ownerLossKind"))
        assertEquals(if (terminationKind == "graceful") 1 else 0, status.getInt("sourceSdkResultCount"))
        if (terminationKind == "graceful") assertTrue(status.getBoolean("ownerLossSdkCancelledVerified"))
    }
    private fun countsJson(counts: Triple<Int, Int, Int>) = org.json.JSONArray().put(counts.first).put(counts.second).put(counts.third)
    private fun awaitPhase(phase: String) {
        compose.waitUntil(120_000) { statusOrNull()?.let { check(it.optString("ownerLossPhase") != "failed") { "Owner loss failed: $it" }; it.optString("ownerLossPhase") == phase } == true }
    }
    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun persisted() = stores.pagedEditor.load()
    private fun status(stats: Boolean = false): JSONObject {
        val url = fixture.getString("statusUrl").toHttpUrl().newBuilder().apply { if (stats) addQueryParameter("stats", "1") }.build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    }
    private fun statusWithStats() = status(true)
    private fun statusOrNull(stats: Boolean = false) = runCatching { status(stats) }.getOrNull()
    private fun action(value: String) {
        val body = JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", if (value == "release-editor-owner-ack") 200 else 202, it.code) }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun verifyMetadata(label: String, value: String) {
        assertEquals(fixture.getInt("pagedEditor${label}Bytes"), value.toByteArray(Charsets.UTF_8).size)
        assertEquals(fixture.getString("pagedEditor${label}Sha256"), digest(value))
    }
    private fun readFixture(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}

/** Observes the production guarded send and forwards the original listener unchanged. */
private class ResumeCommitObserver(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    val observations = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
    private fun observe(payload: JsonObject, direction: String) {
        if (observations.size >= 512) return
        observations.add(buildJsonObject {
            put("direction", direction)
            for (key in listOf("type", "kind", "sessionId", "requestId", "epoch", "revision", "status", "ok"))
                (payload[key] as? JsonPrimitive)?.let { put(key, it) }
            (payload["data"] as? JsonObject)?.let { data ->
                put("data", buildJsonObject {
                    for (key in listOf("kind", "sessionId", "epoch", "revision", "status"))
                        (data[key] as? JsonPrimitive)?.let { put(key, it) }
                })
            }
        })
    }
    val request = AtomicReference<JsonObject?>(null)
    val result = AtomicReference<JsonObject?>(null)
    val secondRequest = AtomicReference<JsonObject?>(null)
    val secondResult = AtomicReference<JsonObject?>(null)
    val possibleWrite = java.util.concurrent.atomic.AtomicBoolean(false)
    val sendCount = java.util.concurrent.atomic.AtomicInteger()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                observe(payload, "in")
                val original = request.get()
                if (original != null && payload.optionalText("type") == "result" && payload.optionalText("requestId") == original.optionalText("requestId")) result.compareAndSet(null, payload)
                val second = secondRequest.get()
                if (second != null && payload.optionalText("type") == "result" && payload.optionalText("requestId") == second.optionalText("requestId")) secondResult.compareAndSet(null, payload)
                value.message(payload)
            }
        } }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        observe(payload, "out")
        if (payload.optionalText("type") != "session.question.editor.upload.commit") return delegate.sendGuarded(payload, beforePossibleWrite)
        if (!request.compareAndSet(null, payload)) check(secondRequest.compareAndSet(null, payload)) { "Unexpected third commit" }
        delegate.sendGuarded(payload) {
            beforePossibleWrite()
            sendCount.incrementAndGet()
            possibleWrite.set(true)
        }
    }
    override fun send(payload: JsonObject) {
        observe(payload, "out")
        delegate.send(payload)
    }
}
