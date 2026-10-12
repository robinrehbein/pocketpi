package de.joinnoah.pi.remote

import android.content.ClipData
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// ---- Pure helpers -------------------------------------------------------------------------

/** One displayed output row: [number] is the 1-based source line, null on a soft-split continuation. */
internal data class OutputRow(val number: Int?, val text: String)

/** What the "copy output" action ended in, shown next to the search bar. */
internal enum class CopyOutcome { COPIED, TRUNCATED, FAILED }

internal data class SearchMatch(val line: Int, val start: Int, val end: Int)

private val lineBreak = Regex("\r\n|\n|\r")

/** Splits at [MAX_CODE_LINE_CHARS] without cutting a surrogate pair in half. */
private fun softSplit(line: String): List<String> {
    if (line.length <= MAX_CODE_LINE_CHARS) return listOf(line)
    val parts = ArrayList<String>(line.length / MAX_CODE_LINE_CHARS + 1)
    var start = 0
    while (start < line.length) {
        var end = minOf(start + MAX_CODE_LINE_CHARS, line.length)
        if (end < line.length && line[end - 1].isHighSurrogate()) end--
        parts.add(line.substring(start, end))
        start = end
    }
    return parts
}

/**
 * Output rows with their source line numbers. CRLF, LF and a lone CR each end a line, a trailing
 * line break adds no empty row, and lines longer than [MAX_CODE_LINE_CHARS] are soft-split.
 */
internal fun outputRows(text: String): List<OutputRow> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split(lineBreak)
    val source = if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    val rows = ArrayList<OutputRow>(source.size)
    for ((index, line) in source.withIndex()) {
        softSplit(line).forEachIndexed { part, chunk -> rows.add(OutputRow(if (part == 0) index + 1 else null, chunk)) }
    }
    return rows
}

internal fun outputLines(text: String): List<String> = outputRows(text).map { it.text }

/** Case-insensitive, non-overlapping matches in reading order, at most [limit]. */
internal fun searchMatches(lines: List<String>, query: String, limit: Int = 1000): List<SearchMatch> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val matches = ArrayList<SearchMatch>()
    for ((index, line) in lines.withIndex()) {
        var from = 0
        while (true) {
            val start = line.indexOf(query, from, ignoreCase = true)
            if (start < 0) break
            matches.add(SearchMatch(index, start, start + query.length))
            if (matches.size >= limit) return matches
            from = start + query.length
        }
    }
    return matches
}

/** Search matches together with the exact rows and query they were computed from. */
internal class SearchResult(val rows: List<String>, val query: String, val matches: List<SearchMatch>)

/**
 * The matches of [result] when it was computed from these very [rows] (by identity) and [query];
 * an empty list otherwise, so offsets from older rows are never applied to newer ones.
 */
internal fun currentMatches(result: SearchResult?, rows: List<String>, query: String): List<SearchMatch> =
    result?.takeIf { it.rows === rows && it.query == query }?.matches.orEmpty()

/**
 * The index of the selected match after the match list changed: the same match when it is still
 * there, otherwise the previous index kept inside the new list.
 */
internal fun retainedMatchIndex(selected: SearchMatch?, index: Int, matches: List<SearchMatch>): Int {
    if (matches.isEmpty()) return 0
    if (selected != null) {
        val found = matches.indexOf(selected)
        if (found >= 0) return found
    }
    return index.coerceIn(matches.indices)
}

/**
 * [text] with its [matches] highlighted, the one at [current] in [currentStyle]. Offsets are
 * clamped to the text, so a match that does not fit the row can never throw.
 */
internal fun highlightedText(
    text: String,
    matches: List<IndexedValue<SearchMatch>>,
    current: Int,
    currentStyle: SpanStyle,
    otherStyle: SpanStyle,
): AnnotatedString {
    if (matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for ((index, match) in matches) {
            val start = match.start.coerceIn(at, text.length)
            val end = match.end.coerceIn(start, text.length)
            append(text.substring(at, start))
            if (end > start) withStyle(if (index == current) currentStyle else otherStyle) { append(text.substring(start, end)) }
            at = end
        }
        append(text.substring(at))
    }
}

