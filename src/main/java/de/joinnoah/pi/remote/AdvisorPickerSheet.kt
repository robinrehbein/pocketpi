package de.joinnoah.pi.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvisorPickerSheet(
    advisor: SessionAdvisor?,
    selectedChoice: AdvisorChoice?,
    selectedLevel: String,
    loading: Boolean,
    canSelect: Boolean,
    onSelectChoice: (AdvisorChoice) -> Unit,
    onSelectLevel: (String) -> Unit,
    onApply: () -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
    unavailableReason: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val currentChoice = advisor?.choices?.firstOrNull { choice ->
        advisor.model == "${choice.provider}/${choice.id}"
    }
    val groups = remember(advisor?.choices) { groupAdvisorChoices(advisor?.choices.orEmpty()) }
    val currentLabel = stringResource(R.string.remote_advisor_current_label)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("advisorPickerList"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item {
                Text(
                    stringResource(R.string.remote_advisor_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (advisor == null) {
                if (!loading) {
                    item { Text(unavailableReason ?: stringResource(R.string.remote_advisor_unavailable)) }
                    if (onRetry != null) item {
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.remote_advisor_retry))
                        }
                    }
                }
            } else {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.remote_advisor_current_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (advisor.enabled)
                                    currentChoice?.name ?: advisor.model?.substringAfterLast('/')
                                        ?: stringResource(R.string.remote_advisor_title)
                                else stringResource(R.string.remote_advisor_off),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (advisor.enabled)
                                Text(
                                    stringResource(R.string.remote_advisor_thinking) + ": " + advisor.reasoning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            Text(
                                stringResource(
                                    R.string.remote_advisor_remaining,
                                    advisor.remainingAttempts,
                                    advisor.maxAttempts,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                advisor.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Text(
                        stringResource(R.string.remote_advisor_choose),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                groups.forEach { group ->
                  if (group.displayName.isNotBlank()) item(key = "advisorProvider/" + group.provider) {
                    Text(
                        group.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 4.dp)
                            .testTag("advisorProviderHeader-" + group.provider)
                            .semantics { heading() },
                    )
                  }
                  items(group.choices, key = { it.provider + ":" + it.id }) { choice ->
                    val selected = selectedChoice == choice
                    val current = advisor.enabled && currentChoice == choice
                    val providerLabel = advisorRowLabel(choice, group)
                    val modelLabel = advisorRowModelName(choice)
                    val description = listOfNotNull(
                        group.displayName.takeIf { it.isNotBlank() }, providerLabel, modelLabel,
                    ).joinToString(", ")
                    Surface(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            enabled = canSelect,
                            role = Role.RadioButton,
                            onClick = { onSelectChoice(choice) },
                        ).semantics {
                            contentDescription = description
                            if (current) stateDescription = currentLabel
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            else null,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (providerLabel != null)
                                    Text(
                                        providerLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                Text(
                                    modelLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (current)
                                    Text(
                                        stringResource(R.string.remote_advisor_current_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                            }
                            Spacer(Modifier.width(8.dp))
                            RadioButton(selected = selected, onClick = null, enabled = canSelect)
                        }
                    }
                  }
                }
                item {
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    Text(
                        stringResource(R.string.remote_advisor_context_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
          }
          if (advisor != null) {
              Column(
                  Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                  if (selectedChoice != null) {
                      Text(stringResource(R.string.remote_advisor_thinking),
                          style = MaterialTheme.typography.titleMedium)
                      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                          selectedChoice.levels.forEach { level ->
                              FilterChip(
                                  selected = selectedLevel == level,
                                  onClick = { onSelectLevel(level) },
                                  label = { Text(level) },
                                  enabled = canSelect,
                              )
                          }
                      }
                      Button(onClick = onApply, enabled = canSelect,
                          modifier = Modifier.fillMaxWidth()) {
                          Text(stringResource(R.string.remote_advisor_apply))
                      }
                  }
                  if (advisor.enabled) {
                      OutlinedButton(onClick = onDisable, enabled = canSelect,
                          modifier = Modifier.fillMaxWidth()) {
                          Text(stringResource(R.string.remote_advisor_disable))
                      }
                  }
              }
          }
        }
    }
}
