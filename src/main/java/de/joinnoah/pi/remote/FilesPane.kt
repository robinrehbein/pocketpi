package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [FilesPane] can ask for; the chat screen wires these to its view model. */
internal class FilesActions(
    val onClose: () -> Unit,
    val onOpenDir: (String) -> Unit,
    /** Opens a file of the shown folder, or returns to the folder for null. */
    val onOpenFile: (String?) -> Unit,
    val onLoadMore: () -> Unit,
    val onReload: () -> Unit,
    val onSelectLines: (LineSelection?) -> Unit,
    /** Puts a quote into the composer. Nothing is sent. False when it did not fit. */
    val onSend: (String) -> Boolean,
)

/**
 * The session folder, read-only: a folder listing, or one file with line numbers. Like
 * [ChangesPane] it fills whatever container it is given, the phone overlay or the tablet
 * inspector. Back closes the file, then climbs one folder, then closes the pane.
 */
@Composable
internal fun FilesPane(state: FilesState, actions: FilesActions, modifier: Modifier = Modifier) {
    val back = {
        when {
            state.file != null -> actions.onOpenFile(null)
            state.path.isNotEmpty() -> actions.onOpenDir(parentPath(state.path))
            else -> actions.onClose()
        }
    }
    BackHandler(onBack = back)
    // A failed prefill belongs to the file or folder it happened in.
    var prefillFailed by remember { mutableStateOf(false) }
    LaunchedEffect(state.file?.path, state.path) { prefillFailed = false }
    val send: (String) -> Unit = { prompt -> prefillFailed = !actions.onSend(prompt) }
    Surface(modifier.fillMaxSize().testTag("filesPane")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            FilesTopBar(state, actions, back)
            HorizontalDivider()
            if (prefillFailed)
                Text(
                    stringResource(R.string.remote_files_prefill_failed),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("filesPrefillFailed"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val file = state.file
                // Every folder and file starts with fresh scroll state.
                if (file == null) key(state.path) { FolderList(state, actions) }
                else key(file.path) { FileView(file, actions, send) }
            }
        }
    }
}

@Composable
private fun FilesTopBar(state: FilesState, actions: FilesActions, back: () -> Unit) {
    val nested = state.file != null || state.path.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (nested)
            IconButton(onClick = back, Modifier.testTag("filesBack")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_files_back))
            }
        else
            IconButton(onClick = actions.onClose, Modifier.testTag("filesClose")) {
                Icon(Icons.Default.Close, stringResource(R.string.remote_files_close))
            }
        val title = state.file?.path ?: state.path.ifEmpty { null }
        Text(
            title ?: stringResource(R.string.remote_files_title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (title != null) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        val busy = state.file?.loading ?: state.loading
        IconButton(onClick = actions.onReload, enabled = !busy, modifier = Modifier.testTag("filesReload")) {
            Icon(Icons.Default.Refresh, stringResource(R.string.remote_files_reload))
        }
        if (nested)
            IconButton(onClick = actions.onClose, Modifier.testTag("filesClose")) {
                Icon(Icons.Default.Close, stringResource(R.string.remote_files_close))
            }
    }
}

private fun failureText(failure: FilesFailure): Int =
    when (failure) {
        FilesFailure.UNSUPPORTED -> R.string.remote_files_failure_unsupported
        FilesFailure.OFFLINE -> R.string.remote_files_failure_offline
        FilesFailure.NOT_FOUND -> R.string.remote_files_failure_not_found
        FilesFailure.INVALID_PATH -> R.string.remote_files_failure_invalid_path
        FilesFailure.FORBIDDEN -> R.string.remote_files_failure_forbidden
        FilesFailure.BUSY -> R.string.remote_files_failure_busy
        FilesFailure.FAILED -> R.string.remote_files_failure_failed
    }

@Composable
private fun FilesNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FilesFailureNotice(failure: FilesFailure, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("filesFailure")) {
        Text(
            stringResource(failureText(failure)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (failure != FilesFailure.UNSUPPORTED && failure != FilesFailure.INVALID_PATH)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.remote_files_retry)) }
    }
}

