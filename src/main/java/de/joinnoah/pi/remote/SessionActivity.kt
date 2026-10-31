package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One line of what the selected session is doing right now, shown above the composer. */
internal data class SessionActivityLine(@StringRes val labelRes: Int, val detail: String? = null)

/** A raw tool name is a safe detail only when it is short and free of anything command-like. */
private fun toolNameDetail(name: String?): String? =
    name?.takeIf { it.length <= 64 && isOpaqueId(it) }

/**
 * The compact activity line for the open chat, or null when nothing should show. The session must
 * genuinely run on the connection and be the chat's own destination: a live task list wins with
 * its first in-progress task, otherwise the newest streaming tool does, else a generic working
 * text. Idle, waiting, loading, stale, offline and previewed sessions show nothing, and questions
 * hold the line back so the pending answer is the only call to act.
 */
internal fun sessionActivityLine(
    state: RemoteState,
    items: List<ConversationItem>,
    destination: RemoteSelection?,
): SessionActivityLine? {
    if (destination == null || state.selection != destination) return null
    if (!state.connected || state.loading || state.stale || state.preview) return null
    if (state.status != "running" || state.selection.sessionId == null) return null
    if (state.questions.isNotEmpty()) return null
    val task = state.tasks.items.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
    if (state.tasks.freshness == TaskFreshness.LIVE && task != null) {
        val text = task.activeForm?.takeIf(String::isNotBlank) ?: task.text
        return SessionActivityLine(R.string.remote_chat_activity_working, text.takeIf(String::isNotBlank))
    }
    val tool = items.lastOrNull { it is ConversationItem.Activity && it.state == "streaming" }
        as? ConversationItem.Activity ?: return genericLine()
    val summary = tool.summary
    if (summary.labelRes == null) return genericLine(toolNameDetail(tool.name))
    // A shell command belongs to the tool card, not the compact line; path tools keep their file.
    val detail = summary.detail.takeUnless { tool.name in setOf("bash", "bash_bg", USER_BASH_TOOL) }
    return SessionActivityLine(summary.labelRes, detail)
}

private fun genericLine(detail: String? = null) =
    SessionActivityLine(R.string.remote_chat_activity_working, detail)

/** The one-line spinner and activity label immediately above the chat composer. */
@Composable
internal fun ChatCurrentActivity(line: SessionActivityLine?) {
    if (line == null) return
    val label = stringResource(line.labelRes)
    val text = line.detail?.let { "$label · $it" } ?: label
    Row(
        Modifier.fillMaxWidth().testTag("chatCurrentActivity").padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
