package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun LoadingControlPill(width: Dp) {
    Surface(
        modifier = Modifier.width(width).height(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionControls(
    state: RemoteState,
    refreshConfiguration: () -> Unit,
    refreshContextUsage: () -> Unit,
    refreshAdvisor: () -> Unit,
    setAdvisor: (String?, String?, String?) -> Unit,
    setModel: (String, String) -> Unit,
    setThinking: (String) -> Unit,
    refreshCommands: () -> Unit,
    selectCommand: (RemoteCommand) -> Unit,
) {
    var picker by remember { mutableStateOf<String?>(null) }
    val pickerSheet = rememberModalBottomSheetState()
    val pickerScope = rememberCoroutineScope()
    val closePicker = {
        pickerScope.launch { pickerSheet.hide() }.invokeOnCompletion { picker = null }
    }
    var showContext by remember { mutableStateOf(false) }
    var showAdvisor by remember { mutableStateOf(false) }
    var advisorChoice by remember(state.selection.sessionId) { mutableStateOf<AdvisorChoice?>(null) }
    var advisorLevel by remember(state.selection.sessionId) { mutableStateOf("high") }
    var advisorDraftDirty by remember(state.selection.sessionId) { mutableStateOf(false) }
    LaunchedEffect(state.selection.sessionId) { showAdvisor = false }
    val sessionAdvisor = state.advisor?.takeIf { it.sessionId == state.selection.sessionId }
    LaunchedEffect(showAdvisor, sessionAdvisor?.model, sessionAdvisor?.reasoning, sessionAdvisor?.choices) {
        if (showAdvisor && sessionAdvisor != null) {
            val refreshedChoice = sessionAdvisor.choices.firstOrNull { choice ->
                choice.provider == advisorChoice?.provider && choice.id == advisorChoice?.id
            }
            val currentChoice = sessionAdvisor.choices.firstOrNull { choice ->
                sessionAdvisor.model == "${choice.provider}/${choice.id}"
            }
            if (!advisorDraftDirty || refreshedChoice == null) {
                advisorChoice = currentChoice
                advisorDraftDirty = false
            } else advisorChoice = refreshedChoice
            advisorChoice?.let { choice ->
                advisorLevel = advisorLevel.takeIf { advisorDraftDirty && it in choice.levels }
                    ?: sessionAdvisor.reasoning.takeIf { choice == currentChoice && it in choice.levels }
                    ?: choice.levels.firstOrNull { it == "high" }
                    ?: choice.levels.first()
            }
        }
    }
    val confirmed = state.configuration
    val thinking = confirmed?.takeIf(::thinkingControlAvailable)
    val available = configurationControlsAvailable(state.capabilities, state.unavailableCapabilities)
    val canChange =
        state.connected &&
            !state.loading &&
            state.status == "idle" &&
            !state.sending &&
            state.answering.isEmpty() &&
            !state.configurationLoading &&
            !state.configurationChanging
    val slash = commandName(state.draft)
    val configurationChipColors =
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    LaunchedEffect(slash != null, state.selection) { if (slash != null) refreshCommands() }
    LaunchedEffect(
        state.selection,
        state.connected,
        state.loading,
        state.status,
        state.configuration?.model?.provider,
        state.configuration?.model?.id,
    ) {
        if (state.connected && state.selection.sessionId != null) refreshContextUsage()
    }
    val usage = state.contextUsage?.takeIf { context ->
        context.sessionId == state.selection.sessionId &&
            (confirmed?.model == null || context.modelProvider == null ||
                (context.modelProvider == confirmed.model.provider &&
                    context.modelId == confirmed.model.id))
    }
    val contextPercent = usage?.percent
    val contextDescription = stringResource(
        R.string.remote_context_indicator,
        if (contextPercent != null) "${contextPercent.toInt()}%" else "?",
    )
    val loadingConfiguration =
        (state.loading && (available || state.capabilities.isEmpty())) ||
            (available && confirmed == null && state.configurationLoading)
    val loadingContext = state.loading || (usage == null && state.contextLoading)
    val advisorAvailable = ADVISOR_CAPABILITY in state.capabilities &&
        ADVISOR_CAPABILITY !in state.unavailableCapabilities
    val loadingAdvisor =
        (state.loading && (advisorAvailable || state.capabilities.isEmpty())) ||
            (advisorAvailable && sessionAdvisor == null && state.advisorLoading)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loadingConfiguration) {
                LoadingControlPill(160.dp)
                LoadingControlPill(96.dp)
            } else if (available) {
                AssistChip(
                    onClick = {
                        picker = "model"
                        refreshConfiguration()
                    },
                    enabled = state.connected && !state.configurationChanging,
                    shape = CircleShape,
                    colors = configurationChipColors,
                    border = null,
                    leadingIcon = {
                        ConfigurationChipIcon(Icons.Outlined.AutoAwesome, state.configurationChanging)
                    },
                    label = {
                        Text(
                            confirmed?.model?.name ?: stringResource(R.string.remote_choose_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                if (thinking != null) {
                    val thinkingLabel = thinkingLevelLabel(thinking.thinkingLevel)
                    val thinkingContentDescription =
                        stringResource(R.string.remote_thinking_level, thinkingLabel)
                    AssistChip(
                        onClick = {
                            picker = "thinking"
                            refreshConfiguration()
                        },
                        enabled = state.connected && !state.configurationChanging,
                        shape = CircleShape,
                        colors = configurationChipColors,
                        border = null,
                        modifier =
                            Modifier.semantics { contentDescription = thinkingContentDescription },
                        leadingIcon = {
                            ConfigurationChipIcon(Icons.Outlined.Psychology, state.configurationChanging)
                        },
                        label = {
                            Text(thinkingLabel)
                        },
                    )
                }
            }
            if (loadingContext) LoadingControlPill(80.dp)
            else AssistChip(
                onClick = {
                    showContext = true
                    refreshContextUsage()
                },
                shape = CircleShape,
                colors = configurationChipColors,
                border = null,
                modifier = Modifier.semantics { contentDescription = contextDescription },
                leadingIcon = {
                    Box(Modifier.size(AssistChipDefaults.IconSize), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { ((contextPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                },
                label = {
                    Text(if (contextPercent != null) "${contextPercent.toInt()}%" else "?")
                },
            )
            if (loadingAdvisor) LoadingControlPill(140.dp)
            else if (advisorAvailable) {
                AssistChip(
                    onClick = {
                        advisorChoice = sessionAdvisor?.choices?.firstOrNull { choice ->
                            sessionAdvisor.model == "${choice.provider}/${choice.id}"
                        }
                        advisorLevel = advisorChoice?.let { choice ->
                            sessionAdvisor?.reasoning?.takeIf { it in choice.levels }
                                ?: choice.levels.firstOrNull { it == "high" }
                                ?: choice.levels.first()
                        } ?: "high"
                        advisorDraftDirty = false
                        showAdvisor = true
                        refreshAdvisor()
                    },
                    enabled = state.connected,
                    shape = CircleShape,
                    colors = configurationChipColors,
                    border = null,
                    leadingIcon = {
                        Icon(Icons.Outlined.SupportAgent, contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    label = {
                        Text(
                            if (sessionAdvisor == null) stringResource(R.string.remote_advisor_title)
                            else if (sessionAdvisor.pending) stringResource(R.string.remote_advisor_busy)
                            else if (sessionAdvisor.error != null) stringResource(R.string.remote_advisor_error)
                            else sessionAdvisor.model?.substringAfterLast('/') ?: stringResource(R.string.remote_advisor_off),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
        if (configurationControlsNoticeVisible(state))
            Text(
                stringResource(R.string.remote_configuration_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        state.commandNotice?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (
            slash != null &&
                COMMANDS_CAPABILITY in state.capabilities &&
                COMMANDS_CAPABILITY !in state.unavailableCapabilities
        ) {
            val suggestions = state.commands.filter { it.name.startsWith(slash, ignoreCase = true) }
            if (state.commandsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (suggestions.isNotEmpty())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(suggestions, key = { it.name }) { command ->
                        ListItem(
                            headlineContent = { Text("/" + command.name) },
                            supportingContent = {
                                command.description?.let {
                                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            modifier = Modifier.clickable { selectCommand(command) },
                        )
                    }
                }
            if (state.commandsTruncated)
                Text(
                    stringResource(R.string.remote_catalog_truncated),
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
    if (showContext) ModalBottomSheet(onDismissRequest = { showContext = false }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.remote_context_title), style = MaterialTheme.typography.titleLarge)
            if (!state.connected || CONTEXT_CAPABILITY !in state.capabilities ||
                CONTEXT_CAPABILITY in state.unavailableCapabilities || usage == null) {
                Text(stringResource(R.string.remote_context_unavailable))
            } else {
                Text(stringResource(R.string.remote_context_window, usage.contextWindow))
                if (usage.usedTokens != null && usage.percent != null) {
                    Text(stringResource(R.string.remote_context_used, usage.usedTokens, usage.percent.toInt()))
                    Text(stringResource(R.string.remote_context_remaining,
                        (usage.contextWindow - usage.usedTokens).coerceAtLeast(0)))
                } else Text(stringResource(R.string.remote_context_unknown))
                usage.totals?.let { UsageSummary(it) }
            }
            Text(
                stringResource(R.string.remote_context_estimate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showAdvisor) AdvisorPickerSheet(
        advisor = sessionAdvisor,
        selectedChoice = advisorChoice,
        selectedLevel = advisorLevel,
        loading = state.advisorLoading || state.advisorChanging,
        canSelect = canChange && !state.advisorChanging && !state.advisorLoading,
        onSelectChoice = { choice ->
            advisorChoice = choice
            advisorLevel = if ("high" in choice.levels) "high" else choice.levels.first()
            advisorDraftDirty = true
        },
        onSelectLevel = { advisorLevel = it; advisorDraftDirty = true },
        onApply = {
            advisorChoice?.let { choice ->
                setAdvisor(choice.provider, choice.id, advisorLevel)
                showAdvisor = false
            }
        },
        onDisable = {
            setAdvisor(null, null, null)
            showAdvisor = false
        },
        onDismiss = { showAdvisor = false },
    )
    if (picker != null && available)
        ModalBottomSheet(onDismissRequest = { picker = null }, sheetState = pickerSheet) {
            val busy = !canChange && state.connected && state.status in setOf("running", "waiting")
            val selectModel = { provider: String, id: String ->
                setModel(provider, id)
                closePicker()
            }
            val selectThinking = { level: String ->
                setThinking(level)
                closePicker()
            }
            Text(
                stringResource(
                    if (picker == "model") R.string.remote_choose_model
                    else R.string.remote_choose_thinking
                ),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (busy)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.remote_busy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (state.configurationLoading || state.configurationChanging)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                if (picker == "model") {
                    items(confirmed?.models ?: emptyList(), key = { it.provider + ":" + it.id }) {
                        model ->
                        val chosen =
                            confirmed?.model?.let {
                                it.provider == model.provider && it.id == model.id
                            } == true
                        PickerRow(
                            chosen = chosen,
                            enabled = canChange,
                            onSelect = { selectModel(model.provider, model.id) },
                        ) {
                            Column {
                                Text(model.name)
                                Text(
                                    model.provider + " · " + model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (confirmed?.modelsTruncated == true)
                        item {
                            Text(
                                stringResource(R.string.remote_catalog_truncated),
                                Modifier.padding(24.dp),
                            )
                        }
                } else
                    items(confirmed?.thinkingLevels ?: emptyList()) { level ->
                        PickerRow(
                            chosen = confirmed?.thinkingLevel == level,
                            enabled = canChange,
                            onSelect = { selectThinking(level) },
                        ) {
                            Text(thinkingLevelLabel(level))
                        }
                    }
            }
        }
}

@Composable
private fun ConfigurationChipIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean) {
    if (busy)
        CircularProgressIndicator(
            Modifier.size(AssistChipDefaults.IconSize),
            strokeWidth = 2.dp,
        )
    else Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
}

@Composable
private fun PickerRow(
    chosen: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    val contentColor =
        if (chosen) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (chosen)
                    Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp),
                    )
                else Modifier
            )
            .selectable(
                selected = chosen,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, enabled = enabled, onClick = null)
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

@Composable
internal fun thinkingLevelLabel(level: String): String =
    when (level) {
        "off" -> stringResource(R.string.remote_thinking_off)
        "minimal" -> stringResource(R.string.remote_thinking_minimal)
        "low" -> stringResource(R.string.remote_thinking_low)
        "medium" -> stringResource(R.string.remote_thinking_medium)
        "high" -> stringResource(R.string.remote_thinking_high)
        "xhigh" -> stringResource(R.string.remote_thinking_xhigh)
        else -> level
    }
