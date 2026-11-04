package de.joinnoah.pi.remote

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
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
class MainActivityLiveRunEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun liveRunSurvivesBackgroundAndHandsBackToMainActivity() {
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
        fun action(value: String) = http.newCall(Request.Builder().url(endpoint("actionUrl"))
            .post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.code == 200) { "Fixture action returned ${it.code}" }
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
        val proof = JSONObject().put("pid", Process.myPid()).put("apiLevel", Build.VERSION.SDK_INT)
            .put("bootstrapInstalled", ProductionEntryBootstrap.installed)
        val proofFile = AtomicFile(File(app.filesDir, "production-entry-callback-proof-$label.json"))
        val milestones = JSONArray()
        fun saveProof(stage: String) {
            proof.put("stage", stage).put("elapsed", SystemClock.elapsedRealtime())
            val events = onMain { ProductionEntryBootstrap.activityEvents.toList() }
            val lifecycles = onMain {
                Triple(
                    ProductionEntryBootstrap.activityEvents.lastOrNull()?.activity?.lifecycle?.currentState?.name,
                    ProcessLifecycleOwner.get().lifecycle.currentState.name,
                    LiveRun.service != null,
                )
            }
            proof.put("activityLifecycle", lifecycles.first)
            val state = app.repository.state.value
            milestones.put(JSONObject().put("stage", stage).put("elapsed", SystemClock.elapsedRealtime())
                .put("activityLifecycle", lifecycles.first ?: JSONObject.NULL)
                .put("processLifecycle", lifecycles.second).put("servicePresent", lifecycles.third)
                .put("connected", state.connected)
                .put("assistantId", proof.opt("assistantId") ?: JSONObject.NULL)
                .put("markerCount", proof.opt("markerCount") ?: JSONObject.NULL))
            proof.put("milestoneHistory", milestones)
            proof.put("hostsLoaded", state.hostsLoaded).put("hostsUnreadable", state.hostsUnreadable)
                .put("hostCount", state.hosts.size).put("selectionRoute", state.selection.routeId)
                .put("selectionProject", state.selection.projectId).put("selectionSession", state.selection.sessionId)
                .put("connected", state.connected).put("loading", state.loading).put("errorResource", state.error)
                .put("factoryRequests", ProductionEntryBootstrap.factoryRequests.get())
                .put("socketOpens", ProductionEntryBootstrap.socketOpens.get())
                .put("socketFailures", ProductionEntryBootstrap.socketFailures.get())
                .put("lastFailureType", ProductionEntryBootstrap.lastFailureType)
                .put("lastEndpointAllowed", ProductionEntryBootstrap.lastEndpointAllowed)
            proof.put("activityEvents", JSONArray().apply {
                events.forEach { put(JSONObject().put("event", it.kind).put("elapsed", it.elapsed)
                    .put("activityIdentity", System.identityHashCode(it.activity))) }
            })
            val stream = proofFile.startWrite()
            try { stream.write(proof.toString().toByteArray(Charsets.UTF_8)); proofFile.finishWrite(stream) }
            catch (error: Throwable) { proofFile.failWrite(stream); throw error }
        }
        val settings = LiveRunSettings(app)
        val originalEnabled = settings.enabled.value
        var scenario: ActivityScenario<RemoteActivity>? = null
        var completed = false
        try {
            saveProof("bootstrap")
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
                )).use { it.readBytes() }
                check(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
            main { settings.setEnabled(true) }
            val launch = LaunchIntents.session(app, route, session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            scenario = ActivityScenario.launch(launch)
            val repository = app.repository
            val actualIntent = onMain { Intent(ProductionEntryBootstrap.activityEvents.last { it.kind == "resumed" }.activity.intent) }
            val target = LaunchIntents.target(actualIntent)
            proof.put("intendedComponent", launch.component?.className)
                .put("actualComponent", actualIntent.component?.className)
                .put("actualFlags", actualIntent.flags)
                .put("targetRecognized", target != null)
                .put("targetRoute", target?.routeId).put("targetSession", target?.sessionId)
            saveProof("launch")
            check(target?.routeId == route && target.sessionId == session) { "MainActivity launch target was not recognized" }
            val hostsDeadline = SystemClock.elapsedRealtime() + 10_000
            while (!repository.state.value.hostsLoaded && SystemClock.elapsedRealtime() < hostsDeadline) Thread.sleep(100)
            val hostState = repository.state.value
            proof.put("expectedHostPresent", hostState.hosts.any { it.routeId == route })
                .put("appLocked", app.appLock.locked.value)
                .put("boundaryProbeElapsed", SystemClock.elapsedRealtime())
            saveProof("host-loaded")
            check(hostState.hostsLoaded && hostState.hosts.any { it.routeId == route }) { "Expected fixture host was not initialized" }
            waitFor("real session navigation") {
                val s = repository.state.value
                s.selection == RemoteSelection(route, project, session) && s.session != null && s.connected && !s.loading
            }
            tag("composerField")
            val originalActivity = onMain {
                val events = ProductionEntryBootstrap.activityEvents
                check(events.any { it.kind == "created" })
                events.last { it.kind == "resumed" }.activity
            }
            proof.put("activityIdentity", System.identityHashCode(originalActivity))
            saveProof("created")
            // The native lifecycle provider only accepts its prompt after the existing workflow is prepared.
            if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick()
            else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
            val workflow = fixture.getJSONObject("workflow")
            val name = workflow.getString("fileName")
            compose.waitUntil(90_000) { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
            compose.onNodeWithTag(if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name").performScrollTo().performClick()
            tag("filesEdit"); compose.onNodeWithTag("filesEdit").performClick(); tag("filesEditorText")
            compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText")).performTextReplacement(workflow.getString("editedText"))
            compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
            waitFor("fixture file saved") { status().getJSONObject("workflow").getBoolean("fileSaved") }
            repeat(6) {
                val close = listOf("filesEditorBack", "filesClose", "filesBack", "changesClose").firstOrNull(::exists)
                if (close != null) { compose.onNodeWithTag(close).performClick(); compose.waitForIdle() }
            }
            tag("composerField")
            main { repository.draft(workflow.getString("agentPrompt")); repository.prompt() }
            waitFor("native workflow prepared") { status().getJSONObject("workflow").getBoolean("agentVerified") && repository.state.value.status == "idle" }
            val lifecycle = fixture.getJSONObject("nativeLifecycle")
            main { repository.draft(lifecycle.getString("prompt")); repository.prompt() }
            waitFor("running native session") { repository.state.value.status == "running" && status().getJSONObject("nativeLifecycle").getBoolean("running") }
            val initial = owner(status())
            assertEquals(1, status().getJSONObject("nativeLifecycle").getInt("promptRequests"))
            waitFor("initial assistant receipt") { runCatching { receipt(lifecycle.getString("partialMarker")) }.isSuccess }
            val initialReceipt = receipt(lifecycle.getString("partialMarker"))
            proof.put("assistantId", initialReceipt).put("ownerPid", initial.get("pid"))
                .put("ownerSessionId", initial.get("sessionId")).put("ownerRuntimeId", initial.get("runtimeId"))
                .put("promptRequests", 1)
            val eventOffset = onMain { ProductionEntryBootstrap.activityEvents.size }
            val beforeHome = android.os.SystemClock.elapsedRealtime()
            check(UiDevice.getInstance(instrumentation).pressHome())
            waitFor("process background and real foreground service") {
                onMain { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && LiveRun.service != null }
            }
            waitFor("actual MainActivity pause and stop") { onMain {
                val events = ProductionEntryBootstrap.activityEvents.drop(eventOffset)
                events.any { it.activity === originalActivity && it.kind == "paused" && it.elapsed >= beforeHome } &&
                    events.any { it.activity === originalActivity && it.kind == "stopped" && it.elapsed >= beforeHome }
            } }
            val manager = app.getSystemService(NotificationManager::class.java)
            waitFor("live run foreground notification") { manager.activeNotifications.any { it.id == RemoteNotifications.LIVE_RUN_ID } }
            proof.put("notificationId", RemoteNotifications.LIVE_RUN_ID).put("notificationPresent", true)
                .put("processStopped", true)
            saveProof("background")
            action("emit-background-delta")
            waitFor("provider emitted background delta") { status().getJSONObject("nativeLifecycle").getJSONObject("backgroundDelta").let { it.getBoolean("requested") && it.getBoolean("emitted") } }
            val marker = lifecycle.getString("backgroundDeltaMarker")
            waitFor("real repository received background delta") { runCatching { receipt(marker) }.isSuccess }
            val receiptId = receipt(marker)
            assertEquals(initialReceipt, receiptId)
            val inBackground = owner(status()); listOf("pid", "sessionId", "runtimeId", "project").forEach { assertEquals(initial.get(it), inBackground.get(it)) }
            assertTrue(onMain {
                !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !originalActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            })
            proof.put("markerCount", 1).put("deltaAssistantId", receiptId).put("ownerStableInBackground", true)
            saveProof("delta")
            app.startActivity(LaunchIntents.session(app, route, session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("foreground handoff") { onMain { LiveRun.service == null && LiveRun.lastStop == LiveRunStop.FOREGROUND } }
            waitFor("same session after return") { repository.state.value.selection == RemoteSelection(route, project, session) && runCatching { receipt(marker) }.getOrNull() == receiptId }
            assertEquals(receiptId, receipt(marker))
            assertEquals(1, compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().size)
            assertTrue(onMain { ProductionEntryBootstrap.activityEvents.drop(eventOffset).any { it.activity === originalActivity && it.kind == "resumed" && it.elapsed >= beforeHome } })
            val after = status(); val resumed = owner(after)
            listOf("pid", "sessionId", "runtimeId", "project").forEach { assertEquals(initial.get(it), resumed.get(it)) }
            assertEquals(1, after.getJSONObject("nativeLifecycle").getInt("promptRequests"))
            proof.put("renderedMarkerNodes", 1).put("sameAssistantId", true).put("ownerStableOnReturn", true)
                .put("promptRequestsAfterReturn", 1).put("serviceStop", "FOREGROUND")
            saveProof("return")
            main { settings.setEnabled(false) }
            check(UiDevice.getInstance(instrumentation).pressHome())
            waitFor("disabled setting background") { onMain { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } }
            repeat(20) {
                assertNull(onMain { LiveRun.service })
                assertFalse(onMain { LiveRun.wanted })
                Thread.sleep(100)
            }
            proof.put("disabledServiceSamples", 20).put("disabledServiceObservedAbsent", true)
            saveProof("settingoff")
            completed = true
        } finally {
            // Preserve the last completed milestone even when the test fails.
            proof.put("completed", completed)
            runCatching { saveProof("cleanup-start") }
            // Do not restart the service during activity teardown; restore the original setting afterwards.
            main { settings.setEnabled(false); LiveRun.onResume() }
            scenario?.close()
            main { settings.setEnabled(originalEnabled); app.repository.disconnect() }
            proof.put("cleanupCompleted", true)
            saveProof("cleanup")
        }
    }
}
