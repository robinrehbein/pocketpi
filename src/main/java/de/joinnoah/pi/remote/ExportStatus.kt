package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** The FileProvider authority declared in the manifest as `${applicationId}.exports`. */
internal fun exportAuthority(context: Context): String = context.packageName + ".exports"

/** Writes [result] to the export cache folder and returns the shareable content URI. Blocking. */
internal fun storeExport(context: Context, result: ExportResult.Ready): Uri {
    val file: File = ExportStorage.write(context.cacheDir, result.fileName, result.bytes)
    return FileProvider.getUriForFile(context, exportAuthority(context), file)
}

/** The chooser that shares the exported HTML file with another app. */
internal fun exportShareIntent(uri: Uri, title: CharSequence): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/html")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip is what grants the chooser's targets read access on all API levels.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@StringRes
internal fun exportFailureText(failure: ExportFailure): Int =
    when (failure) {
        ExportFailure.UNSUPPORTED -> R.string.remote_export_unsupported
        ExportFailure.BUSY -> R.string.remote_export_busy
        ExportFailure.NOT_FOUND -> R.string.remote_export_not_found
        ExportFailure.OFFLINE -> R.string.remote_export_offline
        ExportFailure.TIMED_OUT -> R.string.remote_export_timed_out
        ExportFailure.TOO_LARGE -> R.string.remote_export_too_large
        ExportFailure.HASH_MISMATCH -> R.string.remote_export_hash_mismatch
        ExportFailure.PROTOCOL -> R.string.remote_export_protocol
        ExportFailure.STORAGE -> R.string.remote_export_storage
        ExportFailure.FAILED -> R.string.remote_export_failed
    }

/** Progress, failure or completion of `/export`; [onShare] shares the finished file again. */
@Composable
internal fun ExportStatus(state: ExportState?, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    if (state == null) return
    val failed = state is ExportState.Failed
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("exportStatus").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor =
            if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                ExportState.Exporting -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        stringResource(R.string.remote_export_running),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                is ExportState.Failed -> {
                    Column(Modifier) {
                        Text(
                            stringResource(exportFailureText(state.failure)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_export_dismiss)) }
                }
                is ExportState.Done -> {
                    Text(
                        stringResource(R.string.remote_export_done),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = { onShare(state.uri) }) { Text(stringResource(R.string.remote_export_share)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_export_dismiss)) }
                }
            }
        }
    }
}
