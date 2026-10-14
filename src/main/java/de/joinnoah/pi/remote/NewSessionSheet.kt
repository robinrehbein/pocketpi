package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the new-session sheet asks for: the model (null keeps the host's) and the first prompt (blank sends none). */
internal data class NewSessionChoice(val model: RemoteModel?, val prompt: String)

/** The repository calls that follow a new session's creation, in order; see [finishNewSession]. */
internal interface NewSessionSteps {
    /** The selection the repository holds now. */
    fun selected(): RemoteSelection

    /** Waits until the models of [selection]'s chat are known; false when they do not come. */
    suspend fun awaitModels(selection: RemoteSelection): Boolean

    /** Sets the model and waits until the chat reports it; false when the change was dropped or failed. */
    suspend fun setModel(model: RemoteModel): Boolean

    /** The text the chat [selection] holds as its draft now. */
    fun draftText(selection: RemoteSelection): String

    fun draft(selection: RemoteSelection, text: String)

    fun send()
}

/** How long a new chat may take to report its models before the model is left alone. */
internal const val NEW_SESSION_MODELS_WAIT_MILLIS = 15_000L

/** How long the host may take to confirm the chosen model before the prompt stays in the draft. */
internal const val NEW_SESSION_MODEL_CHANGE_WAIT_MILLIS = 15_000L

/**
 * Applies [choice] to the session just created and opened as [created], by sequencing the existing
 * commands: set the chosen model once the new chat reports its models and the host confirms it,
 * then send the first prompt. The caller has navigated to the chat already, so this runs behind a
 * visible, usable chat. Every step runs only while the new chat is still the selected one, so a
 * navigation that lands elsewhere never gets the model or the prompt. When the model cannot be set,
 * the prompt stays in the draft instead of going out with another model. A message the user typed
 * meanwhile is kept below the prompt and is not sent with it. A create that opened no session
 * ([created] without a session id) does nothing.
 */
internal suspend fun finishNewSession(
    created: RemoteSelection,
    choice: NewSessionChoice,
    steps: NewSessionSteps,
) {
    // A create that failed opened no chat; the repository reported the error already.
    if (created.sessionId == null) return
    val model = choice.model
    var modelReady = true
    if (model != null) {
        modelReady = steps.selected() == created && steps.awaitModels(created) &&
            steps.selected() == created && steps.setModel(model)
    }
    if (choice.prompt.isBlank() || steps.selected() != created) return
    val typed = steps.draftText(created)
    steps.draft(created, if (typed.isBlank()) choice.prompt else "${choice.prompt}\n\n$typed")
    if (modelReady && typed.isBlank() && steps.selected() == created) steps.send()
}

/** Whether a first message can go into a new chat: not a command (none is loaded yet) and within the draft limit. */
internal enum class NewSessionPromptIssue { COMMAND, TOO_LONG }

internal fun newSessionPromptIssue(prompt: String): NewSessionPromptIssue? =
    when {
        prompt.trimStart().startsWith("/") -> NewSessionPromptIssue.COMMAND
        prompt.length * 3 > MAX_DRAFT_BYTES && prompt.encodeToByteArray().size > MAX_DRAFT_BYTES ->
            NewSessionPromptIssue.TOO_LONG
        else -> null
    }

/**
 * The new-session sheet: a model to start with (or the host's default) and an optional first
 * message. [onStart] runs with the choice; the caller creates the session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSessionSheet(
    projectName: String?,
    models: List<RemoteModel>,
    onStart: (NewSessionChoice) -> Unit,
    onDismiss: () -> Unit,
    /** True while another session is being created or forked: Start waits for it. */
    busy: Boolean = false,
) {
    // The model's "provider/id", or null for the host's default; saved so a rotation keeps the choice.
    var modelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val issue = newSessionPromptIssue(prompt)
    val chosen = models.firstOrNull { "${it.provider}/${it.id}" == modelKey }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("newSessionSheet")) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.remote_new_session),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            projectName?.let {
                Text(
                    stringResource(R.string.remote_new_session_project, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (models.isNotEmpty()) {
                Text(stringResource(R.string.remote_new_session_model), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().selectableGroup().testTag("newSessionModels")) {
                    ModelChoiceRow(
                        label = stringResource(R.string.remote_new_session_model_default),
                        selected = chosen == null,
                    ) { modelKey = null }
                    models.forEach { model ->
                        ModelChoiceRow(label = model.name.ifBlank { model.id }, selected = chosen == model) {
                            modelKey = "${model.provider}/${model.id}"
                        }
                    }
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth().testTag("newSessionPrompt"),
                label = { Text(stringResource(R.string.remote_new_session_prompt)) },
                isError = issue != null,
                supportingText = {
                    when (issue) {
                        NewSessionPromptIssue.COMMAND -> Text(stringResource(R.string.remote_new_session_prompt_command))
                        NewSessionPromptIssue.TOO_LONG -> Text(stringResource(R.string.remote_new_session_prompt_long))
                        null -> {}
                    }
                },
                minLines = 2,
                maxLines = 6,
            )
            Button(
                onClick = { onStart(NewSessionChoice(chosen, prompt.trim())) },
                enabled = issue == null && !busy,
                modifier = Modifier.align(Alignment.End).testTag("newSessionStart"),
            ) {
                Text(
                    stringResource(
                        if (prompt.isBlank()) R.string.remote_new_session_start
                        else R.string.remote_new_session_start_send
                    )
                )
            }
        }
    }
}

@Composable
private fun ModelChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Whether the new chat [selection] reports its models within [NEW_SESSION_MODELS_WAIT_MILLIS]. */
internal suspend fun awaitConfiguredModels(
    state: Flow<RemoteState>,
    selection: RemoteSelection,
): Boolean =
    withTimeoutOrNull(NEW_SESSION_MODELS_WAIT_MILLIS) {
        state.first { it.selection == selection && it.configuration != null && !it.configurationLoading }
    } != null

/**
 * Whether [state] holds [model] as the chat [selection]'s model once the host settled: a change the
 * repository dropped never starts (the model stays as it was), one it started ends with the model
 * confirmed or, on a failure, unchanged. Gives up after [NEW_SESSION_MODEL_CHANGE_WAIT_MILLIS].
 */
internal suspend fun awaitModelApplied(
    state: StateFlow<RemoteState>,
    selection: RemoteSelection,
    model: RemoteModel,
): Boolean {
    fun RemoteState.holds() =
        this.selection == selection && configuration?.model.let { it?.provider == model.provider && it.id == model.id }
    if (!state.value.configurationChanging) return state.value.holds()
    val settled =
        withTimeoutOrNull(NEW_SESSION_MODEL_CHANGE_WAIT_MILLIS) {
            state.first { it.selection != selection || !it.configurationChanging }
        } ?: return false
    return settled.holds()
}
