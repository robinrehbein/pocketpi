package de.joinnoah.pi.remote

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmulatorPaneTest {
    @get:Rule val compose = createComposeRule()
    @Test fun profileChoiceRequiresExplicitSelection() {
        val selected = mutableListOf<String>()
        val actions = EmulatorPaneActions({ selected.add(it); true }, { true }, { true }, { true }, { true }, { true }, {})
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, profiles = listOf(EmulatorProfileInfo("phone", "Test phone"))), actions, {}) } }
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("Test phone").performClick()
        compose.runOnIdle { assertEquals(listOf("phone"), selected) }
    }
    @Test fun canceledPointerDoesNotSendInput() {
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val inputs = mutableListOf<EmulatorInput>()
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { inputs.add(it); true }, { true }, {})
        val image = EmulatorImage(bytes, 20, 30, 20, 30, 0, 1, 1)
        compose.setContent { MaterialTheme { EmulatorPane(EmulatorView("s", opening = false, emulatorId = "e", ownsControl = true, image = image), actions, {}) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(emptyList<EmulatorInput>(), inputs) }
    }
    @Test fun profilePickerDisplaysLoadAndOpenFailures() {
        var view by androidx.compose.runtime.mutableStateOf(EmulatorView("s", opening = false, notice = "failed"))
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { true }, { true }, {})
        compose.setContent { MaterialTheme { EmulatorPane(view, actions, {}) } }
        compose.onNodeWithText("Emulator request failed").assertIsDisplayed()
        compose.runOnIdle { view = view.copy(profiles = listOf(EmulatorProfileInfo("phone", "Test phone")), profileId = "phone") }
        compose.onNodeWithText("Emulator request failed").assertIsDisplayed()
        compose.onNodeWithText("Test phone").assertIsDisplayed()
    }
    @Test fun newFramesDoNotCancelAnActiveSwipe() {
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val inputs = mutableListOf<EmulatorInput>()
        val actions = EmulatorPaneActions({ true }, { true }, { true }, { true }, { inputs.add(it); true }, { true }, {})
        var view by androidx.compose.runtime.mutableStateOf(EmulatorView("s", opening = false, emulatorId = "e", ownsControl = true, image = EmulatorImage(bytes, 20, 30, 20, 30, 0, 1, 1)))
        compose.setContent { MaterialTheme { EmulatorPane(view, actions, {}) } }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { down(center); moveBy(Offset(0f, 30f)) }
        compose.runOnIdle { view = view.copy(image = requireNotNull(view.image).copy(frameId = 2)) }
        compose.onNodeWithTag("emulatorScreen").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, inputs.size); assertTrue(inputs.single() is EmulatorInput.Swipe) }
    }
}
