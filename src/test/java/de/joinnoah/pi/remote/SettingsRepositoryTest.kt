package de.joinnoah.pi.remote

import android.app.UiModeManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The night modes handed to the system, in order. */
    private val applied = mutableListOf<ThemeMode>()

    private fun repository() = DefaultSettingsRepository(context) { applied += it }

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @After
    fun forgetPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun storedPaletteValuesReadAsTheirEnums() {
        assertEquals(ThemePalette.BRAND, repository().state.value.theme)
        val stored =
            mapOf(
                "system" to ThemePalette.BRAND,
                "synthwave" to ThemePalette.SYNTHWAVE,
                "dynamic" to ThemePalette.DYNAMIC,
                // Values from before the mode existed, or from a newer version, read as the brand palette.
                "light" to ThemePalette.BRAND,
                "dark" to ThemePalette.BRAND,
                "unknown" to ThemePalette.BRAND,
            )
        for ((value, palette) in stored) {
            preferences.edit().putString("theme", value).commit()
            assertEquals(value, palette, DefaultSettingsRepository(context).state.value.theme)
        }
    }

    @Test
    fun storedModeValuesReadAsTheirEnums() {
        assertEquals(ThemeMode.SYSTEM, repository().state.value.themeMode)
        for (mode in ThemeMode.entries) {
            preferences.edit().putString("theme_mode", mode.stored).commit()
            assertEquals(mode, DefaultSettingsRepository(context).state.value.themeMode)
        }
        for (value in listOf("synthwave", "unknown", "")) {
            preferences.edit().putString("theme_mode", value).commit()
            assertEquals(value, ThemeMode.SYSTEM, DefaultSettingsRepository(context).state.value.themeMode)
        }
    }

    @Test
    fun storedThinkingValuesReadAsTheirEnums() {
        assertEquals(ThinkingDisplay.STATUS, repository().state.value.thinkingDisplay)
        preferences.edit().putString("thinking_display", "text").commit()
        assertEquals(ThinkingDisplay.TEXT, DefaultSettingsRepository(context).state.value.thinkingDisplay)
        preferences.edit().putString("thinking_display", "unknown").commit()
        assertEquals(ThinkingDisplay.STATUS, DefaultSettingsRepository(context).state.value.thinkingDisplay)
    }

    @Test
    fun paletteAndModeRoundTripIndependently() {
        val settings = repository()
        settings.setTheme(ThemePalette.SYNTHWAVE)
        settings.setThemeMode(ThemeMode.DARK)

        val restored = DefaultSettingsRepository(context).state.value
        assertEquals(ThemePalette.SYNTHWAVE, restored.theme)
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals("synthwave", preferences.getString("theme", null))
        assertEquals("dark", preferences.getString("theme_mode", null))

        settings.setTheme(ThemePalette.DYNAMIC)
        settings.setThemeMode(ThemeMode.SYSTEM)
        val changedAgain = DefaultSettingsRepository(context).state.value
        assertEquals(ThemePalette.DYNAMIC, changedAgain.theme)
        assertEquals(ThemeMode.SYSTEM, changedAgain.themeMode)
    }

    @Test
    fun thinkingDisplayRoundTripsWithoutTouchingTheTheme() {
        val settings = repository()
        settings.setThinkingDisplay(ThinkingDisplay.TEXT)
        assertEquals("text", preferences.getString("thinking_display", null))
        val restored = DefaultSettingsRepository(context).state.value
        assertEquals(ThinkingDisplay.TEXT, restored.thinkingDisplay)
        assertEquals(ThemePalette.BRAND, restored.theme)
        assertEquals(ThemeMode.SYSTEM, restored.themeMode)
    }

    @Test
    fun nightModeIsAppliedWhenTheModeChanges() {
        val settings = repository()
        applied.clear()
        settings.setTheme(ThemePalette.SYNTHWAVE)
        assertTrue("A palette change does not touch the night mode", applied.isEmpty())

        settings.setThemeMode(ThemeMode.DARK)
        settings.setThemeMode(ThemeMode.DARK)
        settings.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.SYSTEM), applied)
    }

    @Test
    fun storedNightModeIsHandedToTheSystemOnEveryStart() {
        repository()
        assertEquals("Nothing stored follows the device", listOf(ThemeMode.SYSTEM), applied)

        preferences.edit().putString("theme_mode", "dark").commit()
        applied.clear()
        repository()
        assertEquals(listOf(ThemeMode.DARK), applied)
    }

    @Test
    fun dynamicPaletteIsOfferedOnlyFromAndroid12() {
        assertEquals(listOf(ThemePalette.BRAND, ThemePalette.SYNTHWAVE), ThemePalette.available(30))
        assertEquals(
            listOf(ThemePalette.BRAND, ThemePalette.SYNTHWAVE, ThemePalette.DYNAMIC),
            ThemePalette.available(31),
        )
        assertFalse(ThemePalette.DYNAMIC.isAvailable(30))
        assertTrue(ThemePalette.DYNAMIC.isAvailable(31))
    }

    @Test
    fun storedDynamicShowsAsTheBrandPaletteBelowAndroid12() {
        assertEquals(ThemePalette.BRAND, ThemePalette.DYNAMIC.effective(30))
        assertEquals(ThemePalette.DYNAMIC, ThemePalette.DYNAMIC.effective(31))
        assertEquals(ThemePalette.SYNTHWAVE, ThemePalette.SYNTHWAVE.effective(30))
    }

    @Test
    fun themeModeFollowsTheDeviceOnlyWhenNotForced() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    @Config(sdk = [31])
    fun appNightModeFollowsTheChosenModeFromAndroid12() {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        applyAppNightMode(context, ThemeMode.DARK)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(uiModeManager).applicationNightMode)
        applyAppNightMode(context, ThemeMode.LIGHT)
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(uiModeManager).applicationNightMode)
        applyAppNightMode(context, ThemeMode.SYSTEM)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(uiModeManager).applicationNightMode)
    }

    @Test
    @Config(sdk = [30])
    fun appNightModeIsLeftAloneBelowAndroid12() {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        val before = shadowOf(uiModeManager).applicationNightMode
        applyAppNightMode(context, ThemeMode.DARK)
        assertEquals(before, shadowOf(uiModeManager).applicationNightMode)
    }

    @Test
    @Config(sdk = [30])
    fun dynamicColourFallsBackToTheBrandSchemeBelowAndroid12() {
        val brand = remoteColorScheme(dark = false)
        assertEquals(brand.primary, appColorScheme(context, dark = false, palette = ThemePalette.DYNAMIC).primary)
        assertEquals(
            remoteColorScheme(dark = true, synthwave = true).primary,
            appColorScheme(context, dark = true, palette = ThemePalette.SYNTHWAVE).primary,
        )
    }
}
