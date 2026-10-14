package de.joinnoah.pi.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow

private val disabled = MutableStateFlow(false)
private val noHistory = MutableStateFlow(PromptHistory())

/**
 * The suggested next prompt for the open chat, or null. The composer shows it as a chip above the
 * field, where a tap or Tab takes it.
 */
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
