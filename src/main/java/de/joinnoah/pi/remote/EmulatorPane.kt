package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EmulatorPaneActions(
    val select: (String) -> Boolean,
    val acquire: () -> Boolean,
    val handoff: (String) -> Boolean,
    val stop: () -> Boolean,
    val input: (EmulatorInput) -> Boolean,
    val refresh: () -> Boolean,
    val visible: (Boolean) -> Unit,
    /** The notices' retry: see [EmulatorClient.retry]. */
    val retry: () -> Boolean = { false },
    val release: () -> Boolean = { false },
    /** One raw touch event; used instead of taps and swipes where [EmulatorView.controls] holds. */
    val touch: (EmulatorInput.Touch) -> Boolean = { false },
    val rotate: (Long) -> Boolean = { false },
    /** One hardware keystroke on the focused screen, and whether it is an auto-repeat (see [EmulatorClient.type]). */
    val type: (EmulatorInput, Boolean) -> Boolean = { _, _ -> false },
)

/** How long a held finger presses: the host's swipe takes 50 to 1000 ms. */
internal const val EMULATOR_LONG_PRESS_MS = 700L
/** Movement, in pixels of the pane, below which a touch stays a tap or a long press. */
internal const val EMULATOR_TOUCH_SLOP_PX = 8f

/**
 * The display point under ([x], [y]) of a pane [containerWidth] by [containerHeight] that shows
 * [image] letterboxed (ContentScale.Fit), or null outside the image. The Host reports the display
 * size in its current rotation and captures the screen in it, so the point only scales; rotation
 * needs no turn of its own.
 */
internal fun emulatorImagePoint(containerWidth: Float, containerHeight: Float, image: EmulatorImage, x: Float, y: Float): Pair<Long, Long>? {
    if (containerWidth <= 0f || containerHeight <= 0f || image.width <= 0 || image.height <= 0 || image.displayWidth <= 0 || image.displayHeight <= 0) return null
    if (!x.isFinite() || !y.isFinite()) return null
    val scale = min(containerWidth / image.width, containerHeight / image.height)
    val width = image.width * scale; val height = image.height * scale
    val left = (containerWidth - width) / 2f; val top = (containerHeight - height) / 2f
    if (x < left || y < top || x >= left + width || y >= top + height) return null
    return ((x - left) / width * image.displayWidth).toLong().coerceIn(0, image.displayWidth - 1) to
        ((y - top) / height * image.displayHeight).toLong().coerceIn(0, image.displayHeight - 1)
}

/**
 * [emulatorImagePoint] for a pointer that already went down on the image: a point outside it is
 * taken to the nearest edge, so a drag off the screen keeps its pointer.
 */
internal fun emulatorImagePointClamped(containerWidth: Float, containerHeight: Float, image: EmulatorImage, x: Float, y: Float): Pair<Long, Long>? {
    if (containerWidth <= 0f || containerHeight <= 0f || image.width <= 0 || image.height <= 0 || !x.isFinite() || !y.isFinite()) return null
    val scale = min(containerWidth / image.width, containerHeight / image.height)
    val width = image.width * scale; val height = image.height * scale
    val left = (containerWidth - width) / 2f; val top = (containerHeight - height) / 2f
    return emulatorImagePoint(containerWidth, containerHeight, image, x.coerceIn(left, left + width - 0.01f), y.coerceIn(top, top + height - 0.01f))
}

/** A finger's pressure (0 to 1, sometimes above) as the host's 1 to 1024; 0 is reserved for a lift. */
internal fun emulatorTouchPressure(pressure: Float): Int =
    if (!pressure.isFinite() || pressure <= 0f) EMULATOR_TOUCH_MAX_PRESSURE / 2 else (pressure * EMULATOR_TOUCH_MAX_PRESSURE).roundToInt().coerceIn(1, EMULATOR_TOUCH_MAX_PRESSURE)