/**
 * A clipboard payload well under the Binder transaction limit (~1 MiB shared by the whole
 * transaction, not just this extra). A `String` marshals as UTF-16 (2 bytes per `char`), so
 * 128 Ki chars is about 256 KiB and leaves headroom for the rest of the transaction.
 */
internal const val CLIPBOARD_MAX_CHARS = 128 * 1024

/** How long typing must settle before the search result count is announced to TalkBack. */
internal const val SEARCH_ANNOUNCE_DEBOUNCE_MILLIS = 600L

/**
 * [text] cut to at most [maxChars] UTF-16 `char`s, true when it was cut. The cut never lands
 * inside a surrogate pair.
 */
internal fun clipboardSafeText(text: String, maxChars: Int = CLIPBOARD_MAX_CHARS): Pair<String, Boolean> {
    if (text.length <= maxChars) return text to false
    var end = maxChars.coerceAtLeast(0)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end) to true
}

private val prettyJson = Json { prettyPrint = true }

/** The arguments as indented JSON, or null when they are missing or not valid JSON. */
internal fun prettyArguments(arguments: String?): String? {
    if (arguments.isNullOrBlank()) return null
    val element = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() ?: return null
    return prettyJson.encodeToString(JsonElement.serializer(), element)
}

private fun JsonObject.stringArg(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.numberArg(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private const val MAX_ARGUMENT_ROWS = 2000

/** Rows all argument blocks of one tool call share until the user asks to see all of them. */
internal const val ARGUMENT_PREVIEW_ROWS = 200

/**
 * A code block's rows render in a bounded-height lazy list, so a large expanded block (up to
 * [MAX_ARGUMENT_ROWS] rows) only composes what fits on screen instead of every row at once. Short
 * blocks size to their content, exactly as a plain column would.
 */
private val CODE_BLOCK_MAX_HEIGHT = 420.dp

/**
 * Row limits for consecutive blocks of [sizes] rows. Collapsed, the blocks share [budget] rows in
 * order, so one large edit composes a bounded number of rows; expanded, each block gets
 * [MAX_ARGUMENT_ROWS] of its own.
 */
internal fun argumentRowLimits(sizes: List<Int>, expanded: Boolean, budget: Int = ARGUMENT_PREVIEW_ROWS): List<Int> {
    if (expanded) return sizes.map { minOf(it, MAX_ARGUMENT_ROWS) }
    var left = budget
    return sizes.map { size ->
        val take = minOf(size, left).coerceAtLeast(0)
        left -= take
        take
    }
}

// ---- Screen -------------------------------------------------------------------------------

@Composable
internal fun ToolDetailScreen(
    item: ConversationItem.Activity,
    download: ToolOutputDownload?,
    canLoadFullOutput: Boolean,
    canAskToFix: Boolean,
    onLoadFullOutput: () -> Unit,
    onCancelFullOutput: () -> Unit,
    onAskToFix: () -> Unit,
    onQuote: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    // Everything below belongs to one tool call: another item starts with fresh search and scroll state.
    key(item.id) {
        ToolDetailContent(item, download, canAskToFix, canLoadFullOutput, onLoadFullOutput, onCancelFullOutput, onAskToFix, onQuote, onClose, modifier)
    }
}

@Composable
private fun ToolDetailContent(
    item: ConversationItem.Activity,
    download: ToolOutputDownload?,
    canAskToFix: Boolean,
    canLoadFullOutput: Boolean,
    onLoadFullOutput: () -> Unit,
    onCancelFullOutput: () -> Unit,
    onAskToFix: () -> Unit,
    onQuote: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val ownDownload = download?.takeIf { item.toolCallId != null && it.toolCallId == item.toolCallId }
    val complete = ownDownload?.takeIf { it.text != null }
    val output = complete?.text ?: item.output
    val style = monoTextStyle()
    val rows = remember(output) { output?.let(::outputRows).orEmpty() }
    val rowTexts = remember(rows) { rows.map { it.text } }
    var query by rememberSaveable { mutableStateOf("") }
    val result by
        produceState<SearchResult?>(null, rowTexts, query) {
            value = withContext(Dispatchers.Default) { SearchResult(rowTexts, query, searchMatches(rowTexts, query)) }
        }
    // Matches computed for older rows or an older query are dropped until the recompute lands.
    val matches = currentMatches(result, rowTexts, query)
    // The user's selection survives new match lists (streaming output, a finished download).
    var selected by remember(query) { mutableStateOf<SearchMatch?>(null) }
    var selectedIndex by remember(query) { mutableIntStateOf(0) }
    val current = retainedMatchIndex(selected, selectedIndex, matches)
    // Scrolling follows user input only: once per query when its first matches arrive, then per step.
    var navigation by remember(query) { mutableIntStateOf(0) }
    var revealedNavigation by remember(query) { mutableIntStateOf(-1) }
    val select = { index: Int ->
        selectedIndex = index
        selected = matches.getOrNull(index)
        navigation++
    }
    val matchesByRow = remember(matches) { matches.withIndex().groupBy { it.value.line } }
    val listState = rememberLazyListState()
    val outputScroll = rememberScrollState()
    val charWidth = rememberMonoCharWidth(style)
    val contentWidth = rememberCodeContentWidth(rowTexts, style)
    val maxNumber = remember(rows) { rows.maxOfOrNull { it.number ?: 0 } ?: 1 }
    val numberWidth = rememberLineNumberWidth(maxNumber, style)
    val density = LocalDensity.current
    var searchBarHeight by remember { mutableIntStateOf(0) }
    val cards = remember(output) { resultCards(output.orEmpty()) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val useCards = item.name == "bash" && rows.isNotEmpty()
    val rawVisible = !useCards || showRaw || query.isNotEmpty()
    val firstRowIndex = if (useCards) 4 else 3 // includes the raw-output disclosure

    LaunchedEffect(query, navigation, matches.isNotEmpty()) {
        if (revealedNavigation == navigation) return@LaunchedEffect
        val match = matches.getOrNull(current) ?: return@LaunchedEffect
        revealedNavigation = navigation
        val target = firstRowIndex + match.line
        val visible = listState.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.index == target }
        val top = listState.layoutInfo.viewportStartOffset + searchBarHeight
        if (info == null || info.offset < top || info.offset + info.size > listState.layoutInfo.viewportEndOffset) {
            listState.scrollToItem(target)
            listState.scrollBy(-(searchBarHeight + with(density) { 48.dp.toPx() }))
        }
        val column = with(density) { (charWidth * (match.start - 8).coerceAtLeast(0)).toPx() }
        outputScroll.animateScrollTo(column.toInt().coerceIn(0, outputScroll.maxValue))
    }

    Surface(modifier.fillMaxSize().testTag("toolDetail")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ToolDetailTopBar(item, onClose)
            HorizontalDivider()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("toolDetailContent"),
                state = listState,
            ) {
                item(key = "arguments") {
                    ArgumentsSection(item, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                item(key = "output") {
                    OutputHeader(
                        item = item,
                        download = ownDownload,
                        hasOutput = rows.isNotEmpty(),
                        canLoadFullOutput = canLoadFullOutput,
                        onLoadFullOutput = onLoadFullOutput,
                        onCancelFullOutput = onCancelFullOutput,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                stickyHeader(key = "search") {
                    if (rows.isNotEmpty())
                        SearchBar(
                            query = query,
                            onQuery = { query = it },
                            matches = matches.size,
                            current = current,
                            onPrevious = { if (matches.isNotEmpty()) select((current - 1 + matches.size) % matches.size) },
                            onNext = { if (matches.isNotEmpty()) select((current + 1) % matches.size) },
                            output = output.orEmpty(),
                            modifier = Modifier.onSizeChanged { searchBarHeight = it.height },
                        )
                }
                if (useCards) {
                    item(key = "rawToggle") {
                        if (!rawVisible) Text(
                            stringResource(R.string.remote_result_count, cards.size),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(onClick = { showRaw = !showRaw }, Modifier.padding(horizontal = 16.dp).testTag("toolDetailRawToggle")) {
                            Text(stringResource(if (showRaw) R.string.remote_result_hide_raw else R.string.remote_result_show_raw))
                        }
                    }
                }
                if (rawVisible) {
                    itemsIndexed(rows, contentType = { _, _ -> "row" }) { index, row ->
                        OutputRowView(
                            row = row,
                            matches = matchesByRow[index].orEmpty(),
                            current = current,
                            numberWidth = numberWidth,
                            contentWidth = contentWidth,
                            scroll = outputScroll,
                            style = style,
                        )
                    }
                } else {
                    items(cards, contentType = { "resultCard" }) { card -> ResultCardView(card) }
                }
                item(key = "end") { Box(Modifier.heightIn(min = 16.dp)) }
            }
            val quote = !output.isNullOrBlank()
            if (quote || canAskToFix) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (quote)
                        OutlinedButton(onClick = onQuote, Modifier.testTag("toolDetailQuote")) {
                            Text(stringResource(R.string.remote_quote))
                        }
                    if (canAskToFix)
                        Button(onClick = onAskToFix, Modifier.testTag("toolDetailAskFix")) {
                            Text(stringResource(R.string.remote_tool_detail_ask_fix))
                        }
                }
            }
        }
    }
}

@Composable
private fun ToolDetailTopBar(item: ConversationItem.Activity, onClose: () -> Unit) {
    val titleFocus = remember { FocusRequester() }
    // Moves TalkBack focus to the overlay's own title as it opens, instead of leaving it wherever
    // it was on the screen behind.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { titleFocus.requestFocus() }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose, Modifier.testTag("toolDetailClose")) {
            Icon(Icons.Default.Close, stringResource(R.string.remote_tool_detail_close))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.summary.labelRes?.let { stringResource(it) } ?: item.name ?: stringResource(R.string.remote_tool),
                modifier = Modifier.testTag("toolDetailTitle").focusRequester(titleFocus).focusable(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.summary.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StateChip(item.state)
        }
    }
}

@Composable
private fun StateChip(state: String) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) =
        when (state) {
            "error" -> Triple(R.string.remote_tool_error, colors.errorContainer, colors.onErrorContainer)
            "streaming" -> Triple(R.string.remote_tool_pending, colors.primaryContainer, colors.onPrimaryContainer)
            "unavailable" -> Triple(R.string.remote_tool_unavailable, colors.surfaceVariant, colors.onSurfaceVariant)
            else -> Triple(R.string.remote_tool_complete, colors.secondaryContainer, colors.onSecondaryContainer)
        }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag("toolDetailState"),
    ) {
        Text(
            stringResource(label),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Notice(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun LabeledMono(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = monoTextStyle()) }
    }
}

