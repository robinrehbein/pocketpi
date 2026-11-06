package de.joinnoah.pi.remote

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel

/** The fill level, in percent, from which an idle chat offers to make room. */
internal const val CONTEXT_OFFER_PERCENT = 75

/** After "Not now" at the first level, the offer returns once more from this fill level. */
internal const val CONTEXT_OFFER_REOFFER_PERCENT = 90

/** On very large windows the offer starts from this many used tokens instead... */
internal const val CONTEXT_OFFER_LARGE_TOKENS = 200_000L

/**
 * ...for windows of at least this size, the one where [CONTEXT_OFFER_LARGE_TOKENS] is
 * [CONTEXT_OFFER_PERCENT] full (200k / 0.75): from here up the token rule offers earlier than 75%...
 */
internal const val CONTEXT_OFFER_LARGE_WINDOW = 266_667L

/** ...but never below this fill level, so it acts on windows up to 400k only. */
internal const val CONTEXT_OFFER_FLOOR_PERCENT = 50

/** How long the offer stays a live region after it first shows, so TalkBack reads it once per level. */
internal const val CONTEXT_OFFER_ANNOUNCE_MILLIS = 1_500L

/** What the context offer lets the user do: compact the chat, or continue in a new chat with a summary. */
internal enum class ContextOfferAction { COMPACT, HANDOVER }

/**
 * The offer to make room in a filling context: [level] is the threshold it stands for
 * ([CONTEXT_OFFER_PERCENT] or [CONTEXT_OFFER_REOFFER_PERCENT]), [percent] the current fill.
 */
internal data class ContextOffer(val level: Int, val percent: Int, val actions: Set<ContextOfferAction>)

/**
 * The offer level [usage] has reached, or null below it: [CONTEXT_OFFER_REOFFER_PERCENT] from 90%,
 * [CONTEXT_OFFER_PERCENT] from 75%, or from [CONTEXT_OFFER_LARGE_TOKENS] used tokens on windows of at
 * least [CONTEXT_OFFER_LARGE_WINDOW] (never below [CONTEXT_OFFER_FLOOR_PERCENT]), which makes a
 * 267k to 400k window offer before it is 75% full. Unknown usage offers nothing.
 */
internal fun contextOfferLevel(usage: SessionContextUsage?): Int? =
    usage?.percent?.let { offerLevel(it, usage) }

private fun offerLevel(percent: Double, usage: SessionContextUsage): Int? =
    when {
        percent >= CONTEXT_OFFER_REOFFER_PERCENT -> CONTEXT_OFFER_REOFFER_PERCENT
        percent >= CONTEXT_OFFER_PERCENT -> CONTEXT_OFFER_PERCENT
        // The parser reports a percentage only together with the used tokens.
        percent >= CONTEXT_OFFER_FLOOR_PERCENT && usage.contextWindow >= CONTEXT_OFFER_LARGE_WINDOW &&
            checkNotNull(usage.usedTokens) >= CONTEXT_OFFER_LARGE_TOKENS -> CONTEXT_OFFER_PERCENT
        else -> null
    }

/**
 * The offer the open chat shows now, or null. It needs an idle, loaded, connected chat that can
 * compact now ([canCompact]: no compaction requested or running), a usage read of this chat
 * ([selectedContextUsage]; a finished compaction clears it until the next read) that finished
 * after the chat's last run ended ([RemoteState.contextUsageStale]), no handover running
 * ([canCompact] covers that too), an offer level that
 * [dismissedLevel] does not already cover, and at least one available action.
 */
internal fun contextOffer(state: RemoteState, dismissedLevel: Int?, nowMillis: Long): ContextOffer? {
    if (state.selection.sessionId == null || state.contextLoading || state.contextUsageStale) return null
    if (!canCompact(state, nowMillis)) return null
    val usage = selectedContextUsage(state) ?: return null
    val percent = usage.percent ?: return null
    val level = offerLevel(percent, usage) ?: return null
    if (dismissedLevel != null && dismissedLevel >= level) return null
    val actions = buildSet {
        if (compactAvailable(state)) add(ContextOfferAction.COMPACT)
        if (handoverAvailable(state)) add(ContextOfferAction.HANDOVER)
    }
    if (actions.isEmpty()) return null
    return ContextOffer(level, percent.toInt(), actions)
}

/**
 * Whether the open chat's dismissal can be forgotten: a compaction is running or requested (once
 * done, the next fill-up offers again), or a finished read shows usage below the offer.
 */
internal fun contextOfferResets(state: RemoteState, nowMillis: Long): Boolean {
    if (state.compactionRequesting || compactionVisible(state.compaction, nowMillis)) return true
    if (state.contextLoading || state.contextUsageStale) return false
    val usage = selectedContextUsage(state) ?: return false
    return usage.percent != null && contextOfferLevel(usage) == null
}

/**
 * The offer's memory, per chat by session ID: the level a "Not now" (or the user's typing)
 * dismissed, and the levels TalkBack already announced. It is in memory only (not persisted) and
 * scoped to the activity, above the nav entries, so leaving a chat and opening it again keeps a
 * dismissal. Both are snapshot state, so the screen reads them reactively.
 */
internal class ContextOfferMemory : ViewModel() {
    private val dismissedLevels = mutableStateMapOf<String, Int>()
    private val announcedLevels = mutableStateSetOf<Pair<String, Int>>()

    /** The highest level dismissed in [sessionId], or null. */
    fun dismissed(sessionId: String): Int? = dismissedLevels[sessionId]

    fun dismiss(sessionId: String, level: Int) {
        dismissedLevels[sessionId] = maxOf(level, dismissedLevels[sessionId] ?: level)
    }

    fun forget(sessionId: String) {
        dismissedLevels.remove(sessionId)
        announcedLevels.removeAll { it.first == sessionId }
    }

    /** Handovers (by request ID) whose `done` already moved the phone to the new chat. */
    private val followedHandovers = mutableSetOf<String>()

    /** Handover outcomes (by request ID) the user closed in the old chat's banner slot. */
    private val closedHandovers = mutableStateSetOf<String>()

    /** New chats a handover just opened, by session ID, with the old chat's session ID. */
    private val continuedFrom = mutableMapOf<String, String>()

    /**
     * True once per [handover]: the first time its live `done` is seen, so the phone moves to the new
     * chat once, and [target] learns where it came from (see [takeContinuedFrom]).
     */
    fun follow(handover: SessionHandover, sourceSessionId: String): Boolean {
        val target = handover.targetSessionId ?: return false
        if (!followedHandovers.add(handover.requestId)) return false
        continuedFrom[target] = sourceSessionId
        return true
    }

    /** The old chat's session ID, once, when [sessionId] is the new chat a handover just opened. */
    fun takeContinuedFrom(sessionId: String): String? = continuedFrom.remove(sessionId)

    fun closeHandover(requestId: String) {
        closedHandovers += requestId
    }

    fun handoverClosed(requestId: String): Boolean = requestId in closedHandovers

    /** True until the offer at [level] in [sessionId] was read out once. */
    fun shouldAnnounce(sessionId: String, level: Int): Boolean = (sessionId to level) !in announcedLevels

    fun announced(sessionId: String, level: Int) {
        announcedLevels += sessionId to level
    }
}