/**
 * Follows the fingers on the screen and reports each change as one raw touch event: every pointer
 * down or lifting, with an id 0 to 31 kept for as long as it is down. A pointer that goes down
 * outside the image, beyond the tenth, or whose down was refused, is ignored until it lifts. A
 * refused lift keeps its id and goes again with the next event or [clear].
 */
internal class EmulatorTouchTracker {
    private val ids = mutableMapOf<Long, Int>()
    private val last = mutableMapOf<Int, Pair<Long, Long>>()
    /** One pointer of an event: [key] is the platform's pointer id, [down] whether it is pressed. */
    data class Pointer(val key: Long, val down: Boolean, val wasDown: Boolean, val point: Pair<Long, Long>?, val clamped: Pair<Long, Long>?, val pressure: Float)

    fun event(pointers: List<Pointer>, send: (EmulatorInput.Touch) -> Boolean) {
        val touches = mutableListOf<EmulatorTouchPoint>()
        val started = mutableListOf<Long>()
        for (p in pointers) {
            var id = ids[p.key]
            if (id == null) {
                if (!p.down || p.wasDown || p.point == null || ids.size >= EMULATOR_TOUCH_MAX_POINTERS) continue
                id = (0..EMULATOR_TOUCH_MAX_ID).first { it !in ids.values }
                ids[p.key] = id; started += p.key
            }
            val at = p.clamped ?: last[id] ?: continue
            last[id] = at
            touches += EmulatorTouchPoint(id, at.first, at.second, if (p.down) emulatorTouchPressure(p.pressure) else 0)
        }
        // A pointer that is no longer reported lifted with a refused event: lift it again.
        val keys = pointers.map { it.key }.toSet()
        val orphaned = ids.filterKeys { it !in keys }.values.mapNotNull { id -> last[id]?.let { EmulatorTouchPoint(id, it.first, it.second, 0) } }
        touches += orphaned
        val lifted = pointers.filter { !it.down }.mapNotNull { ids[it.key] } + orphaned.map { it.id }
        if (touches.isEmpty()) return
        if (send(EmulatorInput.Touch(touches))) lifted.forEach { forget(it) }
        else started.forEach { key -> ids.remove(key)?.let { last.remove(it) } }
    }

    private fun forget(id: Int) { ids.entries.removeAll { it.value == id }; last.remove(id) }

    /** Lifts every pointer still down through [send], then forgets them all, whether it went out or not. */
    fun clear(send: (EmulatorInput.Touch) -> Boolean = { false }) {
        val lifts = ids.values.sorted().mapNotNull { id -> last[id]?.let { EmulatorTouchPoint(id, it.first, it.second, 0) } }
        if (lifts.isNotEmpty()) send(EmulatorInput.Touch(lifts))
        ids.clear(); last.clear()
    }
}

/**
 * The input a finished touch makes: a long press is a swipe that does not move, held for
 * [EMULATOR_LONG_PRESS_MS] (the Host accepts identical start and end points); a touch that moved
 * past the slop is a swipe of its own duration; anything else is a tap.
 */
internal fun emulatorGesture(start: Pair<Long, Long>, end: Pair<Long, Long>, moved: Boolean, longPress: Boolean, durationMs: Long): EmulatorInput =
    when {
        longPress -> EmulatorInput.Swipe(start.first, start.second, start.first, start.second, EMULATOR_LONG_PRESS_MS)
        !moved -> EmulatorInput.Tap(start.first, start.second)
        else -> EmulatorInput.Swipe(start.first, start.second, end.first, end.second, durationMs.coerceIn(50, 1000))
    }

/** The caller owns the returned bitmap and recycles it once it is no longer displayed. */
internal fun decodeEmulatorImage(image: EmulatorImage): Bitmap? {
    if (image.png.isEmpty() || image.png.size > EMULATOR_FRAME_MAX_BYTES || image.width !in 1..16384 || image.height !in 1..16384 ||
        image.width * image.height > 8_388_608
    ) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.png, 0, image.png.size, bounds)
        if (bounds.outMimeType != "image/png" || bounds.outWidth.toLong() != image.width || bounds.outHeight.toLong() != image.height) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(image.png, 0, image.png.size, options) ?: return null
        if (bitmap.width.toLong() != image.width || bitmap.height.toLong() != image.height) { bitmap.recycle(); null } else bitmap
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** A participant as the pane names it (see [remoteParticipantLabel]). */
@Composable
internal fun emulatorParticipantLabel(owner: EmulatorOwner): String = remoteParticipantLabel(owner.kind, owner.id)