/**
 * A code block for argument values; each block scrolls sideways on its own. Rows are lazy items in
 * a height-bounded list, so an expanded block of up to [MAX_ARGUMENT_ROWS] rows composes only what
 * is visible instead of all of them at once.
 */
@Composable
private fun CodeBlock(
    text: String,
    numbered: Boolean,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    limit: Int = MAX_ARGUMENT_ROWS,
) {
    val style = monoTextStyle()
    val rows = remember(text) { outputRows(text) }
    val shown = remember(rows, limit) { rows.take(limit.coerceIn(0, MAX_ARGUMENT_ROWS)) }
    val texts = remember(shown) { shown.map { it.text } }
    val width = rememberCodeContentWidth(texts, style)
    val maxNumber = remember(shown) { shown.maxOfOrNull { it.number ?: 0 } ?: 1 }
    val numberWidth = rememberLineNumberWidth(maxNumber, style)
    val scroll = rememberScrollState()
    Surface(modifier.fillMaxWidth(), color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(vertical = 6.dp)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = CODE_BLOCK_MAX_HEIGHT).testTag("codeBlockRows")) {
                items(shown.size, key = { it }, contentType = { "row" }) { index ->
                    val row = shown[index]
                    Row {
                        if (numbered) LineNumber(row.number, numberWidth, style)
                        Box(Modifier.weight(1f).horizontalScroll(scroll).padding(horizontal = 8.dp)) {
                            Text(row.text, Modifier.width(width), style = style, softWrap = false, maxLines = 1)
                        }
                    }
                }
            }
            if (rows.size > shown.size)
                Text(
                    pluralStringResource(R.plurals.remote_tool_detail_more_lines, rows.size - shown.size, rows.size - shown.size),
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
}

