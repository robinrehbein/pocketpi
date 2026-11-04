package de.joinnoah.pi.remote

import android.content.ComponentName
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Private opt-in actual system IME, windowed editor, transport and SDK proof. */
class PagedEditorImeWindowEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val show = mutableStateOf(false)
    private lateinit var fixture: JSONObject
    private lateinit var nonce: String
    private lateinit var directory: File
    private lateinit var stores: AppStores
    private lateinit var host: PairedHost
    private lateinit var selection: RemoteSelection
    private lateinit var scope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var observer: ImeWindowWire
    private lateinit var presence: PresenceObservingTransport
    private lateinit var repository: DefaultRemoteRepository
    private val stages = JSONArray()
    private val boundaries = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val samples = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val pending = AtomicBoolean(false)
    private val pendingSince = AtomicLong(0)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var servicePid: Int? = null
    private var latestIme: JSONObject? = null
    private var defaultIme: String? = null
    private var native = false
    private var failure: Throwable? = null
    private val uri = Uri.parse("content://de.robinrehbein.pocketpi.test-ime")
    private val source = "😀".repeat(262144)
    private val firstEdit = "に" + "😀".repeat(262143)
    private val secondEdit = "に" + "😀".repeat(1023) + "XY" + "😀".repeat(261119)
    private val finalValue = "p".repeat(4096) + "Y" + "😀".repeat(261119)

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit private IME window fixture required", args.getString("pagedImeWindowExpected") == "true")
            nonce = requireNotNull(args.getString("pagedImeWindowNonce")); require(nonce.matches(Regex("[a-f0-9]{32}")))
            require(args.getString("prStatusFixtureExpected") == "true")
            require(args.getString("prStatusNativeExpected") in setOf("true", "false"))
            native = args.getString("prStatusNativeExpected") == "true"
            fixture = JSONObject(readFixture(requireNotNull(args.getString("prStatusFixturePath"))).toString(Charsets.UTF_8))
            require(fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
            require(fixture.getBoolean("pagedEditorFixture") && fixture.getBoolean("pagedImeWindowProof") && fixture.getString("pagedEditorCase") == "ime-window")
            require(!fixture.optBoolean("pagedEditorPositiveOutcomes") && fixture.getBoolean("nativeWorker") == native)
            val relay = fixture.getString("relayUrl").toHttpUrl()
            require(relay.scheme == "https" && relay.host == "10.0.2.2" && relay.encodedPath == "/")
            val status = fixture.getString("statusUrl").toHttpUrl()
            require(status.scheme == "https" && status.host == relay.host && status.port == relay.port && status.encodedPath == "/fixture-status" && status.query == null && status.fragment == null)
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            directory = File(app.noBackupFilesDir, "paged-ime-window-$nonce")
            check(directory.mkdirs() || directory.isDirectory); check(!File(directory, "proof.json").exists())
            val expected = ComponentName(app.packageName, "de.joinnoah.pi.remote.testing.PocketPiTestIme")
            defaultIme = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            require(defaultIme?.let(ComponentName::unflattenFromString) == expected) { "Controlled IME is not system default" }
            val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
            stores = AppStores(context); check(stores.pairings.load().isEmpty())
            host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "IME window fixture")
            stores.pairings.save(listOf(host)); selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(readFixture(fixture.getString("caCertPath")).inputStream())
            val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            observer = ImeWindowWire(SecureRemoteTransport(scope, client))
            presence = PresenceObservingTransport(observer, requireNotNull(selection.sessionId), true, false)
            repository = DefaultRemoteRepository(stores.pairings, stores.drafts, presence, scope, pagedEditorTestGate = true, pagedEditorStorage = stores.pagedEditor, positiveOutcomeTestGate = false)
            NavigationTestActivity.contentFactory = { { if (show.value) MaterialTheme { RemoteNavigation(repository, PrStatusSettings(), false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1)) } } }
        }
        override fun after() {
            scheduler.shutdownNow()
            if (::directory.isInitialized && !File(directory, "proof.json").exists()) runCatching { writeProof("diagnostic.json") }
            NavigationTestActivity.contentFactory = { {} }
            if (::repository.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { repository.setForeground(false); repository.disconnect() }
            if (::scope.isInitialized) scope.cancel()
            if (::client.isInitialized) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }).around(compose)

    @Test fun realImeWindowAndSdkChain() {
        try { execute() } catch (t: Throwable) { failure = t; throw t }
    }

    private fun execute() {
        assertEquals(1048576, source.toByteArray().size); assertEquals(1048573, finalValue.toByteArray().size)
        compose.runOnIdle { repository.setForeground(true); repository.setValidatedNetwork("paged-ime-window") }
        compose.waitUntil(60_000) { repository.state.value.hostsLoaded && repository.state.value.hosts.any { it.routeId == host.routeId } }
        activate(RemoteSelection(host.routeId)); compose.waitUntil(60_000) { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
        activate(RemoteSelection(host.routeId, selection.projectId)); compose.waitUntil(60_000) { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
        compose.runOnIdle { show.value = true }
        val command = fixture.getString("editorProbeCommand") + " " + fixture.getString("editorProbeNonce")
        compose.waitUntil(60_000) { repository.state.value.let { it.selection == selection && it.connected && !it.loading && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities && presence.pagedV3Accepted.get() && (!native || presence.presenceAccepted.get()) && it.commands.any { c -> c.name == commandName(fixture.getString("editorProbeCommand")) } && !it.commandsLoading } }
        assertTrue(imeCall("reset").getBoolean("ok"))
        compose.onNodeWithTag("composerField").performClick()
        var s = awaitIme(0); val composerGeneration = s.getInt("generation")
        imeAction(composerGeneration, "commit", Bundle().apply { putString("text", command) })
        compose.waitUntil(60_000) { repository.state.value.draft == command }
        composerObservation("before-send")
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
        val imeHeightDp = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom / compose.activity.resources.displayMetrics.density
        assertTrue("Controlled IME must be visible and occupy a small strip; heightDp=$imeHeightDp", insets.isVisible(WindowInsetsCompat.Type.ime()) && imeHeightDp > 0f && imeHeightDp < 320f)
        val button = compose.onNodeWithTag("primaryComposerAction")
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue("Composer Send must have positive bounds: $buttonBounds", buttonBounds.width > 0f && buttonBounds.height > 0f)
        button.assertIsDisplayed().assertIsEnabled().performClick()
        composerObservation("after-send")
        try {
            compose.waitUntil(8_000) { repository.state.value.draft.isEmpty() }
        } catch (t: Throwable) {
            composerObservation("send-timeout")
            throw AssertionError("Composer draft was not consumed after Send; inspect composer observations and screenshots", t)
        }
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(60_000) { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
        val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
        val id = question.text("id"); val grant = question.text("editorUploadGrantId")
        val owner = PagedEditorWire.ownerIncarnationId(question)
        stages.put(JSONObject().put("stage", "question-binding").put("question", JSONObject(question.toString())).put("routeId", host.routeId).put("deviceId", host.deviceId))
        boundary("before-load"); startProbes()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { repository.state.value.pagedEditors[id]?.text == source }
        assertEquals(fixture.getString("pagedEditorSourceSha256"), digest(source)); boundary("loaded")
        assertEquals("😀".repeat(1024), fieldText())
        compose.onNodeWithTag("questionEditorField").performScrollTo().performClick()
        s = awaitIme(composerGeneration); var generation = s.getInt("generation")
        assertEquals("😀".repeat(1024), s.getString("text")); record("source", id, 0, s)
        pace("select-first")
        var mark = seq(s); imeAction(generation, "select", Bundle().apply { putInt("start", 0); putInt("end", 2) })
        s = awaitCallback(generation, mark) { it.optInt("newSelStart") == 0 && it.optInt("newSelEnd") == 2 }
        pace("compose-ni"); mark = seq(s); imeAction(generation, "compose", Bundle().apply { putString("text", "ni") })
        s = awaitCallback(generation, mark) { it.optInt("candidatesStart") == 0 && it.optInt("candidatesEnd") == 2 }
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == "ni" + "😀".repeat(262143) }
        assertEquals("ni" + "😀".repeat(1023), fieldText()); assertEquals(fieldText(), s.getString("text")); record("compose-ni", id, 0, s)
        pace("compose-kana"); mark = seq(s); imeAction(generation, "compose", Bundle().apply { putString("text", "に") })
        s = awaitCallback(generation, mark) { it.optInt("candidatesStart") == 0 && it.optInt("candidatesEnd") == 1 }
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == firstEdit }
        assertEquals("に" + "😀".repeat(1023), fieldText()); assertEquals(fieldText(), s.getString("text"))
        record("composing", id, 0, s)
        pace("next-with-composition"); val old = generation
        compose.onNodeWithTag("pagedEditorNext").performScrollTo().assertIsEnabled().performClick()
        s = awaitIme(old); generation = s.getInt("generation")
        val events = s.getJSONArray("events")
        val start = (0 until events.length()).map { events.getJSONObject(it) }.last { it.optString("event") == "startInput" && it.optInt("generation") == generation }
        assertEquals(0, start.getInt("initialSelStart")); assertEquals(0, start.getInt("initialSelEnd"))
        assertEquals(firstEdit, repository.state.value.pagedEditors[id]?.text)
        assertEquals("😀".repeat(1024), fieldText()); assertEquals(fieldText(), s.getString("text"))
        assertEquals(0, s.getInt("selectionStart")); assertEquals(0, s.getInt("selectionEnd"))
        val before = digest(requireNotNull(repository.state.value.pagedEditors[id]?.text))
        val stale = imeCall("action", Bundle().apply { putString("op", "commit"); putInt("generation", old); putString("text", "STALE") })
        assertFalse(stale.optBoolean("ok", true)); assertEquals("stale generation", stale.optString("error")); assertEquals(generation, stale.getInt("generation"))
        compose.waitForIdle(); assertEquals(before, digest(requireNotNull(repository.state.value.pagedEditors[id]?.text)))
        record("next-stale-rejected", id, 2047, imeSnapshot(), stale)
        pace("select-second"); mark = seq(s); imeAction(generation, "select", Bundle().apply { putInt("start", 0); putInt("end", 2) })
        s = awaitCallback(generation, mark) { it.optInt("newSelStart") == 0 && it.optInt("newSelEnd") == 2 }
        pace("commit-xy"); mark = seq(s); imeAction(generation, "commit", Bundle().apply { putString("text", "XY") })
        s = awaitCallback(generation, mark) { it.optInt("newSelStart") == 2 && it.optInt("newSelEnd") == 2 }
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == secondEdit }; record("xy", id, 2047, s)
        pace("first"); compose.onNodeWithTag("pagedEditorFirst").performScrollTo().assertIsEnabled().performClick()
        s = awaitIme(generation); generation = s.getInt("generation")
        assertEquals("に" + "😀".repeat(1023) + "X", fieldText()); assertEquals(fieldText(), s.getString("text")); record("first", id, 0, s)
        pace("select-window"); mark = seq(s); imeAction(generation, "select", Bundle().apply { putInt("start", 0); putInt("end", 2048) })
        s = awaitCallback(generation, mark) { it.optInt("newSelStart") == 0 && it.optInt("newSelEnd") == 2048 }
        pace("programmatic-paste-4096"); imeAction(generation, "commit", Bundle().apply { putString("text", "p".repeat(4096)) })
        compose.waitUntil(60_000) { repository.state.value.pagedEditors[id]?.text == finalValue }
        s = awaitIme(generation); generation = s.getInt("generation")
        val expectedPasteWindow = "p".repeat(1024) + "Y" + "😀".repeat(511)
        assertEquals(2047, expectedPasteWindow.length); assertTrue(expectedPasteWindow.length <= 2048 && !Character.isHighSurrogate(expectedPasteWindow.last()))
        assertEquals(expectedPasteWindow, fieldText()); assertEquals(fieldText(), s.getString("text")); assertEquals(1024, s.getInt("selectionStart")); assertEquals(1024, s.getInt("selectionEnd"))
        assertEquals(fixture.getString("pagedEditorFinalSha256"), digest(finalValue)); record("paste-rewindow", id, 3072, s)
        pace("last"); compose.onNodeWithTag("pagedEditorLast").performScrollTo().assertIsEnabled().performClick(); s = awaitIme(generation); generation = s.getInt("generation")
        assertEquals(finalValue.takeLast(fieldText().length), fieldText()); assertEquals(fieldText(), s.getString("text")); assertEquals(finalValue, repository.state.value.pagedEditors[id]?.text); record("last", id, finalValue.length - fieldText().length, s)
        pace("first-again"); compose.onNodeWithTag("pagedEditorFirst").performScrollTo().assertIsEnabled().performClick(); s = awaitIme(generation)
        assertEquals(finalValue.take(2048), fieldText()); assertEquals(fieldText(), s.getString("text")); assertEquals(finalValue, repository.state.value.pagedEditors[id]?.text); record("first-again", id, 0, s)
        val waitStart = SystemClock.elapsedRealtime(); boundary("foreground-hold")
        while (SystemClock.elapsedRealtime() - waitStart < 22000) {
            val st = status(); stages.put(JSONObject().put("stage", "foreground-hold").put("elapsedNanos", SystemClock.elapsedRealtimeNanos()).put("status", st))
            assertTrue(st.getBoolean("ownerPidVerified"))
            if (native) { assertTrue(st.getBoolean("nativeWorkerVerified")); assertEquals(0, st.getInt("nativeEditorOriginalCalls")) }
            assertTrue(repository.state.value.connected); assertTrue(repository.state.value.questions.any { it.optionalText("id") == id })
            assertEquals(finalValue, repository.state.value.pagedEditors[id]?.text); imeSnapshot(); Thread.sleep(2500)
        }
        boundary("send"); compose.onNodeWithText(compose.activity.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(120_000) { statusOrNull()?.let { it.optBoolean("pagedOwnerVerified") && it.optBoolean("pagedCommitDispatched") && it.optBoolean("pagedAckVerified") && it.optBoolean("pagedSdkResultVerified") && it.optInt("pagedCommitCount", -1) == 1 } == true && repository.state.value.questions.none { it.optionalText("id") == id } }
        boundary("send-complete"); val status = status()
        observer.verify(selection.sessionId!!, id, grant, owner, finalValue, native)
        assertTrue(status.getBoolean("ownerPidVerified")); assertEquals(1, status.getInt("pagedBeginCount")); assertEquals(22, status.getInt("pagedChunkCount"))
        assertEquals(1, status.getInt("pagedCommitCount")); assertEquals(1, status.getInt("sourceSdkResultCount"))
        if (native) { assertTrue(status.getBoolean("nativeWorkerVerified")); assertTrue(status.getBoolean("nativeEditorVerified")); assertEquals(0, status.getInt("nativeEditorOriginalCalls")) }
        val ack = status.getJSONObject("imeWindowSourceAck")
        assertEquals("editor_owner_upload_committed", ack.getString("kind")); assertEquals(selection.sessionId, ack.getString("sessionId")); assertEquals(id, ack.getString("questionId"))
        assertTrue(ack.getString("transferId").isNotEmpty()); assertEquals(1048573, ack.getInt("byteLength")); assertEquals(digest(finalValue), ack.getString("sha256"))
        stages.put(JSONObject().put("stage", "final-status").put("status", status))
        scheduler.shutdown(); check(scheduler.awaitTermination(5, TimeUnit.SECONDS)); compose.waitUntil(10_000) { !pending.get() }
        writeProof("proof.json")
    }

    private fun activate(target: RemoteSelection) {
        compose.runOnIdle { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }
        compose.waitUntil(60_000) { repository.state.value.selection == target && !repository.state.value.loading && repository.state.value.connected }
    }
    private fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
    private fun statusOrNull() = runCatching { status() }.getOrNull()
    private fun imeCall(method: String, extras: Bundle? = null): JSONObject = JSONObject(requireNotNull(compose.activity.contentResolver.call(uri, method, null, extras)?.getString("json")))
    private fun imeSnapshot(): JSONObject {
        val s = imeCall("snapshot"); latestIme = s
        check(!s.getBoolean("overflow")) { "IME trace overflow" }
        servicePid?.let { check(s.getInt("pid") == it) { "IME process changed" } }
        return s
    }
    private fun awaitIme(previous: Int): JSONObject {
        compose.waitUntil(30_000) {
            val s = imeSnapshot(); val gen = s.optInt("generation")
            val events = s.getJSONArray("events")
            val started = (0 until events.length()).any { i -> events.getJSONObject(i).let { it.optString("event") == "startInput" && it.optInt("generation") == gen && it.optString("packageName") == compose.activity.packageName } }
            val view = previous != 0 || (0 until events.length()).any { i -> events.getJSONObject(i).let { it.optString("event") == "startInputView" && it.optInt("generation") == gen && it.optString("packageName") == compose.activity.packageName } }
            s.optBoolean("active") && s.optBoolean("connection") && s.optString("packageName") == compose.activity.packageName && gen > previous && started && view
        }
        val s = requireNotNull(latestIme)
        assertEquals(ComponentName(compose.activity.packageName, "de.joinnoah.pi.remote.testing.PocketPiTestIme"), ComponentName.unflattenFromString(s.getString("component")))
        assertNotEquals(Process.myPid(), s.getInt("pid")); if (servicePid == null) servicePid = s.getInt("pid")
        return s
    }
    private fun imeAction(generation: Int, op: String, extras: Bundle = Bundle()) {
        extras.putInt("generation", generation); extras.putString("op", op)
        val result = imeCall("action", extras); assertTrue("IME $op: $result", result.optBoolean("ok") && result.optBoolean("result"))
    }
    private fun awaitCallback(generation: Int, after: Long, predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(30_000) {
            val s = imeSnapshot(); val events = s.getJSONArray("events")
            s.optInt("generation") == generation && (0 until events.length()).any { i -> events.getJSONObject(i).let { e -> e.optLong("sequence") > after && e.optString("event") == "updateSelection" && e.optInt("generation") == generation && predicate(e) } }
        }
        return requireNotNull(latestIme)
    }
    private fun seq(s: JSONObject): Long { val a = s.getJSONArray("events"); return if (a.length() == 0) 0 else a.getJSONObject(a.length() - 1).getLong("sequence") }
    private fun fieldText(): String = requireNotNull(compose.onNodeWithTag("questionEditorField").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)).text
    private fun record(label: String, id: String, windowStart: Int, s: JSONObject, extra: JSONObject? = null) {
        val text = requireNotNull(repository.state.value.pagedEditors[id]?.text); val field = fieldText()
        stages.put(JSONObject().put("stage", label).put("elapsedNanos", SystemClock.elapsedRealtimeNanos()).put("windowStart", windowStart)
            .put("windowText", field).put("windowLengthUtf16", field.length).put("draftLengthUtf16", text.length).put("draftBytes", text.toByteArray().size)
            .put("draftSha256", digest(text)).put("ime", s).put("extra", extra ?: JSONObject.NULL))
    }
    private fun pace(label: String) { boundary("human-paced-injection:$label"); Thread.sleep(650) }
    private fun composerObservation(label: String) {
        val state = repository.state.value
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
        val bounds = compose.onAllNodesWithTag("primaryComposerAction").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        stages.put(JSONObject().put("stage", "composer-$label").put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
            .put("draft", state.draft).put("connected", state.connected).put("loading", state.loading).put("sending", state.sending)
            .put("sessionId", state.selection.sessionId ?: JSONObject.NULL).put("routeId", state.selection.routeId ?: JSONObject.NULL)
            .put("questions", JSONArray(state.questions.map { JSONObject(it.toString()) }))
            .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
            .put("density", compose.activity.resources.displayMetrics.density.toDouble())
            .put("imeInsets", if (ime == null) JSONObject.NULL else JSONObject().put("left", ime.left).put("top", ime.top).put("right", ime.right).put("bottom", ime.bottom))
            .put("buttonBoundsInRoot", if (bounds == null) JSONObject.NULL else JSONObject().put("left", bounds.left.toDouble()).put("top", bounds.top.toDouble()).put("right", bounds.right.toDouble()).put("bottom", bounds.bottom.toDouble())))
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(directory, "composer-$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
    }
    private fun boundary(label: String) { boundaries.add(JSONObject().put("label", label).put("elapsedNanos", SystemClock.elapsedRealtimeNanos())) }
    private fun startProbes() {
        scheduler.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtimeNanos()
            if (pending.compareAndSet(false, true)) {
                pendingSince.set(now)
                val sample = JSONObject().put("tickNanos", now).put("enqueueNanos", now)
                samples.add(sample)
                main.post { synchronized(samples) { sample.put("completionNanos", SystemClock.elapsedRealtimeNanos()) }; pending.set(false) }
            } else samples.add(JSONObject().put("tickNanos", now).put("pending", true).put("pendingEnqueueNanos", pendingSince.get()))
        }, 0, 1, TimeUnit.SECONDS)
    }
    private fun writeProof(filename: String) {
        val sampleCopy = synchronized(samples) { JSONArray(samples.map { JSONObject(it.toString()) }) }
        val completed = (0 until sampleCopy.length()).map { sampleCopy.getJSONObject(it) }.filter { it.has("completionNanos") }
        val delays = completed.map { it.getLong("completionNanos") - it.getLong("enqueueNanos") }.sorted()
        val proof = JSONObject().put("nonce", nonce).put("mode", if (native) "native" else "rpc").put("defaultIme", defaultIme ?: JSONObject.NULL)
            .put("appPid", Process.myPid()).put("servicePid", servicePid ?: JSONObject.NULL).put("stages", stages).put("latestIme", latestIme ?: JSONObject.NULL)
            .put("wire", if (::observer.isInitialized) observer.json() else JSONArray()).put("capabilities", if (::repository.isInitialized) JSONArray(repository.state.value.capabilities.sorted()) else JSONArray())
            .put("presenceMetrics", if (::observer.isInitialized) observer.presenceMetrics(selection.sessionId!!) else JSONObject.NULL)
            .put("boundaries", synchronized(boundaries) { JSONArray(boundaries.toList()) }).put("mainQueueSamples", sampleCopy)
            .put("mainQueueMaxNanos", delays.lastOrNull() ?: JSONObject.NULL).put("mainQueueP95Nanos", if (delays.isEmpty()) JSONObject.NULL else delays[(kotlin.math.ceil(delays.size * 0.95).toInt() - 1)])
            .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
        File(directory, filename).outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun readFixture(path: String): ByteArray {
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        return ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")).use { it.readBytes() }.also { require(it.isNotEmpty()) }
    }
}