@Composable
private fun FilesLoading() {
    val label = stringResource(R.string.remote_files_loading)
    LinearProgressIndicator(
        Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label }.testTag("filesLoading")
    )
}

/** The next page: a button, its progress, or why it failed with a retry. */
private fun LazyListScope.loadMore(loading: Boolean, failure: FilesFailure?, onLoadMore: () -> Unit) {
    item(key = "more") {
        when {
            failure != null -> FilesFailureNotice(failure, onLoadMore)
            loading -> FilesLoading()
            else ->
                TextButton(onClick = onLoadMore, Modifier.padding(horizontal = 8.dp).testTag("filesLoadMore")) {
                    Text(stringResource(R.string.remote_files_load_more))
                }
        }
    }
}

@Composable
private fun FolderList(state: FilesState, actions: FilesActions) {
    LazyColumn(Modifier.fillMaxSize().testTag("filesList")) {
        item(key = "crumbs") { Breadcrumbs(state.path, actions.onOpenDir) }
        val listing = state.listing
        val failure = state.failure
        when {
            failure != null -> item(key = "failure") { FilesFailureNotice(failure, actions.onReload) }
            state.loading || listing == null -> item(key = "loading") { FilesLoading() }
            listing.unavailable != null ->
                item(key = "unavailable") {
                    FilesNotice(
                        stringResource(
                            when (listing.unavailable) {
                                FilesUnavailable.GIT_UNAVAILABLE -> R.string.remote_files_git_unavailable
                                FilesUnavailable.NOT_A_REPOSITORY -> R.string.remote_files_not_a_repository
                            }
                        ),
                        Modifier.testTag("filesUnavailable"),
                    )
                }
            else -> {
                if (listing.entries.isEmpty() && listing.nextAfter == null)
                    item(key = "empty") { FilesNotice(stringResource(R.string.remote_files_empty)) }
                items(listing.entries, key = { "entry:" + it.name }) { entry ->
                    EntryRow(entry) {
                        val path = childPath(state.path, entry.name)
                        when (entry.type) {
                            FileEntryType.DIR -> actions.onOpenDir(path)
                            FileEntryType.FILE -> actions.onOpenFile(path)
                            FileEntryType.SYMLINK, FileEntryType.SUBMODULE -> Unit
                        }
                    }
                }
                if (listing.nextAfter != null) loadMore(state.moreLoading, state.moreFailure, actions.onLoadMore)
                if (listing.truncated)
                    item(key = "truncated") { FilesNotice(stringResource(R.string.remote_files_truncated)) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onOpenDir: (String) -> Unit) {
    val segments = if (path.isEmpty()) emptyList() else path.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpenDir("") }, enabled = path.isNotEmpty(), modifier = Modifier.testTag("filesCrumb:")) {
            Text(stringResource(R.string.remote_files_title))
        }
        segments.forEachIndexed { index, segment ->
            val target = segments.take(index + 1).joinToString("/")
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { onOpenDir(target) },
                enabled = target != path,
                modifier = Modifier.testTag("filesCrumb:$target"),
            ) {
                Text(segment, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FileEntry, onOpen: () -> Unit) {
    val context = LocalContext.current
    val openable = entry.type == FileEntryType.DIR || entry.type == FileEntryType.FILE
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val detail =
        when (entry.type) {
            FileEntryType.FILE -> entry.size?.let { Formatter.formatShortFileSize(context, it) }
            FileEntryType.SYMLINK -> stringResource(R.string.remote_files_symlink)
            FileEntryType.SUBMODULE -> stringResource(R.string.remote_files_submodule)
            FileEntryType.DIR -> null
        }
    Row(
        Modifier.fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("filesEntry:${entry.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            when (entry.type) {
                FileEntryType.DIR -> Icons.Default.Folder
                FileEntryType.FILE -> Icons.Default.Description
                FileEntryType.SYMLINK -> Icons.Default.Link
                FileEntryType.SUBMODULE -> Icons.Default.AccountTree
            },
            stringResource(
                when (entry.type) {
                    FileEntryType.DIR -> R.string.remote_files_kind_dir
                    FileEntryType.FILE -> R.string.remote_files_kind_file
                    FileEntryType.SYMLINK -> R.string.remote_files_kind_symlink
                    FileEntryType.SUBMODULE -> R.string.remote_files_kind_submodule
                }
            ),
            Modifier.size(24.dp),
            tint = if (entry.type == FileEntryType.DIR) MaterialTheme.colorScheme.primary else muted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (openable) LocalContentColor.current else muted,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail != null)
                Text(detail, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
        }
    }
}

@Composable
private fun syntaxColors(): SyntaxColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        SyntaxColors(
            keyword = scheme.primary,
            string = scheme.tertiary,
            comment = scheme.onSurfaceVariant,
            number = scheme.secondary,
            key = scheme.primary,
            heading = scheme.primary,
        )
    }
}

@Composable
private fun FileView(file: OpenFile, actions: FilesActions, send: (String) -> Unit) {
    val context = LocalContext.current
    val lines = remember(file.content) { fileLines(file.content) }
    val language = remember(file.path) { languageFor(file.path) }
    val colors = syntaxColors()
    val plain = remember(lines) { lines.map { AnnotatedString(it.take(MAX_CODE_LINE_CHARS)) } }
    // One highlighter per version of the file: a later page only styles the lines it adds. While
    // it works, the lines styled before stay styled and only the new ones show plain.
    val highlighter =
        remember(file.version, language, colors) { language?.let { IncrementalHighlighter(it, colors) } }
    val highlighted by
        produceState<Pair<IncrementalHighlighter, List<AnnotatedString>>?>(null, lines, highlighter) {
            if (highlighter != null)
                value = highlighter to withContext(Dispatchers.Default) { highlighter.update(lines) }
        }
    val shown =
        highlighted
            ?.takeIf { (owner, styled) -> owner === highlighter && styled.size <= lines.size }
            ?.let { (_, styled) -> if (styled.size == lines.size) styled else styled + plain.subList(styled.size, plain.size) }
            ?: plain
    val style = monoTextStyle()
    val contentWidth = rememberCodeContentWidth(lines, style)
    val charWidth = rememberMonoCharWidth(style)
    val numberWidth = charWidth * lines.size.coerceAtLeast(1).toString().length
    val scroll = rememberScrollState()
    val selection = file.selection?.takeIf { it.last <= lines.size }
    val selectionState =
        selection?.let {
            if (it.first == it.last) stringResource(R.string.remote_files_selection_line, it.first)
            else stringResource(R.string.remote_files_selection_lines, it.first, it.last)
        }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("filesFile")) {
            if (file.reopened)
                item(key = "reopened") {
                    FilesNotice(stringResource(R.string.remote_files_reopened), Modifier.testTag("filesReopened"))
                }
            val failure = file.failure
            when {
                failure != null -> item(key = "failure") { FilesFailureNotice(failure, actions.onReload) }
                file.loading -> item(key = "loading") { FilesLoading() }
                file.binary ->
                    item(key = "binary") {
                        FilesNotice(stringResource(R.string.remote_files_binary), Modifier.testTag("filesBinary"))
                    }
                file.tooLarge ->
                    item(key = "tooLarge") {
                        FilesNotice(stringResource(R.string.remote_files_too_large), Modifier.testTag("filesTooLarge"))
                    }
                lines.isEmpty() && file.nextOffset == null ->
                    item(key = "empty") { FilesNotice(stringResource(R.string.remote_files_file_empty)) }
                else -> {
                    // Rows stay one text line high to keep code dense; the number cell is at
                    // least 48dp wide, so it stays easy to hit sideways.
                    itemsIndexed(shown, contentType = { _, _ -> "line" }) { index, text ->
                        val number = index + 1
                        FileLineRow(
                            number,
                            text,
                            selection != null && number in selection.first..selection.last,
                            FileRowStyle(style, numberWidth, contentWidth),
                            scroll,
                            lineTapLabel(context, file.selection.tapAction(number), number),
                            selectionState,
                        ) { actions.onSelectLines(file.selection.tap(number)) }
                    }
                    if (file.nextOffset != null) loadMore(file.moreLoading, file.moreFailure, actions.onLoadMore)
                }
            }
        }
        if (!file.loading && file.failure == null)
            FileActionBar(
                selection = selection?.takeIf { lines.isNotEmpty() && !file.binary },
                canSendLines = lines.isNotEmpty() && !file.binary,
                onClearSelection = { actions.onSelectLines(null) },
                onSendFile = { send(filePrompt(context, file, lines, null, language)) },
                onSendLines = { selection?.let { send(filePrompt(context, file, lines, it, language)) } },
            )
    }
}

