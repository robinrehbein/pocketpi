package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
)

/**
 * The changes of one session against a base: the file list with the commit log, or one file's
 * diff with line comments. It fills whatever container it is given, so the phone overlay and a
 * later tablet inspector can share it.
 */
@Composable
internal fun ChangesPane(state: ChangesState, actions: ChangesActions, modifier: Modifier = Modifier) {
    BackHandler { if (state.file != null) actions.onOpenFile(null) else actions.onClose() }
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
            )
        }
    Surface(modifier.fillMaxSize().testTag("changesPane")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ChangesTopBar(state, actions)
            HorizontalDivider()
            if (prefillFailed)
                Text(
                    stringResource(R.string.remote_changes_prefill_failed),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("changesPrefillFailed"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val file = state.file
                if (file == null) ChangesOverview(state, checked)
                // A new file starts with fresh scroll and dialog state.
                else key(file) { ChangesFileDiff(state, file, checked) }
            }
            if (state.comments.isNotEmpty()) ReviewBar(state.comments, checked)
        }
    }
}

@Composable
private fun ChangesTopBar(state: ChangesState, actions: ChangesActions) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.file != null)
            IconButton(onClick = { actions.onOpenFile(null) }, Modifier.testTag("changesBack")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_changes_back_to_files))
            }
        else
            IconButton(onClick = actions.onClose, Modifier.testTag("changesClose")) {
                Icon(Icons.Default.Close, stringResource(R.string.remote_changes_close))
            }
        Text(
            state.file ?: stringResource(R.string.remote_changes_title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (state.file != null) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        IconButton(onClick = actions.onReload, enabled = !state.loading, modifier = Modifier.testTag("changesReload")) {
            Icon(Icons.Default.Refresh, stringResource(R.string.remote_changes_reload))
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

private fun baseLabel(base: GitBase): Int =
    when (base) {
        GitBase.SESSION -> R.string.remote_changes_base_session
        GitBase.HEAD -> R.string.remote_changes_base_head
        GitBase.DEV -> R.string.remote_changes_base_dev
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
private fun ChangesOverview(state: ChangesState, actions: ChangesActions) {
    val context = LocalContext.current
    val status = state.status
    val shownBase = status?.base ?: state.base
    val commentCounts =
        remember(state.comments, shownBase) {
            state.comments.filter { it.base == shownBase }.groupingBy { it.path }.eachCount()
        }
    LazyColumn(Modifier.fillMaxSize().testTag("changesList")) {
        item(key = "bases") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GitBase.entries.forEach { base ->
                    FilterChip(
                        selected = base == shownBase,
                        onClick = { if (base != shownBase || state.failure != null) actions.onSelectBase(base) },
                        label = { Text(stringResource(baseLabel(base))) },
                        modifier = Modifier.testTag("changesBase_${base.wire}"),
                    )
                }
            }
        }
        if (state.sessionUnavailable != null)
            item(key = "fallback") {
                Notice(stringResource(R.string.remote_changes_session_fallback), Modifier.testTag("changesFallback"))
            }
        status?.since?.let { since ->
            item(key = "since") {
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
        status?.devRef?.let { ref ->
            item(key = "devRef") { Notice(stringResource(R.string.remote_changes_dev_ref, ref)) }
        }
        status?.branch?.let { branch ->
            item(key = "branch") { Notice(stringResource(R.string.remote_changes_branch, branch)) }
        }
        val failure = state.failure
        when {
            failure != null -> item(key = "failure") { FailureNotice(failure, actions.onReload) }
            state.loading || status == null -> item(key = "loading") { Loading() }
            status.unavailable != null ->
                item(key = "unavailable") {
                    Notice(stringResource(unavailableText(status.unavailable)), Modifier.testTag("changesUnavailable"))
                }
            else -> {
                if (status.files.isEmpty())
                    item(key = "empty") { Notice(stringResource(R.string.remote_changes_empty)) }
                items(status.files, key = { "file:" + it.path }) { file ->
                    FileRow(file, commentCounts[file.path] ?: 0) { actions.onOpenFile(file.path) }
                }
                if (status.truncated)
                    item(key = "truncated") { Notice(stringResource(R.string.remote_changes_truncated)) }
                commits(state, context, actions.onReload)
            }
        }
    }
}

private fun LazyListScope.commits(state: ChangesState, context: Context, onRetry: () -> Unit) {
    if (state.status?.base == GitBase.HEAD) return
    item(key = "commitsHeader") {
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
        failure != null -> item(key = "commitsFailure") { FailureNotice(failure, onRetry) }
        state.logLoading || log == null -> item(key = "commitsLoading") { Loading() }
        log.unavailable != null ->
            item(key = "commitsUnavailable") { Notice(stringResource(R.string.remote_changes_commits_unavailable)) }
        else -> {
            if (log.commits.isEmpty())
                item(key = "commitsEmpty") { Notice(stringResource(R.string.remote_changes_commits_empty)) }
            items(log.commits, key = { "commit:" + it.sha }) { commit ->
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
                item(key = "commitsTruncated") { Notice(stringResource(R.string.remote_changes_commits_truncated)) }
        }
    }
}

@Composable
private fun FileRow(file: GitFile, comments: Int, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val counts =
        if (file.additions != null && file.deletions != null)
            stringResource(R.string.remote_changes_line_counts, file.additions, file.deletions)
        else null
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp).testTag("changesFile")
    ) {
        Text(
            file.path,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

@Composable
private fun ChangesFileDiff(state: ChangesState, path: String, actions: ChangesActions) {
    val context = LocalContext.current
    val diff = state.diff?.takeIf { it.path == path }
    val lines = remember(diff?.patch) { diff?.patch?.let(::patchDiff).orEmpty() }
    val rowStyle = rememberDiffRowStyle(lines)
    val scroll = rememberScrollState()
    val base = state.status?.base ?: state.base
    // Line numbers belong to one base: a comment shows only on the diff it was written on.
    val comments =
        remember(state.comments, path, base) {
            state.comments.filter { it.path == path && it.base == base }.associateBy { it.oldLine to it.newLine }
        }
    val atLimit = state.comments.size >= MAX_REVIEW_COMMENTS
    var editing by remember { mutableStateOf<DiffLine?>(null) }
    val addLabel = stringResource(R.string.remote_changes_comment_add)
    val editLabel = stringResource(R.string.remote_changes_comment_edit)
    LazyColumn(Modifier.fillMaxSize().testTag("changesDiff")) {
        item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.REVERT, path, base)) },
                    label = { Text(stringResource(R.string.remote_changes_action_revert)) },
                    modifier = Modifier.testTag("changesActionRevert"),
                )
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.TESTS, path, base)) },
                    label = { Text(stringResource(R.string.remote_changes_action_tests)) },
                    modifier = Modifier.testTag("changesActionTests"),
                )
                AssistChip(
                    onClick = { actions.onPrefill(quickActionPrompt(context, QuickAction.EXPLAIN, path, base)) },
                    label = { Text(stringResource(R.string.remote_changes_action_explain)) },
                    modifier = Modifier.testTag("changesActionExplain"),
                )
            }
        }
        val failure = state.diffFailure
        when {
            failure != null -> item(key = "failure") { FailureNotice(failure) { actions.onOpenFile(path) } }
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
                    val commentable = line.kind != DiffKind.HUNK
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
private fun ReviewBar(comments: List<ReviewComment>, actions: ChangesActions) {
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
            onClick = { actions.onUseReview(reviewPrompt(context, comments)) },
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
internal fun quickActionPrompt(context: Context, action: QuickAction, path: String, base: GitBase): String =
    when (action) {
        QuickAction.REVERT ->
            context.getString(
                R.string.remote_changes_prompt_revert,
                path,
                context.getString(
                    when (base) {
                        GitBase.SESSION -> R.string.remote_changes_prompt_base_session
                        GitBase.HEAD -> R.string.remote_changes_prompt_base_head
                        GitBase.DEV -> R.string.remote_changes_prompt_base_dev
                    }
                ),
            )
        QuickAction.TESTS -> context.getString(R.string.remote_changes_prompt_tests, path)
        QuickAction.EXPLAIN -> context.getString(R.string.remote_changes_prompt_explain, path)
    }

/** The composer text for all pending [comments]. */
internal fun reviewPrompt(context: Context, comments: List<ReviewComment>): String =
    reviewPrompt(context.getString(R.string.remote_changes_review_intro), comments) { path, line, removed, base ->
        context.getString(
            if (removed) R.string.remote_changes_review_removed_line else R.string.remote_changes_review_line,
            path,
            line,
            context.getString(
                when (base) {
                    GitBase.SESSION -> R.string.remote_changes_review_base_session
                    GitBase.HEAD -> R.string.remote_changes_review_base_head
                    GitBase.DEV -> R.string.remote_changes_review_base_dev
                }
            ),
        )
    }
