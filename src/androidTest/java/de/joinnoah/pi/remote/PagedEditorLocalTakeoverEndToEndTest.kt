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
class PagedEditorLocalTakeoverEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var gate: LocalTakeoverCommitObserver
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source = "😀".repeat(262_144)
    private val finalValue = "é\\\n".repeat(1_024) + "😀".repeat(261_120)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private local takeover fixture required", args.getString("pagedLocalTakeoverExpected") == "true")
            nonce = requireNotNull(args.getString("pagedLocalTakeoverNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(native && fixture.getBoolean("nativeWorker") && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getBoolean("pagedEditorLocalTakeoverProof"))
            require(fixture.getString("pagedEditorCase") == "max-edited")
            require(!fixture.optBoolean("pagedEditorPositiveOutcomes"))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((name, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(name).toHttpUrl()
                require(url.scheme == "https" && url.host == relay.host && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            verifyMetadata("Source", source); verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-local-takeover-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Local takeover fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            gate = LocalTakeoverCommitObserver(SecureRemoteTransport(scope, client))
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

    @Test fun localTakeoverFencesOldUploadAndAcceptsFreshEditor() {
        val before = status()
        assertTrue(before.getBoolean("ownerPidVerified"))
        assertEquals(0, before.getInt("sourceSdkResultCount"))
        assertTrue(before.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-local-takeover-fixture") }
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
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("é\\\n".repeat(1_024))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == finalValue && persisted().drafts[key]?.text == finalValue }
        assertTrue(persisted().attempts.isEmpty())
        val uploadStartedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(45_000) { statusOrNull()?.optJSONObject("localTakeover")?.optString("phase") == "held" && gate.possibleWrite.get() && gate.request.get() != null }
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
        assertEquals(0, held.getInt("sourceSdkResultCount"))
        action("take-editor-local")
        awaitPhase("taken")
        val local = statusWithStats()
        val transition = local.getJSONObject("localTakeover")
        assertTrue(transition.getBoolean("leaseActiveBefore"))
        assertTrue(transition.getBoolean("leaseInactiveAfter"))
        assertTrue(transition.getBoolean("forgetObserved"))
        assertTrue(transition.getBoolean("leaseForgetActiveBefore"))
        assertTrue(transition.getBoolean("leaseForgetInactiveAfter"))
        assertTrue(transition.getBoolean("leaseForgetReaderUnchanged"))
        assertTrue(transition.getLong("leaseForgetAt") > 0L)
        assertTrue(transition.getLong("transitionMs") in 0L until 3_000L)
        assertTrue(transition.getBoolean("ownerIdentityUnchanged"))
        assertTrue(transition.getBoolean("captureInvalid"))
        assertTrue(transition.getBoolean("questionClosed"))
        assertTrue(transition.getBoolean("localEditorReady"))
        assertEquals(0, transition.getInt("oldSdkResultCount"))
        assertEquals(0, local.getInt("sourceSdkResultCount"))
        val questionRemovedAtTakeover = repository.state.value.questions.none { it.optionalText("id") == id }
        action("release-local-takeover-ack")
        awaitPhase("released")
        compose.waitUntil(45_000) { gate.result.get() != null && statusOrNull(true)?.getJSONObject("pagedUploadStats")?.let { it.optInt("count", -1) == 0 && it.optLong("bytes", -1) == 0L } == true }
        val released = statusWithStats()
        val releasedTakeover = released.getJSONObject("localTakeover")
        val result = requireNotNull(gate.result.get())
        assertEquals(request.text("requestId"), result.text("requestId"))
        assertFalse(result.flag("ok"))
        val resultError = result.obj("error")
        val resultCode = resultError.text("code")
        val sanitizedResult = JSONObject().put("type", result.text("type")).put("requestId", result.text("requestId")).put("ok", false)
            .put("error", JSONObject().put("code", resultCode).apply { resultError.optionalText("reason")?.let { put("reason", it) } })
        assertTrue(resultCode.isNotEmpty())
        assertTrue(releasedTakeover.getBoolean("ackReturned"))
        val ackBefore = releasedTakeover.getJSONObject("lastAckBefore")
        val ackAfter = releasedTakeover.getJSONObject("lastAckAfter")
        assertEquals(1_048_576, ackBefore.getInt("nextOffset"))
        assertEquals(ackBefore.getInt("nextOffset"), ackAfter.getInt("nextOffset"))
        assertEquals(ackBefore.getString("sha256"), ackAfter.getString("sha256"))
        assertEquals(1, gate.sendCount.get())
        assertEquals(0, releasedTakeover.getInt("oldSdkResultCount"))
        assertEquals(0, releasedTakeover.getInt("oldCommitCount"))
        assertEquals(0, released.getInt("sourceSdkResultCount"))
        assertFalse(transport.pagedCommitAccepted(id, grant, finalValue.toByteArray(Charsets.UTF_8).size, digest(finalValue)))
        val cleanupElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        assertTrue("Cleanup must precede the upload inactivity expiry", cleanupElapsedMs in 0L until 50_000L)
        val attemptsBefore = persisted().attempts
        assertEquals(1, attemptsBefore.size)
        assertEquals(heldAttempt, attemptsBefore.values.single())
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val questionRemovedBeforeCancel = repository.state.value.questions.none { it.optionalText("id") == id }
        val beforeCancel = statusWithStats()
        assertEquals(0, beforeCancel.getJSONObject("localTakeover").getInt("oldSdkResultCount"))
        assertEquals(0, beforeCancel.getInt("sourceSdkResultCount"))
        assertTrue(SystemClock.elapsedRealtime() - uploadStartedAt < 50_000L)
        action("cancel-local-editor")
        compose.waitUntil(60_000) { statusOrNull()?.optJSONObject("localTakeover")?.let { it.optBoolean("oldLocalCancelled") && it.optInt("oldSdkResultCount") == 1 } == true }
        assertEquals(selection, repository.state.value.selection)
        val newId = requireNotNull(selection.sessionId)
        val freshNonce = fixture.getString("localTakeoverProbeNonce")
        val freshValue = fixture.getString("localTakeoverExpectedValue")
        assertEquals(32, freshValue.toByteArray(Charsets.UTF_8).size)
        assertNotEquals(fixture.getString("editorProbeNonce"), freshNonce)
        assertNotEquals(finalValue, freshValue)
        assertEquals(fixture.getInt("localTakeoverExpectedBytes"), freshValue.toByteArray(Charsets.UTF_8).size)
        assertEquals(fixture.getString("localTakeoverExpectedSha256"), digest(freshValue))
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
            throw AssertionError("Local takeover composer not ready: ${current.status}, loading=${current.loading}, sending=${current.sending}, draftLength=${current.draft.length}", e)
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
        assertEquals("", repository.state.value.pagedEditors[freshId]?.text)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(freshValue)
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[freshId]?.text == freshValue }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.optJSONObject("localTakeover")?.let { it.optBoolean("freshSdkVerified") && it.optInt("freshSdkResultCount") == 1 } == true && gate.secondResult.get() != null }
        val freshRequest = requireNotNull(gate.secondRequest.get())
        val freshResult = requireNotNull(gate.secondResult.get())
        assertEquals(newId, freshRequest.text("sessionId")); assertEquals(freshId, freshRequest.text("questionId"))
        assertNotEquals(request.text("requestId"), freshRequest.text("requestId"))
        assertNotEquals(request.text("uploadId"), freshRequest.text("uploadId"))
        assertEquals(freshRequest.text("requestId"), freshResult.text("requestId"))
        assertTrue(freshResult.flag("ok")); assertEquals("accepted", freshResult.obj("data").text("kind"))
        val newStatus = status()
        val freshTakeover = newStatus.getJSONObject("localTakeover")
        assertTrue(freshTakeover.getBoolean("ownerIdentityUnchanged"))
        assertTrue(freshTakeover.getBoolean("freshLeaseDistinct"))
        assertTrue(freshTakeover.getBoolean("freshBeginVerified"))
        assertTrue(freshTakeover.getBoolean("freshReceipt"))
        assertEquals(1, freshTakeover.getInt("freshCommitCount"))
        assertEquals(1, freshTakeover.getInt("oldSdkResultCount"))
        assertEquals(1, freshTakeover.getInt("freshSdkResultCount"))
        assertEquals(2, newStatus.getInt("sourceSdkResultCount"))
        assertEquals(0, freshTakeover.getInt("oldCommitCount"))
        compose.waitUntil(60_000) { persisted().attempts == attemptsBefore }
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val countsAfterNew = transport.uploadCounts()
        val noReplayUntil = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < noReplayUntil) {
            assertEquals(countsAfterNew, transport.uploadCounts())
            assertEquals(attemptsBefore, persisted().attempts)
            assertEquals(finalValue, persisted().drafts[key]?.text)
            assertEquals(1, status().getJSONObject("localTakeover").getInt("oldSdkResultCount"))
            SystemClock.sleep(200)
        }
        // The Hosts entry reads the encrypted local journal even after navigating away.
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.remote_back)).performClick()
        compose.onNodeWithTag("openEditorRecovery").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("recoveryEntryDraft") and hasAnyDescendant(hasText(id, substring = true)), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("recoveryEntryUncertain") and hasAnyDescendant(hasText(id, substring = true)), useUnmergedTree = true).assertIsDisplayed().performClick()
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
            .put("before", before).put("held", held).put("local", local).put("released", released).put("beforeCancel", beforeCancel).put("final", finalStatus)
            .put("cleanupElapsedMs", cleanupElapsedMs).put("questionRemovedAtTakeover", questionRemovedAtTakeover).put("questionRemovedBeforeCancel", questionRemovedBeforeCancel)
            .put("sessionId", selection.sessionId).put("questionId", id).put("grantId", grant).put("journalAttempt", attemptJson)
            .put("commitRequest", JSONObject(request.toString())).put("commitResult", sanitizedResult)
            .put("newSessionId", newId).put("newQuestionId", freshId).put("newGrantId", freshGrant).put("newProbeNonce", freshNonce)
            .put("newCommitRequest", JSONObject(freshRequest.toString())).put("newCommitResult", JSONObject(freshResult.toString()))
            .put("newBytes", freshValue.toByteArray(Charsets.UTF_8).size).put("newSha256", digest(freshValue))
            .put("attemptPreserved", true).put("draftRetained", true).put("recoveryUiObserved", true)
            .put("recoveryFirstLength", first.length).put("recoveryLastLength", last.length)
            .put("transport", org.json.JSONArray(gate.observations.map { JSONObject(it.toString()) }))
            .put("uploadCountsBeforeObservation", countsJson(countsAfterNew)).put("uploadCountsAfterObservation", countsJson(transport.uploadCounts()))
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedLocalTakeoverProof", "nonce=$nonce pid=${Process.myPid()} old=$id new=$freshId") })
    }

    private fun countsJson(counts: Triple<Int, Int, Int>) = org.json.JSONArray().put(counts.first).put(counts.second).put(counts.third)
    private fun awaitPhase(phase: String) {
        compose.waitUntil(45_000) { statusOrNull()?.optJSONObject("localTakeover")?.let { check(it.optString("phase") != "failed") { "Local takeover failed: $it" }; it.optString("phase") == phase } == true }
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
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", if (value == "take-editor-local") 202 else 200, it.code) }
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
private class LocalTakeoverCommitObserver(private val delegate: RemoteTransport) : RemoteTransport by delegate {
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
