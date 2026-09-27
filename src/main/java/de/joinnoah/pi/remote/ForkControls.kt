package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The rewind icon next to a user bubble, its menu and the confirmation. [menuOpen] is hoisted so
 * the bubble's accessibility action can open the same menu. Retry is hidden for slash commands,
 * which the host forks only with edit.
 */
@Composable
internal fun ForkControl(
    messageId: String,
    text: String,
    fork: MessageFork,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    confirming: ForkMode?,
    onConfirming: (ForkMode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = { onMenuOpen(true) },
        modifier = modifier.testTag("forkControl"),
        colors =
            IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
    ) {
        Icon(
            Icons.Filled.Replay,
            contentDescription = stringResource(R.string.remote_fork_action),
            modifier = Modifier.size(18.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
            if (fork.stopFirst)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.remote_fork_stop_first),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("forkStopFirst"),
                )
            if (!text.trimStart().startsWith("/"))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_fork_retry)) },
                    onClick = {
                        onMenuOpen(false)
                        onConfirming(ForkMode.RETRY)
                    },
                    enabled = fork.ready,
                    modifier = Modifier.testTag("forkRetry"),
                )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_fork_edit)) },
                onClick = {
                    onMenuOpen(false)
                    onConfirming(ForkMode.EDIT)
                },
                enabled = fork.ready,
                modifier = Modifier.testTag("forkEdit"),
            )
        }
    }
    confirming?.let { mode ->
        AlertDialog(
            onDismissRequest = { onConfirming(null) },
            title = {
                Text(
                    stringResource(
                        if (mode == ForkMode.RETRY) R.string.remote_fork_retry_title
                        else R.string.remote_fork_edit_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (mode == ForkMode.RETRY) R.string.remote_fork_retry_message
                        else R.string.remote_fork_edit_message
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirming(null)
                        fork.onFork(messageId, mode)
                    },
                    enabled = fork.ready,
                    modifier = Modifier.testTag("forkConfirm"),
                ) {
                    Text(stringResource(R.string.remote_fork_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirming(null) }) {
                    Text(stringResource(R.string.remote_fork_cancel))
                }
            },
        )
    }
}
