package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun QuestionnaireResultCard(
    presentation: QuestionnairePresentation,
    modifier: Modifier = Modifier,
    standalone: Boolean = false,
) {
    Surface(
        modifier.fillMaxWidth().testTag("questionnaireCard"),
        shape = RoundedCornerShape(PAGE_CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (standalone) stringResource(R.string.remote_questionnaire_earlier_title) else "AskUserQuestion",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(when (presentation.state) {
                        QuestionnaireState.PENDING -> R.string.remote_questionnaire_pending
                        QuestionnaireState.ANSWERED -> R.string.remote_questionnaire_answered
                        QuestionnaireState.CANCELLED -> R.string.remote_questionnaire_cancelled
                    }),
                    Modifier.testTag("questionnaireStatus"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            presentation.questions.forEach { question ->
                key(question.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(question.prompt, Modifier.testTag("questionnairePrompt-${question.id}"), style = MaterialTheme.typography.bodyMedium)
                        if (presentation.state == QuestionnaireState.ANSWERED) {
                            val answers = when {
                                question.skipped -> listOf(stringResource(R.string.remote_questionnaire_skipped))
                                question.answers.isEmpty() -> listOf(stringResource(R.string.remote_questionnaire_missing))
                                else -> question.answers
                            }
                            val long = answers.any { it.length > 160 }
                            var expanded by rememberSaveable(presentation.toolCallId, question.id) { mutableStateOf(false) }
                            SelectionContainer {
                                Column(Modifier.testTag("questionnaireAnswer-${question.id}")) {
                                    answers.forEach { answer ->
                                        Text(
                                            if (answers.size > 1) "• $answer" else answer,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = if (answer.length > 160 && !expanded) 4 else Int.MAX_VALUE,
                                            overflow = if (answer.length > 160 && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                                        )
                                    }
                                }
                            }
                            if (long) {
                                val label = stringResource(if (expanded) R.string.remote_questionnaire_show_less else R.string.remote_questionnaire_show_more)
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.testTag("questionnaireToggle-${question.id}")
                                        .semantics { stateDescription = label },
                                ) { Text(label) }
                            }
                            question.note?.takeIf { it.isNotBlank() }?.let { note ->
                                SelectionContainer {
                                    Text(note, Modifier.testTag("questionnaireNote-${question.id}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
