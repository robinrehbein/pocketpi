package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChangesPaneTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val snapshot = "Q2hhbmdlc1NuYXBzaG90MQ"

    private val status =
        GitStatus(
            GitBase.SESSION,
            snapshot,
            listOf(
                GitFile("big.kt", GitChange.MODIFIED, 2000, 0),
                GitFile("logo.png", GitChange.ADDED, binary = true),
                GitFile("dump.sql", GitChange.ADDED, omitted = GitOmitted.TOO_LARGE),
                GitFile("notes.md", GitChange.ADDED, omitted = GitOmitted.BASE_UNAVAILABLE),
            ),
            since = GitSince(true, 1_790_000_000_000),
        )

    private val bigPatch =
        "@@ -0,0 +1,2000 @@\n" + (1..2000).joinToString("") { "+line $it\n" }

    private class Recorder {
        val prefills = mutableListOf<String>()
        val reviews = mutableListOf<String>()
        val comments = mutableListOf<ReviewComment>()
        val opened = mutableListOf<String?>()
        var saveResult = true
        var prefillResult = true
    }

    private fun render(initial: ChangesState, recorder: Recorder) {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    state,
                    ChangesActions(
                        onClose = {},
                        onSelectBase = {},
                        onReload = {},
                        onOpenFile = { recorder.opened += it },
                        onSetComment = { comment ->
                            recorder.saveResult.also { saved ->
                                if (saved)
                                    state = state.copy(comments = state.comments.filterNot { it.sameLine(comment) } + comment)
                            }
                        },
                        onRemoveComment = {},
                        onUseReview = { recorder.reviews += it; recorder.prefillResult },
                        onPrefill = { recorder.prefills += it; recorder.prefillResult },
                    ),
                )
            }
        }
    }

    @Test
    fun fileListShowsSinceLabelKindsAndStates() {
        val recorder = Recorder()
        render(ChangesState("s", loading = false, status = status, log = GitLog()), recorder)
        val time =
            android.text.format.DateUtils.formatDateTime(
                context,
                1_790_000_000_000,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                    android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
            )
        compose.onNodeWithText(context.getString(R.string.remote_changes_since_first_contact, time)).assertExists()
        // Tiles are lazy: bring each file into view before checking its detail.
        listOf(
            "notes.md" to context.getString(R.string.remote_changes_base_content_gone),
            "logo.png" to context.getString(R.string.remote_changes_binary),
            "dump.sql" to context.getString(R.string.remote_changes_too_large),
            "big.kt" to "+2000",
        ).forEach { (path, detail) ->
            compose.onNodeWithTag("changesList").performScrollToNode(androidx.compose.ui.test.hasText(path))
            compose.onNodeWithText(detail, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("big.kt", useUnmergedTree = true).performClick()
        assertEquals(listOf<String?>("big.kt"), recorder.opened)
    }

    @Test
    fun theTimeOfTheShownStatusShowsOnlyWhileOffline() {
        val at = System.currentTimeMillis()
        var offline by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    ChangesState("s", loading = false, status = status, log = GitLog(), fetchedAt = at),
                    ChangesActions({}, {}, {}, {}, { true }, {}, { true }, { true }),
                    offline = offline,
                )
            }
        }
        val updated = context.getString(R.string.remote_changes_updated_at, updatedTime(context, at))
        compose.onNodeWithTag("changesUpdated").assertDoesNotExist()
        offline = true
        compose.onNodeWithTag("changesUpdated").assertTextEquals(updated)
        offline = false
        compose.onNodeWithTag("changesUpdated").assertDoesNotExist()
    }

    @Test
    fun aStatusThatCouldNotBeReadAgainSaysWhenItWasReadAndOffersAReload() {
        val at = System.currentTimeMillis()
        var reloads = 0
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    ChangesState("s", loading = false, status = status, log = GitLog(), fetchedAt = at, stale = true),
                    ChangesActions({}, {}, { reloads++ }, {}, { true }, {}, { true }, { true }),
                )
            }
        }
        compose.onNodeWithTag("changesUpdated")
            .assertTextEquals(context.getString(R.string.remote_changes_stale_at, updatedTime(context, at)))
        compose.onNodeWithText(context.getString(R.string.remote_changes_retry)).performClick()
        assertEquals(1, reloads)
    }

    @Test
    fun longPressShowsDiffPeekWithoutOpeningFile() {
        val recorder = Recorder()
        val excerpt = "@@ -1 +1 @@\n-old\n+new\n context"
        render(ChangesState("s", loading = false, status = status.copy(files = listOf(status.files.first())),
            log = GitLog(), previews = mapOf("big.kt" to FileTilePreview(loading = false, content = excerpt))), recorder)
        compose.onNodeWithTag("changesList").performScrollToNode(androidx.compose.ui.test.hasText("big.kt"))
        compose.onNodeWithTag("changesFile").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("filePeek").assertExists()
        compose.onNodeWithText(excerpt, useUnmergedTree = true).assertExists()
        assertTrue(recorder.opened.isEmpty())
        compose.onNodeWithTag("filePeekDismiss").performClick()
        compose.onNodeWithTag("filePeek").assertDoesNotExist()
    }

    @Test
    fun longDiffIsLazyAndACommentBecomesTheReviewPrompt() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, bigPatch, binary = false, truncated = false),
            ),
            recorder,
        )
        val composed = compose.onAllNodesWithTag("changesDiffLine").fetchSemanticsNodes().size
        assertTrue("lazy list composed $composed rows", composed in 1..200)

        compose.onNodeWithTag("changesDiff").performScrollToNode(hasTestTag("changesDiffLine"))
        // A click picks the line; the comment opens from its gutter button.
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onAllNodesWithTag("changesGutterComment").onFirst().performClick()
        compose.onNodeWithTag("changesCommentField").performTextInput("Why this line?")
        compose.onNodeWithTag("changesCommentSave").performClick()
        compose.onNodeWithText("Why this line?").assertExists()

        compose.onNodeWithTag("changesUseReview").performClick()
        assertEquals(
            listOf(
                context.getString(R.string.remote_changes_review_intro) + "\n\n" +
                    context.getString(
                        R.string.remote_changes_review_line,
                        "big.kt",
                        1,
                        context.getString(R.string.remote_changes_review_base_session),
                    ) +
                    "\n> line 1\nWhy this line?"
            ),
            recorder.reviews,
        )
        assertTrue(recorder.prefills.isEmpty())
    }

    @Test
    fun ctrlAAndCtrlCCopyThePickedDiffLinesWithoutTheirPrefixes() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, "@@ -1,2 +1,3 @@\n ctx\n-old\n+new\n+more\n", binary = false, truncated = false),
            ),
            recorder,
        )
        // A click picks the line and gives the pane the keyboard.
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onNodeWithTag("changesDiff").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            pressKey(Key.C)
            keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("the context, removed and added lines are all in: $copied", copied.lines().containsAll(listOf("ctx", "old", "new", "more")))
        assertTrue("no line keeps a diff marker: $copied", copied.lines().none { it.startsWith("+") || it.startsWith("-") })
    }

    @Test
    fun quickActionsOnlyPrefill() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "logo.png",
                diff = GitDiff("logo.png", snapshot, "", binary = true, truncated = false),
            ),
            recorder,
        )
        compose.onNodeWithText(context.getString(R.string.remote_changes_diff_binary)).assertExists()
        compose.onNodeWithTag("changesActionRevert").performClick()
        compose.onNodeWithTag("changesActionExplain").performClick()
        assertEquals(
            listOf(
                context.getString(
                    R.string.remote_changes_prompt_revert,
                    "logo.png",
                    context.getString(R.string.remote_changes_prompt_base_session),
                ),
                context.getString(R.string.remote_changes_prompt_explain, "logo.png"),
            ),
            recorder.prefills,
        )
        assertTrue(recorder.reviews.isEmpty())
    }

    @Test
    fun failedSaveKeepsTheDialogAndFailedPrefillIsShown() {
        val recorder = Recorder().apply {
            saveResult = false
            prefillResult = false
        }
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, "@@ -1 +1 @@\n-a\n+b\n", binary = false, truncated = false),
            ),
            recorder,
        )
        // A click picks the line; the comment opens from its gutter button.
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onAllNodesWithTag("changesGutterComment").onFirst().performClick()
        compose.onNodeWithTag("changesCommentField").performTextInput("Note")
        compose.onNodeWithTag("changesCommentSave").performClick()
        compose.onNodeWithTag("changesCommentError").assertExists()
        compose.onNodeWithTag("changesCommentField").assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_changes_comment_cancel)).performClick()
        compose.onNodeWithTag("changesActionTests").performClick()
        compose.onNodeWithTag("changesPrefillFailed").assertExists()
    }

    @Test
    fun devChoiceIsNamedAfterTheHostsBranchAndHiddenWithoutOne() {
        val recorder = Recorder()
        val loaded = ChangesState("s", loading = false, status = status, log = GitLog())
        var state by mutableStateOf(loaded)
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    state,
                    ChangesActions(
                        onClose = {},
                        onSelectBase = {},
                        onReload = {},
                        onOpenFile = {},
                        onSetComment = { true },
                        onRemoveComment = {},
                        onUseReview = { recorder.reviews += it; true },
                        onPrefill = { recorder.prefills += it; true },
                    ),
                )
            }
        }
        // An older host names no branch: the choice stays and is called dev.
        compose.onNodeWithTag("changesBase_dev").assertTextEquals("dev")
        state = loaded.copy(devBase = GitDevBase("origin/main"))
        compose.onNodeWithTag("changesBase_dev").assertTextEquals("main")
        state = loaded.copy(devBase = GitDevBase(null))
        compose.onNodeWithTag("changesBase_dev").assertDoesNotExist()
        // Still shown while it is the base the user asked for.
        state = loaded.copy(base = GitBase.DEV, devBase = GitDevBase(null))
        compose.onNodeWithTag("changesBase_dev").assertExists()
    }

    @Test
    fun subagentGroupsListTheirFilesAndOpenAReadOnlyDiff() {
        val opened = mutableListOf<Pair<String, String>>()
        val own = GitStatus(GitBase.HEAD, snapshot, listOf(GitFile("own.kt", GitChange.MODIFIED, 1, 0)), branch = "feature")
        val subagent =
            SubagentChanges(
                "child",
                "Implementer",
                running = false,
                GitStatus(GitBase.HEAD, "child-snapshot", listOf(GitFile("lib/new.kt", GitChange.ADDED, 3, 0)), branch = "pi-agent-1"),
            )
        val sameFolder =
            SubagentChanges("same", "Helper", running = true, GitStatus(GitBase.HEAD, "s", listOf(GitFile("own.kt", GitChange.MODIFIED, 1, 0)), branch = "feature"))
        val loaded = ChangesState("s", base = GitBase.HEAD, loading = false, status = own, log = GitLog(), subagents = listOf(subagent, sameFolder))
        var state by mutableStateOf(loaded)
        compose.setContent {
            MaterialTheme {
                ChangesPane(
                    state,
                    ChangesActions(
                        onClose = {},
                        onSelectBase = {},
                        onReload = {},
                        onOpenFile = {},
                        onSetComment = { true },
                        onRemoveComment = {},
                        onUseReview = { true },
                        onPrefill = { true },
                        onOpenSubagentFile = { id, path -> opened += id to path },
                    ),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.remote_changes_this_session)).assertExists()
        // The subagent on the session's own branch gets no group.
        compose.onAllNodesWithTag("changesSubagent").assertCountEquals(1)
        compose.onNodeWithText("Implementer").assertExists()
        compose.onNodeWithText(context.getString(R.string.remote_changes_subagent_finished, "pi-agent-1")).assertExists()
        compose.onNodeWithTag("changesList").performScrollToNode(hasText("new.kt"))
        compose.onNodeWithText("new.kt").performClick()
        assertEquals(listOf("child" to "lib/new.kt"), opened)
        // Its diff carries no quick actions and takes no comments; its lines can still be picked and copied.
        state = loaded.copy(file = "lib/new.kt", fileSession = "child", diff = GitDiff("lib/new.kt", "child-snapshot", "@@ -0,0 +1 @@\n+x\n", false, false))
        compose.onNodeWithTag("changesSubagentFile").assertExists()
        compose.onNodeWithTag("changesActionRevert").assertDoesNotExist()
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onNodeWithTag("changesGutterComment").assertDoesNotExist()
    }

    @Test
    fun devPromptsNameTheBranch() {
        val main = GitDevBase("origin/main")
        assertEquals(
            context.getString(
                R.string.remote_changes_prompt_revert,
                "a.kt",
                context.getString(R.string.remote_changes_prompt_base_dev, "origin/main"),
            ),
            quickActionPrompt(context, QuickAction.REVERT, "a.kt", GitBase.DEV, main),
        )
        val comment = ReviewComment("a.kt", null, 3, "x", "why?", GitBase.DEV)
        assertTrue(reviewPrompt(context, listOf(comment), main).contains(context.getString(R.string.remote_changes_review_base_dev, "origin/main")))
        assertTrue(reviewPrompt(context, listOf(comment)).contains(context.getString(R.string.remote_changes_review_base_dev, "dev")))
    }

    @Test
    fun commentsShowOnlyUnderTheirOwnBase() {
        val recorder = Recorder()
        render(
            ChangesState(
                "s",
                loading = false,
                status = status,
                file = "big.kt",
                diff = GitDiff("big.kt", snapshot, "@@ -1 +1 @@\n-a\n+b\n", binary = false, truncated = false),
                comments = listOf(
                    ReviewComment("big.kt", null, 1, "b", "Head note", GitBase.HEAD),
                    ReviewComment("big.kt", null, 1, "b", "Session note", GitBase.SESSION),
                ),
            ),
            recorder,
        )
        compose.onNodeWithText("Session note").assertExists()
        compose.onNodeWithText("Head note").assertDoesNotExist()
    }

    @Test
    fun failedPrefillShowsAfterOpeningAFile() {
        var prefills = 0
        var state by mutableStateOf(ChangesState("s", loading = false, status = status, log = GitLog()))
        val actions =
            ChangesActions(
                onClose = {},
                onSelectBase = {},
                onReload = {},
                onOpenFile = {},
                onSetComment = { false },
                onRemoveComment = {},
                onUseReview = { false },
                onPrefill = {
                    prefills++
                    false
                },
            )
        compose.setContent { MaterialTheme { ChangesPane(state, actions) } }
        compose.onNodeWithTag("changesPrefillFailed").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                file = "logo.png",
                diff = GitDiff("logo.png", snapshot, "", binary = true, truncated = false),
            )
        }
        compose.onNodeWithTag("changesActionTests").performClick()
        compose.onNodeWithTag("changesPrefillFailed").assertExists()
        assertEquals(1, prefills)
        // Another file starts without the old error.
        compose.runOnIdle { state = state.copy(file = "big.kt", diff = null) }
        compose.onNodeWithTag("changesPrefillFailed").assertDoesNotExist()
    }

    // ---- Review mode, the file list beside the diff and the Ship menu ------------------------

    private val reviewStatus =
        GitStatus(
            GitBase.SESSION,
            snapshot,
            listOf(GitFile("src/a.kt", GitChange.MODIFIED, 1, 1), GitFile("src/b.kt", GitChange.MODIFIED, 1, 1)),
            branch = "feature/review",
            devBase = GitDevBase("origin/main"),
        )

    private val reviewPatch = "@@ -1,2 +1,2 @@\n keep\n-old\n+new\n@@ -9 +9 @@\n-x\n+y\n"

    private fun reviewState(file: String? = "src/a.kt") =
        ChangesState(
            "s",
            loading = false,
            status = reviewStatus,
            log = GitLog(),
            devBase = reviewStatus.devBase,
            file = file,
            diff = file?.let { GitDiff(it, snapshot, reviewPatch, binary = false, truncated = false) },
        )

    /** The pane inside a two-pane layout ([twoPane]) and a chat's keys ([keys]), as in the app. */
    private fun renderInLayout(
        initial: ChangesState,
        recorder: Recorder,
        twoPane: TwoPaneState? = null,
        keys: ChatKeyTarget? = null,
        projectName: String? = null,
        menus: Boolean = false,
    ) {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalTwoPane provides twoPane, LocalChatKeyTarget provides keys) {
                  val pane: @androidx.compose.runtime.Composable () -> Unit = {
                    ChangesPane(
                        state,
                        ChangesActions(
                            onClose = {},
                            onSelectBase = {},
                            onReload = {},
                            onOpenFile = { path ->
                                recorder.opened += path
                                state = state.copy(file = path, diff = path?.let { GitDiff(it, snapshot, reviewPatch, false, false) })
                            },
                            onSetComment = { recorder.comments += it; true },
                            onRemoveComment = {},
                            onUseReview = { recorder.reviews += it; recorder.prefillResult },
                            onPrefill = { recorder.prefills += it; recorder.prefillResult },
                        ),
                        projectName = projectName,
                    )
                  }
                  if (menus) PointerMenuHost(pane) else pane()
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun shipPromptsPrefillTheComposerAndNeverSend() {
        val recorder = Recorder()
        renderInLayout(reviewState(file = null), recorder)
        compose.onNodeWithTag("changesShip").performClick()
        val labels =
            listOf(
                "changesShip_commit" to context.getString(R.string.remote_ship_commit),
                "changesShip_commit_push" to context.getString(R.string.remote_ship_commit_push),
                "changesShip_branch" to context.getString(R.string.remote_ship_branch),
                "changesShip_pull_request" to context.getString(R.string.remote_ship_pull_request, "main"),
                "changesShip_rebase" to context.getString(R.string.remote_ship_rebase, "main"),
            )
        for ((tag, label) in labels) compose.onNodeWithTag(tag).assertTextEquals(label)
        compose.onNodeWithTag("changesShip_pull_request").performClick()
        // Only the composer gets the prompt: nothing is sent and no review is used.
        assertEquals(listOf(context.getString(R.string.remote_ship_prompt_pull_request, "main")), recorder.prefills)
        assertTrue(recorder.reviews.isEmpty())
        compose.onNodeWithTag("changesShip").performClick()
        compose.onNodeWithTag("changesShip_rebase").performClick()
        // A rebase names the full ref, so pi does not rebase on a lagging local branch.
        assertEquals(context.getString(R.string.remote_ship_prompt_rebase, "origin/main"), recorder.prefills.last())
    }

    @Test
    fun shipPromptsNeedADevBaseOnlyForAPullRequestAndARebase() {
        assertEquals(ShipAction.entries, shipActions(GitDevBase("origin/main")))
        assertEquals(listOf(ShipAction.COMMIT, ShipAction.COMMIT_PUSH, ShipAction.BRANCH), shipActions(GitDevBase(null)))
        assertEquals(listOf(ShipAction.COMMIT, ShipAction.COMMIT_PUSH, ShipAction.BRANCH), shipActions(null))
        // The commit message and the branch name are the user's to type at the end.
        assertTrue(shipPrompt(context, ShipAction.COMMIT, null).endsWith(": "))
        assertTrue(shipPrompt(context, ShipAction.BRANCH, null).endsWith(": "))
    }

    @Test
    fun aNarrowHeaderKeepsTheShipMenuBehindMore() {
        val recorder = Recorder()
        renderInLayout(reviewState(file = null).copy(devBase = GitDevBase(null), status = reviewStatus.copy(devBase = GitDevBase(null))), recorder)
        compose.onNodeWithTag("changesShip").assertDoesNotExist()
        compose.onNodeWithTag("changesMoreActions").performClick()
        compose.onNodeWithTag("changesShip").performClick()
        // Without a branch to compare against there is nothing to open a PR against.
        compose.onNodeWithTag("changesShip_pull_request").assertDoesNotExist()
        compose.onNodeWithTag("changesShip_commit_push").performClick()
        assertEquals(listOf(context.getString(R.string.remote_ship_prompt_commit_push)), recorder.prefills)
    }

    @Test
    fun theBranchShowsInTheHeaderInsteadOfTheOverview() {
        renderInLayout(reviewState(file = null), Recorder(), projectName = "noah-monorepo")
        val branch = context.getString(R.string.remote_changes_branch, "feature/review")
        compose.onNodeWithTag("changesSubtitle")
            .assertTextEquals(context.getString(R.string.remote_review_subtitle, "noah-monorepo", branch))
        compose.onNodeWithText(branch).assertDoesNotExist()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aWidePaneKeepsTheFileListBesideTheDiff() {
        val recorder = Recorder()
        renderInLayout(reviewState(), recorder)
        compose.onNodeWithTag("changesFileList").assertExists()
        compose.onNodeWithTag("changesDiff").assertExists()
        compose.onAllNodesWithTag("changesFileListItem").assertCountEquals(2)
        // Down on the open file's row opens the next one as focus moves there.
        compose.onAllNodesWithTag("changesFileListItem").onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onAllNodesWithTag("changesFileListItem").onFirst()
            .performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        assertEquals(listOf<String?>("src/b.kt"), recorder.opened)
        compose.onAllNodesWithTag("changesFileListItem")[0].performClick()
        assertEquals("src/a.kt", recorder.opened.last())
        // Outside review mode the diff stays unified, however wide.
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(0)
    }

    @Test
    fun aNarrowPaneShowsTheDiffAlone() {
        renderInLayout(reviewState(), Recorder())
        compose.onNodeWithTag("changesDiff").assertExists()
        compose.onNodeWithTag("changesFileList").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1400dp-h900dp")
    fun reviewModeSplitsAWideDiff() {
        val twoPane = TwoPaneState().apply { enterReview() }
        renderInLayout(reviewState(), Recorder(), twoPane)
        // Two hunks, a context line and two pairs.
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(5)
        compose.onNodeWithTag("changesReview").assertIsOn()
        compose.onNodeWithTag("changesReview").performClick()
        assertTrue(!twoPane.reviewing)
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(0)
        compose.onNodeWithTag("changesReview").performClick()
        assertTrue(twoPane.reviewing)
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(5)
    }

    @Test
    @Config(qualifiers = "w940dp-h900dp")
    fun aHunkHeaderNeverPullsTheSplitColumnsBackSideways() {
        // Lines wider than a column; a hunk header has about twice a column's room for them.
        val long = "x".repeat(600)
        val patch = "@@ -1,1 +1,1 @@\n-$long a\n+$long b\n@@ -9,1 +9,1 @@\n-$long c\n+$long d\n"
        val lines = patchDiff(patch)
        val rows = checkNotNull(splitDiff(patch, lines))
        assertEquals(4, rows.size)
        val scroll = androidx.compose.foundation.ScrollState(0)
        // The second hunk's header comes into view last, as it does while the list scrolls down.
        var shown by mutableStateOf(rows.size - 2)
        compose.setContent {
            MaterialTheme {
                val rowStyle = rememberDiffRowStyle(lines)
                Column(Modifier.fillMaxWidth()) {
                    SplitDiffHeading()
                    rows.take(shown).forEach { SplitDiffItem(it, lines, rowStyle, scroll, wrap = false) }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("the columns scroll sideways", scroll.maxValue > 0)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(scroll.maxValue) } }
        val end = scroll.value
        shown = rows.size
        compose.waitForIdle()
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(4)
        assertEquals(end, scroll.value)
        assertTrue(scroll.maxValue >= end)
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun reviewModeKeepsANarrowerDiffUnified() {
        renderInLayout(reviewState(), Recorder(), TwoPaneState().apply { enterReview() })
        // 1000dp less the file list leave the diff under the split's width.
        compose.onNodeWithTag("changesFileList").assertExists()
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(0)
        compose.onAllNodesWithTag("changesDiffLine").onFirst().assertExists()
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun reviewKeysStepThroughFilesCommentAndUseTheReview() {
        val recorder = Recorder()
        val twoPane = TwoPaneState().apply { enterReview() }
        val keys = ChatKeyTarget().apply { reviewing = { twoPane.reviewing } }
        renderInLayout(reviewState(file = null), recorder, twoPane, keys)
        // Review mode starts on the first file.
        compose.waitForIdle()
        assertEquals(listOf<String?>("src/a.kt"), recorder.opened)
        compose.runOnIdle { assertTrue(keys.reviewKey(ReviewKey.NEXT_FILE)) }
        assertEquals("src/b.kt", recorder.opened.last())
        // At the last file "n" is used up but goes nowhere.
        compose.runOnIdle { assertTrue(keys.reviewKey(ReviewKey.NEXT_FILE)) }
        assertEquals(2, recorder.opened.size)
        compose.runOnIdle { keys.reviewKey(ReviewKey.PREVIOUS_FILE) }
        assertEquals("src/a.kt", recorder.opened.last())
        compose.runOnIdle { keys.reviewKey(ReviewKey.NEXT_HUNK) }
        compose.runOnIdle { keys.reviewKey(ReviewKey.COMMENT) }
        compose.onNodeWithTag("changesCommentField").assertExists()
    }

    @Test
    fun reviewKeysWaitForReviewModeAndAFreeKeyboard() {
        val twoPane = TwoPaneState()
        val keys = ChatKeyTarget().apply { reviewing = { twoPane.reviewing } }
        var handled = 0
        keys.addReviewHandler { handled++; true }
        // Outside review mode a letter is the composer's.
        assertTrue(!keys.reviewKey(ReviewKey.NEXT_FILE))
        twoPane.enterReview()
        assertTrue(keys.reviewKey(ReviewKey.NEXT_FILE))
        assertEquals(1, handled)
        // A text field with focus takes its letters itself.
        keys.composerFocused = true
        assertTrue(!keys.reviewKey(ReviewKey.NEXT_FILE))
        keys.composerFocused = false
        keys.questionFieldFocus(Any(), focused = true, blank = true)
        assertTrue(!keys.reviewKey(ReviewKey.COMMENT))
        assertEquals(1, handled)
    }

    @Test
    fun useReviewKeyNeedsComments() {
        val recorder = Recorder()
        val twoPane = TwoPaneState().apply { enterReview() }
        val keys = ChatKeyTarget().apply { reviewing = { twoPane.reviewing } }
        val comment = ReviewComment("src/a.kt", null, 1, "new", "Rename this", GitBase.SESSION)
        renderInLayout(reviewState().copy(comments = listOf(comment)), recorder, twoPane, keys)
        compose.runOnIdle { keys.reviewKey(ReviewKey.USE_REVIEW) }
        assertEquals(listOf(reviewPrompt(context, listOf(comment), reviewStatus.devBase)), recorder.reviews)
    }

    // ---- Select, copy, zoom and comment ----------------------------------------------------------

    private fun clipboardText() =
        context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()

    private fun ctrl(press: Key): androidx.compose.ui.test.SemanticsNodeInteraction.() -> Unit = {
        performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(press)
            keyUp(Key.CtrlLeft)
        }
    }

    private fun fontSizeOf(text: String): Float {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.fontSize.value
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aClickPicksTheLineAndCThenCommentsOnThatLine() {
        val recorder = Recorder()
        val twoPane = TwoPaneState().apply { enterReview() }
        val keys = ChatKeyTarget().apply { reviewing = { twoPane.reviewing } }
        renderInLayout(reviewState(), recorder, twoPane, keys)
        // Rows: keep, old, new, then the second hunk's x and y; the click takes the second hunk's y.
        compose.onAllNodesWithTag("changesDiffLine")[4].performClick()
        compose.runOnIdle { assertTrue(keys.reviewKey(ReviewKey.COMMENT)) }
        compose.onNodeWithTag("changesCommentField").performTextInput("Second hunk")
        compose.onNodeWithTag("changesCommentSave").performClick()
        assertEquals(listOf(9), recorder.comments.map { it.newLine })
        assertEquals(listOf("y"), recorder.comments.map { it.quoted })
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun theDiffMenuCommentsAndCopiesThePickedLinesWithTheirPrefixes() {
        val recorder = Recorder()
        renderInLayout(reviewState(), recorder, menus = true)
        val rows = compose.onAllNodesWithTag("changesDiffLine")
        rows[1].performClick()
        // Shift on a line number extends the pick from its anchor to that line.
        val newNumber = compose.onAllNodesWithTag("changesGutter:4", useUnmergedTree = true).onFirst()
        newNumber.performKeyInput { keyDown(Key.ShiftLeft) }
        newNumber.performMouseInput { click() }
        newNumber.performKeyInput { keyUp(Key.ShiftLeft) }
        rows[2].performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_copy_with_prefix").performClick()
        assertEquals("-old\n+new", clipboardText())
        // Ctrl+C without a prefix, from the pane.
        compose.onNodeWithTag("changesDiff").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.C)
            keyUp(Key.CtrlLeft)
        }
        assertEquals("old\nnew", clipboardText())
        rows[2].performMouseInput { rightClick() }
        compose.onNodeWithTag("menu_diff_comment").performClick()
        compose.onNodeWithTag("changesCommentField").assertExists()
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun ctrlPlusMinusAndAPinchZoomTheDiffAndNotFiles() {
        CodeZoom.reset()
        renderInLayout(reviewState(), Recorder())
        val before = fontSizeOf("keep")
        compose.onAllNodesWithTag("changesDiffLine").onFirst().performClick()
        compose.onNodeWithTag("changesDiff").apply(ctrl(Key.Equals))
        compose.waitForIdle()
        assertEquals(CodeZoom.STEP, CodeZoom.scale(CodeZoom.CHANGES), 0.001f)
        assertEquals(1f, CodeZoom.scale(CodeZoom.FILES), 0f)
        assertTrue("the code text grew", fontSizeOf("keep") > before)
        // A pinch changes the same value.
        compose.onNodeWithTag("changesDiff").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(100f, 200f))
            down(1, androidx.compose.ui.geometry.Offset(140f, 200f))
            move()
            moveTo(0, androidx.compose.ui.geometry.Offset(60f, 200f))
            moveTo(1, androidx.compose.ui.geometry.Offset(180f, 200f))
            move()
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue("the pinch zoomed the same value", CodeZoom.scale(CodeZoom.CHANGES) > CodeZoom.STEP)
        assertEquals(1f, CodeZoom.scale(CodeZoom.FILES), 0f)
        CodeZoom.reset()
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aPickedRowOffersTheCommentToAScreenReaderAndOnlySelectableRowsSayWhetherTheyAreSelected() {
        renderInLayout(reviewState(), Recorder())
        val row = compose.onAllNodesWithTag("changesDiffLine")[1]
        row.performClick()
        val config = row.fetchSemanticsNode().config
        assertEquals(true, config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected))
        val actions = config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf(context.getString(R.string.remote_changes_comment_add)), actions.map { it.label })
    }

    @Test
    fun aDiffThatCannotPickLinesSaysNothingAboutSelection() {
        compose.setContent { MaterialTheme { DiffView(listOf(DiffLine(DiffKind.CONTEXT, "code", 1, 1))) } }
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
            .assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1400dp-h900dp")
    fun aSplitReviewCommentsFromTheButtonOnThePickedCell() {
        val recorder = Recorder()
        val twoPane = TwoPaneState().apply { enterReview() }
        renderInLayout(reviewState(), recorder, twoPane)
        compose.onAllNodesWithTag("changesSplitRow").assertCountEquals(5)
        compose.onNodeWithTag("changesGutterComment").assertDoesNotExist()
        // A tap picks the cell and brings its comment button, with no keyboard or mouse.
        compose.onAllNodesWithTag("changesDiffLine")[1].performClick()
        compose.onAllNodesWithTag("changesGutterComment").onFirst().performClick()
        compose.onNodeWithTag("changesCommentField").performTextInput("Split")
        compose.onNodeWithTag("changesCommentSave").performClick()
        assertEquals(1, recorder.comments.size)
    }

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aShiftClickOnTheCodeTextOfARowSelectsTextAndNeverCrashes() {
        renderInLayout(reviewState(), Recorder())
        compose.onAllNodesWithTag("changesDiff").onFirst().performKeyInput { keyDown(Key.ShiftLeft) }
        compose.onAllNodesWithText("keep", useUnmergedTree = true).onFirst().performMouseInput { click() }
        compose.onAllNodesWithTag("changesDiff").onFirst().performKeyInput { keyUp(Key.ShiftLeft) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("changesDiffLine").onFirst().assertExists()
    }

    @Test
    fun aFailedStatusUsesTheSharedFailureNotice() {
        render(ChangesState("s", loading = false, failure = ChangesFailure.OFFLINE), Recorder())
        compose.onNode(hasAnyAncestor(hasTestTag("changesFailure")) and hasText(context.getString(R.string.remote_changes_failure_offline)))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNode(hasAnyAncestor(hasTestTag("changesFailure")) and hasText(context.getString(R.string.remote_changes_retry))).assertIsDisplayed()
    }
}
