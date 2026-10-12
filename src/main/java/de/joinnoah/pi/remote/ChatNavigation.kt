package de.joinnoah.pi.remote

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.launch

// Finding a place in a long chat: a thin marker scrollbar beside the list, and the chat history,
// a list of the user's messages with the questions, changes, plans and errors between them.

// ---- Scrollbar maths ----------------------------------------------------------------------

/**
 * Where the list stands, counted in items: [position] is the first visible item's index plus the
 * share of it scrolled away, [visible] how many items fit the viewport, [total] all items.
 */
internal data class ScrollMetrics(val position: Float, val visible: Float, val total: Int)

/** The thumb as fractions of the track. */
internal data class ScrollbarThumb(val top: Float, val size: Float)

/**
 * How many items the viewport shows, partly visible ones by their visible share. [offsets] are
 * relative to the start of the content area, which is [viewportLength] long.
 */
internal fun visibleItemSpan(offsets: List<Int>, sizes: List<Int>, viewportLength: Int): Float =
    offsets.indices.sumOf { i ->
        val size = sizes[i]
        if (size <= 0) 0.0
        else ((minOf(offsets[i] + size, viewportLength) - maxOf(offsets[i], 0)).coerceAtLeast(0) / size.toDouble())
    }.toFloat()

internal fun scrollMetrics(info: LazyListLayoutInfo, firstIndex: Int, firstOffset: Int): ScrollMetrics {
    val items = info.visibleItemsInfo
    val firstSize = items.firstOrNull { it.index == firstIndex }?.size ?: 0
    val position = firstIndex + if (firstSize > 0) (firstOffset.toFloat() / firstSize).coerceIn(0f, 1f) else 0f
    val length = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    return ScrollMetrics(position, visibleItemSpan(items.map { it.offset }, items.map { it.size }, length), info.totalItemsCount)
}

/**
 * The thumb covers the visible items: with no minimum size its top is exactly
 * `position / total`, the same index fraction [scrollbarTick] uses, so a tick lies inside the
 * thumb while its item is on screen. [minSize] keeps a short thumb grabbable.
 */
internal fun scrollbarThumb(metrics: ScrollMetrics, minSize: Float = 0f): ScrollbarThumb? {
    val (position, visible, total) = metrics
    if (total <= 0 || visible <= 0f || visible >= total) return null
    val size = (visible / total).coerceIn(minSize.coerceIn(0f, 1f), 1f)
    val progress = (position / (total - visible)).coerceIn(0f, 1f)
    return ScrollbarThumb(progress * (1f - size), size)
}

/** A list item's tick, at the middle of the item's share of the track. */
internal fun scrollbarTick(listIndex: Int, total: Int): Float =
    if (total <= 0) 0f else ((listIndex + 0.5f) / total).coerceIn(0f, 1f)

/**
 * The scroll position, in items, for a drag at [fraction] of the track: the thumb's middle follows
 * the finger, so the thumb's ends reach the list's ends.
 */
internal fun scrollbarSeek(fraction: Float, thumbSize: Float, metrics: ScrollMetrics): Float {
    val free = 1f - thumbSize
    val progress = if (free <= 0f) 0f else ((fraction - thumbSize / 2) / free).coerceIn(0f, 1f)
    return progress * (metrics.total - metrics.visible).coerceAtLeast(0f)
}

/** The index of the tick closest to [fraction], when it lies within [slop]; null otherwise. */
internal fun nearestTick(ticks: List<Float>, fraction: Float, slop: Float): Int? =
    ticks.indices.minByOrNull { abs(ticks[it] - fraction) }?.takeIf { abs(ticks[it] - fraction) <= slop }

/** Long chats get the bar even with few markers: it still shows where the reader is. */
private const val SCROLLBAR_MIN_ITEMS = 30

/** Shown on a scrollable list with something to mark, or on a long one. */
internal fun chatScrollbarVisible(scrollable: Boolean, markers: Int, items: Int): Boolean =
    scrollable && (markers >= 2 || items >= SCROLLBAR_MIN_ITEMS)

