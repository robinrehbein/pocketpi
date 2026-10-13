package de.joinnoah.pi.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** How long typing must pause before the colours are computed again. */
private const val HIGHLIGHT_DELAY_MS = 150L

/** How long "Saved" stays in the footer. */
private const val SAVED_NOTICE_MS = 2_000L

/** The characters of the accessory row above the soft keyboard. */
private val ACCESSORY_KEYS = listOf("{", "}", "(", ")", "[", "]", ";", ":", "=", "\"", "'")

private enum class EditorNotice { TOO_LARGE, PREFILL_FAILED }

/** Holds the field's latest layout; read when the cursor moves, never observed. */
private class LayoutHolder {
    var get: (() -> TextLayoutResult?)? = null
}

/**
 * The open file as editable text. It replaces the file view in [FilesPane], in the phone overlay
 * and the tablet inspector alike. The text starts from [FileEditor.text] and every change goes to
 * [FilesActions.onEditorText]; a save that sets a new original never resets what is typed. Back
 * closes find first, then asks before it drops unsaved changes.
 */
@Composable
internal fun CodeEditor(editor: FileEditor, actions: FilesActions, modifier: Modifier = Modifier) {
    // Seeded once per editor (FilesPane keys this by path); the loader's copy follows the field.
    val textState = remember { TextFieldState(editor.text) }
    val current by rememberUpdatedState(editor)
    val intro by rememberUpdatedState(stringResource(R.string.remote_files_editor_intro, editor.path))
    val currentActions by rememberUpdatedState(actions)
    val language = remember(editor.path) { languageFor(editor.path) }
    val colors = syntaxColors()
    val unit = remember { indentUnit(editor.original) }
    var findOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(-1) }
    var discardAsked by remember { mutableStateOf(false) }
    var conflictDismissed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<EditorNotice?>(null) }
    var focused by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<TextHighlight?>(null) }
    val editorFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val requester = remember { BringIntoViewRequester() }
    val layout = remember { LayoutHolder() }

    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { currentActions.onEditorText(it) }
    }
    LaunchedEffect(textState) { snapshotFlow { textState.text.toString() }.collect { notice = null } }
    // Colours follow the text after a pause; a result for text that changed meanwhile is dropped.
    LaunchedEffect(textState, language, colors) {
        if (language == null) {
            highlight = null
            return@LaunchedEffect
        }
        var first = true
        snapshotFlow { textState.text.toString() }.collectLatest { text ->
            if (!first) delay(HIGHLIGHT_DELAY_MS)
            first = false
            val spans = withContext(Dispatchers.Default) { highlightSpans(text, language, colors) }
            if (text.contentEquals(textState.text)) highlight = TextHighlight(text, spans)
        }
    }
    // Keeps the cursor visible while typing or moving it, once the new text is laid out.
    LaunchedEffect(textState) {
        snapshotFlow { textState.selection }.collectLatest { selection ->
            withFrameNanos {}
            val result = layout.get?.invoke() ?: return@collectLatest
            val offset = selection.end.coerceIn(0, result.layoutInput.text.length)
            val cursor = result.getCursorRect(offset)
            requester.bringIntoView(Rect(cursor.left - 48f, cursor.top, cursor.right + 48f, cursor.bottom))
        }
    }
    LaunchedEffect(Unit) { editorFocus.requestFocus() }

    val matches by remember { derivedStateOf { if (findOpen) findMatches(textState.text, query) else emptyList() } }
    val currentMatch = matches.getOrNull(matchIndex)
    val matchColor = MaterialTheme.colorScheme.tertiaryContainer
    val matchState by rememberUpdatedState(currentMatch)
    val transformation =
        remember(matchColor) {
            OutputTransformation {
                highlight?.let { shown -> for (span in spansFor(shown, asCharSequence())) addStyle(span.style, span.start, span.end) }
                val match = matchState
                if (match != null && match.last < length) addStyle(SpanStyle(background = matchColor), match.first, match.last + 1)
            }
        }

    fun flush() = currentActions.onEditorText(textState.text.toString())

    fun changed() = !textState.text.contentEquals(current.original)

    fun save() {
        flush()
        currentActions.onSaveEditor()
    }

    fun sendToAgent() {
        flush()
        when (val prompt = currentActions.onEditorPrompt(intro)) {
            is EditorPrompt.Ready -> if (!currentActions.onSend(prompt.text)) notice = EditorNotice.PREFILL_FAILED
            EditorPrompt.TooLarge -> notice = EditorNotice.TOO_LARGE
            null -> {}
        }
    }

    fun leaveEditor() {
        if (changed()) discardAsked = true
        else {
            flush()
            currentActions.onDiscardEditor()
        }
    }

    fun selectMatch(index: Int) {
        val match = matches.getOrNull(index) ?: return
        matchIndex = index
        textState.edit { selection = TextRange(match.first, match.last + 1) }
    }

    fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        val size = matches.size
        val index =
            if (matchIndex in matches.indices) (matchIndex + if (forward) 1 else size - 1) % size
            else matchAtOrAfter(matches, textState.selection.max)
        selectMatch(index)
    }

    fun openFind() {
        if (findOpen) findFocus.requestFocus() else findOpen = true
    }

    fun closeFind() {
        findOpen = false
        matchIndex = -1
        editorFocus.requestFocus()
    }

    fun indent(outdent: Boolean) {
        val selection = textState.selection
        val edit = indentLines(textState.text.toString(), selection.start, selection.end, unit, outdent) ?: return
        textState.edit {
            replace(edit.start, edit.end, edit.replacement)
            this.selection = TextRange(edit.selectionStart, edit.selectionEnd)
        }
    }

    fun insert(text: String) {
        textState.edit {
            val at = selection.min
            replace(at, selection.max, text)
            selection = TextRange(at + text.length)
        }
    }

    // Shortcuts that work wherever focus is inside the editor, the find field included.
    fun paneKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return when {
            event.isCtrlPressed && event.key == Key.S -> true.also { save() }
            event.isCtrlPressed && event.key == Key.F -> true.also { openFind() }
            event.key == Key.Escape && findOpen -> true.also { closeFind() }
            else -> false
        }
    }

    // Keys of the text itself.
    fun fieldKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val undo = textState.undoState
        return when {
            event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> true.also { if (undo.canRedo) undo.redo() }
            event.isCtrlPressed && event.key == Key.Z -> true.also { if (undo.canUndo) undo.undo() }
            event.isCtrlPressed && event.key == Key.Y -> true.also { if (undo.canRedo) undo.redo() }
            event.key == Key.Tab && !event.isCtrlPressed -> true.also { indent(outdent = event.isShiftPressed) }
            else -> false
        }
    }

    BackHandler {
        if (findOpen) closeFind() else leaveEditor()
    }
    LaunchedEffect(query, findOpen) {
        if (!findOpen) return@LaunchedEffect
        if (matches.isEmpty()) matchIndex = -1 else selectMatch(matchAtOrAfter(matches, textState.selection.min))
    }
    LaunchedEffect(findOpen) { if (findOpen) findFocus.requestFocus() }
    LaunchedEffect(editor.saving) { if (editor.saving) conflictDismissed = false }

    val style = monoTextStyle()
    val lineCount by remember { derivedStateOf { textState.text.count { it == '\n' } + 1 } }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val numberWidth = rememberLineNumberWidth(lineCount, style)
    val gutterWidth = maxOf(numberWidth, 32.dp) + 16.dp
    val maxWidth = with(LocalDensity.current) { MAX_CODE_CONTENT_WIDTH_PX.toDp() }

    Column(modifier.fillMaxSize().onPreviewKeyEvent(::paneKey).testTag("filesEditor")) {
        EditorHeader(
            editor = editor,
            changed = editor.changed,
            canUndo = textState.undoState.canUndo,
            canRedo = textState.undoState.canRedo,
            onBack = ::leaveEditor,
            onUndo = { textState.undoState.undo() },
            onRedo = { textState.undoState.redo() },
            onSave = ::save,
        )
        if (findOpen)
            FindBar(
                query = query,
                onQuery = { query = it },
                count = if (query.isEmpty()) null else matches.size,
                current = if (currentMatch != null) matchIndex + 1 else 0,
                onStep = ::step,
                onClose = ::closeFind,
                focus = findFocus,
            )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportWidth = this.maxWidth
            val viewportHeight = this.maxHeight
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("filesEditorScroll")) {
                Text(
                    numbers,
                    Modifier.padding(vertical = 8.dp).padding(horizontal = 8.dp).width(gutterWidth - 16.dp).testTag("filesEditorGutter"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = style,
                    textAlign = TextAlign.End,
                    softWrap = false,
                )
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    BasicTextField(
                        state = textState,
                        modifier =
                            Modifier.widthIn(min = (viewportWidth - gutterWidth).coerceAtLeast(0.dp), max = maxWidth)
                                .heightIn(min = viewportHeight)
                                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                                .bringIntoViewRequester(requester)
                                .focusRequester(editorFocus)
                                .onFocusChanged { focused = it.isFocused }
                                .onPreviewKeyEvent(::fieldKey)
                                .testTag("filesEditorText"),
                        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        keyboardOptions =
                            KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        outputTransformation = transformation,
                        onTextLayout = { getResult -> layout.get = getResult },
                    )
                }
            }
        }
        if (focused && !hardwareKeyboardAttached()) AccessoryRow(onIndent = ::indent, onInsert = ::insert)
        EditorFooter(
            editor = editor,
            notice = notice,
            onFind = ::openFind,
            onSend = ::sendToAgent,
            onRetry = ::save,
            onReload = currentActions.onReloadAfterConflict,
        )
    }

    if (discardAsked)
        AlertDialog(
            onDismissRequest = { discardAsked = false },
            title = { Text(stringResource(R.string.remote_files_editor_discard_title)) },
            text = { Text(stringResource(R.string.remote_files_editor_discard_text, editor.path.substringAfterLast('/'))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardAsked = false
                        currentActions.onDiscardEditor()
                    },
                    modifier = Modifier.testTag("filesEditorDiscard"),
                ) { Text(stringResource(R.string.remote_files_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardAsked = false }, modifier = Modifier.testTag("filesEditorKeep")) {
                    Text(stringResource(R.string.remote_files_editor_keep))
                }
            },
        )
    if (editor.conflict && !editor.saving && !conflictDismissed)
        AlertDialog(
            onDismissRequest = { conflictDismissed = true },
            title = { Text(stringResource(R.string.remote_files_editor_conflict_title)) },
            text = { Text(stringResource(R.string.remote_files_editor_conflict_text, editor.path.substringAfterLast('/'))) },
            confirmButton = {
                Row {
                    TextButton(onClick = currentActions.onReloadAfterConflict, modifier = Modifier.testTag("filesEditorConflictReload")) {
                        Text(stringResource(R.string.remote_files_editor_reload))
                    }
                    TextButton(
                        onClick = {
                            conflictDismissed = true
                            sendToAgent()
                        },
                        modifier = Modifier.testTag("filesEditorConflictSend"),
                    ) { Text(stringResource(R.string.remote_files_editor_send)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { conflictDismissed = true }, modifier = Modifier.testTag("filesEditorConflictKeep")) {
                    Text(stringResource(R.string.remote_files_editor_keep))
                }
            },
        )
}

@Composable
private fun EditorHeader(
    editor: FileEditor,
    changed: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
) {
    val changedLabel = stringResource(R.string.remote_files_editor_changed)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingSurface(Modifier.weight(1f, fill = false), shape = CircleShape) {
            Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(48.dp).testTag("filesEditorBack")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_files_editor_back))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        editor.path.substringAfterLast('/') + if (changed) " •" else "",
                        Modifier.testTag("filesEditorTitle").semantics { if (changed) stateDescription = changedLabel },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        editor.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FloatingSurface(shape = CircleShape) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp).testTag("filesEditorUndo")) {
                    Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.remote_files_editor_undo))
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(48.dp).testTag("filesEditorRedo")) {
                    Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.remote_files_editor_redo))
                }
                if (editor.saving) {
                    val saving = stringResource(R.string.remote_files_editor_saving)
                    CircularProgressIndicator(
                        Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = saving }.testTag("filesEditorSaving"),
                        strokeWidth = 2.dp,
                    )
                } else
                    IconButton(onClick = onSave, enabled = changed, modifier = Modifier.size(48.dp).testTag("filesEditorSave")) {
                        Icon(Icons.Default.Save, stringResource(R.string.remote_files_editor_save))
                    }
            }
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int?,
    current: Int,
    onStep: (Boolean) -> Unit,
    onClose: () -> Unit,
    focus: FocusRequester,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier =
                Modifier.weight(1f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            onStep(!event.isShiftPressed)
                            true
                        } else false
                    }
                    .testTag("filesEditorFindQuery"),
            placeholder = { Text(stringResource(R.string.remote_files_editor_find)) },
            singleLine = true,
            textStyle = monoTextStyle(),
        )
        if (count != null)
            Text(
                if (count == 0) stringResource(R.string.remote_files_editor_find_none)
                else stringResource(R.string.remote_files_editor_find_count, current, count),
                Modifier.padding(horizontal = 8.dp).testTag("filesEditorFindCount"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        IconButton(onClick = { onStep(false) }, enabled = (count ?: 0) > 0, modifier = Modifier.testTag("filesEditorFindPrevious")) {
            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.remote_files_editor_find_previous))
        }
        IconButton(onClick = { onStep(true) }, enabled = (count ?: 0) > 0, modifier = Modifier.testTag("filesEditorFindNext")) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.remote_files_editor_find_next))
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("filesEditorFindClose")) {
            Icon(Icons.Default.Close, stringResource(R.string.remote_files_editor_find_close))
        }
    }
}

