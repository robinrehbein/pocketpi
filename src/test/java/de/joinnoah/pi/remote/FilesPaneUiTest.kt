package de.joinnoah.pi.remote

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilesPaneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private val files = mutableListOf<String?>()
    private val sent = mutableListOf<String>()
    private var closed = 0

    private val root =
        FileListing(
            "",
            listOf(
                FileEntry("src", FileEntryType.DIR),
                FileEntry("vendor", FileEntryType.SUBMODULE),
                FileEntry("latest", FileEntryType.SYMLINK),
                FileEntry("README.md", FileEntryType.FILE, 2048),
            ),
        )

    private fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Renders the pane alone; navigation only records, the test moves [state] itself. */
    private fun render(initial: FilesState): (FilesState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                FilesPane(
                    state,
                    FilesActions(
                        onClose = { closed++ },
                        onOpenDir = { opened += it },
                        onOpenFile = { files += it },
                        onLoadMore = {},
                        onReload = {},
                        onSelectLines = { state = state.copy(file = state.file?.copy(selection = it)) },
                        onSend = {
                            sent += it
                            true
                        },
                    ),
                )
            }
        }
        return { state = it }
    }

    @Test
    fun foldersOpenFilesOpenAndLinksDoNot() {
        render(FilesState("s", loading = false, listing = root))
        compose.onNodeWithText(Formatter.formatShortFileSize(compose.activity, 2048)).assertExists()
        compose.onNodeWithText(label(R.string.remote_files_submodule)).assertExists()
        compose.onNodeWithTag("filesEntry:src").performClick()
        compose.onNodeWithTag("filesEntry:README.md").performClick()
        compose.onNodeWithTag("filesEntry:latest").performClick()
        compose.onNodeWithTag("filesEntry:vendor").performClick()
        assertEquals(listOf("src"), opened)
        assertEquals(listOf<String?>("README.md"), files)
        compose.onNodeWithTag("filesCrumb:").assertIsNotEnabled()
    }

    @Test
    fun backClosesTheFileThenClimbsThenCloses() {
        val show = render(FilesState("s", path = "src/app", loading = false, listing = FileListing("src/app"), file = OpenFile("src/app/a.kt", loading = false)))
        back()
        assertEquals(listOf<String?>(null), files)
        show(FilesState("s", path = "src/app", loading = false, listing = FileListing("src/app")))
        compose.waitForIdle()
        // The breadcrumb jumps straight to an ancestor.
        compose.onNodeWithTag("filesCrumb:src").performClick()
        back()
        assertEquals(listOf("src", "src"), opened)
        show(FilesState("s", loading = false, listing = root))
        compose.waitForIdle()
        back()
        assertEquals(1, closed)
    }

    @Test
    fun binaryAndTooLargeFilesShowPlaceholdersAndSendAReference() {
        val show = render(FilesState("s", loading = false, listing = root, file = OpenFile("logo.png", loading = false, binary = true)))
        compose.onNodeWithTag("filesBinary").assertIsDisplayed()
        compose.onNodeWithTag("filesSendLines").assertDoesNotExist()
        compose.onNodeWithTag("filesSendFile").performClick()
        assertEquals(listOf(label(R.string.remote_files_reference_file, "logo.png")), sent)
        show(FilesState("s", loading = false, listing = root, file = OpenFile("dump.sql", loading = false, tooLarge = true)))
        compose.onNodeWithTag("filesTooLarge").assertIsDisplayed()
    }

    @Test
    fun sendFileQuotesTheWholeFile() {
        render(FilesState("s", loading = false, listing = root, file = OpenFile("a.json", loading = false, content = "{\"a\": 1}\n")))
        compose.onNodeWithTag("filesSendLines").assertIsNotEnabled()
        compose.onNodeWithTag("filesSendFile").performClick()
        assertEquals(listOf(label(R.string.remote_files_quote_file, "a.json") + "\n\n```json\n{\"a\": 1}\n```"), sent)
    }

    // ---- Through the chat screen ---------------------------------------------------------

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val source = (1..8).joinToString("") { "line $it\n" }

    private fun chat(capabilities: Set<String>) {
        val session = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "rpc")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                sessions = listOf(session),
                session = session,
                capabilities = capabilities,
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
        repository.filesOnOpen = { sessionId ->
            FilesState(sessionId, loading = false, listing = root, file = OpenFile("src/a.kt", loading = false, content = source))
        }
        compose.setContent {
            MaterialTheme { RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1)) }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun theActionNeedsTheCapability() {
        chat(emptySet())
        compose.onNodeWithTag("composerField").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_files").assertDoesNotExist()
        compose.onNodeWithTag("chatActionMenu_files").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun selectedLinesFillTheComposer() {
        chat(setOf(FILES_CAPABILITY))
        compose.onNodeWithTag("chatAction_files").performClick()
        compose.waitForIdle()
        assertEquals(1, repository.filesOpens)
        compose.onNodeWithTag("filesPane").assertIsDisplayed()
        compose.onNodeWithTag("filesLine:3").performClick()
        compose.onNodeWithTag("filesLine:5").performClick()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.remote_files_selected_lines, 3, 3)).assertExists()
        compose.onNodeWithTag("filesSendLines").performClick()
        compose.waitForIdle()
        assertEquals(
            label(R.string.remote_files_quote_lines, "src/a.kt", 3, 5) + "\n\n```kotlin\nline 3\nline 4\nline 5\n```",
            repository.state.value.draft,
        )
        assertNull(repository.state.value.files)
        compose.onNodeWithTag("filesPane").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun tabletsOpenFilesInTheInspector() {
        chat(setOf(FILES_CAPABILITY))
        compose.onNodeWithTag("chatAction_files").performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("filesPane") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertIsDisplayed()
        // Back leaves the file first, as on a phone.
        back()
        assertNull(repository.state.value.files?.file)
        compose.onNode(hasTestTag("filesList") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
    }
}
