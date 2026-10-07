package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the tree sheet shows: the tree is loading, loaded, or could not be read. */
private sealed interface TreeUi {
    data object Loading : TreeUi

    data class Loaded(val tree: SessionTree) : TreeUi

    data class Failed(val failure: TreeFailure) : TreeUi
}

/** Rows indent by this much per level, up to [MAX_TREE_INDENT] levels. */
private val INDENT_STEP = 12.dp

@StringRes
private fun kindLabel(kind: TreeNodeKind): Int =
    when (kind) {
        TreeNodeKind.USER -> R.string.remote_tree_kind_user
        TreeNodeKind.ASSISTANT -> R.string.remote_tree_kind_assistant
        TreeNodeKind.TOOL -> R.string.remote_tree_kind_tool
        TreeNodeKind.COMPACTION -> R.string.remote_tree_kind_compaction
        TreeNodeKind.BRANCH_SUMMARY -> R.string.remote_tree_kind_branch_summary
        TreeNodeKind.CUSTOM_MESSAGE -> R.string.remote_tree_kind_custom_message
    }

/**
 * `/tree`: the session's tree, with "Continue from here" (moves the session in place) and "Fork
 * from here" (a new session) for the row the user taps. [load] reads the tree, [navigate] moves the
 * session ([scope] runs it); [onFork] starts the fork, and [onAbort] stops a running summary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionTreeSheet(
    state: RemoteState,
    scope: CoroutineScope,
    load: suspend () -> TreeLoadResult,
    navigate: suspend (nodeId: String, summarize: Boolean) -> TreeNavigateResult,
    onFork: (nodeId: String) -> Unit,
    onAbort: () -> Unit,
    onDismiss: () -> Unit,
) {
    var ui by remember { mutableStateOf<TreeUi>(TreeUi.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    // The summary choice of the running navigation; null while none runs.
    var running by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val loadLatest by rememberUpdatedState(load)
    val busy by rememberUpdatedState(running != null)

    LaunchedEffect(reload) {
        ui = TreeUi.Loading
        ui =
            when (val result = loadLatest()) {
                is TreeLoadResult.Loaded -> TreeUi.Loaded(result.tree)
                is TreeLoadResult.Failed -> TreeUi.Failed(result.failure)
            }
    }

    fun navigateTo(nodeId: String, summarize: Boolean) {
        if (running != null) return
        running = summarize
        notice = null
        scope.launch {
            when (val result = navigate(nodeId, summarize)) {
                is TreeNavigateResult.Done -> onDismiss()
                is TreeNavigateResult.Failed -> {
                    running = null
                    notice = treeFailureText(result.failure)
                    // The tree changed under us, or the move may have happened.
                    if (result.failure in setOf(TreeFailure.NOT_FOUND, TreeFailure.INVALID, TreeFailure.UNKNOWN_RESULT))
                        reload++
                }
            }
        }
    }

    val sheetState =
        rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag("treeSheet"),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
            Text(
                stringResource(R.string.remote_tree_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            when (val current = ui) {
                TreeUi.Loading ->
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
                is TreeUi.Failed ->
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(treeFailureText(current.failure)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("treeError"),
                        )
                        OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.remote_tree_retry))
                        }
                    }
                is TreeUi.Loaded -> {
                    val tree = current.tree
                    val selected = tree.nodes.firstOrNull { it.id == selectedId }
                    TreeList(
                        tree = tree,
                        selectedId = selected?.id,
                        enabled = running == null,
                        onSelect = { selectedId = it },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected != null)
                        TreeActions(
                            state = state,
                            tree = tree,
                            node = selected,
                            running = running,
                            notice = notice,
                            onContinue = { asking = true },
                            onFork = { onFork(selected.id) },
                            onCancel = onAbort,
                        )
                    else if (notice != null)
                        Text(
                            stringResource(checkNotNull(notice)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                }
            }
        }
    }

    val target = (ui as? TreeUi.Loaded)?.tree?.nodes?.firstOrNull { it.id == selectedId }
    if (asking && target != null)
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.remote_tree_summarize_title)) },
            text = { Text(stringResource(R.string.remote_tree_summarize_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        navigateTo(target.id, true)
                    },
                    modifier = Modifier.testTag("treeSummarize"),
                ) {
                    Text(stringResource(R.string.remote_tree_summarize))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        asking = false
                        navigateTo(target.id, false)
                    },
                    modifier = Modifier.testTag("treeNoSummary"),
                ) {
                    Text(stringResource(R.string.remote_tree_no_summary))
                }
            },
        )
}

@Composable
private fun TreeList(
    tree: SessionTree,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val depths = remember(tree) { treeDepths(tree.nodes) }
    val listState = rememberLazyListState()
    // Open at the current point, which is usually at the end.
    LaunchedEffect(tree) {
        val leaf = tree.nodes.indexOfFirst { it.id == tree.leafId }
        if (leaf > 0) listState.scrollToItem((leaf - 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tree.truncated)
            item(key = "truncated") {
                Text(
                    stringResource(R.string.remote_tree_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp).testTag("treeTruncated"),
                )
            }
        if (tree.nodes.isEmpty())
            item(key = "empty") {
                Text(stringResource(R.string.remote_tree_empty), modifier = Modifier.testTag("treeEmpty"))
            }
        itemsIndexed(tree.nodes, key = { _, node -> node.id }) { _, node ->
            TreeRow(
                node = node,
                indent = treeIndent(depths[node.id] ?: 0),
                current = node.id == tree.leafId,
                selected = node.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(node.id) },
            )
        }
    }
}

@Composable
private fun TreeRow(
    node: TreeNode,
    indent: Int,
    current: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val time =
        remember(node.timestamp) {
            treeTimestampMillis(node.timestamp)?.let {
                DateUtils.formatDateTime(
                    context,
                    it,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                )
            }
        }
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = INDENT_STEP * indent)
                .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag("treeRow"),
        shape = RoundedCornerShape(14.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(kindLabel(node.kind)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current)
                    Text(
                        stringResource(R.string.remote_tree_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("treeCurrent"),
                    )
                if (node.children > 1)
                    Text(
                        stringResource(R.string.remote_tree_branch),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("treeBranch"),
                    )
            }
            if (node.preview.isEmpty())
                Text(
                    stringResource(R.string.remote_tree_no_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
            else
                Text(
                    node.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            if (time != null)
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun TreeActions(
    state: RemoteState,
    tree: SessionTree,
    node: TreeNode,
    running: Boolean?,
    notice: Int?,
    onContinue: () -> Unit,
    onFork: () -> Unit,
    onCancel: () -> Unit,
) {
    val availability = continueAvailability(state, tree, node)
    val canFork = canForkFromNode(state, node)
    val idle = running == null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running != null) {
            Text(
                stringResource(if (running) R.string.remote_tree_summarizing else R.string.remote_tree_moving),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("treeRunning"),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (running)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("treeCancel")) {
                    Text(stringResource(R.string.remote_tree_cancel))
                }
        }
        if (notice != null)
            Text(
                stringResource(notice),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("treeNotice"),
            )
        Button(
            onClick = onContinue,
            enabled = idle && availability == TreeContinue.AVAILABLE,
            modifier = Modifier.fillMaxWidth().testTag("treeContinue"),
        ) {
            Text(
                stringResource(
                    if (availability == TreeContinue.ALREADY_HERE) R.string.remote_tree_already_here
                    else R.string.remote_tree_continue
                )
            )
        }
        when (availability) {
            TreeContinue.NOT_NAVIGABLE -> Hint(R.string.remote_tree_continue_not_navigable)
            TreeContinue.NOT_IDLE -> Hint(R.string.remote_tree_continue_not_idle)
            else -> Unit
        }
        OutlinedButton(
            onClick = onFork,
            enabled = idle && canFork,
            modifier = Modifier.fillMaxWidth().testTag("treeFork"),
        ) {
            Text(stringResource(R.string.remote_tree_fork))
        }
        if (!node.forkable) Hint(R.string.remote_tree_fork_not_forkable)
        else if (!forkStopped(state)) Hint(R.string.remote_tree_fork_not_stopped)
    }
}

@Composable
private fun Hint(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
