package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlassMenuDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun menuOpensAndItemCanDismissIt() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.TextButton(onClick = { expanded = true }) { Text("Open") }
                    GlassDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Choice") }, onClick = { expanded = false })
                    }
                }
            }
        }
        compose.onNodeWithText("Choice").assertDoesNotExist()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Choice").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertFalse(expanded)
    }
}
