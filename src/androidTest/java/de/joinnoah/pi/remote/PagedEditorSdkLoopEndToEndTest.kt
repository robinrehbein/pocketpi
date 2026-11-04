package de.joinnoah.pi.remote

import android.content.ContextWrapper
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
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
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Private, opt-in continuity proof against one real Pi SDK owner and real encrypted journal. */
class PagedEditorSdkLoopEndToEndTest {
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
    private lateinit var wire: SdkLoopWire
    private lateinit var storage: SdkLoopStorage
    private lateinit var presence: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private val proofs = JSONArray()
    private var currentIndex = -1

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private SDK loop fixture required", args.getString("pagedSdkLoopExpected") == "true")
            nonce = requireNotNull(args.getString("pagedSdkLoopNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(fixture.getBoolean("nativeWorker") == native && fixture.getBoolean("pagedEditorFixture") && fixture.getBoolean("pagedEditorPositiveOutcomes"))
            require(fixture.getBoolean("sdkLoopProof") && fixture.getInt("sdkLoopCount") == 40 && fixture.getString("sdkLoopCommand") == "/probe-editor")
            require(fixture.getString("sdkLoopNonce").matches(Regex("[A-Za-z0-9_-]{22}")))
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((name, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(name).toHttpUrl()
                require(url.scheme == "https" && url.host == relay.host && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-sdk-loop-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
            stores = AppStores(context)
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "SDK loop fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            wire = SdkLoopWire(SecureRemoteTransport(scope, client))
            presence = PresenceObservingTransport(wire, requireNotNull(selection.sessionId), true, false)
            storage = SdkLoopStorage(stores.pagedEditor, { PagedEditorStore(context) }, wire)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, presence, scope, pagedEditorTestGate = true, pagedEditorStorage = storage, positiveOutcomeTestGate = true)
            NavigationTestActivity.contentFactory = { { if (showNavigation.value) MaterialTheme {
                RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1))
            } } }
        }
        override fun after() {
            if (::directory.isInitialized && !File(directory, "proof.json").exists()) runCatching {
                File(directory, "diagnostic.json").writeText(JSONObject().put("index", currentIndex).put("records", proofs).put("wireRequests", if (::wire.isInitialized) wire.requests.size else -1).toString())
            }
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun fortyRealSdkEditorsRetireAndRelease() {
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-sdk-loop-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) { repository.state.value.let { it.selection == selection && it.connected && !it.loading && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities && QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY in it.capabilities && presence.pagedV3Accepted.get() && (!native || presence.presenceAccepted.get()) } }
        val command = fixture.getString("sdkLoopCommand")
        val commandName = requireNotNull(commandName(command))
        compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == commandName } && !repository.state.value.commandsLoading }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextReplacement("$command ${fixture.getString("sdkLoopNonce")}")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        val questionIds = mutableSetOf<String>(); val uploadIds = mutableSetOf<String>(); val commitIds = mutableSetOf<String>(); val attemptIds = mutableSetOf<String>()
        var sdkPid: Int? = null
        val failuresAtStart = wire.failures.get()
        for (index in 0 until 40) {
            currentIndex = index
            val expected = "loop-" + index.toString().padStart(27, '0')
            assertEquals(32, expected.toByteArray(Charsets.UTF_8).size)
            compose.waitUntil(120_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") && it.optionalText("id") !in questionIds } }
            val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
            val id = question.text("id"); val grant = question.text("editorUploadGrantId")
            assertTrue(questionIds.add(id))
            val currentOwner = requireNotNull(PagedEditorWire.ownerIncarnationId(question))
            val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
            compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
            assertEquals("", repository.state.value.pagedEditors[id]?.text)
            compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(expected)
            compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == expected && stores.pagedEditor.load().drafts[key]?.text == expected }
            compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(120_000) {
                val s = statusOrNull()?.optJSONObject("sdkLoop")
                val records = s?.optJSONArray("records")
                records != null && records.length() > index && records.optJSONObject(index)?.optBoolean("complete") == true &&
                    storage.complete(key) && repository.state.value.questions.none { it.optionalText("id") == id }
            }
            val status = status().getJSONObject("sdkLoop")
            assertEquals(40, status.getInt("count")); assertEquals(0, status.getInt("baselineLedgerCount")); assertEquals(index + 1, status.getInt("completed"))
            val record = status.getJSONArray("records").getJSONObject(index)
            val evidence = storage.evidence(key)
            val attempt = evidence.attempt
            assertTrue(attemptIds.add(attempt.attemptId)); assertTrue(uploadIds.add(attempt.uploadId)); assertTrue(commitIds.add(attempt.commitRequestId))
            assertEquals(expected, attempt.text); assertEquals(grant, attempt.grantId)
            val binding = requireNotNull(attempt.outcomeBinding)
            assertEquals(host.deviceId, binding.deviceId); assertEquals(currentOwner, binding.ownerIncarnationId); assertEquals(32, binding.byteLength); assertEquals(digest(expected), binding.sha256)
            val receipt = PagedEditorWire.receipt(evidence.release)
            val loopWire = wire.verify(key, attempt, receipt, expected)
            assertEquals(Json.parseToJsonElement(loopWire.getJSONObject("commitResult").toString()), Json.parseToJsonElement(record.getJSONObject("accepted").toString()))
            assertEquals(Json.parseToJsonElement(loopWire.getJSONObject("release").toString()), Json.parseToJsonElement(record.getJSONObject("releaseRequest").toString()))
            assertEquals(Json.parseToJsonElement(loopWire.getJSONObject("releaseResult").toString()), Json.parseToJsonElement(record.getJSONObject("releaseResponse").toString()))
            val tuple = record.getJSONObject("fullTuple")
            assertEquals(host.routeId, tuple.getString("routeId")); assertEquals(host.deviceId, tuple.getString("deviceId"))
            for (field in listOf("sessionId", "questionId", "ownerIncarnationId", "grantId", "uploadId", "commitRequestId", "sha256")) assertEquals(receipt.text(field), tuple.getString(field))
            assertEquals(receipt.long("byteLength"), tuple.getLong("byteLength"))
            val sdk = record.getJSONObject("sdkResult")
            assertEquals(fixture.getString("sdkLoopNonce"), sdk.getString("nonce")); assertEquals(index, sdk.getInt("index"))
            assertEquals(selection.sessionId, sdk.getString("sessionId")); assertEquals(if (native) "tui" else "rpc", sdk.getString("mode"))
            assertEquals(expected, sdk.getString("value")); assertEquals(32, sdk.getInt("bytes")); assertEquals(digest(expected), sdk.getString("sha256"))
            if (sdkPid == null) sdkPid = sdk.getInt("pid") else assertEquals(sdkPid, sdk.getInt("pid"))
            assertEquals(expected, record.getString("expectedValue")); assertEquals(index, record.getInt("index"))
            assertEquals(1, record.getInt("ledgerAppliedCount")); assertEquals(0, record.getInt("ledgerAfterReleaseCount"))
            val ack = record.getJSONObject("sourceAck")
            assertEquals("editor_owner_upload_committed", ack.getString("kind")); assertEquals(selection.sessionId, ack.getString("sessionId")); assertEquals(id, ack.getString("questionId"))
            assertEquals(32, ack.getInt("byteLength")); assertEquals(digest(expected), ack.getString("sha256")); assertTrue(ack.getString("transferId").isNotEmpty())
            val outcome = ack.getJSONObject("outcome")
            for (field in listOf("ownerIncarnationId", "uploadId", "commitRequestId")) assertEquals(receipt.text(field), outcome.getString(field))
            assertTrue(repository.state.value.connected); assertEquals(failuresAtStart, wire.failures.get()); assertEquals(0, presence.questionAnswerAttempts.get())
            val snapshot = PagedEditorStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) { override fun getNoBackupFilesDir() = directory }).load()
            assertTrue(snapshot.drafts.isEmpty()); assertTrue(snapshot.attempts.isEmpty()); assertTrue(snapshot.pendingReleases.isEmpty())
            proofs.put(JSONObject().put("index", index).put("questionId", id).put("attemptId", attempt.attemptId).put("uploadId", attempt.uploadId).put("commitRequestId", attempt.commitRequestId).put("sha256", digest(expected)).put("receipt", JSONObject(receipt.toString())).put("wire", loopWire).put("journalEvents", storage.events(key)).put("host", record))
            action("sdk-loop-next-$index")
        }
        compose.waitUntil(120_000) { statusOrNull()?.optJSONObject("sdkLoop")?.let { it.optInt("gatesReleased", -1) == 40 && it.optBoolean("commandComplete") } == true }
        assertEquals(40, proofs.length()); assertEquals(Triple(40, 40, 40), presence.uploadCounts())
        val snapshot = stores.pagedEditor.load(); assertTrue(snapshot.drafts.isEmpty()); assertTrue(snapshot.attempts.isEmpty()); assertTrue(snapshot.pendingReleases.isEmpty())
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("native", native).put("count", proofs.length()).put("records", proofs).put("finalDrafts", snapshot.drafts.size).put("finalAttempts", snapshot.attempts.size).put("finalPendingReleases", snapshot.pendingReleases.size)
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pagedSdkLoopProof", "nonce=$nonce pid=${Process.myPid()} count=40") })
    }
    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun action(value: String) {
        val body = JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals("Fixture action $value", 202, it.code) }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun readFixture(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}

