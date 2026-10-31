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
 * connections with their handshake phases, offline intervals, round-trip times and counters, and
 * what the run before the last restart saved. "Copy report" copies the same
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
    // The previous run may be days old, so its times carry the date.
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val wallNow = remember(report) { System.currentTimeMillis() }
    fun clock(at: Long) = time.format(Date(wallNow - (report.now - at)))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (
            report.requests.isEmpty() && report.connects.isEmpty() && report.offline.isEmpty() &&
                report.link == Diagnostics.Link() && report.previous == null
        ) {
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
                    val extensions = connect.extensions.joinToString("+").let { if (it.isEmpty()) "" else " · $it" }
                    Text("${clock(connect.at)} · ${connect.label} · $outcome$duration$extensions", style = MaterialTheme.typography.bodyMedium)
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
        if (report.link != Diagnostics.Link()) {
            Heading(stringResource(R.string.remote_diagnostics_link))
            LinkDetails(report.link)
        }
        report.frames?.let {
            Detail(stringResource(R.string.remote_diagnostics_frames, it.count, it.p50Micros, it.p95Micros))
        }
        report.previous?.let { previous ->
            val saved = dateTime.format(Date(previous.savedAt))
            Heading(stringResource(R.string.remote_diagnostics_previous, saved))
            LinkDetails(previous.link)
            previous.connects.forEach { connect ->
                val outcome = connect.outcome ?: stringResource(R.string.remote_diagnostics_connect_pending)
                val duration =
                    connect.durationMs?.let { " · " + stringResource(R.string.remote_diagnostics_milliseconds, it) }.orEmpty()
                Text(
                    "${dateTime.format(Date(connect.at))} · ${connect.label} · $outcome$duration",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            previous.entries.forEach {
                Detail("${dateTime.format(Date(it.at))} · ${it.kind.name.lowercase()} · ${it.label} ${it.outcome}".trim())
            }
        }
    }
}

@Composable
private fun LinkDetails(link: Diagnostics.Link) {
    link.rtt?.let {
        Detail(stringResource(R.string.remote_diagnostics_rtt, it.p50, it.p95, it.last, it.count))
        it.relayMs?.let { relay -> Detail(stringResource(R.string.remote_diagnostics_rtt_relay, relay)) }
    }
    Detail(
        stringResource(
            R.string.remote_diagnostics_link_counts,
            link.count(Diagnostics.Counter.RECONNECTS),
            link.count(Diagnostics.Counter.PING_FAILURES),
            link.count(Diagnostics.Counter.PING_TIMEOUTS),
        )
    )
    Detail(
        stringResource(
            R.string.remote_diagnostics_resumes,
            link.count(Diagnostics.Counter.RESUMES_REPLAYED),
            link.count(Diagnostics.Counter.RESUMES_SNAPSHOT),
            link.count(Diagnostics.Counter.ACKS_SENT),
        )
    )
    if (link.closeCodes.isNotEmpty())
        Detail(
            stringResource(
                R.string.remote_diagnostics_close_codes,
                link.closeCodes.entries.joinToString(", ") { "${it.key}×${it.value}" },
                link.flowCloses,
            )
        )
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
