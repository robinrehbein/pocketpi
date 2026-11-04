package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIconSettingsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingEachVariantUpdatesLauncherAndRadioSelection() {
        compose.setContent { MaterialTheme { AppIconSettingsSection() } }
        compose.onNodeWithTag("appIconWHITE_COLOR").assertIsSelected()
        AppIcon.entries.forEach { icon ->
            compose.onNodeWithTag("appIcon${icon.name}").performClick().assertIsSelected()
            assertEquals(icon, AppIconManager(compose.activity).selectedIcon())
        }
    }
}
