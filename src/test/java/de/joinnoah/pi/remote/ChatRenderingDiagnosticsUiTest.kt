package de.joinnoah.pi.remote

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRenderingDiagnosticsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun reset() { ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options()) }
    @After fun cleanup() { reset() }

    @Test fun controlsAreDefaultOffAndCanBeChangedIndependently() {
        compose.setContent { MaterialTheme { ChatRenderingDiagnosticsControls() } }
        compose.onNodeWithTag("renderRecord").assertIsOff().performClick().assertIsOn()
        assertEquals(ChatRenderingDiagnostics.Options(recording = true), ChatRenderingDiagnostics.options.value)
        compose.onNodeWithTag("renderNoGlass").assertIsOff().performClick().assertIsOn()
        assertFalse(ChatRenderingDiagnostics.options.value.disableMotion)
        compose.onNodeWithTag("renderNoMotion").performClick().assertIsOn()
        assertTrue(ChatRenderingDiagnostics.options.value.recording)
    }

    @Test fun disablingGlassRemovesBackdropSourceAndConsumerState() {
        var hasBackdrop = false
        compose.setContent { MaterialTheme { ProvideGlass {
            hasBackdrop = LocalGlassBackdrop.current != null
            Text("Fixture")
        } } }
        compose.waitForIdle()
        assertTrue(hasBackdrop)
        compose.runOnIdle { ChatRenderingDiagnostics.update(ChatRenderingDiagnostics.Options(disableGlass = true)) }
        compose.waitForIdle()
        assertFalse(hasBackdrop)
    }

    @Test fun reportOnlyLeavesAppAfterExplicitShareAction() {
        val report = "{\"format\":\"pocketpi-chat-render-v1\",\"frames\":[]}"
        compose.setContent { MaterialTheme { ChatRenderingSnapshotDialog(report) {} } }
        compose.waitForIdle()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("shareRenderSnapshot").performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(report, send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.data)
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
    }
}
