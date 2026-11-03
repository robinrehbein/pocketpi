package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in emulator test: the real system document picker writes files in Downloads. */
class EditorRecoverySafExportUiTest {
    private val compose = createComposeRule()
    private lateinit var nonce: String
    private lateinit var evidence: File
    private val savedUri = AtomicReference<Uri?>(null)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit SAF export fixture required", args.getString("editorRecoveryExportExpected") == "true")
            nonce = requireNotNull(args.getString("editorRecoveryExportNonce"))
            require(nonce.matches(Regex("[a-f0-9]{32}"))) { "Expected a 32-character lowercase hexadecimal nonce" }
            check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") || Build.HARDWARE in setOf("ranchu", "goldfish")) { "SAF fixture may run only on an emulator" }
            evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "editor-recovery-export-$nonce")
            check(!evidence.exists()) { "Evidence directory already exists" }
            check(evidence.mkdirs())
        }
    }).around(compose)

    @Test fun createsAndVerifiesUtf8AndRawUtf16UsingRealDocumentProvider() {
        val valid = "é\\\n".repeat(262_144) // Exactly 1 MiB as UTF-8.
        val invalid = "x".repeat(1_048_575) + "\uD800"
        val expectedValid = valid.toByteArray(Charsets.UTF_8)
        val expectedRaw = ByteArray(2 + invalid.length * 2)
        expectedRaw[0] = 0xff.toByte()
        expectedRaw[1] = 0xfe.toByte()
        invalid.forEachIndexed { index, c ->
            expectedRaw[2 + 2 * index] = (c.code and 0xff).toByte()
            expectedRaw[3 + 2 * index] = (c.code ushr 8).toByte()
        }
        val validEntry = EditorRecoveryEntry(PagedJournalKey("local", "session", "valid"), EditorRecoveryKind.DRAFT, valid, 1)
        val invalidEntry = EditorRecoveryEntry(PagedJournalKey("local", "session", "invalid"), EditorRecoveryKind.UNCERTAIN_ATTEMPT, invalid, 2, "attempt")
        compose.setContent { MaterialTheme { EditorRecoveryDialog({ EditorRecoveryLoad.Ready(listOf(validEntry, invalidEntry)) }, onExportSaved = { savedUri.set(it) }) {} } }
        val proof = JSONObject()
        exportAndVerify("valid", "txt", expectedValid, "recoveryEntryDraft", proof)
        compose.onNodeWithTag("recoveryBackToList").performScrollTo().performClick()
        exportAndVerify("raw", "utf16", expectedRaw, "recoveryEntryUncertain", proof)
        File(evidence, "proof.json").writeText(proof.toString(2))
    }

    private fun exportAndVerify(kind: String, extension: String, expected: ByteArray, entryTag: String, proof: JSONObject) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val name = "pocketpi-recovery-$nonce-$kind.$extension"
        savedUri.set(null)
        val resolver = instrumentation.targetContext.contentResolver
        try {
            compose.onNodeWithTag(entryTag).performScrollTo().performClick()
            val preview = compose.onNodeWithTag("recoveryPreview").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            assertTrue("Only a bounded preview may enter semantics", requireNotNull(preview).sumOf { it.text.length } <= 2048)
            compose.onNodeWithTag("recoveryExport").performScrollTo().performClick()
            assertTrue("CreateDocument provider did not appear", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000) || device.currentPackageName.endsWith("documentsui"))
            device.waitForIdle()
            val drawer = device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Open navigation drawer"))
            checkNotNull(drawer) { "DocumentsUI navigation drawer missing" }.click()
            val downloads = device.findObject(UiSelector().text("Downloads"))
            check(downloads.waitForExists(5_000)) { "Downloads destination missing from DocumentsUI" }
            downloads.click()
            device.waitForIdle()
            val picker = device.currentPackageName
            val filename = device.findObject(By.res(picker, "file_name"))
                ?: device.findObject(By.res("android", "title"))
                ?: device.findObject(By.clazz("android.widget.EditText"))
            checkNotNull(filename) { "DocumentsUI filename field missing" }.text = name
            screenshot(device, "$kind-picker.png")
            val save = device.findObject(By.res(picker, "button_create"))
                ?: device.findObject(By.res("android", "button1"))
                ?: device.findObject(By.text("SAVE"))
                ?: device.findObject(By.text("Save"))
            checkNotNull(save) { "DocumentsUI Save button missing" }.click()
            // A failed or cancelled save must not produce a success assertion.
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("recoveryExportStatus").fetchSemanticsNodes().isNotEmpty()
            }
            val success = instrumentation.targetContext.getString(R.string.editor_recovery_export_saved)
            compose.onNodeWithTag("recoveryExportStatus").assertTextEquals(success)
            screenshot(device, "$kind-saved.png")
            compose.waitUntil(10_000) { savedUri.get() != null }
            val uri = requireNotNull(savedUri.get())
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst()) { "Document provider returned no display name" }
                cursor.getString(0)
            }
            assertEquals("Created document must retain the unique fixture filename", name, displayName)
            val actual = checkNotNull(resolver.openInputStream(uri)) { "Cannot read returned document URI" }.use { it.readBytes() }
            assertArrayEquals("Document bytes differ from independently constructed expected data", expected, actual)
            File(evidence, "$kind.bin").writeBytes(actual)
            proof.put(kind, JSONObject().put("filename", name).put("bytes", actual.size).put("sha256", sha256(actual)).put("expectedSha256", sha256(expected)))
        } finally {
            savedUri.get()?.let { uri ->
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    check(cursor.moveToFirst()) { "Cannot validate cleanup document name" }
                    cursor.getString(0)
                }
                check(displayName == name) { "Refusing to delete unexpected document: $displayName" }
                check(DocumentsContract.deleteDocument(resolver, uri)) { "Fixture cleanup failed: $name" }
            }
        }
    }

    private fun screenshot(device: UiDevice, name: String) {
        device.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(evidence, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
