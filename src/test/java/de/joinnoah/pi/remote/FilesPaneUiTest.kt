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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private val asked = mutableListOf<String>()
    private var askPlaced = true
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

    /**
     * Renders the pane alone, or with [besideChat] right of a focusable button as in the two-pane
     * layout; navigation only records, the test moves [state] itself.
     */
    private fun render(initial: FilesState, projectName: String? = null, besideChat: Boolean = false): (FilesState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row {
                if (besideChat)
                    androidx.compose.material3.Button(onClick = {}, modifier = androidx.compose.ui.Modifier.testTag("chatButton")) {
                        androidx.compose.material3.Text("Chat")
                    }
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
                        onAsk = {
                            asked += it
                            askPlaced
                        },
                    ),
                    projectName = projectName,
                )
                }
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
    fun widePaneUsesMoreTileColumns() {
        val names = listOf("src", "docs", "scripts", "tests")
        val listing = FileListing("", names.map { FileEntry(it, FileEntryType.DIR) })
        render(FilesState("s", loading = false, listing = listing))
        val tiles = names.map { compose.onNodeWithTag("filesEntry:$it").getUnclippedBoundsInRoot() }
        for (tile in tiles.drop(1)) assertEquals(tiles[0].top, tile.top)
        assertEquals(true, tiles.zipWithNext().all { (a, b) -> a.left < b.left })
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun rightOpensATileAtTheRowsEndAndBackspaceGoesUp() {
        val listing = FileListing("src", listOf(FileEntry("lib", FileEntryType.DIR), FileEntry("Main.kt", FileEntryType.FILE, 10), FileEntry("Util.kt", FileEntryType.FILE, 10)))
        render(FilesState("s", loading = false, path = "src", listing = listing))
        val main = compose.onNodeWithTag("filesEntry:Main.kt")
        main.performSemanticsAction(SemanticsActions.RequestFocus)
        // Main.kt has Util.kt to its right: Right moves there and opens nothing.
        main.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(emptyList<String?>(), files)
        compose.onNodeWithTag("filesEntry:Util.kt").assertIsFocused()
        // At the row's end Right opens the file, as Enter would.
        compose.onNodeWithTag("filesEntry:Util.kt").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(listOf<String?>("src/Util.kt"), files)
        // Backspace goes up a folder.
        compose.onNodeWithTag("filesEntry:Util.kt").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
        compose.waitForIdle()
        assertEquals(listOf(""), opened)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun leftOnTheFirstTileGoesUpAFolderInsteadOfIntoTheChatBesideIt() {
        val listing = FileListing("src", listOf(FileEntry("lib", FileEntryType.DIR), FileEntry("Main.kt", FileEntryType.FILE, 10)))
        render(FilesState("s", loading = false, path = "src", listing = listing), besideChat = true)
        val lib = compose.onNodeWithTag("filesEntry:lib")
        lib.performSemanticsAction(SemanticsActions.RequestFocus)
        lib.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(listOf(""), opened)
        compose.onNodeWithTag("chatButton").assertIsNotFocused()
        lib.assertIsFocused()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w1205dp-h753dp")
    fun leftAtTheTopFolderMayLeaveThePane() {
        render(FilesState("s", loading = false, listing = root), besideChat = true)
        val src = compose.onNodeWithTag("filesEntry:src")
        src.performSemanticsAction(SemanticsActions.RequestFocus)
        src.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithTag("chatButton").assertIsFocused()
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
    fun folderGridExtendsBehindFloatingHeader() {
        render(FilesState("s", loading = false, listing = root))
        val list = compose.onNodeWithTag("filesList").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("filesHeaderPill").getUnclippedBoundsInRoot()
        assertEquals(true, list.top < header.bottom)
    }

    @Test
    fun rootHeaderShowsProjectNameWhenAvailable() {
        render(FilesState("s", loading = false, listing = root), projectName = "PocketPi")
        compose.onNodeWithTag("filesSubtitle").assertTextEquals("PocketPi")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun rootHeaderShowsSeparatePills() {
        render(FilesState("s", loading = false, listing = root))
        compose.onNodeWithTag("filesHeaderPill").assertIsDisplayed()
        compose.onNodeWithTag("filesReloadPill").assertIsDisplayed()
        compose.onNodeWithTag("filesTitle").assertTextEquals(label(R.string.remote_files_title))
        compose.onNodeWithTag("filesTitle").assertWidthIsAtLeast(36.dp)
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

    @Test
    fun askAboutSelectionSendsTheLinesAndTheQuestionAsOnePrompt() {
        val content = (1..8).joinToString("") { "line $it\n" }
        render(FilesState("s", loading = false, listing = root, file = OpenFile("src/a.kt", loading = false, content = content)))
        compose.onNodeWithTag("filesAsk").assertDoesNotExist()
        compose.onNodeWithTag("filesLine:3").performClick()
        compose.onNodeWithTag("filesLine:5").performClick()
        compose.onNodeWithTag("filesAskSend").assertIsNotEnabled()
        compose.onNodeWithTag("filesAsk").performTextInput("Why is this slow?")
        compose.onNodeWithTag("filesAsk").performImeAction()
        val quote = label(R.string.remote_files_quote_lines, "src/a.kt", 3, 5) + "\n\n```kotlin\nline 3\nline 4\nline 5\n```"
        assertEquals(listOf("$quote\n\nWhy is this slow?"), asked)
        assertEquals(emptyList<String>(), sent)
        // Enter from a keyboard asks too, and the field is empty again for the next question.
        compose.onNodeWithTag("filesAsk").performTextInput("And here?")
        compose.onNodeWithTag("filesAsk").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        assertEquals("$quote\n\nAnd here?", asked.last())
        compose.onNodeWithTag("filesAskSend").assertIsNotEnabled()
        // A question that went nowhere stays for another try.
        askPlaced = false
        compose.onNodeWithTag("filesAsk").performTextInput("Too long?")
        compose.onNodeWithTag("filesAskSend").performClick()
        assertEquals("$quote\n\nToo long?", asked.last())
        compose.onNodeWithTag("filesAskSend").assertIsEnabled()
        compose.onNodeWithTag("filesAsk").assertTextEquals("Too long?")
        // Sending the lines stays: it only fills the composer.
        compose.onNodeWithTag("filesSendLines").performClick()
        assertEquals(listOf(quote), sent)
    }

    @Test
    fun aFileOpenedAtLinesScrollsThemIntoView() {
        val content = (1..300).joinToString("") { "line $it\n" }
        val show = render(FilesState("s", loading = false, listing = root, file = OpenFile("a.kt", loading = true, selection = LineSelection(250, 252), revealLine = 250)))
        show(FilesState("s", loading = false, listing = root, file = OpenFile("a.kt", loading = false, content = content, selection = LineSelection(250, 252), revealLine = 250)))
        compose.waitForIdle()
        compose.onNodeWithTag("filesLine:250").assertIsDisplayed()
        compose.onNodeWithTag("filesLine:1").assertDoesNotExist()
        // Picking a line afterwards leaves the view where it is.
        compose.onNodeWithTag("filesLine:251").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filesLine:250").assertIsDisplayed()
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

    /** The chat read src/a.kt, lines 3 to 5, with an absolute path as pi often writes it. */
    private fun readToolCall() {
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    messages =
                        listOf(
                            buildJsonObject {
                                put("id", "reading")
                                put("role", "assistant")
                                put("text", "")
                                put(
                                    "parts",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "toolCall")
                                                put("id", "call-read")
                                                put("name", "read")
                                                put("arguments", "{\"path\":\"/Users/robin/project/src/a.kt\",\"offset\":3,\"limit\":3}")
                                            }
                                        )
                                    },
                                )
                            },
                            buildJsonObject {
                                put("id", "read")
                                put("role", "tool")
                                put("text", "line 3")
                                put("state", "complete")
                                put("toolName", "read")
                                put("toolCallId", "call-read")
                            },
                        ),
                    project = buildJsonObject {
                        put("id", "project")
                        put("name", "project")
                    },
                )
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aToolCardOpensItsFileAtTheLinesInTwoTaps() {
        chat(setOf(FILES_CAPABILITY))
        readToolCall()
        compose.onNodeWithTag("openInFiles").performClick()
        compose.waitForIdle()
        assertEquals(listOf("src/a.kt" to LineSelection(3, 5)), repository.filesOpenedAt)
        compose.onNodeWithTag("filesPane").assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithTag("filesLineSelected").fetchSemanticsNodes().size)
        assertEquals(label(R.string.remote_files_selection_lines, 3, 5), stateOf("filesLine:4"))
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun onATabletTheFileOpensInTheInspectorBesideTheChat() {
        chat(setOf(FILES_CAPABILITY))
        readToolCall()
        compose.onNodeWithTag("openInFiles").performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("filesPane") and hasAnyAncestor(hasTestTag("inspectorPane"))).assertIsDisplayed()
        compose.onNodeWithTag("composerField").assertIsDisplayed()
        assertEquals(listOf("src/a.kt" to LineSelection(3, 5)), repository.filesOpenedAt)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun withoutFilesTheToolCardShowsNoFileIcon() {
        chat(emptySet())
        readToolCall()
        compose.onNodeWithTag("toolCard").assertIsDisplayed()
        // Every path would only offer a copy; the tool menu has Copy path.
        compose.onNodeWithTag("openInFiles").assertDoesNotExist()
        compose.onNodeWithTag("copyOutsidePath").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheToolDetailMakesWayForTheFileItOpens() {
        chat(setOf(FILES_CAPABILITY))
        readToolCall()
        compose.onNodeWithTag("toolCardHeader").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toolDetail").assertIsDisplayed()
        compose.onNode(hasTestTag("openInFiles") and hasAnyAncestor(hasTestTag("toolDetail"))).performClick()
        compose.waitForIdle()
        assertEquals(listOf("src/a.kt" to LineSelection(3, 5)), repository.filesOpenedAt)
        compose.onNodeWithTag("toolDetail").assertDoesNotExist()
        compose.onNodeWithTag("filesPane").assertIsDisplayed()
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
     * shown; ProjectFilesLoaderTest covers the exclusion itself. Beside the chat both stay open,
     * each as a tab of the inspector.
     */
    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun filesAndChangesShareTheInspectorAsTabs() {
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
        // Changes again switches back to their tab; the files stay open behind it.
        compose.onNodeWithTag("chatAction_changes").performClick()
        compose.waitForIdle()
        shown("changesPane")
        compose.onNodeWithTag("filesPane").assertDoesNotExist()
        assertNotNull(repository.state.value.files)
        compose.onNodeWithTag("inspectorTab_files").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        shown("filesPane")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aWidePaneKeepsTheFolderBesideTheOpenFile() {
        val listing = FileListing("", listOf(FileEntry("src", FileEntryType.DIR), FileEntry("a.kt", FileEntryType.FILE), FileEntry("b.kt", FileEntryType.FILE)))
        render(FilesState("s", loading = false, listing = listing, file = OpenFile("a.kt", loading = false, content = "val a = 1")))
        compose.onNodeWithTag("filesSideList").assertIsDisplayed()
        compose.onNodeWithTag("filesFile").assertIsDisplayed()
        // Up from the first file reaches a folder, which opens only when chosen.
        compose.onNodeWithTag("filesSideEntry:a.kt").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("filesSideEntry:a.kt").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
        assertEquals(emptyList<String?>(), files)
        // Down on the open file's row opens the next file as focus moves there.
        compose.onNodeWithTag("filesSideEntry:a.kt").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("filesSideEntry:a.kt").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        assertEquals(listOf<String?>("b.kt"), files)
        compose.onNodeWithTag("filesSideEntry:src").performClick()
        assertEquals(listOf("src"), opened)
    }

    @Test
    fun aNarrowPaneShowsTheFileAlone() {
        render(FilesState("s", loading = false, listing = root, file = OpenFile("README.md", loading = false, content = "# Pi")))
        compose.onNodeWithTag("filesFile").assertIsDisplayed()
        compose.onNodeWithTag("filesSideList").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w600dp-h900dp")
    fun wrapLinesIsOneChoiceThatPersists() {
        val show = render(FilesState("s", loading = false, listing = root, file = OpenFile("README.md", loading = false, content = "x".repeat(400))))
        compose.onNodeWithTag("filesWrap").assertIsOff()
        compose.onNodeWithTag("filesWrap").performClick()
        compose.onNodeWithTag("filesWrap").assertIsOn()
        assertEquals(true, PaneLayoutStore(compose.activity).loadWrapLines())
        // The folder has nothing to wrap; the next file wraps again.
        show(FilesState("s", loading = false, listing = root))
        compose.onNodeWithTag("filesWrap").assertDoesNotExist()
        show(FilesState("s", loading = false, listing = root, file = OpenFile("README.md", loading = false, content = "short")))
        compose.onNodeWithTag("filesWrap").assertIsOn()
        compose.onNodeWithTag("filesWrap").performClick()
        assertEquals(false, PaneLayoutStore(compose.activity).loadWrapLines())
    }
}