/** Passive immutable request/result observations; never changes or sends a request. */
private class ImeWindowWire(private val delegate: RemoteTransport) : RemoteTransport by delegate {
    private data class Entry(val request: JsonObject, val sent: Long, @Volatile var result: JsonObject? = null, @Volatile var received: Long = 0)
    private val entries = ConcurrentHashMap<String, Entry>()
    override var listener: RemoteTransport.Listener
        get() = delegate.listener
        set(value) { delegate.listener = object : RemoteTransport.Listener by value {
            override fun message(payload: JsonObject) {
                if (payload.optionalText("type") == "result") payload.optionalText("requestId")?.let { id -> entries[id]?.let { e -> e.received = SystemClock.elapsedRealtimeNanos(); e.result = kotlinx.serialization.json.Json.parseToJsonElement(payload.toString()) as JsonObject } }
                value.message(payload)
            }
        } }
    private fun observe(payload: JsonObject) {
        val type = payload.optionalText("type") ?: return
        if (type == "session.editor.presence" || type.startsWith("session.question.editor.upload.")) {
            val copy = kotlinx.serialization.json.Json.parseToJsonElement(payload.toString()) as JsonObject
            check(entries.putIfAbsent(payload.text("requestId"), Entry(copy, SystemClock.elapsedRealtimeNanos())) == null)
        }
    }
    override fun send(payload: JsonObject) { observe(payload); delegate.send(payload) }
    override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) { observe(payload); delegate.sendGuarded(payload, beforePossibleWrite) }
    fun json(): JSONArray = JSONArray(entries.values.sortedBy { it.sent }.map { e -> JSONObject().put("request", JSONObject(e.request.toString())).put("requestNanos", e.sent).put("result", e.result?.let { JSONObject(it.toString()) } ?: JSONObject.NULL).put("resultNanos", if (e.received == 0L) JSONObject.NULL else e.received) })
    private fun acceptedPresence(session: String) = entries.values.filter { it.request.optionalText("type") == "session.editor.presence" && it.request.optionalText("sessionId") == session && it.request["active"]?.toString() == "true" && it.result?.let { r -> r.flag("ok") && r.obj("data").optionalText("kind") == "accepted" && r.obj("data").optionalText("sessionId") == session } == true }.sortedBy { it.received }
    fun presenceMetrics(session: String): JSONObject {
        val accepted = acceptedPresence(session); val gaps = accepted.zipWithNext { a, b -> b.received - a.received }
        return JSONObject().put("acceptedCount", accepted.size).put("acceptedSpanNanos", if (accepted.size < 2) 0 else accepted.last().received - accepted.first().received)
            .put("acceptedGapsNanos", JSONArray(gaps)).put("maximumAcceptedGapNanos", gaps.maxOrNull() ?: JSONObject.NULL).put("leaseTtlNanos", TimeUnit.SECONDS.toNanos(15))
            .put("maximumGapBelowTtl", gaps.maxOrNull()?.let { it < TimeUnit.SECONDS.toNanos(15) } ?: JSONObject.NULL)
    }
    fun verify(session: String, question: String, grant: String, owner: String?, expected: String, native: Boolean) {
        val items = entries.values.toList(); val relevant = items.filter { it.request.optionalText("questionId") == question }
        val begin = relevant.single { it.request.optionalText("type") == "session.question.editor.upload.begin" }
        val chunks = relevant.filter { it.request.optionalText("type") == "session.question.editor.upload.chunk" }.sortedBy { it.request.long("offset") }
        val commit = relevant.single { it.request.optionalText("type") == "session.question.editor.upload.commit" }
        assertEquals(22, chunks.size); assertEquals(session, begin.request.text("sessionId")); assertEquals(grant, begin.request.text("grantId"))
        assertEquals(expected.toByteArray().size.toLong(), begin.request.long("totalBytes"))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).joinToString("") { "%02x".format(it) }, begin.request.text("sha256"))
        begin.request.optionalText("ownerIncarnationId")?.let { assertEquals(owner, it) }
        commit.request.optionalText("ownerIncarnationId")?.let { assertEquals(owner, it) }
        val beginResult = requireNotNull(begin.result); assertTrue(beginResult.flag("ok"))
        val beginData = beginResult.obj("data")
        assertEquals("editor_upload_begun", beginData.text("kind")); assertEquals(session, beginData.text("sessionId")); assertEquals(question, beginData.text("questionId"))
        val uploadId = beginData.text("uploadId"); assertEquals(uploadId, commit.request.text("uploadId"))
        val all = java.io.ByteArrayOutputStream(); var offset = 0L
        for (chunk in chunks) {
            assertEquals(offset, chunk.request.long("offset")); val bytes = Wire.decode(chunk.request.text("data")); all.write(bytes); offset += bytes.size
            assertEquals(session, chunk.request.text("sessionId")); assertEquals(question, chunk.request.text("questionId")); assertEquals(uploadId, chunk.request.text("uploadId"))
            val result = requireNotNull(chunk.result); assertTrue(result.flag("ok")); val data = result.obj("data")
            assertEquals("editor_upload_ack", data.text("kind")); assertEquals(session, data.text("sessionId")); assertEquals(question, data.text("questionId")); assertEquals(uploadId, data.text("uploadId")); assertEquals(offset, data.long("nextOffset"))
        }
        assertArrayEquals(expected.toByteArray(), all.toByteArray())
        for (e in listOf(begin, commit) + chunks) { val result = requireNotNull(e.result); assertTrue(result.flag("ok")); assertEquals(session, result.obj("data").text("sessionId")) }
        assertEquals("accepted", requireNotNull(commit.result).obj("data").text("kind"))
        assertEquals(question, commit.request.text("questionId"))
        val active = acceptedPresence(session)
        if (native) { assertTrue("Three accepted real active presence calls required", active.size >= 3); assertTrue("Presence span below 20s", active.last().received - active.first().received >= TimeUnit.SECONDS.toNanos(20)) }
        assertEquals(session, commit.request.text("sessionId"))
    }
}
