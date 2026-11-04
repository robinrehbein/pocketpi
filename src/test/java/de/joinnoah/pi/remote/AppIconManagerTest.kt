package de.joinnoah.pi.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33])
class AppIconManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = AppIconManager(context)
    private val pm = context.packageManager

    private fun component(icon: AppIcon) = ComponentName(context.packageName, "de.joinnoah.pi.remote.${icon.aliasName}")

    @Test
    fun defaultSelectionRespectsManifestWithoutWritingState() {
        assertEquals("de.robinrehbein.pocketpi", context.packageName)
        assertEquals(AppIcon.WHITE_COLOR, manager.selectedIcon())
        AppIcon.entries.forEach { icon ->
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(component(icon)))
            val info = pm.getActivityInfo(component(icon), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(icon == AppIcon.WHITE_COLOR, info.enabled)
            assertEquals("de.joinnoah.pi.remote.RemoteActivity", info.targetActivity)
        }
    }

    @Test
    fun allVariantsAndRepeatedRoundTripsKeepExactlyOneLauncher() {
        repeat(3) {
            (AppIcon.entries + AppIcon.WHITE_COLOR).forEach { selected ->
                repeat(2) { manager.setIcon(selected) }
                assertEquals(selected, AppIconManager(context).selectedIcon())
                assertEquals(component(selected), manager.selectedComponent())
                AppIcon.entries.forEach { icon ->
                    val state = pm.getComponentEnabledSetting(component(icon))
                    val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                        (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == AppIcon.WHITE_COLOR)
                    if (icon == selected) assertTrue(enabled) else assertFalse(enabled)
                }
            }
        }
    }

    @Test
    fun selectingVariantRestoresLauncherWhenAllAliasesAreDisabled() {
        AppIcon.entries.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        manager.setIcon(AppIcon.COLOR_WHITE)
        assertEquals(AppIcon.COLOR_WHITE, manager.selectedIcon())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(component(AppIcon.COLOR_WHITE)))
    }

    @Test
    fun switchingRepairsMultipleEnabledAliases() {
        AppIcon.entries.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        manager.setIcon(AppIcon.WHITE_COLOR)
        assertEquals(AppIcon.WHITE_COLOR, manager.selectedIcon())
        AppIcon.entries.filter { it != AppIcon.WHITE_COLOR }.forEach {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component(it)))
        }
    }
}
