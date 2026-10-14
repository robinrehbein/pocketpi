package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val texts = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private val intros = mutableListOf<String?>()
    private var started = 0
    private var saves = 0
    private var discards = 0
    private var reloads = 0
    private var leaves = 0
    private var stays = 0
    /** The text the view model held when each save was asked for. */
    private val savedTexts = mutableListOf<String?>()
    private var prompt: EditorPrompt? = EditorPrompt.Ready("the diff prompt")

    private val file = OpenFile("src/Main.kt", loading = false, version = "v1", size = 12, content = "hello\nworld\n")

    private fun editing(text: String = file.content, conflict: Boolean = false) =
        FilesState("s", loading = false, file = file, editor = FileEditor(file.path, "v1", file.content, text, conflict = conflict))

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun render(initial: FilesState, canEdit: Boolean = true): (FilesState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                FilesPane(
                    state,
                    FilesActions(
                        onClose = {},
                        onOpenDir = {},
                        onOpenFile = {},
                        onLoadMore = {},
                        onReload = {},
                        onRequestPreview = {},
                        onShowPeek = { _, _ -> },
                        onDismissPeek = {},
                        onSelectLines = {},
                        onSend = {
                            sent += it
                            true
                        },
                        onStartEditing = { started++ },
                        onEditorText = { texts += it },
                        onSaveEditor = {
                            saves++
                            savedTexts += texts.lastOrNull()
                        },
                        onDiscardEditor = { discards++ },
                        onReloadAfterConflict = { reloads++ },
                        onEditorPrompt = {
                            synchronized(intros) { intros += it }
                            prompt
                        },
                        onConfirmLeave = { leaves++ },
                        onCancelLeave = { stays++ },
                    ),
                    canEdit = canEdit,
                )
            }
        }
        return { state = it }
    }

    /** Waits until the view model got a text that passes [check]; pushes follow a pause in typing. */
    private fun awaitText(check: (String) -> Boolean) {
        compose.waitUntil(5_000) { texts.lastOrNull()?.let(check) == true }
    }

    @Test
    fun editIsNotOfferedWhenTheFileIsNotEditable() {
        render(FilesState("s", loading = false, file = file), canEdit = false)
        compose.onNodeWithTag("filesSendFile").assertIsDisplayed()
        compose.onNodeWithTag("filesEdit").assertDoesNotExist()
    }

    @Test
    fun editOpensTheEditorInPlaceOfTheFile() {
        val update = render(FilesState("s", loading = false, file = file))
        compose.onNodeWithTag("filesEdit").performClick()
        assertEquals(1, started)
        // The view model opens the editor; the pane swaps the file view for it.
        update(editing())
        compose.onNodeWithTag("filesEditor").assertIsDisplayed()
        compose.onNodeWithTag("filesFile").assertDoesNotExist()
        compose.onNodeWithTag("filesEdit").assertDoesNotExist()
        compose.onNodeWithTag("filesEditorTitle").assertTextEquals("Main.kt")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun typingGoesToTheViewModelAndTheTitleMarksAChange() {
        val update = render(editing())
        compose.onNodeWithTag("filesEditorText").performTextReplacement("hello\nthere\n")
        awaitText { it == "hello\nthere\n" }
        // The mark follows the field itself, not a round trip through the view model.
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("filesEditorTitle").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null } != null
        }
        // The dot is not read out; the state description says it instead.
        compose.onNodeWithTag("filesEditorTitle").assertTextEquals("Main.kt")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, compose.activity.getString(R.string.remote_files_editor_changed)))
        update(editing("hello\nthere\n"))
        compose.onNodeWithTag("filesEditorTitle").assertTextEquals("Main.kt")
    }

    @Test
    fun ctrlSSavesTheLatestText() {
        render(editing())
        val field = compose.onNodeWithTag("filesEditorText")
        field.requestFocus()
        // No time passes, so the pause-driven push cannot run: only the save itself hands the text over.
        compose.mainClock.autoAdvance = false
        field.performTextInput("x")
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        assertEquals(1, saves)
        assertEquals(listOf<String?>("xhello\nworld\n"), savedTexts)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun ctrlSDoesNothingWithoutAChange() {
        render(editing())
        val field = compose.onNodeWithTag("filesEditorText")
        field.requestFocus()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        compose.waitForIdle()
        assertEquals(0, saves)
    }

    @Test
    fun tabIndentsAndCtrlZUndoesIt() {
        render(editing())
        val field = compose.onNodeWithTag("filesEditorText")
        field.requestFocus()
        // The cursor starts at the top of the file.
        field.performKeyInput { pressKey(Key.Tab) }
        awaitText { it == "    hello\nworld\n" }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        awaitText { it == "hello\nworld\n" }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        awaitText { it == "    hello\nworld\n" }
    }

    @Test
    fun findCountsMatchesStepsAndEscapeClosesIt() {
        render(editing())
        val field = compose.onNodeWithTag("filesEditorText")
        field.requestFocus()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        compose.onNodeWithTag("filesEditorFindQuery").performTextInput("o")
        compose.onNodeWithTag("filesEditorFindCount").assertTextEquals("1 of 2")
        compose.onNodeWithTag("filesEditorFindNext").performClick()
        compose.onNodeWithTag("filesEditorFindCount").assertTextEquals("2 of 2")
        compose.onNodeWithTag("filesEditorFindNext").performClick()
        compose.onNodeWithTag("filesEditorFindCount").assertTextEquals("1 of 2")
        compose.onNodeWithTag("filesEditorFindQuery").performTextInput("x")
        compose.onNodeWithTag("filesEditorFindCount").assertTextEquals("No matches")
        compose.onNodeWithTag("filesEditorFindQuery").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("filesEditorFindQuery").assertDoesNotExist()
    }

    @Test
    fun backClosesFindThenAsksBeforeDroppingChanges() {
        render(editing("changed\n"))
        compose.onNodeWithTag("filesEditorFind").performClick()
        compose.onNodeWithTag("filesEditorFindQuery").assertIsDisplayed()
        back()
        compose.onNodeWithTag("filesEditorFindQuery").assertDoesNotExist()
        compose.onNodeWithTag("filesEditorDiscard").assertDoesNotExist()
        // The field was seeded with the changed text, so leaving asks first.
        back()
        compose.onNodeWithTag("filesEditorKeep").performClick()
        assertEquals(0, discards)
        compose.onNodeWithTag("filesEditor").assertIsDisplayed()
        back()
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        assertEquals(1, discards)
    }

    @Test
    fun backWithoutChangesLeavesAtOnce() {
        render(editing())
        back()
        compose.onNodeWithTag("filesEditorDiscard").assertDoesNotExist()
        assertEquals(1, discards)
    }

    @Test
    fun sendToAgentNeedsAChangeAndFillsTheComposer() {
        val update = render(editing())
        compose.onNodeWithTag("filesEditorSend").assertIsNotEnabled()
        update(editing("changed\n"))
        compose.onNodeWithTag("filesEditorSend").assertIsEnabled().performClick()
        // The prompt is built off the main thread.
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf("the diff prompt"), sent)
        assertEquals(compose.activity.getString(R.string.remote_files_editor_intro, "src/Main.kt"), synchronized(intros) { intros.single() })
        prompt = EditorPrompt.TooLarge
        compose.onNodeWithTag("filesEditorSend").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("filesEditorNotice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filesEditorNotice").assertIsDisplayed()
        assertEquals(1, sent.size)
    }

    @Test
    fun theConflictDialogReloadsSendsOrKeepsEditing() {
        val update = render(editing("mine\n", conflict = true))
        compose.onNodeWithTag("filesEditorConflictReload").performClick()
        assertEquals(1, reloads)
        compose.onNodeWithTag("filesEditorConflictSend").performClick()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf("the diff prompt"), sent)
        compose.onNodeWithTag("filesEditorConflictSend").assertDoesNotExist()
        // A later save that conflicts again shows the dialog again.
        update(editing("mine\n").copy(editor = editing("mine\n").editor!!.copy(saving = true, conflict = true)))
        compose.waitForIdle()
        update(editing("mine\n", conflict = true))
        compose.onNodeWithTag("filesEditorConflictKeep").performClick()
        compose.onNodeWithTag("filesEditorConflictKeep").assertDoesNotExist()
        assertEquals(1, reloads)
        assertEquals(1, sent.size)
    }

    @Test
    fun saveFailuresOfferRetryAndASaveSaysSo() {
        val update = render(editing("changed\n"))
        val failed = editing("changed\n").let { it.copy(editor = it.editor!!.copy(saveFailure = FilesFailure.BUSY)) }
        update(failed)
        compose.onNodeWithTag("filesEditorSaveFailure").assertIsDisplayed()
        compose.onNodeWithTag("filesEditorRetry").performClick()
        assertEquals(1, saves)
        update(editing().let { it.copy(editor = it.editor!!.copy(savedAt = 1)) })
        compose.onNodeWithTag("filesEditorSaved").assertIsDisplayed()
        compose.onNodeWithTag("filesEditorSaveFailure").assertDoesNotExist()
        // A path the host refuses says so and offers no retry.
        update(editing("changed\n").let { it.copy(editor = it.editor!!.copy(saveFailure = FilesFailure.INVALID_PATH)) })
        compose.onNodeWithTag("filesEditorSaveFailure")
            .assertTextEquals(compose.activity.getString(R.string.remote_files_save_failed_invalid_path))
        compose.onNodeWithTag("filesEditorRetry").assertDoesNotExist()
        // So does a file grown too large to save.
        update(editing("changed\n").let { it.copy(editor = it.editor!!.copy(saveFailure = FilesFailure.TOO_LARGE)) })
        compose.onNodeWithTag("filesEditorSaveFailure")
            .assertTextEquals(compose.activity.getString(R.string.remote_files_save_failed_too_large))
        compose.onNodeWithTag("filesEditorRetry").assertDoesNotExist()
    }

    @Test
    fun theFooterReloadAsksBeforeDroppingChanges() {
        val update = render(editing("mine\n").let { it.copy(editor = it.editor!!.copy(saveFailure = FilesFailure.CONFLICT)) })
        compose.onNodeWithTag("filesEditorReload").performClick()
        assertEquals(0, reloads)
        compose.onNodeWithTag("filesEditorKeep").performClick()
        assertEquals(0, reloads)
        compose.onNodeWithTag("filesEditorReload").performClick()
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        assertEquals(1, reloads)
        // Without a change there is nothing to lose.
        update(editing().let { it.copy(editor = it.editor!!.copy(saveFailure = FilesFailure.CONFLICT)) })
        compose.onNodeWithTag("filesEditorReload").performClick()
        compose.onNodeWithTag("filesEditorDiscard").assertDoesNotExist()
        assertEquals(2, reloads)
    }

    @Test
    fun anActionWaitingForTheEditorAsksFirst() {
        render(editing("mine\n").let { it.copy(editor = it.editor!!.copy(leaveRequest = EditorLeave.CHANGES)) })
        compose.onNodeWithTag("filesEditorKeep").performClick()
        assertEquals(1, stays)
        compose.onNodeWithTag("filesEditorDiscard").performClick()
        assertEquals(1, leaves)
    }

    @Test
    @Config(qualifiers = "nokeys")
    fun theAccessoryRowTypesSymbolsWithoutAHardwareKeyboard() {
        render(editing())
        compose.onNodeWithTag("filesEditorText").requestFocus()
        compose.onNodeWithTag("filesEditorKey:{").performClick()
        awaitText { it == "{hello\nworld\n" }
    }

    @Test
    @Config(qualifiers = "w411dp-h600dp")
    fun typingAtTheEndOfALongFileKeepsTheCursorInView() {
        val long = (1..300).joinToString("") { "line $it\n" }
        render(FilesState("s", loading = false, file = file.copy(content = long), editor = FileEditor(file.path, "v1", long)))
        val field = compose.onNodeWithTag("filesEditorText")
        field.requestFocus()
        // It opens at the top, not at the end of the text.
        compose.waitForIdle()
        val start = compose.onNodeWithTag("filesEditorScroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        // Focus may scroll by the field's padding, never further.
        assertTrue("opened at ${start.value()}", start.value() < 48f)
        field.performTextInputSelection(TextRange(long.length))
        field.performTextInput("end")
        compose.waitForIdle()
        val range = compose.onNodeWithTag("filesEditorScroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("scrolled to ${range.value()} of ${range.maxValue()}", range.maxValue() > 0f && range.value() >= range.maxValue() - 48f)
    }
}
