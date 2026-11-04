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
 * A running state confirmed on the live connection draws the animated session glyph instead of
 * the play mark (see [chatStatusLive]); a last-known one keeps the static play mark.
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

/**
 * The state the chat header shows: an open question turns a running session into a waiting one,
 * so the header never suggests work while pi needs an answer.
 */
internal fun chatHeaderState(state: RemoteState): RemoteState =
    if (state.status == "running" && state.questions.isNotEmpty()) state.copy(status = "waiting") else state

/**
 * Whether a RUNNING mark may animate: exactly [sessionActivityAllowed] for the chat actually
 * rendered ([destination]), so a status read on the current connection for that session, not kept
 * from a lost one, not an open in progress, not a cached preview and with no open question.
 */
internal fun chatStatusLive(
    state: RemoteState,
    destination: RemoteSelection?,
    now: Long = android.os.SystemClock.elapsedRealtime(),
): Boolean = chatStatusPhase(state, destination, now) != null ||
    (state.executionActivity.negotiated.not() &&
        chatStatusShape(state) == ChatStatusShape.RUNNING && sessionActivityAllowed(state, destination))

/**
 * The typed phase the header's RUNNING mark animates in, on a connection that negotiated
 * `session_activity.v1`: only a fresh typed view with the RUNNING shape and the legacy gates; the
 * shape itself (offline, waiting, ready) still follows the session status. Null otherwise, also
 * for a legacy connection, whose mark keeps animating the plain snake.
 */
internal fun chatStatusPhase(state: RemoteState, destination: RemoteSelection?, now: Long): ActivitySnapshot.Active? {
    if (chatStatusShape(state) != ChatStatusShape.RUNNING || !sessionActivityAllowed(state, destination)) return null
    return (typedSessionActivity(state, destination?.sessionId, now) as? TypedActivity.Live)?.snapshot
}

/** Whether a status mark animates; tests read it. */
internal val ChatStatusLiveKey = SemanticsPropertyKey<Boolean>("ChatStatusLive")
private var SemanticsPropertyReceiver.chatStatusLive by ChatStatusLiveKey

/** The shape a status mark draws; tests read it, TalkBack reads the label instead. */
internal val ChatStatusShapeKey = SemanticsPropertyKey<ChatStatusShape>("ChatStatusShape")
private var SemanticsPropertyReceiver.chatStatusShape by ChatStatusShapeKey

/** The size the header reserves for the mark. */
internal val CHAT_STATUS_MARK_SIZE = 10.dp

/**
 * Draws [shape] in [color]; [label] is the state in words, announced politely when it changes.
 * [live] (only honoured for RUNNING) swaps the play mark for the animated session glyph.
 */
@Composable
internal fun ChatStatusMark(
    shape: ChatStatusShape,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    /** The typed phase of a live mark: its glyph and colour replace the plain snake. */
    phase: ActivitySnapshot.Active? = null,
) {
    val animated = live && shape == ChatStatusShape.RUNNING
    val markModifier =
        modifier.size(CHAT_STATUS_MARK_SIZE).semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
            chatStatusShape = shape
            chatStatusLive = animated
        }
    if (animated) {
        // The size sits in markModifier, so the glyph never falls back to its own default size;
        // the label lives there too, so the glyph gets no description of its own.
        if (phase != null)
            StatusGlyph(activityGlyphKind(phase.phase), activityPhaseColor(phase.phase, phase.thinkingLevel), markModifier)
        else StatusGlyph(StatusGlyphKind.SESSION_RUNNING, color, markModifier)
        return
    }
    Canvas(markModifier) {
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
