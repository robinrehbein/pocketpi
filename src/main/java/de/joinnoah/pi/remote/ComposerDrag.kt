package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs

private class ComposerDragObserver(
    val allowed: () -> Boolean,
    val restingTop: () -> Float,
    val travel: () -> Int,
    val hidden: () -> Boolean,
    val dragging: () -> Boolean,
    val onDrag: (Float) -> Unit,
    val onReveal: () -> Unit,
    val onSettle: () -> Unit,
)

/**
 * Observes, never consumes, chat touches. The measured origin and pointer node share coordinates;
 * [restingTop] must come from an untranslated parent in root coordinates.
 *
 * Kept outside RemoteScreen's content lambda: large inline lambdas have caused R8/ART VerifyErrors.
 * Updated callbacks let a gesture see current gates without restarting or stealing list scrolling.
 */
@Composable
internal fun Modifier.observeComposerDrag(
    key: Any?,
    allowed: () -> Boolean,
    restingTop: () -> Float,
    travel: () -> Int,
    hidden: () -> Boolean,
    dragging: () -> Boolean,
    onDrag: (Float) -> Unit,
    onReveal: () -> Unit,
    onSettle: () -> Unit,
): Modifier {
    val current by rememberUpdatedState(
        ComposerDragObserver(allowed, restingTop, travel, hidden, dragging, onDrag, onReveal, onSettle)
    )
    var rootTop by remember { mutableStateOf(0f) }
    return onGloballyPositioned { rootTop = it.positionInRoot().y }
        .pointerInput(key) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val first = awaitPointerEvent(PointerEventPass.Initial)
                        val down = first.changes.singleOrNull()?.takeIf { it.pressed && !it.previousPressed } ?: continue
                        val start = down.position
                        val startedAt = down.uptimeMillis
                        val eligible = current.allowed() && start.y + rootTop <
                            current.restingTop() + (if (current.hidden()) current.travel() else 0)
                        val startedHidden = current.hidden()
                        var tracking = false
                        var rejected = !eligible
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.size != 1) {
                                    rejected = true
                                    if (current.dragging()) current.onReveal()
                                }
                                if (!change.pressed) {
                                    if (!rejected && current.dragging()) current.onSettle()
                                    break
                                }
                                val delta = change.position - start
                                if (!tracking && !rejected) {
                                    if (change.uptimeMillis - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                        rejected = true
                                    } else if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) {
                                        // Real fingers drift sideways too; only reject horizontal intent.
                                        tracking = true
                                    } else if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)) {
                                        rejected = true
                                    }
                                }
                                if (tracking && !rejected && current.allowed()) {
                                    val progress = composerDragProgress(change.position.y + rootTop, current.restingTop(), current.travel().toFloat())
                                    if (!startedHidden && (current.dragging() || (delta.y > 0 && progress > 0f))) {
                                        current.onDrag(progress)
                                    }
                                } else if (rejected && current.dragging()) {
                                    current.onReveal()
                                }
                            }
                        } finally {
                            // Cancellation/lost pointers must not leave partially hidden controls.
                            if (current.dragging()) current.onReveal()
                        }
                    }
                }
            } finally {
                // Includes modifier/key cancellation, not just normal pointer-up.
                if (current.dragging()) current.onReveal()
            }
        }
}

/** Interactive progress uses the resting (untranslated) edge, in the finger's coordinate space. */
internal fun composerDragProgress(fingerY: Float, restingTop: Float, travel: Float): Float =
    (fingerY - restingTop).coerceIn(0f, travel.coerceAtLeast(0f))

internal fun composerShouldSettleHidden(progress: Float, travel: Float): Boolean =
    travel > 0f && progress > travel / 2f
