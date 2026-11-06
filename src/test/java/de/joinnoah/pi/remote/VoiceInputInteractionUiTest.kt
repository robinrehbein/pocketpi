package de.joinnoah.pi.remote

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceInputInteractionUiTest {
    @get:Rule val compose = createComposeRule()

    private val source = MutableInteractionSource()
    private val interactions = mutableListOf<Interaction>()
    private val showing = mutableStateOf(true)
    private val current = mutableStateOf(
        RemoteState(
            selection = RemoteSelection("host", "project", "live"),
            connected = true,
            status = "running",
            draft = "Next step",
            session = Wire.objectOf("id" to "live", "origin" to "rpc"),
            capabilities = setOf(STEER_CAPABILITY, FOLLOW_UP_CAPABILITY),
        )
    )
    private var sends = 0
    private lateinit var focusManager: FocusManager
    private lateinit var view: View
    private val engine = object : VoiceRecognitionEngine {
        override val systemAvailable = true
        override val onDeviceAvailable = true
        override fun start(start: VoiceInputStart, listener: VoiceRecognitionListener) = Unit
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private fun render() {
        compose.setContent {
            focusManager = LocalFocusManager.current
            view = LocalView.current
            LaunchedEffect(source) { source.interactions.collect { if (it is PressInteraction) interactions.add(it) } }
            val binding = rememberVoiceInputBinding(current.value.draft, {}, engine)
            MaterialTheme {
                if (showing.value) VoiceInput(
                    state = current.value,
                    binding = binding,
                    onSend = { sends++ },
                    busyAction = if (FOLLOW_UP_CAPABILITY in current.value.capabilities &&
                        STEER_CAPABILITY !in current.value.capabilities) BusyComposerAction.FollowUp
                    else BusyComposerAction.Steer,
                    busyActionEnabled = true,
                    onBusyAction = { sends++ },
                    modifier = Modifier.size(48.dp).testTag("action"),
                    permissionGrantedOverride = true,
                    interactionSourceOverride = source,
                )
            }
        }
        compose.onNodeWithTag("action").performSemanticsAction(SemanticsActions.RequestFocus)
    }

    @Test
    fun glassActionKeepsThe48DpTouchTarget() {
        render()
        compose.onNodeWithTag("action")
            .assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun validKeyPressReleasesOnceAndSendsOnce() {
        render()
        compose.onNodeWithTag("action").performKeyInput { keyDown(Key.Spacebar) }
        compose.waitForIdle()
        assertEquals(1, interactions.size)
        val press = interactions.single() as PressInteraction.Press
        compose.onNodeWithTag("action").performKeyInput { keyUp(Key.Spacebar) }
        compose.waitForIdle()
        assertEquals(2, interactions.size)
        assertSame(press, (interactions[1] as PressInteraction.Release).press)
        assertEquals(1, sends)
    }

    @Test
    fun cancelledNativeKeyUpCancelsPressAndNextKeyCanSend() {
        render()
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, time: Long, flags: Int = 0) {
            compose.runOnIdle {
                view.dispatchKeyEvent(AndroidKeyEvent(
                    time, SystemClock.uptimeMillis(), action, AndroidKeyEvent.KEYCODE_SPACE,
                    0, 0, 0, 0, flags,
                ))
            }
        }
        dispatch(AndroidKeyEvent.ACTION_DOWN, downTime)
        compose.waitForIdle()
        val first = interactions.single() as PressInteraction.Press
        dispatch(AndroidKeyEvent.ACTION_UP, downTime, AndroidKeyEvent.FLAG_CANCELED)
        compose.waitForIdle()
        assertEquals(2, interactions.size)
        assertSame(first, (interactions[1] as PressInteraction.Cancel).press)
        assertEquals(0, sends)

        val nextTime = SystemClock.uptimeMillis()
        dispatch(AndroidKeyEvent.ACTION_DOWN, nextTime)
        dispatch(AndroidKeyEvent.ACTION_UP, nextTime)
        compose.waitForIdle()
        assertEquals(4, interactions.size)
        assertSame(interactions[2], (interactions[3] as PressInteraction.Release).press)
        assertEquals(1, sends)
    }

    @Test
    fun contextChangeCancelsHeldPressAndFreshPressStillWorks() {
        render()
        compose.onNodeWithTag("action").performKeyInput { keyDown(Key.Spacebar) }
        compose.waitForIdle()
        val press = interactions.single() as PressInteraction.Press
        compose.runOnIdle { current.value = current.value.copy(capabilities = setOf(FOLLOW_UP_CAPABILITY)) }
        compose.waitForIdle()
        assertEquals(2, interactions.size)
        assertSame(press, (interactions[1] as PressInteraction.Cancel).press)
        compose.onNodeWithTag("action").performKeyInput { keyUp(Key.Spacebar) }
        assertEquals(0, sends)
        compose.onNodeWithTag("action").performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
        compose.waitForIdle()
        assertEquals(4, interactions.size)
        assertSame(interactions[2], (interactions[3] as PressInteraction.Release).press)
        assertEquals(1, sends)
    }

    @Test
    fun focusLossCancelsHeldPress() {
        render()
        compose.onNodeWithTag("action").performKeyInput { keyDown(Key.Spacebar) }
        compose.waitForIdle()
        val press = interactions.single() as PressInteraction.Press
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
        assertEquals(2, interactions.size)
        assertSame(press, (interactions[1] as PressInteraction.Cancel).press)
        assertEquals(0, sends)
    }

    @Test
    fun disposalCancelsHeldPress() {
        render()
        compose.onNodeWithTag("action").performKeyInput { keyDown(Key.Spacebar) }
        compose.waitForIdle()
        val press = interactions.single() as PressInteraction.Press
        compose.runOnIdle { showing.value = false }
        compose.waitForIdle()
        assertEquals(2, interactions.size)
        assertSame(press, (interactions[1] as PressInteraction.Cancel).press)
        assertEquals(0, sends)
    }
}
