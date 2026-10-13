package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutTooltipUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0

    private fun render() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalHardwareKeyboard provides true) {
                    ShortcutTooltip("Stop", Shortcut.STOP) {
                        IconButton(onClick = { clicks++ }, modifier = Modifier.testTag("stop")) { Text("■") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aLongPressStillActsAndShowsNoTooltip() {
        render()
        compose.onNodeWithTag("stop").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, clicks)
        compose.onNodeWithText("Stop · Ctrl+.").assertDoesNotExist()
    }

    @Test
    fun hoveringShowsTheLabelAndShortcut() {
        render()
        compose.onNodeWithTag("stop").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        compose.onNodeWithText("Stop · Ctrl+.").assertExists()
        assertEquals(0, clicks)
    }
}
