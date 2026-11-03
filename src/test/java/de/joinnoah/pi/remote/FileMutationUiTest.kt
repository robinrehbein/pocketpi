package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The file rows' rename and delete, and the delete prompt, in [FilesPane]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileMutationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val renames = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private val confirms = mutableListOf<String?>()
    private var cancels = 0

    private val actions = FilesActions(
        onClose = {}, onOpenDir = {}, onOpenFile = {}, onLoadMore = {}, onReload = {}, onRequestPreview = {},
        onShowPeek = { _, _ -> }, onDismissPeek = {}, onSelectLines = {}, onSend = { true },
        onPrepareRename = { renames += it }, onPrepareDelete = { deletes += it },
        onConfirmMutation = { confirms += it }, onCancelMutation = { cancels++ },
    )

    private val listing = FileListing("", listOf(FileEntry("a.txt", FileEntryType.FILE, 1), FileEntry("src", FileEntryType.DIR)))

    private fun render(access: FileMutationAccess, dialog: FileMutationDialog? = null) {
        compose.setContent {
            MaterialTheme {
                FilesPane(
                    FilesState("session", loading = false, listing = listing), actions,
                    mutations = FileMutationsState("host", "session", dialog = dialog), mutationAccess = access,
                )
            }
        }
    }

    @Test
    fun fileRowsOfferRenameAndDeleteOnlyWhenAdvertised() {
        render(FileMutationAccess(rename = true, delete = true))
        compose.onNodeWithTag("fileActions:a.txt").performClick()
        compose.onNodeWithTag("fileAction:delete:a.txt").performClick()
        assertEquals(listOf("a.txt"), deletes)
        compose.onNodeWithTag("fileActions:a.txt").performClick()
        compose.onNodeWithTag("fileAction:rename:a.txt").performClick()
        assertEquals(listOf("a.txt"), renames)
        // Folders get none.
        assertTrue(compose.onAllNodesWithTagCount("fileActions:src") == 0)
    }

    @Test
    fun withoutTheCapabilitiesRowsHaveNoActions() {
        render(FileMutationAccess())
        assertTrue(compose.onAllNodesWithTagCount("fileActions:a.txt") == 0)
    }

    @Test
    fun deletePromptNamesThePathAndConfirmsOnlyAfterTheFreshRead() {
        render(FileMutationAccess(delete = true), FileMutationDialog(FileMutationKind.DELETE, "src/a.txt", checking = true))
        compose.onNodeWithTag("filesDeletePath").assertTextContains("src/a.txt", substring = true)
        compose.onNodeWithTag("filesMutationConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("filesMutationCancel").assertIsEnabled().performClick()
        assertEquals(1, cancels)
        assertTrue(confirms.isEmpty())
    }

    @Test
    fun aBlockedPromptCannotBeConfirmed() {
        render(FileMutationAccess(delete = true), FileMutationDialog(FileMutationKind.DELETE, "a.txt", version = "v", blocked = true))
        compose.onNodeWithTag("filesMutationConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("filesMutationNotice").assertExists()
    }

    @Test
    fun aCheckedPromptConfirmsTheDelete() {
        render(FileMutationAccess(delete = true), FileMutationDialog(FileMutationKind.DELETE, "a.txt", version = "v"))
        compose.onNodeWithTag("filesMutationConfirm").assertIsEnabled().performClick()
        assertEquals(listOf<String?>(null), confirms)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
