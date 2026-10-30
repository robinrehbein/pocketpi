package de.joinnoah.pi.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalInspectorLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingTabsKeepsTerminalUntilClosed() {
        var tabs by mutableStateOf(InspectorTabs().open(InspectorTab.TERMINAL))
        var attached = 0
        var detached = 0
        compose.setContent {
            InspectorHost(tabs, content = { tab ->
                if (tab == InspectorTab.TERMINAL) ({ DisposableEffect(Unit) { attached++; onDispose { detached++ } } })
                else ({})
            }, onSelect = {}, onClose = {}, expanded = false, onExpand = {})
        }
        compose.runOnIdle { tabs = tabs.open(InspectorTab.FILES) }
        compose.runOnIdle { assertEquals(1, attached); assertEquals(0, detached); tabs = tabs.close(InspectorTab.TERMINAL) }
        compose.runOnIdle { assertEquals(1, detached) }
    }
    @Test fun retainedHiddenTerminalReleasesKeyboardOwnership() {
        var tabs by mutableStateOf(InspectorTabs().open(InspectorTab.TERMINAL))
        val focus = TerminalInputFocus()
        val sent = mutableListOf<String>()
        val chords = mutableListOf<TerminalInput.Chord>()
        var draft by mutableStateOf("unsent")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTerminalInputFocus provides focus) {
                    InspectorHost(tabs, content = { tab ->
                        if (tab == InspectorTab.TERMINAL) ({
                            OutlinedTextField(draft, { draft = it }, modifier = Modifier.testTag("terminalKeyboardInput")
                                .then(rememberTerminalInputKeys(false, true, false, { sent += it }, { chords += it })))
                        }) else ({})
                    }, onSelect = {}, onClose = {}, expanded = false, onExpand = {})
                }
            }
        }
        compose.onNodeWithTag("terminalKeyboardInput").performClick()
        compose.runOnIdle { assertTrue(focus.focused) }
        compose.onNodeWithTag("terminalKeyboardInput").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        compose.runOnIdle {
            assertEquals("unsent", draft)
            assertEquals(listOf(TerminalInput.Chord("c", ctrl = true)), chords)
        }
        compose.onNodeWithTag("terminalKeyboardInput").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle {
            assertEquals(listOf("escape"), sent)
            tabs = tabs.open(InspectorTab.FILES)
        }
        compose.runOnIdle {
            assertFalse(focus.focused)
            tabs = tabs.open(InspectorTab.TERMINAL)
        }
        compose.onNodeWithTag("terminalKeyboardInput").performClick()
        compose.runOnIdle {
            assertTrue(focus.focused)
            tabs = tabs.close(InspectorTab.TERMINAL)
        }
        compose.runOnIdle { assertFalse(focus.focused) }
    }

}
