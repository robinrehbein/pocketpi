package de.joinnoah.pi.remote

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Private, owned-emulator fixture. Phase A is deliberately killed by the external runner. */
class MainActivityProcessRecoveryEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recoveryThroughColdMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productionEntryProcessRecovery") == "true")
        check(args.getString("productionEntryFixture") == "true")
        val phase = requireNotNull(args.getString("productionEntryRecoveryPhase"))
        require(phase in setOf("A", "B"))
        check(args.getString("productionEntryMode") == if (phase == "A") "seed" else "restore")
        val label = requireNotNull(args.getString("productionEntryLabel"))
        val identity = requireNotNull(args.getString("productionEntryIdentity"))
        val edited = args.getString("productionEntryRecoveryEdited") == "true"
        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        val repository = app.repository
        check(ProductionEntryBootstrap.installed)
        val fixturePath = requireNotNull(args.getString("productionEntryFixturePath"))
        require(fixturePath.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !fixturePath.contains(".."))
        val fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $fixturePath")
        ).use { it.readBytes().toString(Charsets.UTF_8) })
        val route = fixture.getString("routeId")
        val project = fixture.getString("projectId")
        val session = fixture.getString("sessionId")
        val relay = fixture.getString("relayUrl").toHttpUrl()
        check(route == args.getString("productionEntryRoute") && project == args.getString("productionEntryProjectId"))
        check(session == args.getString("productionEntrySessionId") && fixture.getString("deviceId") == args.getString("productionEntryDeviceId"))
        check(fixture.getString("relayUrl") == args.getString("productionEntryRelay"))
        check(fixture.getString("deviceSecret") == args.getString("productionEntryDeviceSecret"))
        val caPath = fixture.getString("caCertPath")
        require(caPath.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !caPath.contains(".."))
        val caBytes = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $caPath")).use { it.readBytes() }
        check(caBytes.contentEquals(android.util.Base64.decode(requireNotNull(args.getString("productionEntryCaBase64")), android.util.Base64.DEFAULT)))
        check(fixture.getBoolean("nativeWorker") && fixture.getJSONObject("nativeLifecycle").getBoolean("lostAck"))
        fun endpoint(key: String): String = fixture.getString(key).also {
            val u = it.toHttpUrl()
            require(u.scheme == "https" && u.host == "10.0.2.2" && u.port == relay.port && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null)
            require(u.encodedPath == if (key == "statusUrl") "/fixture-status" else "/fixture-action")
        }
        val http = ProductionEntryBootstrap.http()
        fun status(): JSONObject = http.newCall(Request.Builder().url(endpoint("statusUrl")).build()).execute().use {
            check(it.isSuccessful); JSONObject(checkNotNull(it.body).string())
        }
        fun action(value: String) = http.newCall(Request.Builder().url(endpoint("actionUrl"))
            .post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.code == 200)
        }
        fun owner(full: JSONObject, expected: JSONObject? = null): JSONObject {
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
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun <T> onMain(block: () -> T): T { val value = AtomicReference<T>(); main { value.set(block()) }; return value.get() }
        fun waitFor(message: String, predicate: () -> Boolean) {
            try { compose.waitUntil(90_000) { predicate() } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Timed out: $message", e) }
        }
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun tag(tag: String) = waitFor(tag) { exists(tag) }
        fun writeProof(file: File, value: JSONObject) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Throwable) { atomic.failWrite(stream); throw e }
        }
        val aFile = File(app.filesDir, "production-entry-recovery-$label-a.json")
        val bFile = File(app.filesDir, "production-entry-recovery-$label-b.json")
        val key = DraftKey(route, session)
        val prompt = fixture.getJSONObject("nativeLifecycle").getString("prompt")
        val launch = if (phase == "A") LaunchIntents.session(app, route, session) else LaunchIntents.app(app)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (phase == "A") check(!aFile.exists()) else check(aFile.isFile && !bFile.exists())
        val saved = if (phase == "B") JSONObject(aFile.readText()) else null
        if (saved != null) {
            assertNotEquals(saved.getInt("pid"), Process.myPid())
            assertEquals(identity, saved.getString("identity")); assertEquals(edited, saved.getBoolean("edited"))
            val before = status(); val life = before.getJSONObject("nativeLifecycle")
            assertTrue(life.getBoolean("cut")); assertFalse(before.getBoolean("deviceOnline"))
            assertEquals(1, life.getInt("promptRequests")); owner(before, saved.getJSONObject("owner"))
        }
        val scenario = ActivityScenario.launch<MainActivity>(launch)
        try {
            val actual = onMain { Intent(ProductionEntryBootstrap.activityEvents.last { it.kind == "resumed" }.activity.intent) }
            check(actual.component?.className == LaunchIntents.ALIAS)
            val createdWithState = onMain { ProductionEntryBootstrap.activityEvents.first { it.kind == "created" }.hadSavedState }
            if (phase == "A") check(LaunchIntents.target(actual)?.sessionId == session)
            else check(LaunchIntents.target(actual) == null && actual.data == null && actual.action == Intent.ACTION_MAIN)
            waitFor("pairing loaded") { repository.state.value.hostsLoaded }
            check(!repository.state.value.hostsUnreadable && repository.state.value.hosts.size == 1)
            check(repository.state.value.hosts.single().routeId == route && repository.state.value.hosts.single().deviceId == fixture.getString("deviceId"))
            if (phase == "A") {
                waitFor("session") { val s = repository.state.value; s.selection == RemoteSelection(route, project, session) && s.connected && !s.loading }
                tag("composerField")
                if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick()
                else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
                val workflow = fixture.getJSONObject("workflow")
                val name = workflow.getString("fileName")
                waitFor("file") { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
                compose.onNodeWithTag(if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name").performScrollTo().performClick()
                tag("filesEdit"); compose.onNodeWithTag("filesEdit").performClick(); tag("filesEditorText")
                compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText")).performTextReplacement(workflow.getString("editedText"))
                compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
                waitFor("file saved") { status().getJSONObject("workflow").getBoolean("fileSaved") }
                repeat(6) { listOf("filesEditorBack", "filesClose", "filesBack", "changesClose").firstOrNull(::exists)?.let { compose.onNodeWithTag(it).performClick(); compose.waitForIdle() } }
                tag("composerField")
                compose.onNodeWithTag("composerField").performTextReplacement(workflow.getString("agentPrompt"))
                compose.onNodeWithTag("primaryComposerAction").performClick()
                waitFor("workflow") { status().getJSONObject("workflow").getBoolean("agentVerified") && repository.state.value.status == "idle" }
                val beforeInbound = ProductionEntryBootstrap.inboundMessages().size
                val beforeWrites = ProductionEntryBootstrap.writeAttempts().size
                action("arm-lifecycle-lost-ack")
                compose.onNodeWithTag("composerField").performTextReplacement(prompt)
                compose.onNodeWithTag("primaryComposerAction").performClick()
                var request = ""
                waitFor("pending prompt") { repository.state.value.pendingSend?.let { if (it.text == prompt) { request = it.mutationId; true } else false } == true }
                waitFor("withheld receipt") { val l = status().getJSONObject("nativeLifecycle"); val lost = l.getJSONObject("lostAck"); l.getBoolean("running") && lost.getBoolean("accepted") && lost.getBoolean("ackAccepted") && lost.getInt("ackQueued") == 1 && lost.getInt("echoQueued") >= 1 }
                val lost = status().getJSONObject("nativeLifecycle").getJSONObject("lostAck")
                assertEquals(request, lost.getString("requestId")); assertEquals(0, lost.getInt("ackSent")); assertEquals(0, lost.getInt("echoSent"))
                val echo = lost.getString("echoSourceId")
                val delivery = lost.getJSONObject("echoRemoteDelivery")
                assertEquals(request, delivery.getString("requestId")); assertEquals("prompt", delivery.getString("delivery"))
                action("cut-lifecycle")
                waitFor("uncertain prompt") { val s = repository.state.value; !s.connected && s.uncertain && s.pendingSend?.mutationId == request && s.pendingSend?.state == PendingSendState.NOT_CONFIRMED && s.draft == prompt }
                if (edited) {
                    compose.onNodeWithTag("composerField").performTextReplacement("$prompt edit")
                    waitFor("edited composer propagated") { repository.state.value.draft == "$prompt edit" }
                    compose.onNodeWithTag("composerField").performTextReplacement(prompt)
                    waitFor("restored composer propagated") { repository.state.value.draft == prompt }
                }
                runBlocking { repository.flush() }
                val stored = requireNotNull(app.stores.drafts.load()[key])
                assertEquals(request, stored.mutationId); assertEquals(prompt, stored.submittedText); assertEquals(prompt, stored.text)
                if (edited) assertEquals("edited", stored.promptTextState)
                check(UiDevice.getInstance(instrumentation).pressHome())
                waitFor("app stopped and disconnected") { onMain { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } && !repository.state.value.connected && LiveRun.service == null }
                assertFalse(ProductionEntryBootstrap.observerOverflow)
                val incoming = ProductionEntryBootstrap.inboundMessages().drop(beforeInbound)
                assertFalse(incoming.any { it.toString().contains(request) || it.toString().contains(echo) || it.toString().contains(prompt) })
                assertEquals(1, ProductionEntryBootstrap.writeAttempts().drop(beforeWrites).count { it.optionalText("type") == "session.prompt" && it.optionalText("text") == prompt })
                val final = status(); val life = final.getJSONObject("nativeLifecycle")
                assertEquals(1, life.getInt("promptRequests")); assertEquals(0, life.getJSONObject("lostAck").getInt("ackSent")); assertEquals(0, life.getJSONObject("lostAck").getInt("echoSent"))
                val pairing = File(app.noBackupFilesDir, "paired-hosts.enc")
                check(pairing.isFile && listOf("paired-hosts.enc.bak", "paired-hosts.enc.new").none { File(app.noBackupFilesDir, it).exists() })
                val sessionTitle = repository.state.value.sessions.firstOrNull { it.optionalText("id") == session }?.optionalText("title")
                    ?: error("Original session title unavailable")
                val proof = JSONObject().put("sessionTitle", sessionTitle).put("activityHadSavedState", createdWithState).put("pid", Process.myPid()).put("identity", identity).put("pairingSha256", ProductionEntryBootstrap.sha256(pairing.readBytes()))
                    .put("edited", edited).put("requestId", request).put("echo", echo).put("owner", owner(final))
                    .put("homeStopped", true).put("connected", false).put("observerOverflow", false).put("relevantInbound", 0)
                writeProof(aFile, proof)
                while (true) Thread.sleep(1_000)
            } else {
                val proof = requireNotNull(saved)
                val request = proof.getString("requestId")
                val stored = requireNotNull(app.stores.drafts.load()[key])
                assertEquals(request, stored.mutationId); assertEquals(prompt, stored.submittedText); assertEquals(prompt, stored.text)
                if (edited) assertEquals("edited", stored.promptTextState)
                // A normal launcher must return to the last Mac's project list, not the old chat.
                tag("projectsMacTitle")
                check(repository.state.value.selection.sessionId == null)
                tag("projectRow-$project")
                compose.onNodeWithTag("projectRow-$project").performClick()
                // The saved session list is used while the fixture remains cut.
                waitFor("saved session row") { repository.state.value.sessions.any { it.optionalText("id") == session } }
                val row = repository.state.value.sessions.first { it.optionalText("id") == session }
                val title = row.optionalText("title") ?: error("Session title unavailable")
                assertEquals(proof.getString("sessionTitle"), title)
                val displayTitle = title.ifBlank { app.getString(R.string.remote_new_session) }
                assertEquals(1, compose.onAllNodesWithContentDescription(displayTitle, substring = true).fetchSemanticsNodes().size)
                assertTrue(status().getJSONObject("nativeLifecycle").getBoolean("cut"))
                assertEquals(0, ProductionEntryBootstrap.writeAttempts().count { it.optionalText("type") == "session.prompt" && it.optionalText("text") == prompt })
                // A user-opened chat requires live host validation, including the historical-fork
                // guard. Verify the on-disk journal while cut, then open the row after reconnect.
                action("allow-lifecycle")
                if (exists("reconnectNow")) compose.onNodeWithTag("reconnectNow").performClick()
                waitFor("host reconnected") { repository.state.value.connected }
                waitFor("session row after reconnect") {
                    compose.onAllNodesWithContentDescription(displayTitle, substring = true).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription(displayTitle, substring = true).performClick()
                waitFor("validated chat") { repository.state.value.selection.sessionId == session && exists("composerField") }
                waitFor("source receipt") { repository.state.value.messages.any { val d = it["remoteDelivery"] as? JsonObject; it.optionalText("role") == "user" && d?.optionalText("requestId") == request && d.optionalText("delivery") == "prompt" } }
                val receipts = repository.state.value.messages.filter { val d = it["remoteDelivery"] as? JsonObject; it.optionalText("role") == "user" && d?.optionalText("requestId") == request && d.optionalText("delivery") == "prompt" }
                assertEquals(1, receipts.size); assertEquals(proof.getString("echo"), receipts.single().optionalText("id")); assertEquals(prompt, userMessageBody(receipts.single()))
                waitFor("journal settled") { repository.state.value.pendingSend == null && !repository.state.value.uncertain }
                assertEquals(if (edited) prompt else "", repository.state.value.draft)
                runBlocking { repository.flush() }
                val settled = requireNotNull(app.stores.drafts.load()[key])
                assertNull(settled.mutationId); assertEquals(if (edited) prompt else "", settled.text)
                assertFalse(ProductionEntryBootstrap.observerOverflow)
                assertTrue(ProductionEntryBootstrap.inboundMessages().any { it.toString().contains(request) && it.toString().contains(proof.getString("echo")) })
                assertEquals(0, ProductionEntryBootstrap.writeAttempts().count { it.optionalText("type") == "session.prompt" && it.optionalText("text") == prompt })
                val final = status(); assertEquals(1, final.getJSONObject("nativeLifecycle").getInt("promptRequests"))
                val finalOwner = owner(final, proof.getJSONObject("owner"))
                writeProof(bFile, JSONObject().put("pid", Process.myPid()).put("identity", identity).put("requestId", request)
                    .put("journalVerifiedWhileCut", true).put("receiptId", receipts.single().optionalText("id")).put("owner", finalOwner).put("activityHadSavedState", createdWithState).put("promptRequests", 1).put("writes", 0).put("edited", edited).put("completed", true))
            }
        } finally { scenario.close() }
    }
}
