package de.joinnoah.pi.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes

enum class AppIcon(@DrawableRes val previewRes: Int, internal val aliasName: String) {
    WHITE_COLOR(R.drawable.ic_launcher_preview_white_color, "MainActivity"),
    WHITE_BLACK(R.drawable.ic_launcher_preview_white_black, "WhiteBlackLauncher"),
    BLACK_WHITE(R.drawable.ic_launcher_preview_black_white, "BlackWhiteLauncher"),
    COLOR_WHITE(R.drawable.ic_launcher_preview_color_white, "ColorWhiteLauncher"),
}

/** Launcher aliases are the source of truth; no separate preference can drift out of sync. */
class AppIconManager(context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    private fun component(icon: AppIcon) = ComponentName(
        packageName,
        "${RemoteActivity::class.java.name.substringBeforeLast('.')}.${icon.aliasName}",
    )

    private fun isEnabled(icon: AppIcon): Boolean =
        when (packageManager.getComponentEnabledSetting(component(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.WHITE_COLOR
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }

    fun selectedIcon(): AppIcon = AppIcon.entries.firstOrNull(::isEnabled) ?: AppIcon.WHITE_COLOR

    internal fun selectedComponent(): ComponentName = component(selectedIcon())

    fun setIcon(icon: AppIcon) {
        if (isEnabled(icon) && AppIcon.entries.none { it != icon && isEnabled(it) }) return
        // Enable first on older Android versions: never leave the app without a launcher.
        val ordered = listOf(icon) + AppIcon.entries.filter { it != icon }
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.setComponentEnabledSettings(ordered.map {
                PackageManager.ComponentEnabledSetting(component(it), state(it == icon), PackageManager.DONT_KILL_APP)
            })
        } else {
            ordered.forEach {
                packageManager.setComponentEnabledSetting(component(it), state(it == icon), PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun state(enabled: Boolean): Int = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
