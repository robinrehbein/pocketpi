package de.joinnoah.pi.remote

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Opt-in owned-emulator test; an external runner force-stops phase A before phase B. */
class MainActivityFileDraftRecoveryEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recoveryThroughColdMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productionEntryFileDraftRecovery") == "true")
        check(args.getString("productionEntryFixture") == "true")
        val phase = requireNotNull(args.getString("productionEntryRecoveryPhase"))
        require(phase in setOf("A", "B"))
        check(args.getString("productionEntryMode") == if (phase == "A") "seed" else "restore")
        val label = requireNotNull(args.getString("productionEntryLabel"))
        val identity = requireNotNull(args.getString("productionEntryIdentity"))
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
        check(fixture.getString("relayUrl") == args.getString("productionEntryRelay") && fixture.getString("deviceSecret") == args.getString("productionEntryDeviceSecret"))
        val caPath = fixture.getString("caCertPath")
        require(caPath.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !caPath.contains(".."))
        val caBytes = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $caPath")).use { it.readBytes() }
        check(caBytes.contentEquals(android.util.Base64.decode(requireNotNull(args.getString("productionEntryCaBase64")), android.util.Base64.DEFAULT)))
        check(fixture.getBoolean("nativeWorker"))
        val file = fixture.getJSONObject("fileDraftRecovery")
        val path = file.getString("path")
        require('/' !in path && path.isNotBlank()) // The fixture file is a root entry.
        val original = file.getString("original")
        val submitted = file.getString("submittedText")
        val draft = file.getString("draftText")
        check(setOf(original, submitted, draft).size == 3)
        fun endpoint(key: String): String = fixture.getString(key).also {
            val u = it.toHttpUrl()
            require(u.scheme == "https" && u.host == "10.0.2.2" && u.port == relay.port && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null)
            require(u.encodedPath == if (key == "statusUrl") "/fixture-status" else "/fixture-action")
        }
        val http = ProductionEntryBootstrap.http()
        fun status(): JSONObject = http.newCall(Request.Builder().url(endpoint("statusUrl")).build()).execute().use {
            check(it.isSuccessful); JSONObject(checkNotNull(it.body).string())
        }
        fun fileStatus() = status().getJSONObject("fileDraftRecovery").also { assertTrue(it.getBoolean("nativeWorkerVerified")) }
        fun events(s: JSONObject): List<JSONObject> {
            val array = s.getJSONArray("events")
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        fun action(value: String) = http.newCall(Request.Builder().url(endpoint("actionUrl"))
            .post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.code == 200) }
        fun <T> onMain(block: () -> T): T {
            val value = AtomicReference<T>(); instrumentation.runOnMainSync { value.set(block()) }; return value.get()
        }
        fun waitFor(message: String, predicate: () -> Boolean) {
            try { compose.waitUntil(90_000) { predicate() } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Timed out: $message", e) }
        }
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun tag(tag: String) = waitFor(tag) { exists(tag) }
        fun writeProof(file: File, value: JSONObject) {
            val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Throwable) { atomic.failWrite(stream); throw e }
        }
        fun screenshot(name: String): String {
            val target = File(app.filesDir, "production-entry-file-recovery-$label-$name.png")
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val colors = mutableSetOf<Int>()
                for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 50))
                    for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 50)) colors.add(bitmap.getPixel(x, y))
                check(colors.size > 3) { "Screenshot is blank" }
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            finally { bitmap.recycle() }
            return target.name
        }
        fun openFile() {
            tag("composerField")
            if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick()
            else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
            waitFor("fixture file") { exists("filesEntry:$path") || exists("filesSideEntry:$path") }
            compose.onNodeWithTag(if (exists("filesEntry:$path")) "filesEntry:$path" else "filesSideEntry:$path").performScrollTo().performClick()
            tag("filesEdit"); compose.onNodeWithTag("filesEdit").performClick()
        }
        val aFile = File(app.filesDir, "production-entry-file-recovery-$label-a.json")
        val bFile = File(app.filesDir, "production-entry-file-recovery-$label-b.json")
        val key = FileDraftKey(route, session, path)
        val launch = if (phase == "A") LaunchIntents.session(app, route, session) else LaunchIntents.app(app)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (phase == "A") check(!aFile.exists()) else check(aFile.isFile && !bFile.exists())
        val proof = if (phase == "B") JSONObject(aFile.readText()) else null
        var readsBeforeOpen = 0
        if (proof != null) {
            assertNotEquals(proof.getInt("pid"), Process.myPid())
            assertEquals(identity, proof.getString("identity"))
            val stored = requireNotNull(app.stores.fileDrafts.load()[key])
            assertEquals(original, stored.original); assertEquals(draft, stored.text)
            assertEquals(proof.getString("requestId"), stored.pending.single().requestId)
            assertEquals(submitted, stored.pending.single().content)
            val before = fileStatus()
            assertEquals(submitted, before.getString("content")); assertEquals(1, before.getInt("writeCount"))
            assertEquals(proof.getJSONObject("worker").toString(), before.getJSONObject("worker").toString())
            readsBeforeOpen = events(before).count { it.getString("event") == "read.request" }
        }
        val scenario = ActivityScenario.launch<MainActivity>(launch)
        try {
            val actual = onMain { Intent(ProductionEntryBootstrap.activityEvents.last { it.kind == "resumed" }.activity.intent) }
            check(actual.component?.className == LaunchIntents.ALIAS)
            val savedState = onMain { ProductionEntryBootstrap.activityEvents.first { it.kind == "created" }.hadSavedState }
            if (phase == "A") check(LaunchIntents.target(actual)?.sessionId == session)
            else check(LaunchIntents.target(actual) == null && actual.data == null && actual.action == Intent.ACTION_MAIN)
            waitFor("pairing loaded") { repository.state.value.hostsLoaded }
            check(!repository.state.value.hostsUnreadable && repository.state.value.hosts.single().routeId == route)
            if (phase == "B") {
                tag("projectsMacTitle"); tag("projectRow-$project")
                compose.onNodeWithTag("projectRow-$project").performClick()
                waitFor("saved session") { repository.state.value.sessions.any { it.optionalText("id") == session } }
                val title = repository.state.value.sessions.first { it.optionalText("id") == session }.optionalText("title") ?: error("Session title unavailable")
                val displayTitle = title.ifBlank { app.getString(R.string.remote_new_session) }
                waitFor("session row") { compose.onAllNodesWithContentDescription(displayTitle, substring = true).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithContentDescription(displayTitle, substring = true).performClick()
            }
            waitFor("connected session") { val s = repository.state.value; s.selection == RemoteSelection(route, project, session) && s.connected && !s.loading }
            openFile()
            if (phase == "A") {
                tag("filesEditorText")
                compose.onNodeWithTag("filesEditorText").assertTextEquals(original).performTextReplacement(submitted)
                // Confirm the actual editor snapshot reached encrypted storage before arming the lost ACK.
                waitFor("submitted snapshot persisted") { runCatching { app.stores.fileDrafts.load()[key]?.text == submitted }.getOrDefault(false) }
                action("arm-file-write-lost-ack")
                compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
                waitFor("host applied save and dropped acknowledgement") {
                    val s = fileStatus(); s.getInt("writeCount") == 1 && s.getString("content") == submitted && s.getBoolean("ackDropped")
                }
                val first = fileStatus()
                assertEquals(1, first.getJSONArray("requestIds").length())
                val request = first.getJSONArray("requestIds").getString(0)
                assertEquals(request, first.getString("ackRequestId"))
                waitFor("pending request persisted") { runCatching { app.stores.fileDrafts.load()[key]?.pending?.singleOrNull()?.let { it.requestId == request && it.content == submitted } == true }.getOrDefault(false) }
                compose.onNodeWithTag("filesEditorText").performTextReplacement(draft)
                waitFor("latest editor snapshot persisted") { runCatching { app.stores.fileDrafts.load()[key]?.let { it.original == original && it.text == draft && it.pending.singleOrNull()?.requestId == request } == true }.getOrDefault(false) }
                val stored = requireNotNull(app.stores.fileDrafts.load()[key])
                assertEquals(stored.baseVersion, stored.pending.single().baseVersion)
                assertEquals(1, fileStatus().getInt("writeCount"))
                val pairing = File(app.noBackupFilesDir, "paired-hosts.enc")
                check(pairing.isFile && listOf("paired-hosts.enc.bak", "paired-hosts.enc.new").none { File(app.noBackupFilesDir, it).exists() })
                check(!ProductionEntryBootstrap.observerOverflow)
                writeProof(aFile, JSONObject().put("pid", Process.myPid()).put("identity", identity).put("pairingSha256", ProductionEntryBootstrap.sha256(pairing.readBytes()))
                    .put("path", path).put("baseVersion", stored.baseVersion).put("requestId", request).put("worker", first.getJSONObject("worker"))
                    .put("initialHostWriteCount", 1).put("activityHadSavedState", savedState).put("journalPersisted", true)
                    .put("screenshot", screenshot("a-editor")))
                while (true) Thread.sleep(1_000)
            } else {
                val request = requireNotNull(proof).getString("requestId")
                tag("filesEditorRestoreDraft")
                compose.onNodeWithTag("filesEditorText").assertDoesNotExist()
                compose.onNodeWithTag("filesEditorRecoveryHost").assertTextEquals(submitted.take(2_000))
                compose.onNodeWithTag("filesEditorRecoveryDraft").assertTextEquals(draft.take(2_000))
                compose.onNodeWithTag("filesEditorRecoveryPending").assertTextEquals(submitted.take(2_000))
                val recoveryScreenshot = screenshot("b-recovery")
                val recoveryAudit = events(fileStatus())
                val firstWriteResult = recoveryAudit.single { it.getString("event") == "write.result" && it.getString("id") == request }.getJSONObject("result")
                val freshReads = recoveryAudit.filter { it.getString("event") == "read.request" }.drop(readsBeforeOpen)
                assertTrue(freshReads.any { read ->
                    recoveryAudit.any { result ->
                        result.getString("event") == "read.result" && result.getString("id") == read.getString("id") &&
                            result.getJSONObject("result").let { it.getString("content") == submitted && it.getString("version") == firstWriteResult.getString("version") }
                    }
                })
                assertEquals(1, fileStatus().getInt("writeCount"))
                assertEquals(1, fileStatus().getJSONArray("requestIds").length())
                assertFalse(ProductionEntryBootstrap.writeAttempts().any { it.optionalText("type") == "session.files.write" })
                compose.onNodeWithTag("filesEditorRestoreDraft").performClick()
                tag("filesEditorText")
                compose.onNodeWithTag("filesEditorText").assertTextEquals(draft)
                waitFor("restored conflict guard") { repository.state.value.files?.editor?.let { it.conflict && it.unconfirmed?.requestId == request && it.latestText == draft } == true }
                tag("filesEditorConflictKeep")
                compose.onNodeWithTag("filesEditorConflictKeep").performClick()
                assertEquals(1, fileStatus().getInt("writeCount"))
                assertEquals(1, fileStatus().getJSONArray("requestIds").length())
                compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
                waitFor("explicit draft save") { fileStatus().let { it.getInt("writeCount") == 2 && it.getString("content") == draft } }
                waitFor("editor save settled") { repository.state.value.files?.editor?.let { !it.saving && it.original == draft && it.saveFailure == null } == true }
                // The first ACK never reached the app. A later successful save must retain that
                // unresolved receipt, even though the Host audit proves the earlier mutation.
                waitFor("final save durable with unresolved receipt retained") {
                    runCatching {
                        app.stores.fileDrafts.load()[key]?.let { stored ->
                            stored.original == draft && stored.text == draft && stored.pending.singleOrNull()?.let {
                                it.requestId == request && it.content == submitted && it.baseVersion == proof.getString("baseVersion")
                            } == true
                        } == true
                    }.getOrDefault(false)
                }
                val final = fileStatus()
                assertEquals(2, final.getInt("writeCount"))
                assertEquals(proof.getJSONObject("worker").toString(), final.getJSONObject("worker").toString())
                assertEquals(request, final.getString("ackRequestId"))
                val audit = events(final)
                val requests = audit.filter { it.getString("event") == "write.request" }
                assertEquals(3, requests.size)
                val ids = requests.map { it.getString("id") }
                assertEquals(3, ids.distinct().size); assertEquals(request, ids.first())
                assertEquals(listOf(submitted, draft, draft), requests.map { it.getString("content") })
                assertEquals(proof.getString("baseVersion"), requests[0].getString("baseVersion"))
                assertEquals(proof.getString("baseVersion"), requests[1].getString("baseVersion"))
                val conflict = audit.single { it.getString("event") == "write.error" && it.getString("id") == ids[1] }
                assertEquals("conflict", conflict.getString("code"))
                val firstResult = audit.single { it.getString("event") == "write.result" && it.getString("id") == ids[0] }.getJSONObject("result")
                assertEquals("files.written", firstResult.getString("kind"))
                assertEquals(firstResult.getString("version"), requests[2].getString("baseVersion"))
                val finalResult = audit.single { it.getString("event") == "write.result" && it.getString("id") == ids[2] }.getJSONObject("result")
                assertEquals("files.written", finalResult.getString("kind"))
                val stored = requireNotNull(app.stores.fileDrafts.load()[key])
                assertEquals(finalResult.getString("version"), stored.baseVersion)
                assertEquals(listOf(request), stored.pending.map { it.requestId })
                assertEquals(listOf(request), repository.state.value.files?.editor?.superseded?.map { it.requestId })
                assertEquals(1, audit.count { it.getString("event") == "ack.dropped" && it.getString("id") == request })
                assertTrue(audit.indexOf(requests[0]) < audit.indexOf(conflict) && audit.indexOf(conflict) < audit.indexOf(requests[2]))
                check(!ProductionEntryBootstrap.observerOverflow)
                writeProof(bFile, JSONObject().put("pid", Process.myPid()).put("identity", identity).put("pairingSha256", proof.getString("pairingSha256"))
                    .put("path", path).put("requestId", request).put("worker", final.getJSONObject("worker")).put("initialHostWriteCount", 1)
                    .put("activityHadSavedState", savedState).put("recoveryShown", true).put("conflictGuard", true)
                    .put("writesBeforeRestore", 1).put("finalHostWrites", 2).put("unresolvedReceiptRetained", true)
                    .put("screenshot", recoveryScreenshot).put("completed", true))
            }
        } finally { scenario.close() }
    }
}
