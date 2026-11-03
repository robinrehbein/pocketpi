package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The connection details of a paired Mac, from its Projects page's overflow: the relay it is
 * reached through and the device it pairs as. The pairing's secret is never shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MacConnectionDetailsDialog(
    host: PairedHost,
    /** The status the Mac's page shows under its name, so both say the same. */
    status: String,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.remote_mac_details))
            SheetSection {
                Column(Modifier.testTag("macDetailsBody").padding(16.dp)) {
                    MacDetailRow(stringResource(R.string.remote_mac_details_status), status)
                    MacDetailRow(stringResource(R.string.remote_mac_details_relay), host.relay)
                    MacDetailRow(stringResource(R.string.remote_mac_details_device), host.deviceId)
                }
            }
            SheetPrimaryButton(onClick = onDismiss, modifier = Modifier.testTag("macDetailsClose")) {
                Text(stringResource(R.string.remote_error_close))
            }
        }
    }
}

@Composable
private fun MacDetailRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