// ---- Scrollbar ----------------------------------------------------------------------------

/** The lane the bar sits in: the list's end padding, so it never covers a message. */
internal val CHAT_SCROLLBAR_LANE = 20.dp
private val SCROLLBAR_THUMB_WIDTH = 4.dp
private val SCROLLBAR_THUMB_MIN = 24.dp
private val SCROLLBAR_TICK_WIDTH = 10.dp
private val SCROLLBAR_TICK_HEIGHT = 3.dp

/** How far from a tick a tap still jumps to it. */
private val SCROLLBAR_TAP_SLOP = 16.dp

@Composable
internal fun timelineMarkerColor(kind: TimelineMarkerKind): Color {
    val colors = MaterialTheme.colorScheme
    return when (kind) {
        TimelineMarkerKind.USER -> colors.primary
        TimelineMarkerKind.ERROR -> colors.error
        TimelineMarkerKind.QUESTION -> colors.tertiary
        // Primary now marks the user's own messages; a change takes the diff's green.
        TimelineMarkerKind.EDIT -> diffAddedContent()
        TimelineMarkerKind.PLAN -> colors.secondary
    }
}

/**
 * A thin scrollbar with a coloured tick per marker. It is always there while it is [chatScrollbarVisible]
 * and never moves the list's content. A drag scrolls the list, a tap near a tick jumps to its item.
 * TalkBack skips it: the chat history is the accessible way to the same places.
 *
 * [listIndex] turns a marker's index in the shown items into its index in the lazy list.
 */
@Composable
internal fun ChatScrollbar(
    listState: LazyListState,
    markers: List<TimelineMarker>,
    listIndex: (TimelineMarker) -> Int,
    onJump: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val currentMarkers by rememberUpdatedState(markers)
    val currentListIndex by rememberUpdatedState(listIndex)
    val currentJump by rememberUpdatedState(onJump)
    val colors = TimelineMarkerKind.entries.associateWith { timelineMarkerColor(it) }
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.6f else 0.35f)
    fun metrics() = scrollMetrics(listState.layoutInfo, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    fun seek(y: Float, height: Int, minThumb: Float) {
        if (height <= 0) return
        val now = metrics()
        val thumb = scrollbarThumb(now, minThumb / height) ?: return
        val target = scrollbarSeek(y / height, thumb.size, now)
        val atEnd = target >= now.total - now.visible - 0.001f
        val index = floor(target).toInt().coerceIn(0, (now.total - 1).coerceAtLeast(0))
        val sizes = listState.layoutInfo.visibleItemsInfo
        val itemSize = sizes.firstOrNull { it.index == index }?.size ?: sizes.map { it.size }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        scope.launch {
            if (atEnd) {
                listState.scrollToItem((now.total - 1).coerceAtLeast(0))
                listState.scroll { while (listState.canScrollForward) if (scrollBy(10_000f) == 0f) break }
            } else listState.scrollToItem(index, ((target - index) * itemSize).toInt())
        }
    }
    Canvas(
        modifier
            .width(CHAT_SCROLLBAR_LANE)
            .fillMaxHeight()
            .testTag("chatScrollbar")
            // TalkBack reaches the same places through the chat history.
            .clearAndSetSemantics {}
            .pointerInput(listState) {
                val minThumb = SCROLLBAR_THUMB_MIN.toPx()
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        seek(offset.y, size.height, minThumb)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    seek(change.position.y, size.height, minThumb)
                }
            }
            .pointerInput(listState) {
                val slop = SCROLLBAR_TAP_SLOP.toPx()
                detectTapGestures { offset ->
                    if (size.height <= 0) return@detectTapGestures
                    val total = listState.layoutInfo.totalItemsCount
                    val marked = currentMarkers
                    val ticks = marked.map { scrollbarTick(currentListIndex(it), total) }
                    nearestTick(ticks, offset.y / size.height, slop / size.height)?.let { currentJump(marked[it].itemId) }
                }
            }
    ) {
        val now = metrics()
        val tickWidth = SCROLLBAR_TICK_WIDTH.toPx()
        val tickHeight = SCROLLBAR_TICK_HEIGHT.toPx()
        for (marker in markers) {
            val y = (scrollbarTick(listIndex(marker), now.total) * size.height - tickHeight / 2)
                .coerceIn(0f, (size.height - tickHeight).coerceAtLeast(0f))
            drawRoundRect(
                colors.getValue(marker.kind),
                topLeft = Offset((size.width - tickWidth) / 2, y),
                size = Size(tickWidth, tickHeight),
                cornerRadius = CornerRadius(tickHeight / 2),
            )
        }
        val thumb = scrollbarThumb(now, SCROLLBAR_THUMB_MIN.toPx() / size.height.coerceAtLeast(1f)) ?: return@Canvas
        val thumbWidth = SCROLLBAR_THUMB_WIDTH.toPx()
        drawRoundRect(
            thumbColor,
            topLeft = Offset((size.width - thumbWidth) / 2, thumb.top * size.height),
            size = Size(thumbWidth, thumb.size * size.height),
            cornerRadius = CornerRadius(thumbWidth / 2),
        )
    }
}

