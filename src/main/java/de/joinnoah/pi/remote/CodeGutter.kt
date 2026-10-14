package de.joinnoah.pi.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import android.content.ClipData
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The zoom of each code pane's text, by pane name. It lasts for the session and is not stored. */
internal object CodeZoom {
    const val MIN = 0.6f
    const val MAX = 2.5f
    const val STEP = 1.1f
    const val FILES = "files"
    const val CHANGES = "changes"

    private val scales = mutableStateMapOf<String, Float>()

    /** The zoom of [pane]; 1 until it changes. */
    fun scale(pane: String): Float = scales[pane] ?: 1f

    /** Multiplies [pane]'s zoom by [factor], kept between [MIN] and [MAX]. A factor that is not usable changes nothing. */
    fun zoom(pane: String, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        scales[pane] = (scale(pane) * factor).coerceIn(MIN, MAX)
    }

    /** One zoom step in or out, as the keys take it. */
    fun step(pane: String, inward: Boolean) = zoom(pane, if (inward) STEP else 1f / STEP)

    /** Drops every zoom; the next read of a pane starts at 1. */
    fun reset() = scales.clear()
}

/**
 * Zooms a code pane's text: two fingers pinch it, and Ctrl with a trackpad or the wheel does too,
 * which is how a trackpad pinch arrives. Only a pinch consumes its events, so one finger scrolls.
 */
internal fun Modifier.codeZoomGestures(pane: String): Modifier =
    pointerInput(pane) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var distance: Float? = null
            var event: androidx.compose.ui.input.pointer.PointerEvent
            do {
                event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.filter { it.pressed }
                if (fingers.size >= 2) {
                    val now = (fingers[0].position - fingers[1].position).getDistance()
                    val before = distance
                    if (before != null && before > 0f && now > 0f) {
                        CodeZoom.zoom(pane, now / before)
                        fingers.forEach { it.consume() }
                    }
                    distance = now
                } else {
                    distance = null
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(pane) {
        // Ctrl with a scroll: a trackpad pinch, or the wheel. A scroll up (negative y) zooms in.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.first()
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed && change.scrollDelta.y != 0f) {
                    CodeZoom.zoom(pane, (1f - change.scrollDelta.y * 0.05f).coerceIn(0.5f, 2f))
                    change.consume()
                }
            }
        }
    }

/** Where each shown row's gutter sits in the window, so a drag over the gutters knows the line under the pointer. */
internal class GutterRows {
    private val places = HashMap<Int, Rect>()

    fun place(line: Int, bounds: Rect) {
        places[line] = bounds
    }

    fun remove(line: Int) {
        places.remove(line)
    }

    /** The window place of [line]'s gutter, or null when that row is not shown. */
    fun placeOf(line: Int): Rect? = places[line]

    /** The shown line under the window height [y]; past the shown rows, the nearest one. */
    fun lineAt(y: Float): Int? = lineAtY(places, y)
}

/**
 * The line whose [places] rectangle holds [y]. Between rows (a hunk header has no place) or past
 * the shown ones, the nearest row.
 */
internal fun lineAtY(places: Map<Int, Rect>, y: Float): Int? {
    if (places.isEmpty()) return null
    places.entries.firstOrNull { y >= it.value.top && y < it.value.bottom }?.let { return it.key }
    return places.minByOrNull { (_, rect) -> if (y < rect.top) rect.top - y else y - rect.bottom }?.key
}

/**
 * The gutter of one code row, the line number cell. A tap calls [onTap] with whether Shift was
 * held, and a Shift-tap extends the range. A press that moves beyond touch slop starts a range
 * with [onStart] and calls [onDrag] for every line the pointer crosses: a mouse or a stylus does
 * so at once, a finger after a long press. A finger that scrolls leaves the press alone.
 * [tapLabel] names the tap for accessibility services.
 */
