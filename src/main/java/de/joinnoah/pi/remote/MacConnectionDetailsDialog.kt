package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The connection details of a paired Mac, from its Projects page's overflow: the relay it is
 * reached through and the device it pairs as. The pairing's secret is never shown.
 */
@Composable
internal fun MacConnectionDetailsDialog(
    host: PairedHost,
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_mac_details)) },
        text = {
            Column(Modifier.testTag("macDetailsBody")) {
                MacDetailRow(
                    stringResource(R.string.remote_mac_details_status),
                    stringResource(if (connected) R.string.remote_connected else R.string.remote_design_not_connected),
                )
                MacDetailRow(stringResource(R.string.remote_mac_details_relay), host.relay)
                MacDetailRow(stringResource(R.string.remote_mac_details_device), host.deviceId)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("macDetailsClose")) {
                Text(stringResource(R.string.remote_error_close))
            }
        },
    )
}

@Composable
private fun MacDetailRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
