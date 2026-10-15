package de.joinnoah.pi.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The chat header's status mark. Each state has a shape of its own, so the state never rests on
 * colour alone: a dot when ready, a play mark while pi works, a diamond while it waits for an
 * answer, an empty ring when the state is unknown and a struck-through ring when offline.
 */
internal enum class ChatStatusShape { READY, RUNNING, WAITING, UNKNOWN, OFFLINE }

/** The shape of the state [chatStatusLabel] names. */
internal fun chatStatusShape(state: RemoteState): ChatStatusShape =
    when {
        !state.connected || state.status == "offline" -> ChatStatusShape.OFFLINE
        state.status == "running" -> ChatStatusShape.RUNNING
        state.status == "waiting" -> ChatStatusShape.WAITING
        state.status == "idle" -> ChatStatusShape.READY
        else -> ChatStatusShape.UNKNOWN
    }

/** The shape a status mark draws; tests read it, TalkBack reads the label instead. */
internal val ChatStatusShapeKey = SemanticsPropertyKey<ChatStatusShape>("ChatStatusShape")
private var SemanticsPropertyReceiver.chatStatusShape by ChatStatusShapeKey

/** The size the header reserves for the mark. */
internal val CHAT_STATUS_MARK_SIZE = 10.dp

/** Draws [shape] in [color]; [label] is the state in words, announced politely when it changes. */
@Composable
internal fun ChatStatusMark(shape: ChatStatusShape, color: Color, label: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier.size(CHAT_STATUS_MARK_SIZE).semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
            chatStatusShape = shape
        }
    ) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        when (shape) {
            ChatStatusShape.READY -> drawCircle(color, radius = w * 0.4f)
            ChatStatusShape.RUNNING ->
                drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, 0f)
                        lineTo(w, h / 2)
                        lineTo(w * 0.15f, h)
                        close()
                    },
                    color,
                )
            ChatStatusShape.WAITING ->
                drawPath(
                    Path().apply {
                        moveTo(w / 2, 0f)
                        lineTo(w, h / 2)
                        lineTo(w / 2, h)
                        lineTo(0f, h / 2)
                        close()
                    },
                    color,
                )
            ChatStatusShape.UNKNOWN -> drawCircle(color, radius = (w - stroke) / 2, style = Stroke(stroke))
            ChatStatusShape.OFFLINE -> {
                drawCircle(color, radius = (w - stroke) / 2, style = Stroke(stroke))
                drawLine(color, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.15f, h * 0.85f), strokeWidth = stroke)
            }
        }
    }
}
