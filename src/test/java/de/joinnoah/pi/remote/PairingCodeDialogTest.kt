package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialog for a pasted pairing code, rendered on its own: over the whole app at a phone's size,
 * Robolectric never settles with a text field in a dialog, so [PairingUiTest] stops at the buttons
 * that open it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingCodeDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun label(id: Int) = compose.activity.getString(id)

    private fun clip(text: String) {
        context().getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", text))
    }

    private fun secureWindow(): Boolean =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    // ---- The code dialog -----------------------------------------------------------------

    private fun renderDialog(): MutableList<String> {
        val pairs = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(true) }
                var text by remember { mutableStateOf("") }
                if (open)
                    PairingCodeDialog(
                        text = text,
                        onTextChange = { text = it },
                        onPair = {
                            pairs += text
                            open = false
                        },
                        onDismiss = { open = false },
                    )
            }
        }
        compose.waitForIdle()
        return pairs
    }

    @Test
    fun thePasteDialogTakesAPairingCodeFromTheClipboard() {
        clip("  $PAIRING_CODE\n")
        val pairs = renderDialog()
        compose.onNodeWithTag("pairingCodeField").assert(hasText(PAIRING_CODE))
        compose.onNodeWithTag("confirmPairingCode").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PAIRING_CODE), pairs)
    }

    @Test
    fun thePasteDialogLeavesOtherClipsOnTheClipboard() {
        clip("https://example.com/not-a-code")
        renderDialog()
        compose.onNodeWithTag("pairingCodeField").assertExists()
        compose.onNode(hasTestTag("pairingCodeField") and hasText("https://example.com/not-a-code")).assertDoesNotExist()
        compose.onNodeWithTag("confirmPairingCode").assertIsNotEnabled()
    }

    @Test
    fun onlyAWholePairingCodeCountsAsOne() {
        assertEquals(PAIRING_CODE, pairingCodeFromClip(" $PAIRING_CODE "))
        assertNull(pairingCodeFromClip(null))
        assertNull(pairingCodeFromClip(""))
        assertNull(pairingCodeFromClip("pi-remote://pair#nonsense"))
        assertNull(pairingCodeFromClip("Here is my code: $PAIRING_CODE"))
    }

    @Test
    fun theCodeFieldUsesThePasswordKeyboardWithoutAutocorrect() {
        assertEquals(KeyboardType.Password, PAIRING_CODE_KEYBOARD.keyboardType)
        assertFalse(PAIRING_CODE_KEYBOARD.autoCorrectEnabled == true)
        assertEquals(ImeAction.Done, PAIRING_CODE_KEYBOARD.imeAction)
    }

    @Test
    fun theWindowStaysOutOfScreenshotsWhileTheDialogIsOpen() {
        assertFalse(secureWindow())
        renderDialog()
        assertTrue(secureWindow())
        compose.onNodeWithText(label(R.string.remote_cancel)).performClick()
        compose.waitForIdle()
        assertFalse(secureWindow())
    }

    private companion object {
        /** The protocol fixture's pairing code (src/test/resources/v1.json). */
        const val PAIRING_CODE =
            "pi-remote://pair#eyJ2IjoxLCJyZWxheSI6Imh0dHBzOi8vcmVsYXkuZXhhbXBsZSIsInJvdXRlSWQiOiJBQUVDQXdRRkJnY0lDUW9MREEwT0R3Ii" +
                "wicGFpcklkIjoiRUJFU0V4UVZGaGNZR1JvYkhCMGVIdyIsInNlY3JldCI6Im9LR2lvNlNscHFlb3FhcXJySzJ1cjdDeHNyTzB0YmEzdUxtNnU3eT" +
                "l2cjgiLCJleHBpcmVzQXQiOjE3MzAwMDAxMjAwMDB9"
    }
}
