package de.joinnoah.pi.remote

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The connection details of a paired Mac, from its Projects page's overflow: the relay it is
 * reached through and the device it pairs as. The pairing's secret is never shown. With
 * [loadStats] (the host offers `host.stats.v1`) it also shows the computer's load, asked for once
 * a second while the sheet is open and never in the background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MacConnectionDetailsDialog(
    host: PairedHost,
    /** The status the Mac's page shows under its name, so both say the same. */
    status: String,
    onDismiss: () -> Unit,
    /** Reads `host.stats`; null when the host does not offer it, which hides the section. */
    loadStats: (suspend () -> HostStats)? = null,
) {
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.remote_mac_details))
            if (loadStats != null) HostStatsSection(loadStats)
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

/** The latest reading, or whether the last request failed while none had arrived yet. */
private data class HostStatsView(val stats: HostStats? = null, val failed: Boolean = false)

@Composable
private fun HostStatsSection(loadStats: suspend () -> HostStats) {
    var view by remember { mutableStateOf(HostStatsView()) }
    LaunchedEffect(loadStats) {
        // One request at a time: the next one goes out a second after the previous answer.
        while (true) {
            view =
                try {
                    HostStatsView(stats = loadStats())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("host.stats", e)
                    // Keep the last reading on a transient failure; say so only without one.
                    if (view.stats != null) view else HostStatsView(failed = true)
                }
            // Real time, off the composition's clock, so a UI test's virtual clock never spins this loop.
            withContext(Dispatchers.Default) { delay(HOST_STATS_POLL_MILLIS) }
        }
    }
    SheetSection {
        Column(Modifier.testTag("hostStats").padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val stats = view.stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_host_stats_title), style = MaterialTheme.typography.titleSmall)
                if (stats != null) {
                    Text(
                        stringResource(
                            when {
                                stats.container != null -> R.string.remote_host_stats_container
                                stats.platform == HostPlatform.DARWIN -> R.string.remote_host_stats_live_macos
                                stats.platform == HostPlatform.LINUX -> R.string.remote_host_stats_live_linux
                                else -> R.string.remote_host_stats_live
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stats.container != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("hostStatsSource"),
                    )
                }
            }
            when {
                stats != null -> HostStatsBody(stats)
                view.failed ->
                    Text(
                        stringResource(R.string.remote_host_stats_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("hostStatsUnavailable"),
                    )
                else -> {
                    val label = stringResource(R.string.remote_host_stats_measuring)
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("hostStatsLoading"))
                }
            }
        }
    }
}

