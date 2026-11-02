package de.joinnoah.pi.remote

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the application-created repository and journal through the real MainActivity. */
class PagedEditorNativeProductionFactoryEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativePagedEditorUsesProductionFactoryAndReleasesOutcome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit production factory fixture required", args.getString("pagedNativeProductionFactoryExpected") == "true")
        check(args.getString("productionEntryFixture") == "true" && args.getString("productionEntryFreshOwned") == "true")
        check(args.getString("productionEntryMode") == "seed" && args.getString("productionEntryObserve") == "true")
        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        check(ProductionEntryBootstrap.installed)
        val label = requireNotNull(args.getString("productionEntryLabel"))
        require(label.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val path = requireNotNull(args.getString("productionEntryFixturePath"))
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $path")
        ).use { it.readBytes().toString(Charsets.UTF_8) })
        check(fixture.getBoolean("prStatusFixture") && fixture.getString("sessionKind") == "actual-pi")
        check(fixture.getBoolean("nativeWorker") && !fixture.getBoolean("pagedEditorFixture") && fixture.getBoolean("nativeProductionProof"))
        check(fixture.getString("pagedEditorCase") == "max-unchanged" && fixture.getBoolean("pagedEditorPositiveOutcomes"))
        val route = fixture.getString("routeId")
        val project = fixture.getString("projectId")
        val session = fixture.getString("sessionId")
        check(route == args.getString("productionEntryRoute") && project == args.getString("productionEntryProjectId") && session == args.getString("productionEntrySessionId"))
        check(fixture.getString("deviceId") == args.getString("productionEntryDeviceId"))
        check(args.getString("productionEntrySecretFromFixture") == "true" && args.getString("productionEntryDeviceSecret") == null)
        val relay = fixture.getString("relayUrl").toHttpUrl()
        check(relay.toString().removeSuffix("/") == requireNotNull(args.getString("productionEntryRelay")).toHttpUrl().toString().removeSuffix("/"))
        val statusUrl = fixture.getString("statusUrl").toHttpUrl()
        require(statusUrl.scheme == "https" && statusUrl.host == "10.0.2.2" && statusUrl.port == relay.port && statusUrl.encodedPath == "/fixture-status" && statusUrl.query == null && statusUrl.fragment == null && statusUrl.username.isEmpty() && statusUrl.password.isEmpty())
        fun status(): JSONObject = ProductionEntryBootstrap.http().newCall(Request.Builder().url(statusUrl).build()).execute().use {
            check(it.isSuccessful); JSONObject(requireNotNull(it.body).string())
        }
        fun statusOrNull() = runCatching { status() }.getOrNull()
        fun waitFor(message: String, predicate: () -> Boolean) {
            try { compose.waitUntil(120_000) { predicate() } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Timed out: $message", e) }
        }
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val source = "😀".repeat(262_144)
        val hash = digest(source)
        for (name in listOf("Source", "Final")) check(fixture.getInt("pagedEditor${name}Bytes") == 1_048_576 && fixture.getString("pagedEditor${name}Sha256") == hash)
        val repository = app.repository
        val journal = app.stores.pagedEditor
        val initial = journal.load()
        check(initial.drafts.isEmpty() && initial.attempts.isEmpty() && initial.pendingReleases.isEmpty())
        check(status().getBoolean("ownerPidVerified") && status().getBoolean("nativeWorkerVerified"))
        val proof = JSONObject().put("label", label).put("routeId", route).put("projectId", project).put("sessionId", session)
        val proofFile = File(app.filesDir, "production-entry-paged-factory-$label.json")
        fun record(stage: String) { proof.put("stage", stage); proofFile.outputStream().use { it.write(proof.toString().toByteArray()); it.fd.sync() } }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            record("launch")
            scenario = ActivityScenario.launch(LaunchIntents.session(app, route, session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val command = fixture.getString("editorProbeCommand")
            waitFor("production repository session and capabilities") {
                repository.state.value.let { it.selection == RemoteSelection(route, project, session) && it.connected && !it.loading &&
                    NATIVE_EDITOR_CAPABILITY in it.capabilities && QUESTION_PAGED_EDITOR_CAPABILITY in it.capabilities &&
                    QUESTION_EDITOR_POSITIVE_OUTCOME_CAPABILITY in it.capabilities && !it.commandsLoading &&
                    it.commands.any { c -> c.name == commandName(command) } } &&
                    compose.onAllNodesWithTag("composerField").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(ProductionEntryBootstrap.factoryRequests.get() > 0 && ProductionEntryBootstrap.socketOpens.get() > 0)
            assertEquals(true, ProductionEntryBootstrap.lastEndpointAllowed)
            compose.onNodeWithTag("composerField").performTextReplacement("$command ${fixture.getString("editorProbeNonce")}")
            compose.onNodeWithTag("primaryComposerAction").assertIsEnabled().performClick()
            checkNotNull(scenario).onActivity { activity -> WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            waitFor("native editor question") { repository.state.value.questions.any { it.optionalText("kind") == "editor" && it.containsKey("editorUploadGrantId") } }
            val question = repository.state.value.questions.single { it.optionalText("kind") == "editor" }
            assertEquals(kotlinx.serialization.json.JsonPrimitive(true), question["native"])
            val id = question.text("id")
            val grant = question.text("editorUploadGrantId")
            val key = PagedJournalKey(route, session, id)
            compose.onNodeWithText(app.getString(R.string.remote_paged_editor_load)).performScrollTo().assertIsEnabled().performClick()
            waitFor("editor source") { repository.state.value.pagedEditors[id]?.text == source }
            val changed = "XX" + "😀".repeat(262_143)
            compose.onNodeWithTag("questionEditorField").performScrollTo().performTextReplacement("XX" + "😀".repeat(1_023))
            waitFor("durable UI edit") { repository.state.value.pagedEditors[id]?.text == changed && journal.load().drafts[key]?.text == changed }
            compose.onNodeWithTag("questionEditorField").performTextReplacement("😀".repeat(1_024))
            waitFor("durable restored source") { repository.state.value.pagedEditors[id]?.text == source && journal.load().drafts[key]?.text == source }
            record("draft-persisted")
            compose.onNodeWithText(app.getString(R.string.remote_send)).performScrollTo().assertIsEnabled().performClick()
            waitFor("native result and released ledger") {
                statusOrNull()?.let { s -> s.optBoolean("pagedOwnerVerified") && s.optBoolean("pagedCommitDispatched") && s.optBoolean("pagedAckVerified") && s.optBoolean("pagedSdkResultVerified") && s.optBoolean("nativeEditorValueVerified") && s.optInt("pagedCommitCount", -1) == 1 && s.optJSONObject("nativeProduction")?.optInt("ledgerAfterReleaseCount", -1) == 0 } == true &&
                    repository.state.value.questions.none { it.optionalText("id") == id } && journal.load().let { it.drafts.isEmpty() && it.attempts.isEmpty() && it.pendingReleases.isEmpty() }
            }
            val fresh = PagedEditorStore(app).load()
            assertTrue(fresh.drafts.isEmpty() && fresh.attempts.isEmpty() && fresh.pendingReleases.isEmpty())
            assertFalse(ProductionEntryBootstrap.observerOverflow)
            val writes = ProductionEntryBootstrap.writeAttempts()
            val inbound = ProductionEntryBootstrap.inboundMessages()
            val begin = writes.single { it.optionalText("type") == "session.question.editor.upload.begin" && it.optionalText("questionId") == id }
            assertEquals(grant, begin.text("grantId"))
            val commit = writes.single { it.optionalText("type") == "session.question.editor.upload.commit" && it.optionalText("questionId") == id }
            val release = writes.single { it.optionalText("type") == "session.question.editor.outcome.release" }
            val receipt = release.obj("receipt")
            assertEquals(commit.text("requestId"), receipt.text("commitRequestId"))
            assertEquals(commit.text("uploadId"), receipt.text("uploadId"))
            assertEquals(hash, receipt.text("sha256"))
            assertEquals(1_048_576L, receipt.long("byteLength"))
            assertTrue(inbound.any { it.optionalText("type") == "result" && it.optionalText("requestId") == release.text("requestId") && it.flag("ok") && it.obj("data").optionalText("kind") == "editor_outcome_released" })
            assertTrue(writes.none { it.optionalText("type") == "question.answer" })
            val final = status()
            assertEquals(1, final.getInt("pagedBeginCount")); assertEquals(22, final.getInt("pagedChunkCount")); assertEquals(1, final.getInt("sourceSdkResultCount"))
            assertEquals(0, final.getInt("nativeEditorOriginalCalls"))
            val production = final.getJSONObject("nativeProduction")
            assertEquals(1, production.getInt("ledgerAppliedCount")); assertEquals(0, production.getInt("ledgerAfterReleaseCount"))
            val capabilityRequest = production.getJSONObject("capabilityRequest")
            val capabilityId = capabilityRequest.getString("requestId")
            assertTrue(capabilityId.startsWith("capabilities.v3:"))
            for (optIn in listOf("question_editor.v1", "question_editor_native.v1", QUESTION_PAGED_EDITOR_OPT_IN, QUESTION_EDITOR_POSITIVE_OUTCOME_OPT_IN)) assertTrue(capabilityId.contains(optIn))
            assertEquals(capabilityId, production.getJSONObject("capabilityResponse").getString("requestId"))
            assertTrue(production.getJSONObject("capabilityResponse").getBoolean("ok"))
            proof.put("sourceSha256", hash).put("uiDraftPersisted", true).put("freshJournalEmpty", true).put("hostStatus", final).put("imeQualified", false)
            record("complete")
        } catch (failure: Throwable) {
            runCatching { proof.put("failure", failure.stackTraceToString()); record("failure") }
            throw failure
        } finally { scenario?.close() }
    }
}
