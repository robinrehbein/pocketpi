package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Whether the chat on screen is a preview of a history session, which its open copies. */
internal fun previewContinuesAsCopy(state: RemoteState): Boolean =
    state.preview && state.session?.optionalText("origin") == "history"

/**
 * Above the composer while a history session opens from its preview: what is shown is the
 * original, and `sessions.open` makes a new copy of it that the chat then shows.
 */
@Composable
internal fun ChatPreviewHint(state: RemoteState, modifier: Modifier = Modifier) {
    if (!previewContinuesAsCopy(state)) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chatPreviewCopyHint"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.remote_chat_preview_copy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
