package de.joinnoah.pi.remote

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BrowserPaneActions(
    val open: () -> Boolean = { false }, val acquire: () -> Boolean = { false }, val refresh: () -> Boolean = { false },
    val newTab: (String) -> Boolean = { false }, val navigate: (String) -> Boolean = { false },
    val selectTab: (String) -> Unit = {}, val closeTab: (String) -> Boolean = { false },
    val handoff: (String) -> Boolean = { false }, val resize: (Int, Int, Double, String) -> Boolean = { _, _, _, _ -> false },
    val input: (BrowserInput) -> Boolean = { false }, val visible: (Boolean) -> Unit = {},
    /** The fail-safe release while a key or button is held: gives up control, never taking it back. */
    val dropControl: () -> Unit = {},
    /** `browser.history`: back, forward, reload or stop (see [BrowserClient.history]). */
    val history: (String) -> Boolean = { false },
)

/** How long the hint that a tab has no earlier or later page stays. */
internal const val BROWSER_HISTORY_HINT_MS = 3000L

/**
 * Below this width a browser pane with a failure notice moves its header buttons into the "more"
 * menu (see [remoteViewNoticeFirst]). It covers the narrow panes of about 312 to 336dp, a side
 * inspector or a small window; wider panes keep their header as it is.
 */
internal val BROWSER_NOTICE_FIRST_WIDTH = 344.dp

private data class PendingBrowserInput(val input: BrowserInput, val submittedText: String? = null, val repeatRelease: Boolean = false)
private data class BrowserFlight(val item: PendingBrowserInput, val baseline: BrowserInputReceipt?)
private class BrowserFrameKey(private val image: BrowserImage?) {
    override fun equals(other: Any?): Boolean = other is BrowserFrameKey && image === other.image
    override fun hashCode(): Int = System.identityHashCode(image)
}

/**
 * The shortest pause between two wheel inputs. Wheel deltas that arrive meanwhile add up in the
 * queued wheel, so a long finger drag stays far below the relay's per-device message limit.
 */
private const val BROWSER_WHEEL_INTERVAL_MS = 50L

/**
 * Inputs the pane keeps queued, in flight and held at once without host limits; the host's
 * `maxInFlightInputs` (`session.limits.v1`) can only lower it (see [inFlightInputBound]).
 */
private const val BROWSER_MAX_PENDING_INPUTS = 32

private enum class BrowserTouchMode {
    /** No finger gesture, or one that was given up (a second finger, a refused press). */
    NONE,

    /** The finger is down and has not moved past the touch slop: a tap or a long press so far. */
    PENDING,

    /** The finger moved before the long-press timeout: the page scrolls with it. */
    SCROLL,

    /** The finger moved after a long press: a left-button mouse drag. */
    DRAG,
}

/** The one finger a touch gesture on the viewport follows, in view pixels and CSS pixels. */
private class BrowserTouch {
    /** State, so the pane sees a gesture begin and end (a resize waits for its end). */
    var mode by mutableStateOf(BrowserTouchMode.NONE)
    var pointerId = -1
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var downTime = 0L
    /** Where the finger went down, as CSS coordinates: the tap, long press and wheel position. */
    var point: Pair<Double, Double>? = null
    /** The image fit and CSS size at the down, so one scroll keeps one scale. */
    var fit: BrowserImageFit? = null
    var cssWidth = 0
    var cssHeight = 0

    fun reset() {
        mode = BrowserTouchMode.NONE
        pointerId = -1
        point = null
        fit = null
    }
}

/** A finger or a stylus on a touchscreen; a mouse or a touchpad keeps its mouse semantics. */
private fun browserTouchEvent(event: MotionEvent): Boolean {
    val tool = event.getToolType(event.actionIndex)
    return (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS) && !event.isFromSource(InputDevice.SOURCE_MOUSE)
}

private fun browserInputId(input: BrowserInput): String? = when (input) {
    is BrowserInput.Pointer -> "pointer:${input.button}"
    is BrowserInput.Key -> "key:${input.code}"
    else -> null
}

@Composable
private fun browserParticipantLabel(participant: BrowserOwner): String = remoteParticipantLabel(participant.kind, participant.id)

@Composable
private fun browserStripKeys(): List<RemoteKey> {
    val navigation = remoteNavigationKeys().associateBy { it.id }
    return BROWSER_STRIP_KEYS.map { key ->
    navigation[key] ?: when (key) {
        "Escape" -> RemoteKey(key, stringResource(R.string.remote_view_key_escape), stringResource(R.string.remote_view_key_escape_description))
        "Tab" -> RemoteKey(key, stringResource(R.string.remote_view_key_tab))
        "Enter" -> RemoteKey(key, stringResource(R.string.remote_view_key_enter))
        "Delete" -> RemoteKey(key, stringResource(R.string.remote_view_key_delete), stringResource(R.string.remote_view_key_delete_description))
        "Home" -> RemoteKey(key, stringResource(R.string.remote_view_key_home))
        "End" -> RemoteKey(key, stringResource(R.string.remote_view_key_end))
        "PageUp" -> RemoteKey(key, stringResource(R.string.remote_view_key_page_up), stringResource(R.string.remote_view_key_page_up_description))
        "PageDown" -> RemoteKey(key, stringResource(R.string.remote_view_key_page_down), stringResource(R.string.remote_view_key_page_down_description))
        else -> error("Unknown strip key $key")
    }
    }
}

