package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
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
import kotlinx.serialization.json.JsonObject
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
class PagedEditorOwnerLossEndToEndTest {
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
    private lateinit var gate: OwnerLossCommitObserver
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source = ""
    private val finalValue = "é\\\n".repeat(262_144)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private owner-loss fixture required", args.getString("pagedOwnerLossExpected") == "true")
            terminationKind = args.getString("pagedOwnerLossKindExpected") ?: "abrupt"
            require(terminationKind in setOf("abrupt", "graceful"))
            nonce = requireNotNull(args.getString("pagedOwnerLossNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getBoolean("pagedEditorOwnerLossProof"))
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
            directory = File(app.noBackupFilesDir, "paged-owner-loss-$nonce")
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
            gate = OwnerLossCommitObserver(SecureRemoteTransport(scope, client))
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

    @Test fun ownerLossRejectsCommitAfterRealLastSourceAck() {
        val before = status()
        assertTrue(before.getBoolean("ownerPidVerified"))
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
        val finalStatus = statusWithStats()
        assertSdkResultAfterLoss(finalStatus)
        val proofElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        assertTrue("Observation must precede upload expiry", proofElapsedMs in 0L until 50_000L)
        assertTrue(finalStatus.getLong("ownerLossElapsedMs") in 0L until 50_000L)
        val snapshot = persisted()
        assertEquals(attemptsBefore, snapshot.attempts)
        val attempt = snapshot.attempts.values.single()
        val draft = snapshot.drafts[key]?.text
        assertEquals(finalValue, draft)
        val attemptJson = JSONObject().put("attemptId", attempt.attemptId).put("commitRequestId", attempt.commitRequestId).put("uploadId", attempt.uploadId)
            .put("routeId", key.routeId).put("sessionId", selection.sessionId).put("questionId", id).put("grantId", attempt.grantId).put("createdAt", attempt.createdAt)
            .put("bytes", attempt.text.toByteArray(Charsets.UTF_8).size).put("sha256", digest(attempt.text))
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("native", native).put("terminationKind", terminationKind).put("sessionId", selection.sessionId).put("questionId", id).put("grantId", grant)
            .put("sourceBytes", 0).put("sourceSha256", digest(source)).put("finalBytes", finalValue.toByteArray(Charsets.UTF_8).size).put("finalSha256", digest(finalValue))
            .put("before", before).put("held", held).put("lost", lost).put("released", released).put("final", finalStatus)
            .put("commitRequest", JSONObject(request.toString())).put("commitResult", sanitizedResult).put("resultCode", resultCode)
            .put("commitPossibleWrite", gate.possibleWrite.get()).put("commitSendCount", gate.sendCount.get())
            .put("questionPresent", repository.state.value.questions.any { it.optionalText("id") == id }).put("connected", repository.state.value.connected)
            .put("draftBytes", draft!!.toByteArray(Charsets.UTF_8).size).put("draftSha256", digest(draft)).put("draftRetained", true)
            .put("cleanupElapsedMs", cleanupElapsedMs).put("proofElapsedMs", proofElapsedMs).put("cleanupObserved", true)
            .put("journalAttempt", attemptJson).put("attemptPreserved", true)
            .put("uploadCountsBeforeObservation", countsJson(countsBefore)).put("uploadCountsAfterObservation", countsJson(transport.uploadCounts())).put("answerAttempts", transport.questionAnswerAttempts.get())
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedOwnerLossProof", "nonce=$nonce pid=${Process.myPid()} question=$id") })
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
private class OwnerLossCommitObserver(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    val request = AtomicReference<JsonObject?>(null)
    val result = AtomicReference<JsonObject?>(null)
    val possibleWrite = java.util.concurrent.atomic.AtomicBoolean(false)
    val sendCount = java.util.concurrent.atomic.AtomicInteger()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                val original = request.get()
                if (original != null && payload.optionalText("type") == "result" && payload.optionalText("requestId") == original.optionalText("requestId")) result.compareAndSet(null, payload)
                value.message(payload)
            }
        } }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        if (payload.optionalText("type") != "session.question.editor.upload.commit") return delegate.sendGuarded(payload, beforePossibleWrite)
        check(request.compareAndSet(null, payload)) { "Unexpected second commit" }
        delegate.sendGuarded(payload) {
            beforePossibleWrite()
            sendCount.incrementAndGet()
            possibleWrite.set(true)
        }
    }
}
