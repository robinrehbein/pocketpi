package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Open in Files" where the chat names a file: tool cards, tool details, touched files and diff lines. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenInFilesUiTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<FileTarget>()
    private val copied = mutableListOf<String>()

    private fun host(canOpen: Boolean = true, content: @Composable () -> Unit) {
        val opener = FileOpener(SessionFolderHints(projectName = "app"), canOpen, { opened += it }, { copied += it })
        compose.setContent {
            MaterialTheme(typography = RemoteTypography) {
                CompositionLocalProvider(LocalFileOpener provides opener) { PointerMenuHost(content) }
            }
        }
    }

    private fun read(path: String) =
        ConversationItem.Activity("tool-1", "tool-1", "read", """{"path":"$path","offset":12,"limit":4}""", "text", "complete", false)

    @Test
    fun aToolCardOpensItsFileAtTheLinesItRead() {
        host { ConversationMessage(read("/Users/robin/app/src/a.kt"), "status", thinkingActive = false, onQuote = {}, onOpenTool = {}) }
        compose.onNodeWithTag("openInFiles").performClick()
        assertEquals(listOf(FileTarget("src/a.kt", LineSelection(12, 15))), opened)
    }

    @Test
    fun aFileOutsideTheProjectOffersItsPathToCopy() {
        host { ConversationMessage(read("/etc/hosts"), "status", thinkingActive = false, onQuote = {}, onOpenTool = {}) }
        compose.onNodeWithTag("openInFiles").assertDoesNotExist()
        compose.onNodeWithTag("copyOutsidePath").performClick()
        assertEquals(listOf("/etc/hosts"), copied)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aToolCardWithoutAFileShowsNoIcon() {
        val bash = ConversationItem.Activity("tool-2", "tool-2", "bash", """{"command":"ls"}""", "a.kt", "complete", false)
        host { ConversationMessage(bash, "status", thinkingActive = false, onQuote = {}) }
        compose.onNodeWithTag("toolCard").assertIsDisplayed()
        compose.onNodeWithTag("openInFiles").assertDoesNotExist()
    }

    @Test
    fun outsideAChatAToolCardShowsNoIcon() {
        compose.setContent { MaterialTheme { ConversationMessage(read("src/a.kt"), "status", thinkingActive = false, onQuote = {}) } }
        compose.onNodeWithTag("toolCard").assertIsDisplayed()
        compose.onNodeWithTag("openInFiles").assertDoesNotExist()
        compose.onNodeWithTag("copyOutsidePath").assertDoesNotExist()
    }

    @Test
    fun theToolDetailPathOpensTheFile() {
        host {
            ToolDetailScreen(
                read("src/a.kt"), null, canLoadFullOutput = false, canAskToFix = false,
                onLoadFullOutput = {}, onCancelFullOutput = {}, onAskToFix = {}, onQuote = {}, onClose = {},
            )
        }
        compose.onNodeWithTag("openInFiles").performClick()
        assertEquals(listOf(FileTarget("src/a.kt", LineSelection(12, 15))), opened)
    }

    @Test
    fun aTouchedFileOpensFromItsIconWhileTheRowStillJumpsToTheMessage() {
        val jumped = mutableListOf<String>()
        host { TouchedFilesPane(TouchedFiles(listOf(TouchedFile("src/a.kt", 2, "item-7")), emptyList()), onOpen = { jumped += it }, onClose = {}) }
        compose.onNodeWithTag("touchedFileRow").performClick()
        assertEquals(listOf("item-7"), jumped)
        assertTrue(opened.isEmpty())
        compose.onNodeWithTag("openInFiles").performClick()
        assertEquals(listOf(FileTarget("src/a.kt")), opened)
        assertEquals(listOf("item-7"), jumped)
    }

    @Test
    fun aDiffLineOpensTheFileAtThatLine() {
        host {
            ChangesPane(
                ChangesState(
                    "s",
                    loading = false,
                    status = GitStatus(GitBase.SESSION, "Q2hhbmdlc1NuYXBzaG90MQ", listOf(GitFile("src/a.kt", GitChange.MODIFIED, 1, 1))),
                    file = "src/a.kt",
                    diff = GitDiff("src/a.kt", "Q2hhbmdlc1NuYXBzaG90MQ", "@@ -10,3 +10,3 @@\n same\n-old\n+new\n", binary = false, truncated = false),
                ),
                ChangesActions(
                    onClose = {}, onSelectBase = {}, onReload = {}, onOpenFile = {},
                    onSetComment = { true }, onRemoveComment = {}, onUseReview = { true }, onPrefill = { true },
                ),
            )
        }
        val lines = compose.onAllNodesWithTag("changesDiffLine")
        // The removed line opens where the new file goes on: line 11, as the added line does.
        lines[1].performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_open_file").performClick()
        lines[2].performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_open_file").performClick()
        lines.onFirst().performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_open_file").assertIsDisplayed().performClick()
        assertEquals(
            listOf(FileTarget("src/a.kt", LineSelection(11)), FileTarget("src/a.kt", LineSelection(11)), FileTarget("src/a.kt", LineSelection(10))),
            opened,
        )
    }

    @Test
    fun withoutFilesADiffLineOffersNoOpening() {
        host(canOpen = false) {
            ChangesPane(
                ChangesState(
                    "s",
                    loading = false,
                    status = GitStatus(GitBase.SESSION, "Q2hhbmdlc1NuYXBzaG90MQ", listOf(GitFile("a.kt", GitChange.MODIFIED, 1, 1))),
                    file = "a.kt",
                    diff = GitDiff("a.kt", "Q2hhbmdlc1NuYXBzaG90MQ", "@@ -1 +1 @@\n-a\n+b\n", binary = false, truncated = false),
                ),
                ChangesActions(
                    onClose = {}, onSelectBase = {}, onReload = {}, onOpenFile = {},
                    onSetComment = { true }, onRemoveComment = {}, onUseReview = { true }, onPrefill = { true },
                ),
            )
        }
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_copy_line").assertIsDisplayed()
        compose.onNodeWithTag("menu_diff_open_file").assertDoesNotExist()
    }
}
