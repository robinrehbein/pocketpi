package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** Whole seconds, rounded up to one for a sub-second run; omit empty units. */
internal fun completedWorkDurationUnits(millis: Long): Triple<Int, Int, Int> {
    val seconds = (millis / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    return Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

@Composable
internal fun completedWorkDuration(millis: Long): String {
    val (hours, minutes, seconds) = completedWorkDurationUnits(millis)
    val units = buildList {
        if (hours > 0) add(pluralStringResource(R.plurals.remote_chat_duration_hours, hours, hours))
        if (minutes > 0) add(pluralStringResource(R.plurals.remote_chat_duration_minutes, minutes, minutes))
        if (seconds > 0) add(pluralStringResource(R.plurals.remote_chat_duration_seconds, seconds, seconds))
    }
    return when (units.size) {
        1 -> units.single()
        2 -> stringResource(R.string.remote_chat_duration_join, units[0], units[1])
        else -> stringResource(R.string.remote_chat_duration_three, units[0], units[1], units[2])
    }
}

/**
 * Local receipt-to-receipt monotonic timing for starts this connection observed. Not persisted.
 * Connection loss invalidates it: buffered delivery cannot establish the host's actual duration.
 */
data class ChatWorkTiming(val startedAtMillis: Long? = null, val durationMillis: Long? = null)

private fun timingAuthority(state: RemoteState): Boolean =
    state.connected && !state.stale && !state.loading && !state.preview && state.selection.sessionId != null

/** Waiting for an answer belongs to the same run; losing authority invalidates its clock. */
internal fun reconcileChatWorkTiming(previous: RemoteState, next: RemoteState, now: () -> Long): RemoteState {
    val timing = when {
        previous.selection != next.selection || !timingAuthority(previous) || !timingAuthority(next) -> ChatWorkTiming()
        previous.status == "idle" && next.status == "running" -> ChatWorkTiming(startedAtMillis = now())
        next.status == "idle" && previous.status in setOf("running", "waiting") ->
            previous.chatWorkTiming.startedAtMillis?.let { ChatWorkTiming(durationMillis = (now() - it).coerceAtLeast(0)) }
                ?: ChatWorkTiming()
        next.status !in setOf("idle", "running", "waiting") -> ChatWorkTiming()
        next.status == "idle" && next.sending && !previous.sending -> ChatWorkTiming()
        else -> previous.chatWorkTiming
    }
    return next.copy(chatWorkTiming = timing)
}