@Composable
private fun emulatorNotice(view: EmulatorView, retry: () -> Unit): RemoteViewNotice? {
    // Retrying needs the connection; while it is down the app reconnects on its own.
    val onRetry = retry.takeIf { !view.disconnected && !view.opening }
    return when (view.notice) {
        null -> null
        // The control bar names who holds control.
        EmulatorNotice.BUSY -> null
        EmulatorNotice.DISCONNECTED -> RemoteViewNotice.Info(stringResource(R.string.remote_view_disconnected), "emulatorNotice")
        EmulatorNotice.STREAM_RECOVERING -> RemoteViewNotice.Info(stringResource(R.string.emulator_notice_stream_recovering), "emulatorNotice")
        EmulatorNotice.STREAM_FAILED -> RemoteViewNotice.Failure(stringResource(R.string.remote_view_stream_failed), "emulatorNotice", onRetry = onRetry)
        EmulatorNotice.FAILED -> RemoteViewNotice.Failure(stringResource(R.string.remote_view_failed), "emulatorNotice", onRetry = onRetry)
        EmulatorNotice.UNCERTAIN -> RemoteViewNotice.Failure(
            stringResource(R.string.emulator_notice_uncertain), "emulatorNotice",
            retryLabel = stringResource(R.string.emulator_reopen), onRetry = onRetry,
        )
    }
}

@Composable
private fun emulatorKeys(controls: Boolean): List<RemoteKey> = listOf(
    RemoteKey("Back", stringResource(R.string.remote_view_key_android_back)),
    RemoteKey("Home", stringResource(R.string.remote_view_key_android_home), stringResource(R.string.remote_view_key_android_home_description)),
    RemoteKey("Escape", stringResource(R.string.remote_view_key_escape), stringResource(R.string.remote_view_key_escape_description)),
    RemoteKey("Tab", stringResource(R.string.remote_view_key_tab)),
    RemoteKey("Enter", stringResource(R.string.remote_view_key_enter)),
) + remoteNavigationKeys() + (if (controls) listOf(
    RemoteKey("AppSwitch", stringResource(R.string.remote_view_key_android_recents)),
    RemoteKey("Power", stringResource(R.string.remote_view_key_android_power)),
    RemoteKey("VolumeDown", stringResource(R.string.remote_view_key_android_volume_down), stringResource(R.string.remote_view_key_android_volume_down_description)),
    RemoteKey("VolumeUp", stringResource(R.string.remote_view_key_android_volume_up), stringResource(R.string.remote_view_key_android_volume_up_description)),
) else emptyList())

/**
 * The shared Android emulator: the profile choice, then the control bar, the screen and the
 * device keys. Kept composed while another inspector tab shows, it is hidden there
 * ([LocalTerminalKeyboardActive] false), and so it is while the app is in the background: the
 * stream stops, the session stays, and the lease stays for [EMULATOR_HIDDEN_RELEASE_MS].
 *
 * Each frame's PNG is decoded once by [decodeImage] in [decodeContext], off the main thread.
 */