@Composable
private fun HostStatsBody(stats: HostStats) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val container = stats.container

    val cpuValue = stats.cpuPercent?.let { stringResource(R.string.remote_host_stats_percent, formatNumber(it, 0, locale)) }
    val cpuDetail =
        listOfNotNull(
            if (container?.cpuLimitCores != null) {
                stringResource(R.string.remote_host_stats_cpu_limit, formatNumber(container.cpuLimitCores, 2, locale))
            } else {
                stringResource(R.string.remote_host_stats_cores, formatNumber(stats.cores, 2, locale))
            },
            stats.load?.let { (one, five, fifteen) ->
                stringResource(
                    R.string.remote_host_stats_load,
                    formatNumber(one, 1, locale),
                    formatNumber(five, 1, locale),
                    formatNumber(fifteen, 1, locale),
                )
            },
        ).joinToString(" · ")
    HostStatsMetric(
        label = stringResource(R.string.remote_host_stats_cpu),
        value = cpuValue ?: stringResource(R.string.remote_host_stats_measuring),
        fraction = (stats.cpuPercent ?: 0.0).toFloat() / 100f,
        level = levelOf((stats.cpuPercent ?: 0.0) / 100),
        detail = cpuDetail,
        tag = "hostStatsCpu",
    )

    HostStatsMetric(
        label = stringResource(R.string.remote_host_stats_memory),
        value = stringResource(R.string.remote_host_stats_used_of, size(stats.memory.usedBytes), size(stats.memory.totalBytes)),
        fraction = stats.memory.fraction,
        level =
            when (stats.pressure) {
                MemoryPressure.CRITICAL -> LoadLevel.CRITICAL
                MemoryPressure.WARN -> LoadLevel.WARN
                // macOS reports the pressure itself; a high fill alone is not a problem there.
                MemoryPressure.NORMAL -> LoadLevel.NORMAL
                null -> levelOf(stats.memory.fraction.toDouble())
            },
        detail =
            when (stats.pressure) {
                MemoryPressure.NORMAL -> stringResource(R.string.remote_host_stats_pressure_normal)
                MemoryPressure.WARN -> stringResource(R.string.remote_host_stats_pressure_warn)
                MemoryPressure.CRITICAL -> stringResource(R.string.remote_host_stats_pressure_critical)
                null -> container?.memoryLimitBytes?.let { stringResource(R.string.remote_host_stats_memory_limit, size(it)) }
            },
        tag = "hostStatsMemory",
    )

    stats.disk?.let { disk ->
        HostStatsMetric(
            label = stringResource(R.string.remote_host_stats_disk),
            value = stringResource(R.string.remote_host_stats_used_of, size(disk.usedBytes), size(disk.totalBytes)),
            fraction = disk.fraction,
            level = levelOf(disk.fraction.toDouble()),
            detail = null,
            tag = "hostStatsDisk",
        )
    }

    stats.battery?.let { battery ->
        val percent = stringResource(R.string.remote_host_stats_percent, formatNumber(battery.percent, 0, locale))
        val power =
            when {
                battery.charging -> stringResource(R.string.remote_host_stats_charging)
                battery.onAc -> stringResource(R.string.remote_host_stats_plugged_in)
                else -> null
            }
        HostStatsMetric(
            label = stringResource(R.string.remote_host_stats_battery),
            value = listOfNotNull(percent, power).joinToString(" · "),
            fraction = (battery.percent / 100).toFloat(),
            // A low battery is the warning here, not a full one.
            level = if (!battery.onAc && battery.percent <= 10) LoadLevel.CRITICAL else if (!battery.onAc && battery.percent <= 20) LoadLevel.WARN else LoadLevel.NORMAL,
            detail = null,
            tag = "hostStatsBattery",
        )
    }

    if (container != null) {
        Text(
            stringResource(R.string.remote_host_stats_container_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (stats.uptimeSeconds > 0) {
        val (days, hours, minutes) = uptimeParts(stats.uptimeSeconds)
        val duration =
            when {
                days > 0 -> stringResource(R.string.remote_host_stats_days_hours, days, hours)
                hours > 0 -> stringResource(R.string.remote_host_stats_hours_minutes, hours, minutes)
                else -> stringResource(R.string.remote_host_stats_minutes, minutes)
            }
        Text(
            stringResource(R.string.remote_host_stats_uptime, duration),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("hostStatsUptime"),
        )
    }
}

internal enum class LoadLevel { NORMAL, WARN, CRITICAL }

/** A fill of 0..1: warn from 80 %, critical from 95 %. */
internal fun levelOf(fraction: Double): LoadLevel =
    when {
        fraction >= 0.95 -> LoadLevel.CRITICAL
        fraction >= 0.8 -> LoadLevel.WARN
        else -> LoadLevel.NORMAL
    }

/** Whole days, hours and minutes of [seconds]. */
internal fun uptimeParts(seconds: Long): Triple<Long, Long, Long> {
    val minutes = seconds / 60
    return Triple(minutes / (24 * 60), minutes / 60 % 24, minutes % 60)
}

internal fun formatNumber(value: Double, maxFractionDigits: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = maxFractionDigits
        minimumFractionDigits = 0
    }.format(value)

@Composable
private fun HostStatsMetric(label: String, value: String, fraction: Float, level: LoadLevel, detail: String?, tag: String) {
    val color: Color =
        when (level) {
            LoadLevel.NORMAL -> MaterialTheme.colorScheme.primary
            LoadLevel.WARN -> waitingColor()
            LoadLevel.CRITICAL -> MaterialTheme.colorScheme.error
        }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        if (!detail.isNullOrEmpty()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
