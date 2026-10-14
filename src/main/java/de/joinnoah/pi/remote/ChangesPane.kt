package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
internal fun ChangesPane(
    state: ChangesState,
    actions: ChangesActions,
    modifier: Modifier = Modifier,
    projectName: String? = null,
    /** No connection: a status shown says when it was read. */
    offline: Boolean = false,
) {
    PaneBackHandler { if (state.file != null) actions.onOpenFile(null) else actions.onClose() }
    // Review mode belongs to the two-pane layout, which gives the pane the window.
    val twoPane = LocalTwoPane.current
    val reviewing = twoPane?.reviewing == true
    // Back leaves review mode before anything else, as Esc does; registered after the pane's own.
    BackHandler(enabled = reviewing) { twoPane?.leaveReview() }
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
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val codeWrap = rememberCodeWrap()
    val diffKeys = remember { DiffKeys() }
    // Review mode starts on the first file, beside the list.
    val firstPath = state.status?.files?.firstOrNull()?.path
    LaunchedEffect(reviewing, firstPath) {
        if (reviewing && state.file == null && firstPath != null) actions.onOpenFile(firstPath)
    }
    val reviewKeys by rememberUpdatedState { key: ReviewKey ->
        when (key) {
            ReviewKey.NEXT_FILE,
            ReviewKey.PREVIOUS_FILE -> {
                val current = state.file.takeIf { state.fileSession == null }
                val step = if (key == ReviewKey.NEXT_FILE) 1 else -1
                adjacentId(state.status?.files.orEmpty().map { it.path }, current, step)
                    ?.takeIf { it != current }
                    ?.let { actions.onOpenFile(it) } != null
            }
            ReviewKey.NEXT_HUNK -> diffKeys.step(1)
            ReviewKey.PREVIOUS_HUNK -> diffKeys.step(-1)
            ReviewKey.COMMENT -> diffKeys.comment()
            ReviewKey.USE_REVIEW ->
                state.comments.isNotEmpty() && checked.onUseReview(reviewPrompt(context, state.comments, state.devBase))
        }
    }
    val keyTarget = LocalChatKeyTarget.current
    if (keyTarget != null)
        DisposableEffect(keyTarget) {
            val handler: (ReviewKey) -> Boolean = { reviewKeys(it) }
            keyTarget.addReviewHandler(handler)
            onDispose { keyTarget.removeReviewHandler(handler) }
        }
    val headerActions =
        changesHeaderActions(state, codeWrap, twoPane, reviewing) { prompt -> checked.onPrefill(prompt) }
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
                // Backspace, or Left at the edge, goes from a diff back to the file list.
                val parent = { actions.onOpenFile(null) }.takeIf { state.file != null }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().parentOnLeft(focusManager, parent)) {
                    val inset = if (prefillFailed) 0.dp else headerHeight
                    val file = state.file
                    val wrap = codeWrap.wrap
                    val width = maxWidth
                    when {
                        file == null -> ChangesOverview(state, checked, inset, offline)
                        // A wide pane keeps the file list beside the diff.
                        width >= FILE_LIST_BESIDE_MIN_WIDTH ->
                            Row(Modifier.fillMaxSize()) {
                                ChangesFileList(state, checked, inset, Modifier.width(FILE_LIST_WIDTH).fillMaxHeight())
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    val split = reviewing && width - FILE_LIST_WIDTH - PANE_DIVIDER_WIDTH >= SPLIT_DIFF_MIN_WIDTH
                                    key(file) { ChangesFileDiff(state, file, checked, inset, split, wrap, diffKeys) }
                                }
                            }
                        // Too narrow for the list, far too narrow for a split.
                        else -> key(file) { ChangesFileDiff(state, file, checked, inset, wrap = wrap, keys = diffKeys) }
                    }
                }
                if (state.comments.isNotEmpty()) ReviewBar(state.comments, state.devBase, checked)
            }
            Box(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                // The overview names the project and its branch; a diff names its file.
                val branch = state.status?.branch?.let { stringResource(R.string.remote_changes_branch, it) }
                val project = projectName?.takeIf(String::isNotBlank)
                PaneHeader(
                    title = stringResource(R.string.remote_changes_title),
                    subtitle =
                        state.file
                            ?: if (project != null && branch != null) stringResource(R.string.remote_review_subtitle, project, branch)
                            else project ?: branch,
                    nested = state.file != null,
                    busy = state.loading || state.diffLoading,
                    tag = "changes",
                    backLabel = stringResource(R.string.remote_changes_back_to_files),
                    closeLabel = stringResource(R.string.remote_changes_close),
                    reloadLabel = stringResource(R.string.remote_changes_reload),
                    onBack = { actions.onOpenFile(null) },
                    onClose = actions.onClose,
                    onReload = actions.onReload,
                    actions = headerActions,
                )
            }
        }
    }
}