private class SdkLoopWire(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    val requests = ConcurrentHashMap<String, JsonObject>()
    val results = ConcurrentHashMap<String, JsonObject>()
    val failures = AtomicInteger()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                val id = payload.optionalText("requestId")
                if (payload.optionalText("type") == "result" && id != null && requests.containsKey(id)) check(results.putIfAbsent(id, payload) == null)
                value.message(payload)
            }
            override fun failed(reconnect: Boolean, error: Int) { failures.incrementAndGet(); value.failed(reconnect, error) }
        } }
    private fun observe(payload: JsonObject) {
        val type = payload.optionalText("type") ?: return
        if (type.startsWith("session.question.editor.upload.") || type == "session.question.editor.outcome.release" || type == "session.question.answer") check(requests.putIfAbsent(payload.text("requestId"), payload) == null)
    }
    override fun send(payload: JsonObject) { observe(payload); delegate.send(payload) }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) { observe(payload); delegate.sendGuarded(payload, beforePossibleWrite) }
    fun releaseAccepted(release: StoredPagedRelease): Boolean = requests.values.any { req ->
        req.optionalText("type") == "session.question.editor.outcome.release" && req.obj("receipt") == PagedEditorWire.receipt(release) && results[req.text("requestId")]?.let { it.flag("ok") && it.obj("data").optionalText("kind") == "editor_outcome_released" } == true
    }
    fun verify(key: PagedJournalKey, attempt: StoredPagedAttempt, receipt: JsonObject, expected: String): JSONObject {
        val relevant = requests.values.filter { it.optionalText("questionId") == key.questionId }
        val begin = relevant.single { it.optionalText("type") == "session.question.editor.upload.begin" }
        val chunk = relevant.single { it.optionalText("type") == "session.question.editor.upload.chunk" }
        val commit = relevant.single { it.optionalText("type") == "session.question.editor.upload.commit" }
        val release = requests.values.single { it.optionalText("type") == "session.question.editor.outcome.release" && it.obj("receipt") == receipt }
        assertEquals(attempt.commitRequestId, commit.text("requestId")); assertEquals(attempt.uploadId, commit.text("uploadId")); assertEquals(attempt.uploadId, chunk.text("uploadId"))
        assertEquals(key.sessionId, begin.text("sessionId")); assertEquals(attempt.grantId, begin.text("grantId")); assertEquals(32L, begin.long("totalBytes")); assertEquals(requireNotNull(attempt.outcomeBinding).sha256, begin.text("sha256"))
        assertEquals(0L, chunk.long("offset")); assertArrayEquals(expected.toByteArray(Charsets.UTF_8), Wire.decode(chunk.text("data")))
        val commitResult = requireNotNull(results[commit.text("requestId")]); assertTrue(commitResult.flag("ok")); assertEquals("accepted", commitResult.obj("data").text("kind")); assertEquals(key.sessionId, commitResult.obj("data").text("sessionId"))
        val releaseResult = requireNotNull(results[release.text("requestId")]); assertTrue(releaseResult.flag("ok")); assertEquals("editor_outcome_released", releaseResult.obj("data").text("kind"))
        for (req in listOf(begin, chunk)) assertTrue(requireNotNull(results[req.text("requestId")]).flag("ok"))
        return JSONObject().put("begin", JSONObject(begin.toString())).put("chunk", JSONObject(chunk.toString())).put("commit", JSONObject(commit.toString())).put("commitResult", JSONObject(commitResult.toString())).put("release", JSONObject(release.toString())).put("releaseResult", JSONObject(releaseResult.toString()))
    }
}

