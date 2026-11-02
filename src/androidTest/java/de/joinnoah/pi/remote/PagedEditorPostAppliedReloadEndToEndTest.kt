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
class PagedEditorPostAppliedReloadEndToEndTest {
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
    private lateinit var gate: PostAppliedGate
    private lateinit var transport: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private var native = false
    private var positive = false
    private val source = "😀".repeat(262_144)
    private val finalValue = "é\\\n".repeat(1_024) + "😀".repeat(261_120)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private peer reload fixture required", args.getString("pagedPostAppliedReloadExpected") == "true")
            nonce = requireNotNull(args.getString("pagedPostAppliedReloadNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(args.getString("prStatusFixtureExpected") == "true" && fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            native = args.getString("prStatusNativeExpected") == "true"
            require(native && fixture.getBoolean("nativeWorker") && fixture.getBoolean("pagedEditorFixture"))
            require(fixture.getBoolean("pagedEditorPostAppliedReloadProof"))
            require(fixture.getString("pagedEditorCase") == "restart-lost-ack")
            positive = true
            require(fixture.optBoolean("pagedEditorPositiveOutcomes") == positive)
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            for ((name, path) in listOf("statusUrl" to "/fixture-status", "actionUrl" to "/fixture-action")) {
                val url = fixture.getString(name).toHttpUrl()
                require(url.scheme == "https" && url.host == relay.host && url.port == relay.port && url.encodedPath == path && url.query == null && url.fragment == null)
            }
            verifyMetadata("Source", source); verifyMetadata("Final", finalValue)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-post-applied-$nonce")
            check(directory.mkdirs() || directory.isDirectory)
            check(!File(directory, "proof.json").exists())
            stores = AppStores(object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory })
            check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Peer reload fixture")
            stores.pairings.save(listOf(host))
            selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            gate = PostAppliedGate(SecureRemoteTransport(scope, client))
            transport = PresenceObservingTransport(gate, requireNotNull(selection.sessionId), true, false)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, transport, scope, pagedEditorTestGate = true, pagedEditorStorage = stores.pagedEditor, positiveOutcomeTestGate = positive)
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

