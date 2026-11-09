package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests the real observer without depending on chat loading, auto-follow or history pagination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ComposerDragUiTest {
    @get:Rule val compose = createComposeRule()

    private class Harness {
        var restingTop by mutableStateOf(0f)
        var travel by mutableStateOf(0)
        var dragging by mutableStateOf(false)
        var hidden by mutableStateOf(false)
        var progress by mutableStateOf(0f)
        var allowed by mutableStateOf(true)
        val lift get() = if (dragging) progress else if (hidden) travel.toFloat() else 0f
    }

    private fun open(): Harness {
        val state = Harness()
        compose.setContent {
            // Nonzero root origin catches mixing local finger positions with root coordinates.
            Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                Box(Modifier.fillMaxSize().testTag("chat").observeComposerDrag(
                    key = "chat",
                    allowed = { state.allowed },
                    restingTop = { state.restingTop },
                    travel = { state.travel },
                    hidden = { state.hidden },
                    dragging = { state.dragging },
                    onDrag = { state.dragging = true; state.progress = it },
                    onReveal = { state.hidden = false; state.dragging = false; state.progress = 0f },
                    onSettle = {
                        state.hidden = composerShouldSettleHidden(state.progress, state.travel.toFloat())
                        state.dragging = false
                    },
                )) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp)
                        .testTag("restingWrapper")
                        .onSizeChanged { state.travel = it.height }
                        .onGloballyPositioned { state.restingTop = it.positionInRoot().y }) {
                        Box(Modifier.fillMaxSize()
                            .graphicsLayer { translationY = state.lift }.testTag("composer"))
                    }
                }
            }
        }
        compose.waitForIdle()
        return state
    }

    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val density = compose.density
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return with(density) {
            androidx.compose.ui.geometry.Rect(bounds.left.toPx(), bounds.top.toPx(), bounds.right.toPx(), bounds.bottom.toPx())
        }
    }
    private fun localEdge(state: Harness) = state.restingTop - bounds("chat").top

    @Test fun waitsForEdgeThenFollowsAndReversesWithoutChangingMeasurement() {
        val state = open()
        val wrapper = bounds("restingWrapper")
        val edge = localEdge(state)
        val chat = compose.onNodeWithTag("chat")
        chat.performTouchInput {
            down(Offset(100f, edge - 180f))
            moveTo(Offset(100f, edge - 80f))
        }
        compose.runOnIdle {
            assertFalse(state.dragging)
            assertEquals(0f, state.progress, 0.5f)
        }
        chat.performTouchInput { moveTo(Offset(100f, edge + 40f)) }
        compose.runOnIdle {
            assertTrue(state.dragging)
            assertEquals(40f, state.progress, 0.5f)
            assertEquals(wrapper.top, state.restingTop, 0.5f)
        }
        assertEquals(wrapper, bounds("restingWrapper"))
        assertEquals(wrapper.top + 40f, bounds("composer").top, 0.5f)
        chat.performTouchInput { moveTo(Offset(100f, edge + 10f)); up() }
        compose.runOnIdle { assertFalse(state.hidden); assertFalse(state.dragging) }
        assertEquals(wrapper, bounds("restingWrapper"))
        assertEquals(wrapper.top, bounds("composer").top, 0.5f)
    }

    @Test fun verticallyDominantDragAllowsSidewaysDrift() {
        val state = open()
        val edge = localEdge(state)
        val chat = compose.onNodeWithTag("chat")
        chat.performTouchInput {
            down(Offset(100f, edge - 180f))
            // Both axes exceed touch slop in the first event, but intent is vertical.
            moveTo(Offset(140f, edge - 80f))
            moveTo(Offset(150f, edge + 40f))
        }
        compose.runOnIdle {
            assertTrue(state.dragging)
            assertEquals(40f, state.progress, 0.5f)
        }
        chat.performTouchInput { up() }
    }

    @Test fun horizontalIntentDoesNotHideComposer() {
        val state = open()
        val edge = localEdge(state)
        compose.onNodeWithTag("chat").performTouchInput {
            down(Offset(100f, edge - 180f))
            moveTo(Offset(200f, edge - 140f))
            moveTo(Offset(200f, edge + 80f))
            up()
        }
        compose.runOnIdle { assertFalse(state.hidden); assertFalse(state.dragging) }
    }

    @Test fun releasesBeyondHalfHiddenAndScrollingBothDirectionsKeepsItHidden() {
        val state = open()
        val edge = localEdge(state)
        val distance = state.travel * 0.75f
        val chat = compose.onNodeWithTag("chat")
        chat.performTouchInput {
            down(Offset(100f, edge - 160f))
            moveTo(Offset(100f, edge - 60f))
            moveTo(Offset(100f, edge + distance))
            up()
        }
        compose.runOnIdle { assertTrue(state.hidden); assertFalse(state.dragging) }
        chat.performTouchInput {
            down(Offset(100f, edge - 60f))
            moveTo(Offset(100f, edge - 140f))
        }
        chat.performTouchInput { up() }
        compose.runOnIdle { assertTrue(state.hidden) }
        chat.performTouchInput {
            down(Offset(100f, edge - 140f))
            moveTo(Offset(100f, edge - 60f))
            up()
        }
        compose.runOnIdle { assertTrue(state.hidden) }
    }

    @Test fun ignoresTouchesStartingInsideComposerAndReadsUpdatedGate() {
        val state = open()
        val edge = localEdge(state)
        val chat = compose.onNodeWithTag("chat")
        chat.performTouchInput {
            down(Offset(100f, edge + 5f))
            moveTo(Offset(100f, edge + 80f))
            up()
        }
        compose.runOnIdle { assertFalse(state.hidden); assertFalse(state.dragging); state.allowed = false }
        chat.performTouchInput {
            down(Offset(100f, edge - 160f))
            moveTo(Offset(100f, edge - 60f))
            moveTo(Offset(100f, edge + 80f))
            up()
        }
        compose.runOnIdle { assertFalse(state.hidden); assertFalse(state.dragging) }
    }
}
