package de.joinnoah.pi.remote

/** Keep scheduler truth separate from display labels and the session's active-turn status. */
internal fun scheduledActivityPresentation(
    continuations: SessionContinuations,
    connected: Boolean,
    stale: Boolean,
    stoppingIds: Set<String> = emptySet(),
    stopFailedIds: Set<String> = emptySet(),
): ScheduledActivityPresentation = ScheduledActivityPresentation(
    items = continuations.items.map { item ->
        ScheduledActivityItem(
            id = item.id,
            kind = when (item.kind) {
                ContinuationKind.WAKEUP -> ScheduledActivityKind.WAKEUP
                ContinuationKind.LOOP -> ScheduledActivityKind.LOOP
                ContinuationKind.GOAL -> ScheduledActivityKind.GOAL
            },
            phase = when (item.phase) {
                ContinuationPhase.AFTER_TURN -> ScheduledActivityPhase.AFTER_TURN
                ContinuationPhase.SCHEDULED -> ScheduledActivityPhase.SCHEDULED
                ContinuationPhase.CONTINUING -> ScheduledActivityPhase.CONTINUING
                ContinuationPhase.HOLDING -> ScheduledActivityPhase.HOLDING
                ContinuationPhase.PAUSED -> ScheduledActivityPhase.PAUSED
            },
            purpose = item.purpose,
            dueAt = item.dueAt,
            delayMs = item.delayMs,
        )
    },
    stale = stale || !connected || continuations.freshness != ContinuationFreshness.LIVE,
    stoppingIds = stoppingIds,
    stopFailedIds = stopFailedIds,
)
