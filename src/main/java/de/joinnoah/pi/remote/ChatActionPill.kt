package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private fun ChatAction.icon(): ImageVector =
    when (this) {
        ChatAction.CHANGES -> Icons.Default.Difference
        ChatAction.FILES -> Icons.Default.FolderOpen
        ChatAction.JOBS -> Icons.Outlined.Terminal
        ChatAction.TREE -> Icons.Default.AccountTree
        ChatAction.ARTIFACTS -> Icons.Default.Collections
        ChatAction.RENAME -> Icons.Default.Edit
        ChatAction.EXPORT -> Icons.Default.Share
        ChatAction.REFRESH -> Icons.Default.Refresh
        ChatAction.RELOAD -> Icons.Default.RestartAlt
        ChatAction.SETTINGS -> Icons.Default.Settings
        ChatAction.NEW_SESSION -> Icons.Default.Add
        ChatAction.SESSION_SETTINGS -> Icons.Outlined.Tune
        ChatAction.HISTORY -> Icons.AutoMirrored.Filled.List
        ChatAction.STATUS -> Icons.Outlined.Info
        ChatAction.CLOSE -> Icons.Default.DeleteOutline
    }

/** The key that runs the same action, for its tooltip. */
private fun ChatAction.shortcut(): Shortcut? =
    when (this) {
        ChatAction.CHANGES -> Shortcut.CHANGES
        ChatAction.FILES -> Shortcut.FILES
        ChatAction.JOBS -> Shortcut.JOBS
        ChatAction.RENAME -> Shortcut.RENAME
        ChatAction.REFRESH -> Shortcut.REFRESH
        ChatAction.SETTINGS -> Shortcut.SETTINGS
        ChatAction.NEW_SESSION -> Shortcut.NEW_SESSION
        ChatAction.HISTORY -> Shortcut.HISTORY
        ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.EXPORT, ChatAction.RELOAD,
        ChatAction.SESSION_SETTINGS, ChatAction.STATUS, ChatAction.CLOSE -> null
    }

internal fun ChatAction.label(): Int =
    when (this) {
        ChatAction.CHANGES -> R.string.remote_changes_open
        ChatAction.FILES -> R.string.remote_files_open
        ChatAction.JOBS -> R.string.remote_jobs_title
        ChatAction.TREE -> R.string.remote_tree_title
        ChatAction.ARTIFACTS -> R.string.remote_artifacts_title
        ChatAction.RENAME -> R.string.remote_rename_session
        ChatAction.EXPORT -> R.string.remote_chat_action_export
        ChatAction.REFRESH -> R.string.remote_refresh
        ChatAction.RELOAD -> R.string.remote_chat_action_reload
        ChatAction.SETTINGS -> R.string.remote_settings
        ChatAction.NEW_SESSION -> R.string.remote_new_session
        ChatAction.SESSION_SETTINGS -> R.string.remote_session_settings_title
        ChatAction.HISTORY -> R.string.remote_chat_history
        ChatAction.STATUS -> R.string.remote_chat_status
        ChatAction.CLOSE -> R.string.remote_session_close
    }

/**
 * The chat header pill: the first actions as buttons, the rest in a menu behind a chevron. The menu
 * names every entry next to its icon and opens under the chevron wherever the pill sits.
 */
