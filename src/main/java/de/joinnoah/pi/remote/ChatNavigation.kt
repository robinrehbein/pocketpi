package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
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
import java.text.NumberFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
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

/**
 * What the bubble beside a held scrollbar names: the kind of the tick nearest the thumb's middle,
 * when one lies within [slop] of it, or null for a plain position.
 */
internal fun scrollbarBubbleKind(ticks: List<ScrollbarTick>, thumb: ScrollbarThumb, slop: Float): TimelineMarkerKind? =
    nearestTick(ticks.map { it.fraction }, thumb.top + thumb.size / 2, slop)?.let { ticks[it].marker.kind }

/** How far through the list the thumb stands, from 0 at the top to 1 at the end. */
internal fun scrollbarProgress(thumb: ScrollbarThumb): Float =
    if (thumb.size >= 1f) 0f else (thumb.top / (1f - thumb.size)).coerceIn(0f, 1f)

/** Long chats get the bar even with few markers: it still shows where the reader is. */
private const val SCROLLBAR_MIN_ITEMS = 30

/** Shown on a scrollable list with something to mark, or on a long one. */
internal fun chatScrollbarVisible(scrollable: Boolean, markers: Int, items: Int): Boolean =
    scrollable && (markers >= 2 || items >= SCROLLBAR_MIN_ITEMS)

// ---- Scrollbar ----------------------------------------------------------------------------

/** The lane the bar sits in: the list's end padding, so it never covers a message. */
internal val CHAT_SCROLLBAR_LANE = 20.dp

/** The thumb and the ticks share this axis, measured from the lane's end. */
private val SCROLLBAR_AXIS_END = 7.dp

/** A hairline at rest, so the bar stays calm while reading; it grows while the user holds it. */
internal val SCROLLBAR_THUMB_WIDTH = 2.dp
internal val SCROLLBAR_THUMB_HELD_WIDTH = 8.dp
private val SCROLLBAR_THUMB_MIN = 24.dp
private val SCROLLBAR_TICK_WIDTH = 5.dp
private val SCROLLBAR_TICK_HEIGHT = 3.dp

/** How close to the thumb's middle, as a share of the track, a tick names the bubble. */
private const val SCROLLBAR_BUBBLE_SLOP = 0.04f

/** The gap between the bubble and the lane. */
private val SCROLLBAR_BUBBLE_GAP = 4.dp

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

/** A tick on the track at [fraction], for [marker]. */
internal data class ScrollbarTick(val marker: TimelineMarker, val fraction: Float)

/** Which kind wins a crowded spot on the track: errors first, the user's messages last. */
private val TICK_PRIORITY =
    listOf(
        TimelineMarkerKind.ERROR,
        TimelineMarkerKind.QUESTION,
        TimelineMarkerKind.PLAN,
        TimelineMarkerKind.EDIT,
        TimelineMarkerKind.USER,
    )

/**
 * At most one tick per bucket of the track, so a long chat keeps ticks along its whole length
 * instead of only its latest ones. The more important kind wins a bucket; among equals the later one.
 */
internal fun thinTicks(ticks: List<ScrollbarTick>, buckets: Int): List<ScrollbarTick> {
    if (buckets <= 0) return emptyList()
    val kept = HashMap<Int, ScrollbarTick>()
    for (tick in ticks) {
        val bucket = (tick.fraction * buckets).toInt().coerceIn(0, buckets - 1)
        val current = kept[bucket]
        if (current == null || TICK_PRIORITY.indexOf(tick.marker.kind) <= TICK_PRIORITY.indexOf(current.marker.kind))
            kept[bucket] = tick
    }
    return kept.values.sortedBy { it.fraction }
}

/** How much taller than the thumb the area kept free of the system back gesture is, per side. */
private val SCROLLBAR_GESTURE_MARGIN = 16.dp

/** Android honours at most 200 dp of gesture exclusion per screen edge. */
private val SCROLLBAR_GESTURE_MAX = 150.dp

/**
 * A thin scrollbar with a coloured tick per marker. It is always there while it is [chatScrollbarVisible]
 * and never moves the list's content. A drag scrolls the list, a tap near a tick jumps to its item.
 * While the user holds it, the thumb widens and a bubble beside the lane names the nearest marker
 * or the position; only that bubble reaches over the messages. TalkBack skips all of it: the chat
 * history is the accessible way to the same places.
 *
 * [listIndex] turns a marker's index in the shown items into its index in the lazy list. [onSeek]
 * reports each drag position and the drag's end, with whether the list then stands at its end.
 */