@Composable
private fun LineNumber(number: Int?, width: Dp, style: TextStyle) {
    Text(
        number?.toString().orEmpty(),
        Modifier.padding(horizontal = 8.dp).width(width),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        softWrap = false,
        maxLines = 1,
    )
}

/** The argument blocks of one tool call, in display order; row limits are assigned in this order. */
private sealed interface ArgumentBlock {
    val rows: Int

    data class Code(val text: String, val numbered: Boolean) : ArgumentBlock {
        override val rows = outputRows(text).size
    }

    data class Change(val index: Int, val count: Int, val change: EditChange) : ArgumentBlock {
        val oldRows = outputRows(change.oldText).size
        val newRows = outputRows(change.newText).size
        override val rows = oldRows + newRows
    }

    data class Diff(val diff: ToolDiff) : ArgumentBlock {
        override val rows = diff.lines.size
    }
}

private class ArgumentsModel(
    val path: String?,
    val label: Int?,
    val blocks: List<ArgumentBlock>,
    val offset: Long? = null,
    val limit: Long? = null,
    val truncated: Boolean = false,
    val missing: Boolean = false,
)

private fun argumentsModel(item: ConversationItem.Activity): ArgumentsModel {
    val diff = if (item.name == "edit") toolDiff(item)?.let { listOf(ArgumentBlock.Diff(it)) }.orEmpty() else emptyList()
    val raw = item.arguments
    if (raw.isNullOrEmpty()) return ArgumentsModel(null, null, diff, missing = true)
    val args = parsedToolArguments(raw)
    val pathTool = item.name == "edit" || item.name == "write" || item.name == "read"
    return when {
        args == null || item.argumentsTruncated ->
            ArgumentsModel(
                if (pathTool) toolPath(item) else null,
                null,
                listOf(ArgumentBlock.Code(raw, numbered = false)) + diff,
                truncated = item.argumentsTruncated,
            )
        item.name == "bash" -> {
            val command = args.stringArg("command")
            if (command != null)
                ArgumentsModel(null, R.string.remote_tool_detail_command, listOf(ArgumentBlock.Code(command, numbered = false)))
            else ArgumentsModel(null, null, listOf(ArgumentBlock.Code(prettyArguments(raw) ?: raw, numbered = false)))
        }
        item.name == "read" ->
            ArgumentsModel(toolPath(item), null, emptyList(), offset = args.numberArg("offset"), limit = args.numberArg("limit"))
        item.name == "write" -> {
            val content = args.stringArg("content")
            ArgumentsModel(
                toolPath(item),
                content?.let { R.string.remote_tool_detail_content },
                listOfNotNull(content?.let { ArgumentBlock.Code(it, numbered = true) }),
            )
        }
        item.name == "edit" -> {
            val edit = editArguments(raw)
            val changes = edit?.changes.orEmpty()
            ArgumentsModel(
                edit?.path,
                null,
                changes.mapIndexed { index, change -> ArgumentBlock.Change(index, changes.size, change) } + diff,
            )
        }
        else -> ArgumentsModel(null, null, listOf(ArgumentBlock.Code(prettyArguments(raw) ?: raw, numbered = false)))
    }
}

