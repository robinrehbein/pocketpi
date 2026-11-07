package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable

internal fun continuationInsightShown(continuations: SessionContinuations): Boolean =
    continuations.items.isNotEmpty()

private fun RemoteState.scheduledActivityView(): ScheduledActivityPresentation =
    scheduledActivityPresentation(
        continuations = continuations,
        connected = connected,
        stale = stale,
        stoppingIds = continuationStopping,
        stopFailedIds = continuationStopFailed,
    )

/** Kept outside the chat's large content lambda for the minified build. */
@Composable
internal fun SessionContinuationPill(state: RemoteState, onClick: () -> Unit) {
    ScheduledActivityPill(state.scheduledActivityView(), onClick)
}

@Composable
internal fun SessionContinuationSheet(state: RemoteState, onStop: (String) -> Unit, onDismiss: () -> Unit) {
    ScheduledActivitySheet(
        presentation = state.scheduledActivityView(),
        sessionTitle = state.session?.optionalText("title")?.let { displayTitle(it).text }?.takeIf(String::isNotBlank),
        onStop = onStop,
        onDismiss = onDismiss,
    )
}