@Composable
internal fun ChatScrollbar(
    listState: LazyListState,
    markers: List<TimelineMarker>,
    listIndex: (TimelineMarker) -> Int,
    onJump: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onSeek: (atEnd: Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    val held = dragging || touching
    val currentMarkers by rememberUpdatedState(markers)
    val currentListIndex by rememberUpdatedState(listIndex)
    val currentJump by rememberUpdatedState(onJump)
    val currentSeek by rememberUpdatedState(onSeek)
    val colors = TimelineMarkerKind.entries.associateWith { timelineMarkerColor(it) }
    // Reduced motion switches between the two looks without the grow.
    val motion = ValueAnimator.areAnimatorsEnabled()
    val thumbWidth by animateDpAsState(
        if (held) SCROLLBAR_THUMB_HELD_WIDTH else SCROLLBAR_THUMB_WIDTH,
        if (motion) tween(150) else snap(),
        label = "scrollbarThumbWidth",
    )
    val thumbAlpha by animateFloatAsState(if (held) 0.6f else 0.35f, if (motion) tween(150) else snap(), label = "scrollbarThumbAlpha")
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = thumbAlpha)
    val density = LocalDensity.current
    val minThumb = with(density) { SCROLLBAR_THUMB_MIN.toPx() }
    val tickHeight = with(density) { SCROLLBAR_TICK_HEIGHT.toPx() }
    fun metrics() = scrollMetrics(listState.layoutInfo, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    fun ticks(height: Float): List<ScrollbarTick> {
        val total = listState.layoutInfo.totalItemsCount
        return thinTicks(
            currentMarkers.map { ScrollbarTick(it, scrollbarTick(currentListIndex(it), total)) },
            (height / tickHeight).toInt(),
        )
    }
    fun seek(y: Float, height: Int) {
        if (height <= 0) return
        val now = metrics()
        val thumb = scrollbarThumb(now, minThumb / height) ?: return
        val target = scrollbarSeek(y / height, thumb.size, now)
        val atEnd = target >= now.total - now.visible - 0.001f
        val index = floor(target).toInt().coerceIn(0, (now.total - 1).coerceAtLeast(0))
        currentSeek(atEnd)
        scope.launch {
            if (atEnd) {
                listState.scrollToItem((now.total - 1).coerceAtLeast(0))
                listState.scroll { while (listState.canScrollForward) if (scrollBy(10_000f) == 0f) break }
            } else {
                // Measure the target first, so the offset inside it is exact and the thumb holds still.
                listState.scrollToItem(index)
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                if (size > 0) listState.scrollBy((target - index) * size)
            }
        }
    }
    var laneHeight by remember { mutableIntStateOf(0) }
    Box(modifier.width(CHAT_SCROLLBAR_LANE).fillMaxHeight().onSizeChanged { laneHeight = it.height }) {
        Box(
            Modifier.matchParentSize()
                .testTag("chatScrollbar")
                // TalkBack reaches the same places through the chat history.
                .clearAndSetSemantics {}
                // Watches without consuming, so the bar looks held from the first touch on.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touching = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touching = false
                        }
                    }
                }
                .pointerInput(listState) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            seek(offset.y, size.height)
                        },
                        onDragEnd = {
                            dragging = false
                            currentSeek(!listState.canScrollForward)
                        },
                        onDragCancel = {
                            dragging = false
                            currentSeek(!listState.canScrollForward)
                        },
                    ) { change, _ ->
                        change.consume()
                        seek(change.position.y, size.height)
                    }
                }
                .pointerInput(listState) {
                    val slop = SCROLLBAR_TAP_SLOP.toPx()
                    detectTapGestures { offset ->
                        if (size.height <= 0) return@detectTapGestures
                        val shown = ticks(size.height.toFloat())
                        nearestTick(shown.map { it.fraction }, offset.y / size.height, slop / size.height)
                            ?.let { currentJump(shown[it].marker.itemId) }
                    }
                }
        ) {
            Canvas(Modifier.matchParentSize()) {
                val tickWidth = SCROLLBAR_TICK_WIDTH.toPx()
                val axis = size.width - SCROLLBAR_AXIS_END.toPx()
                for (tick in ticks(size.height)) {
                    val y = (tick.fraction * size.height - tickHeight / 2)
                        .coerceIn(0f, (size.height - tickHeight).coerceAtLeast(0f))
                    drawRoundRect(
                        colors.getValue(tick.marker.kind),
                        topLeft = Offset(axis - tickWidth / 2, y),
                        size = Size(tickWidth, tickHeight),
                        cornerRadius = CornerRadius(tickHeight / 2),
                    )
                }
            }
            // On a phone the lane is the screen edge: keep the back gesture off the thumb, so dragging
            // it scrolls. Only around the thumb, as Android limits how much of an edge an app may claim.
            Box(
                Modifier.fillMaxWidth()
                    .layout { measurable, constraints ->
                        val height = constraints.maxHeight
                        val thumb = if (height > 0) scrollbarThumb(metrics(), minThumb / height) else null
                        val area =
                            if (thumb == null) 0
                            else (thumb.size * height + 2 * SCROLLBAR_GESTURE_MARGIN.toPx()).roundToInt()
                                .coerceAtMost(SCROLLBAR_GESTURE_MAX.roundToPx())
                        val centre = thumb?.let { (it.top + it.size / 2) * height } ?: 0f
                        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, area))
                        layout(constraints.maxWidth, height) {
                            placeable.place(0, (centre - area / 2f).roundToInt().coerceIn(0, (height - area).coerceAtLeast(0)))
                        }
                    }
                    .systemGestureExclusion()
            )
        }
        // The thumb is a column of its own width on the ticks' axis; it takes no input.
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(end = SCROLLBAR_AXIS_END - thumbWidth / 2)
                .width(thumbWidth)
                .fillMaxHeight()
                .testTag("chatScrollbarThumb")
                .drawBehind {
                    val thumb = scrollbarThumb(metrics(), minThumb / size.height.coerceAtLeast(1f)) ?: return@drawBehind
                    drawRoundRect(
                        thumbColor,
                        topLeft = Offset(0f, thumb.top * size.height),
                        size = Size(size.width, thumb.size * size.height),
                        cornerRadius = CornerRadius(size.width / 2),
                    )
                }
        )
        if (held) ScrollbarBubble(laneHeight, minThumb, ::metrics, ::ticks)
    }
}

