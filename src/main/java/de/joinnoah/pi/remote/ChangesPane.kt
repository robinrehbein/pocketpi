package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/** What [ChangesPane] can ask for; the chat screen wires these to its view model. */
internal class ChangesActions(
    val onClose: () -> Unit,
    val onSelectBase: (GitBase) -> Unit,
    val onReload: () -> Unit,
    val onOpenFile: (String?) -> Unit,
    /** False when the comment was not saved. */
    val onSetComment: (ReviewComment) -> Boolean,
    val onRemoveComment: (ReviewComment) -> Unit,
    /** Puts the review prompt into the composer. Nothing is sent. False when it did not fit. */
    val onUseReview: (String) -> Boolean,
    /** Puts a quick action prompt into the composer. Nothing is sent. False when it did not fit. */
    val onPrefill: (String) -> Boolean,
    val onRequestPreview: (String) -> Unit = {},
    /** Opens a file from a subagent's changes: its session ID and the path. */
    val onOpenSubagentFile: (String, String) -> Unit = { _, _ -> },
)

/**
 * The changes of one session against a base: the file list with the commit log, or one file's
 * diff with line comments. It fills whatever container it is given, so the phone overlay and a
 * later tablet inspector can share it.
 */
@Composable
internal fun ChangesPane(state: ChangesState, actions: ChangesActions, modifier: Modifier = Modifier, projectName: String? = null) {
    PaneBackHandler { if (state.file != null) actions.onOpenFile(null) else actions.onClose() }
    // One state object for the remembered actions below; a failed prefill belongs to the file
    // and base it happened on, so it resets when either changes.
    var prefillFailed by remember { mutableStateOf(false) }
    LaunchedEffect(state.file, state.base) { prefillFailed = false }
    val checked =
        remember(actions) {
            ChangesActions(
                actions.onClose,
                actions.onSelectBase,
                actions.onReload,
                actions.onOpenFile,
                actions.onSetComment,
                actions.onRemoveComment,
                onUseReview = { prompt -> actions.onUseReview(prompt).also { prefillFailed = !it } },
                onPrefill = { prompt -> actions.onPrefill(prompt).also { prefillFailed = !it } },
                onRequestPreview = actions.onRequestPreview,
                onOpenSubagentFile = actions.onOpenSubagentFile,
            )
        }
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(64.dp) }
    Surface(modifier.fillMaxSize().testTag("changesPane")) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                if (prefillFailed)
                    Text(
                        stringResource(R.string.remote_changes_prefill_failed),
                        Modifier.fillMaxWidth().padding(top = headerHeight).padding(horizontal = 16.dp, vertical = 8.dp).testTag("changesPrefillFailed"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val inset = if (prefillFailed) 0.dp else headerHeight
                    val file = state.file
                    if (file == null) ChangesOverview(state, checked, inset)
                    else key(file) { ChangesFileDiff(state, file, checked, inset) }
                }
                if (state.comments.isNotEmpty()) ReviewBar(state.comments, state.devBase, checked)
            }
            Box(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                PaneHeader(
                    title = stringResource(R.string.remote_changes_title),
                    subtitle = state.file ?: projectName?.takeIf(String::isNotBlank),
                    nested = state.file != null,
                    busy = state.loading || state.diffLoading,
                    tag = "changes",
                    backLabel = stringResource(R.string.remote_changes_back_to_files),
                    closeLabel = stringResource(R.string.remote_changes_close),
                    reloadLabel = stringResource(R.string.remote_changes_reload),
                    onBack = { actions.onOpenFile(null) },
                    onClose = actions.onClose,
                    onReload = actions.onReload,
                )
            }
        }
    }
}

