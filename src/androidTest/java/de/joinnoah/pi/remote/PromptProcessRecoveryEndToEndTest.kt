package de.joinnoah.pi.remote

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in private fixture. Phase A intentionally remains alive until the runner kills its process. */
class PromptProcessRecoveryEndToEndTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val content = mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { NavigationTestActivity.contentFactory = { { content.value?.invoke() } } }
        override fun after() { NavigationTestActivity.contentFactory = { {} } }
    }).around(compose)

    @Test fun processRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit private process-recovery fixture required", args.getString("processRecoveryExpected") == "true")
        val phase = requireNotNull(args.getString("processRecoveryPhase"))
        require(phase in setOf("A", "B", "CLEANUP"))
        val label = requireNotNull(args.getString("processRecoveryLabel"))
        require(label.matches(Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")))
        val edited = args.getString("processRecoveryEdited") == "true"
        val context = instrumentation.targetContext
        val proofFile = File(context.filesDir, "process-recovery-$label.json")
        val storeName = "process-recovery-$label.enc"
        val alias = "process-recovery-$label"
        if (phase == "CLEANUP") {
            EncryptedFileStore.dropKey(alias)
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            listOf(proofFile, File(context.filesDir, "process-recovery-$label-b.json"), File(context.noBackupFilesDir, storeName), File(context.noBackupFilesDir, "$storeName.bak"), File(context.noBackupFilesDir, "$storeName.new")).forEach { assertTrue(!it.exists() || it.delete()) }
            return
        }
        fun read(path: String): ByteArray {
            require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
            return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path")).use { it.readBytes() }.also { require(it.isNotEmpty()) }
        }
        val fixture = JSONObject(read(requireNotNull(args.getString("processRecoveryFixturePath"))).toString(Charsets.UTF_8))
        require(fixture.getBoolean("nativeWorker") && fixture.getJSONObject("nativeLifecycle").getBoolean("lostAck"))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(read(fixture.getString("caCertPath")).inputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", cert) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).callTimeout(3, TimeUnit.SECONDS).build()
        fun status(): JSONObject = client.newCall(Request.Builder().url(fixture.getString("statusUrl")).build()).execute().use { check(it.isSuccessful); JSONObject(requireNotNull(it.body).string()) }
        fun assertOwner(full: JSONObject, expected: JSONObject? = null): JSONObject {
            assertTrue(full.getBoolean("nativeWorkerVerified"))
            val workflow = full.getJSONObject("workflow")
            assertTrue(workflow.getBoolean("ownerStable"))
            val initial = workflow.getJSONObject("initialOwner")
            val current = workflow.getJSONObject("currentOwner")
            for (field in listOf("pid", "sessionId", "runtimeId", "project")) {
                assertEquals(initial.get(field), current.get(field))
                if (expected != null) assertEquals(expected.get(field), current.get(field))
            }
            return initial
        }
        fun action(value: String) { client.newCall(Request.Builder().url(fixture.getString("actionUrl")).post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(200, it.code) } }
        fun waitFor(predicate: () -> Boolean) = compose.waitUntil(90_000) { runCatching(predicate).getOrDefault(false) }
        val store = DraftStore(context, storeName, alias)
        val host = PairedHost(fixture.getString("routeId"), fixture.getString("relayUrl"), fixture.getString("deviceId"), fixture.getString("deviceSecret"), "Private fixture")
        val selection = RemoteSelection(host.routeId, fixture.getString("projectId"), fixture.getString("sessionId"))
        val key = DraftKey(host.routeId, requireNotNull(selection.sessionId))
        val config = fixture.getJSONObject("nativeLifecycle")
        val prompt = config.getString("prompt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val writes = AtomicInteger()
        val inbound = mutableListOf<JsonObject>()
        var capturing = false
        var overflow = false
        val transport = SecureRemoteTransport(scope, client)
        fun observe(payload: JsonObject) { if (payload.optionalText("type") == "session.prompt" && payload.optionalText("text") == prompt) writes.incrementAndGet() }
        val observer = object : RemoteTransport by transport {
            override fun send(payload: JsonObject) { observe(payload); transport.send(payload) }
            override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) { transport.sendGuarded(payload) { observe(payload); beforePossibleWrite() } }
            override var listener: RemoteTransport.Listener
                get() = transport.listener
                set(value) { transport.listener = object : RemoteTransport.Listener by value {
                    override fun message(payload: JsonObject) { synchronized(inbound) { if (capturing) { if (inbound.size < 256) inbound.add(payload) else overflow = true } }; value.message(payload) }
                } }
        }
        val pairings = object : PairingStorage { override fun load() = listOf(host); override fun save(hosts: List<PairedHost>) = Unit }
        if (phase == "A") { require(!proofFile.exists()); require(store.load().isEmpty()) }
        val proof = if (phase == "B") JSONObject(proofFile.readText()) else null
        if (proof != null) {
            assertNotEquals(proof.getInt("pid"), Process.myPid())
            assertEquals(edited, proof.getBoolean("edited"))
            assertEquals(proof.getString("requestId"), store.load()[key]?.mutationId)
            assertEquals(prompt, store.load()[key]?.submittedText)
            assertTrue(status().getJSONObject("nativeLifecycle").getBoolean("cut"))
            assertFalse(status().getBoolean("deviceOnline"))
        }
        val repository = DefaultRemoteRepository(pairings, store, observer, scope)
        fun main(block: () -> Unit) = compose.runOnIdle(block)
        fun activate(target: RemoteSelection) { main { scope.launch { repository.activate(target, ActivationMode.USER_OPEN) } }; waitFor { repository.state.value.selection == target && repository.state.value.connected && !repository.state.value.loading } }
        try {
            if (phase == "A") {
                main { repository.setForeground(true); repository.setValidatedNetwork("process-recovery-fixture") }
                waitFor { repository.state.value.hostsLoaded }
                activate(RemoteSelection(host.routeId)); waitFor { repository.state.value.projects.any { it.optionalText("id") == selection.projectId } }
                activate(RemoteSelection(host.routeId, selection.projectId)); waitFor { repository.state.value.sessions.any { it.optionalText("id") == selection.sessionId } }
                // Match the production-navigation fixture: resolve project/session lists before
                // mounting navigation so its notification opens the chat exactly once.
                val settings = RecoverySettings()
                main { content.value = { MaterialTheme { RemoteNavigation(repository, settings, false, {}, RemoteNotification(host.routeId, requireNotNull(selection.sessionId), 1)) } } }
                waitFor { repository.state.value.selection == selection && repository.state.value.connected && !repository.state.value.loading }
                waitFor { HOST_WORKER_V2_CAPABILITY in repository.state.value.capabilities && status().getBoolean("nativeWorkerVerified") }
                fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                fun tag(tag: String) { waitFor { exists(tag) } }
                tag("composerField")
                if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick() else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
                val workflow = fixture.getJSONObject("workflow")
                val name = workflow.getString("fileName")
                waitFor { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
                compose.onNodeWithTag(if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name").performScrollTo().performClick()
                tag("filesEdit"); compose.onNodeWithTag("filesEdit").performClick(); tag("filesEditorText")
                compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText")).performTextReplacement(workflow.getString("editedText"))
                compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
                waitFor { status().getJSONObject("workflow").getBoolean("fileSaved") }
                var panesClosed = false
                repeat(6) {
                    if (!panesClosed) {
                        val close = listOf("filesEditorBack", "filesClose", "filesBack", "changesClose").firstOrNull(::exists)
                        if (close == null) {
                            compose.onNodeWithTag("composerField").assertIsDisplayed()
                            check(!exists("filesPane") && !exists("changesPane"))
                            panesClosed = true
                        } else {
                            compose.onNodeWithTag(close).performClick()
                            compose.waitForIdle()
                        }
                    }
                }
                check(panesClosed) { "Could not close the foreground pane" }
                tag("composerField")
                main { repository.draft(workflow.getString("agentPrompt")); repository.prompt() }
                waitFor { status().getJSONObject("workflow").getBoolean("agentVerified") && repository.state.value.status == "idle" }
                synchronized(inbound) { inbound.clear(); overflow = false; capturing = true }
                action("arm-lifecycle-lost-ack")
                main { repository.draft(prompt); repository.prompt() }
                var request = ""
                waitFor { repository.state.value.pendingSend?.let { if (it.text == prompt) { request = it.mutationId; true } else false } == true }
                waitFor { val l = status().getJSONObject("nativeLifecycle"); val lost = l.getJSONObject("lostAck"); l.getBoolean("running") && lost.getBoolean("accepted") && lost.getBoolean("ackAccepted") && lost.getInt("ackQueued") == 1 && lost.getInt("echoQueued") >= 1 }
                val lost = status().getJSONObject("nativeLifecycle").getJSONObject("lostAck")
                assertEquals(request, lost.getString("requestId")); assertEquals(0, lost.getInt("ackSent")); assertEquals(0, lost.getInt("echoSent"))
                val echo = lost.getString("echoSourceId")
                action("cut-lifecycle")
                waitFor { val s = repository.state.value; !s.connected && s.uncertain && s.pendingSend?.mutationId == request && s.pendingSend?.state == PendingSendState.NOT_CONFIRMED && s.draft == prompt }
                synchronized(inbound) { capturing = false; assertFalse(overflow); assertFalse(inbound.any { it.toString().contains(request) || it.toString().contains(echo) || it.toString().contains(prompt) }) }
                assertEquals(1, writes.get())
                val cutStatus = status().getJSONObject("nativeLifecycle")
                assertEquals(1, cutStatus.getInt("promptRequests"))
                assertEquals(0, cutStatus.getJSONObject("lostAck").getInt("ackSent"))
                assertEquals(0, cutStatus.getJSONObject("lostAck").getInt("echoSent"))
                if (edited) main { repository.draft(prompt + " edit"); repository.draft(prompt) }
                runBlocking { repository.flush() }
                val reopened = DraftStore(context, storeName, alias).load()[key]
                assertEquals(request, reopened?.mutationId); assertEquals(prompt, reopened?.submittedText); assertEquals(prompt, reopened?.text)
                val owner = assertOwner(status())
                proofFile.writeText(JSONObject().put("pid", Process.myPid()).put("requestId", request).put("echo", echo).put("owner", owner).put("edited", edited).put("writes", writes.get()).put("inboundOverflow", false).put("relevantInbound", 0).toString())
                // Runner observes this file, checks PID ownership, then force-stops this live process.
                while (true) Thread.sleep(1_000)
            } else {
                val saved = requireNotNull(proof)
                val before = status(); val life = before.getJSONObject("nativeLifecycle")
                assertTrue(life.getBoolean("running") && life.getBoolean("cut")); assertEquals(1, life.getInt("promptRequests")); assertEquals(0, writes.get())
                assertOwner(before, saved.getJSONObject("owner"))
                action("allow-lifecycle")
                main { repository.setForeground(true); repository.setValidatedNetwork("process-recovery-fixture") }
                activate(RemoteSelection(host.routeId)); activate(RemoteSelection(host.routeId, selection.projectId)); activate(selection)
                val request = saved.getString("requestId")
                waitFor { repository.state.value.messages.any { val d = it["remoteDelivery"] as? JsonObject; it.optionalText("role") == "user" && d?.optionalText("requestId") == request && d.optionalText("delivery") == "prompt" } }
                val receipts = repository.state.value.messages.filter { val d = it["remoteDelivery"] as? JsonObject; it.optionalText("role") == "user" && d?.optionalText("requestId") == request && d.optionalText("delivery") == "prompt" }
                assertEquals(1, receipts.size); assertEquals(saved.getString("echo"), receipts.single().optionalText("id")); assertEquals(prompt, userMessageBody(receipts.single()))
                waitFor { repository.state.value.pendingSend == null && !repository.state.value.uncertain }
                assertEquals(if (edited) prompt else "", repository.state.value.draft)
                runBlocking { repository.flush() }
                val settledDraft = requireNotNull(DraftStore(context, storeName, alias).load()[key])
                assertNull(settledDraft.mutationId)
                assertEquals(if (edited) prompt else "", settledDraft.text)
                assertEquals(0, writes.get()); val after = status(); assertEquals(1, after.getJSONObject("nativeLifecycle").getInt("promptRequests")); assertTrue(after.getBoolean("nativeWorkerVerified"))
                assertOwner(after, saved.getJSONObject("owner"))
                File(context.filesDir, "process-recovery-$label-b.json").writeText(JSONObject().put("pid", Process.myPid()).put("requestId", request).put("owner", saved.getJSONObject("owner")).put("promptRequests", after.getJSONObject("nativeLifecycle").getInt("promptRequests")).put("writes", writes.get()).put("receiptId", receipts.single().optionalText("id")).put("edited", edited).toString())
                println("PROCESS_RECOVERY phase=B passed=true")
            }
        } finally { main { repository.setForeground(false); repository.disconnect() }; scope.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); NavigationTestActivity.contentFactory = { {} } }
    }
}

private class RecoverySettings : SettingsRepository {
    override val state = MutableStateFlow(RemoteSettings())
    override fun setTheme(theme: ThemePalette) { state.value = state.value.copy(theme = theme) }
    override fun setThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
    override fun setPushEnabled(enabled: Boolean) { state.value = state.value.copy(pushEnabled = enabled, pushOptedOut = !enabled) }
    override fun markNotificationPromptShown() { state.value = state.value.copy(notificationPromptShown = true) }
    override fun setThinkingDisplay(display: ThinkingDisplay) { state.value = state.value.copy(thinkingDisplay = display) }
    override fun setHideOfflineSessions(hide: Boolean) { state.value = state.value.copy(hideOfflineSessions = hide) }
    override fun setSwipeEndToStart(action: SwipeAction) { state.value = state.value.copy(swipeEndToStart = action) }
    override fun setSwipeStartToEnd(action: SwipeAction) { state.value = state.value.copy(swipeStartToEnd = action) }
    override fun setEnterSends(enabled: Boolean) { state.value = state.value.copy(enterSends = enabled) }
    override fun recordChatAction(action: ChatAction, now: Long) = Unit
    override fun chatActionUsage(): Map<ChatAction, ActionUsage> = emptyMap()
}