/**
 * The held bar's bubble, to the start of the lane and centred on the thumb. It names the nearest
 * marker's kind or the position in percent, and stays out of the accessibility tree like the bar.
 */
@Composable
private fun ScrollbarBubble(
    laneHeight: Int,
    minThumb: Float,
    metrics: () -> ScrollMetrics,
    ticks: (height: Float) -> List<ScrollbarTick>,
) {
    val thumb by remember(laneHeight) {
        derivedStateOf { if (laneHeight > 0) scrollbarThumb(metrics(), minThumb / laneHeight) else null }
    }
    val current = thumb ?: return
    val kind = remember(current, laneHeight) { scrollbarBubbleKind(ticks(laneHeight.toFloat()), current, SCROLLBAR_BUBBLE_SLOP) }
    val locale = LocalConfiguration.current.locales[0]
    val text =
        if (kind != null) stringResource(timelineMarkerLabel(kind))
        else NumberFormat.getPercentInstance(locale).format(scrollbarProgress(current).toDouble())
    Box(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            val centre = (current.top + current.size / 2) * constraints.maxHeight
            layout(0, 0) {
                placeable.place(
                    -placeable.width - SCROLLBAR_BUBBLE_GAP.roundToPx(),
                    (centre - placeable.height / 2f).roundToInt()
                        .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0)),
                )
            }
        }
            .testTag("chatScrollbarBubble")
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
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
internal fun ChatHistorySheet(
    entries: List<ChatHistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (itemId: String) -> Unit,
    initialIndex: Int = 0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().testTag("chatHistorySheet").navigationBarsPadding()) {
            Text(
                stringResource(R.string.remote_chat_history),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            ChatHistoryList(entries, Modifier.fillMaxWidth(), initialIndex) {
                onOpen(it)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
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
    initialIndex: Int = 0,
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
            ChatHistoryList(entries, Modifier.weight(1f).fillMaxWidth(), initialIndex, onOpen)
        }
    }
}

@Composable
private fun ChatHistoryList(
    entries: List<ChatHistoryEntry>,
    modifier: Modifier,
    initialIndex: Int,
    onOpen: (itemId: String) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.remote_chat_history_empty),
            modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag("chatHistoryEmpty"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Opens where the chat stands, so the nearby entries are at hand in a long chat.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceIn(0, entries.lastIndex))
    LazyColumn(modifier.testTag("chatHistoryList"), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
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
            val time = DateFormat.getTimeFormat(context).format(Date(it))
            if (DateUtils.isToday(it)) time else "${DateFormat.getDateFormat(context).format(Date(it))} $time"
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