/** Keys a soft keyboard hides behind its symbol pages. */
@Composable
private fun AccessoryRow(onIndent: (Boolean) -> Unit, onInsert: (String) -> Unit) {
    val label = stringResource(R.string.remote_files_editor_keys)
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = label }
            .testTag("filesEditorAccessory"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val indent = stringResource(R.string.remote_files_editor_indent)
        val outdent = stringResource(R.string.remote_files_editor_outdent)
        TextButton(onClick = { onIndent(false) }, Modifier.semantics { contentDescription = indent }) { Text("⇥") }
        TextButton(onClick = { onIndent(true) }, Modifier.semantics { contentDescription = outdent }) { Text("⇤") }
        for (key in ACCESSORY_KEYS)
            TextButton(onClick = { onInsert(key) }, Modifier.testTag("filesEditorKey:$key")) {
                Text(key, fontFamily = FontFamily.Monospace)
            }
    }
}

@Composable
private fun EditorFooter(
    editor: FileEditor,
    notice: EditorNotice?,
    onFind: () -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
) {
    // "Saved" shows for a moment after each save of this editor, not again after a recomposition.
    val seenSaves = remember { mutableStateOf(editor.savedAt) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(editor.savedAt) {
        if (editor.savedAt > seenSaves.value) {
            seenSaves.value = editor.savedAt
            saved = true
            delay(SAVED_NOTICE_MS)
            saved = false
        }
    }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val failure = editor.saveFailure
        when {
            failure != null && !editor.saving ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(saveFailureText(failure)),
                        Modifier.weight(1f).testTag("filesEditorSaveFailure"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (failure == FilesFailure.CONFLICT)
                        TextButton(onClick = onReload, modifier = Modifier.testTag("filesEditorReload")) {
                            Text(stringResource(R.string.remote_files_editor_reload))
                        }
                    else if (failure != FilesFailure.UNSUPPORTED && failure != FilesFailure.INVALID_PATH)
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("filesEditorRetry")) {
                            Text(stringResource(R.string.remote_files_retry))
                        }
                }
            notice != null ->
                Text(
                    stringResource(
                        when (notice) {
                            EditorNotice.TOO_LARGE -> R.string.remote_files_editor_prompt_too_large
                            EditorNotice.PREFILL_FAILED -> R.string.remote_files_editor_prefill_failed
                        }
                    ),
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("filesEditorNotice"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            saved ->
                Text(
                    stringResource(R.string.remote_files_editor_saved),
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("filesEditorSaved"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onFind, modifier = Modifier.testTag("filesEditorFind")) {
                Icon(Icons.Default.Search, stringResource(R.string.remote_files_editor_find))
            }
            Button(onClick = onSend, enabled = editor.changed, modifier = Modifier.weight(1f).testTag("filesEditorSend")) {
                Text(stringResource(R.string.remote_files_editor_send), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun saveFailureText(failure: FilesFailure): Int =
    when (failure) {
        FilesFailure.OFFLINE -> R.string.remote_files_save_failed_offline
        FilesFailure.UNSUPPORTED -> R.string.remote_files_save_failed_unsupported
        FilesFailure.INVALID_PATH -> R.string.remote_files_save_failed_invalid_path
        FilesFailure.NOT_FOUND -> R.string.remote_files_failure_not_found
        FilesFailure.FORBIDDEN -> R.string.remote_files_failure_forbidden
        FilesFailure.BUSY -> R.string.remote_files_failure_busy
        FilesFailure.CONFLICT -> R.string.remote_files_failure_conflict
        FilesFailure.FAILED -> R.string.remote_files_save_failed
    }
