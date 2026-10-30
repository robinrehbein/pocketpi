package de.joinnoah.pi.remote

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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
}