@Composable
private fun ArgumentsSection(item: ConversationItem.Activity, modifier: Modifier = Modifier) {
    val model = remember(item.arguments, item.argumentsTruncated, item.details, item.name) { argumentsModel(item) }
    // Large arguments start as a bounded preview (ARGUMENT_PREVIEW_ROWS shared across blocks) and
    // the user opts into the rest; each code block then lays its own rows out lazily (CodeBlock).
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sizes = remember(model) { model.blocks.flatMap { if (it is ArgumentBlock.Change) listOf(it.oldRows, it.newRows) else listOf(it.rows) } }
    val limits = remember(sizes, expanded) { argumentRowLimits(sizes, expanded) }
    val cut = remember(sizes) { argumentRowLimits(sizes, expanded = false).zip(sizes).any { (limit, size) -> limit < size } }
    Column(modifier.fillMaxWidth().testTag("toolDetailArguments"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.remote_tool_arguments))
        if (model.missing) Notice(stringResource(R.string.remote_tool_detail_no_arguments))
        model.path?.let { LabeledMono(stringResource(R.string.remote_tool_detail_path), it) }
        model.offset?.let { Notice(stringResource(R.string.remote_tool_detail_offset, it.toString())) }
        model.limit?.let { Notice(stringResource(R.string.remote_tool_detail_limit, it.toString())) }
        model.label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium) }
        var at = 0
        for (block in model.blocks) {
            when (block) {
                is ArgumentBlock.Code -> {
                    if (item.name == "bash" && !item.argumentsTruncated && block.rows <= 16 && block.text.length <= RESULT_CARD_PREVIEW_CHARS) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                            SelectionContainer { Text(block.text, Modifier.fillMaxWidth().padding(12.dp), style = monoTextStyle()) }
                        }
                    } else CodeBlock(block.text, block.numbered, limit = limits[at])
                    at += 1
                }
                is ArgumentBlock.Change -> {
                    EditChangeView(block.index, block.count, block.change, limits[at], limits[at + 1])
                    at += 2
                }
                is ArgumentBlock.Diff -> {
                    EditDiffSection(block.diff, limits[at])
                    at += 1
                }
            }
        }
        if (model.truncated) Notice(stringResource(R.string.remote_tool_detail_arguments_truncated))
        if (cut)
            TextButton(onClick = { expanded = !expanded }, Modifier.testTag("toolDetailArgumentsToggle")) {
                Text(
                    stringResource(
                        if (expanded) R.string.remote_tool_detail_arguments_show_less
                        else R.string.remote_tool_detail_arguments_show_all
                    )
                )
            }
    }
}