@Composable
internal fun Modifier.codeGutter(
    line: Int,
    rows: GutterRows,
    tapLabel: String,
    onTap: (extend: Boolean) -> Unit,
    onStart: (Int) -> Unit,
    onDrag: (Int) -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    DisposableEffect(line, rows) { onDispose { rows.remove(line) } }
    return onGloballyPositioned { rows.place(line, it.boundsInRoot()) }
        .semantics { onClick(label = tapLabel) { tap(false); true } }
        .pointerInput(line, rows) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val shift = currentEvent.keyboardModifiers.isShiftPressed
                fun dragTo(change: PointerInputChange) {
                    val origin = rows.placeOf(line)?.topLeft ?: Offset.Zero
                    rows.lineAt((origin + change.position).y)?.let { drag(it) }
                }
                if (shift || down.type != PointerType.Touch) {
                    // A mouse, a stylus or Shift: a tap until the press moves, a drag after.
                    var dragging = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            if (!dragging) tap(shift)
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            if (!shift) start(line)
                        }
                        if (dragging) dragTo(change)
                    }
                } else {
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        start(line)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            dragTo(change)
                        }
                    } else if (currentEvent.changes.none { it.id == down.id && it.pressed }) {
                        // Lifted before the long press, not moved: a tap. The release is consumed,
                        // so the row's own click does not pick the line a second time.
                        currentEvent.changes.forEach { it.consume() }
                        tap(false)
                    }
                }
            }
        }
}

/** The shown row's hover state: the modifier that reports it, and whether a pointer is over the row now. */
@Composable
internal fun rememberRowHover(): Pair<Modifier, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return Modifier.hoverable(source) to hovered
}

/**
 * The gutter button that comments on a diff line. Shown on a row under a pointer and on the
 * selected row; otherwise its width stays empty, so the rows do not shift when it appears.
 */
@Composable
internal fun GutterCommentButton(shown: Boolean, label: String, onClick: () -> Unit) {
    if (!shown) {
        Spacer(Modifier.width(GUTTER_BUTTON_WIDTH))
        return
    }
    Box(
        Modifier.size(width = GUTTER_BUTTON_WIDTH, height = 16.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .testTag("changesGutterComment"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Comment,
            contentDescription = label,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private val GUTTER_BUTTON_WIDTH = 24.dp

/**
 * The range that a Shift-click or a drag over the gutter makes: from the anchor, which is the
 * selection's first line, to [line]. Without a selection the range starts at [line].
 */
internal fun LineSelection?.extendTo(line: Int): LineSelection = this?.copy(end = line) ?: LineSelection(line)

/** Every line of a file of [count] lines, or null for an empty one. */
internal fun allLines(count: Int): LineSelection? = if (count > 0) LineSelection(1, count) else null

/** The picked [lines] of a file, joined with newlines; the range is kept within the file. */
internal fun selectedFileText(lines: List<String>, selection: LineSelection): String {
    val first = selection.first.coerceAtLeast(1)
    val last = selection.last.coerceAtMost(lines.size)
    if (first > last) return ""
    return lines.subList(first - 1, last).joinToString("\n")
}

/**
 * The picked diff [lines] as text: the line text alone, or with each line's '+', '-' or ' ' first
 * ([prefixed]). Dividers between hunks are left out.
 */
internal fun selectedDiffText(lines: List<DiffLine>, selection: LineSelection, prefixed: Boolean): String {
    val first = selection.first.coerceAtLeast(1)
    val last = selection.last.coerceAtMost(lines.size)
    if (first > last) return ""
    return lines.subList(first - 1, last)
        .filterNot { it.kind == DiffKind.HUNK && it.text.isEmpty() }
        .joinToString("\n") { line -> if (prefixed) diffPrefix(line.kind) + line.text else line.text }
}

/**
 * The local state of a drag of picked code lines out of a pane: the chat takes it as [prompt],
 * the quoted lines with their path and numbers, as "Send lines" writes them. Other apps get the
 * plain text of the drag and never see this.
 */
internal class CodeLinesDrag(val prompt: String)

/** The drag data of picked lines: the plain [text], labelled [label], and the chat's [prompt] as local state. */
internal fun codeLinesTransfer(label: String, text: String, prompt: String): DragAndDropTransferData =
    DragAndDropTransferData(
        clipData = ClipData.newPlainText(label, text),
        localState = CodeLinesDrag(prompt),
        flags = DRAG_TEXT_FLAGS,
    )
