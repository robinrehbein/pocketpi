package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The minutes the app may spend in the background before the lock asks again. */
internal val APP_LOCK_TIMEOUTS = listOf(1, 5, 15, 30)
internal const val APP_LOCK_DEFAULT_TIMEOUT_MINUTES = 5

/** The app lock setting: off unless the user switches it on. */
internal data class AppLockSettings(
    val enabled: Boolean = false,
    val timeoutMinutes: Int = APP_LOCK_DEFAULT_TIMEOUT_MINUTES,
)

/** Keeps [AppLockSettings] in a preferences file of its own, apart from the other settings. */
internal class AppLockStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("app_lock", Context.MODE_PRIVATE))

    private val mutable =
        MutableStateFlow(
            AppLockSettings(
                preferences.getBoolean(KEY_ENABLED, false),
                preferences.getInt(KEY_TIMEOUT, APP_LOCK_DEFAULT_TIMEOUT_MINUTES).takeIf { it in APP_LOCK_TIMEOUTS }
                    ?: APP_LOCK_DEFAULT_TIMEOUT_MINUTES,
            )
        )
    val state: StateFlow<AppLockSettings> = mutable.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        mutable.value = mutable.value.copy(enabled = enabled)
    }

    fun setTimeoutMinutes(minutes: Int) {
        require(minutes in APP_LOCK_TIMEOUTS)
        preferences.edit { putInt(KEY_TIMEOUT, minutes) }
        mutable.value = mutable.value.copy(timeoutMinutes = minutes)
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TIMEOUT = "timeout_minutes"
    }
}