// ---- Chat history -------------------------------------------------------------------------

internal fun timelineMarkerLabel(kind: TimelineMarkerKind): Int =
    when (kind) {
        TimelineMarkerKind.USER -> R.string.remote_panel_marker_user
        TimelineMarkerKind.ERROR -> R.string.remote_panel_marker_error
        TimelineMarkerKind.QUESTION -> R.string.remote_panel_marker_question
        TimelineMarkerKind.EDIT -> R.string.remote_panel_marker_edit
        TimelineMarkerKind.PLAN -> R.string.remote_panel_marker_plan
    }

/** The chat history as a bottom sheet; picking an entry jumps to it and closes the sheet. */
@Composable
internal fun ChatHistorySheet(entries: List<ChatHistoryEntry>, onDismiss: () -> Unit, onOpen: (itemId: String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().testTag("chatHistorySheet").navigationBarsPadding()) {
            Text(
                stringResource(R.string.remote_chat_history),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            ChatHistoryList(entries, Modifier.fillMaxWidth()) {
                onOpen(it)
                onDismiss()
            }
        }
    }
}

/** The chat history in the inspector beside a wide chat; it stays open while the user jumps around. */
@Composable
internal fun ChatHistoryPane(
    entries: List<ChatHistoryEntry>,
    onOpen: (itemId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    Surface(modifier.fillMaxSize().testTag("chatHistoryPane")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                FloatingSurface(shape = CircleShape) {
                    Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, Modifier.size(48.dp).testTag("chatHistoryClose")) {
                            Icon(Icons.Default.Close, stringResource(R.string.remote_chat_history_close))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.remote_chat_history),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            ChatHistoryList(entries, Modifier.weight(1f).fillMaxWidth(), onOpen)
        }
    }
}

@Composable
private fun ChatHistoryList(entries: List<ChatHistoryEntry>, modifier: Modifier, onOpen: (itemId: String) -> Unit) {
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.remote_chat_history_empty),
            modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag("chatHistoryEmpty"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier.testTag("chatHistoryList"), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(entries, key = { it.itemId }) { entry -> ChatHistoryRow(entry) { onOpen(entry.itemId) } }
    }
}

@Composable
private fun ChatHistoryRow(entry: ChatHistoryEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(timelineMarkerLabel(entry.kind))
    val own = entry.kind == TimelineMarkerKind.USER
    val text =
        when {
            entry.text == null -> label
            own -> entry.text
            else -> "$label: ${entry.text}"
        }
    val time =
        entry.timestamp?.let {
            (if (DateUtils.isToday(it)) DateFormat.getTimeFormat(context) else DateFormat.getDateFormat(context))
                .format(Date(it))
        }
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("chatHistoryRow")
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp)
                .background(timelineMarkerColor(entry.kind), CircleShape)
                // Other rows name their kind in the text; the user's own read out as such.
                .then(if (own && entry.text != null) Modifier.semantics { contentDescription = label } else Modifier)
        )
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (own) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (time != null)
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
