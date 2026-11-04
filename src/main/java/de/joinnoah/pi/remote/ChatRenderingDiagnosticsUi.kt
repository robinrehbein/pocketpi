package de.joinnoah.pi.remote

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatRenderingDiagnosticsControls() {
    val options by ChatRenderingDiagnostics.options.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_render_diagnostics_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.remote_render_diagnostics_help), style = MaterialTheme.typography.bodySmall)
        RenderSwitch(R.string.remote_render_record, options.recording, "renderRecord") {
            ChatRenderingDiagnostics.update(options.copy(recording = it))
        }
        RenderSwitch(R.string.remote_render_no_glass, options.disableGlass, "renderNoGlass") {
            ChatRenderingDiagnostics.update(options.copy(disableGlass = it))
        }
        RenderSwitch(R.string.remote_render_no_motion, options.disableMotion, "renderNoMotion") {
            ChatRenderingDiagnostics.update(options.copy(disableMotion = it))
        }
    }
}

@Composable
private fun RenderSwitch(label: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

internal fun chatRenderShareIntent(report: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, report)
}

/** The snapshot was frozen by the chat button BEFORE this sheet or the share chooser opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRenderingSnapshotDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.remote_render_diagnostics_title)
    val copy = rememberCopyToClipboard(title)
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader(title)
            Text(stringResource(R.string.remote_render_snapshot_help))
            SheetPrimaryButton(onClick = { context.startActivity(Intent.createChooser(chatRenderShareIntent(report), title)) },
                modifier = Modifier.testTag("shareRenderSnapshot")) { Text(stringResource(R.string.remote_render_share)) }
            SheetSecondaryButton(onClick = { copy(report) }, modifier = Modifier.testTag("copyRenderSnapshot")) {
                Text(stringResource(R.string.remote_diagnostics_copy))
            }
            SheetSecondaryButton(onClick = onDismiss) { Text(stringResource(R.string.remote_diagnostics_close)) }
        }
    }
}
