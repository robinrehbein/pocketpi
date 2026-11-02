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
import androidx.core.view.ViewCompat
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Private two-process proof of an uncertain commit across actual Host death. */
class PagedEditorPendingHostRestartEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val showNavigation = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var phase: String
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var transport: PresenceObservingTransport
    private lateinit var observer: PendingOutcomeObserver
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val source = "😀".repeat(262_144)
    private val finalValue = "é\\\n".repeat(1_024) + "😀".repeat(261_120)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private pending Host fixture required", args.getString("pagedPendingHostExpected") == "true")
            phase = requireNotNull(args.getString("pagedPendingHostPhase"))
            nonce = requireNotNull(args.getString("pagedPendingHostNonce"))
            require(phase in setOf("A", "B") && nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((field, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(field).toHttpUrl()
                require(url.scheme == "https" && url.host == "10.0.2.2" && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            val nativeExpected = requireNotNull(args.getString("prStatusNativeExpected"))
            require(nativeExpected in setOf("true", "false"))
            native = nativeExpected == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getString("pagedEditorCase") == "restart-lost-ack" && fixture.getBoolean("pagedEditorPositiveOutcomes") && fixture.getBoolean("hostPendingProof"))
            verifyMetadata("Source", source); verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-pending-host-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Pending Host fixture")
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val proofFile = File(directory, "phase-a.json")
            if (phase == "A") {
                check(!proofFile.exists() && stores.pairings.load().isEmpty())
                stores.pairings.save(listOf(host))
            } else {
                val proof = JSONObject(proofFile.readText())
                assertEquals(nonce, proof.getString("nonce")); assertEquals(identity(), proof.getString("identity"))
                assertNotEquals(proof.getInt("pid"), Process.myPid())
                verifyJournal(proof)
            }
            assertEquals(listOf(host), stores.pairings.load())
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            observer = PendingOutcomeObserver(SecureRemoteTransport(scope, client))
            transport = PresenceObservingTransport(observer, requireNotNull(selection.sessionId), true, false)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, transport, scope, pagedEditorTestGate = true, pagedEditorStorage = stores.pagedEditor, positiveOutcomeTestGate = true)
            NavigationTestActivity.contentFactory = { { if (showNavigation.value) MaterialTheme { RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1)) } } }
        }
        override fun after() {
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun pendingSurvivesActualHostDeath() {
        val initial = status()
        if (phase == "B") {
            assertTrue(initial.getBoolean("actualHostRestart")); assertTrue(initial.getBoolean("originalHostStopped")); assertTrue(initial.getBoolean("originalOwnerStopped"))
            assertTrue(initial.getBoolean("ledgerReadable")); assertEquals("pending", initial.getString("expectedState"))
            assertEquals(1, initial.getInt("initialPendingCount")); assertEquals(1, initial.getInt("pendingCount")); assertEquals(0, initial.getInt("initialAppliedCount"))
            assertTrue(initial.getBoolean("tuplePresent")); assertTrue(initial.getInt("originalHostPid") != initial.getInt("newHostPid"))
        } else {
            assertTrue(initial.getBoolean("ownerPidVerified")); if (native) assertTrue(initial.getBoolean("nativeWorkerVerified"))
        }
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-pending-host-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        if (phase == "B") { phaseB(initial); return }
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) { val s = repository.state.value; s.selection == selection && s.connected && !s.loading && QUESTION_PAGED_EDITOR_CAPABILITY in s.capabilities && transport.pagedV3Accepted.get() && (!native || transport.presenceAccepted.get()) }
        phaseA()
    }

    private fun phaseA() {
        val command = fixture.getString("editorProbeCommand")
        val probeName = requireNotNull(commandName(command))
        compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == probeName } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextInput("$command ${fixture.getString("editorProbeNonce")}")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id")
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        assertEquals(source, repository.state.value.pagedEditors[id]?.text)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("é\\\n".repeat(1_024))
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == finalValue }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.optJSONObject("hostPending")?.optBoolean("held") == true && persisted().attempts.size == 1 }
        val owner = status(); val pending = owner.getJSONObject("hostPending")
        assertTrue(owner.getInt("pagedSourceReadReplyCount") > 0)
        assertTrue(pending.getBoolean("originalSDKStarted")); assertEquals(0, pending.getInt("sdkValueCount"))
        for (field in listOf("sourceDispatchCount", "sourceBeginCount", "sourceChunkCount", "sourceCommitCount")) assertEquals(0, pending.getInt(field))
        val attempt = persisted().attempts.values.single()
        val binding = requireNotNull(attempt.outcomeBinding)
        assertEquals(finalValue, attempt.text); assertEquals(finalValue, persisted().drafts[attempt.key]?.text)
        assertEquals(host.deviceId, binding.deviceId); assertEquals(question.text("editorOwnerIncarnationId"), binding.ownerIncarnationId)
        val receipt = receipt(attempt)
        assertJsonEquals(receipt.toString(), pending.getJSONObject("receipt").toString())
        val tuple = pending.getJSONObject("tuple")
        assertEquals(host.routeId, tuple.getString("routeId")); assertEquals(host.deviceId, tuple.getString("deviceId"))
        val wireTuple = JSONObject(tuple.toString()).apply { remove("routeId"); remove("deviceId") }
        assertJsonEquals(receipt.toString(), wireTuple.toString())
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("identity", identity())
            .put("questionId", id).put("attemptId", attempt.attemptId).put("attemptGrant", attempt.grantId).put("attemptUpload", attempt.uploadId)
            .put("attemptCommit", attempt.commitRequestId).put("attemptCreatedAt", attempt.createdAt).put("receipt", receipt)
            .put("sourceBytes", source.toByteArray().size).put("sourceSha256", digest(source)).put("draftSha256", digest(finalValue))
            .put("draftEditedAt", requireNotNull(persisted().drafts[attempt.key]).editedAt).put("attemptBytes", attempt.text.toByteArray().size).put("attemptSha256", digest(attempt.text))
            .put("sdkIdentity", pending.getJSONObject("originalSDKIdentity")).put("ledgerTuple", tuple).put("deviceId", binding.deviceId).put("ledgerHash", pending.getString("ledgerHash"))
            .put("sourceReadReplyCount", owner.getInt("pagedSourceReadReplyCount"))
        writeProof("phase-a.json", proof); report("A", id)
        while (true) SystemClock.sleep(1_000)
    }

    private fun phaseB(initial: JSONObject) {
        val proof = JSONObject(File(directory, "phase-a.json").readText())
        compose.waitUntil(60_000) { observer.result.get() != null }
        val observed = requireNotNull(observer.result.get())
        assertJsonEquals(proof.getJSONObject("receipt").toString(), observed.first.obj("receipt").toString())
        assertTrue(observed.second.flag("ok"))
        val data = observed.second.obj("data")
        assertEquals(setOf("kind", "status"), data.keys); assertEquals("editor_outcome", data.text("kind")); assertEquals("unknown", data.text("status"))
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            verifyJournal(proof)
            assertEquals(Triple(0, 0, 0), transport.uploadCounts()); assertEquals(0, transport.questionAnswerAttempts.get())
            assertEquals(0, transport.positiveOutcomeReleaseAttempts.get()); assertNull(transport.positiveOutcomeApplied.get())
            val current = status(); assertEquals(1, current.getInt("pendingCount")); assertEquals(0, current.getInt("appliedCount")); assertEquals(0, current.getInt("ownerDispatchCalls")); assertTrue(current.getBoolean("tuplePresent"))
            SystemClock.sleep(200)
        }
        val final = status()
        val queries = final.getJSONArray("authenticatedQueries")
        val matching = (0 until queries.length()).map { queries.getJSONObject(it) }.single { it.getString("requestId") == observed.first.text("requestId") }
        assertJsonEquals(proof.getJSONObject("receipt").toString(), matching.getJSONObject("receipt").toString())
        assertJsonEquals(data.toString(), matching.getJSONObject("result").toString())
        val result = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("identity", identity()).put("questionId", proof.getString("questionId"))
            .put("receipt", proof.getJSONObject("receipt")).put("ledgerTuple", proof.getJSONObject("ledgerTuple")).put("deviceId", proof.getString("deviceId")).put("authenticatedQuery", matching).put("query", JSONObject(observed.first.toString())).put("queryResult", JSONObject(observed.second.toString()))
            .put("draftSha256", digest(persisted().drafts.values.single().text)).put("initialPendingCount", initial.getInt("initialPendingCount"))
            .put("pendingCount", final.getInt("pendingCount")).put("appliedCount", final.getInt("appliedCount")).put("ownerDispatchCalls", final.getInt("ownerDispatchCalls"))
            .put("uploadCounts", transport.uploadCounts().let { JSONArray().put(it.first).put(it.second).put(it.third) }).put("answerAttempts", transport.questionAnswerAttempts.get()).put("releaseAttempts", transport.positiveOutcomeReleaseAttempts.get())
            .put("oldHostPid", initial.getInt("originalHostPid")).put("newHostPid", initial.getInt("newHostPid"))
        writeProof("phase-b.json", result); report("B", proof.getString("questionId"))
    }

    private fun verifyJournal(proof: JSONObject) {
        val snapshot = persisted(); assertTrue(snapshot.pendingReleases.isEmpty())
        val attempt = snapshot.attempts.values.single(); assertEquals(proof.getString("deviceId"), requireNotNull(attempt.outcomeBinding).deviceId); assertEquals(proof.getString("questionId"), attempt.key.questionId); assertEquals(host.routeId, attempt.key.routeId); assertEquals(selection.sessionId, attempt.key.sessionId); assertEquals(proof.getString("attemptId"), attempt.attemptId)
        assertEquals(proof.getString("attemptGrant"), attempt.grantId); assertEquals(proof.getString("attemptUpload"), attempt.uploadId)
        assertEquals(proof.getString("attemptCommit"), attempt.commitRequestId); assertEquals(proof.getLong("attemptCreatedAt"), attempt.createdAt)
        assertEquals(finalValue, attempt.text); assertEquals(finalValue, snapshot.drafts[attempt.key]?.text)
        assertEquals(proof.getString("draftSha256"), digest(attempt.text)); assertEquals(proof.getString("attemptSha256"), digest(attempt.text)); assertEquals(proof.getInt("attemptBytes"), attempt.text.toByteArray().size); assertEquals(proof.getLong("draftEditedAt"), requireNotNull(snapshot.drafts[attempt.key]).editedAt); assertJsonEquals(proof.getJSONObject("receipt").toString(), receipt(attempt).toString())
    }
    private fun receipt(attempt: StoredPagedAttempt): JSONObject {
        val binding = requireNotNull(attempt.outcomeBinding)
        return JSONObject().put("sessionId", attempt.key.sessionId).put("questionId", attempt.key.questionId)
            .put("ownerIncarnationId", binding.ownerIncarnationId).put("grantId", attempt.grantId).put("uploadId", attempt.uploadId)
            .put("commitRequestId", attempt.commitRequestId).put("byteLength", binding.byteLength).put("sha256", binding.sha256)
    }
    private fun assertJsonEquals(a: String, b: String) = assertEquals(Json.parseToJsonElement(a), Json.parseToJsonElement(b))
    private fun activate(target: RemoteSelection) { compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }; compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected } }
    private fun persisted() = PagedEditorStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) { override fun getNoBackupFilesDir() = directory }).load()
    private fun status() = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun identity() = digest(listOf(fixture.getString("routeId"), fixture.getString("deviceId"), fixture.getString("projectId"), fixture.getString("sessionId"), fixture.getString("editorProbeNonce"), native.toString()).joinToString("\u0000"))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun verifyMetadata(label: String, value: String) { assertEquals(fixture.getInt("pagedEditor${label}Bytes"), value.toByteArray(Charsets.UTF_8).size); assertEquals(fixture.getString("pagedEditor${label}Sha256"), digest(value)) }
    private fun writeProof(name: String, proof: JSONObject) = File(directory, name).outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
    private fun report(phase: String, id: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedPendingHostProof", "phase=$phase nonce=$nonce pid=${Process.myPid()} identity=${identity()} question=$id") })
    private fun readFixture(path: String): ByteArray { require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains("..")); val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path"); return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) } }
}

private class PendingOutcomeObserver(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    private val requests = ConcurrentHashMap<String, JsonObject>()
    val result = AtomicReference<Pair<JsonObject, JsonObject>?>(null)
    private lateinit var downstream: RemoteTransport.Listener
    override var listener: RemoteTransport.Listener
        get() = downstream
        set(value) { downstream = value; delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                if (payload.optionalText("type") == "result") payload.optionalText("requestId")?.let { id -> requests.remove(id)?.let { result.compareAndSet(null, it to payload) } }
                value.message(payload)
            }
        } }
    private fun observe(payload: JsonObject) { if (payload.optionalText("type") == "session.question.editor.outcome.get") requests[payload.text("requestId")] = payload }
    override fun send(payload: JsonObject) { observe(payload); delegate.send(payload) }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) { delegate.sendGuarded(payload) { beforePossibleWrite(); observe(payload) } }
}
