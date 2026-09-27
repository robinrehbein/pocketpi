package de.joinnoah.pi.remote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.MutableStateFlow

private val disabled = MutableStateFlow(false)
private val noHistory = MutableStateFlow(PromptHistory())

/** The suggested next prompt for the open chat, or null. */
@Composable
internal fun rememberPromptPrediction(state: RemoteState): String? {
    val predictions = LocalPromptPredictions.current
    val enabled by (predictions?.enabled ?: disabled).collectAsState()
    val history by (predictions?.history ?: noHistory).collectAsState()
    val route = state.selection.routeId
    val project = state.selection.projectId
    val session = state.selection.sessionId
    val active = enabled && state.connected && route != null && session != null
    val prediction =
        remember(active, state.messages, state.status, state.questions.isEmpty(), history, route, project, session) {
            if (!active) null
            else
                predictPrompt(
                    state.messages,
                    state.status,
                    state.questions.isNotEmpty(),
                    history.planApprovals[sessionKey(route!!, session!!)],
                    project?.let { history.frequent(projectKey(route, it)) }.orEmpty(),
                )
        }
    return when (prediction) {
        null -> null
        is PromptPrediction.Rule -> stringResource(prediction.rule.text)
        is PromptPrediction.History -> prediction.text
    }
}

/**
 * Double tap on the empty field accepts [prediction]. The accepting tap is consumed, so the text
 * field never sees it as a second tap. TalkBack users get the same through a custom action, since
 * there a double tap already activates the field, and hear the suggestion as the field's state.
 *
 * The gesture restarts only when the prediction changes, so [onAccept] must read the latest state
 * itself (for example through `rememberUpdatedState`).
 */
internal fun Modifier.promptPrediction(
    prediction: String?,
    label: String,
    announcement: String,
    onAccept: () -> Unit,
): Modifier {
    if (prediction == null) return this
    return this.pointerInput(prediction) {
            var lastTap: Pair<Long, Offset>? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val previous = lastTap
                val second =
                    previous != null &&
                        down.uptimeMillis - previous.first <= viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - previous.second).getDistance() <= viewConfiguration.touchSlop * 4
                if (second) down.consume()
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                if (up == null) {
                    lastTap = null
                    return@awaitEachGesture
                }
                if (second) {
                    up.consume()
                    lastTap = null
                    onAccept()
                } else {
                    lastTap = up.uptimeMillis to up.position
                }
            }
        }
        .semantics {
            stateDescription = announcement
            customActions = listOf(CustomAccessibilityAction(label) { onAccept(); true })
        }
}
