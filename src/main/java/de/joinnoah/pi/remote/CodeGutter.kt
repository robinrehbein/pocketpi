package de.joinnoah.pi.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import android.content.ClipData
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed as isKeyShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
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
        // Ctrl with a scroll: a trackpad pinch, or the wheel. A scroll up (negative y) zooms in. The
        // Initial pass and the consumed event keep the list below from scrolling with the same event.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.first()
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed && change.scrollDelta.y != 0f) {
                    CodeZoom.zoom(pane, (1f - change.scrollDelta.y * 0.05f).coerceIn(0.5f, 2f))
                    change.consume()
                }
            }
        }
    }

/**
 * Finds the line under a pointer for a drag over the gutters, from the list's own layout: only a
 * drag asks, so scrolling pays nothing. [lineOfItem] maps a list item's index to the line it shows,
 * or null for an item that is no pickable line (a notice, a divider); the pane keeps it current.
 */
internal class GutterRows(private val state: LazyListState) {
    /** The list's place in the window; the pane sets it with `onPlaced`. */
    var coordinates: LayoutCoordinates? = null
    var lineOfItem: (Int) -> Int? = { it + 1 }

    /** The shown line under the window height [y]; past the shown rows, the nearest one. */
    fun lineAt(y: Float): Int? {
        val top = coordinates?.takeIf { it.isAttached }?.localToWindow(Offset.Zero)?.y ?: return null
        val places = HashMap<Int, Rect>()
        val info = state.layoutInfo
        // Item offsets count from where the content padding ends; the viewport starts that far above.
        val contentTop = top - info.viewportStartOffset
        for (item in info.visibleItemsInfo) {
            val line = lineOfItem(item.index) ?: continue
            val itemTop = contentTop + item.offset
            places[line] = Rect(0f, itemTop, 1f, itemTop + item.size)
        }
        return lineAtY(places, y)
    }
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

/** The cell's place in the window, kept for the drag. */
private class CellPlace {
    var coordinates: LayoutCoordinates? = null
}

/** A press of the primary button, a finger or a stylus tip; a secondary, middle, back or forward button is not. */
private fun PointerButtons.isPrimaryPress(): Boolean =
    !isSecondaryPressed && !isTertiaryPressed && !isBackPressed && !isForwardPressed

/**
 * The gutter of one code row, the line number cell. A tap calls [onTap] with whether Shift was
 * held, and a Shift-tap extends the range. A press that moves beyond touch slop starts a range
 * with [onStart] (the line, and whether Shift extends the range already picked) and calls [onDrag]
 * for every line the pointer crosses: a mouse or a stylus does so at once, a finger after a long
 * press. A finger that scrolls leaves the press alone, and so does a press that is not of the
 * primary button, or that a context menu took. [tapLabel] names the tap for accessibility
 * services. The cell takes keyboard focus when [focusable], and Enter or Space taps then.
 */
@Composable
internal fun Modifier.codeGutter(
    line: Int,
    rows: GutterRows,
    tapLabel: String,
    onTap: (extend: Boolean) -> Unit,
    onStart: (line: Int, extend: Boolean) -> Unit,
    onDrag: (Int) -> Unit,
    focusable: Boolean = true,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val place = remember { CellPlace() }
    return this
        .semantics { onClick(label = tapLabel) { tap(false); true } }
        .then(
            if (!focusable) Modifier
            else
                Modifier.focusable().onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in SELECT_KEYS) {
                        tap(event.isKeyShiftPressed)
                        true
                    } else false
                }
        )
        .onPlaced { place.coordinates = it }
        .pointerInput(line, rows) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.isConsumed || !currentEvent.buttons.isPrimaryPress()) return@awaitEachGesture
                val shift = currentEvent.keyboardModifiers.isShiftPressed
                fun dragTo(change: PointerInputChange) {
                    val cell = place.coordinates?.takeIf { it.isAttached } ?: return
                    rows.lineAt(cell.localToWindow(change.position).y)?.let { drag(it) }
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
                            start(line, shift)
                        }
                        if (dragging) dragTo(change)
                    }
                } else {
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        start(line, false)
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

private val SELECT_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * The range a drag over the gutter makes: from [anchor], the line it started at or the start of the
 * range it extends, to [line]. The pane keeps the anchor itself, so a drag never reads a selection
 * that composition has not caught up with.
 */
internal fun dragRange(anchor: Int, line: Int): LineSelection = LineSelection(anchor, line)

/** The anchor of a drag that starts at [line]: the start of the [selection] when [extend], else [line]. */
internal fun dragAnchor(selection: LineSelection?, line: Int, extend: Boolean): Int =
    if (extend) selection?.anchor ?: line else line

/**
 * Gives [focus] the keyboard when a press lands in the list and nothing in it [hasFocus] yet, so
 * Ctrl+= and Ctrl+A work after a click on the code too. The press passes on untouched.
 */
internal fun Modifier.takeFocusOnPress(
    focus: FocusRequester,
    hasFocus: () -> Boolean,
): Modifier =
    pointerInput(focus) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && !hasFocus()) runCatching { focus.requestFocus() }
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

/** The width a hidden [GutterCommentButton] keeps, for a row that has none but sits among rows that do. */
@Composable
internal fun GutterCommentSpace() = Spacer(Modifier.width(GUTTER_BUTTON_WIDTH))

private val GUTTER_BUTTON_WIDTH = 24.dp

/**
 * The range that a Shift-click or a drag over the gutter makes: from the anchor, which is the
 * selection's first line, to [line]. Without a selection the range starts at [line].
 */
internal fun LineSelection?.extendTo(line: Int): LineSelection = this?.copy(end = line) ?: LineSelection(line)

/** Keeps picked lines across a rotation: nothing picked is an empty list. */
internal val LineSelectionSaver: androidx.compose.runtime.saveable.Saver<LineSelection?, Any> =
    androidx.compose.runtime.saveable.Saver(
        save = { picked -> if (picked == null) emptyList<Int>() else listOf(picked.anchor, picked.end ?: NO_END) },
        restore = { saved ->
            val numbers = (saved as? List<*>)?.filterIsInstance<Int>().orEmpty()
            if (numbers.size == 2) LineSelection(numbers[0], numbers[1].takeIf { it != NO_END }) else null
        },
    )

private const val NO_END = -1

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
