package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** The chat header's More actions, their snackbar feedback and the status mark, inside the whole app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()

    private val session = buildJsonObject {
        put("id", "s1")
        put("title", "First session")
        put("status", "idle")
        put("origin", "rpc")
    }

    init {
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                // The list was read on this connection, so its rows are confirmed.
                sessionsFresh = true,
                capabilities = setOf(SESSION_TREE_CAPABILITY, ARTIFACTS_CAPABILITY, EXPORT_CAPABILITY, RELOAD_CAPABILITY),
                capabilitiesKnown = true,
            )
        val target = RemoteSelection("host", "project", "s1")
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = target)
            target
        }
    }

    private fun render() {
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun runFromMenu(action: ChatAction) {
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_${action.name.lowercase()}").performClick()
        compose.waitForIdle()
    }

    private fun snackbarShows(text: String) =
        compose.onNode(hasText(text).and(hasAnyAncestor(hasTestTag("chatSnackbar"))), useUnmergedTree = true)

    @Test
    fun treeArtifactsExportAndReloadAreInTheMoreActions() {
        render()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        for (action in listOf(ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.EXPORT, ChatAction.RELOAD))
            compose.onNode(
                hasText(label(action.label())).and(hasAnyAncestor(hasTestTag("chatActionMenu_${action.name.lowercase()}"))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun reloadFeedbackAppearsAsSnackbarsAndItsUseIsRecorded() {
        val answer = CompletableDeferred<ReloadResult>()
        repository.reload = { answer.await() }
        render()
        runFromMenu(ChatAction.RELOAD)
        assertEquals(listOf("s1"), repository.reloads)
        snackbarShows(label(R.string.remote_reload_running)).assertIsDisplayed()
        compose.runOnIdle { answer.complete(ReloadResult.Done) }
        compose.waitForIdle()
        snackbarShows(label(R.string.remote_reload_done)).assertIsDisplayed()
        snackbarShows(label(R.string.remote_reload_running)).assertDoesNotExist()
        assertNull(ShadowToast.getTextOfLatestToast())
        // The palette still ranks its actions by use.
        assertEquals(listOf(ChatAction.RELOAD), settings.recordedActions)
    }

    @Test
    fun aFailedReloadSaysWhyInASnackbar() {
        repository.reload = { ReloadResult.Failed(ReloadFailure.Failed("extension crashed")) }
        render()
        runFromMenu(ChatAction.RELOAD)
        snackbarShows(compose.activity.getString(R.string.remote_reload_failed_detail, "extension crashed")).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theArtifactsHintAppearsAsASnackbar() {
        // A host that answered without artifacts keeps the entry, which explains itself.
        repository.state.value =
            repository.state.value.copy(capabilities = repository.state.value.capabilities - ARTIFACTS_CAPABILITY)
        render()
        runFromMenu(ChatAction.ARTIFACTS)
        snackbarShows(label(R.string.remote_artifacts_host_outdated)).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theArtifactsRowOpensTheGalleryOnceTheHostOffersArtifacts() {
        // The chat first composes before the capability arrives, as after a reconnect or a host update.
        repository.state.value =
            repository.state.value.copy(capabilities = repository.state.value.capabilities - ARTIFACTS_CAPABILITY)
        render()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(capabilities = repository.state.value.capabilities + ARTIFACTS_CAPABILITY)
        }
        compose.waitForIdle()
        runFromMenu(ChatAction.ARTIFACTS)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactsEmpty").fetchSemanticsNodes().isNotEmpty() }
        snackbarShows(label(R.string.remote_artifacts_not_now)).assertDoesNotExist()
        snackbarShows(label(R.string.remote_artifacts_host_outdated)).assertDoesNotExist()
    }

    @Test
    fun theArtifactsRowExplainsItselfOnceTheHostStopsOfferingArtifacts() {
        render()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(capabilities = repository.state.value.capabilities - ARTIFACTS_CAPABILITY)
        }
        compose.waitForIdle()
        runFromMenu(ChatAction.ARTIFACTS)
        snackbarShows(label(R.string.remote_artifacts_host_outdated)).assertIsDisplayed()
    }

    @Test
    fun theCloseRowAsksAboutTheSessionItCanCloseNow() {
        render()
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(capabilities = repository.state.value.capabilities + SESSION_CLOSE_CAPABILITY)
        }
        compose.waitForIdle()
        runFromMenu(ChatAction.CLOSE)
        compose.onNodeWithText(label(R.string.remote_session_close_confirmation)).assertIsDisplayed()
    }

    @Test
    fun aTerminalSessionAsksWithItsOwnConfirmation() {
        val terminal = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "tui")
        }
        compose.runOnIdle {
            repository.state.value =
                repository.state.value.copy(
                    sessions = listOf(terminal),
                    session = terminal,
                    capabilities =
                        repository.state.value.capabilities + SESSION_CLOSE_CAPABILITY + SESSION_CLOSE_TERMINAL_CAPABILITY,
                )
        }
        render()
        runFromMenu(ChatAction.CLOSE)
        compose.onNodeWithText(label(R.string.remote_session_close_terminal_confirmation)).assertIsDisplayed()
    }

    @Test
    fun exportReportsInTheChatWithoutAToast() {
        render()
        runFromMenu(ChatAction.EXPORT)
        // The fake host cannot export: the failure shows in the chat's export status.
        compose.onNode(
            hasText(label(R.string.remote_export_unsupported)).and(hasAnyAncestor(hasTestTag("exportStatus"))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(ChatAction.EXPORT), settings.recordedActions)
    }

    @Test
    fun headerStatusHasAShapePerState() {
        render()
        val mark = compose.onNodeWithTag("chatStatusDot")
        fun expect(shape: ChatStatusShape, text: Int, live: Boolean = false) {
            compose.waitForIdle()
            mark.assertContentDescriptionEquals(label(text))
                .assert(SemanticsMatcher.expectValue(ChatStatusShapeKey, shape))
                .assert(SemanticsMatcher.expectValue(ChatStatusLiveKey, live))
        }
        expect(ChatStatusShape.READY, R.string.remote_status_idle)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "running") }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running, live = true)
        // Last-known, opening or previewed RUNNING keeps the static play mark: still running, not live.
        compose.runOnIdle { repository.state.value = repository.state.value.copy(stale = true) }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(stale = false, loading = true) }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(loading = false, preview = true) }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(preview = false) }
        expect(ChatStatusShape.RUNNING, R.string.remote_status_running, live = true)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "waiting") }
        expect(ChatStatusShape.WAITING, R.string.remote_status_waiting)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "unrecognized") }
        expect(ChatStatusShape.UNKNOWN, R.string.remote_status_unknown)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(connected = false) }
        expect(ChatStatusShape.OFFLINE, R.string.remote_status_offline)
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "running") }
        expect(ChatStatusShape.OFFLINE, R.string.remote_status_offline)
    }

    private fun withPullRequest(capabilities: Set<String>, connected: Boolean = true) {
        repository.state.value =
            repository.state.value.copy(
                capabilities = capabilities,
                connected = connected,
                pullRequest =
                    PullRequestStatus(
                        123,
                        "https://github.com/o/r/pull/123",
                        false,
                        PullRequestReview.NONE,
                        PullRequestChecksState.DONE,
                        2,
                        listOf("build", "lint"),
                    ),
            )
    }

    @Test
    fun theConnectedPullRequestPillOpensTheChangesPane() {
        withPullRequest(setOf(GIT_CAPABILITY))
        render()
        val chip = compose.onNodeWithTag("pullRequestChip")
        chip.assertIsDisplayed().assertHasClickAction()
        chip.assertTextContains("PR #123", substring = true).assertTextContains("2 failing", substring = true)
        compose.onNodeWithTag("changesPane").assertDoesNotExist()
        chip.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("changesPane").assertIsDisplayed()
    }

    @Test
    fun thePullRequestPillIsNotAButtonWithoutGitSupport() {
        withPullRequest(setOf(SESSION_TREE_CAPABILITY))
        render()
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun thePullRequestPillIsNotAButtonWhileOffline() {
        withPullRequest(setOf(GIT_CAPABILITY), connected = false)
        render()
        compose.onNodeWithTag("pullRequestChip").assertIsDisplayed().assertHasNoClickAction()
    }

    private fun menuEntries(): List<String> {
        compose.waitForIdle()
        return ChatAction.entries.map { "chatActionMenu_${it.name.lowercase()}" }
            .filter { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theOpenMoreActionsKeepTheirEntriesWhenPiStartsRunning() {
        render()
        compose.onNodeWithTag("chatActionsMore").performClick()
        val idle = menuEntries()
        assertTrue(idle.containsAll(listOf("chatActionMenu_export", "chatActionMenu_reload")))
        compose.runOnIdle { repository.state.value = repository.state.value.copy(status = "running") }
        assertEquals(idle, menuEntries())
    }

    @Test
    fun exportAndReloadExplainThemselvesWhilePiRuns() {
        repository.state.value = repository.state.value.copy(status = "running")
        render()
        runFromMenu(ChatAction.EXPORT)
        snackbarShows(label(R.string.remote_export_when_idle)).assertIsDisplayed()
        compose.onNodeWithTag("exportStatus").assertDoesNotExist()
        runFromMenu(ChatAction.RELOAD)
        snackbarShows(label(R.string.remote_reload_busy)).assertIsDisplayed()
        assertEquals(emptyList<String>(), repository.reloads)
    }

    @Test
    fun closedPillUsesMostRecentAvailableStoredActionNotMostFrequent() {
        settings.usage[ChatAction.SETTINGS] = ActionUsage(500.0, 10)
        settings.usage[ChatAction.FILES] = ActionUsage(1.0, 20)
        settings.usage[ChatAction.JOBS] = ActionUsage(1.0, 30)
        settings.usage[ChatAction.CLOSE] = ActionUsage(1.0, 40)
        repository.state.value =
            repository.state.value.copy(capabilities = repository.state.value.capabilities + GIT_CAPABILITY + FILES_CAPABILITY)
        render()
        compose.onNodeWithTag("chatAction_changes").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_refresh").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_settings").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_files").assertIsDisplayed()
        compose.runOnIdle {
            repository.state.value = repository.state.value.copy(capabilities = repository.state.value.capabilities - FILES_CAPABILITY)
        }
        compose.onNodeWithTag("chatAction_settings").assertIsDisplayed()
    }

    private fun terminalTextCount() =
        compose.onAllNodesWithText(label(R.string.remote_terminal_title), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun terminalIsNotOfferedWhileTheRepositoryCannotAttachIt() {
        render()
        assertEquals(0, terminalTextCount())
        compose.onNodeWithTag("chatAction_terminal").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_terminal").assertDoesNotExist()
        assertEquals(0, terminalTextCount())
    }

    @Test
    fun terminalSitsInTheMoreActionsAndOpensTheTerminalInspector() {
        repository.terminalAttachable = true
        render()
        // The closed header is the title pill plus one shortcut and the chevron: no Terminal text button.
        assertEquals(0, terminalTextCount())
        compose.onNodeWithTag("chatAction_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_terminal").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNode(
            hasText(label(R.string.remote_terminal_title)).and(hasAnyAncestor(hasTestTag("chatActionMenu_terminal"))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_terminal").performClick()
        compose.waitForIdle()
        // The row asks the inspector to attach, as the old header button did, and its use is recorded.
        assertEquals(listOf(80 to 24), repository.terminalAttaches)
        assertEquals(listOf(ChatAction.TERMINAL), settings.recordedActions)
        compose.onNodeWithTag("chatActionMenu_terminal").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_terminal").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_terminal").performClick()
        compose.waitForIdle()
        assertEquals(listOf(80 to 24, 80 to 24), repository.terminalAttaches)
    }

    @Test
    fun aStoredTerminalShortcutFallsBackWhileTheTerminalIsUnavailable() {
        settings.usage[ChatAction.SETTINGS] = ActionUsage(1.0, 10)
        settings.usage[ChatAction.TERMINAL] = ActionUsage(1.0, 20)
        repository.terminalAttachable = true
        render()
        compose.onNodeWithTag("chatAction_terminal").assertIsDisplayed()
        // The gate lives in the repository; the next state the header reads drops Terminal.
        compose.runOnIdle {
            repository.terminalAttachable = false
            repository.state.value = repository.state.value.copy(status = "running")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chatAction_terminal").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_settings").assertIsDisplayed()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_terminal").assertDoesNotExist()
        // The stored use stays, so Terminal returns as the shortcut once it can attach again.
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            repository.terminalAttachable = true
            repository.state.value = repository.state.value.copy(status = "idle")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chatAction_terminal").assertIsDisplayed()
    }

    @Test
    fun thePaletteNeverListsTerminal() {
        val everything =
            repository.state.value.copy(
                selection = RemoteSelection("host", "project", "s1"),
                capabilities = repository.state.value.capabilities + SESSION_TERMINAL_CAPABILITY + SESSION_TERMINAL_KEYS_CAPABILITY,
            )
        assertFalse(chatActionAvailable(ChatAction.TERMINAL, everything))
        assertFalse(ChatAction.TERMINAL in DEFAULT_CHAT_ACTIONS.filter { chatActionAvailable(it, everything) })
    }

    @After
    fun stopRenderRecording() {
        ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options())
    }

    @Test
    fun thePaletteNeverListsRenderCapture() {
        val everything = repository.state.value.copy(selection = RemoteSelection("host", "project", "s1"))
        assertFalse(chatActionAvailable(ChatAction.RENDER_CAPTURE, everything))
        assertFalse(ChatAction.RENDER_CAPTURE in DEFAULT_CHAT_ACTIONS.filter { chatActionAvailable(it, everything) })
    }

    @Test
    fun renderCaptureIsOfferedInTheMenuOnlyWhileRecording() {
        render()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_refresh").assertIsDisplayed()
        compose.onNodeWithTag("chatActionMenu_render_capture").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.runOnIdle { ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options(recording = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag("captureChatRender").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_render_capture").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_render_capture").performScrollTo().assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun recordingKeepsTheTwoControlHeaderAndCaptureOpensTheExportFromTheMenu() {
        ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options(recording = true))
        repository.terminalAttachable = true
        withLongTitle()
        render()
        assertTwoControlHeader(sameRow = true)
        compose.onNodeWithTag("captureChatRender").assertDoesNotExist()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatActionMenu_render_capture").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shareRenderSnapshot").assertIsDisplayed()
        compose.onNodeWithTag("copyRenderSnapshot").assertIsDisplayed()
        assertEquals(listOf(ChatAction.RENDER_CAPTURE), settings.recordedActions)
    }

    @Test
    fun aUsedRenderCaptureBecomesTheShortcutAndFallsBackWhenRecordingStops() {
        ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options(recording = true))
        settings.usage[ChatAction.SETTINGS] = ActionUsage(1.0, 10)
        settings.usage[ChatAction.RENDER_CAPTURE] = ActionUsage(1.0, 20)
        render()
        compose.onNodeWithTag("chatAction_render_capture").assertIsDisplayed()
        val quick = compose.onNodeWithTag("chatAction_render_capture").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("chatActionsMore").getUnclippedBoundsInRoot()
        assertEquals(96f, (more.right - quick.left).value, 0.5f)
        compose.runOnIdle { ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options()) }
        compose.waitForIdle()
        compose.onNodeWithTag("chatAction_render_capture").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_settings").assertIsDisplayed()
    }

    private val longTitle = "glassy-frosted-header-session-" + "with-a-very-long-title-".repeat(8)

    private fun withLongTitle() {
        val long = buildJsonObject {
            put("id", "s1")
            put("title", longTitle)
            put("status", "idle")
            put("origin", "rpc")
        }
        // The open session and its row in the list agree, however the notification resolves it.
        repository.state.value = repository.state.value.copy(session = long, sessions = listOf(long))
    }

    /** The tags of the header's clickable controls on the shortcut's row from [fromX] px on. */
    private fun rightControls(fromX: Float, quickTag: String = "chatAction_refresh"): List<String?> {
        val quick = compose.onNodeWithTag(quickTag).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(hasClickAction().and(hasAnyAncestor(hasTestTag("chatHeader"))))
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.left >= fromX - 0.5f && it.boundsInRoot.center.y in quick.top..quick.bottom }
            .sortedBy { it.boundsInRoot.left }
            .map { it.config.getOrNull(SemanticsProperties.TestTag) }
    }

    /**
     * The closed header's right side is exactly the 96dp pill, a 48dp shortcut and the 48dp chevron,
     * inside the header's padding; History and Terminal wait in the menu. On one row the title pill
     * runs up to the pill's 8dp gap, so no width stays reserved for a button beside it.
     */
    private fun assertTwoControlHeader(sameRow: Boolean) {
        // The fixture's long title is what the header renders, so the width checks measure it.
        compose.onNodeWithTag("chatTitle").assertTextEquals(longTitle)
        assertEquals(0, terminalTextCount())
        compose.onNodeWithTag("chatHistoryButton").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_history").assertDoesNotExist()
        compose.onNodeWithTag("chatAction_terminal").assertDoesNotExist()
        val header = compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot()
        val quick = compose.onNodeWithTag("chatAction_refresh").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("chatActionsMore").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("chatTitle").getUnclippedBoundsInRoot()
        assertEquals(48f, (quick.right - quick.left).value, 0.5f)
        assertEquals(48f, (more.right - more.left).value, 0.5f)
        assertEquals(96f, (more.right - quick.left).value, 0.5f)
        assertEquals("chevron $more ends at the header's padding $header", (header.right - 16.dp).value, more.right.value, 0.5f)
        val titleNode = compose.onNodeWithTag("chatTitle").fetchSemanticsNode().boundsInRoot
        if (sameRow) {
            // The title's 8dp end padding and the row's gap: 16dp, where a History button once kept 72dp.
            val gap = (quick.left - title.right).value
            assertTrue("title $title must fill up to the shortcut $quick, gap $gap", gap in 7.5f..24f)
            assertEquals(listOf("chatAction_refresh", "chatActionsMore"), rightControls(titleNode.right))
        } else {
            assertTrue("actions $quick sit below the title $title", quick.top >= title.bottom - 0.5.dp)
            assertEquals(listOf("chatAction_refresh", "chatActionsMore"), rightControls(0f))
        }
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
        // The menu may scroll on a short screen; both rows exist in it.
        compose.onNodeWithTag("chatActionMenu_history").assertExists()
        compose.onNodeWithTag("chatActionMenu_terminal").assertExists()
        compose.onNodeWithTag("chatActionsMore").performClick()
        compose.waitForIdle()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun aPhoneHeaderShowsOnlyTheShortcutAndChevronAtLargeFont() {
        RuntimeEnvironment.setFontScale(1.8f)
        repository.terminalAttachable = true
        withLongTitle()
        render()
        assertTwoControlHeader(sameRow = true)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun aNarrowHeaderKeepsOnlyTheShortcutAndChevronOnTheirOwnRowAtLargeFont() {
        RuntimeEnvironment.setFontScale(1.8f)
        repository.terminalAttachable = true
        withLongTitle()
        render()
        assertTwoControlHeader(sameRow = false)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun aWideHeaderHasNoHistoryButtonAndGivesItsWidthToTheTitle() {
        repository.terminalAttachable = true
        withLongTitle()
        render()
        // Wide enough that History used to get its own 48dp button and a 56dp reserve beside the pill.
        assertTrue(compose.onNodeWithTag("chatHeader").getUnclippedBoundsInRoot().let { it.right - it.left } >= 600.dp)
        assertTwoControlHeader(sameRow = true)
    }

    @Test
    @Config(qualifiers = "w1300dp-h800dp")
    fun aUsedHistoryBecomesTheOneShortcutAndNeverAThirdIcon() {
        render()
        compose.onNodeWithTag("chatAction_history").assertDoesNotExist()
        runFromMenu(ChatAction.HISTORY)
        assertEquals(listOf(ChatAction.HISTORY), settings.recordedActions)
        // History follows the last-used rule: it replaces the shortcut, beside the chevron only.
        compose.onNodeWithTag("chatAction_history").assertIsDisplayed()
        compose.onNodeWithTag("chatAction_refresh").assertDoesNotExist()
        compose.onNodeWithTag("chatHistoryButton").assertDoesNotExist()
        val history = compose.onNodeWithTag("chatAction_history").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("chatActionsMore").getUnclippedBoundsInRoot()
        assertEquals(96f, (more.right - history.left).value, 0.5f)
        // The open History pane narrows the chat; a narrow header puts the pill on a row of its own.
        val title = compose.onNodeWithTag("chatTitle").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("chatAction_history").fetchSemanticsNode().boundsInRoot
        val fromX = if (pill.top < title.bottom) title.right else 0f
        assertEquals(
            listOf("chatAction_history", "chatActionsMore"),
            rightControls(fromX, quickTag = "chatAction_history"),
        )
    }

    @Test
    fun selectedShortcutSurvivesLeavingAndReenteringTheChat() {
        render()
        runFromMenu(ChatAction.SETTINGS)
        compose.onNodeWithTag("chatActionsMore").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("chatAction_settings").assertIsDisplayed()
        assertEquals(listOf(ChatAction.SETTINGS), settings.recordedActions)
    }
}