/** From this pane width the file list stays beside an open diff or file. */
internal val FILE_LIST_BESIDE_MIN_WIDTH = 720.dp

/** The file list's width beside a diff or a file. */
internal val FILE_LIST_WIDTH = 280.dp

/** From this diff width review mode shows the old and the new side by side ([SplitDiffRow]). */
internal val SPLIT_DIFF_MIN_WIDTH = 900.dp

/** The open diff's side of the review keys: hunk steps and a comment on the focused line. */
private class DiffKeys {
    var step: (Int) -> Boolean = { false }
    var comment: () -> Boolean = { false }
}

/** The git prompts of the Ship menu; each one goes into the composer and is never sent by itself. */
internal enum class ShipAction {
    COMMIT,
    COMMIT_PUSH,
    BRANCH,
    PULL_REQUEST,
    REBASE,
}

/**
 * The Ship actions for a repository whose dev base is [devBase]: a pull request and a rebase need
 * one. Deterministic git operations are the host's to add; these are prompts for pi.
 */
internal fun shipActions(devBase: GitDevBase?): List<ShipAction> =
    ShipAction.entries.filter { (it != ShipAction.PULL_REQUEST && it != ShipAction.REBASE) || devBase?.ref != null }

/** What [action] is called in the Ship menu. */
internal fun shipLabel(context: Context, action: ShipAction, devBase: GitDevBase?): String =
    when (action) {
        ShipAction.COMMIT -> context.getString(R.string.remote_ship_commit)
        ShipAction.COMMIT_PUSH -> context.getString(R.string.remote_ship_commit_push)
        ShipAction.BRANCH -> context.getString(R.string.remote_ship_branch)
        ShipAction.PULL_REQUEST -> context.getString(R.string.remote_ship_pull_request, devBaseName(devBase))
        ShipAction.REBASE -> context.getString(R.string.remote_ship_rebase, devBaseName(devBase))
    }

/**
 * The composer text for [action]. A commit message and a branch name are the user's to finish,
 * so their prompts end where the name goes. A pull request names the branch, a rebase the full
 * ref, so pi rebases on `origin/main` and not a lagging local `main`.
 */
internal fun shipPrompt(context: Context, action: ShipAction, devBase: GitDevBase?): String =
    when (action) {
        ShipAction.COMMIT -> context.getString(R.string.remote_ship_prompt_commit)
        ShipAction.COMMIT_PUSH -> context.getString(R.string.remote_ship_prompt_commit_push)
        ShipAction.BRANCH -> context.getString(R.string.remote_ship_prompt_branch)
        ShipAction.PULL_REQUEST -> context.getString(R.string.remote_ship_prompt_pull_request, devBaseName(devBase))
        ShipAction.REBASE -> context.getString(R.string.remote_ship_prompt_rebase, devBaseRef(devBase))
    }

/**
 * The Changes header's actions: "Wrap lines" over a diff, Review in the two-pane layout, and the
 * Ship menu once the repository is known. [onPrefill] puts a Ship prompt into the composer.
 */