@Composable
internal fun EmulatorPane(
    view: EmulatorView,
    actions: EmulatorPaneActions,
    close: () -> Unit,
    decodeImage: suspend (EmulatorImage) -> Bitmap? = { decodeEmulatorImage(it) },
    decodeContext: CoroutineContext = Dispatchers.Default,
) {
    val active = LocalTerminalKeyboardActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val shown = active && started
    val latestActions by rememberUpdatedState(actions)
    DisposableEffect(shown) {
        if (shown) latestActions.visible(true)
        // A hidden pane has stopped already.
        onDispose { if (shown) latestActions.visible(false) }
    }
    var confirmStop by remember { mutableStateOf(false) }
    var text by rememberSaveable(view.sessionId) { mutableStateOf("") }
    var textRefused by remember(view.sessionId) { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)

    // The frame shown and the frames it replaced, recycled once they are drawn no more.
    val image = view.image
    var decoded by remember { mutableStateOf<Pair<EmulatorImage, Bitmap>?>(null, referentialEqualityPolicy()) }
    var failedImage by remember { mutableStateOf<EmulatorImage?>(null, referentialEqualityPolicy()) }
    val retired = remember { mutableListOf<Bitmap>() }
    LaunchedEffect(image) {
        if (image == null) {
            decoded?.second?.let { retired += it }; decoded = null; failedImage = null
            return@LaunchedEffect
        }
        if (decoded?.first === image) return@LaunchedEffect
        var candidate: Bitmap? = null
        var transferred = false
        try {
            val result = withContext(decodeContext) { decodeImage(image).also { candidate = it } }
            if (result == null) failedImage = image
            else { decoded?.second?.let { retired += it }; decoded = image to result; failedImage = null; transferred = true }
        } finally {
            if (!transferred) candidate?.recycle()
        }
    }
    LaunchedEffect(decoded) {
        if (retired.isEmpty()) return@LaunchedEffect
        // The new frame (or none) is drawn by the second frame from now; the old one no longer is.
        withFrameNanos { }; withFrameNanos { }
        val shown = decoded?.second
        retired.removeAll { bitmap -> (bitmap !== shown).also { if (it) bitmap.recycle() } }
    }
    DisposableEffect(Unit) {
        onDispose { retired.forEach { it.recycle() }; retired.clear(); decoded?.second?.recycle(); decoded = null }
    }
    // A frame that failed to decode is not covered by the one before it: the pane says it failed.
    val bitmap = decoded?.second?.takeIf { image != null && failedImage !== image }
    val displayed = bitmap != null && failedImage !== image
    val latestImage by rememberUpdatedState(image)
    val latestDisplayed by rememberUpdatedState(displayed)

    val stopLabel = stringResource(R.string.emulator_stop)
    // No header reload: an emulator has nothing to reload. Updating who has control is an action.
    val paneActions =
        if (view.emulatorId != null)
            listOf(
                PaneAction(stringResource(R.string.remote_view_update_status), Icons.Default.Sync, "emulatorSync", enabled = !view.opening) { latestActions.refresh() },
                PaneAction(stopLabel, Icons.Default.Stop, "emulatorStop", enabled = view.ownsControl && !view.opening) { confirmStop = true },
            ) + (if (view.controls) listOf(
                // A quarter turn further each time.
                PaneAction(stringResource(R.string.emulator_rotate), Icons.Default.ScreenRotation, "emulatorRotate", enabled = view.ownsControl && displayed && !view.opening && !view.inputPending) {
                    view.geometry?.let { latestActions.rotate((it.rotation + 1) % 4) }
                },
            ) else emptyList())
        else emptyList()
    val notice = emulatorNotice(view) { latestActions.retry() }
    RemoteViewPane(
        title = stringResource(R.string.emulator_title),
        tag = "emulator",
        onClose = close,
        subtitle = view.label ?: view.profiles.firstOrNull { it.id == view.profileId }?.label,
        busy = view.opening,
        actions = paneActions,
        notices = listOfNotNull(notice),
    ) {
        if (view.emulatorId == null) {
            EmulatorProfilePicker(view, Modifier.weight(1f)) { latestActions.select(it) }
        } else {
            val controlState = when {
                view.ownsControl -> RemoteControlState.CONTROLLING
                view.owner != null -> RemoteControlState.HELD
                else -> RemoteControlState.VIEWING
            }
            RemoteControlBar(
                state = controlState,
                tag = "emulator",
                modifier = Modifier.remoteViewShed(RemoteViewShed.CONTROL),
                holder = view.owner?.let { emulatorParticipantLabel(it) },
                enabled = !view.opening,
                onTake = { latestActions.acquire() },
                onRelease = { latestActions.release() },
                handoffTargets = view.participants.filter { it != view.owner }.map { RemoteControlTarget(it.id, emulatorParticipantLabel(it)) },
                onHandOff = { latestActions.handoff(it) },
            )
            // Nothing more comes on its own after these: the notice says what to do.
            // A refused request (FAILED) stops nothing: the watch lives on and a frame follows.
            val stopped = view.notice == EmulatorNotice.STREAM_FAILED || view.notice == EmulatorNotice.UNCERTAIN
            val placeholder = when {
                view.opening -> RemoteViewPlaceholder(stringResource(R.string.remote_view_opening))
                image != null && failedImage === image -> RemoteViewPlaceholder(stringResource(R.string.remote_view_frame_failed), busy = false)
                image == null && stopped -> RemoteViewPlaceholder(stringResource(R.string.remote_view_frame_failed), busy = false)
                bitmap == null -> RemoteViewPlaceholder(stringResource(R.string.remote_view_waiting))
                else -> null
            }
            val keysEnabled = view.ownsControl && displayed && !view.opening && !view.rotating
            val screenKeys = rememberEmulatorInputKeys(keysEnabled, view.keyboard) { input, repeat -> latestActions.type(input, repeat) }
            val screenFocus = remember { FocusRequester() }
            // With a hardware keyboard attached, taking control puts its keys on the screen at once.
            val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
            LaunchedEffect(view.ownsControl) { if (view.ownsControl && hardwareKeyboard && active) screenFocus.requestFocus() }
            // The viewport, not the image, holds the keyboard focus: the image goes away with each
            // gap between frames (rotation, a new watch, hiding) and its focus would go with it.
            // Touching the screen gives it focus.
            RemoteViewport("emulatorViewport", Modifier.weight(1f).fillMaxWidth()
                .focusRequester(screenFocus).then(screenKeys).focusable()
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); screenFocus.requestFocus() } },
                placeholder, busy = view.inputPending) {
                if (bitmap != null) {
                    val geometry = image?.let { listOf(it.width, it.height, it.displayWidth, it.displayHeight, it.rotation, it.revision) }
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.emulator_screen),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("emulatorScreen").pointerInput(geometry, view.controls) {
                            if (view.controls) {
                                // Raw multi-touch: every finger goes to the device as it moves.
                                val tracker = EmulatorTouchTracker()
                                // A restart (new geometry) or disposal lifts the fingers still down.
                                try { awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val shown = latestImage
                                        val w = size.width.toFloat(); val h = size.height.toFloat()
                                        val pointers = event.changes.map { change ->
                                            EmulatorTouchTracker.Pointer(change.id.value, change.pressed, change.previousPressed,
                                                shown?.let { emulatorImagePoint(w, h, it, change.position.x, change.position.y) },
                                                shown?.let { emulatorImagePointClamped(w, h, it, change.position.x, change.position.y) },
                                                change.pressure)
                                        }
                                        event.changes.forEach { it.consume() }
                                        if (currentView.ownsControl && latestDisplayed) tracker.event(pointers) { latestActions.touch(it) }
                                        else tracker.clear { latestActions.touch(it) }
                                    }
                                } } finally { tracker.clear { latestActions.touch(it) } }
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                val start = down.position; val startTime = down.uptimeMillis
                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                var last = start; var endTime = startTime
                                var canceled = down.isConsumed; var finished = false; var moved = false; var longPressed = false
                                fun point(at: Offset): Pair<Long, Long>? {
                                    val shown = latestImage ?: return null
                                    return emulatorImagePoint(size.width.toFloat(), size.height.toFloat(), shown, at.x, at.y)
                                }
                                fun canSend() = currentView.ownsControl && latestDisplayed
                                while (!finished && !canceled) {
                                    // Until it moves, a held finger becomes a long press after the timeout.
                                    val waiting = !moved && !longPressed
                                    val event: PointerEvent? =
                                        if (waiting) withTimeoutOrNull((longPressTimeout - (endTime - startTime)).coerceAtLeast(1)) { awaitPointerEvent() }
                                        else awaitPointerEvent()
                                    if (event == null) {
                                        longPressed = true
                                        val a = point(start)
                                        if (a != null && canSend()) latestActions.input(emulatorGesture(a, a, moved = false, longPress = true, durationMs = EMULATOR_LONG_PRESS_MS))
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || change.isConsumed) { canceled = true; break }
                                    last = change.position; endTime = change.uptimeMillis
                                    if (!change.pressed) finished = true
                                    else if (abs(start.x - last.x) + abs(start.y - last.y) >= EMULATOR_TOUCH_SLOP_PX) { if (!longPressed) moved = true; change.consume() }
                                }
                                if (finished && !canceled && !longPressed) {
                                    val a = point(start); val b = point(last)
                                    // A press released after the timeout without the timer firing is still a long press.
                                    val long = !moved && endTime - startTime >= longPressTimeout
                                    if (a != null && b != null && canSend()) latestActions.input(emulatorGesture(a, b, moved, long, endTime - startTime))
                                }
                            }
                        },
                    )
                }
            }
            RemoteKeyStrip(
                emulatorKeys(view.controls),
                onKey = { latestActions.input(EmulatorInput.Key(it.id)) },
                tag = "emulator",
                enabled = keysEnabled,
                sheds = true,
            )
            if (view.controls) {
                // Without the keyboard capability text is typed through the device's key map, so only
                // printable ASCII goes; with it any NFC text of up to 256 code points.
                val keyboard = view.keyboard
                val outgoing = if (keyboard) emulatorNfc(text) else text
                val valid = text.isEmpty() || (if (keyboard) emulatorKeyboardTextValid(outgoing) else emulatorTextValid(text))
                RemoteInputBar(
                    value = text,
                    onValueChange = { text = it; textRefused = false },
                    onSend = { if (latestActions.input(EmulatorInput.Text(outgoing))) text = "" else textRefused = true },
                    tag = "emulatorText",
                    label = stringResource(R.string.emulator_text),
                    enabled = view.ownsControl,
                    sendEnabled = view.ownsControl && displayed && !view.opening && !view.rotating && text.isNotEmpty() && valid,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, keyboardType = if (keyboard) KeyboardType.Text else KeyboardType.Ascii, autoCorrectEnabled = false),
                )
                if (!valid) {
                    val hint = when {
                        !keyboard -> stringResource(R.string.emulator_text_ascii_only)
                        outgoing.codePointCount(0, outgoing.length) > EMULATOR_TEXT_MAX_CHARS -> stringResource(R.string.emulator_text_too_long, EMULATOR_TEXT_MAX_CHARS)
                        else -> stringResource(R.string.emulator_text_unsupported)
                    }
                    RemoteViewNoticeLine(RemoteViewNotice.Info(hint, "emulatorTextHint"))
                }
                else if (textRefused) RemoteViewNoticeLine(RemoteViewNotice.Info(stringResource(R.string.emulator_text_refused), "emulatorTextRefused"))
            }
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.emulator_stop_confirm)) },
        confirmButton = { TextButton(onClick = { confirmStop = false; latestActions.stop() }, Modifier.testTag("emulatorStopConfirm")) { Text(stopLabel) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

/**
 * The emulator profiles the user may open, as plain list rows like the Files pane's, under a
 * heading; with none, the empty notice.
 */
@Composable
private fun EmulatorProfilePicker(view: EmulatorView, modifier: Modifier, select: (String) -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Top) {
        if (view.opening) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("emulatorProfilesLoading"))
        Text(
            stringResource(R.string.emulator_select_profile),
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        if (view.profiles.isEmpty() && !view.opening && view.notice == null)
            PaneNotice(stringResource(R.string.remote_view_empty), Modifier.testTag("emulatorProfilesEmpty"))
        view.profiles.forEach { profile ->
            Text(
                profile.label,
                Modifier.fillMaxWidth()
                    .clickable(enabled = !view.opening, role = Role.Button) { select(profile.id) }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("emulatorProfile_${profile.id}"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