@Composable
private fun EditChangeView(index: Int, count: Int, change: EditChange, beforeLimit: Int, afterLimit: Int) {
    Column(Modifier.fillMaxWidth().testTag("toolDetailEditChange-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (count > 1) Text(stringResource(R.string.remote_tool_detail_change, index + 1), style = MaterialTheme.typography.labelMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val before: @Composable (Modifier) -> Unit = { m ->
                EditSide(stringResource(R.string.remote_tool_detail_before), change.oldText, diffRemovedContainer(), diffRemovedContent(), beforeLimit, m.testTag("toolDetailEditBefore-$index"))
            }
            val after: @Composable (Modifier) -> Unit = { m ->
                EditSide(stringResource(R.string.remote_tool_detail_after), change.newText, diffAddedContainer(), diffAddedContent(), afterLimit, m.testTag("toolDetailEditAfter-$index"))
            }
            if (maxWidth >= 600.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    before(Modifier.weight(1f))
                    after(Modifier.weight(1f))
                }
            else
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    before(Modifier)
                    after(Modifier)
                }
        }
    }
}

@Composable
private fun EditSide(label: String, text: String, container: Color, content: Color, limit: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        CodeBlock(text, numbered = false, container = container, content = content, limit = limit)
    }
}

@Composable
private fun EditDiffSection(diff: ToolDiff, limit: Int) {
    Column(Modifier.fillMaxWidth().testTag("toolDetailDiff"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(R.string.remote_tool_detail_diff))
        Notice(
            stringResource(
                if (diff.fromDetails) R.string.remote_tool_detail_diff_from_host
                else R.string.remote_tool_detail_diff_from_arguments
            ) + " · " + stringResource(R.string.remote_tool_detail_diff_summary, diff.added, diff.removed)
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
            DiffView(diff.lines, Modifier.fillMaxWidth().padding(vertical = 6.dp), maxLines = limit)
        }
        if (diff.truncated) Notice(stringResource(R.string.remote_tool_detail_diff_truncated))
    }
}

private fun formatBytes(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

/**
 * [loaded] of [total] bytes rounded down to the nearest 10%, or null when [total] is unknown (so
 * the caller announces a one-off "started" instead). Used to throttle the accessible progress
 * announcement to coarse steps instead of one per chunk.
 */
internal fun loadingProgressStep(loaded: Long, total: Long?): Int? {
    if (total == null || total <= 0) return null
    return ((loaded.toFloat() / total) * 10).toInt().coerceIn(0, 10) * 10
}

@Composable
private fun OutputHeader(
    item: ConversationItem.Activity,
    download: ToolOutputDownload?,
    hasOutput: Boolean,
    canLoadFullOutput: Boolean,
    onLoadFullOutput: () -> Unit,
    onCancelFullOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val complete = download?.takeIf { it.text != null }
    Column(modifier.fillMaxWidth().testTag("toolDetailOutput"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.remote_tool_output))
        if (!hasOutput) Notice(stringResource(R.string.remote_tool_detail_no_output))
        if (complete != null) {
            Notice(stringResource(R.string.remote_tool_detail_full_loaded))
            if (complete.truncated) Notice(stringResource(R.string.remote_tool_detail_capped))
        } else if (item.truncated) Notice(stringResource(R.string.remote_truncated))
        // Only the first "Load full output" button depends on the capability. A download that already
        // started keeps its progress, cancel button and failure notice: an UNSUPPORTED answer marks the
        // capability unavailable, and hiding the block then would swallow the explanation.
        if (download != null && complete == null)
            LoadFullOutput(download, onLoadFullOutput, onCancelFullOutput, canRetry = canLoadFullOutput)
        else if (canLoadFullOutput && item.truncated && item.toolCallId != null && complete == null)
            LoadFullOutput(null, onLoadFullOutput, onCancelFullOutput, canRetry = true)
    }
}

