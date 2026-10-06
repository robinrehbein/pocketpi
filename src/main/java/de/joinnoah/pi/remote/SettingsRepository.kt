package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SwipeAction {
    CLOSE,
    RENAME,
    NONE,
}

data class RemoteSettings(
    val theme: String = "system",
    val pushEnabled: Boolean = false,
    val thinkingDisplay: String = "status",
    val hideOfflineSessions: Boolean = false,
    val swipeEndToStart: SwipeAction = SwipeAction.CLOSE,
    val swipeStartToEnd: SwipeAction = SwipeAction.RENAME,
    /** With a hardware keyboard, Enter sends and Shift+Enter inserts a newline. */
    val enterSends: Boolean = true,
    /**
     * Push was switched off in the app, as opposed to never switched on. The Mac may still hold
     * this phone's token, so every connection tells it to forget it until push is on again.
     */
    val pushOptedOut: Boolean = false,
    /** The notification permission was offered after pairing; it is never offered again. */
    val notificationPromptShown: Boolean = false,
)

interface SettingsRepository {
    val state: StateFlow<RemoteSettings>

    fun setTheme(theme: String)

    fun setPushEnabled(enabled: Boolean)

    /** Remembers that the permission was offered, whatever the answer was. */
    fun markNotificationPromptShown() {}

    fun setThinkingDisplay(display: String)

    fun setHideOfflineSessions(hide: Boolean)

    fun setSwipeEndToStart(action: SwipeAction)

    fun setSwipeStartToEnd(action: SwipeAction)

    fun setEnterSends(enabled: Boolean)

    /** The chat header actions, most used first; read once when a chat opens. */
    fun rankedChatActions(now: Long = System.currentTimeMillis()): List<ChatAction> = DEFAULT_CHAT_ACTIONS

    /** Counts one use of [action] on this device. */
    fun recordChatAction(action: ChatAction, now: Long = System.currentTimeMillis()) {}
}

class DefaultSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            RemoteSettings(
                preferences.getString("theme", "system") ?: "system",
                preferences.getBoolean("push_enabled", false),
                preferences.getString("thinking_display", "status")?.takeIf {
                    it in setOf("status", "text")
                } ?: "status",
                preferences.getBoolean("hide_offline_sessions", false),
                swipeAction("swipe_end_to_start", SwipeAction.CLOSE),
                swipeAction("swipe_start_to_end", SwipeAction.RENAME),
                preferences.getBoolean("enter_sends", true),
                preferences.getBoolean("push_opted_out", false),
                preferences.getBoolean("notification_prompt_shown", false),
            )
        )
    override val state = mutable.asStateFlow()

    override fun setTheme(theme: String) {
        require(theme in setOf("system", "light", "dark"))
        preferences.edit().putString("theme", theme).apply()
        mutable.value = mutable.value.copy(theme = theme)
    }

    override fun setPushEnabled(enabled: Boolean) {
        preferences
            .edit()
            .putBoolean("push_enabled", enabled)
            .putBoolean("push_opted_out", !enabled)
            .apply()
        mutable.value = mutable.value.copy(pushEnabled = enabled, pushOptedOut = !enabled)
    }

    override fun markNotificationPromptShown() {
        preferences.edit().putBoolean("notification_prompt_shown", true).apply()
        mutable.value = mutable.value.copy(notificationPromptShown = true)
    }

    override fun setThinkingDisplay(display: String) {
        require(display in setOf("status", "text"))
        preferences.edit().putString("thinking_display", display).apply()
        mutable.value = mutable.value.copy(thinkingDisplay = display)
    }

    override fun setHideOfflineSessions(hide: Boolean) {
        preferences.edit().putBoolean("hide_offline_sessions", hide).apply()
        mutable.value = mutable.value.copy(hideOfflineSessions = hide)
    }

    override fun setSwipeEndToStart(action: SwipeAction) {
        preferences.edit().putString("swipe_end_to_start", action.name).apply()
        mutable.value = mutable.value.copy(swipeEndToStart = action)
    }

    override fun setSwipeStartToEnd(action: SwipeAction) {
        preferences.edit().putString("swipe_start_to_end", action.name).apply()
        mutable.value = mutable.value.copy(swipeStartToEnd = action)
    }

    override fun setEnterSends(enabled: Boolean) {
        preferences.edit().putBoolean("enter_sends", enabled).apply()
        mutable.value = mutable.value.copy(enterSends = enabled)
    }

    override fun rankedChatActions(now: Long): List<ChatAction> =
        rankChatActions(
            ChatAction.entries.mapNotNull { action -> actionUsage(action)?.let { action to it } }.toMap(),
            now,
        )

    override fun recordChatAction(action: ChatAction, now: Long) {
        val usage = actionUsage(action).used(now)
        preferences.edit()
            .putFloat(usageScoreKey(action), usage.score.toFloat())
            .putLong(usageTimeKey(action), usage.at)
            .apply()
    }

    private fun usageScoreKey(action: ChatAction) = "chat_action_${action.name.lowercase()}_score"

    private fun usageTimeKey(action: ChatAction) = "chat_action_${action.name.lowercase()}_at"

    private fun actionUsage(action: ChatAction): ActionUsage? {
        if (!preferences.contains(usageScoreKey(action))) return null
        val score = preferences.getFloat(usageScoreKey(action), 0f).toDouble()
        val at = preferences.getLong(usageTimeKey(action), 0L)
        return ActionUsage(score, at).takeIf { score.isFinite() && score >= 0 }
    }

    private fun swipeAction(key: String, default: SwipeAction): SwipeAction =
        parseSwipeAction(preferences.getString(key, null), default)
}

internal fun parseSwipeAction(stored: String?, default: SwipeAction): SwipeAction =
    SwipeAction.entries.firstOrNull { it.name == stored } ?: default
