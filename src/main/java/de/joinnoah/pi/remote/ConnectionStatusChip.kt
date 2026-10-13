package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** List key of the connection chip, the first item of every list page. */
internal const val CONNECTION_STATUS_KEY = "connection-status"

/**
 * What the connection chip says, or null while there is nothing to say: on a live connection
 * (unless a slow resume probe is checking it), and offline with no Mac chosen ([hostChosen]).
 */
@StringRes
internal fun connectionStatusLine(connected: Boolean, @StringRes connection: Int, hostChosen: Boolean): Int? =
    when {
        connection == R.string.remote_connected -> null
        connected -> connection.takeIf { it == R.string.remote_checking_connection }
        !hostChosen && connection == R.string.remote_offline -> null
        else -> connection
    }

internal fun connectionStatusLine(state: RemoteState): Int? =
    connectionStatusLine(state.connected, state.connection, hostChosen = state.host != null)

/**
 * The connection state as a small pill: "Reconnecting…", "Waiting for network…", "Offline" and
 * the like. A spinner marks an attempt under way; a state that waits on something else (the
 * network, the Mac, a confirmation on the Mac) or has stopped gets a still icon, so a long wait
 * draws no frames. The text wraps rather than cut off a long state at a large font size.
 */
@Composable
internal fun ConnectionStatusChip(@StringRes connection: Int, modifier: Modifier = Modifier) {
    val text = stringResource(connection)
    val description = stringResource(R.string.remote_connection_status, text)
    FloatingSurface(
        modifier.testTag("connectionStatusChip").semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        // Round on one line, and still a pill rather than an oval once the text wraps.
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = connectionStatusIcon(connection)
            if (icon != null)
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The still icon of [connection], or null for an attempt under way, which spins instead. */
internal fun connectionStatusIcon(@StringRes connection: Int): ImageVector? =
    when (connection) {
        R.string.remote_connecting, R.string.remote_reconnecting, R.string.remote_checking_connection -> null
        R.string.remote_waiting_for_network -> Icons.Default.WifiOff
        R.string.remote_offline -> Icons.Default.CloudOff
        else -> Icons.Default.HourglassEmpty
    }

/**
 * The connection chip as a list's first item, sticky, so it stays in view while the list scrolls.
 * It is there on every list page also while [line] is null, so the items after it keep their
 * indices when the connection comes and goes. A sticky item sticks to the very top of the
 * viewport, above the list's content padding: [topInset] keeps the chip below the floating header.
 */
internal fun LazyListScope.connectionStatusItem(@StringRes line: Int?, topInset: Dp) {
    stickyHeader(key = CONNECTION_STATUS_KEY, contentType = CONNECTION_STATUS_KEY) { _ ->
        Box(Modifier.fillMaxWidth().padding(top = topInset)) {
            if (line != null) ConnectionStatusChip(line)
        }
    }
}

/**
 * The chat's notices, right above the composer field, so they stay in view however far the chat
 * is scrolled: the connection state ([connection], see [connectionStatusLine]) and, after a send
 * the connection may have lost ([uncertain]), the request to check the conversation first.
 */
@Composable
internal fun ChatComposerNotices(@StringRes connection: Int?, uncertain: Boolean) {
    if (connection == null && !uncertain) return
    Column(
        Modifier.fillMaxWidth().testTag("chatComposerNotices"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (connection != null) ConnectionStatusChip(connection)
        if (uncertain)
            FloatingSurface(
                Modifier.fillMaxWidth().testTag("uncertainDraftNotice"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.remote_draft_uncertain),
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
    }
}
