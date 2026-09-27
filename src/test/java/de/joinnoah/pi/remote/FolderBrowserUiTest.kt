package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderBrowserUiTest {
    @get:Rule val compose = createComposeRule()

    private val jumps = mutableListOf<String>()
    private val opens = mutableListOf<String>()
    private val confirms = mutableListOf<FolderTrustPrompt>()
    private val entered = mutableListOf<String>()
    private var dismissed = 0

    private fun loaded(path: String, vararg entries: FolderEntry) =
        FolderBrowserState(
            routeId = "host",
            root = "~",
            path = path,
            entries = entries.toList(),
            loaded = true,
        )

    private fun render(folders: FolderBrowserState, available: Boolean = true) {
        val state = mutableStateOf(folders)
        compose.setContent {
            MaterialTheme {
                FolderBrowserContent(
                    folders = state.value,
                    connected = true,
                    available = available,
                    connection = R.string.remote_connected,
                    onBack = {},
                    onJump = { jumps += it },
                    onEnter = { entered += it },
                    onRetry = {},
                    onCreateFolder = {},
                    onClone = { _, _ -> },
                    onOpen = { opens += it },
                    onConfirm = { confirms += it },
                    onCancelTrust = {},
                    onDismissClone = { dismissed++ },
                    onDismissNotice = {},
                )
            }
        }
    }

    @Test
    fun breadcrumbJumpsToAnAncestor() {
        render(loaded("Code/app"))
        compose.onNodeWithText("~").assertIsDisplayed()
        compose.onNodeWithTag("folderCrumb:Code/app").assertIsNotEnabled()
        compose.onNodeWithText("Code").performClick()
        compose.onNodeWithText("~").performClick()
        assertEquals(listOf("Code", ""), jumps)
    }

    @Test
    fun rootOffersNoOpenHereButExplainsWhy() {
        render(loaded("", FolderEntry("Code", git = false, piConfig = false, shared = false, trusted = true)))
        compose.onNodeWithTag("folderOpenHere").assertIsNotEnabled()
        compose.onNodeWithTag("folderRootHint").assertIsDisplayed()
        compose.onNodeWithTag("folderNewAction").assertIsEnabled()
        compose.onNodeWithTag("folderRow:Code").performClick()
        assertEquals(listOf("Code"), entered)
    }

    @Test
    fun subfolderOffersOpenHereAndShowsBadges() {
        render(loaded("Code", FolderEntry("app", git = true, piConfig = true, shared = true, trusted = false)))
        compose.onNodeWithTag("folderRootHint").assertDoesNotExist()
        compose.onNodeWithText("Git").assertIsDisplayed()
        compose.onNodeWithText("Shared").assertIsDisplayed()
        compose.onNodeWithTag("folderPiConfig:app", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("folderOpenHere").assertIsEnabled().performClick()
        assertEquals(listOf("Code"), opens)
    }

    @Test
    fun actionsAreUnavailableWithoutTheCapability() {
        render(loaded("Code"), available = false)
        compose.onNodeWithTag("folderUnavailable").assertIsDisplayed()
        compose.onNodeWithTag("folderOpenHere").assertIsNotEnabled()
        compose.onNodeWithTag("folderNewAction").assertIsNotEnabled()
        compose.onNodeWithTag("folderCloneAction").assertIsNotEnabled()
    }

    @Test
    fun trustDialogConfirmsWithoutWarningForAPlainFolder() {
        render(loaded("Code").copy(trust = FolderTrustPrompt("host", "Code/app", piConfig = false)))
        compose.onNodeWithText("Trust this folder?").assertIsDisplayed()
        compose.onNodeWithTag("trustPiConfigWarning").assertDoesNotExist()
        compose.onNodeWithTag("trustConfirm").performClick()
        assertEquals(listOf(FolderTrustPrompt("host", "Code/app", piConfig = false)), confirms)
    }

    @Test
    fun trustDialogWarnsAboutPiConfig() {
        render(loaded("Code").copy(trust = FolderTrustPrompt("host", "Code/app", piConfig = true)))
        compose.onNodeWithTag("trustPiConfigWarning").assertIsDisplayed()
    }

    @Test
    fun cloneProgressShowsPhaseAndPercent() {
        render(
            loaded("Code").copy(clones = mapOf("host" to CloneProgress("host", "c", "Code", "Code/repo", phase = ClonePhase.RECEIVING, percent = 42))
            )
        )
        compose.onNodeWithText("Cloning into ~/Code/repo", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Receiving objects", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("clonePercent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("42 %", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("folderCloneAction").assertIsNotEnabled()
    }

    @Test
    fun finishedCloneOffersToOpenIt() {
        render(
            loaded("Code").copy(clones = mapOf("host" to CloneProgress("host", "c", "Code", "Code/repo", stage = CloneStage.SUCCEEDED))
            )
        )
        compose.onNodeWithTag("cloneOpen").performClick()
        assertEquals(listOf("Code/repo"), opens)
    }

    @Test
    fun failedCloneExplainsWhy() {
        render(
            loaded("Code").copy(clones = mapOf("host" to
                    CloneProgress(
                        "host",
                        "c",
                        "Code",
                        "Code/repo",
                        stage = CloneStage.FAILED,
                        failure = CloneFailure.REPO_NOT_FOUND,
                    ))
            )
        )
        compose.onNodeWithText("The repository was not found.").assertIsDisplayed()
    }

    @Test
    fun anotherHostsCloneIsNotShown() {
        render(
            loaded("Code").copy(
                clones = mapOf("other" to CloneProgress("other", "c", "", "repo", stage = CloneStage.SUCCEEDED))
            )
        )
        compose.onNodeWithTag("cloneProgress").assertDoesNotExist()
    }

    @Test
    fun runningCloneCanBeHidden() {
        render(loaded("Code").copy(clones = mapOf("host" to CloneProgress("host", "c", "Code", "Code/repo"))))
        compose.onNodeWithTag("cloneHide").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun piConfigTrustDialogConfirmsTheShownPrompt() {
        val prompt = FolderTrustPrompt("host", "Code/repo", piConfig = true)
        render(loaded("Code").copy(trust = prompt))
        compose.onNodeWithText("Trust this folder?").assertIsDisplayed()
        compose.onNodeWithTag("trustPiConfigWarning").assertIsDisplayed()
        compose.onNodeWithTag("trustConfirm").performClick()
        assertEquals(listOf(prompt), confirms)
    }

    @Test
    fun loadingEmptyAndErrorStates() {
        val state = mutableStateOf(FolderBrowserState(routeId = "host", loading = true))
        compose.setContent {
            MaterialTheme {
                RemoteFolderList(state.value, enabled = true, onEnter = {}, onRetry = { jumps += "retry" })
            }
        }
        compose.onNodeWithTag("folderLoading").assertIsDisplayed()
        state.value = loaded("Code")
        compose.onNodeWithTag("folderEmpty").assertIsDisplayed()
        state.value = loaded("Code").copy(error = R.string.remote_folders_error_browse_busy)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), jumps)
    }
}