/**
 * The remote browser, and the same pane for a dev-server preview ([BrowserView.previewPort]).
 *
 * Touch input follows phone conventions: a tap clicks, a one-finger drag scrolls the page (wheel
 * input, the content follows the finger), and a long press clicks the right button or, followed
 * by a drag, drags with the left button (text selection, drag and drop). A mouse or touchpad keeps
 * plain mouse semantics.
 *
 * Hardware Esc never closes the pane; Back and the close button do. With the viewport focused and
 * in control it goes to the page; otherwise it does nothing, or, beside the chat with focus
 * outside the pane, runs the chat's own Esc steps (see [RemoteViewPane]). The preview's own window
 * swallows it too ([PreviewDialog]).
 *
 * A compact pane keeps its text bar (one line) and moves the tab row into a header menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BrowserPane(view: BrowserView?, actions: BrowserPaneActions, onClose: () -> Unit, onUnsentChanged: (Boolean) -> Unit = {}, onHide: (() -> Unit)? = null, decodeImage: suspend (BrowserImage) -> android.graphics.Bitmap? = { decodeBrowserImage(it) }) {
    val active = LocalTerminalKeyboardActive.current
    val focusOwner = LocalTerminalInputFocus.current
    val focusToken = remember { Any() }
    val focusRequester = remember { FocusRequester() }
    val held = remember { mutableStateListOf<String>() }
    val heldKeys = remember { mutableMapOf<String, BrowserInput.Key>() }
    val releasing = remember { mutableSetOf<String>() }
    var pointerPosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pointerButton by remember { mutableStateOf("left") }
    var heldRevision by remember { mutableStateOf<Long?>(null) }
    val touch = remember { BrowserTouch() }
    val lastWheelAt = remember { longArrayOf(0L) }
    val disallowIntercept = remember { RequestDisallowInterceptTouchEvent() }
    val touchScope = rememberCoroutineScope()
    var longPressFeedback by remember { mutableStateOf<Job?>(null) }
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val latestActions by rememberUpdatedState(actions)
    val latestUnsent by rememberUpdatedState(onUnsentChanged)
    DisposableEffect(active) {
        if (active) latestActions.visible(true)
        onDispose { latestActions.visible(false); focusOwner?.update(focusToken, false) }
    }
    var url by rememberSaveable(view?.sessionId, view?.previewPort) { mutableStateOf("") }
    var urlDirty by rememberSaveable(view?.sessionId, view?.previewPort) { mutableStateOf(false) }
    var draft by rememberSaveable(view?.sessionId, view?.previewPort) { mutableStateOf("") }
    var textSendRefused by remember(view?.sessionId) { mutableStateOf(false) }
    // The fail-safe gave up control under a held key or button; taking control again clears it.
    var controlDropped by remember(view?.sessionId) { mutableStateOf(false) }
    LaunchedEffect(view?.ownsControl) { if (view?.ownsControl == true) controlDropped = false }
    val queue = remember(view?.sessionId) { mutableStateListOf<PendingBrowserInput>() }
    var flight by remember(view?.sessionId) { mutableStateOf<BrowserFlight?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var requestedSize by remember(view?.sessionId, view?.generation, view?.selectedTabId) { mutableStateOf<Triple<Int, Int, Double>?>(null) }
    var resizeRevision by remember(view?.sessionId, view?.generation, view?.selectedTabId) { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current.density
    val selected = view?.tabs?.firstOrNull { it.id == view.selectedTabId }
    var goRefused by remember(view?.sessionId, view?.previewPort, view?.selectedTabId, view?.ownsControl) { mutableStateOf(false) }
    LaunchedEffect(selected?.id, selected?.url) { if (!urlDirty) url = selected?.url.orEmpty() }
    LaunchedEffect(view?.generation, view?.ownsControl, active, view?.sessionId, view?.documentEpoch) {
        queue.clear(); flight = null; held.clear(); heldKeys.clear(); releasing.clear(); pointerPosition = null; heldRevision = null; touch.reset()
    }
    val latestView by rememberUpdatedState(view)
    LaunchedEffect(view?.receipt, view?.inputPending, view?.ownsControl, flight, queue.size) {
        val f = flight
        if (f != null && view?.inputPending == false && view.receipt != null && view.receipt != f.baseline) {
            if (view.receipt.status == "accepted" && view.ownsControl) {
                if (f.item.submittedText != null && draft == f.item.submittedText) draft = ""
                val released = when (val input = f.item.input) {
                    is BrowserInput.Key -> if (input.action == "up" && !f.item.repeatRelease) "key:${input.code}" else null
                    is BrowserInput.Pointer -> if (input.action == "up") "pointer:${input.button}" else null
                    else -> null
                }
                if (released != null) { held.remove(released); heldKeys.remove(released); releasing.remove(released); if (held.isEmpty()) heldRevision = null }
                flight = null
            } else { queue.clear(); flight = null; held.clear(); heldKeys.clear(); releasing.clear(); pointerPosition = null; heldRevision = null }
        }
        if (flight == null && view?.ownsControl == true && !view.inputPending && active && queue.isNotEmpty()) {
            if (queue[0].input is BrowserInput.Wheel) {
                // Deltas that arrive during the pause add up in this wheel (see enqueue).
                val wait = lastWheelAt[0] + BROWSER_WHEEL_INTERVAL_MS - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait)
            }
            val current = latestView
            if (flight != null || queue.isEmpty() || current?.ownsControl != true || current.inputPending) return@LaunchedEffect
            val next = queue.removeAt(0)
            if (next.input is BrowserInput.Wheel) lastWheelAt[0] = SystemClock.uptimeMillis()
            flight = BrowserFlight(next, current.receipt)
            if (!latestActions.input(next.input)) {
                queue.clear(); flight = null
                if (next.submittedText != null) textSendRefused = true
                if (held.isNotEmpty()) { latestActions.dropControl(); controlDropped = true }
            }
        }
    }
    // Read here, not only in the scaffold's content: a change must recompose this scope to report it.
    val unsent = urlDirty || draft.isNotEmpty() || queue.isNotEmpty() || flight != null
    SideEffect { latestUnsent(unsent) }
    DisposableEffect(Unit) { onDispose { latestUnsent(false) } }
    // A finger on the viewport counts as held: the soft keyboard hiding at its touch must not
    // resize the page under the tap it is about to send.
    val touching = touch.mode != BrowserTouchMode.NONE
    // The viewport sides (dp, CSS pixels) and scale the pane may ask for: the host's limits where it
    // sent them, within the built-in bounds (see [browserViewportBounds]).
    val bounds = browserViewportBounds(view?.limits?.viewport)
    val inputBound = inFlightInputBound(view?.limits?.maxInFlightInputs, BROWSER_MAX_PENDING_INPUTS)
    LaunchedEffect(bounds, size, density, view?.ownsControl, view?.generation, view?.selectedTabId, active, held.size, touching) {
        // A viewport below the clamp's floor (a pane squeezed to a few dp) asks for nothing: the page
        // would reflow to a size nobody sees. The last requested size stays until it grows again.
        val measuredWidth = (size.width / density).toInt()
        val measuredHeight = (size.height / density).toInt()
        if (active && view?.ownsControl == true && held.isEmpty() && !touching && measuredWidth >= bounds.minWidth && measuredHeight >= bounds.minHeight) {
            val width = measuredWidth.coerceIn(bounds.minWidth, bounds.maxWidth)
            val height = measuredHeight.coerceIn(bounds.minHeight, bounds.maxHeight)
            val scale = density.toDouble().coerceIn(bounds.minScale, bounds.maxScale)
            val wanted = Triple(width, height, scale)
            if (wanted != requestedSize && latestActions.resize(width, height, scale, if (width >= height) "landscape" else "portrait")) {
                requestedSize = wanted; resizeRevision = view.viewportRevision
            }
        }
    }
    LaunchedEffect(view?.viewportRevision) {
        if (resizeRevision != null && resizeRevision != view?.viewportRevision) resizeRevision = null
        if (held.isNotEmpty() && heldRevision != null && heldRevision != view?.viewportRevision) { latestActions.dropControl(); controlDropped = true }
    }
    val image = view?.image?.takeIf { it.viewportRevision == view.viewportRevision }
    var decoded by remember(view?.sessionId, view?.browserId, view?.generation, view?.selectedTabId, view?.viewportRevision, view?.documentEpoch, view?.previewPort) { mutableStateOf<Pair<BrowserImage, android.graphics.Bitmap>?>(null) }
    var failedImage by remember(view?.sessionId, view?.browserId, view?.generation, view?.selectedTabId, view?.viewportRevision, view?.documentEpoch, view?.previewPort) { mutableStateOf<BrowserImage?>(null, referentialEqualityPolicy()) }
    LaunchedEffect(BrowserFrameKey(image), view?.sessionId, view?.browserId, view?.generation, view?.selectedTabId, view?.viewportRevision, view?.documentEpoch, view?.previewPort) {
        if (image != null) {
            var candidate: android.graphics.Bitmap? = null
            var transferred = false
            try {
                val result = withContext(Dispatchers.Default) { decodeImage(image).also { candidate = it } }
                if (result == null) { decoded = null; failedImage = image }
                else { decoded = image to result; failedImage = null; transferred = true }
            } finally {
                if (!transferred) candidate?.recycle()
            }
        }
    }
    val bitmap = decoded?.second?.takeIf { image != null || view?.ownsControl == true }
    val canWheel = active && view?.ownsControl == true && image != null && decoded?.first === image && resizeRevision == null
    val canInput = canWheel && view?.moving != true
    val canTextKeys = active && view?.ownsControl == true && image != null && bitmap != null && resizeRevision == null && view.moving != true
    val canRelease = active && view?.ownsControl == true && (heldRevision == null || heldRevision == view.viewportRevision)
    val latestCanRelease by rememberUpdatedState(canRelease)
    val latestCanInput by rememberUpdatedState(canInput)
    val latestCanWheel by rememberUpdatedState(canWheel)
    val latestCanTextKeys by rememberUpdatedState(canTextKeys)
    val latestImage by rememberUpdatedState(image)
    fun enqueue(item: PendingBrowserInput): Boolean {
        val input = item.input
        val id = browserInputId(input)
        val down = when (input) { is BrowserInput.Pointer -> input.action == "down"; is BrowserInput.Key -> input.action == "down"; else -> false }
        val up = when (input) { is BrowserInput.Pointer -> input.action == "up"; is BrowserInput.Key -> input.action == "up"; else -> false }
        val allowed = when (input) {
            is BrowserInput.Pointer -> latestCanInput
            is BrowserInput.Wheel -> latestCanWheel
            is BrowserInput.Key, is BrowserInput.Text -> latestCanTextKeys
        }
        if ((!up && !allowed) || (up && !latestCanRelease)) return false
        if (up && (id == null || id !in held || id in releasing)) return false
        if (down && id in held) return false
        if (input is BrowserInput.Pointer && input.action == "move") {
            val last = queue.lastOrNull()?.input
            if (last is BrowserInput.Pointer && last.action == "move" && last.button == input.button) {
                queue[queue.lastIndex] = item
                return true
            }
        }
        if (input is BrowserInput.Wheel) {
            // While one input is in flight, wheel deltas add up in the queued wheel at the latest position.
            val last = queue.lastOrNull()?.input
            if (last is BrowserInput.Wheel) {
                val deltaX = last.deltaX + input.deltaX
                val deltaY = last.deltaY + input.deltaY
                if (abs(deltaX) <= BROWSER_WHEEL_MAX_DELTA && abs(deltaY) <= BROWSER_WHEEL_MAX_DELTA) {
                    queue[queue.lastIndex] = PendingBrowserInput(input.copy(deltaX = deltaX, deltaY = deltaY))
                    return true
                }
            }
        }
        val occupied = queue.size + (if (flight == null) 0 else 1) + held.size
        if (!up && occupied + (if (down) 2 else 1) > inputBound) return false
        if (down && id != null) {
            if (held.isEmpty()) heldRevision = latestView?.viewportRevision
            held.add(id)
            if (input is BrowserInput.Key) heldKeys[id] = input
        }
        if (up && id != null) releasing.add(id)
        queue.add(item); return true
    }
    val latestEnqueue by rememberUpdatedState<(PendingBrowserInput) -> Boolean>({ enqueue(it) })
    // A press admits its down and its up together, so a refusal can never leave the key or button down.
    fun press(down: BrowserInput, up: BrowserInput): Boolean {
        val id = browserInputId(down) ?: return false
        val allowed = if (down is BrowserInput.Pointer) latestCanInput else latestCanTextKeys
        if (!allowed || !latestCanRelease || id in held) return false
        val occupied = queue.size + (if (flight == null) 0 else 1) + held.size
        if (occupied + 2 > inputBound) return false
        if (held.isEmpty()) heldRevision = latestView?.viewportRevision
        held.add(id)
        if (down is BrowserInput.Key) heldKeys[id] = down
        releasing.add(id)
        queue.add(PendingBrowserInput(down)); queue.add(PendingBrowserInput(up))
        return true
    }
    val latestPress by rememberUpdatedState<(BrowserInput, BrowserInput) -> Boolean>({ down, up -> press(down, up) })
    fun repeatKey(input: BrowserInput.Key): Boolean {
        val id = "key:${input.code}"
        val original = heldKeys[id] ?: return false
        if (!latestCanTextKeys || !latestCanRelease || id in releasing) return false
        // The host rejects duplicate key-down transitions. Admit the entire pair
        // atomically while retaining the reserved slot for the final release.
        val occupied = queue.size + (if (flight == null) 0 else 1) + held.size
        if (occupied + 2 > inputBound) return false
        queue.add(PendingBrowserInput(original.copy(action = "up"), repeatRelease = true))
        val repeated = input.copy(key = original.key)
        queue.add(PendingBrowserInput(repeated))
        heldKeys[id] = repeated
        return true
    }
    val latestRepeatKey by rememberUpdatedState<(BrowserInput.Key) -> Boolean>({ repeatKey(it) })

    // Touch gestures (see BrowserPane's documentation).
    fun currentPoint(x: Float, y: Float): Pair<Double, Double>? {
        val frame = latestImage ?: return null
        val fit = browserImageFit(size.width.toFloat(), size.height.toFloat(), frame.width, frame.height) ?: return null
        return browserImagePoint(fit, x, y, frame.cssWidth, frame.cssHeight)
    }
    fun scrollTo(x: Float, y: Float) {
        val fit = touch.fit ?: return
        val at = touch.point ?: return
        val delta = browserImageDelta(fit, x - touch.lastX, y - touch.lastY, touch.cssWidth, touch.cssHeight) ?: return
        if (delta.first == 0.0 && delta.second == 0.0) return
        // A refused wheel (a full queue) keeps its distance for the next move.
        if (latestEnqueue(PendingBrowserInput(browserTouchWheel(at.first, at.second, delta.first, delta.second)))) {
            touch.lastX = x; touch.lastY = y
        }
    }
    fun dragTo(x: Float, y: Float) {
        val point = currentPoint(x, y) ?: return
        pointerPosition = point
        latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(point.first, point.second, "move", pointerButton)))
    }
    fun startDrag(x: Float, y: Float) {
        val at = touch.point ?: return
        if (latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(at.first, at.second, "down", "left")))) {
            touch.mode = BrowserTouchMode.DRAG
            pointerPosition = at; pointerButton = "left"
            disallowIntercept(true)
            dragTo(x, y)
        } else touch.mode = BrowserTouchMode.NONE
    }
    fun endDrag() {
        val at = pointerPosition ?: touch.point ?: return
        latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(at.first, at.second, "up", pointerButton)))
        pointerPosition = null
    }
    fun onTouch(event: MotionEvent): Boolean {
        val longPress = viewConfiguration.longPressTimeoutMillis
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFeedback?.cancel()
                touch.reset()
                val frame = latestImage
                val fit = frame?.let { browserImageFit(size.width.toFloat(), size.height.toFloat(), it.width, it.height) }
                val point = if (frame != null && fit != null) browserImagePoint(fit, event.x, event.y, frame.cssWidth, frame.cssHeight) else null
                if (latestView?.ownsControl != true || frame == null || fit == null || point == null) return false
                focusRequester.requestFocus()
                touch.mode = BrowserTouchMode.PENDING
                touch.pointerId = event.getPointerId(0)
                touch.downX = event.x; touch.downY = event.y; touch.lastX = event.x; touch.lastY = event.y
                touch.downTime = event.eventTime
                touch.point = point; touch.fit = fit; touch.cssWidth = frame.cssWidth; touch.cssHeight = frame.cssHeight
                val downTime = event.eventTime
                // Only feedback: whether it was a long press is read from the event times.
                longPressFeedback = touchScope.launch {
                    delay(longPress)
                    if (touch.mode == BrowserTouchMode.PENDING && touch.downTime == downTime) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(touch.pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                when (touch.mode) {
                    BrowserTouchMode.PENDING -> if (hypot(x - touch.downX, y - touch.downY) > viewConfiguration.touchSlop) {
                        longPressFeedback?.cancel()
                        if (event.eventTime - touch.downTime >= longPress) startDrag(x, y)
                        else {
                            touch.mode = BrowserTouchMode.SCROLL
                            disallowIntercept(true)
                            scrollTo(x, y)
                        }
                    }
                    BrowserTouchMode.SCROLL -> scrollTo(x, y)
                    BrowserTouchMode.DRAG -> dragTo(x, y)
                    BrowserTouchMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressFeedback?.cancel()
                when (touch.mode) {
                    BrowserTouchMode.PENDING -> touch.point?.let { (x, y) ->
                        val button = if (event.eventTime - touch.downTime >= longPress) "right" else "left"
                        latestPress(BrowserInput.Pointer(x, y, "down", button), BrowserInput.Pointer(x, y, "up", button))
                    }
                    BrowserTouchMode.DRAG -> endDrag()
                    else -> Unit
                }
                touch.reset()
            }
            // A second finger (pinch is not supported) or a cancelled gesture ends the gesture
            // without a click; a drag releases its button.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                longPressFeedback?.cancel()
                if (touch.mode == BrowserTouchMode.DRAG) endDrag()
                touch.reset()
            }
        }
        return true
    }
    val latestOnTouch by rememberUpdatedState<(MotionEvent) -> Boolean>({ onTouch(it) })

    val title = if (view?.previewPort != null) stringResource(R.string.preview_title_port, view.previewPort) else stringResource(R.string.browser_title)
    val subtitle = selected?.let { tab -> tab.title.ifBlank { tab.url } }?.takeIf { it.isNotBlank() }
    // Without `browser.history`, browser shortcuts do not reach headless Chromium: a reload opens
    // the tab's current address again, which only works for a real document URL.
    val history = view?.history == true && view.ownsControl && selected != null
    val reloadUrl = selected?.url?.takeIf { view?.ownsControl == true && !view.history && browserReloadable(it) }
    // Back or forward without an entry: a short hint, and the button stays off until the page changes.
    var historyHint by remember(view?.sessionId, view?.previewPort) { mutableStateOf<String?>(null) }
    LaunchedEffect(view?.historyMissingCount) {
        val missing = latestView?.historyMissing ?: return@LaunchedEffect
        historyHint = missing; delay(BROWSER_HISTORY_HINT_MS); historyHint = null
    }
    val headerActions = buildList {
        // Narrow panes move these into the "more" menu with the rest (see PaneHeader).
        if (history) {
            // With `session.browser.history.state.v1` the host says per tab whether an entry exists;
            // without it the buttons stay on until the host refuses one (historyMissing).
            val stated = view?.historyState == true
            add(PaneAction(stringResource(R.string.browser_back), Icons.AutoMirrored.Filled.ArrowBack, "browserBack",
                enabled = held.isEmpty() && if (stated) view?.canGoBack == true else view?.historyMissing != "back", onClick = { latestActions.history("back") }))
            add(PaneAction(stringResource(R.string.browser_forward), Icons.AutoMirrored.Filled.ArrowForward, "browserForward",
                enabled = held.isEmpty() && if (stated) view?.canGoForward == true else view?.historyMissing != "forward", onClick = { latestActions.history("forward") }))
            add(PaneAction(stringResource(R.string.browser_stop), Icons.Default.Close, "browserStop",
                enabled = held.isEmpty(), onClick = { latestActions.history("stop") }))
        }
        if (view != null && !view.opening)
            add(PaneAction(stringResource(R.string.remote_view_update_status), Icons.Default.Sync, "browserSync", onClick = { latestActions.refresh() }))
        if (onHide != null) add(PaneAction(stringResource(R.string.preview_hide), Icons.Default.VisibilityOff, "browserHide", onClick = onHide))
    }
    val notices = buildList {
        when (view?.notice) {
            // The control bar names who holds control.
            BrowserNotice.BUSY -> Unit
            BrowserNotice.UNCERTAIN -> add(RemoteViewNotice.Failure(stringResource(if (view.ownsControl) R.string.browser_uncertain_control_retained else R.string.browser_uncertain), "browserNotice"))
            BrowserNotice.DISCONNECTED -> add(RemoteViewNotice.Info(stringResource(R.string.remote_view_disconnected), "browserNotice"))
            BrowserNotice.FAILED -> add(RemoteViewNotice.Failure(stringResource(R.string.remote_view_failed), "browserNotice",
                onRetry = { if (latestView?.browserId == null) latestActions.open() else latestActions.refresh() }))
            BrowserNotice.FRAME_FAILED -> add(RemoteViewNotice.Failure(stringResource(R.string.remote_view_stream_failed), "browserNotice", onRetry = { latestActions.refresh() }))
            null -> Unit
        }
        if (textSendRefused) add(RemoteViewNotice.Failure(stringResource(R.string.browser_send_refused), "browserSendRefused"))
        if (goRefused) add(RemoteViewNotice.Failure(stringResource(R.string.browser_go_refused), "browserGoRefused"))
        if (controlDropped) add(RemoteViewNotice.Info(stringResource(R.string.browser_control_dropped), "browserControlDropped"))
        historyHint?.let { add(RemoteViewNotice.Info(stringResource(if (it == "back") R.string.browser_history_no_back else R.string.browser_history_no_forward), "browserHistoryHint")) }
    }
    // A compact pane has no room for the tab row: its tabs are a header menu instead.
    val tabsMenu = if (view == null || view.opening) emptyList<PaneMenuItem>() else buildList<PaneMenuItem> {
        view.tabs.forEach { tab ->
            val tabLabel = tab.title.ifBlank { tab.url }
            val close = if (view.ownsControl) PaneMenuClose(stringResource(R.string.browser_close_tab, tabLabel), "browserTabsMenuClose_${tab.id}") { latestActions.closeTab(tab.id) } else null
            add(PaneMenuItem(tabLabel, "browserTabsMenu_${tab.id}", selected = tab.id == view.selectedTabId, close = close) { latestActions.selectTab(tab.id) })
        }
        if (view.ownsControl) add(PaneMenuItem(stringResource(R.string.browser_new_tab), "browserTabsMenuNew") { latestActions.newTab("about:blank") })
    }
    val compactActions = if (tabsMenu.isEmpty()) emptyList<PaneAction>() else
        listOf(PaneAction(stringResource(R.string.browser_tabs), Icons.Default.Tab, "browserTabsMenu", items = tabsMenu, enabled = held.isEmpty()))
    RemoteViewPane(
        title = title,
        tag = "browser",
        onClose = onClose,
        subtitle = subtitle,
        busy = held.isNotEmpty(),
        onReload = when {
            history -> ({ latestActions.history("reload") })
            reloadUrl != null -> ({ latestActions.navigate(reloadUrl) })
            else -> null
        },
        actions = headerActions,
        compactActions = compactActions,
        notices = notices,
        escapeCloses = false,
        noticeFirstBelow = BROWSER_NOTICE_FIRST_WIDTH,
    ) {
        val compact = LocalRemoteViewCompact.current
        if (view == null || view.opening) {
            RemoteViewport("browserViewport", Modifier.weight(1f).fillMaxWidth(), placeholder = RemoteViewPlaceholder(stringResource(R.string.remote_view_opening)))
        } else {
            RemoteControlBar(
                state = when {
                    view.ownsControl -> RemoteControlState.CONTROLLING
                    view.owner != null -> RemoteControlState.HELD
                    else -> RemoteControlState.VIEWING
                },
                tag = "browser",
                modifier = Modifier.remoteViewShed(RemoteViewShed.CONTROL),
                holder = view.owner?.let { browserParticipantLabel(it) },
                onTake = { latestActions.acquire() },
                handoffTargets = view.participants.filter { it != view.owner }.map { RemoteControlTarget(it.id, browserParticipantLabel(it)) },
                onHandOff = { latestActions.handoff(it) },
            )
            // A compact pane shows its tabs as a header menu (compactActions); the address bar stays.
            if (!compact)
                RemoteTabStrip(
                    tabs = view.tabs.map { tab ->
                        val tabLabel = tab.title.ifBlank { tab.url }
                        RemoteTab(tab.id, tabLabel, tab.id == view.selectedTabId, stringResource(R.string.browser_close_tab, tabLabel).takeIf { view.ownsControl })
                    },
                    onSelect = { latestActions.selectTab(it) },
                    onClose = { latestActions.closeTab(it) },
                    tag = "browser",
                    enabled = held.isEmpty(),
                ) {
                    if (view.ownsControl) TextButton(onClick = { latestActions.newTab("about:blank") }, Modifier.testTag("browserNewTab"), enabled = held.isEmpty()) { Text(stringResource(R.string.browser_new_tab)) }
                }
            RemoteInputBar(
                value = url,
                onValueChange = { url = it; urlDirty = true; goRefused = false },
                onSend = {
                    // With no tab open, the address opens in a new one instead of going nowhere.
                    val sent = if (view.selectedTabId == null) latestActions.newTab(url) else latestActions.navigate(url)
                    if (sent) urlDirty = false
                    goRefused = !sent
                },
                tag = "browserAddress",
                label = stringResource(R.string.browser_address),
                enabled = view.ownsControl,
                sendEnabled = view.ownsControl && held.isEmpty() && url.isNotBlank(),
                sendLabel = stringResource(R.string.browser_go),
                sendIcon = Icons.AutoMirrored.Filled.ArrowForward,
                sendTag = "browserGo",
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            RemoteViewport(
                "browserViewport",
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it }
                    .onFocusChanged {
                        focusOwner?.update(focusToken, active && it.hasFocus)
                        if (!it.hasFocus) {
                            heldKeys.values.toList().forEach { key -> latestEnqueue(PendingBrowserInput(key.copy(action = "up"))) }
                            // A button held by a drag is released too; a gesture in progress ends.
                            val at = pointerPosition
                            if (at != null && "pointer:$pointerButton" in held) {
                                latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(at.first, at.second, "up", pointerButton)))
                                pointerPosition = null
                            }
                            touch.reset()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val pressedCodes = held.filter { it.startsWith("key:") }.map { it.removePrefix("key:") }.toSet()
                        val input = browserHardwareInput(event.nativeKeyEvent, latestCanTextKeys || latestCanRelease && (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP || event.nativeKeyEvent.repeatCount > 0), pressedCodes)
                        when {
                            input is BrowserInput.Key && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount > 0 ->
                                if (browserRepeatableKey(event.nativeKeyEvent) && "key:${input.code}" in held) {
                                    latestRepeatKey(input)
                                    true // A full queue drops repeats without moving Android focus.
                                } else false
                            input is BrowserInput.Key && input.action == "up" -> {
                                val original = heldKeys["key:${input.code}"]
                                latestEnqueue(PendingBrowserInput(if (original == null) input else input.copy(key = original.key)))
                            }
                            input != null -> latestEnqueue(PendingBrowserInput(input))
                            else -> false
                        }
                    }.focusRequester(focusRequester).focusable(enabled = canRelease)
                    .pointerInteropFilter(disallowIntercept) { event ->
                        // A cancel the filter makes up itself carries no tool type: it ends a finger
                        // gesture in progress like a real one.
                        if (browserTouchEvent(event) || (event.actionMasked == MotionEvent.ACTION_CANCEL && touch.mode != BrowserTouchMode.NONE))
                            return@pointerInteropFilter latestOnTouch(event)
                        // A mouse or touchpad: plain mouse semantics.
                        val frame = latestImage; val current = latestView
                        val fit = frame?.let { browserImageFit(size.width.toFloat(), size.height.toFloat(), it.width, it.height) }
                        val rawPoint = if (fit != null && frame != null) browserImagePoint(fit, event.x, event.y, frame.cssWidth, frame.cssHeight) else null
                        val point = rawPoint ?: if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) pointerPosition else null
                        if (current == null || point == null) false else {
                            val button = when { event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> "right"; event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> "middle"; else -> "left" }
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> { focusRequester.requestFocus(); pointerPosition = point; pointerButton = button; latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(point.first, point.second, "down", button))) }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_BUTTON_RELEASE -> { val sent = latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(point.first, point.second, "up", pointerButton))); pointerPosition = null; sent }
                                MotionEvent.ACTION_MOVE -> { pointerPosition = point; latestEnqueue(PendingBrowserInput(BrowserInput.Pointer(point.first, point.second, "move", pointerButton))) }
                                MotionEvent.ACTION_SCROLL -> latestEnqueue(PendingBrowserInput(BrowserInput.Wheel(point.first, point.second, (-event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 48).toDouble(), (-event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 48).toDouble())))
                                else -> false
                            }
                        }
                    },
                placeholder = when {
                    bitmap != null -> null
                    image != null && failedImage === image -> RemoteViewPlaceholder(stringResource(R.string.remote_view_frame_failed), busy = false)
                    // The notice says the stream stopped; nothing more comes on its own.
                    view.notice == BrowserNotice.FRAME_FAILED -> RemoteViewPlaceholder(stringResource(R.string.remote_view_frame_failed), busy = false)
                    else -> RemoteViewPlaceholder(stringResource(R.string.remote_view_waiting))
                },
                busy = view.inputPending || flight != null,
            ) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.browser_image), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            RemoteKeyStrip(
                keys = browserStripKeys(),
                onKey = { key -> latestPress(BrowserInput.Key("down", key.id, key.id), BrowserInput.Key("up", key.id, key.id)) },
                tag = "browser",
                enabled = canTextKeys,
                sheds = true,
            )
            // Always composed, also in a compact pane (one line there): the soft keyboard opening
            // shrinks the pane, and a bar that left composition then would take the focus with it.
            RemoteInputBar(
                value = draft,
                onValueChange = { draft = it },
                onSend = { textSendRefused = !enqueue(PendingBrowserInput(BrowserInput.Text(draft), draft)) },
                tag = "browserText",
                enabled = view.ownsControl,
                sendEnabled = canTextKeys && draft.isNotBlank() && flight == null && queue.isEmpty(),
                sendTag = "browserSendText",
            )
        }
    }
}

/**
 * The window of the dev-server preview: [content] (its [BrowserPane]) over everything. Android
 * turns an Esc nothing in a dialog took into Back, which would close the preview or ask to discard
 * its input; here an Esc the pane leaves (focus in the address or text field, or nowhere) is used
 * up at the window's root, which holds the focus while nothing else does (it takes it back when
 * the focused field or viewport turns disabled and the focus would go nowhere). Back and the pane's
 * close button still close the preview through the pane's handler and [onDismissRequest]; with
 * the viewport focused and in control, Esc goes to the page before it gets here.
 */
@Composable
internal fun PreviewDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rootFocus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        // Whether the root or anything in it has focus; read when the posted request runs.
        val focusInside = remember { booleanArrayOf(false) }
        Surface(Modifier.fillMaxSize().onKeyEvent { it.key == Key.Escape }
            .onFocusChanged { state ->
                focusInside[0] = state.hasFocus
                // A focused field or viewport that turns disabled (control taken, the fail-safe
                // release) loses focus to nowhere, and the next Esc would close the window. The
                // root takes it back, posted (never inside a focus change), and only while still
                // nothing in the window has it, so a field focused meanwhile keeps its focus. The
                // root is no text field: the soft keyboard does not open for it.
                if (!state.hasFocus) scope.launch { if (!focusInside[0]) runCatching { rootFocus.requestFocus() } }
            }
            .focusRequester(rootFocus).focusTarget().testTag("previewDialog")) {
            content()
        }
        LaunchedEffect(rootFocus) { runCatching { rootFocus.requestFocus() } }
    }
}
