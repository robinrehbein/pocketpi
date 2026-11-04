package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class EditorRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private class FakeRepo(var result: EditorRecoveryLoad) {
        suspend fun loadEditorRecovery() = result
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.noBackupFilesDir, "recovery-ui-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private val key = PagedJournalKey("host-id", "session-id", "question-id")

    @Test fun offlineDistinctEntriesOpenBoundedWindowsAndNavigate() {
        val draft = "A".repeat(2048) + "m".repeat(1_044_480) + "Z".repeat(2048)
        val attempt = "U".repeat(1_048_576)
        val repo = FakeRepo(EditorRecoveryLoad.Ready(listOf(
            EditorRecoveryEntry(key, EditorRecoveryKind.DRAFT, draft, 1),
            EditorRecoveryEntry(key, EditorRecoveryKind.UNCERTAIN_ATTEMPT, attempt, 2, "attempt"),
        )))
        compose.setContent { MaterialTheme { EditorRecoveryDialog(repo::loadEditorRecovery) {} } }
        compose.onNodeWithTag("recoveryEntryDraft").assertIsDisplayed()
        screenshot("list")
        compose.onNodeWithTag("recoveryEntryDraft").performClick()
        val previewBounds = compose.onNodeWithTag("recoveryPreview").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Preview viewport must leave the navigation reachable", previewBounds.bottom - previewBounds.top <= 240.dp)
        screenshot("detail")
        fun displayed(): String = requireNotNull(compose.onNodeWithTag("recoveryPreview").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)).joinToString("") { it.text }
        assertTrue(displayed().length <= 2048 && displayed().startsWith("AAAA"))
        compose.onNodeWithTag("recoveryLast").performScrollTo().performClick()
        assertTrue(displayed().length <= 2048 && displayed().endsWith("ZZZZ"))
        compose.onNodeWithTag("recoveryPreview").performScrollTo()
        screenshot("last")
        val actualSection = compose.onNodeWithTag("recoverySection").performScrollTo().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("512", actualSection)
        compose.onNodeWithTag("recoveryFirst").performScrollTo().performClick()
        assertTrue(displayed().startsWith("AAAA"))
        compose.onNodeWithTag("recoveryBackToList").performScrollTo().performClick()
        compose.onNodeWithTag("recoveryEntryUncertain").assertIsDisplayed().performClick()
        compose.onNodeWithTag("recoveryUncertainWarning").assertIsDisplayed()
        assertTrue(displayed().length <= 2048 && displayed().startsWith("UUUU"))
    }

    @Test fun failureCanRetryAndEmptyIsNotFailure() {
        val repo = FakeRepo(EditorRecoveryLoad.Failed)
        compose.setContent { MaterialTheme { EditorRecoveryDialog(repo::loadEditorRecovery) {} } }
        compose.onNodeWithTag("recoveryError").assertIsDisplayed()
        compose.runOnIdle { repo.result = EditorRecoveryLoad.Ready(emptyList()) }
        compose.onNodeWithTag("recoveryRetry").performClick()
        compose.onNodeWithTag("recoveryEmpty").assertIsDisplayed()
    }
}
