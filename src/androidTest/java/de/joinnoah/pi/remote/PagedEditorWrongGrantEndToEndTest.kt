package de.joinnoah.pi.remote

import android.content.ContextWrapper
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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

/** Private opt-in proof: a real wrong-grant refusal followed by an explicit valid retry. */
class PagedEditorWrongGrantEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val show = mutableStateOf(false)
    private val source = "😀".repeat(262_144)
    private lateinit var fixture: JSONObject
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var wire: WrongGrantWire
    private lateinit var presence: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var failure: Throwable? = null
    private val stages = JSONArray()

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private wrong-grant fixture required", args.getString("pagedWrongGrantExpected") == "true")
            nonce = requireNotNull(args.getString("pagedWrongGrantNonce")); require(nonce.matches(Regex("[a-f0-9]{32}")))
            require(args.getString("prStatusFixtureExpected") == "true" && args.getString("prStatusNativeExpected") == "true")
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            require(fixture.getBoolean("nativeWorker") && fixture.getBoolean("pagedEditorFixture") && fixture.getBoolean("nativeWrongGrantProof"))
            require(fixture.getString("pagedEditorCase") == "max-unchanged" && !fixture.optBoolean("pagedEditorPositiveOutcomes"))
            for (label in listOf("Source", "Final")) {
                require(fixture.getInt("pagedEditor${label}Bytes") == 1_048_576 && fixture.getString("pagedEditor${label}Sha256") == digest(source))
            }
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            val status = fixture.getString("statusUrl").toHttpUrl()
            require(status.scheme == "https" && status.host == relay.host && status.port == relay.port && status.encodedPath == "/fixture-status" && status.query == null && status.fragment == null)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-wrong-grant-$nonce")
            check(directory.mkdirs() || directory.isDirectory); check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Wrong grant fixture")
            stores.pairings.save(listOf(host)); selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            wire = WrongGrantWire(SecureRemoteTransport(scope, client), nonce)
            presence = PresenceObservingTransport(wire, requireNotNull(selection.sessionId), true, false)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, presence, scope, pagedEditorTestGate = true, pagedEditorStorage = stores.pagedEditor, positiveOutcomeTestGate = false)
            NavigationTestActivity.contentFactory = { { if (show.value) MaterialTheme { RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1)) } } }
        }
        override fun after() {
            if (::directory.isInitialized && !File(directory, "proof.json").exists()) runCatching { writeProof("diagnostic.json") }
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun wrongGrantRefusedThenExplicitValidRetry() {
        try { execute() } catch (t: Throwable) { failure = t; throw t }
    }

    private fun execute() {
        val initial = status(); assertTrue(initial.getBoolean("ownerPidVerified")); assertTrue(initial.getBoolean("nativeWorkerVerified"))
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-wrong-grant") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { show.value = true }
        val command = fixture.getString("editorProbeCommand")
        compose.waitUntil(60_000) { repository.state.value.let { it.selection == selection && it.connected && !it.loading && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities && presence.pagedV3Accepted.get() && presence.presenceAccepted.get() && it.commands.any { c -> c.name == commandName(command) } && !it.commandsLoading } }
        compose.onNodeWithTag("composerField").assertIsDisplayed().performTextReplacement("$command ${fixture.getString("editorProbeNonce")}")
        compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id"); val grant = question.text("editorUploadGrantId")
        assertTrue(grant.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        stages.put(JSONObject().put("stage", "question").put("question", JSONObject(question.toString())))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == source }
        // Exercise the actual editor callback, then restore the source before either Send.
        // This establishes the encrypted draft; it does not qualify an IME.
        val changed = "XX" + "😀".repeat(262_143)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("XX" + "😀".repeat(1_023))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == changed && stores.pagedEditor.load().drafts[key]?.text == changed }
        compose.onNodeWithTag("questionEditorField").performTextReplacement("😀".repeat(1_024))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == source && stores.pagedEditor.load().drafts[key]?.text == source }
        stages.put(JSONObject().put("stage", "draft-restored").put("draftBytes", source.toByteArray().size).put("draftSha256", digest(source)).put("encryptedDraftVerified", true).put("imeQualified", false))
        val connectionCount = wire.connections.get()
        assertTrue(stores.pagedEditor.load().attempts.isEmpty()); assertTrue(stores.pagedEditor.load().pendingReleases.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { wire.firstResult() != null }
        val refusal = requireNotNull(wire.firstResult())
        assertFalse(refusal.second.flag("ok")); assertTrue(refusal.second.obj("error").text("code") in setOf("unavailable", "forbidden"))
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == source && stores.pagedEditor.load().drafts[key]?.text == source && stores.pagedEditor.load().attempts.isEmpty() && stores.pagedEditor.load().pendingReleases.isEmpty() && id !in repository.state.value.answering && repository.state.value.questionBlocks[id] == null }
        assertSame(question, repository.state.value.questions.single { it.optionalText("id") == id })
        assertTrue(repository.state.value.connected); assertEquals(connectionCount, wire.connections.get()); assertEquals(Triple(1, 0, 0), presence.uploadCounts()); assertEquals(0, presence.questionAnswerAttempts.get())
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled()
        compose.waitUntil(60_000) { statusOrNull()?.optJSONObject("nativeWrongGrant")?.optJSONObject("firstRefusal") != null }
        val refused = status(); val anchor = refused.getJSONObject("nativeWrongGrant").getJSONObject("firstRefusal")
        assertEquals(refusal.first.text("requestId"), anchor.getJSONObject("request").getString("requestId"))
        assertEquals(refusal.second.text("requestId"), anchor.getJSONObject("result").getString("requestId"))
        val counts = anchor.getJSONObject("sourceCounts")
        for (name in listOf("begin", "chunk", "commit")) assertEquals(0, counts.getInt(name))
        assertEquals(0, anchor.getInt("sdkResultCount")); assertTrue(anchor.getBoolean("questionPending")); assertTrue(anchor.getBoolean("ownerPidVerified")); assertEquals(0, anchor.getInt("originalCalls"))
        stages.put(JSONObject().put("stage", "refused-before-retry").put("status", refused).put("draftBytes", source.toByteArray().size).put("draftSha256", digest(source)).put("attempts", 0).put("pendingReleases", 0).put("question", JSONObject(question.toString())).put("connected", true).put("sendEnabled", true))
        val until = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < until) {
            assertEquals(Triple(1, 0, 0), presence.uploadCounts()); assertEquals(0, presence.questionAnswerAttempts.get())
            assertTrue(repository.state.value.connected); assertSame(question, repository.state.value.questions.single { it.optionalText("id") == id })
            assertEquals(source, stores.pagedEditor.load().drafts[key]?.text); assertTrue(stores.pagedEditor.load().attempts.isEmpty()); assertTrue(stores.pagedEditor.load().pendingReleases.isEmpty())
            SystemClock.sleep(200)
        }
        stages.put(JSONObject().put("stage", "explicit-second-click"))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true && presence.pagedCommitAccepted(id, grant, 1_048_576, digest(source)) && repository.state.value.questions.none { it.optionalText("id") == id } }
        compose.waitUntil(60_000) { stores.pagedEditor.load().attempts.isEmpty() }
        assertEquals(connectionCount, wire.connections.get()); assertEquals(Triple(2, 22, 1), presence.uploadCounts()); assertEquals(0, presence.questionAnswerAttempts.get())
        val final = status(); assertEquals(1, final.getInt("pagedBeginCount")); assertEquals(22, final.getInt("pagedChunkCount")); assertEquals(1, final.getInt("pagedCommitCount")); assertEquals(1, final.getInt("sourceSdkResultCount")); assertTrue(final.getBoolean("ownerPidVerified")); assertEquals(0, final.getInt("nativeEditorOriginalCalls"))
        wire.verify(id, grant, source)
        stages.put(JSONObject().put("stage", "committed").put("status", final))
        writeProof("proof.json")
    }

    private fun activate(target: RemoteSelection) { compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }; compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected } }
    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun readFixture(path: String): ByteArray { require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains("..")); return ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")).use { it.readBytes() }.also { require(it.isNotEmpty()) } }
    private fun writeProof(name: String) {
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("mode", "native").put("stages", stages).put("wire", if (::wire.isInitialized) wire.json() else JSONArray()).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        File(directory, name).outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
    }
}