private fun formatTime(context: Context, at: Long): String =
    DateUtils.formatDateTime(
        context,
        at,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

private fun failureText(failure: ChangesFailure): Int =
    when (failure) {
        ChangesFailure.UNSUPPORTED -> R.string.remote_changes_failure_unsupported
        ChangesFailure.OFFLINE -> R.string.remote_changes_failure_offline
        ChangesFailure.NOT_FOUND -> R.string.remote_changes_failure_not_found
        ChangesFailure.FORBIDDEN -> R.string.remote_changes_failure_forbidden
        ChangesFailure.BUSY -> R.string.remote_changes_failure_busy
        ChangesFailure.FAILED -> R.string.remote_changes_failure_failed
    }

private fun unavailableText(reason: GitUnavailable): Int =
    when (reason) {
        GitUnavailable.GIT_UNAVAILABLE -> R.string.remote_changes_unavailable_git
        GitUnavailable.NOT_A_REPOSITORY -> R.string.remote_changes_unavailable_repository
        GitUnavailable.BASE_UNAVAILABLE -> R.string.remote_changes_unavailable_base
        GitUnavailable.SESSION_UNSUPPORTED -> R.string.remote_changes_unavailable_session
    }

@Composable
private fun baseLabel(base: GitBase, devBase: GitDevBase?): String =
    when (base) {
        GitBase.SESSION -> stringResource(R.string.remote_changes_base_session)
        GitBase.HEAD -> stringResource(R.string.remote_changes_base_head)
        GitBase.DEV -> devBaseName(devBase)
    }

private fun changeLabel(change: GitChange): Int =
    when (change) {
        GitChange.ADDED -> R.string.remote_changes_kind_added
        GitChange.MODIFIED -> R.string.remote_changes_kind_modified
        GitChange.DELETED -> R.string.remote_changes_kind_deleted
        GitChange.TYPE_CHANGED -> R.string.remote_changes_kind_type_changed
    }

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FailureNotice(failure: ChangesFailure, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("changesFailure")) {
        Text(
            stringResource(failureText(failure)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (failure != ChangesFailure.UNSUPPORTED)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.remote_changes_retry)) }
    }
}

@Composable
private fun Loading() {
    val label = stringResource(R.string.remote_changes_loading)
    LinearProgressIndicator(
        Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label }.testTag("changesLoading")
    )
}

@Composable
private fun ChangesOverview(state: ChangesState, actions: ChangesActions, headerHeight: Dp) {
    val context = LocalContext.current
    val status = state.status
    val shownBase = status?.base ?: state.base
    val commentCounts =
        remember(state.comments, shownBase) {
            state.comments.filter { it.base == shownBase }.groupingBy { it.path }.eachCount()
        }
    val subagents = remember(state.subagents, state.status?.branch) { visibleSubagents(state) }
    val gridState = rememberLazyGridState()
    var peek by remember(state.status?.snapshotId) { mutableStateOf<Pair<String, IntRect>?>(null) }
    LaunchedEffect(state.loading, state.failure) { if (state.loading || state.failure != null) peek = null }
    LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress) peek = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(fileGridColumns(maxWidth)),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("changesList"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerHeight + 8.dp, bottom = 8.dp),
        ) {
            item(key = "bases", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A repository without a branch to compare against loses that choice, unless it is the one shown.
                    val noDevBase = state.devBase?.ref == null && state.devBase != null
                    GitBase.entries.filter { it != GitBase.DEV || !noDevBase || it == shownBase || it == state.base }.forEach { base ->
                        FilterChip(
                            selected = base == shownBase,
                            onClick = { if (base != shownBase || state.failure != null) actions.onSelectBase(base) },
                            label = { Text(baseLabel(base, state.devBase)) },
                            modifier = Modifier.testTag("changesBase_${base.wire}"),
                        )
                    }
                }
            }
            if (state.sessionUnavailable != null)
                item(key = "fallback", span = { GridItemSpan(maxLineSpan) }) {
                    Notice(stringResource(R.string.remote_changes_session_fallback), Modifier.testTag("changesFallback"))
                }
            status?.since?.let { since ->
                item(key = "since", span = { GridItemSpan(maxLineSpan) }) {
                    val time = formatTime(context, since.at)
                    Notice(
                        stringResource(
                            if (since.firstContact) R.string.remote_changes_since_first_contact
                            else R.string.remote_changes_since_session_start,
                            time,
                        ),
                        Modifier.testTag("changesSince"),
                    )
                }
            }
            status?.takeIf { it.base == GitBase.DEV }?.let { it.devBase?.ref ?: it.devRef }?.let { ref ->
                item(key = "devRef", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_dev_ref, ref)) }
            }
            status?.branch?.let { branch ->
                item(key = "branch", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_branch, branch)) }
            }
            val failure = state.failure
            when {
                failure != null -> item(key = "failure", span = { GridItemSpan(maxLineSpan) }) { FailureNotice(failure, actions.onReload) }
                state.loading || status == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { Loading() }
                status.unavailable != null ->
                    item(key = "unavailable", span = { GridItemSpan(maxLineSpan) }) {
                        Notice(stringResource(unavailableText(status.unavailable)), Modifier.testTag("changesUnavailable"))
                    }
                else -> {
                    if (status.files.isNotEmpty() || subagents.isNotEmpty())
                        item(key = "filesHeader", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(
                                    if (subagents.isEmpty()) R.string.remote_files_title else R.string.remote_changes_this_session
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    if (status.files.isEmpty())
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_empty)) }
                    items(status.files.size, key = { "file:" + status.files[it].path }) { index ->
                        val file = status.files[index]
                        ChangesFileTile(file, commentCounts[file.path] ?: 0, state.previews[file.path],
                            onRequestPreview = { actions.onRequestPreview(file.path) },
                            onPeek = { anchor -> peek = file.path to anchor },
                            onOpen = { actions.onOpenFile(file.path) })
                    }
                    if (status.truncated)
                        item(key = "truncated", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_truncated)) }
                    commits(state, context, actions.onReload)
                    subagents.forEach { sub -> subagentGroup(sub, actions) }
                }
            }
        }
        peek?.let { (path, anchor) ->
            FilePeekPopup(FilesPeek(path, FileEntryType.FILE), state.previews[path], anchor) { peek = null }
        }
    }
}