@Composable
internal fun ChatActionPill(
    layout: ChatActionLayout,
    color: Color,
    onAction: (ChatAction) -> Unit,
    /** A dot on the menu button: the menu holds an entry that has news, such as the status banners. */
    badge: Boolean = false,
) {
    FloatingSurface(shape = CircleShape, color = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (action in layout.shown)
                ShortcutTooltip(stringResource(action.label()), action.shortcut()) {
                    IconButton(
                        onClick = { onAction(action) },
                        modifier = Modifier.size(48.dp).testTag("chatAction_${action.name.lowercase()}"),
                    ) {
                        Icon(action.icon(), stringResource(action.label()))
                    }
                }
            if (layout.menu.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    val moreLabel =
                        stringResource(
                            when {
                                open -> R.string.remote_chat_actions_close
                                badge -> R.string.remote_chat_actions_more_status
                                else -> R.string.remote_chat_actions_more
                            }
                        )
                    ShortcutTooltip(moreLabel) {
                        IconButton(
                            onClick = { open = !open },
                            modifier = Modifier.size(48.dp).testTag("chatActionsMore"),
                        ) {
                            if (badge && !open)
                                BadgedBox(badge = { Badge() }) { Icon(Icons.Default.ExpandMore, moreLabel) }
                            else Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, moreLabel)
                        }
                    }
                    ChatActionMenu(
                        layout.menu,
                        open = open,
                        badge = badge,
                        onDismiss = { open = false },
                        onAction = { action ->
                            open = false
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The pill's menu: one labelled row per action, in [ChatAction] order. Close, the only destructive
 * entry, sits apart at the end in the error colour; the status entry carries the chevron's [badge].
 */
@Composable
private fun ChatActionMenu(
    actions: List<ChatAction>,
    open: Boolean,
    badge: Boolean,
    onDismiss: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, modifier = Modifier.testTag("chatActionsMenu")) {
        for (action in actions) {
            val destructive = action == ChatAction.CLOSE
            if (destructive && actions.size > 1) HorizontalDivider()
            val error = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(stringResource(action.label())) },
                onClick = { onAction(action) },
                leadingIcon = { Icon(action.icon(), contentDescription = null) },
                trailingIcon = if (badge && action == ChatAction.STATUS) ({ Badge() }) else null,
                colors =
                    if (destructive) MenuDefaults.itemColors(textColor = error, leadingIconColor = error)
                    else MenuDefaults.itemColors(),
                modifier = Modifier.testTag("chatActionMenu_${action.name.lowercase()}"),
            )
        }
    }
}

/**
 * Wires [ChangesPane] to [model]; prefilled prompts move focus to the composer via [focusComposer].
 * [flushComposer] first hands the composer's typing to the draft the prefill extends.
 */
internal fun changesActions(model: ChatViewModel, flushComposer: () -> Unit = {}, focusComposer: () -> Unit) =
    ChangesActions(
        onClose = model::closeChanges,
        onSelectBase = model::selectChangesBase,
        onReload = model::reloadChanges,
        onOpenFile = model::openChangesFile,
        onOpenSubagentFile = model::openChangesSubagentFile,
        onRequestPreview = model::requestChangesPreview,
        onSetComment = model::setReviewComment,
        onRemoveComment = model::removeReviewComment,
        onUseReview = { prompt ->
            flushComposer()
            model.useReview(prompt).also { if (it) focusComposer() }
        },
        onPrefill = { prompt ->
            flushComposer()
            model.prefillPrompt(prompt).also {
                if (it) {
                    model.closeChanges()
                    focusComposer()
                }
            }
        },
    )

/**
 * Wires [FilesPane] to [model]; a prefilled quote moves focus to the composer via [focusComposer].
 * [flushComposer] first hands the composer's typing to the draft the prefill extends. A question
 * sent at once keeps the file open where it shows [besideChat], so the answer comes in beside it.
 */
internal fun filesActions(
    model: ChatViewModel,
    flushComposer: () -> Unit = {},
    besideChat: () -> Boolean = { false },
    focusComposer: () -> Unit,
) =
    FilesActions(
        onClose = model::closeFiles,
        onOpenDir = model::openFilesDir,
        onOpenFile = model::openFilesFile,
        onLoadMore = model::loadMoreFiles,
        onReload = model::reloadFiles,
        onRequestPreview = model::requestFilesPreview,
        onShowPeek = model::showFilesPeek,
        onDismissPeek = model::dismissFilesPeek,
        onSelectLines = model::selectFileLines,
        onSend = { prompt ->
            flushComposer()
            model.prefillPrompt(prompt).also {
                if (it) {
                    model.closeFiles()
                    focusComposer()
                }
            }
        },
        onAsk = { prompt ->
            flushComposer()
            when (model.askNow(prompt)) {
                PromptHandoff.SENT -> {
                    if (!besideChat()) model.closeFiles()
                    true
                }
                PromptHandoff.PREFILLED -> {
                    model.closeFiles()
                    focusComposer()
                    true
                }
                PromptHandoff.FAILED -> false
            }
        },
        onStartEditing = model::startFileEditing,
        onEditorText = model::updateFileEditorText,
        onSaveEditor = model::saveFileEditor,
        onDiscardEditor = model::discardFileEditor,
        onReloadAfterConflict = model::reloadFileAfterConflict,
        onEditorPrompt = model::fileEditorPrompt,
        onConfirmLeave = model::confirmFileEditorLeave,
        onCancelLeave = model::cancelFileEditorLeave,
    )
