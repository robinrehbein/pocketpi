package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
        ChatAction.RENAME -> Icons.Default.Edit
        ChatAction.REFRESH -> Icons.Default.Refresh
        ChatAction.SETTINGS -> Icons.Default.Settings
    }

private fun ChatAction.label(): Int =
    when (this) {
        ChatAction.CHANGES -> R.string.remote_changes_open
        ChatAction.FILES -> R.string.remote_files_open
        ChatAction.RENAME -> R.string.remote_rename_session
        ChatAction.REFRESH -> R.string.remote_refresh
        ChatAction.SETTINGS -> R.string.remote_settings
    }

/** The chat header pill: the most used actions as buttons, the rest behind a chevron. */
@Composable
internal fun ChatActionPill(layout: ChatActionLayout, color: Color, onAction: (ChatAction) -> Unit) {
    FloatingSurface(shape = CircleShape, color = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (action in layout.shown)
                IconButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.size(48.dp).testTag("chatAction_${action.name.lowercase()}"),
                ) {
                    Icon(action.icon(), stringResource(action.label()))
                }
            if (layout.menu.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { open = true },
                        modifier = Modifier.size(48.dp).testTag("chatActionsMore"),
                    ) {
                        Icon(Icons.Default.ExpandMore, stringResource(R.string.remote_chat_actions_more))
                    }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        for (action in layout.menu)
                            DropdownMenuItem(
                                text = { Text(stringResource(action.label())) },
                                leadingIcon = { Icon(action.icon(), null) },
                                onClick = {
                                    open = false
                                    onAction(action)
                                },
                                modifier = Modifier.testTag("chatActionMenu_${action.name.lowercase()}"),
                            )
                    }
                }
            }
        }
    }
}

/** Wires [ChangesPane] to [model]; prefilled prompts move focus to the composer via [focusComposer]. */
internal fun changesActions(model: ChatViewModel, focusComposer: () -> Unit) =
    ChangesActions(
        onClose = model::closeChanges,
        onSelectBase = model::selectChangesBase,
        onReload = model::reloadChanges,
        onOpenFile = model::openChangesFile,
        onSetComment = model::setReviewComment,
        onRemoveComment = model::removeReviewComment,
        onUseReview = { prompt -> model.useReview(prompt).also { if (it) focusComposer() } },
        onPrefill = { prompt ->
            model.prefillPrompt(prompt).also {
                if (it) {
                    model.closeChanges()
                    focusComposer()
                }
            }
        },
    )

/** Wires [FilesPane] to [model]; a prefilled quote moves focus to the composer via [focusComposer]. */
internal fun filesActions(model: ChatViewModel, focusComposer: () -> Unit) =
    FilesActions(
        onClose = model::closeFiles,
        onOpenDir = model::openFilesDir,
        onOpenFile = model::openFilesFile,
        onLoadMore = model::loadMoreFiles,
        onReload = model::reloadFiles,
        onSelectLines = model::selectFileLines,
        onSend = { prompt ->
            model.prefillPrompt(prompt).also {
                if (it) {
                    model.closeFiles()
                    focusComposer()
                }
            }
        },
    )