@Composable
private fun LoadFullOutput(
    download: ToolOutputDownload?,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    canRetry: Boolean,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().testTag("toolDetailLoadFull"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            download == null ->
                OutlinedButton(onClick = onLoad) { Text(stringResource(R.string.remote_tool_detail_load_full)) }
            download.failure != null -> {
                Notice(
                    stringResource(
                        when (download.failure) {
                            ToolOutputFailure.UNSUPPORTED -> R.string.remote_tool_detail_failure_unsupported
                            ToolOutputFailure.NOT_FOUND -> R.string.remote_tool_detail_failure_not_found
                            ToolOutputFailure.OFFLINE -> R.string.remote_tool_detail_failure_offline
                            ToolOutputFailure.FAILED -> R.string.remote_tool_detail_failure_failed
                        }
                    ),
                    MaterialTheme.colorScheme.error,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (canRetry && download.failure != ToolOutputFailure.UNSUPPORTED)
                    OutlinedButton(onClick = onLoad) { Text(stringResource(R.string.remote_tool_detail_retry)) }
            }
            else -> {
                val total = download.totalBytes
                if (total != null && total > 0)
                    LinearProgressIndicator(
                        progress = { (download.loadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Updates on every chunk: visible for sighted users, but deliberately outside
                    // any live region so TalkBack is not asked to speak it that often.
                    Text(
                        if (total != null)
                            stringResource(
                                R.string.remote_tool_detail_loading_progress,
                                formatBytes(context, download.loadedBytes),
                                formatBytes(context, total),
                            )
                        else stringResource(R.string.remote_tool_detail_loading, formatBytes(context, download.loadedBytes)),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.remote_tool_detail_cancel_load)) }
                }
                // A coarse announcement instead: only text that changes at 10% steps (or once, when
                // the total size is unknown) reaches this live region, so TalkBack speaks progress
                // occasionally rather than on every chunk.
                val percentStep = loadingProgressStep(download.loadedBytes, total)
                val coarseProgress =
                    if (percentStep != null) stringResource(R.string.remote_tool_detail_loading_progress_percent, percentStep)
                    else stringResource(R.string.remote_tool_detail_loading_started)
                Spacer(Modifier.size(0.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = coarseProgress })
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    matches: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    output: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copyOutcome by remember { mutableStateOf<CopyOutcome?>(null) }
    LaunchedEffect(copyOutcome) {
        if (copyOutcome != null) {
            delay(2500)
            copyOutcome = null
        }
    }
    // Announced to TalkBack only after typing settles, not on every keystroke: the visible count
    // below still updates immediately.
    var announcedQuery by remember { mutableStateOf(query) }
    var announcedMatches by remember { mutableStateOf(matches) }
    var announcedCurrent by remember { mutableStateOf(current) }
    LaunchedEffect(query, matches, current) {
        delay(SEARCH_ANNOUNCE_DEBOUNCE_MILLIS)
        announcedQuery = query
        announcedMatches = matches
        announcedCurrent = current
    }
    val copyLabel = stringResource(R.string.remote_tool_detail_copy)
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onQuery(it.take(200)) },
                    modifier = Modifier.weight(1f).testTag("toolDetailSearch"),
                    placeholder = { Text(stringResource(R.string.remote_tool_detail_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            val (clipText, truncated) = clipboardSafeText(output)
                            val success =
                                runCatching {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, clipText)))
                                    }
                                    .isSuccess
                            copyOutcome =
                                when {
                                    !success -> CopyOutcome.FAILED
                                    truncated -> CopyOutcome.TRUNCATED
                                    else -> CopyOutcome.COPIED
                                }
                        }
                    },
                    modifier = Modifier.testTag("toolDetailCopy"),
                ) {
                    Icon(Icons.Default.ContentCopy, copyLabel)
                }
            }
            if (query.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = matches > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.remote_tool_detail_search_previous))
                }
                IconButton(onClick = onNext, enabled = matches > 0) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.remote_tool_detail_search_next))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (query.isNotEmpty())
                    Text(
                        when {
                            matches == 0 -> stringResource(R.string.remote_tool_detail_search_none)
                            matches >= 1000 -> stringResource(R.string.remote_tool_detail_search_count_capped, current + 1, matches)
                            else -> stringResource(R.string.remote_tool_detail_search_count, current + 1, matches)
                        },
                        Modifier.testTag("toolDetailSearchCount"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                // A separate, own live region: a copy outcome change never re-triggers the search
                // announcement (and vice versa).
                copyOutcome?.let {
                    Text(
                        stringResource(
                            when (it) {
                                CopyOutcome.COPIED -> R.string.remote_tool_detail_copied
                                CopyOutcome.TRUNCATED -> R.string.remote_tool_detail_copy_truncated
                                CopyOutcome.FAILED -> R.string.remote_tool_detail_copy_failed
                            }
                        ),
                        Modifier.testTag("toolDetailCopyResult").semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it == CopyOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (announcedQuery.isNotEmpty()) {
                val announcedText =
                    when {
                        announcedMatches == 0 -> stringResource(R.string.remote_tool_detail_search_none)
                        announcedMatches >= 1000 ->
                            stringResource(R.string.remote_tool_detail_search_count_capped, announcedCurrent + 1, announcedMatches)
                        else -> stringResource(R.string.remote_tool_detail_search_count, announcedCurrent + 1, announcedMatches)
                    }
                Spacer(
                    Modifier.size(0.dp).testTag("toolDetailSearchAnnouncement").semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcedText
                    }
                )
            }
        }
    }
}

