package de.joinnoah.pi.remote

import kotlin.math.pow

/** The actions of the chat header pill, in the order the pill shows them. */
enum class ChatAction {
    CHANGES,
    FILES,
    /** The session's background jobs; offered while it has any. */
    JOBS,
    RENAME,
    REFRESH,
    SETTINGS,
    NEW_SESSION,
    /** The session's pi settings sheet; [SETTINGS] is the app's own settings. */
    SESSION_SETTINGS,
    /** The chat history; a header wide enough shows it as its own button instead, see [withHistory]. */
    HISTORY,
}

/** How often an action was used, decayed to [at]. */
data class ActionUsage(val score: Double, val at: Long)

/** Uses lose half their weight every 14 days, so the pill follows recent habits. */
internal const val ACTION_USAGE_HALF_LIFE_MILLIS = 14L * 24 * 60 * 60 * 1000

/**
 * The ranking without any use, and the tie-break: Changes and Refresh come first. Usage is stored
 * per action name, so an action added later, like Files, New session or Jobs, simply starts without uses.
 */
internal val DEFAULT_CHAT_ACTIONS =
    listOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.FILES, ChatAction.RENAME, ChatAction.NEW_SESSION,
        ChatAction.SESSION_SETTINGS, ChatAction.SETTINGS, ChatAction.JOBS, ChatAction.HISTORY)

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

/**
 * [this] plus History without displacing any action: it joins the menu when there is one and
 * otherwise takes a button of its own, so the other actions sit exactly where they would without it.
 */
internal fun ChatActionLayout.withHistory(): ChatActionLayout =
    if (menu.isEmpty()) copy(shown = (shown + ChatAction.HISTORY).sortedBy { it.ordinal })
    else copy(menu = (menu + ChatAction.HISTORY).sortedBy { it.ordinal })
