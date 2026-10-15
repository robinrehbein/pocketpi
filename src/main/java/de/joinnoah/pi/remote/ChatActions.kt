package de.joinnoah.pi.remote

import kotlin.math.pow

/**
 * The actions of the chat header pill, in the order the pill and its menu show them. Usage is
 * stored per name, so entries can be inserted anywhere without touching what was recorded.
 */
enum class ChatAction {
    CHANGES,
    FILES,
    /** The session's background jobs; offered while it has any. */
    JOBS,
    /** The session tree sheet, as `/tree` opens it. */
    TREE,
    /** The session's artifacts, as `/artifacts` opens them. */
    ARTIFACTS,
    RENAME,
    /** Exports the session as an HTML file, as `/export` does. */
    EXPORT,
    REFRESH,
    /** Reloads pi's extensions, skills, prompts and context files, as `/reload` does. */
    RELOAD,
    SETTINGS,
    NEW_SESSION,
    /** The session's pi settings sheet; [SETTINGS] is the app's own settings. */
    SESSION_SETTINGS,
    /** The chat history; a header wide enough shows it as its own button instead, see [withHistory]. */
    HISTORY,
    /** The compaction and export banners, in a sheet; offered in the compact header's menu only, see [compact]. */
    STATUS,
    /** Closes the session on the Mac after a confirmation; only ever in the menu, see [withClose]. */
    CLOSE,
}

/** How often an action was used, decayed to [at]. */
data class ActionUsage(val score: Double, val at: Long)

/** Uses lose half their weight every 14 days, so the command palette follows recent habits. */
internal const val ACTION_USAGE_HALF_LIFE_MILLIS = 14L * 24 * 60 * 60 * 1000

/**
 * Which available actions take the pill's buttons, first come first: Changes and Refresh. The order
 * is fixed, so an action never jumps between the pill and the menu because of how often it was used;
 * only the command palette ranks by [ActionUsage]. [ChatAction.CLOSE] is never listed: a destructive
 * action must not move into the pill.
 */
internal val DEFAULT_CHAT_ACTIONS =
    listOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.FILES, ChatAction.RENAME, ChatAction.NEW_SESSION,
        ChatAction.SESSION_SETTINGS, ChatAction.SETTINGS, ChatAction.JOBS, ChatAction.TREE, ChatAction.ARTIFACTS,
        ChatAction.EXPORT, ChatAction.RELOAD, ChatAction.HISTORY)

internal fun ActionUsage.decayed(now: Long): Double =
    score * 0.5.pow((now - at).coerceAtLeast(0L) / ACTION_USAGE_HALF_LIFE_MILLIS.toDouble())

/** [this] plus one use at [now]. */
internal fun ActionUsage?.used(now: Long): ActionUsage =
    ActionUsage((this?.decayed(now) ?: 0.0) + 1.0, now)

/** The pill's buttons and the chevron menu's entries. */
internal data class ChatActionLayout(val shown: List<ChatAction>, val menu: List<ChatAction>)

/**
 * The first two [available] actions of [priority] go into the pill and the rest into the menu,
 * both in [ChatAction] order so entries never swap places. When only one would be left for the
 * menu, it gets the chevron's place instead.
 */
internal fun chatActionLayout(priority: List<ChatAction>, available: Set<ChatAction>): ChatActionLayout {
    val usable = priority.filter { it in available }
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

/**
 * [this] with the banners' entry in the menu while [status] holds: a compact chat moves the
 * compaction and export banners out of the conversation into the status sheet, opened from here.
 */
internal fun ChatActionLayout.withStatus(status: Boolean): ChatActionLayout =
    if (!status) this else copy(menu = (menu + ChatAction.STATUS).sortedBy { it.ordinal })

/**
 * [this] with Close at the end of the menu: the same session actions as a session row's menu,
 * without a swipe. When Close opens the menu, the chevron takes the third button's place, so the
 * pill stays as wide as [chatActionLayout] makes it; History's own button stays.
 */
internal fun ChatActionLayout.withClose(): ChatActionLayout {
    val moved = if (menu.isEmpty()) shown.filter { it != ChatAction.HISTORY }.drop(2) else emptyList()
    return ChatActionLayout(shown - moved.toSet(), (menu + moved + ChatAction.CLOSE).sortedBy { it.ordinal })
}

/** The slash command [this] runs; the menu offers these while the session supports them, see [chatMenuLocalCommands]. */
internal val ChatAction.localCommand: LocalCommand?
    get() =
        when (this) {
            ChatAction.TREE -> LocalCommand.TREE
            ChatAction.ARTIFACTS -> LocalCommand.ARTIFACTS
            ChatAction.EXPORT -> LocalCommand.EXPORT
            ChatAction.RELOAD -> LocalCommand.RELOAD
            else -> null
        }

/** The actions whose slash command is in [available]. */
internal fun localCommandActions(available: List<LocalCommand>): Set<ChatAction> =
    ChatAction.entries.filterTo(mutableSetOf()) { action -> action.localCommand?.let { it in available } == true }
