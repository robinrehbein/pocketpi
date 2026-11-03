package de.joinnoah.pi.remote

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /** Opens the host's providers, where a missing advisor model can be signed in to; null hides the row. */
    onManageProviders: (() -> Unit)? = null,
) {
    val currentChoice = advisor?.choices?.firstOrNull { choice ->
        advisor.model == "${choice.provider}/${choice.id}"
    }
    val groups = remember(advisor?.choices) { groupAdvisorChoices(advisor?.choices.orEmpty()) }
    val currentLabel = stringResource(R.string.remote_advisor_current_label)
    val maxHeight = windowHeightFraction(0.84f)

    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("advisorPickerList"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            item { SheetHeader(stringResource(R.string.remote_advisor_title), padding = PaddingValues(start = 4.dp)) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (advisor == null) {
                if (!loading) {
                    item {
                        SheetSection(Modifier.testTag("advisorUnavailable")) {
                            Text(
                                unavailableReason ?: stringResource(R.string.remote_advisor_unavailable),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (onRetry != null) item {
                        SheetSecondaryButton(onClick = onRetry) {
                            Text(stringResource(R.string.remote_advisor_retry))
                        }
                    }
                }
            } else {
                item {
                    SheetCard {
                        Column(
                            Modifier.padding(18.dp),
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
                  // A provider's models read as one card of rows, like the other lists in sheets.
                  item(key = "advisorGroup/" + group.provider) {
                   Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                   group.choices.forEachIndexed { index, choice ->
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
                        // The choice reads from its tint, as the other sheets' choices do, not from a border.
                        shape = groupedRowShape(index == 0, index == group.choices.lastIndex, corner = 20.dp),
                        color = sheetOptionColor(selected),
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
                  }
                }
                item {
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
                      val levels = selectedChoice.levels
                      // A few levels fit one pill switch, as the other level choices in the app;
                      // more wrap as pills, so a narrow phone never clips their labels.
                      if (levels.size > 3)
                          FlowRow(
                              horizontalArrangement = Arrangement.spacedBy(8.dp),
                              verticalArrangement = Arrangement.spacedBy(8.dp),
                          ) {
                              levels.forEach { level ->
                                  FilterChip(
                                      selected = selectedLevel == level,
                                      onClick = { onSelectLevel(level) },
                                      label = { Text(level) },
                                      enabled = canSelect,
                                      shape = CircleShape,
                                      border = null,
                                      colors = FilterChipDefaults.filterChipColors(
                                          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                          selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                      ),
                                  )
                              }
                          }
                      else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                          levels.forEachIndexed { index, level ->
                              SegmentedButton(
                                  selected = selectedLevel == level,
                                  onClick = { onSelectLevel(level) },
                                  shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                                  enabled = canSelect,
                                  icon = {},
                              ) {
                                  Text(level, maxLines = 1, overflow = TextOverflow.Ellipsis)
                              }
                          }
                      }
                      SheetPrimaryButton(onClick = onApply, enabled = canSelect) {
                          Text(stringResource(R.string.remote_advisor_apply))
                      }
                  }
                  if (advisor.enabled) {
                      SheetSecondaryButton(onClick = onDisable, enabled = canSelect) {
                          Text(stringResource(R.string.remote_advisor_disable))
                      }
                  }
              }
          }
          if (onManageProviders != null)
              ManageProvidersRow(onManageProviders)
        }
    }
}
