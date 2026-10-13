package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * What [Diagnostics] recorded since the app started: request latencies per type, the recent
 * connections with their handshake phases, and offline intervals. "Copy report" copies the same
 * data as plain text, which holds no messages, IDs or keys.
 */
@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val report = remember { Diagnostics.report() }
    val copy = rememberCopyToClipboard(stringResource(R.string.remote_diagnostics_clip_label))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_diagnostics_title)) },
        text = { DiagnosticsReport(report, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(
                onClick = { copy(Diagnostics.export()) },
                modifier = Modifier.testTag("copyDiagnostics"),
            ) { Text(stringResource(R.string.remote_diagnostics_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_diagnostics_close)) }
        },
    )
}

@Composable
private fun DiagnosticsReport(report: Diagnostics.Report, modifier: Modifier = Modifier) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val wallNow = remember(report) { System.currentTimeMillis() }
    fun clock(at: Long) = time.format(Date(wallNow - (report.now - at)))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (report.requests.isEmpty() && report.connects.isEmpty() && report.offline.isEmpty()) {
            Text(stringResource(R.string.remote_diagnostics_empty), style = MaterialTheme.typography.bodyMedium)
        }
        if (report.requests.isNotEmpty()) {
            Heading(stringResource(R.string.remote_diagnostics_requests))
            report.requests.forEach { stats ->
                Column {
                    Text(stats.type, style = MaterialTheme.typography.bodyMedium)
                    Detail(stringResource(R.string.remote_diagnostics_request_stats, stats.count, stats.p50, stats.p95))
                    if (stats.timeouts > 0 || stats.failures > 0)
                        Detail(stringResource(R.string.remote_diagnostics_request_problems, stats.timeouts, stats.failures))
                }
            }
        }
        if (report.connects.isNotEmpty()) {
            Heading(stringResource(R.string.remote_diagnostics_connects))
            report.connects.forEach { connect ->
                Column {
                    val outcome = connect.outcome ?: stringResource(R.string.remote_diagnostics_connect_pending)
                    val duration =
                        connect.durationMs?.let { " · " + stringResource(R.string.remote_diagnostics_milliseconds, it) }.orEmpty()
                    Text("${clock(connect.at)} · ${connect.label} · $outcome$duration", style = MaterialTheme.typography.bodyMedium)
                    val phases = connect.breakdown()
                    if (phases.isNotEmpty())
                        Detail(
                            phases.map { (phase, ms) ->
                                phaseLabel(phase) + " " + stringResource(R.string.remote_diagnostics_milliseconds, ms)
                            }.joinToString(" · ")
                        )
                }
            }
        }
        if (report.offline.isNotEmpty()) {
            Heading(stringResource(R.string.remote_diagnostics_offline))
            report.offline.forEach {
                Text(
                    clock(it.at) + " · " + stringResource(R.string.remote_diagnostics_offline_row, it.durationMs / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        report.frames?.let {
            Detail(stringResource(R.string.remote_diagnostics_frames, it.count, it.p50Micros, it.p95Micros))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun phaseLabel(phase: HandshakePhase): String =
    stringResource(
        when (phase) {
            HandshakePhase.OPENING -> R.string.remote_diagnostics_phase_opening
            HandshakePhase.CHALLENGE -> R.string.remote_diagnostics_phase_challenge
            HandshakePhase.READY -> R.string.remote_diagnostics_phase_ready
            HandshakePhase.AUTHENTICATING -> R.string.remote_diagnostics_phase_authenticating
            HandshakePhase.APPROVAL -> R.string.remote_diagnostics_phase_approval
            HandshakePhase.PERSISTING -> R.string.remote_diagnostics_phase_persisting
            // The end states take no time, so a breakdown never lists them.
            HandshakePhase.PAIRED, HandshakePhase.ACTIVE -> R.string.remote_diagnostics_phase_persisting
        }
    )