    @Test fun appliedOutcomeSurvivesNormalReloadAndRetiresExactAttempt() {
        val before = status()
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-post-applied-fixture") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId))
        compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId))
        compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) { repository.state.value.let { it.selection == selection && it.connected && !it.loading && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities && transport.pagedV3Accepted.get() && transport.presenceAccepted.get() } }
        val command = fixture.getString("editorProbeCommand")
        val name = requireNotNull(commandName(command))
        fun openQuestion(probe: String, except: String? = null): JsonObject {
            compose.onNodeWithTag("composerField").assertIsDisplayed().performTextReplacement("$command $probe")
            try {
                compose.waitUntil(60_000) { repository.state.value.commands.any { it.name == name } && !repository.state.value.commandsLoading }
                compose.waitUntil(60_000) { canSendDraft(repository.state.value) }
            } catch (e: Throwable) {
                val state = repository.state.value
                val diagnostic = JSONObject().put("selection", JSONObject().put("routeId", state.selection.routeId)
                    .put("projectId", state.selection.projectId).put("sessionId", state.selection.sessionId))
                    .put("connected", state.connected).put("loading", state.loading).put("status", state.status)
                    .put("commandsLoading", state.commandsLoading).put("commandNames", org.json.JSONArray(state.commands.map { it.name }))
                    .put("capabilities", org.json.JSONArray(state.capabilities.sorted()))
                    .put("unavailableCapabilities", org.json.JSONArray(state.unavailableCapabilities.sorted()))
                    .put("sending", state.sending).put("importingAttachments", state.importingAttachments)
                    .put("configurationChanging", state.configurationChanging).put("bashRunning", state.bash?.running == true)
                    .put("draftLength", state.draft.length).put("commandEvents", org.json.JSONArray(gate.commandEvents.map { JSONObject(it.toString()) }))
                File(directory, "command-timeout.json").writeText(diagnostic.toString(2))
                throw AssertionError("Command catalog or composer did not become ready; see command-timeout.json: $diagnostic", e)
            }
            compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
            compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.optionalText("id") != except && it.containsKey("editorUploadGrantId") } }
            return repository.state.value.questions.single { it.optionalText("kind") == "editor" && it.optionalText("id") != except }
        }
        val old = openQuestion(fixture.getString("editorProbeNonce"))
        val id = old.text("id")
        val grant = old.text("editorUploadGrantId")
        val oldOwner = requireNotNull(PagedEditorWire.ownerIncarnationId(old))
        gate.oldOwner.set(oldOwner)
        val key = PagedJournalKey(host.routeId, requireNotNull(selection.sessionId), id)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text != null }
        assertEquals(source, repository.state.value.pagedEditors[id]?.text)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("é\\\n".repeat(1_024))
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == finalValue && persisted().drafts[key]?.text == finalValue }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.let { it.optBoolean("pagedHostAckDropped") && it.optBoolean("pagedPositiveOutcomeVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount") == 1 } == true && persisted().attempts.values.any { it.key == key } }
        val applied = status()
        val attempt = persisted().attempts.values.single()
        val receipt = PagedEditorWire.receipt(attempt)
        assertEquals(oldOwner, attempt.outcomeBinding?.ownerIncarnationId)
        assertEquals(host.deviceId, attempt.outcomeBinding?.deviceId)
        assertEquals(finalValue, attempt.text)
        assertEquals(1, transport.uploadCounts().third)
        // Reattach the same notification-targeted navigation after the staged reconnect.
        // While mounted, its stack reconciles a shallower selection but does not grow
        // again when the repository is activated directly to the chat.
        compose.runOnIdle { showNavigation.value = false; repository.disconnect() }
        activate(RemoteSelection(host.routeId)); activate(RemoteSelection(host.routeId, selection.projectId)); activate(selection)
        compose.runOnIdle { showNavigation.value = true }
        compose.waitUntil(60_000) {
            repository.state.value.selection == selection && repository.state.value.connected &&
                compose.onAllNodesWithTag("composerField").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(60_000) { gate.heldQuery.get() != null }
        val originalQuery = requireNotNull(gate.heldQuery.get())
        assertEquals(receipt, originalQuery.obj("receipt"))
        assertEquals(attempt, persisted().attempts.values.single())
        val countsBeforeReload = transport.uploadCounts()
        compose.waitUntil(60_000) { statusOrNull()?.optJSONObject("postAppliedReload")?.let { check(it.optString("phase") != "failed") { "Fixture failed: $it" }; it.optString("phase") == "ready" } == true }
        action("post-applied-reload")
        compose.waitUntil(60_000) { statusOrNull()?.optJSONObject("postAppliedReload")?.let { check(it.optString("phase") != "failed") { "Reload failed: $it" }; it.optString("phase") == "reloaded" } == true }
        val reloaded = status()
        val transition = reloaded.getJSONObject("postAppliedReload")
        assertTrue(transition.getBoolean("oldSdkVerified")); assertTrue(transition.getBoolean("oldQuestionClosed"))
        assertTrue(transition.getBoolean("sameRuntime")); assertTrue(transition.getBoolean("newPeerRegistered")); assertTrue(transition.getBoolean("newPositiveBridge"))
        assertEquals(1, transition.getInt("reloadRunCount"))
        assertTrue(transition.getBoolean("oldLedgerPresent"))
        assertEquals(receipt, Json.parseToJsonElement(transition.getJSONObject("oldReceipt").toString()).jsonObject)
        val oldTuple = Json.parseToJsonElement(transition.getJSONObject("oldTuple").toString()).jsonObject
        assertEquals(JsonObject(receipt + ("routeId" to JsonPrimitive(host.routeId)) + ("deviceId" to JsonPrimitive(host.deviceId))), oldTuple)
        assertNotEquals(transition.getString("oldPeerAlias"), transition.getString("newPeerAlias"))
        assertEquals(attempt, persisted().attempts.values.single())
        assertEquals(countsBeforeReload, transport.uploadCounts())
        val fresh = openQuestion(fixture.getString("postAppliedReloadProbeNonce"), id)
        val freshId = fresh.text("id")
        val freshOwner = requireNotNull(PagedEditorWire.ownerIncarnationId(fresh))
        assertNotEquals(oldOwner, freshOwner)
        val mutated = JsonObject(receipt + ("ownerIncarnationId" to JsonPrimitive(freshOwner)))
        val beforeNegative = status()
        val ownerContactsBefore = beforeNegative.getJSONObject("postAppliedReload").getInt("ownerContactCount")
        assertTrue(beforeNegative.getJSONObject("postAppliedReload").getBoolean("oldLedgerPresent"))
        val probeId = gate.probe(mutated)
        compose.waitUntil(45_000) { gate.results.containsKey(probeId) }
        val negative = requireNotNull(gate.results[probeId])
        assertTrue(negative.flag("ok")); assertEquals(setOf("kind", "status"), negative.obj("data").keys)
        assertEquals("editor_outcome", negative.obj("data").text("kind")); assertEquals("unknown", negative.obj("data").text("status"))
        assertEquals(attempt, persisted().attempts.values.single())
        assertEquals(countsBeforeReload, transport.uploadCounts())
        val afterNegative = status()
        assertTrue(afterNegative.getJSONObject("postAppliedReload").getBoolean("oldLedgerPresent"))
        assertEquals(ownerContactsBefore, afterNegative.getJSONObject("postAppliedReload").getInt("ownerContactCount"))
        gate.allowQuery.complete(Unit)
        compose.waitUntil(60_000) { gate.results.containsKey(originalQuery.text("requestId")) && persisted().attempts.isEmpty() && gate.heldRelease.get() != null }
        val outcome = requireNotNull(gate.results[originalQuery.text("requestId")])
        assertTrue(outcome.flag("ok")); assertEquals("applied", outcome.obj("data").text("status")); assertEquals(receipt, outcome.obj("data").obj("receipt"))
        val release = requireNotNull(gate.heldRelease.get())
        assertEquals(receipt, release.obj("receipt")); assertEquals(1, persisted().pendingReleases.size)
        gate.allowRelease.complete(Unit)
        compose.waitUntil(60_000) { gate.results.containsKey(release.text("requestId")) && persisted().pendingReleases.isEmpty() }
        val released = requireNotNull(gate.results[release.text("requestId")])
        assertTrue(released.flag("ok")); assertEquals("editor_outcome_released", released.obj("data").text("kind"))
        assertEquals(countsBeforeReload, transport.uploadCounts())
        val countsAfterOldSettlement = transport.uploadCounts()
        val afterRelease = status()
        assertFalse(afterRelease.getJSONObject("postAppliedReload").getBoolean("oldLedgerPresent"))
        assertFalse(afterRelease.getJSONObject("postAppliedReload").getBoolean("oldTuplePresent"))
        val freshValue = fixture.getString("postAppliedReloadExpectedValue")
        assertEquals(32, freshValue.toByteArray(Charsets.UTF_8).size)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[freshId]?.text != null }
        assertEquals("", repository.state.value.pagedEditors[freshId]?.text)
        compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement(freshValue)
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[freshId]?.text == freshValue }
        compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.optJSONObject("postAppliedReload")?.let { it.optBoolean("freshLedgerApplied") && it.optBoolean("freshReleaseConfirmed") } == true }
        val final = status()
        val finalTransition = final.getJSONObject("postAppliedReload")
        assertTrue(finalTransition.getBoolean("freshSdkVerified")); assertEquals(1, finalTransition.getInt("freshSdkCount"))
        for (field in listOf("ownerPid", "runtimeId", "sessionFile")) assertEquals(transition.get(field), finalTransition.get(field))
        val freshCommit = gate.commits.values.single { it.optionalText("questionId") == freshId }
        val freshCommitId = freshCommit.text("requestId")
        compose.waitUntil(60_000) { gate.results.containsKey(freshCommitId) && gate.releases.values.any { it.obj("receipt").optionalText("questionId") == freshId && gate.results.containsKey(it.text("requestId")) } }
        val freshResult = requireNotNull(gate.results[freshCommitId])
        assertTrue(freshResult.flag("ok")); assertEquals("accepted", freshResult.obj("data").text("kind"))
        val freshRelease = gate.releases.values.single { it.obj("receipt").optionalText("questionId") == freshId }
        val freshReceipt = freshRelease.obj("receipt")
        assertEquals(freshReceipt, Json.parseToJsonElement(finalTransition.getJSONObject("freshReceipt").toString()).jsonObject)
        assertEquals(freshOwner, freshReceipt.text("ownerIncarnationId")); assertEquals(freshId, freshReceipt.text("questionId"))
        assertEquals(freshCommitId, freshReceipt.text("commitRequestId")); assertEquals(freshCommit.text("uploadId"), freshReceipt.text("uploadId"))
        assertEquals(32L, freshReceipt.long("byteLength")); assertEquals(digest(freshValue), freshReceipt.text("sha256"))
        val freshReleaseResult = requireNotNull(gate.results[freshRelease.text("requestId")])
        assertTrue(freshReleaseResult.flag("ok")); assertEquals("editor_outcome_released", freshReleaseResult.obj("data").text("kind"))
        assertEquals(2, transport.uploadCounts().third)
        assertTrue(persisted().attempts.isEmpty()); assertTrue(persisted().pendingReleases.isEmpty())
        val proof = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("before", before).put("applied", applied).put("reloaded", reloaded).put("beforeNegative", beforeNegative).put("afterNegative", afterNegative).put("afterRelease", afterRelease).put("final", final)
            .put("uploadCountsBeforeReload", org.json.JSONArray(listOf(countsBeforeReload.first, countsBeforeReload.second, countsBeforeReload.third)))
            .put("uploadCountsAfterOldSettlement", org.json.JSONArray(listOf(countsAfterOldSettlement.first, countsAfterOldSettlement.second, countsAfterOldSettlement.third)))
            .put("oldReceipt", JSONObject(receipt.toString())).put("mutatedReceipt", JSONObject(mutated.toString()))
            .put("negativeRequest", JSONObject(requireNotNull(gate.probeRequest.get()).toString())).put("negativeResult", JSONObject(negative.toString()))
            .put("outcomeRequest", JSONObject(originalQuery.toString())).put("outcomeResult", JSONObject(outcome.toString()))
            .put("releaseRequest", JSONObject(release.toString())).put("releaseResult", JSONObject(released.toString()))
            .put("freshCommitRequest", JSONObject(freshCommit.toString())).put("freshCommitResult", JSONObject(freshResult.toString()))
            .put("freshReceipt", JSONObject(freshReceipt.toString())).put("freshReleaseRequest", JSONObject(freshRelease.toString())).put("freshReleaseResult", JSONObject(freshReleaseResult.toString()))
            .put("freshQuestionId", freshId).put("freshOwnerIncarnationId", freshOwner).put("freshBytes", freshValue.toByteArray(Charsets.UTF_8).size).put("freshSha256", digest(freshValue))
        File(directory, "proof.json").outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
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
        client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(body).build()).execute().use { assertEquals(202, it.code) }
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