@Composable
private fun OutputRowView(
    row: OutputRow,
    matches: List<IndexedValue<SearchMatch>>,
    current: Int,
    numberWidth: Dp,
    contentWidth: Dp,
    scroll: ScrollState,
    style: TextStyle,
) {
    val colors = MaterialTheme.colorScheme
    val text =
        highlightedText(
            row.text,
            matches,
            current,
            currentStyle = SpanStyle(background = colors.primary, color = colors.onPrimary),
            otherStyle = SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer),
        )
    Row(Modifier.fillMaxWidth()) {
        LineNumber(row.number, numberWidth, style)
        Box(Modifier.weight(1f).horizontalScroll(scroll).padding(end = 16.dp)) {
            Text(text, Modifier.width(contentWidth), style = style, softWrap = false, maxLines = 1, color = LocalContentColor.current)
        }
    }
}

@Composable
private fun ResultCardView(card: ResultCard) {
    val colors = MaterialTheme.colorScheme
    val accent = when (card.kind) {
        ResultKind.APPROVED -> diffAddedContent()
        ResultKind.REVISION -> colors.tertiary
        ResultKind.NEUTRAL -> colors.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("toolResultCard"),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .35f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(when (card.kind) {
                    ResultKind.APPROVED -> Icons.Default.CheckCircle
                    ResultKind.REVISION -> Icons.Default.Warning
                    ResultKind.NEUTRAL -> Icons.Default.Info
                }, null, tint = accent, modifier = Modifier.size(20.dp))
                Text(stringResource(when (card.kind) {
                    ResultKind.APPROVED -> R.string.remote_result_approved
                    ResultKind.REVISION -> R.string.remote_result_revision
                    ResultKind.NEUTRAL -> R.string.remote_result_output
                }), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = accent)
            }
            card.timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            val body = if (card.timestamp != null) card.text.substringAfter("\n", "") else card.text
            SelectionContainer { Text(resultCardPreview(body), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
            if (body.length > RESULT_CARD_PREVIEW_CHARS) Notice(stringResource(R.string.remote_result_preview_shortened))
        }
    }
}