@Composable
private fun changesHeaderActions(
    state: ChangesState,
    codeWrap: CodeWrapState,
    twoPane: TwoPaneState?,
    reviewing: Boolean,
    onPrefill: (String) -> Unit,
): List<PaneAction> {
    val context = LocalContext.current
    val wrapLabel = stringResource(R.string.remote_review_wrap_lines)
    // One label; the toggle's state says whether review mode is on.
    val reviewLabel = stringResource(R.string.remote_review_enter)
    val shipLabel = stringResource(R.string.remote_ship_menu)
    // Older hosts name the dev base only with the dev comparison.
    val devBase = state.devBase?.takeIf { it.ref != null } ?: state.status?.devRef?.let(::GitDevBase)
    val repository = state.status?.available == true
    return buildList {
        if (state.file != null)
            add(PaneAction(wrapLabel, Icons.AutoMirrored.Filled.WrapText, "changesWrap", checked = codeWrap.wrap, onClick = codeWrap::toggle))
        if (twoPane != null)
            add(
                PaneAction(reviewLabel, Icons.Default.RateReview, "changesReview", checked = reviewing, shortcut = Shortcut.REVIEW) {
                    if (twoPane.reviewing) twoPane.leaveReview() else twoPane.enterReview()
                }
            )
        if (repository)
            add(
                PaneAction(
                    shipLabel,
                    Icons.Default.RocketLaunch,
                    "changesShip",
                    items =
                        shipActions(devBase).map { action ->
                            PaneMenuItem(shipLabel(context, action, devBase), "changesShip_${action.name.lowercase()}") {
                                onPrefill(shipPrompt(context, action, devBase))
                            }
                        },
                )
            )
    }
}

/**
 * A row of a file list beside a diff or a file: once Up or Down moved focus onto it, letting go
 * of the key runs [open]; null where the row does not open that way, such as the open file's
 * own. Holding the key walks the list and opens only the row it stops on, and the opened row is
 * always the focused one. Focus itself moves as usual.
 */
internal fun Modifier.openOnArrowRelease(open: (() -> Unit)?): Modifier =
    if (open == null) this
    else
        onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) &&
                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed)
                open()
            false
        }

