package de.joinnoah.pi.remote

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChildStopUiTest {
    @get:Rule val compose = createComposeRule()

    private var aborts = 0
    private var questionAborts = 0
    private var childStops = 0
    private val current = mutableStateOf(RemoteState())

    private fun render(
        child: Boolean,
        question: Boolean = false,
        control: ChildControl = ChildControl(ChildControlPhase.NOT_RUNNING),
    ) {
        val session = Wire.objectOf("id" to "child", "origin" to "rpc", "status" to "running")
        val state =
            RemoteState(
                selection = RemoteSelection("host", "project", "child"),
                connected = true,
                session =
                    if (child) JsonObject(session + ("parentSessionId" to JsonPrimitive("parent")))
                    else session,
                status = if (question) "waiting" else "running",
                draft = "Check the tests",
                questions =
                    if (question) listOf(Wire.objectOf("id" to "q1", "kind" to "plan", "plan" to "Step one"))
                    else emptyList(),
                capabilities = setOf(SUBAGENT_CONTROL_CAPABILITY, STEER_CAPABILITY, FOLLOW_UP_CAPABILITY),
                // By default a steer met a child that is not running: the composer offers a resume.
                childControls = mapOf("child" to control),
            )
        current.value = state
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    current.value,
                    onDraft = {},
                    onSend = {},
                    onStop = { aborts++ },
                    onRemoveQuote = {},
                    onStopChild = { childStops++ },
                    onAbort = { questionAborts++ },
                )
            }
        }
    }

    @Test
    fun busyStopInAChildOfferingResumeAsksBeforeStoppingThroughTheParent() {
        render(child = true)
        compose.onNodeWithTag("stopRun").performClick()
        compose.onNodeWithTag("confirmChildStop").assertIsDisplayed().performClick()
        assertEquals(0, aborts)
        assertEquals(1, childStops)
    }

    @Test
    fun busyStopOutsideAChildStillAborts() {
        render(child = false)
        compose.onNodeWithTag("stopRun").performClick()
        assertEquals(1, aborts)
        assertEquals(0, childStops)
    }

    @Test
    fun questionAbortInAChildAsksBeforeStoppingThroughTheParent() {
        render(child = true, question = true)
        compose.onNodeWithTag("abortRun").performClick()
        compose.onNodeWithTag("confirmChildStop").assertIsDisplayed().performClick()
        assertEquals(0, questionAborts)
        assertEquals(0, aborts)
        assertEquals(1, childStops)
    }

    @Test
    fun questionAbortOutsideAChildStillAborts() {
        render(child = false, question = true)
        compose.onNodeWithTag("abortRun").performClick()
        compose.onNodeWithTag("confirmChildStop").assertDoesNotExist()
        assertEquals(1, questionAborts)
        assertEquals(0, childStops)
    }

    @Test
    fun questionAbortInAChildWaitsForAStopInFlight() {
        render(child = true, question = true, control = ChildControl(ChildControlPhase.STOPPING))
        compose.onNodeWithTag("abortRun").assertIsNotEnabled()
    }

    @Test
    fun confirmingAfterTheChildWentIdleStillReachesTheStop() {
        render(child = true)
        compose.onNodeWithTag("stopRun").performClick()
        compose.onNodeWithTag("confirmChildStop").assertIsDisplayed()
        // The child goes idle while the dialog is open; the repository explains the no-op.
        compose.runOnIdle {
            current.value = current.value.copy(status = "idle", childControls = emptyMap())
        }
        compose.onNodeWithTag("confirmChildStop").assertIsDisplayed().performClick()
        assertEquals(0, aborts)
        assertEquals(1, childStops)
    }

    @Test
    fun parentOfflineStopHasItsOwnMessageInBothLocales() {
        val context = RuntimeEnvironment.getApplication()
        val german =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) }
            )
        for (localized in listOf<Context>(context, german)) {
            val stop = localized.getString(R.string.remote_child_stop_parent_offline)
            assertTrue(stop.isNotBlank())
            assertNotEquals(localized.getString(R.string.remote_child_parent_offline), stop)
        }
        assertNotEquals(
            context.getString(R.string.remote_child_stop_parent_offline),
            german.getString(R.string.remote_child_stop_parent_offline),
        )
    }
}
