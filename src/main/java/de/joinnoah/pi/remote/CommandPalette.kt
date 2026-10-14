package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** The palette's labels in the app's language; the rows of [paletteItems] carry them. */
@Composable
internal fun rememberPaletteLabels(): PaletteLabels {
    val resources = LocalResources.current
    val project = stringResource(R.string.remote_palette_project)
    val host = stringResource(R.string.remote_palette_host)
    return remember(resources, project, host) {
        PaletteLabels(
            command = { command ->
                when (command) {
                    is ChatCommand.Action -> resources.getString(command.action.label())
                    is ChatCommand.Control -> resources.getString(command.control.labelRes())
                    is ChatCommand.Local -> resources.getString(command.command.description)
                    is ChatCommand.Host -> command.command.description ?: command.command.name
                    is ChatCommand.File -> command.path
                }
            },
            status = { status -> resources.getString(statusRes(status)) },
            project = project,
            host = host,
        )
    }
}

@StringRes
private fun SessionControl.labelRes(): Int =
    when (this) {
        SessionControl.MODEL -> R.string.remote_palette_model
        SessionControl.THINKING -> R.string.remote_palette_thinking
        SessionControl.ADVISOR -> R.string.remote_advisor_title
        SessionControl.COMPACT -> R.string.remote_palette_compact
    }

@StringRes
private fun statusRes(status: String): Int =
    when (status) {
        "waiting" -> R.string.remote_status_waiting
        "running" -> R.string.remote_status_running
        "idle" -> R.string.remote_status_idle
        else -> R.string.remote_status_offline
    }

/**
 * The command palette: Ctrl+K, the chat title, or a prefix mode. A bottom sheet on phones, a dialog
 * beside the chat on wide layouts. Up and Down move through the rows, Enter runs one, Esc closes.
 */
@Composable
internal fun CommandPalette(
    input: PaletteInput,
    labels: PaletteLabels,
    initialMode: PaletteMode,
    sheet: Boolean,
    onChoose: (PaletteItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialQuery = initialMode.prefix?.toString().orEmpty()
    // The caret waits after the prefix, so typing continues the query.
    var field by rememberSaveable(initialMode, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length)))
    }
    val query = field.text
    val items = remember(input, labels, query) { paletteItems(input, labels, query) }
    var selected by remember(query) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(index) { if (items.isNotEmpty()) listState.animateScrollToItem(index) }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).testTag("commandPalette"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.remote_palette_hint)) },
                modifier =
                    Modifier.fillMaxWidth().focusRequester(focus).testTag("paletteField").onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                selected = (index + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                                true
                            }
                            Key.DirectionUp -> {
                                selected = (index - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                items.getOrNull(index)?.let(onChoose)
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { items.getOrNull(index)?.let(onChoose) }),
            )
            if (items.isEmpty())
                Text(
                    stringResource(R.string.remote_palette_empty),
                    Modifier.padding(horizontal = 4.dp).testTag("paletteEmpty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("paletteList"),
                state = listState,
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { position, item ->
                    PaletteRow(item, selected = position == index, onClick = { onChoose(item) })
                }
            }
        }
    }
    if (sheet)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) { content() }
    else
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) { content() }
        }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("paletteRow"),
    ) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The palette [mode] opens over the layout: it lists [repository]'s hosts, projects and sessions,
 * the home snapshot's sessions of the other hosts, and the open chat's actions. Choosing a row
 * opens it through [navigator]; a chat row runs in the chat through its [ChatKeyTarget].
 */
@Composable
internal fun CommandPaletteHost(
    mode: PaletteMode,
    repository: RemoteRepository,
    navigator: RemoteNavigator,
    settings: SettingsRepository,
    home: StateFlow<HomeSnapshot>,
    targets: ShortcutTargets,
    sheet: Boolean,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val snapshot by home.collectAsStateWithLifecycle()
    val chat = navigator.topChat
    val labels = rememberPaletteLabels()
    val input =
        remember(state, snapshot, chat != null) {
            PaletteInput(
                state = state,
                home = snapshot,
                chat = chat != null,
                usage = settings.chatActionUsage(),
                now = System.currentTimeMillis(),
            )
        }
    CommandPalette(
        input = input,
        labels = labels,
        initialMode = mode,
        sheet = sheet,
        onChoose = { item ->
            targets.palette = null
            when (item) {
                is SessionItem -> navigator.open(RemoteNavKey.Chat(item.routeId, item.projectId, item.sessionId))
                is ProjectItem -> navigator.open(RemoteNavKey.Sessions(item.routeId, item.projectId))
                is HostItem -> navigator.open(RemoteNavKey.Projects(item.routeId))
                is CommandItem -> chat?.let(targets::chat)?.perform?.invoke(item.command)
            }
        },
        onDismiss = { targets.palette = null },
    )
}