/** The first begin alone is changed; every actual response is forwarded unchanged. */
private class WrongGrantWire(private val delegate: RemoteTransport, private val nonce: String) : RemoteTransport by delegate {
    private data class Entry(val original: JsonObject, val sent: JsonObject, val time: Long, @Volatile var result: JsonObject? = null)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val changed = AtomicBoolean(false)
    val connections = AtomicInteger()
    override fun connect(host: PairedHost) { connections.incrementAndGet(); delegate.connect(host) }
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                if (payload.optionalText("type") == "result") payload.optionalText("requestId")?.let { entries[it]?.result = copy(payload) }
                value.message(payload)
            }
        } }
    private fun copy(payload: JsonObject) = Json.parseToJsonElement(payload.toString()) as JsonObject
    private fun prepare(payload: JsonObject): JsonObject {
        val type = payload.optionalText("type") ?: return payload
        if (!type.startsWith("session.question.editor.upload.") && type != "question.answer") return payload
        val original = copy(payload)
        val sent = if (type == "session.question.editor.upload.begin" && changed.compareAndSet(false, true)) {
            val grant = payload.text("grantId"); val wrong = if (grant.length < 256) grant + "_" else if (grant != nonce) nonce else "${nonce}_"
            check(wrong != grant && wrong.matches(Regex("[A-Za-z0-9_-]{1,256}")))
            JsonObject(payload + ("grantId" to JsonPrimitive(wrong)))
        } else original
        check(entries.putIfAbsent(payload.text("requestId"), Entry(original, copy(sent), SystemClock.elapsedRealtimeNanos())) == null)
        return sent
    }
    override fun send(payload: JsonObject) { delegate.send(prepare(payload)) }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) { delegate.sendGuarded(prepare(payload), beforePossibleWrite) }
    private fun ordered() = entries.values.sortedBy { it.time }
    fun firstResult(): Pair<JsonObject, JsonObject>? { val first = ordered().firstOrNull { it.sent.optionalText("type") == "session.question.editor.upload.begin" } ?: return null; return first.result?.let { first.sent to it } }
    fun json() = JSONArray(ordered().map { JSONObject().put("original", JSONObject(it.original.toString())).put("request", JSONObject(it.sent.toString())).put("requestNanos", it.time).put("result", it.result?.let { r -> JSONObject(r.toString()) } ?: JSONObject.NULL) })
    fun verify(id: String, grant: String, expected: String) {
        val all = ordered(); val begins = all.filter { it.sent.optionalText("type") == "session.question.editor.upload.begin" }
        assertEquals(2, begins.size); assertEquals(grant, begins[0].original.text("grantId")); assertNotEquals(grant, begins[0].sent.text("grantId")); assertEquals(begins[0].original - "grantId", begins[0].sent - "grantId")
        assertEquals(begins[1].original, begins[1].sent); assertEquals(grant, begins[1].sent.text("grantId")); assertEquals(id, begins[1].sent.text("questionId")); assertNotEquals(begins[0].sent.text("requestId"), begins[1].sent.text("requestId"))
        assertTrue(requireNotNull(begins[1].result).flag("ok")); assertEquals(1_048_576L, begins[1].sent.long("totalBytes"))
        val chunks = all.filter { it.sent.optionalText("type") == "session.question.editor.upload.chunk" }.sortedBy { it.sent.long("offset") }
        assertEquals(22, chunks.size); val bytes = java.io.ByteArrayOutputStream(); var offset = 0L
        chunks.forEach { assertEquals(offset, it.sent.long("offset")); val part = Wire.decode(it.sent.text("data")); bytes.write(part); offset += part.size; assertTrue(requireNotNull(it.result).flag("ok")) }
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), bytes.toByteArray()); assertEquals(1, all.count { it.sent.optionalText("type") == "session.question.editor.upload.commit" }); assertTrue(all.none { it.sent.optionalText("type") == "question.answer" })
    }
}
