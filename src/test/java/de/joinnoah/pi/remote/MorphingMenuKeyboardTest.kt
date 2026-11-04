package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MorphingMenuKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rowsCannotTakeKeyboardFocusUntilOpeningFinishes() {
        var calls = 0
        val rowFocus = FocusRequester()
        // Popup has a separate owner. Model hardware input inside that owner rather than
        // overriding the activity's manager, which does not propagate across windows.
        val keyboard = object : InputModeManager {
            override val inputMode = InputMode.Keyboard
            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }
        lateinit var menu: MorphMenuState
        compose.setContent {
            MaterialTheme {
                menu = rememberMorphMenuState()
                Box(Modifier.padding(top = 320.dp)) {
                    TextButton(
                        onClick = { menu.expanded = true },
                        modifier = Modifier.size(96.dp, 48.dp).morphMenuAnchor(menu),
                    ) { Text("Open") }
                    MorphingMenu(
                        menu, MorphMenuDirection.DownFromEnd,
                        controls = { TextButton(onClick = menu::dismiss) { Text("Close") } },
                    ) {
                        CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                            DropdownMenuItem(
                                text = { Text("Choice") },
                                enabled = menu.contentInteractive,
                                modifier = Modifier.focusRequester(rowFocus),
                                onClick = {
                                    if (menu.contentInteractive) {
                                        calls++
                                        menu.dismiss()
                                    }
                                },
                            )
                            // The first row is already visible while the rest of a real menu
                            // is still expanding. A one-row fixture would only test clipping.
                            repeat(3) { index ->
                                DropdownMenuItem(
                                    text = { Text("Other $index") },
                                    enabled = menu.contentInteractive,
                                    onClick = { if (menu.contentInteractive) calls++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open").performClick()
        // Let the separate popup owner attach before advancing its animation clock.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            assertFalse("Clipped row must not acquire hardware-keyboard focus", rowFocus.requestFocus())
            assertEquals(0, calls)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(menu.contentInteractive)
            assertTrue("Fully open row must accept keyboard focus", rowFocus.requestFocus())
        }
        compose.onNodeWithText("Choice").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
