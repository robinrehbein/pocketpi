package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private fun ChatAction.icon(): ImageVector =
    when (this) {
        ChatAction.CHANGES -> Icons.Default.Difference
        ChatAction.FILES -> Icons.Default.FolderOpen
        ChatAction.RENAME -> Icons.Default.Edit
        ChatAction.REFRESH -> Icons.Default.Refresh
        ChatAction.SETTINGS -> Icons.Default.Settings
        ChatAction.NEW_SESSION -> Icons.Default.Add
        ChatAction.SESSION_SETTINGS -> Icons.Outlined.Tune
    }

private fun ChatAction.label(): Int =
    when (this) {
        ChatAction.CHANGES -> R.string.remote_changes_open
        ChatAction.FILES -> R.string.remote_files_open
        ChatAction.RENAME -> R.string.remote_rename_session
        ChatAction.REFRESH -> R.string.remote_refresh
        ChatAction.SETTINGS -> R.string.remote_settings
        ChatAction.NEW_SESSION -> R.string.remote_new_session
        ChatAction.SESSION_SETTINGS -> R.string.remote_session_settings_title
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
                        onClick = { open = !open },
                        modifier = Modifier.size(48.dp).testTag("chatActionsMore"),
                    ) {
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            stringResource(
                                if (open) R.string.remote_chat_actions_close
                                else R.string.remote_chat_actions_more
                            ),
                        )
                    }
                    if (open) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(0, with(LocalDensity.current) { 56.dp.roundToPx() }),
                            onDismissRequest = { open = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (action in layout.menu)
                                    FloatingSurface(
                                        modifier = Modifier.size(48.dp),
                                        shape = CircleShape,
                                        color = color,
                                    ) {
                                        IconButton(
                                            onClick = {
                                                open = false
                                                onAction(action)
                                            },
                                            modifier = Modifier.size(48.dp).testTag("chatActionMenu_${action.name.lowercase()}"),
                                        ) {
                                            Icon(action.icon(), stringResource(action.label()))
                                        }
                                    }
                            }
                        }
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
        onRequestPreview = model::requestFilesPreview,
        onShowPeek = model::showFilesPeek,
        onDismissPeek = model::dismissFilesPeek,
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
