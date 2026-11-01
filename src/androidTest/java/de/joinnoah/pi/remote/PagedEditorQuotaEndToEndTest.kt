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
import java.util.concurrent.ConcurrentHashMap
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

/** Opt-in proof against a real Pi owner, host quota and production Android repository. */
class PagedEditorQuotaEndToEndTest {
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
    private lateinit var observer: QuotaBeginObserver
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source get() = if (mode == "bytes") "" else "draft"
    private val finalValue get() = if (mode == "bytes") "é\\\n".repeat(262_144) else ""

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            mode = args.getString("pagedQuotaExpected") ?: ""
            assumeTrue("Explicit private quota fixture required", mode in setOf("bytes", "slots"))
            nonce = requireNotNull(args.getString("pagedQuotaNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getString("pagedQuotaMode") == mode)
            require(fixture.getString("pagedEditorCase") == if (mode == "bytes") "empty-expand" else "empty-final")
            require(!fixture.optBoolean("pagedEditorPositiveOutcomes"))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((name, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(name).toHttpUrl()
                require(url.scheme == "https" && url.host == relay.host && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            verifyMetadata("Source", source); verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-quota-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Quota fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            observer = QuotaBeginObserver(SecureRemoteTransport(scope, client))
            transport = PresenceObservingTransport(observer, requireNotNull(selection.sessionId), true, false)
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

    @Test fun fullQuotaRefusesBeginAndExplicitRetryCommitsExactDraft() {
        val initial = status()
        assertTrue(initial.getBoolean("ownerPidVerified"))
        if (native) assertTrue(initial.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-quota-fixture") }
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
        val probeNonce = fixture.getString("editorProbeNonce")
        val name = requireNotNull(commandName(command))
        compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == name } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command $probeNonce")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id"); val grant = question.text("editorUploadGrantId")
        assertTrue(grant.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        assertEquals(source, repository.state.value.pagedEditors[id]?.text)
        if (mode == "slots") compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("")
        else compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(finalValue)
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == finalValue }
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        compose.waitUntil(60_000) { persisted().drafts[key]?.text == finalValue }
        assertTrue(persisted().attempts.isEmpty())
        val before = status()
        action("arm-editor-quota")
        compose.waitUntil(120_000) { statusOrNull()?.optString("pagedQuotaPhase") == "full" }
        val full = status(); assertQuota(full, "full", if (mode == "bytes") 8 else 32, if (mode == "bytes") 8_388_608 else 0)
        assertEquals(0, before.getInt("pagedQuotaCount")); assertEquals(0L, before.getLong("pagedQuotaBytes"))
        assertEquals(full.getInt("pagedQuotaCount"), full.getInt("pagedQuotaInitialCount")); assertEquals(full.getLong("pagedQuotaBytes"), full.getLong("pagedQuotaInitialBytes"))
        assertEquals(if (mode == "bytes") 16 else 40, full.getInt("pagedQuotaRelayMaxPeers"))
        assertEquals(0, full.getInt("pagedQuotaOwnerWritesBeforeRelease"))
        compose.runOnIdle {
            val state = repository.state.value
            val view = state.pagedEditors[id]
            println("QUOTA_SEND_DIAG connected=${state.connected} answering=${id in state.answering} block=${state.questionBlocks[id]} known=${state.capabilitiesKnown} available=${editorCapabilityAvailable(state.questions.single { it.optionalText("id") == id }, state)} capabilities=${state.capabilities} unavailable=${state.unavailableCapabilities} storagePending=${state.pagedStoragePending} storageFailed=${state.pagedStorageFailed} view=${view?.copy(text = view.text?.let { "bytes=${it.toByteArray().size}" }, recoveryDraft = null)}")
        }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { observer.refusal.get() != null }
        val refusal = requireNotNull(observer.refusal.get())
        assertEquals(id, refusal.first.text("questionId")); assertEquals(grant, refusal.first.text("grantId"))
        assertEquals(selection.sessionId, refusal.first.text("sessionId"))
        assertEquals(finalValue.toByteArray(Charsets.UTF_8).size.toLong(), refusal.first.long("totalBytes"))
        assertEquals(digest(finalValue), refusal.first.text("sha256"))
        assertFalse(refusal.second.flag("ok")); assertEquals("busy", refusal.second.obj("error").text("code"))
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == finalValue && persisted().attempts.isEmpty() && persisted().drafts[key]?.text == finalValue }
        assertTrue(repository.state.value.questions.any { it.optionalText("id") == id })
        assertEquals(Triple(1, 0, 0), transport.uploadCounts())
        assertEquals(0, transport.questionAnswerAttempts.get())
        val refused = status(); assertQuota(refused, "full", if (mode == "bytes") 8 else 32, if (mode == "bytes") 8_388_608 else 0)
        assertEquals(0, refused.getInt("pagedCommitCount")); assertEquals(0, refused.getInt("pagedQuotaOwnerWritesBeforeRelease")); assertTrue(refused.getInt("pagedQuotaRefusedCount") >= 1)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled()
        action("release-editor-quota")
        compose.waitUntil(120_000) { statusOrNull()?.optString("pagedQuotaPhase") == "released" }
        val released = status(); assertQuota(released, "released", if (mode == "bytes") 7 else 31, if (mode == "bytes") 7_340_032 else 0)
        assertEquals(0, released.getInt("pagedQuotaOwnerWritesBeforeRelease"))
        observeNoSend(id, key, released, 3_000)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true &&
                transport.pagedCommitAccepted(id, grant, finalValue.toByteArray(Charsets.UTF_8).size, digest(finalValue))
        }
        compose.waitUntil(60_000) { persisted().attempts.isEmpty() && repository.state.value.questions.none { it.optionalText("id") == id } }
        compose.waitUntil(60_000) { statusOrNull()?.let { it.optInt("pagedQuotaCount", -1) == 0 && it.optLong("pagedQuotaBytes", -1) == 0L } == true }
        val committed = status(); assertQuota(committed, "released", 0, 0, if (mode == "bytes") 7 else 31)
        val counts = transport.uploadCounts()
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            assertEquals(counts, transport.uploadCounts()); assertEquals(0, transport.questionAnswerAttempts.get())
            assertTrue(repository.state.value.questions.none { it.optionalText("id") == id })
            val s = status(); assertEquals(1, s.getInt("pagedCommitCount")); assertQuota(s, "released", 0, 0, if (mode == "bytes") 7 else 31)
            SystemClock.sleep(200)
        }
        val proof = JSONObject().put("nonce", nonce).put("mode", mode).put("pid", Process.myPid()).put("questionId", id)
            .put("sourceBytes", source.toByteArray(Charsets.UTF_8).size).put("sourceSha256", digest(source))
            .put("finalBytes", finalValue.toByteArray(Charsets.UTF_8).size).put("finalSha256", digest(finalValue))
            .put("before", before).put("full", full).put("refused", refused).put("released", released).put("committed", committed)
            .put("draftBeforeBytes", finalValue.toByteArray(Charsets.UTF_8).size).put("draftBeforeSha256", digest(finalValue))
            .put("draftAfterRefusalBytes", finalValue.toByteArray(Charsets.UTF_8).size).put("draftAfterRefusalSha256", digest(finalValue))
            .put("refusedRequest", JSONObject(refusal.first.toString())).put("refusedResult", JSONObject(refusal.second.toString()))
            .put("explicitRetry", true).put("answerAttempts", transport.questionAnswerAttempts.get()).put("cleanupVerified", persisted().attempts.isEmpty()).put("draftRetained", persisted().drafts[key]?.text == finalValue)
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedQuotaProof", "mode=$mode nonce=$nonce pid=${Process.myPid()} question=$id") })
    }

    private fun observeNoSend(id: String, key: PagedJournalKey, released: JSONObject, millis: Long) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            assertEquals(Triple(1, 0, 0), transport.uploadCounts()); assertEquals(0, transport.questionAnswerAttempts.get())
            assertEquals(finalValue, repository.state.value.pagedEditors[id]?.text); assertEquals(finalValue, persisted().drafts[key]?.text)
            assertTrue(persisted().attempts.isEmpty()); assertTrue(repository.state.value.questions.any { it.optionalText("id") == id })
            val current = status(); assertQuota(current, "released", if (mode == "bytes") 7 else 31, if (mode == "bytes") 7_340_032 else 0)
            assertEquals(0, current.getInt("pagedQuotaOwnerWritesBeforeRelease")); assertEquals(0, current.getInt("pagedCommitCount")); assertEquals(released.getInt("pagedQuotaCount"), current.getInt("pagedQuotaCount")); assertEquals(released.getLong("pagedQuotaBytes"), current.getLong("pagedQuotaBytes"))
            SystemClock.sleep(200)
        }
    }
    private fun assertQuota(value: JSONObject, phase: String, count: Int, bytes: Int, fillers: Int = count) {
        assertEquals(mode, value.getString("pagedQuotaMode")); assertEquals(phase, value.getString("pagedQuotaPhase"))
        assertTrue("Quota proof must finish before reservation TTL", value.getLong("pagedQuotaElapsedMs") in 0L until 50_000L)
        assertEquals(count, value.getInt("pagedQuotaCount")); assertEquals(bytes.toLong(), value.getLong("pagedQuotaBytes")); assertEquals(fillers, value.getInt("pagedQuotaFillerCount"))
    }
    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun persisted() = stores.pagedEditor.load()
    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun action(value: String) {
        val body = JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", 202, it.code) }
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

/** Correlates the actual begin response with the request sent through the real transport. */
private class QuotaBeginObserver(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    private val pending = ConcurrentHashMap<String, JsonObject>()
    val refusal = AtomicReference<Pair<JsonObject, JsonObject>?>(null)
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                val id = payload.optionalText("requestId")
                if (payload.optionalText("type") == "result" && id != null) pending.remove(id)?.let { request ->
                    refusal.compareAndSet(null, request to payload)
                }
                value.message(payload)
            }
        } }
    private fun observe(payload: JsonObject) {
        if (payload.optionalText("type") == "session.question.editor.upload.begin") pending[payload.text("requestId")] = payload
    }
    override fun send(payload: JsonObject) { observe(payload); delegate.send(payload) }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        delegate.sendGuarded(payload) { beforePossibleWrite(); observe(payload) }
    }
}
