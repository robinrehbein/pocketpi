package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileDraftRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun actions(restore: () -> Unit = {}, discard: () -> Unit = {}) = FilesActions(
        onClose = {}, onOpenDir = {}, onOpenFile = {}, onLoadMore = {}, onReload = {},
        onRequestPreview = {}, onShowPeek = { _, _ -> }, onDismissPeek = {}, onSelectLines = {}, onSend = { false },
        onRestoreEditorDraft = restore, onDiscardEditorDraft = discard,
    )
    @Test fun recoveryShowsComparisonAndStorageFailureWithoutAnEditableBuffer() {
        val editor = FileEditor("a.kt", "host-version", "host text", recovery =
            StoredFileDraft("old-version", "old text", "draft text", listOf(PendingWrite("r", "old-version", "sent text"))),
            saveFailure = FilesFailure.STORAGE)
        compose.setContent { MaterialTheme { CodeEditor(editor, actions()) } }
        compose.onNodeWithTag("filesEditorText").assertDoesNotExist()
        compose.onNodeWithTag("filesEditorRecoveryFailure").assertExists()
        compose.onNodeWithTag("filesEditorRecoveryHost").assertTextEquals("host text")
        compose.onNodeWithTag("filesEditorRecoveryDraft").assertTextEquals("draft text")
        compose.onNodeWithTag("filesEditorRecoveryPending").assertTextEquals("sent text")
    }
    @Test fun restoreRequiresExplicitActionAndLoadingDoesNotExposeEditing() {
        var restores = 0
        val editor = FileEditor("a.kt", "v", "host", recovery = StoredFileDraft("v", "host", "draft"))
        compose.setContent { MaterialTheme { CodeEditor(editor, actions(restore = { restores++ })) } }
        compose.runOnIdle { assertEquals(0, restores) }
        compose.onNodeWithTag("filesEditorRestoreDraft").performClick()
        compose.runOnIdle { assertEquals(1, restores) }
    }
}