/** Suspend only the real old tuple before it enters the transport queue. */
private class PostAppliedGate(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    val oldOwner = AtomicReference<String?>(null)
    val heldQuery = AtomicReference<JsonObject?>(null)
    val heldRelease = AtomicReference<JsonObject?>(null)
    val probeRequest = AtomicReference<JsonObject?>(null)
    val allowQuery = CompletableDeferred<Unit>()
    val allowRelease = CompletableDeferred<Unit>()
    val results = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    val commits = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    val releases = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private val watched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val commandIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val commandEvents = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                val id = payload.optionalText("requestId")
                if (payload.optionalText("type") == "result" && id != null && id in watched) results[id] = payload
                if (payload.optionalText("type") == "result" && id != null && id in commandIds) {
                    commandEvents.add(buildJsonObject {
                        put("event", "result"); put("requestId", id)
                        (payload["ok"] as? JsonPrimitive)?.let { put("ok", it) }
                        ((payload["data"] ?: payload["error"]) as? JsonObject)?.let { data ->
                            for (key in listOf("kind", "code")) (data[key] as? JsonPrimitive)?.let { put(key, it) }
                        }
                    })
                }
                value.message(payload)
            }
        } }
    fun probe(receipt: JsonObject): String {
        val payload = Wire.objectOf("type" to "session.question.editor.outcome.get", "requestId" to Wire.random(), "receipt" to receipt)
        check(probeRequest.compareAndSet(null, payload)); watched.add(payload.text("requestId")); delegate.send(payload)
        return payload.text("requestId")
    }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
        val type = payload.optionalText("type")
        if (type == "session.commands.get") {
            val id = payload.text("requestId")
            commandIds.add(id)
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) commandEvents.add(buildJsonObject { put("event", "request_job_cancelled"); put("requestId", id) })
            }
            commandEvents.add(buildJsonObject { put("event", "send_attempt"); put("requestId", id); payload.optionalText("sessionId")?.let { put("sessionId", it) } })
            try {
                delegate.sendGuarded(payload) {
                    beforePossibleWrite()
                    commandEvents.add(buildJsonObject { put("event", "possible_write"); put("requestId", id) })
                }
            } catch (e: CancellationException) {
                commandEvents.add(buildJsonObject { put("event", "send_cancelled"); put("requestId", id) })
                throw e
            } catch (e: Exception) {
                commandEvents.add(buildJsonObject { put("event", "send_failed"); put("requestId", id); put("errorClass", e.javaClass.simpleName) })
                throw e
            }
            return
        }
        if (type == "session.question.editor.upload.commit") { commits[payload.text("requestId")] = payload; watched.add(payload.text("requestId")) }
        if (type == "session.question.editor.outcome.release") { releases[payload.text("requestId")] = payload; watched.add(payload.text("requestId")) }
        val receipt = payload["receipt"] as? JsonObject
        if (receipt?.optionalText("ownerIncarnationId") == oldOwner.get() && oldOwner.get() != null) {
            if (type == "session.question.editor.outcome.get") {
                heldQuery.set(payload); watched.add(payload.text("requestId"))
                try { allowQuery.await() } catch (e: CancellationException) { heldQuery.compareAndSet(payload, null); throw e }
            } else if (type == "session.question.editor.outcome.release") {
                check(heldRelease.compareAndSet(null, payload)); watched.add(payload.text("requestId")); allowRelease.await()
            }
        }
        delegate.sendGuarded(payload, beforePossibleWrite)
    }
}
