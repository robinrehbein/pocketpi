package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

private val disabled = MutableStateFlow(false)
private val noHistory = MutableStateFlow(PromptHistory())

/**
 * The suggested next prompt for the open chat, or null. The composer shows it as the empty field's
 * grey placeholder; the button at the end of the field or Tab takes it. A rule's text follows the
 * language of the user's recent prompts, falling back to the app's language, since the suggestion
 * is sent to pi as typed. The Mac's own suggestion for an idle session ([RemoteState.suggestion])
 * comes first; it is untrusted plain text that reaches pi only when the user takes it and sends.
 */
/**
 * The Mac's suggestion while it applies: the session is idle with no question open and no plain
 * send of the phone's own is queued, in flight or waiting for the timeline. Until the host's `running`
 * status or its new suggestion arrives, the old suggestion would otherwise come back as the
 * placeholder right after it was sent, ready to be sent twice.
 */
internal fun hostSuggestion(state: RemoteState): String? {
    val inFlight = state.sending ||
        state.pendingSend?.state.let {
            it == PendingSendState.QUEUED || it == PendingSendState.SENDING || it == PendingSendState.CONFIRMED
        }
    return state.suggestion?.takeIf { !inFlight && state.status == "idle" && state.questions.isEmpty() }
}

@Composable
internal fun rememberPromptPrediction(state: RemoteState): String? {
    val predictions = LocalPromptPredictions.current
    val enabled by (predictions?.enabled ?: disabled).collectAsState()
    val history by (predictions?.history ?: noHistory).collectAsState()
    val route = state.selection.routeId
    val project = state.selection.projectId
    val session = state.selection.sessionId
    val active = enabled && state.connected && route != null && session != null
    val hostSuggestion = if (active) hostSuggestion(state) else null
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
    val language = remember(state.messages) { conversationLanguage(state.messages) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val predicted = when (prediction) {
        null -> null
        is PromptPrediction.Rule ->
            remember(prediction.rule, language, configuration) {
                val resources =
                    if (language == null) context.resources
                    else
                        context.createConfigurationContext(
                            Configuration(configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                        ).resources
                resources.getString(prediction.rule.text)
            }
        is PromptPrediction.History -> prediction.text
    }
    return hostSuggestion ?: predicted
}
