package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import kotlinx.coroutines.flow.first

/** The palette's labels in the app's language; the rows of [paletteItems] carry them. */
@Composable
internal fun rememberPaletteLabels(): PaletteLabels {
    val resources = LocalResources.current
    val project = stringResource(R.string.remote_palette_project)
    val current = stringResource(R.string.remote_palette_current)
    return remember(resources, project, current) {
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
            current = current,
        )
    }
}

@StringRes
private fun SessionControl.labelRes(): Int =
    when (this) {
        SessionControl.MODEL -> R.string.remote_palette_model
        SessionControl.THINKING -> R.string.remote_choose_thinking
        SessionControl.ADVISOR -> R.string.remote_advisor_title
        SessionControl.COMPACT -> R.string.remote_context_compact
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
    modifier: Modifier = Modifier,
) {
    val initialQuery = initialMode.prefix?.toString().orEmpty()
    // The caret waits after the prefix, so typing continues the query.
    var field by rememberSaveable(initialMode, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length)))
    }
    val query = field.text
    val items = remember(input, labels, query) { paletteDisplayItems(paletteItems(input, labels, query), query) }
    val parsed = paletteQuery(query)
    val showSections = parsed.mode == PaletteMode.ALL && parsed.text.isEmpty()
    // The highlight follows the row, not its position: the live status and recency reorder the list.
    var selectedKey by remember(query) { mutableStateOf<String?>(null) }
    val index = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val content: @Composable () -> Unit = {
        BoxWithConstraints(modifier.fillMaxWidth().heightIn(max = windowHeightFraction(0.84f))) {
        // Keep search and results available when the IME or a small window leaves little height.
        // Optional orientation controls return automatically as soon as there is room for them.
        val compact = maxHeight < 360.dp
        val fontScale = LocalDensity.current.fontScale
        LaunchedEffect(index, items.getOrNull(index)?.key, maxHeight, fontScale) {
            withFrameNanos { }
            if (items.isNotEmpty()) listState.scrollIntoView(index)
        }
        val window = LocalWindowInfo.current
        LaunchedEffect(window) {
            // The dialog/sheet owns a different focus lifecycle from its launching chat. Wait for
            // that window and the subcomposed field to be ready before requesting initial focus.
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            focus.requestFocus()
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(if (compact) 8.dp else 16.dp).testTag("commandPalette"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!compact) SheetHeader(
                stringResource(R.string.remote_palette_quick_access),
                closeLabel = stringResource(R.string.remote_page_close),
                onClose = onDismiss,
                padding = PaddingValues(start = 4.dp),
            )
            FloatingSurface(Modifier.fillMaxWidth(), shape = CircleShape, blur = false) {
            TextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text(stringResource(if (compact) R.string.remote_palette_search_compact else R.string.remote_palette_search), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (field.text.isNotEmpty()) IconButton(
                            onClick = { field = TextFieldValue(""); focus.requestFocus() },
                            modifier = Modifier.testTag("paletteClear"),
                        ) { Icon(Icons.Default.Close, stringResource(R.string.remote_palette_clear)) }
                        if (compact) IconButton(onClick = onDismiss, modifier = Modifier.testTag("paletteClose")) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.remote_page_close))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier =
                    Modifier.fillMaxWidth().focusRequester(focus).testTag("paletteField").onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                selectedKey = items.getOrNull((index + 1).coerceAtMost(items.lastIndex))?.key
                                true
                            }
                            Key.DirectionUp -> {
                                selectedKey = items.getOrNull((index - 1).coerceAtLeast(0))?.key
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
            }
            if (!compact) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(PaletteMode.ALL, PaletteMode.SESSIONS, PaletteMode.ACTIONS, PaletteMode.COMMANDS, PaletteMode.FILES).forEach { mode ->
                    val bringIntoView = remember { BringIntoViewRequester() }
                    LaunchedEffect(parsed.mode) {
                        if (parsed.mode == mode) bringIntoView.bringIntoView()
                    }
                    FilterChip(
                        selected = parsed.mode == mode,
                        onClick = {
                            val text = paletteModeQuery(field.text, mode)
                            field = TextFieldValue(text, TextRange(text.length))
                            focus.requestFocus()
                        },
                        label = { Text(stringResource(paletteModeLabel(mode))) },
                        modifier = Modifier.bringIntoViewRequester(bringIntoView).testTag("paletteMode${mode.name}"),
                        shape = CircleShape,
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
            if (items.isEmpty())
                Text(
                    stringResource(R.string.remote_palette_empty),
                    Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("paletteEmpty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp).testTag("paletteList"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { position, item ->
                    Column {
                        val section = paletteSection(item)
                        if (showSections && (position == 0 || paletteSection(items[position - 1]) != section)) {
                            Text(
                                stringResource(paletteSectionLabel(section)),
                                Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                                    .semantics { heading() }.testTag("paletteSection${section.name}"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PaletteRow(item, selected = position == index, onClick = { onChoose(item) })
                    }
                }
            }
        }
        }
    }
    if (sheet)
        GlassSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) { content() }
    else
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FloatingSurface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
                shape = MaterialTheme.shapes.extraLarge,
                blur = false,
            ) { content() }
        }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(sheetOptionColor(selected), SHEET_OPTION_SHAPE)
            .clip(SHEET_OPTION_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            // The current session or host says so in its detail line, which TalkBack reads already.
            .semantics { this.selected = selected }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("paletteRow"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when (paletteSection(item)) {
                PaletteCategory.SESSION -> Icons.AutoMirrored.Outlined.Chat
                PaletteCategory.ACTION, PaletteCategory.CONTROL -> Icons.Outlined.Bolt
                PaletteCategory.COMMAND -> Icons.Outlined.Terminal
                PaletteCategory.PROJECT -> Icons.Outlined.Folder
                PaletteCategory.HOST -> Icons.Outlined.Computer
                PaletteCategory.FILE -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
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
}

@StringRes
private fun paletteModeLabel(mode: PaletteMode): Int = when (mode) {
    PaletteMode.ALL -> R.string.remote_palette_mode_all
    PaletteMode.SESSIONS -> R.string.remote_palette_mode_sessions
    PaletteMode.ACTIONS -> R.string.remote_palette_mode_actions
    PaletteMode.COMMANDS -> R.string.remote_palette_mode_commands
    PaletteMode.FILES -> R.string.remote_palette_mode_files
}

@StringRes
private fun paletteSectionLabel(category: PaletteCategory): Int = when (category) {
    PaletteCategory.SESSION -> R.string.remote_palette_section_sessions
    PaletteCategory.ACTION, PaletteCategory.CONTROL -> R.string.remote_palette_section_actions
    PaletteCategory.COMMAND -> R.string.remote_palette_section_commands
    PaletteCategory.PROJECT -> R.string.remote_palette_section_projects
    PaletteCategory.HOST -> R.string.remote_palette_section_hosts
    PaletteCategory.FILE -> R.string.remote_palette_section_files
}

/** Scrolls only as far as it takes to show row [index] whole, so the rows the user reads stay put. */
private suspend fun LazyListState.scrollIntoView(index: Int) {
    if (layoutInfo.visibleItemsInfo.isEmpty()) animateScrollToItem(index)
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val row = visible.firstOrNull { it.index == index }
    val start = info.viewportStartOffset
    val end = info.viewportEndOffset
    when {
        row != null && row.offset >= start && row.offset + row.size <= end -> Unit
        index < visible.first().index || (row != null && row.offset < start) -> animateScrollToItem(index)
        else -> animateScrollToItem(index, -(end - start - (row?.size ?: visible.last().size)).coerceAtLeast(0))
    }
    // A section heading shares its first result's item. In a short viewport both may not fit;
    // reveal the result at the bottom of that item rather than leaving only its heading visible.
    val measured = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (measured != null && viewport > 0 && measured.size > viewport)
        animateScrollToItem(index, measured.size - viewport)
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
    val chat = navigator.chatInView(LocalTwoPane.current)
    val mutating = navigator.mutating
    val labels = rememberPaletteLabels()
    val marks by sessionMarksState()
    val input =
        remember(state, snapshot, chat != null, mutating, marks) {
            PaletteInput(
                state = state,
                home = snapshot,
                chat = chat != null,
                usage = settings.chatActionUsage(),
                now = System.currentTimeMillis(),
                mutating = mutating,
                marks = marks,
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
