package de.joinnoah.pi.remote

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real composer, system picker, importer, encrypted store, transport and native host fixture. */
class MainActivityImageDeliveryEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun imageReceiptAfterLostAcknowledgement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("productionEntryImageDelivery") == "true")
        check(args.getString("productionEntryFixture") == "true" && args.getString("productionEntryObserve") == "true")
        val app = instrumentation.targetContext.applicationContext as RemoteApplication
        val repository = app.repository
        val label = requireNotNull(args.getString("productionEntryLabel"))
        val edited = args.getString("productionEntryImageEdited") == "true"
        val path = requireNotNull(args.getString("productionEntryFixturePath"))
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9._/-]+")) && !path.contains(".."))
        val fixture = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cat $path")
        ).use { it.readBytes().toString(Charsets.UTF_8) })
        val route = fixture.getString("routeId")
        val project = fixture.getString("projectId")
        val session = fixture.getString("sessionId")
        check(route == args.getString("productionEntryRoute") && project == args.getString("productionEntryProjectId") && session == args.getString("productionEntrySessionId"))
        check(fixture.getBoolean("nativeWorker") && fixture.getJSONObject("nativeLifecycle").getBoolean("image"))
        val relay = fixture.getString("relayUrl").toHttpUrl()
        fun endpoint(key: String): String = fixture.getString(key).also {
            val url = it.toHttpUrl()
            require(url.scheme == "https" && url.host == "10.0.2.2" && url.port == relay.port && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(url.encodedPath == if (key == "statusUrl") "/fixture-status" else "/fixture-action")
        }
        val http = ProductionEntryBootstrap.http()
        fun status(): JSONObject = http.newCall(Request.Builder().url(endpoint("statusUrl")).build()).execute().use {
            check(it.isSuccessful); JSONObject(checkNotNull(it.body).string())
        }
        fun action(value: String) = http.newCall(Request.Builder().url(endpoint("actionUrl"))
            .post(JSONObject().put("action", value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.code == 200) }
        fun waitFor(message: String, predicate: () -> Boolean) {
            try { compose.waitUntil(90_000) { predicate() } }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("Timed out: $message", e) }
        }
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        val resolver = app.contentResolver
        val owned = mutableListOf<Uri>()
        val device = UiDevice.getInstance(instrumentation)
        fun createPhoto(suffix: String, color: Int): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PocketPi-$label-$suffix.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketPi-$label")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            owned.add(uri)
            val bitmap = Bitmap.createBitmap(2001, 1, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1)
            return uri
        }
        val stages = JSONArray()
        val diagnosticFile = File(app.filesDir, "production-entry-image-$label-stages.json")
        fun stage(name: String, imageLabel: String, color: String, uri: Uri, thumb: androidx.test.uiautomator.UiObject2? = null, done: androidx.test.uiautomator.UiObject2? = null) {
            // Each probe is independent so a stale UI object cannot erase the other evidence.
            runCatching {
                val record = JSONObject().put("stage", name).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("image", imageLabel).put("expectedColor", color)
                fun probe(key: String, block: () -> Unit) {
                    runCatching(block).onFailure { record.put("${key}Failure", it.javaClass.simpleName.take(80)) }
                }
                probe("state") {
                    val state = repository.state.value
                    record.put("attachments", state.attachments.size).put("importing", state.importingAttachments)
                        .put("loading", state.loading).put("connected", state.connected)
                        .put("selectionMatches", state.selection == RemoteSelection(route, project, session))
                        .put("attachmentCapability", ATTACHMENTS_CAPABILITY in state.capabilities)
                        .put("errorResource", state.error?.let { runCatching { app.resources.getResourceEntryName(it) }.getOrNull() } ?: "")
                }
                probe("window") { record.put("package", device.currentPackageName ?: "") }
                probe("menu") { record.put("choosePhotosNodes", compose.onAllNodesWithText(app.getString(R.string.remote_pick_photos)).fetchSemanticsNodes().size) }
                probe("activity") { record.put("activityEvent", ProductionEntryBootstrap.activityEvents.lastOrNull()?.kind ?: "") }
                probe("media") {
                    resolver.query(uri, arrayOf(MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) record.put("mediaWidth", cursor.getInt(0)).put("mediaHeight", cursor.getInt(1)).put("mediaPending", cursor.getInt(2))
                    }
                }
                probe("selection") {
                    record.put("selectedIndicators", device.findObjects(By.desc("Selected")).size)
                        .put("selectionCountLabels", device.findObjects(By.desc(java.util.regex.Pattern.compile("[0-9]+ photos or videos selected"))).size)
                }
                if (thumb != null) probe("thumbnail") {
                    val bounds = thumb.visibleBounds
                    record.put("thumbnailDescription", thumb.contentDescription ?: "").put("thumbnailBounds", bounds.toShortString())
                    instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                        try {
                            if (bounds.centerX() in 0 until screenshot.width && bounds.centerY() in 0 until screenshot.height) {
                                val pixel = screenshot.getPixel(bounds.centerX(), bounds.centerY())
                                record.put("thumbnailCenterRgb", JSONArray().put(Color.red(pixel)).put(Color.green(pixel)).put(Color.blue(pixel)))
                            }
                        } finally { screenshot.recycle() }
                    }
                }
                if (done != null) probe("done") { record.put("doneEnabled", done.isEnabled).put("doneParentEnabled", done.parent?.isEnabled ?: JSONObject.NULL) }
                stages.put(record)
                diagnosticFile.writeText(stages.toString())
            }
        }

        var activeImage = "none"
        var activeColor = "none"
        var activeUri: Uri? = null
        fun pickPhoto(expectedCount: Int, uri: Uri, imageLabel: String, color: String) {
            activeImage = imageLabel; activeColor = color; activeUri = uri
            stage("before-picker", imageLabel, color, uri)
            val addDescription = app.getString(R.string.remote_add_attachment)
            waitFor("attachment action enabled") {
                val state = repository.state.value
                state.selection == RemoteSelection(route, project, session) && !state.loading &&
                    !state.sending && !state.importingAttachments && state.attachments.size < 5 &&
                    compose.onAllNodesWithContentDescription(addDescription).filter(isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription(addDescription).performClick()
            stage("after-add", imageLabel, color, uri)
            runCatching { device.takeScreenshot(File(app.filesDir, "production-entry-image-$label-$imageLabel-after-add.png")) }
            waitFor("choose photos menu") { compose.onAllNodesWithText(app.getString(R.string.remote_pick_photos)).fetchSemanticsNodes().size == 1 }
            stage("menu-ready", imageLabel, color, uri)
            compose.onNodeWithText(app.getString(R.string.remote_pick_photos)).performClick()
            // API 36 Google photo picker, English locale: the isolated emulator contains exactly
            // one owned published image. Its displayed description does not expose the filename.
            val selector = By.descStartsWith("Photo taken on ")
            check(device.wait(Until.hasObject(selector), 15_000)) { "System photo picker thumbnail unavailable" }
            val photos = device.findObjects(selector)
            check(photos.size == 1) { "Expected exactly one photo in isolated picker, found ${photos.size}" }
            stage("thumbnail", imageLabel, color, uri, photos.single())
            photos.single().click()
            stage("after-select", imageLabel, color, uri)
            check(device.wait(Until.hasObject(By.desc("Selected")), 5_000)) { "Picker selection indicator unavailable" }
            check(device.findObjects(By.desc("Selected")).size == 1) { "Expected one selected photo" }
            val done = device.wait(Until.findObject(By.text("Done")), 5_000)
                ?: error("System photo picker Done button unavailable")
            waitFor("picker Done enabled") { done.isEnabled && (done.parent?.isEnabled != false) }
            stage("before-done", imageLabel, color, uri, done = done)
            done.click()
            stage("after-done", imageLabel, color, uri)
            waitFor("return from system picker") { device.currentPackageName == app.packageName }
            stage("returned-to-app", imageLabel, color, uri)
            waitFor("photo imported through picker") { repository.state.value.attachments.size == expectedCount && !repository.state.value.importingAttachments }
            stage("imported", imageLabel, color, uri)
        }
        fun bytes(attachment: LocalAttachment): ByteArray = app.stores.attachments.read(attachment).also {
            assertEquals(attachment.size, it.size.toLong()); assertEquals(attachment.sha256, ProductionEntryBootstrap.sha256(it))
        }
        val key = DraftKey(route, session)
        val prompt = fixture.getJSONObject("nativeLifecycle").getString("prompt")
        var scenario: ActivityScenario<RemoteActivity>? = null
        try {
            scenario = ActivityScenario.launch(LaunchIntents.session(app, route, session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("connected session") { val s = repository.state.value; s.selection == RemoteSelection(route, project, session) && s.connected && !s.loading && exists("composerField") }
            val workflow = fixture.getJSONObject("workflow")
            if (exists("chatAction_files")) compose.onNodeWithTag("chatAction_files").performClick()
            else { compose.onNodeWithTag("chatActionsMore").performClick(); compose.onNodeWithTag("chatActionMenu_files").performClick() }
            val name = workflow.getString("fileName")
            waitFor("workflow file") { exists("filesEntry:$name") || exists("filesSideEntry:$name") }
            compose.onNodeWithTag(if (exists("filesEntry:$name")) "filesEntry:$name" else "filesSideEntry:$name").performScrollTo().performClick()
            waitFor("file editor action") { exists("filesEdit") }
            compose.onNodeWithTag("filesEdit").performClick()
            waitFor("file editor") { exists("filesEditorText") }
            compose.onNodeWithTag("filesEditorText").assertTextEquals(workflow.getString("originalText")).performTextReplacement(workflow.getString("editedText"))
            compose.onNodeWithTag("filesEditorSave").assertIsEnabled().performClick()
            waitFor("file saved") { status().getJSONObject("workflow").getBoolean("fileSaved") }
            repeat(6) { listOf("filesEditorBack", "filesClose", "filesBack", "changesClose").firstOrNull(::exists)?.let { compose.onNodeWithTag(it).performClick(); compose.waitForIdle() } }
            waitFor("composer after file edit") { exists("composerField") }
            val agentPrompt = workflow.getString("agentPrompt")
            compose.onNodeWithTag("composerField").performTextReplacement(agentPrompt)
            waitFor("agent prompt propagated") { repository.state.value.draft == agentPrompt }
            compose.onNodeWithTag("primaryComposerAction").performClick()
            waitFor("native workflow prepared") { status().getJSONObject("workflow").getBoolean("agentVerified") && repository.state.value.status == "idle" }
            val sourceUri = createPhoto("A", Color.BLACK)
            pickPhoto(1, sourceUri, "A", "black")
            val original = repository.state.value.attachments.single()
            assertEquals("image", original.kind); assertEquals("image/jpeg", original.mimeType)
            val originalBytes = bytes(original)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, dimensions)
            assertEquals(2001, dimensions.outWidth); assertEquals(1, dimensions.outHeight)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size))
            try {
                for (x in 0 until decoded.width) {
                    val pixel = decoded.getPixel(x, 0)
                    assertTrue(Color.red(pixel) <= 2 && Color.green(pixel) <= 2 && Color.blue(pixel) <= 2)
                }
            } finally { decoded.recycle() }
            assertTrue(exists("attachmentPreview-${original.id}"))
            compose.onNodeWithTag("composerField").performTextReplacement(prompt)
            waitFor("image prompt propagated") { repository.state.value.draft == prompt }
            val beforeWrites = ProductionEntryBootstrap.writeAttempts().size
            val beforeInbound = ProductionEntryBootstrap.inboundMessages().size
            action("arm-lifecycle-lost-ack")
            compose.onNodeWithTag("primaryComposerAction").performClick()
            var request = ""
            waitFor("pending prompt") { repository.state.value.pendingSend?.let { if (it.text == prompt) { request = it.mutationId; true } else false } == true }
            waitFor("withheld receipt and model image") {
                val life = status().getJSONObject("nativeLifecycle")
                val lost = life.optJSONObject("lostAck")
                lost != null && lost.optBoolean("accepted") && lost.optBoolean("ackAccepted") && lost.optInt("ackQueued") == 1 && lost.optInt("echoQueued") >= 1 && life.optJSONObject("imageModel")?.optBoolean("verified") == true
            }
            val life = status().getJSONObject("nativeLifecycle")
            val upload = life.getJSONObject("imageUpload")
            assertTrue(upload.getBoolean("committed") && upload.getBoolean("verified")); assertTrue(upload.getInt("chunks") > 0)
            assertEquals(session, upload.getString("sessionId")); assertEquals(original.size, upload.getLong("size"))
            assertEquals(original.sha256, upload.getString("sha256")); assertEquals("image/jpeg", upload.getString("mimeType"))
            val model = life.getJSONObject("imageModel")
            assertEquals(request, model.getString("requestId")); assertEquals(upload.getString("id"), model.getString("attachmentId"))
            assertEquals(original.sha256, model.getString("uploadSha256")); assertTrue(model.getString("normalizedMimeType") in setOf("image/png", "image/jpeg"))
            assertEquals(2000, model.getInt("width")); assertEquals(1, model.getInt("height")); assertNotEquals(original.sha256, model.getString("normalizedSha256"))
            val writes = ProductionEntryBootstrap.writeAttempts().drop(beforeWrites)
            val begin = writes.single { it.optionalText("type") == "session.attachments.begin" }
            val descriptor = begin["attachment"] as JsonObject
            assertEquals(session, begin.optionalText("sessionId")); assertEquals(original.sha256, descriptor.optionalText("sha256"))
            assertEquals(1, writes.count { it.optionalText("type") == "session.attachments.commit" })
            assertTrue(writes.any { it.optionalText("type") == "session.attachments.chunk" })
            val sent = writes.single { it.optionalText("type") == "session.prompt" && it.optionalText("text") == prompt }
            assertEquals(session, sent.optionalText("sessionId")); assertEquals(request, sent.optionalText("requestId"))
            assertEquals(listOf(upload.getString("id")), (sent["attachmentIds"] as JsonArray).map { it.toString().trim('"') })
            val lost = life.getJSONObject("lostAck")
            assertEquals(request, lost.getString("requestId")); assertEquals(0, lost.getInt("ackSent")); assertEquals(0, lost.getInt("echoSent"))
            val echo = lost.getString("echoSourceId")
            val delivery = lost.getJSONObject("echoRemoteDelivery")
            assertEquals(request, delivery.getString("requestId")); assertEquals("prompt", delivery.getString("delivery"))
            action("cut-lifecycle")
            waitFor("uncertain prompt") { val s = repository.state.value; !s.connected && s.uncertain && s.pendingSend?.mutationId == request && s.pendingSend?.state == PendingSendState.NOT_CONFIRMED }
            val incoming = ProductionEntryBootstrap.inboundMessages().drop(beforeInbound)
            assertFalse(incoming.any { it.toString().contains(request) || it.toString().contains(echo) })
            var replacement: LocalAttachment? = null
            if (edited) {
                compose.onNodeWithContentDescription(app.getString(R.string.remote_remove_attachment, original.name)).performClick()
                waitFor("original removed") { repository.state.value.attachments.isEmpty() }
                check(resolver.delete(sourceUri, null, null) == 1)
                check(owned.remove(sourceUri))
                val replacementUri = createPhoto("B", Color.WHITE)
                pickPhoto(1, replacementUri, "B", "white")
                replacement = repository.state.value.attachments.single()
                assertNotEquals(original.id, replacement.id); assertNotEquals(original.sha256, replacement.sha256)
                bytes(replacement)
            }
            runBlocking { repository.flush() }
            val pending = requireNotNull(app.stores.drafts.load()[key])
            assertEquals(request, pending.mutationId); assertEquals(listOf(original), pending.submittedAttachments)
            assertEquals(listOf(replacement ?: original), pending.attachments)
            bytes(original)
            action("allow-lifecycle")
            if (exists("reconnectNow")) compose.onNodeWithTag("reconnectNow").performClick()
            waitFor("source receipt") { repository.state.value.messages.any { val d = it["remoteDelivery"] as? JsonObject; it.optionalText("id") == echo && d?.optionalText("requestId") == request && d.optionalText("delivery") == "prompt" } }
            val receipt = repository.state.value.messages.single { it.optionalText("id") == echo }
            assertEquals("user", receipt.optionalText("role"))
            val body = userMessageBody(receipt)
            assertEquals(prompt + "\n\n[Image: original 2001x1, displayed at 2000x1. Multiply coordinates by 1.00 to map to original image.]", body)
            assertFalse(body.contains("[PocketPi delivery v1]")); assertFalse(body.contains("[PocketPi attachments v1]"))
            val image = (receipt["attachments"] as JsonArray).map { remoteAttachment(it as JsonObject) }.single()
            assertEquals(upload.getString("id"), image.id); assertEquals(original.sha256, image.sha256); assertEquals(original.size, image.size); assertEquals(original.mimeType, image.mimeType)
            waitFor("journal settled") { repository.state.value.pendingSend == null && !repository.state.value.uncertain }
            assertEquals(if (edited) listOf(replacement) else emptyList<LocalAttachment>(), repository.state.value.attachments)
            runBlocking { repository.flush() }
            val settled = requireNotNull(app.stores.drafts.load()[key])
            assertNull(settled.mutationId); assertEquals(if (edited) listOf(replacement) else emptyList<LocalAttachment>(), settled.attachments)
            if (replacement != null) bytes(replacement)
            assertFalse(ProductionEntryBootstrap.observerOverflow)
            assertTrue(ProductionEntryBootstrap.inboundMessages().drop(beforeInbound).any { it.toString().contains(request) && it.toString().contains(echo) })
            assertEquals(1, ProductionEntryBootstrap.writeAttempts().drop(beforeWrites).count { it.optionalText("type") == "session.prompt" && it.optionalText("text") == prompt })
            assertEquals(1, status().getJSONObject("nativeLifecycle").getInt("promptRequests"))
            File(app.filesDir, "production-entry-image-$label.json").writeText(JSONObject().put("completed", true).put("edited", edited).put("requestId", request).put("receiptId", echo).put("uploadSha256", original.sha256).put("uploadSize", original.size).put("normalizedSha256", model.getString("normalizedSha256")).put("promptRequests", 1).toString())
        } catch (failure: Throwable) {
            activeUri?.let { stage("failure", activeImage, activeColor, it) }
            runCatching { device.takeScreenshot(File(app.filesDir, "production-entry-image-$label-failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(app.filesDir, "production-entry-image-$label-failure.xml")) }
            throw failure
        } finally {
            scenario?.close()
            owned.forEach { resolver.delete(it, null, null) }
        }
    }
}
