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

/** Opt-in proof against a real Pi owner, a second paired device and the Android repository. */
class PagedEditorSettlementEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var mode: String
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var gate: SettlementCommitGate
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source = ""
    private val finalValue = "é\\\n".repeat(262_144)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            mode = args.getString("pagedSettlementExpected") ?: ""
            assumeTrue("Explicit private settlement fixture required", mode in setOf("commit-wins", "cancel-wins"))
            nonce = requireNotNull(args.getString("pagedSettlementNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getString("pagedSettlementMode") == mode)
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
            directory = File(app.noBackupFilesDir, "paged-settlement-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Settlement fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            gate = SettlementCommitGate(SecureRemoteTransport(scope, client), mode == "cancel-wins")
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

    @Test fun competingDevicesSettleOnlyOnce() {
        val before = status()
        assertTrue(before.getBoolean("ownerPidVerified"))
        if (native) assertTrue(before.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-settlement-fixture") }
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
        action("prepare-editor-competitor")
        awaitPhase("ready")
        val ready = status()
        assertTrue(ready.getBoolean("competitorConnected"))
        assertNotEquals(host.deviceId, ready.getString("competitorDeviceId"))
        assertEquals(id, ready.getString("competitorQuestionId"))
        val uploadStartedAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { if (mode == "commit-wins") statusOrNull()?.optBoolean("pagedCommitHeld") == true && gate.request.get() != null else gate.held.get() }
        val held = statusWithStats()
        val request = requireNotNull(gate.request.get())
        assertEquals(id, request.text("questionId")); assertEquals(selection.sessionId, request.text("sessionId"))
        assertEquals(1, transport.uploadCounts().first)
        assertTrue(transport.uploadCounts().second > 0)
        if (mode == "cancel-wins") {
            assertEquals(1_048_576, held.getJSONObject("pagedUploadStats").getInt("bytes"))
            assertEquals(1, held.getJSONObject("pagedUploadStats").getInt("count"))
        }
        assertEquals(finalValue, persisted().drafts[key]?.text)
        val heldAttempt = persisted().attempts.values.single()
        assertEquals(key, heldAttempt.key); assertEquals(request.text("requestId"), heldAttempt.commitRequestId)
        assertEquals(finalValue, heldAttempt.text)
        action("compete-editor-cancel")
        awaitPhase("competed")
        val competed = statusWithStats()
        val cancel = competed.getJSONObject("competitorCancelResult")
        assertTrue(competed.getString("competitorCancelRequestId").isNotEmpty())
        assertEquals(competed.getString("competitorCancelRequestId"), cancel.getString("requestId"))
        if (mode == "commit-wins") {
            assertFalse(cancel.getBoolean("ok")); assertEquals("internal", cancel.getJSONObject("error").getString("code")); assertEquals("outcome_unknown", cancel.getJSONObject("error").getString("reason"))
            assertFalse(competed.getBoolean("competitorCancelAccepted"))
            assertEquals(0, competed.getInt("settlementSourceAnswerCount"))
            assertFalse(competed.getBoolean("pagedSdkResultVerified"))
            action("release-paged-commit")
            compose.waitUntil(120_000) {
                statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                    transport.pagedCommitAccepted(id, grant, finalValue.toByteArray(Charsets.UTF_8).size, digest(finalValue))
            }
            compose.waitUntil(60_000) { persisted().attempts.isEmpty() && repository.state.value.questions.none { it.optionalText("id") == id } }
        } else {
            assertTrue(cancel.getBoolean("ok")); assertTrue(competed.getBoolean("competitorCancelAccepted"))
            compose.waitUntil(60_000) { statusOrNull()?.optBoolean("competitorCancelledSdkVerified") == true }
            compose.waitUntil(60_000) { repository.state.value.questions.none { it.optionalText("id") == id } }
            gate.release.complete(Unit)
            compose.waitUntil(60_000) { gate.finished.get() }
            compose.waitUntil(60_000) { persisted().attempts.isEmpty() }
            assertFalse("Closed question must fail the real guarded send before a possible write", gate.possibleWrite.get())
            assertTrue("The original guarded send must fail at its validation callback", gate.validationFailed.get())
            assertNull(gate.result.get())
            assertFalse(transport.pagedCommitAccepted(id, grant, finalValue.toByteArray(Charsets.UTF_8).size, digest(finalValue)))
            assertEquals(finalValue, persisted().drafts[key]?.text)
        }
        compose.waitUntil(60_000) { statusOrNull(true)?.getJSONObject("pagedUploadStats")?.let { it.optInt("count", -1) == 0 && it.optLong("bytes", -1) == 0L } == true }
        val finalStatus = statusWithStats()
        val cleanupElapsedMs = SystemClock.elapsedRealtime() - uploadStartedAt
        assertTrue("Cleanup must precede the 60-second upload inactivity expiry", cleanupElapsedMs in 0L until 50_000L)
        assertEquals(if (mode == "commit-wins") 0 else 1, finalStatus.getInt("settlementSourceAnswerCount"))
        assertEquals(if (mode == "commit-wins") 1 else 0, finalStatus.getInt("pagedCommitCount"))
        val countsBefore = transport.uploadCounts()
        val attemptsBefore = persisted().attempts
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertEquals(countsBefore, transport.uploadCounts())
            assertEquals(0, transport.questionAnswerAttempts.get())
            assertEquals(attemptsBefore, persisted().attempts)
            assertEquals(finalStatus.getInt("pagedCommitCount"), status().getInt("pagedCommitCount"))
            SystemClock.sleep(200)
        }
        assertTrue("Final observation must precede upload expiry", SystemClock.elapsedRealtime() - uploadStartedAt in 0L until 50_000L)
        val snapshot = persisted()
        val attempts = org.json.JSONArray()
        snapshot.attempts.values.forEach { attempt -> attempts.put(JSONObject().put("attemptId", attempt.attemptId).put("commitRequestId", attempt.commitRequestId).put("uploadId", attempt.uploadId).put("bytes", attempt.text.toByteArray(Charsets.UTF_8).size).put("sha256", digest(attempt.text))) }
        val draft = snapshot.drafts[key]?.text
        val proof = JSONObject().put("nonce", nonce).put("mode", mode).put("order", mode).put("pid", Process.myPid()).put("questionId", id)
            .put("sourceBytes", 0).put("sourceSha256", digest(source)).put("finalBytes", finalValue.toByteArray(Charsets.UTF_8).size).put("finalSha256", digest(finalValue))
            .put("before", before).put("ready", ready).put("held", held).put("competed", competed).put("final", finalStatus)
            .put("commitRequest", JSONObject(request.toString())).put("commitResult", gate.result.get()?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("commitPossibleWrite", gate.possibleWrite.get()).put("commitGateCancelled", gate.cancelled.get()).put("commitGuardFailed", gate.validationFailed.get())
            .put("draftBytes", draft?.toByteArray(Charsets.UTF_8)?.size ?: -1).put("draftSha256", draft?.let { digest(it) } ?: "")
            .put("cleanupElapsedMs", cleanupElapsedMs).put("proofElapsedMs", SystemClock.elapsedRealtime() - uploadStartedAt)
            .put("journalAttempts", attempts).put("cleanupVerified", snapshot.attempts.isEmpty())
            .put("uploadCountsBeforeObservation", countsJson(countsBefore)).put("uploadCountsAfterObservation", countsJson(transport.uploadCounts())).put("answerAttempts", transport.questionAnswerAttempts.get())
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedSettlementProof", "mode=$mode nonce=$nonce pid=${Process.myPid()} question=$id") })
    }
    private fun countsJson(counts: Triple<Int, Int, Int>) = org.json.JSONArray().put(counts.first).put(counts.second).put(counts.third)
    private fun awaitPhase(phase: String) {
        compose.waitUntil(120_000) { statusOrNull()?.let { check(it.optString("settlementPhase") != "failed") { "Settlement failed: $it" }; it.optString("settlementPhase") == phase } == true }
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
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", if (value == "release-paged-commit") 200 else 202, it.code) }
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

/** Holds only the real commit prior to the delegate's guarded possible-write boundary. */
private class SettlementCommitGate(private val delegate: RemoteTransport, private val hold: Boolean) : RemoteTransport by delegate {
    val request = AtomicReference<JsonObject?>(null)
    val result = AtomicReference<JsonObject?>(null)
    val held = java.util.concurrent.atomic.AtomicBoolean(false)
    val possibleWrite = java.util.concurrent.atomic.AtomicBoolean(false)
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val validationFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val release = CompletableDeferred<Unit>()
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
        try {
            if (hold) { held.set(true); release.await() }
            delegate.sendGuarded(payload) {
                try { beforePossibleWrite() } catch (e: Throwable) { validationFailed.set(true); throw e }
                possibleWrite.set(true)
            }
        } catch (e: CancellationException) { cancelled.set(true); throw e }
        finally { finished.set(true) }
    }
}
