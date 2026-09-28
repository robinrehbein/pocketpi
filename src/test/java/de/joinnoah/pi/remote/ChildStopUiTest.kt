package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChildStopUiTest {
    @get:Rule val compose = createComposeRule()

    private var aborts = 0
    private var childStops = 0

    private fun render(child: Boolean) {
        val session = Wire.objectOf("id" to "child", "origin" to "rpc", "status" to "running")
        val state =
            RemoteState(
                selection = RemoteSelection("host", "project", "child"),
                connected = true,
                session =
                    if (child) JsonObject(session + ("parentSessionId" to JsonPrimitive("parent")))
                    else session,
                status = "running",
                draft = "Check the tests",
                capabilities = setOf(SUBAGENT_CONTROL_CAPABILITY, STEER_CAPABILITY, FOLLOW_UP_CAPABILITY),
                // A steer met a child that is not running: the composer offers a resume.
                childControls = mapOf("child" to ChildControl(ChildControlPhase.NOT_RUNNING)),
            )
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    state,
                    onDraft = {},
                    onSend = {},
                    onStop = { aborts++ },
                    onRemoveQuote = {},
                    onStopChild = { childStops++ },
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
}