/** One subagent's changed files, below the session's own; tiles open its diff, without a preview. */
private fun LazyGridScope.subagentGroup(sub: SubagentChanges, actions: ChangesActions) {
    item(key = "subagent:" + sub.sessionId, span = { GridItemSpan(maxLineSpan) }) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("changesSubagent")) {
            Text(
                sub.title.ifBlank { stringResource(R.string.remote_changes_subagent_untitled) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (sub.running) R.string.remote_changes_subagent_running else R.string.remote_changes_subagent_finished,
                    sub.status.branch.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val files = sub.status.files
    items(files.size, key = { "subagent:" + sub.sessionId + ":" + files[it].path }) { index ->
        val file = files[index]
        ChangesFileTile(file, 0, null, onRequestPreview = {}, onPeek = {},
            onOpen = { actions.onOpenSubagentFile(sub.sessionId, file.path) })
    }
    if (sub.status.truncated)
        item(key = "subagentTruncated:" + sub.sessionId, span = { GridItemSpan(maxLineSpan) }) {
            Notice(stringResource(R.string.remote_changes_truncated))
        }
}

private fun LazyGridScope.commits(state: ChangesState, context: Context, onRetry: () -> Unit) {
    if (state.status?.base == GitBase.HEAD) return
    item(key = "commitsHeader", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            stringResource(R.string.remote_changes_commits),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
    val log = state.log
    val failure = state.logFailure
    when {
        // A busy host (per-device or host-wide limit) clears up; the retry reloads status and log.
        failure != null -> item(key = "commitsFailure", span = { GridItemSpan(maxLineSpan) }) { FailureNotice(failure, onRetry) }
        state.logLoading || log == null -> item(key = "commitsLoading", span = { GridItemSpan(maxLineSpan) }) { Loading() }
        log.unavailable != null ->
            item(key = "commitsUnavailable", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_commits_unavailable)) }
        else -> {
            if (log.commits.isEmpty())
                item(key = "commitsEmpty", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_commits_empty)) }
            items(log.commits.size, key = { "commit:" + log.commits[it].sha }, span = { GridItemSpan(maxLineSpan) }) { index ->
                val commit = log.commits[index]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("changesCommit")) {
                    Text(
                        commit.subject.ifEmpty { stringResource(R.string.remote_changes_commit_no_subject) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.remote_changes_commit_meta,
                            commit.sha.take(7),
                            commit.author,
                            formatTime(context, commit.time),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (log.truncated)
                item(key = "commitsTruncated", span = { GridItemSpan(maxLineSpan) }) { Notice(stringResource(R.string.remote_changes_commits_truncated)) }
        }
    }
}

@Composable
private fun ChangesFileTile(
    file: GitFile, comments: Int, preview: FileTilePreview?,
    onRequestPreview: () -> Unit, onPeek: (IntRect) -> Unit, onOpen: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val counts =
        if (file.additions != null && file.deletions != null)
            stringResource(R.string.remote_changes_line_counts, file.additions, file.deletions)
        else null
    FileEntryTile(
        entry = FileEntry(file.path.substringAfterLast('/'), FileEntryType.FILE),
        preview = preview,
        onOpen = onOpen,
        onRequestPreview = onRequestPreview,
        onPeek = onPeek,
        tag = "changesFile",
        detail = {
            Column {
                if ('/' in file.path)
                    Text(file.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                        color = muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(changeLabel(file.change)), style = MaterialTheme.typography.labelMedium, color = muted)
                    when {
                        file.omitted != null ->
                            Text(
                                stringResource(
                                    when (file.omitted) {
                                        GitOmitted.TOO_LARGE -> R.string.remote_changes_too_large
                                        GitOmitted.BASE_UNAVAILABLE -> R.string.remote_changes_base_content_gone
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = muted,
                            )
                        file.binary ->
                            Text(stringResource(R.string.remote_changes_binary), style = MaterialTheme.typography.labelMedium, color = muted)
                        counts != null ->
                            Row(
                                Modifier.semantics(mergeDescendants = true) { contentDescription = counts },
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("+${file.additions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text("−${file.deletions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                    }
                    if (comments > 0)
                        Text(
                            pluralStringResource(R.plurals.remote_changes_comment_count, comments, comments),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                }
            }
        },
    )
}

@Composable
private fun ChangesFileDiff(state: ChangesState, path: String, actions: ChangesActions, headerHeight: Dp) {
    val context = LocalContext.current
    val diff = state.diff?.takeIf { it.path == path }
    val lines = remember(diff?.patch) { diff?.patch?.let(::patchDiff).orEmpty() }
    val rowStyle = rememberDiffRowStyle(lines)
    val scroll = rememberScrollState()
    val base = state.status?.base ?: state.base
    // Line numbers belong to one base: a comment shows only on the diff it was written on.
    val comments =
        remember(state.comments, path, base, state.fileSession) {
            if (state.fileSession != null) emptyMap()
            else state.comments.filter { it.path == path && it.base == base }.associateBy { it.oldLine to it.newLine }
        }
    val atLimit = state.comments.size >= MAX_REVIEW_COMMENTS
    var editing by remember { mutableStateOf<DiffLine?>(null) }
    val addLabel = stringResource(R.string.remote_changes_comment_add)
    val editLabel = stringResource(R.string.remote_changes_comment_edit)
    // A subagent's file: its changes are not the session's to comment on or act on from here.
    val subagent = state.fileSession?.let { id -> state.subagents.firstOrNull { it.sessionId == id } }
    val readOnly = state.fileSession != null
    LazyColumn(Modifier.fillMaxSize().testTag("changesDiff"), contentPadding = PaddingValues(top = headerHeight)) {
        if (readOnly)
            item(key = "subagent") {
                Notice(
                    stringResource(
                        R.string.remote_changes_subagent_file,
                        subagent?.title?.ifBlank { null } ?: stringResource(R.string.remote_changes_subagent_untitled),
                    ),
                    Modifier.testTag("changesSubagentFile"),
                )
            }
        if (!readOnly) item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.REVERT, path, base, state.devBase)) },
                    label = { Text(stringResource(R.string.remote_changes_action_revert)) },
                    modifier = Modifier.testTag("changesActionRevert"),
                )
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.TESTS, path, base, state.devBase)) },
                    label = { Text(stringResource(R.string.remote_changes_action_tests)) },
                    modifier = Modifier.testTag("changesActionTests"),
                )
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.EXPLAIN, path, base, state.devBase)) },
                    label = { Text(stringResource(R.string.remote_changes_action_explain)) },
                    modifier = Modifier.testTag("changesActionExplain"),
                )
            }
        }
        val failure = state.diffFailure
        when {
            failure != null -> item(key = "failure") {
                FailureNotice(failure) {
                    // A subagent's snapshot comes back with a reload of the whole view.
                    if (state.fileSession == null) actions.onOpenFile(path) else actions.onReload()
                }
            }
            state.diffLoading || diff == null -> item(key = "loading") { Loading() }
            diff.omitted == GitOmitted.TOO_LARGE ->
                item(key = "tooLarge") { Notice(stringResource(R.string.remote_changes_diff_too_large)) }
            diff.omitted == GitOmitted.BASE_UNAVAILABLE ->
                item(key = "baseGone") { Notice(stringResource(R.string.remote_changes_diff_base_content_gone)) }
            diff.binary -> item(key = "binary") { Notice(stringResource(R.string.remote_changes_diff_binary)) }
            lines.isEmpty() -> item(key = "empty") { Notice(stringResource(R.string.remote_changes_diff_empty)) }
            else -> {
                // Rows stay one text line high, below the 48dp touch target, to keep code readable;
                // an accepted trade-off, as in the tool detail diff.
                itemsIndexed(lines, contentType = { _, line -> line.kind }) { _, line ->
                    val commentable = line.kind != DiffKind.HUNK && !readOnly
                    Column {
                        DiffRow(
                            line,
                            rowStyle,
                            scroll,
                            if (commentable)
                                Modifier.clickable(onClickLabel = addLabel) { editing = line }.testTag("changesDiffLine")
                            else Modifier,
                        )
                        comments[line.oldLine.takeIf { line.newLine == null } to line.newLine]?.let { comment ->
                            Text(
                                comment.text,
                                Modifier.fillMaxWidth()
                                    .clickable(onClickLabel = editLabel) { editing = line }
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .testTag("changesComment"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
                if (diff.truncated)
                    item(key = "truncated") { Notice(stringResource(R.string.remote_changes_diff_truncated)) }
            }
        }
    }
    editing?.let { line ->
        val newLine = line.newLine
        val oldLine = line.oldLine.takeIf { newLine == null }
        val existing = comments[oldLine to newLine]
        CommentDialog(
            line = newLine ?: oldLine ?: 0,
            initial = existing?.text.orEmpty(),
            canDelete = existing != null,
            atLimit = atLimit && existing == null,
            onSave = { text ->
                val saved =
                    actions.onSetComment(
                        ReviewComment(path, oldLine, newLine, line.text.take(MAX_REVIEW_QUOTE_CHARS), text.trim(), base)
                    )
                if (saved) editing = null
                saved
            },
            onDelete = {
                existing?.let(actions.onRemoveComment)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CommentDialog(
    line: Int,
    initial: String,
    canDelete: Boolean,
    atLimit: Boolean,
    onSave: (String) -> Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var failed by remember { mutableStateOf(false) }
    val valid = !atLimit && text.isNotBlank() && text.trim().encodeToByteArray().size <= MAX_REVIEW_COMMENT_BYTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_changes_comment_title, line)) },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    {
                        text = it
                        failed = false
                    },
                    Modifier.fillMaxWidth().testTag("changesCommentField"),
                    placeholder = { Text(stringResource(R.string.remote_changes_comment_hint)) },
                    minLines = 2,
                )
                val error =
                    when {
                        atLimit -> stringResource(R.string.remote_changes_comment_limit, MAX_REVIEW_COMMENTS)
                        failed -> stringResource(R.string.remote_changes_comment_failed)
                        else -> null
                    }
                if (error != null)
                    Text(
                        error,
                        Modifier.padding(top = 8.dp).testTag("changesCommentError"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            TextButton(onClick = { failed = !onSave(text) }, enabled = valid, modifier = Modifier.testTag("changesCommentSave")) {
                Text(stringResource(R.string.remote_changes_comment_save))
            }
        },
        dismissButton = {
            Row {
                if (canDelete)
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.remote_changes_comment_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_changes_comment_cancel)) }
            }
        },
    )
}

