package de.joinnoah.pi.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The fill level, in percent, from which an idle chat offers to make room. */
internal const val CONTEXT_OFFER_PERCENT = 75

/** After "Not now" at the first level, the offer returns once more from this fill level. */
internal const val CONTEXT_OFFER_REOFFER_PERCENT = 90

/** On very large windows the offer starts from this many used tokens instead... */
internal const val CONTEXT_OFFER_LARGE_TOKENS = 200_000L

/** ...for windows of at least this size... */
internal const val CONTEXT_OFFER_LARGE_WINDOW = 400_000L

/** ...but never below this fill level. */
internal const val CONTEXT_OFFER_FLOOR_PERCENT = 50

/** How long the offer stays a live region after it first shows, so TalkBack reads it once per level. */
internal const val CONTEXT_OFFER_ANNOUNCE_MILLIS = 1_500L

/** What the context offer lets the user do. The handover ("New chat with summary") joins in a later slice. */
internal enum class ContextOfferAction { COMPACT }

/**
 * The offer to make room in a filling context: [level] is the threshold it stands for
 * ([CONTEXT_OFFER_PERCENT] or [CONTEXT_OFFER_REOFFER_PERCENT]), [percent] the current fill.
 */
internal data class ContextOffer(val level: Int, val percent: Int, val actions: Set<ContextOfferAction>)

/**
 * The offer level [usage] has reached, or null below it: [CONTEXT_OFFER_REOFFER_PERCENT] from 90%,
 * [CONTEXT_OFFER_PERCENT] from 75%, or from [CONTEXT_OFFER_LARGE_TOKENS] used tokens on windows of at
 * least [CONTEXT_OFFER_LARGE_WINDOW] (never below [CONTEXT_OFFER_FLOOR_PERCENT]). Unknown usage offers nothing.
 */
internal fun contextOfferLevel(usage: SessionContextUsage?): Int? {
    val percent = usage?.percent ?: return null
    val used = usage.usedTokens ?: return null
    return when {
        percent >= CONTEXT_OFFER_REOFFER_PERCENT -> CONTEXT_OFFER_REOFFER_PERCENT
        percent >= CONTEXT_OFFER_PERCENT -> CONTEXT_OFFER_PERCENT
        usage.contextWindow >= CONTEXT_OFFER_LARGE_WINDOW && used >= CONTEXT_OFFER_LARGE_TOKENS &&
            percent >= CONTEXT_OFFER_FLOOR_PERCENT -> CONTEXT_OFFER_PERCENT
        else -> null
    }
}

/**
 * The offer the open chat shows now, or null. It needs an idle, loaded, connected chat that can
 * compact now ([canCompact]: no compaction requested or running), a finished usage read of this
 * chat ([selectedContextUsage]; a finished compaction clears it until the next read), an offer
 * level that [dismissedLevel] does not already cover, and at least one available action.
 */
internal fun contextOffer(state: RemoteState, dismissedLevel: Int?, nowMillis: Long): ContextOffer? {
    if (state.selection.sessionId == null || state.contextLoading) return null
    if (!canCompact(state, nowMillis)) return null
    val usage = selectedContextUsage(state) ?: return null
    val percent = usage.percent ?: return null
    val level = contextOfferLevel(usage) ?: return null
    if (dismissedLevel != null && dismissedLevel >= level) return null
    val actions = buildSet { if (compactAvailable(state)) add(ContextOfferAction.COMPACT) }
    if (actions.isEmpty()) return null
    return ContextOffer(level, percent.toInt(), actions)
}

/**
 * Whether the open chat's dismissal can be forgotten: a compaction is running or requested (once
 * done, the next fill-up offers again), or a finished read shows usage below the offer.
 */
internal fun contextOfferResets(state: RemoteState, nowMillis: Long): Boolean {
    if (state.compactionRequesting || compactionVisible(state.compaction, nowMillis)) return true
    if (state.contextLoading) return false
    val usage = selectedContextUsage(state) ?: return false
    return usage.percent != null && contextOfferLevel(usage) == null
}

/**
 * Per-chat memory of the offer, kept by the chat's ViewModel only (not persisted): the level a
 * "Not now" (or typing) dismissed, and the levels TalkBack already announced.
 */
internal class ContextOfferMemory {
    private val dismissedLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val dismissed: StateFlow<Map<String, Int>> = dismissedLevels.asStateFlow()
    private val announcedLevels = mutableSetOf<Pair<String, Int>>()

    fun dismiss(sessionId: String, level: Int) =
        dismissedLevels.update { it + (sessionId to maxOf(level, it[sessionId] ?: level)) }

    fun forget(sessionId: String) {
        dismissedLevels.update { if (sessionId in it) it - sessionId else it }
        announcedLevels.removeAll { it.first == sessionId }
    }

    /** True the first time the offer at [level] shows in [sessionId], so it is read out once. */
    fun shouldAnnounce(sessionId: String, level: Int): Boolean = (sessionId to level) !in announcedLevels

    fun announced(sessionId: String, level: Int) {
        announcedLevels += sessionId to level
    }
}