/** The changed files beside a diff, as rows; Up and Down open the file above or below. */
@Composable
private fun ChangesFileList(state: ChangesState, actions: ChangesActions, headerHeight: Dp, modifier: Modifier) {
    val files = state.status?.files.orEmpty()
    // A subagent's file is not in the list; none shows as open then.
    val open = state.file.takeIf { state.fileSession == null }
    val label = stringResource(R.string.remote_review_file_list)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        modifier.semantics { contentDescription = label }.testTag("changesFileList"),
        contentPadding = PaddingValues(top = headerHeight + 8.dp, bottom = 8.dp),
    ) {
        itemsIndexed(files, key = { _, file -> file.path }) { _, file ->
            val selected = file.path == open
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Unspecified)
                    .openOnArrowRelease(if (selected) null else ({ actions.onOpenFile(file.path) }))
                    .focusRing(MaterialTheme.shapes.small)
                    .selectable(selected) { actions.onOpenFile(file.path) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("changesFileListItem"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if ('/' in file.path)
                        Text(file.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                            color = muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
                if (file.additions != null && file.deletions != null) {
                    Text("+${file.additions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("−${file.deletions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
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

/** The time of day [at], with its date when that is not today. */
internal fun updatedTime(context: Context, at: Long): String =
    if (DateUtils.isToday(at)) DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME) else formatTime(context, at)

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

/** The changes shown could not be read again; [onRetry] tries once more. */
@Composable
private fun StaleNotice(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("changesStale")) {
        Notice(text, Modifier.testTag("changesUpdated"))
        TextButton(onClick = onRetry, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.remote_changes_retry)) }
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
private fun ChangesOverview(state: ChangesState, actions: ChangesActions, headerHeight: Dp, offline: Boolean = false) {
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
            state.fetchedAt?.takeIf { (offline || state.stale) && status != null && state.failure == null }?.let { at ->
                item(key = "updated", span = { GridItemSpan(maxLineSpan) }) {
                    val time = updatedTime(context, at)
                    if (offline) Notice(stringResource(R.string.remote_changes_updated_at, time), Modifier.testTag("changesUpdated"))
                    else StaleNotice(stringResource(R.string.remote_changes_stale_at, time), actions.onReload)
                }
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
        path = file.path,
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

/**
 * One file's diff with line comments. [split] shows the old and the new side by side where the
 * patch allows it ([splitDiff]); [wrap] breaks long lines. The review keys reach it through [keys].
 */
@Composable
private fun ChangesFileDiff(
    state: ChangesState,
    path: String,
    actions: ChangesActions,
    headerHeight: Dp,
    split: Boolean = false,
    wrap: Boolean = false,
    keys: DiffKeys = remember { DiffKeys() },
) {
    val context = LocalContext.current
    val diff = state.diff?.takeIf { it.path == path }
    val lines = remember(diff?.patch) { diff?.patch?.let(::patchDiff).orEmpty() }
    val splitRows = remember(diff?.patch, lines, split) { if (split) diff?.patch?.let { splitDiff(it, lines) } else null }
    val zoom = CodeZoom.scale(CodeZoom.CHANGES)
    val rowStyle = rememberDiffRowStyle(lines, zoom = zoom)
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val base = state.status?.base ?: state.base
    // Line numbers belong to one base: a comment shows only on the diff it was written on.
    val comments =
        remember(state.comments, path, base, state.fileSession) {
            if (state.fileSession != null) emptyMap()
            else state.comments.filter { it.path == path && it.base == base }.associateBy { it.oldLine to it.newLine }
        }
    val atLimit = state.comments.size >= MAX_REVIEW_COMMENTS
    var editing by remember { mutableStateOf<DiffLine?>(null) }
    val copyLine = rememberCopyToClipboard(stringResource(R.string.remote_menu_copy_line))
    val copyHunk = rememberCopyToClipboard(stringResource(R.string.remote_menu_copy_hunk))
    val copyPicked = rememberCopyToClipboard(stringResource(R.string.remote_code_copy_label))
    val pickColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    val addLabel = stringResource(R.string.remote_changes_comment_add)
    val editLabel = stringResource(R.string.remote_changes_comment_edit)
    val pickLabel = stringResource(R.string.remote_changes_diff_pick)
    // The picked lines, by their 1-based index into [lines]. A new patch starts with none.
    var picked by rememberSaveable(path, base, diff?.patch, stateSaver = LineSelectionSaver) { mutableStateOf<LineSelection?>(null) }
    val gutterRows = remember(listState) { GutterRows(listState) }
    val focus = remember { FocusRequester() }
    var paneFocused by remember { mutableStateOf(false) }
    // Where a drag over the number cells began; the drag reads it, not a pick composition may lag behind.
    var dragFrom by remember { mutableIntStateOf(1) }
    // Ctrl+A picks every line, Ctrl+C copies the picked ones without their prefixes, Ctrl+= and Ctrl+- zoom.
    val onCodeKey: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (resolveCodeShortcut(event)) {
            Shortcut.SELECT_ALL_LINES -> allLines(lines.size)?.let { picked = it; true } ?: false
            Shortcut.COPY_LINES -> picked?.let { copyPicked(selectedDiffText(lines, it, prefixed = false)); true } ?: false
            Shortcut.ZOOM_IN -> { CodeZoom.step(CodeZoom.CHANGES, inward = true); true }
            Shortcut.ZOOM_OUT -> { CodeZoom.step(CodeZoom.CHANGES, inward = false); true }
            else -> false
        }
    }
    // A subagent's file: its changes are not the session's to comment on or act on from here.
    val subagent = state.fileSession?.let { id -> state.subagents.firstOrNull { it.sessionId == id } }
    val readOnly = state.fileSession != null
    // The keyboard's line for "c"; without one, the first changed line of the hunk in view.
    var focusedLine by remember { mutableStateOf<DiffLine?>(null) }
    // The rows as the list shows them, and where each hunk starts among them.
    val rowCount = splitRows?.size ?: lines.size
    val hunkRows =
        remember(lines, splitRows) {
            if (splitRows != null) splitRows.indices.filter { splitRows[it].old?.let(lines::get)?.kind == DiffKind.HUNK }
            else lines.indices.filter { lines[it].kind == DiffKind.HUNK }
        }
    val showsRows = state.diffFailure == null && !state.diffLoading && diff != null && diff.omitted == null && !diff.binary && lines.isNotEmpty()
    // Items before the rows: the actions, or a subagent's notice, and a split diff's heading.
    val leading = if (splitRows != null && showsRows) 2 else 1
    SideEffect {
        keys.step = { direction ->
            val first = listState.firstVisibleItemIndex - leading
            val target =
                if (!showsRows) null
                else if (direction > 0) hunkRows.firstOrNull { it > first }
                else hunkRows.lastOrNull { it < first }
            target?.let { scope.launch { listState.scrollToItem(it + leading) } } != null
        }
        keys.comment = comment@{
            if (readOnly || !showsRows) return@comment false
            val first = (listState.firstVisibleItemIndex - leading).coerceIn(0, maxOf(rowCount - 1, 0))
            // The first changed line from the hunk in view on, else the first line that takes one.
            val start = splitRows?.getOrNull(first)?.let { it.old ?: it.new } ?: first
            // The picked line comes first: a click picks it, and the keyboard follows what the user chose.
            val pickedLine = picked?.let { range -> (range.first..range.last).asSequence().mapNotNull { lines.getOrNull(it - 1) }.firstOrNull { it.kind != DiffKind.HUNK } }
            val target =
                pickedLine
                    ?: focusedLine
                    ?: (start until lines.size).asSequence().map(lines::get).firstOrNull { it.kind == DiffKind.ADDED || it.kind == DiffKind.REMOVED }
                    ?: lines.firstOrNull { it.kind != DiffKind.HUNK }
            target?.let { editing = it } != null
        }
    }
    SideEffect {
        gutterRows.lineOfItem = { item -> (item - leading).takeIf { it in lines.indices && lines[it].kind != DiffKind.HUNK }?.plus(1) }
    }
    DisposableEffect(keys) {
        onDispose {
            keys.step = { false }
            keys.comment = { false }
        }
    }
    // A click picks the line; a second click on the only picked line clears the pick.
    fun pickLine(number: Int) {
        picked = if (picked == LineSelection(number)) null else LineSelection(number)
    }
    fun Modifier.pickable(line: DiffLine, number: Int): Modifier =
        onFocusChanged { if (it.isFocused) focusedLine = line else if (focusedLine == line) focusedLine = null }
            .clickable(onClickLabel = pickLabel) {
                // The row keeps the keyboard it has; the list takes it only when nothing in it has.
                if (!paneFocused) focus.requestFocus()
                pickLine(number)
            }
            .testTag("changesDiffLine")
    // A screen reader lists the comment next to the pick, as the gutter button does for the eye.
    fun Modifier.commentAction(line: DiffLine, commentable: Boolean): Modifier =
        if (!commentable) this else semantics { customActions = listOf(CustomAccessibilityAction(addLabel) { editing = line; true }) }
    // The session's own file opens in Files at the line as it is now; a subagent's lies elsewhere.
    val fileOpener = LocalFileOpener.current?.takeIf { !readOnly }
    fun lineMenu(line: DiffLine, index: Int, commentable: Boolean): Modifier =
        Modifier.contextMenu { _ ->
            val opening =
                if (line.kind == DiffKind.HUNK) null
                else newLineNear(lines, index)?.let { at -> fileOpener?.action(path, LineSelection(at)) as? FileOpenAction.Open }
            diffLineMenuActions(commentable, openable = opening != null, copyWithPrefix = true).map { action ->
                MenuItem(action.label, "diff_${action.name.lowercase()}") {
                    when (action) {
                        DiffLineMenuAction.COMMENT -> editing = line
                        DiffLineMenuAction.COPY_LINE -> copyLine(line.text)
                        // The picked lines when this one is among them, else this line alone.
                        DiffLineMenuAction.COPY_WITH_PREFIX ->
                            copyPicked(selectedDiffText(lines, picked?.takeIf { index + 1 in it.first..it.last } ?: LineSelection(index + 1), prefixed = true))
                        DiffLineMenuAction.COPY_HUNK -> copyHunk(diffHunkText(lines, index))
                        DiffLineMenuAction.OPEN_FILE -> opening?.let { fileOpener?.perform(it) }
                    }
                }
            }
        }
    @Composable
    fun LineComment(line: DiffLine) {
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
    LazyColumn(
        Modifier.fillMaxSize()
            .testTag("changesDiff")
            .onPlaced { gutterRows.coordinates = it }
            .focusRequester(focus)
            .onFocusChanged { paneFocused = it.hasFocus }
            .focusable()
            // After the rows: an in-row text selection copies its own text first.
            .onKeyEvent(onCodeKey)
            .takeFocusOnPress(focus, { paneFocused })
            .codeZoomGestures(CodeZoom.CHANGES),
        state = listState,
        contentPadding = PaddingValues(top = headerHeight),
    ) {
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
            splitRows != null -> {
                // A hunk header spans both sides; every other row pairs an old and a new line.
                item(key = "splitHeading", contentType = "splitHeading") { SplitDiffHeading() }
                itemsIndexed(splitRows, contentType = { _, row -> if (row.old != null && row.old == row.new) lines[row.old].kind else "pair" }) { _, row ->
                    SplitDiffItem(
                        row,
                        lines,
                        rowStyle,
                        scroll,
                        wrap,
                        cell = { line, index ->
                            val commentable = line.kind != DiffKind.HUNK && !readOnly
                            val number = index + 1
                            val pickedHere = picked?.let { number in it.first..it.last } == true
                            lineMenu(line, index, commentable)
                                .then(if (line.kind != DiffKind.HUNK) Modifier.pickable(line, number) else Modifier)
                                .then(if (pickedHere) Modifier.background(pickColor) else Modifier)
                                .commentAction(line, commentable)
                        },
                        // Touch has no menu key here: the picked cell carries the gutter's comment button.
                        leading = { line, index ->
                            val number = index + 1
                            if (line.kind != DiffKind.HUNK && !readOnly && picked?.let { number in it.first..it.last } == true)
                                GutterCommentButton(true, addLabel) { editing = line }
                        },
                    ) { LineComment(it) }
                }
                if (diff.truncated)
                    item(key = "truncated") { Notice(stringResource(R.string.remote_changes_diff_truncated)) }
            }
            else -> {
                // Rows stay one text line high, below the 48dp touch target, to keep code readable;
                // an accepted trade-off, as in the tool detail diff.
                itemsIndexed(lines, contentType = { _, line -> line.kind }) { index, line ->
                    val commentable = line.kind != DiffKind.HUNK && !readOnly
                    val number = index + 1
                    val (hover, hovered) = rememberRowHover()
                    val pickedHere = picked?.let { number in it.first..it.last } == true
                    // The gutter takes a press on a line number: a tap picks, Shift extends, a drag picks a range.
                    val gutter =
                        if (line.kind == DiffKind.HUNK) Modifier
                        else
                            Modifier.codeGutter(
                                line = number,
                                rows = gutterRows,
                                tapLabel = pickLabel,
                                onTap = { extend ->
                                    if (!paneFocused) focus.requestFocus()
                                    if (extend) picked = picked.extendTo(number) else pickLine(number)
                                },
                                onStart = { start, extend ->
                                    if (!paneFocused) focus.requestFocus()
                                    dragFrom = dragAnchor(picked, start, extend)
                                    picked = dragRange(dragFrom, start)
                                },
                                onDrag = { end -> picked = dragRange(dragFrom, end) },
                                // The row is the focus stop and Enter on it picks; two more stops per row would crowd the Tab order.
                                focusable = false,
                            ).testTag("changesGutter:$number")
                    val comment: @Composable () -> Unit = { GutterCommentButton(hovered || pickedHere, addLabel) { editing = line } }
                    Column {
                        DiffRow(
                            line,
                            rowStyle,
                            scroll,
                            hover
                                .then(lineMenu(line, index, commentable))
                                .then(if (line.kind != DiffKind.HUNK) Modifier.pickable(line, number) else Modifier)
                                .commentAction(line, commentable),
                            wrap,
                            selected = pickedHere,
                            selectable = true,
                            // Every row of a session's diff keeps the button's width, so the code lines up under a hunk header too.
                            leading = if (commentable) comment else if (!readOnly) ({ GutterCommentSpace() }) else null,
                            gutter = gutter,
                        )
                        LineComment(line)
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