private fun lineTapLabel(context: Context, action: LineTap, line: Int): String =
    when (action) {
        LineTap.START -> context.getString(R.string.remote_files_line_start, line)
        LineTap.END -> context.getString(R.string.remote_files_line_end, line)
        LineTap.CLEAR -> context.getString(R.string.remote_files_line_clear)
    }

private class FileRowStyle(val text: TextStyle, val numberWidth: Dp, val contentWidth: Dp)

@Composable
private fun FileLineRow(
    number: Int,
    text: AnnotatedString,
    selected: Boolean,
    rowStyle: FileRowStyle,
    scroll: ScrollState,
    tapLabel: String,
    selectionState: String?,
    onTapNumber: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Unspecified
    Row(Modifier.fillMaxWidth().background(background).testTag(if (selected) "filesLineSelected" else "filesLineRow")) {
        Text(
            number.toString(),
            Modifier.clickable(onClickLabel = tapLabel, onClick = onTapNumber)
                .semantics {
                    this.selected = selected
                    if (selectionState != null) stateDescription = selectionState
                }
                .width(maxOf(rowStyle.numberWidth + 16.dp, 48.dp))
                .padding(horizontal = 8.dp)
                .testTag("filesLine:$number"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = rowStyle.text,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Text(
                text,
                Modifier.width(rowStyle.contentWidth).padding(start = 4.dp),
                style = rowStyle.text,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun FileActionBar(
    selection: LineSelection?,
    canSendLines: Boolean,
    onClearSelection: () -> Unit,
    onSendFile: () -> Unit,
    onSendLines: () -> Unit,
) {
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (selection != null) {
            val count = selection.last - selection.first + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.remote_files_selected_lines, count, count),
                    Modifier.weight(1f).testTag("filesSelection"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClearSelection, modifier = Modifier.testTag("filesClearSelection")) {
                    Text(stringResource(R.string.remote_files_clear_selection))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSendFile, Modifier.weight(1f).testTag("filesSendFile")) {
                Text(stringResource(R.string.remote_files_send_file), textAlign = TextAlign.Center)
            }
            if (canSendLines)
                Button(
                    onClick = onSendLines,
                    enabled = selection != null,
                    modifier = Modifier.weight(1f).testTag("filesSendLines"),
                ) {
                    Text(stringResource(R.string.remote_files_send_lines), textAlign = TextAlign.Center)
                }
        }
    }
}

/**
 * The composer text for [file]: the [selection] of its [lines], or the whole file for null. A
 * file that is binary, too large or not fully loaded, and any quote over [MAX_FILE_QUOTE_BYTES],
 * becomes a reference by path and lines instead.
 */
internal fun filePrompt(
    context: Context,
    file: OpenFile,
    lines: List<String>,
    selection: LineSelection?,
    language: SyntaxLanguage?,
): String {
    val path = file.path
    if (selection == null) {
        val reference = context.getString(R.string.remote_files_reference_file, path)
        if (file.binary || file.tooLarge || file.nextOffset != null || lines.isEmpty()) return reference
        return fileQuotePrompt(
            context.getString(R.string.remote_files_quote_file, path),
            reference,
            lines.joinToString("\n"),
            language,
        )
    }
    val first = selection.first.coerceIn(1, lines.size)
    val last = selection.last.coerceIn(first, lines.size)
    val single = first == last
    return fileQuotePrompt(
        if (single) context.getString(R.string.remote_files_quote_line, path, first)
        else context.getString(R.string.remote_files_quote_lines, path, first, last),
        if (single) context.getString(R.string.remote_files_reference_line, path, first)
        else context.getString(R.string.remote_files_reference_lines, path, first, last),
        lines.subList(first - 1, last).joinToString("\n"),
        language,
    )
}
