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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
    private var reloaded = 0
    private var moreLoads = 0
    private val requestedPreviews = mutableListOf<String>()
    private val peeked = mutableListOf<String>()
    private var dismissedPeeks = 0

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

    private fun scrollToEntry(name: String) =
        compose.onNodeWithTag("filesList").performScrollToNode(hasTestTag("filesEntry:$name"))

    /** Renders the pane alone; navigation only records, the test moves [state] itself. */
    private fun render(initial: FilesState, projectName: String? = null): (FilesState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                FilesPane(
                    state,
                    FilesActions(
                        onClose = { closed++ },
                        onOpenDir = { opened += it },
                        onOpenFile = { files += it },
                        onLoadMore = { moreLoads++ },
                        onReload = { reloaded++ },
                        onRequestPreview = { requestedPreviews += it },
                        onShowPeek = { path, type ->
                            peeked += path
                            state = state.copy(peek = FilesPeek(path, type, loading = false, listing = if (type == FileEntryType.DIR) FileListing(path) else null))
                        },
                        onDismissPeek = { dismissedPeeks++; state = state.copy(peek = null) },
                        onSelectLines = { state = state.copy(file = state.file?.copy(selection = it)) },
                        onSend = {
                            sent += it
                            true
                        },
                    ),
                    projectName = projectName,
                )
            }
        }
        return { state = it }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun listingUsesTwoTileColumnsAndRequestsVisibleFilePreview() {
        val listing = root.copy(entries = listOf(FileEntry("src", FileEntryType.DIR), FileEntry("docs", FileEntryType.DIR), FileEntry("README.md", FileEntryType.FILE, 2048)))
        render(FilesState("s", loading = false, listing = listing))
        compose.onNodeWithTag("filesEntry:src").assertExists()
        compose.onNodeWithTag("filesEntry:README.md").assertExists()
        val folder = compose.onNodeWithTag("filesEntry:src").getUnclippedBoundsInRoot()
        val docs = compose.onNodeWithTag("filesEntry:docs").getUnclippedBoundsInRoot()
        assertEquals(folder.top, docs.top)
        assertEquals(true, folder.left < docs.left)
        compose.waitForIdle()
        assertEquals(listOf("README.md"), requestedPreviews)
    }

    @Test
    @Config(qualifiers = "w800dp-h700dp")
    fun widePaneUsesThreeTileColumns() {
        val listing = FileListing("", listOf("src", "docs", "scripts").map { FileEntry(it, FileEntryType.DIR) })
        render(FilesState("s", loading = false, listing = listing))
        val tiles = listOf("src", "docs", "scripts").map { compose.onNodeWithTag("filesEntry:$it").getUnclippedBoundsInRoot() }
        assertEquals(tiles[0].top, tiles[1].top)
        assertEquals(tiles[1].top, tiles[2].top)
        assertEquals(true, tiles[0].left < tiles[1].left && tiles[1].left < tiles[2].left)
    }

    @Test
    fun paginationRemainsReachableAfterTiles() {
        render(FilesState("s", loading = false, listing = root.copy(nextAfter = "README.md")))
        compose.onNodeWithTag("filesList").performScrollToNode(hasTestTag("filesLoadMore"))
        compose.onNodeWithTag("filesLoadMore").performClick()
        assertEquals(1, moreLoads)
    }

    @Test
    fun longPressPeeksWithoutOpeningAndBackDismissesIt() {
        render(FilesState("s", loading = false, listing = root))
        scrollToEntry("README.md")
        compose.onNodeWithTag("filesEntry:README.md").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("filePeek").assertIsDisplayed()
        assertEquals(listOf("README.md"), peeked)
        assertEquals(emptyList<String?>(), files)
        back()
        compose.onNodeWithTag("filePeek").assertDoesNotExist()
        assertEquals(1, dismissedPeeks)
        assertEquals(0, closed)
    }

    @Test
    fun rootHeaderShowsProjectNameWhenAvailable() {
        render(FilesState("s", loading = false, listing = root), projectName = "PocketPi")
        compose.onNodeWithTag("filesSubtitle").assertTextEquals("PocketPi")
    }

    @Test
    fun rootHeaderShowsSeparatePills() {
        render(FilesState("s", loading = false, listing = root))
        compose.onNodeWithTag("filesHeaderPill").assertIsDisplayed()
        compose.onNodeWithTag("filesReloadPill").assertIsDisplayed()
        compose.onNodeWithTag("filesTitle").assertTextEquals(label(R.string.remote_files_title))
        compose.onNodeWithTag("filesSubtitle").assertDoesNotExist()
        compose.onNodeWithTag("filesBack").assertDoesNotExist()
        compose.onNodeWithTag("filesClose").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("filesReload").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("filesClose").performClick()
        assertEquals(1, closed)
        compose.onNodeWithTag("filesReload").assertIsEnabled().performClick()
        assertEquals(1, reloaded)
    }

    @Test
    fun nestedHeaderShowsPathBackAndClose() {
        render(FilesState("s", path = "src/app", loading = false, listing = FileListing("src/app")))
        compose.onNodeWithTag("filesTitle").assertTextEquals(label(R.string.remote_files_title))
        compose.onNodeWithTag("filesSubtitle").assertTextEquals("src/app")
        compose.onNodeWithTag("filesBack").performClick()
        assertEquals(listOf("src"), opened)
        compose.onNodeWithTag("filesClose").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun openFileHeaderShowsFilePath() {
        render(FilesState("s", path = "src", loading = false, listing = root, file = OpenFile("src/App.kt", loading = false)))
        compose.onNodeWithTag("filesSubtitle").assertTextEquals("src/App.kt")
        compose.onNodeWithTag("filesBack").performClick()
        assertEquals(listOf<String?>(null), files)
    }

    @Test
    fun reloadIsDisabledWhileListingOrFileIsLoading() {
        val show = render(FilesState("s", loading = true, listing = root))
        compose.onNodeWithTag("filesReload").assertIsNotEnabled()
        show(FilesState("s", loading = false, listing = root, file = OpenFile("a.kt", loading = true)))
        compose.onNodeWithTag("filesReload").assertIsNotEnabled()
        assertEquals(0, reloaded)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun longNestedPathStaysInsideLeftPillBeforeReload() {
        render(FilesState("s", path = "src/a/very/long/path/that/cannot/fit/on/a/narrow/phone", loading = false, listing = root))
        val text = compose.onNodeWithTag("filesSubtitle").getUnclippedBoundsInRoot()
        val left = compose.onNodeWithTag("filesHeaderPill").getUnclippedBoundsInRoot()
        val reload = compose.onNodeWithTag("filesReloadPill").getUnclippedBoundsInRoot()
        val close = compose.onNodeWithTag("filesClose").getUnclippedBoundsInRoot()
        assertEquals(true, text.right <= close.left)
        assertEquals(true, left.right < reload.left)
        compose.onNodeWithTag("filesBack").assertIsDisplayed()
        compose.onNodeWithTag("filesClose").assertIsDisplayed()
    }

    @Test
    fun foldersOpenFilesOpenAndLinksDoNot() {
        render(FilesState("s", loading = false, listing = root))
        compose.onNodeWithTag("filesCrumb:").assertIsNotEnabled()
        compose.onNodeWithTag("filesEntry:src").performClick()
        scrollToEntry("README.md")
        compose.onNodeWithText(Formatter.formatShortFileSize(compose.activity, 2048)).assertExists()
        compose.onNodeWithText(label(R.string.remote_files_submodule)).assertExists()
        compose.onNodeWithTag("filesEntry:README.md").performClick()
        scrollToEntry("latest")
        compose.onNodeWithTag("filesEntry:latest").performClick()
        scrollToEntry("vendor")
        compose.onNodeWithTag("filesEntry:vendor").performClick()
        assertEquals(listOf("src"), opened)
        assertEquals(listOf<String?>("README.md"), files)
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

    private fun clickLabel(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].label

    private fun stateOf(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun lineNumbersAreWideTargetsThatSayWhatATapDoes() {
        render(
            FilesState(
                "s",
                loading = false,
                listing = root,
                file = OpenFile("a.kt", loading = false, content = (1..8).joinToString("") { "line $it\n" }),
            )
        )
        compose.onNodeWithTag("filesLine:3").assertWidthIsAtLeast(48.dp)
        assertEquals(label(R.string.remote_files_line_start, 3), clickLabel("filesLine:3"))
        assertEquals(null, stateOf("filesLine:3"))
        compose.onNodeWithTag("filesLine:3").performClick()
        assertEquals(label(R.string.remote_files_line_clear), clickLabel("filesLine:3"))
        assertEquals(label(R.string.remote_files_line_end, 5), clickLabel("filesLine:5"))
        assertEquals(label(R.string.remote_files_selection_line, 3), stateOf("filesLine:7"))
        compose.onNodeWithTag("filesLine:5").performClick()
        assertEquals(label(R.string.remote_files_selection_lines, 3, 5), stateOf("filesLine:1"))
        assertEquals(label(R.string.remote_files_line_start, 3), clickLabel("filesLine:3"))
        assertEquals(true, compose.onNodeWithTag("filesLine:4").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        // Starting anew and tapping the start again clears the selection.
        compose.onNodeWithTag("filesLine:6").performClick()
        compose.onNodeWithTag("filesLine:6").performClick()
        assertEquals(null, stateOf("filesLine:6"))
        compose.onNodeWithTag("filesSelection").assertDoesNotExist()
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

    /*
     * On a phone either pane covers the chat header, so the other cannot be opened while it is
     * shown; ProjectFilesLoaderTest covers the exclusion itself, whatever the layout. Beside the
     * chat both stay reachable, so the inspector must show one at a time.
     */
    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun filesAndChangesReplaceEachOtherInTheInspector() {
        chat(setOf(GIT_CAPABILITY, FILES_CAPABILITY))
        fun shown(tag: String) =
            compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        // New users see Changes and Refresh in the pill; Files is in the menu.
        compose.onNodeWithTag("chatAction_changes").performClick()
        compose.waitForIdle()
        shown("changesPane")
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.onNodeWithTag("chatActionMenu_files").performClick()
        compose.waitForIdle()
        shown("filesPane")
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_changes").performClick()
        compose.waitForIdle()
        shown("changesPane")
        compose.onNodeWithTag("filesPane").assertDoesNotExist()
        assertNull(repository.state.value.files)
    }
}
