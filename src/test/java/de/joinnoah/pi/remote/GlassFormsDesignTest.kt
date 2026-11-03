package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp")
class GlassFormsDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun newFolderValidatesAndSubmits() {
        val created = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FolderBrowserContent(
                    folders = FolderBrowserState(routeId = "host", path = "Code", loaded = true),
                    connected = true, available = true, connection = R.string.remote_connected,
                    onBack = {}, onJump = {}, onEnter = {}, onRetry = {},
                    onCreateFolder = { created += it }, onClone = { _, _ -> }, onOpen = {},
                    onConfirm = {}, onCancelTrust = {}, onDismissClone = {}, onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithTag("folderNewAction").performClick()
        compose.onNodeWithTag("newFolderCreate").assertIsNotEnabled()
        compose.onNodeWithTag("newFolderName").performTextInput("project")
        compose.onNodeWithTag("newFolderCreate").assertIsEnabled().performClick()
        assertEquals(listOf("project"), created)
        compose.onNodeWithTag("newFolderName").assertDoesNotExist()
    }

    @Test
    fun cloneUsesDerivedNameAndSubmitsOriginalUrl() {
        val clones = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            MaterialTheme {
                FolderBrowserContent(
                    folders = FolderBrowserState(routeId = "host", path = "Code", loaded = true),
                    connected = true, available = true, connection = R.string.remote_connected,
                    onBack = {}, onJump = {}, onEnter = {}, onRetry = {}, onCreateFolder = {},
                    onClone = { url, name -> clones += url to name }, onOpen = {},
                    onConfirm = {}, onCancelTrust = {}, onDismissClone = {}, onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithTag("folderCloneAction").performClick()
        compose.onNodeWithTag("cloneStart").assertIsNotEnabled()
        compose.onNodeWithTag("cloneUrl").performTextInput("https://github.com/example/project.git")
        compose.onNodeWithTag("cloneStart").assertIsEnabled().performClick()
        assertEquals(listOf("https://github.com/example/project.git" to null), clones)
    }
}
