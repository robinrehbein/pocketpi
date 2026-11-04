package de.joinnoah.pi.remote

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: closing a UI gate must invalidate typed activity without a new ledger instance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class StructuredActivityRecompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unchangedLedgerDoesNotCacheAnOpenGateAcrossRecomposition() {
        val repository = NavigationFakeRepository()
        val target = RemoteSelection("host", "project", "s1")
        val now = SystemClock.elapsedRealtime()
        val snapshot = ActivitySnapshot.Active(
            "clock", "epoch", 1, now + 30_000, ActivityPhase.Tool, null,
            listOf(ActivityTool("call", "read")), null,
        )
        val ledger = ExecutionActivityState(
            connectionId = "connection", negotiated = true,
            clock = ActivityClockBaseline("connection", "clock", 0),
            sessions = mapOf("s1" to ActivityView("connection", 1, snapshot)),
            sessionObservedUntil = mapOf("s1" to now + 30_000),
        )
        val session = Wire.objectOf("id" to "s1", "title" to "Session", "status" to "running", "origin" to "rpc")
        val project = Wire.objectOf("id" to "project", "name" to "Project")
        repository.state.value = RemoteState(
            connected = true, connection = R.string.remote_connected, status = "running",
            selection = target, project = project, projects = listOf(project),
            session = session, sessions = listOf(session), executionActivity = ledger,
            messages = listOf(Wire.objectOf(
                "id" to "output", "role" to "tool", "toolName" to "read",
                "toolCallId" to "call", "state" to "streaming", "text" to "Reading",
            )),
        )
        repository.notificationLookup = { target }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, FakeSettingsRepository(), false, {}, RemoteNotification("host", "s1", 1))
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val baseline = repository.state.value
        val blocked: List<(RemoteState) -> RemoteState> = listOf(
            { it.copy(questions = listOf(Wire.objectOf("id" to "q1", "kind" to "confirm", "title" to "Continue?"))) },
            { it.copy(status = "waiting") },
            { it.copy(stale = true) },
            { it.copy(preview = true) },
        )
        for (closeGate in blocked) {
            compose.runOnIdle { repository.state.value = baseline }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("toolGlyph").assertExists()
            compose.runOnIdle {
                repository.state.value = closeGate(baseline)
                assertSame(ledger, repository.state.value.executionActivity)
            }
            compose.mainClock.advanceTimeBy(100)
            // The card stays visible: only its motion changes. This exercises RemoteScreen's
            // actual typedChat evaluation, not a second miniature implementation of its logic.
            compose.onNodeWithTag("toolCard").assertExists()
            compose.onNodeWithTag("toolGlyphStatic").assertExists()
        }
    }
}