private data class SdkLoopEvidence(val attempt: StoredPagedAttempt, val release: StoredPagedRelease)
private class SdkLoopStorage(private val delegate: PagedEditorStorage, private val fresh: () -> PagedEditorStorage, private val wire: SdkLoopWire) : PagedEditorStorage {
    private var prior: PagedJournalSnapshot? = null
    private val attempts = mutableMapOf<PagedJournalKey, StoredPagedAttempt>()
    private val retired = mutableMapOf<PagedJournalKey, StoredPagedRelease>()
    private val removed = mutableSetOf<PagedJournalKey>()
    private val history = mutableMapOf<PagedJournalKey, JSONArray>()
    private fun event(key: PagedJournalKey, stage: String, snapshot: PagedJournalSnapshot) {
        val attempt = snapshot.attempts.values.singleOrNull { it.key == key }
        val release = snapshot.pendingReleases.values.singleOrNull { it.key == key }
        val draft = snapshot.drafts[key]
        val item = JSONObject().put("stage", stage).put("draft", draft?.let { JSONObject().put("text", it.text).put("editedAt", it.editedAt) } ?: JSONObject.NULL).put("attempt", attempt?.let { JSONObject().put("attemptId", it.attemptId).put("grantId", it.grantId).put("uploadId", it.uploadId).put("commitRequestId", it.commitRequestId).put("text", it.text).put("createdAt", it.createdAt).put("deviceId", it.outcomeBinding?.deviceId).put("ownerIncarnationId", it.outcomeBinding?.ownerIncarnationId).put("byteLength", it.outcomeBinding?.byteLength).put("sha256", it.outcomeBinding?.sha256) } ?: JSONObject.NULL).put("release", release?.let { JSONObject(PagedEditorWire.receipt(it).toString()).put("releaseId", it.releaseId).put("routeId", it.key.routeId).put("deviceId", it.deviceId) } ?: JSONObject.NULL)
        history.getOrPut(key) { JSONArray() }.put(item)
    }
    @Synchronized override fun load(): PagedJournalSnapshot = delegate.load().also { if (prior == null) prior = it }
    @Synchronized override fun save(snapshot: PagedJournalSnapshot) {
        val before = prior ?: delegate.load()
        delegate.save(snapshot)
        check(fresh().load() == snapshot) { "Encrypted journal readback mismatch" }
        snapshot.attempts.values.forEach { attempt ->
            if (attempt.attemptId !in before.attempts) {
                check(snapshot.drafts[attempt.key] == StoredPagedDraft(attempt.text, attempt.createdAt))
                event(attempt.key, "reserved", snapshot)
            }
            attempts[attempt.key] = attempt
        }
        for ((id, attempt) in before.attempts) if (id !in snapshot.attempts) {
            val release = snapshot.pendingReleases[id] ?: error("Attempt disappeared without atomic release")
            val binding = requireNotNull(attempt.outcomeBinding)
            check(before.drafts[attempt.key] == StoredPagedDraft(attempt.text, attempt.createdAt))
            check(snapshot.drafts == before.drafts - attempt.key) { "Retirement must remove only its matching draft" }
            check(release == StoredPagedRelease(id, attempt.key, binding.deviceId, binding.ownerIncarnationId, attempt.grantId, attempt.uploadId, attempt.commitRequestId, binding.byteLength, binding.sha256))
            retired[attempt.key] = release
            event(attempt.key, "retired", snapshot)
        }
        for ((id, release) in before.pendingReleases) if (id !in snapshot.pendingReleases) {
            check(wire.releaseAccepted(release)) { "Release removed before correlated real ACK" }
            check(snapshot.drafts == before.drafts && release.key !in snapshot.drafts) { "Release must not alter drafts" }
            removed.add(release.key)
            event(release.key, "released", snapshot)
        }
        prior = snapshot
    }
    @Synchronized fun events(key: PagedJournalKey): JSONArray = requireNotNull(history[key])
    @Synchronized fun complete(key: PagedJournalKey) = attempts.containsKey(key) && retired.containsKey(key) && key in removed
    @Synchronized fun evidence(key: PagedJournalKey) = SdkLoopEvidence(requireNotNull(attempts[key]), requireNotNull(retired[key])).also { check(key in removed) }
}
