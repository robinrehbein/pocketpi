package de.joinnoah.pi.remote

import kotlin.math.pow

/** The actions of the chat header pill, in the order the pill shows them. */
enum class ChatAction {
    CHANGES,
    FILES,
    RENAME,
    REFRESH,
    SETTINGS,
}

/** How often an action was used, decayed to [at]. */
data class ActionUsage(val score: Double, val at: Long)

/** Uses lose half their weight every 14 days, so the pill follows recent habits. */
internal const val ACTION_USAGE_HALF_LIFE_MILLIS = 14L * 24 * 60 * 60 * 1000

/**
 * The ranking without any use, and the tie-break: Changes, then Files, then Refresh. Usage is
 * stored per action name, so an action added later simply starts without uses.
 */
internal val DEFAULT_CHAT_ACTIONS =
    listOf(ChatAction.CHANGES, ChatAction.FILES, ChatAction.REFRESH, ChatAction.RENAME, ChatAction.SETTINGS)

internal fun ActionUsage.decayed(now: Long): Double =
    score * 0.5.pow((now - at).coerceAtLeast(0L) / ACTION_USAGE_HALF_LIFE_MILLIS.toDouble())

/** [this] plus one use at [now]. */
internal fun ActionUsage?.used(now: Long): ActionUsage =
    ActionUsage((this?.decayed(now) ?: 0.0) + 1.0, now)

/** Every action, most used first; equal scores keep [DEFAULT_CHAT_ACTIONS] order. */
internal fun rankChatActions(usage: Map<ChatAction, ActionUsage>, now: Long): List<ChatAction> =
    DEFAULT_CHAT_ACTIONS.sortedByDescending { usage[it]?.decayed(now) ?: 0.0 }

/** The pill's buttons and the chevron menu's entries. */
internal data class ChatActionLayout(val shown: List<ChatAction>, val menu: List<ChatAction>)

/**
 * The two highest-ranked [available] actions go into the pill and the rest into the menu, both in
 * [ChatAction] order so entries never swap places. When only one would be left for the menu, it
 * gets the chevron's place instead.
 */
internal fun chatActionLayout(ranked: List<ChatAction>, available: Set<ChatAction>): ChatActionLayout {
    val usable = ranked.filter { it in available }
    if (usable.size <= 3) return ChatActionLayout(usable.sortedBy { it.ordinal }, emptyList())
    return ChatActionLayout(usable.take(2).sortedBy { it.ordinal }, usable.drop(2).sortedBy { it.ordinal })
}