@Composable
private fun ReviewBar(comments: List<ReviewComment>, devBase: GitDevBase?, actions: ChangesActions) {
    val context = LocalContext.current
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.remote_changes_comment_count, comments.size, comments.size),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { actions.onUseReview(reviewPrompt(context, comments, devBase)) },
            modifier = Modifier.testTag("changesUseReview"),
        ) {
            Text(stringResource(R.string.remote_changes_use_review))
        }
    }
}

internal enum class QuickAction {
    REVERT,
    TESTS,
    EXPLAIN,
}

/** The composer text for a per-file quick action. */
internal fun quickActionPrompt(
    context: Context,
    action: QuickAction,
    path: String,
    base: GitBase,
    devBase: GitDevBase? = null,
): String =
    when (action) {
        QuickAction.REVERT ->
            context.getString(
                R.string.remote_changes_prompt_revert,
                path,
                when (base) {
                    GitBase.SESSION -> context.getString(R.string.remote_changes_prompt_base_session)
                    GitBase.HEAD -> context.getString(R.string.remote_changes_prompt_base_head)
                    GitBase.DEV -> context.getString(R.string.remote_changes_prompt_base_dev, devBaseRef(devBase))
                },
            )
        QuickAction.TESTS -> context.getString(R.string.remote_changes_prompt_tests, path)
        QuickAction.EXPLAIN -> context.getString(R.string.remote_changes_prompt_explain, path)
    }

/** The composer text for all pending [comments]. */
internal fun reviewPrompt(context: Context, comments: List<ReviewComment>, devBase: GitDevBase? = null): String =
    reviewPrompt(context.getString(R.string.remote_changes_review_intro), comments) { path, line, removed, base ->
        context.getString(
            if (removed) R.string.remote_changes_review_removed_line else R.string.remote_changes_review_line,
            path,
            line,
            when (base) {
                GitBase.SESSION -> context.getString(R.string.remote_changes_review_base_session)
                GitBase.HEAD -> context.getString(R.string.remote_changes_review_base_head)
                GitBase.DEV -> context.getString(R.string.remote_changes_review_base_dev, devBaseRef(devBase))
            },
        )
    }
