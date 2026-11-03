package de.joinnoah.pi.remote

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import java.security.MessageDigest
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Opt-in on a freshly verified owned emulator only; uses the real application and service. */
class MainActivityFreshClientHandoffEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun freshClientContinuesHeldNativeSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productionEntryFixture") == "true")
        check(args.getString("productionEntryFreshOwned") == "true")
        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        check(ProductionEntryBootstrap.installed)
        val label = requireNotNull(args.getString("productionEntryLabel"))
        check(java.io.File(app.noBackupFilesDir, "production-entry-$label.marker").isFile)
        val fixturePath = requireNotNull(args.getString("productionEntryFixturePath"))
        require(fixturePath.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !fixturePath.contains(".."))
        val fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $fixturePath")
        ).use { it.readBytes().toString(Charsets.UTF_8) })
        val route = requireNotNull(args.getString("productionEntryRoute"))
        check(route == fixture.getString("routeId"))
        val relay = requireNotNull(args.getString("productionEntryRelay")).toHttpUrl()
        val session = fixture.getString("sessionId")
        val project = fixture.getString("projectId")
        fun endpoint(key: String): String = fixture.getString(key).also {
            val u = it.toHttpUrl()
            require(u.scheme == "https" && u.host == "10.0.2.2" && u.port == relay.port && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null)
            require(u.encodedPath == if (key == "statusUrl") "/fixture-status" else "/fixture-action")
        }
        val http = ProductionEntryBootstrap.http()
        fun status(): JSONObject = http.newCall(Request.Builder().url(endpoint("statusUrl")).build()).execute().use {
            check(it.isSuccessful); JSONObject(checkNotNull(it.body).string())
        }
        fun action(value: String): String = http.newCall(Request.Builder().url(endpoint("actionUrl"))
            .post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.code == 200) { "Fixture action returned ${it.code}" }; it.body?.string().orEmpty()
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun <T> onMain(block: () -> T): T { val result = AtomicReference<T>(); main { result.set(block()) }; return result.get() }
        fun waitFor(message: String, predicate: () -> Boolean) {
            try {
                compose.waitUntil(timeoutMillis = 90_000) { predicate() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Timed out: $message", error)
            }
        }
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun tag(tag: String) = compose.waitUntil(90_000) { exists(tag) }
        fun owner(s: JSONObject): JSONObject {
            assertTrue(s.getBoolean("nativeWorkerVerified"))
            val workflow = s.getJSONObject("workflow")
            assertTrue(workflow.getBoolean("ownerStable"))
            val initial = workflow.getJSONObject("initialOwner")
            val current = workflow.getJSONObject("currentOwner")
            listOf("pid", "sessionId", "runtimeId", "project").forEach { assertEquals(initial.get(it), current.get(it)) }
            return initial
        }
        fun receipt(marker: String): String {
            val messages = app.repository.state.value.messages
            val assistants = messages.filter { it.optionalText("role") == "assistant" }.mapNotNull { message ->
                val parts = message["parts"] as? JsonArray ?: return@mapNotNull null
                val texts = parts.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }.mapNotNull { it.contentOrNull }
                val count = texts.sumOf { it.split(marker).size - 1 }
                if (count == 0) null else message to count
            }
            check(assistants.size == 1 && assistants.single().second == 1) { "Expected one assistant entry containing marker once" }
            return requireNotNull(assistants.single().first.optionalText("id"))
        }
        fun saveProof(phase: String, proof: JSONObject) {
            val file = AtomicFile(File(app.filesDir, "fresh-client-$label-$phase.json"))
            val stream = file.startWrite()
            try { stream.write(proof.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
        val phase = requireNotNull(args.getString("freshClientPhase"))
        check(fixture.getBoolean("nativeFreshClientHandoff"))
        val repository = app.repository
        val lifecycle = fixture.getJSONObject("nativeLifecycle")
        val launch = if (phase == "prepare") LaunchIntents.session(app, route, session)
            else Intent(app, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use {
            if (phase == "prepare") {
                waitFor("session open") { repository.state.value.selection == RemoteSelection(route, project, session) && repository.state.value.connected && !repository.state.value.loading }
                tag("composerField")
                if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick()
                else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
                val workflow = fixture.getJSONObject("workflow")
                val name = workflow.getString("fileName")
                waitFor("workflow file") { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
                compose.onNodeWithTag(if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name").performScrollTo().performClick()
                tag("filesEdit"); compose.onNodeWithTag("filesEdit").performClick(); tag("filesEditorText")
                compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText")).performTextReplacement(workflow.getString("editedText"))
                compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
                waitFor("file saved") { status().getJSONObject("workflow").getBoolean("fileSaved") }
                repeat(6) {
                    val close = listOf("filesEditorBack", "filesClose", "filesBack", "changesClose").firstOrNull(::exists)
                    if (close != null) { compose.onNodeWithTag(close).performClick(); compose.waitForIdle() }
                }
                tag("composerField")
                main { repository.draft(workflow.getString("agentPrompt")); repository.prompt() }
                waitFor("workflow prepared") { status().getJSONObject("workflow").getBoolean("agentVerified") && repository.state.value.status == "idle" }
                main { repository.draft(lifecycle.getString("prompt")); repository.prompt() }
                waitFor("held session") { status().getJSONObject("nativeLifecycle").getBoolean("running") && runCatching { receipt(lifecycle.getString("partialMarker")) }.isSuccess }
                val prepared = status()
                val initialOwner = owner(prepared)
                val initialRequests = prepared.getJSONObject("nativeLifecycle").getInt("promptRequests")
                assertEquals(1, initialRequests)
                saveProof("prepare", JSONObject().put("appPid", Process.myPid())
                    .put("owner", initialOwner).put("assistantId", receipt(lifecycle.getString("partialMarker")))
                    .put("deviceId", fixture.getString("deviceId")).put("initialPromptRequests", initialRequests))
                // The orchestrator ends the app and clears only its owned emulator after this method.
            } else {
                check(phase == "fresh")
                check(args.getString("productionEntryMode") == "unpaired")
                val expectedAssistantId = requireNotNull(args.getString("productionEntryFreshExpectedAssistantId"))
                check(expectedAssistantId.isNotBlank() && expectedAssistantId.length <= 512)
                waitFor("empty encrypted pairing store") { repository.state.value.hostsLoaded && repository.state.value.hosts.isEmpty() }
                val qr = JSONObject(action("fresh-client-pair")).getString("qr")
                waitFor("pairing entry") { exists("firstRunPaste") || exists("pasteCodeButton") }
                compose.onNodeWithTag(if (exists("firstRunPaste")) "firstRunPaste" else "pasteCodeButton").performClick()
                tag("pairingCodeField")
                compose.onNodeWithTag("pairingCodeField").performTextInput(qr)
                compose.onNodeWithTag("confirmPairingCode").performClick()
                waitFor("public Android pairing") { repository.state.value.hosts.any { it.routeId == route } && repository.state.value.connected }
                check(File(app.noBackupFilesDir, "paired-hosts.enc").isFile)
                val loadedPairings = app.stores.pairingFile.load()
                val stored = loadedPairings.single { it.routeId == route }
                val visible = repository.state.value.hosts.single { it.routeId == route }
                check(stored.deviceId == visible.deviceId && stored.deviceId != fixture.getString("deviceId"))
                action("fresh-client-confirm")
                assertTrue(status().getJSONObject("freshClientHandoff").getBoolean("distinctDeviceConnected"))
                waitFor("project row") { exists("projectRow-$project") }
                compose.onNodeWithTag("projectRow-$project").performClick()
                waitFor("session list") { repository.state.value.sessions.any { it.optionalText("id") == session } }
                val title = repository.state.value.sessions.single { it.optionalText("id") == session }.optionalText("title").orEmpty()
                check(title.isNotBlank()) { "Session title unavailable for UI selection" }
                waitFor("session row") { compose.onAllNodes(hasContentDescription(title, substring = true)).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasContentDescription(title, substring = true)).assertCountEquals(1)
                compose.onNode(hasContentDescription(title, substring = true)).performClick()
                waitFor("same held session") { repository.state.value.selection == RemoteSelection(route, project, session) && repository.state.value.status == "running" && runCatching { receipt(lifecycle.getString("partialMarker")) }.isSuccess }
                assertEquals(expectedAssistantId, receipt(lifecycle.getString("partialMarker")))
                owner(status())
                assertEquals(1, status().getJSONObject("nativeLifecycle").getInt("promptRequests"))
                tag("stopRun"); compose.onNodeWithTag("stopRun").assertIsEnabled().performClick()
                waitFor("stopped") { status().getJSONObject("nativeLifecycle").getBoolean("stopped") && repository.state.value.status == "idle" }
                val followup = lifecycle.getString("followupPrompt")
                val progress = JSONArray()
                fun recordProgress(stage: String) {
                    val state = repository.state.value
                    val field = runCatching {
                        compose.onNodeWithTag("composerField").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
                    }.getOrNull()
                    val button = runCatching { compose.onNodeWithTag("primaryComposerAction").fetchSemanticsNode() }.getOrNull()
                    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    progress.put(JSONObject().put("stage", stage).put("status", state.status)
                        .put("connected", state.connected).put("loading", state.loading)
                        .put("stale", state.stale).put("sending", state.sending)
                        .put("uncertain", state.uncertain).put("pendingSend", state.pendingSend != null)
                        .put("repositoryDraftLength", state.draft.length).put("repositoryDraftSha256", digest(state.draft))
                        .put("repositoryDraftEqualsExpected", state.draft == followup)
                        .put("fieldPresent", field != null).put("fieldLength", field?.length ?: -1)
                        .put("fieldSha256", field?.let(::digest) ?: JSONObject.NULL)
                        .put("fieldEqualsExpected", field == followup)
                        .put("primaryActionPresent", button != null)
                        .put("primaryActionEnabled", button != null && !button.config.contains(SemanticsProperties.Disabled)))
                    saveProof("progress", JSONObject().put("appPid", Process.myPid()).put("observations", progress))
                }
                try {
                    tag("composerField")
                    recordProgress("before-input")
                    compose.onNodeWithTag("composerField").performTextInput(followup)
                    recordProgress("after-input")
                    val sendDescription = app.getString(R.string.remote_send)
                    waitFor("followup draft and send action") {
                        runCatching {
                            val field = compose.onNodeWithTag("composerField").fetchSemanticsNode()
                            val button = compose.onNodeWithTag("primaryComposerAction").fetchSemanticsNode()
                            repository.state.value.draft == followup &&
                                field.config[SemanticsProperties.EditableText].text == followup &&
                                hasContentDescription(sendDescription).matches(button) && isEnabled().matches(button)
                        }.getOrDefault(false)
                    }
                    compose.onNodeWithTag("composerField").assertTextEquals(followup)
                    compose.onNodeWithTag("primaryComposerAction").assert(hasContentDescription(sendDescription)).assertIsEnabled()
                    recordProgress("before-send")
                    compose.onNodeWithTag("primaryComposerAction").performClick()
                    recordProgress("after-send")
                    waitFor("followup") { status().getJSONObject("nativeLifecycle").getBoolean("followupVerified") && runCatching { receipt(lifecycle.getString("followupMarker")) }.isSuccess }
                    recordProgress("followup-verified")
                } catch (failure: Throwable) {
                    runCatching { recordProgress("failure") }
                    throw failure
                }
                val finalStatus = status()
                val finalOwner = owner(finalStatus)
                val result = finalStatus.getJSONObject("nativeLifecycle")
                assertEquals(1, result.getInt("promptRequests")); assertEquals(1, result.getInt("abortRequests")); assertEquals(1, result.getInt("followupRequests"))
                assertEquals(expectedAssistantId, receipt(lifecycle.getString("partialMarker")))
                saveProof("fresh", JSONObject().put("appPid", Process.myPid()).put("owner", finalOwner)
                    .put("assistantId", expectedAssistantId).put("deviceId", stored.deviceId)
                    .put("originalDeviceId", fixture.getString("deviceId")).put("pairingsLoaded", loadedPairings.size)
                    .put("encryptedPairingStoreDeviceId", stored.deviceId)
                    .put("hostConfirmedDistinctDevice", finalStatus.getJSONObject("freshClientHandoff").getBoolean("distinctDeviceConnected"))
                    .put("initialPromptRequests", result.getInt("promptRequests"))
                    .put("abortRequests", result.getInt("abortRequests")).put("followupRequests", result.getInt("followupRequests")))
            }
        }
    }
}
