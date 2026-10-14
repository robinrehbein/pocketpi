package de.joinnoah.pi.remote

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SwipeAction {
    CLOSE,
    RENAME,
    NONE,
}

/**
 * The colour palette, stored under the `theme` key. [BRAND] is the default and keeps the stored value
 * "system" from before the mode existed; [DYNAMIC] takes the wallpaper colours on Android 12 and later.
 */
enum class ThemePalette(val stored: String) {
    BRAND("system"),
    SYNTHWAVE("synthwave"),
    DYNAMIC("dynamic"),
    ;

    /** Whether a device on [sdk] can draw this palette; wallpaper colours need Android 12. */
    fun isAvailable(sdk: Int): Boolean = this != DYNAMIC || sdk >= Build.VERSION_CODES.S

    /**
     * The palette a device on [sdk] actually draws: a stored [DYNAMIC] it cannot draw (for example
     * after an OS downgrade) shows, and is selected in the picker, as [BRAND].
     */
    fun effective(sdk: Int): ThemePalette = if (isAvailable(sdk)) this else BRAND

    companion object {
        /** Unknown or missing values read as the default, so a stored value from another version is harmless. */
        fun fromStored(value: String?): ThemePalette = entries.firstOrNull { it.stored == value } ?: BRAND

        /** The palettes the picker offers on [sdk]. */
        fun available(sdk: Int): List<ThemePalette> = entries.filter { it.isAvailable(sdk) }
    }
}

/** Light, dark or the device's setting, stored under the `theme_mode` key; independent of the palette. */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Whether the app draws dark: a forced mode wins, [SYSTEM] follows [systemDark]. */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}

/** How a running chat shows the host's thinking; stored under `thinking_display`. */
enum class ThinkingDisplay(val stored: String) {
    STATUS("status"),
    TEXT("text"),
    ;

    companion object {
        fun fromStored(value: String?): ThinkingDisplay = entries.firstOrNull { it.stored == value } ?: STATUS
    }
}

data class RemoteSettings(
    val theme: ThemePalette = ThemePalette.BRAND,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pushEnabled: Boolean = false,
    val thinkingDisplay: ThinkingDisplay = ThinkingDisplay.STATUS,
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

    fun setTheme(theme: ThemePalette)

    fun setThemeMode(mode: ThemeMode)

    fun setPushEnabled(enabled: Boolean)

    /** Remembers that the permission was offered, whatever the answer was. */
    fun markNotificationPromptShown() {}

    fun setThinkingDisplay(display: ThinkingDisplay)

    fun setHideOfflineSessions(hide: Boolean)

    fun setSwipeEndToStart(action: SwipeAction)

    fun setSwipeStartToEnd(action: SwipeAction)

    fun setEnterSends(enabled: Boolean)

    /** The chat header actions, most used first; read once when a chat opens. */
    fun rankedChatActions(now: Long = System.currentTimeMillis()): List<ChatAction> = DEFAULT_CHAT_ACTIONS

    /** Counts one use of [action] on this device. */
    fun recordChatAction(action: ChatAction, now: Long = System.currentTimeMillis()) {}

    /** The stored uses of each chat action, undecayed; the palette decays them to its own time. */
    fun chatActionUsage(): Map<ChatAction, ActionUsage> = emptyMap()
}

class DefaultSettingsRepository(
    context: Context,
    /** Tells the system which night mode the app wants (API 31+); a test replaces it. */
    private val applyNightMode: (ThemeMode) -> Unit = { mode -> applyAppNightMode(context, mode) },
) : SettingsRepository {
    private val preferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).also(::migrateLegacyThemeMode)
    private val mutable =
        MutableStateFlow(
            RemoteSettings(
                theme = ThemePalette.fromStored(preferences.getString("theme", null)),
                themeMode = ThemeMode.fromStored(preferences.getString("theme_mode", null)),
                pushEnabled = preferences.getBoolean("push_enabled", false),
                thinkingDisplay = ThinkingDisplay.fromStored(preferences.getString("thinking_display", null)),
                hideOfflineSessions = preferences.getBoolean("hide_offline_sessions", false),
                swipeEndToStart = swipeAction("swipe_end_to_start", SwipeAction.CLOSE),
                swipeStartToEnd = swipeAction("swipe_start_to_end", SwipeAction.RENAME),
                enterSends = preferences.getBoolean("enter_sends", true),
                pushOptedOut = preferences.getBoolean("push_opted_out", false),
                notificationPromptShown = preferences.getBoolean("notification_prompt_shown", false),
            )
        )
    override val state = mutable.asStateFlow()

    init {
        // Re-sync on every start: the system may have dropped the app's night mode (a per-app reset)
        // while the preference kept it, and the first window frame reads the system's copy.
        applyNightMode(mutable.value.themeMode)
    }

    override fun setTheme(theme: ThemePalette) {
        preferences.edit().putString("theme", theme.stored).apply()
        mutable.value = mutable.value.copy(theme = theme)
    }

    override fun setThemeMode(mode: ThemeMode) {
        val changed = mutable.value.themeMode != mode
        preferences.edit().putString("theme_mode", mode.stored).apply()
        mutable.value = mutable.value.copy(themeMode = mode)
        // The system keeps the app's night mode, so a cold start shows the same window colour.
        if (changed) applyNightMode(mode)
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

    override fun setThinkingDisplay(display: ThinkingDisplay) {
        preferences.edit().putString("thinking_display", display.stored).apply()
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

    override fun rankedChatActions(now: Long): List<ChatAction> = rankChatActions(chatActionUsage(), now)

    override fun chatActionUsage(): Map<ChatAction, ActionUsage> =
        ChatAction.entries.mapNotNull { action -> actionUsage(action)?.let { action to it } }.toMap()

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

/**
 * Moves a forced light or dark choice that older builds stored under `theme` into `theme_mode`, and
 * resets `theme` to the brand palette, so the user keeps their mode. Runs only while `theme_mode` is
 * unset; the write-back makes it a one-time step.
 */
internal fun migrateLegacyThemeMode(preferences: SharedPreferences) {
    if (preferences.contains("theme_mode")) return
    val mode =
        when (preferences.getString("theme", null)) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> return
        }
    preferences.edit {
        putString("theme_mode", mode.stored)
        putString("theme", ThemePalette.BRAND.stored)
    }
}

/**
 * Sets the app's night mode in the system, so the values-night window background and the system
 * bars follow the in-app choice from the next cold start on. Calling it again with the same mode
 * changes nothing. Only API 31 and later have a per-app night mode; below that a forced mode can show
 * the system mode's window colour for the first frame, until Compose draws the app's own background.
 */
internal fun applyAppNightMode(context: Context, mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val nightMode =
        when (mode) {
            // The platform stores AUTO as "no app override", so the app follows the device setting.
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
    context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode)
}
