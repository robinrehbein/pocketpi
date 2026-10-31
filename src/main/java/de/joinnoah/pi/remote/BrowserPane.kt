package de.joinnoah.pi.remote

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BrowserPaneActions(
    val open: () -> Boolean = { false }, val acquire: () -> Boolean = { false }, val refresh: () -> Boolean = { false },
    val newTab: (String) -> Boolean = { false }, val navigate: (String) -> Boolean = { false },
    val selectTab: (String) -> Unit = {}, val closeTab: (String) -> Boolean = { false },
    val handoff: (String) -> Boolean = { false }, val resize: (Int, Int, Double, String) -> Boolean = { _, _, _, _ -> false },
    val input: (BrowserInput) -> Boolean = { false }, val visible: (Boolean) -> Unit = {},
)

private data class PendingBrowserInput(val input: BrowserInput, val submittedText: String? = null)
private data class BrowserFlight(val item: PendingBrowserInput, val baseline: BrowserInputReceipt?)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BrowserPane(view: BrowserView?, actions: BrowserPaneActions, onClose: () -> Unit, onUnsentChanged: (Boolean) -> Unit = {}) {
    val active = LocalTerminalKeyboardActive.current
    val focusOwner = LocalTerminalInputFocus.current
    val focusToken = remember { Any() }
    val focusRequester = remember { FocusRequester() }
    val held = remember { mutableStateListOf<String>() }
    val releasing = remember { mutableSetOf<String>() }
    var pointerPosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pointerButton by remember { mutableStateOf("left") }
    var heldRevision by remember { mutableStateOf<Long?>(null) }
    val latestActions by rememberUpdatedState(actions)
    val latestUnsent by rememberUpdatedState(onUnsentChanged)
    DisposableEffect(active) {
        if (active) latestActions.visible(true)
        onDispose { latestActions.visible(false); focusOwner?.update(focusToken, false) }
    }
    var url by rememberSaveable(view?.sessionId) { mutableStateOf("") }
    var urlDirty by rememberSaveable(view?.sessionId) { mutableStateOf(false) }
    var draft by rememberSaveable(view?.sessionId) { mutableStateOf("") }
    val queue = remember(view?.sessionId) { mutableStateListOf<PendingBrowserInput>() }
    var flight by remember(view?.sessionId) { mutableStateOf<BrowserFlight?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var requestedSize by remember(view?.sessionId, view?.generation, view?.selectedTabId) { mutableStateOf<Triple<Int, Int, Double>?>(null) }
    var resizeRevision by remember(view?.sessionId, view?.generation, view?.selectedTabId) { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current.density
    val selected = view?.tabs?.firstOrNull { it.id == view.selectedTabId }
    LaunchedEffect(selected?.id, selected?.url) { if (!urlDirty) url = selected?.url.orEmpty() }
    LaunchedEffect(view?.generation, view?.ownsControl, active, view?.sessionId) {
        queue.clear(); flight = null; held.clear(); releasing.clear(); pointerPosition = null; heldRevision = null
    }
    LaunchedEffect(view?.receipt, view?.inputPending, view?.ownsControl, flight, queue.size) {
        val f = flight
        if (f != null && view?.inputPending == false && view.receipt != null && view.receipt != f.baseline) {
            if (view.receipt.status == "accepted" && view.ownsControl) {
                if (f.item.submittedText != null && draft == f.item.submittedText) draft = ""
                val released = when (val input = f.item.input) {
                    is BrowserInput.Key -> if (input.action == "up") "key:${input.code}" else null
                    is BrowserInput.Pointer -> if (input.action == "up") "pointer:${input.button}" else null
                    else -> null
                }
                if (released != null) { held.remove(released); releasing.remove(released); if (held.isEmpty()) heldRevision = null }
                flight = null
            } else { queue.clear(); flight = null; held.clear(); releasing.clear(); pointerPosition = null; heldRevision = null }
        }
        if (flight == null && view?.ownsControl == true && !view.inputPending && active && queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            flight = BrowserFlight(next, view.receipt)
            if (!latestActions.input(next.input)) {
                queue.clear(); flight = null
                if (held.isNotEmpty()) { latestActions.visible(false); latestActions.visible(true) }
            }
        }
    }
    SideEffect { latestUnsent(urlDirty || draft.isNotEmpty() || queue.isNotEmpty() || flight != null) }
    DisposableEffect(Unit) { onDispose { latestUnsent(false) } }
    LaunchedEffect(size, density, view?.ownsControl, view?.generation, view?.selectedTabId, active, held.size) {
        if (active && view?.ownsControl == true && held.isEmpty() && size.width > 0 && size.height > 0) {
            val width = (size.width / density).toInt().coerceIn(80, 2048)
            val height = (size.height / density).toInt().coerceIn(80, 2048)
            val scale = density.toDouble().coerceIn(0.5, 3.0)
            val wanted = Triple(width, height, scale)
            if (wanted != requestedSize && latestActions.resize(width, height, scale, if (width >= height) "landscape" else "portrait")) {
                requestedSize = wanted; resizeRevision = view.viewportRevision
            }
        }
    }
    LaunchedEffect(view?.viewportRevision) {
        if (resizeRevision != null && resizeRevision != view?.viewportRevision) resizeRevision = null
        if (held.isNotEmpty() && heldRevision != null && heldRevision != view?.viewportRevision) {
            latestActions.visible(false); latestActions.visible(true)
        }
    }
    val image = view?.image?.takeIf { it.viewportRevision == view.viewportRevision }
    var decoded by remember(view?.sessionId, view?.browserId, view?.generation, view?.selectedTabId, view?.viewportRevision) { mutableStateOf<Pair<BrowserImage, android.graphics.Bitmap>?>(null) }
    LaunchedEffect(image, view?.sessionId, view?.browserId, view?.generation, view?.selectedTabId, view?.viewportRevision) {
        if (image != null) {
            val result = withContext(Dispatchers.Default) { decodeBrowserImage(image) }
            if (result != null) decoded = image to result
        }
    }
    val bitmap = decoded?.second
    val canInput = active && view?.ownsControl == true && image != null && decoded?.first === image && resizeRevision == null
    val canRelease = active && view?.ownsControl == true && (heldRevision == null || heldRevision == view.viewportRevision)
    val latestCanRelease by rememberUpdatedState(canRelease)
    val latestCanInput by rememberUpdatedState(canInput)
    val latestView by rememberUpdatedState(view)
    val latestImage by rememberUpdatedState(image)
    fun enqueue(item: PendingBrowserInput): Boolean {
        val input = item.input
        val id = when (input) {
            is BrowserInput.Pointer -> "pointer:${input.button}"
            is BrowserInput.Key -> "key:${input.code}"
            else -> null
        }
        val down = when (input) { is BrowserInput.Pointer -> input.action == "down"; is BrowserInput.Key -> input.action == "down"; else -> false }
        val up = when (input) { is BrowserInput.Pointer -> input.action == "up"; is BrowserInput.Key -> input.action == "up"; else -> false }
        if ((!up && !latestCanInput) || (up && !latestCanRelease)) return false
        if (up && (id == null || id !in held || id in releasing)) return false
        if (down && id in held) return false
        if (input is BrowserInput.Pointer && input.action == "move") {
            val last = queue.lastOrNull()?.input
            if (last is BrowserInput.Pointer && last.action == "move" && last.button == input.button) {
                queue[queue.lastIndex] = item
                return true
            }
        }
        val occupied = queue.size + (if (flight == null) 0 else 1) + held.size
        if (!up && occupied + (if (down) 2 else 1) > 32) return false
        if (down && id != null) { if (held.isEmpty()) heldRevision = latestView?.viewportRevision; held.add(id) }
        if (up && id != null) releasing.add(id)
        queue.add(item); return true
    }
    val latestEnqueue by rememberUpdatedState<(PendingBrowserInput) -> Boolean>({ enqueue(it) })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.browser_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { latestActions.refresh() }) { Text(stringResource(R.string.browser_refresh)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.browser_close)) }
        }
        if (view == null || view.opening) {
            Text(stringResource(R.string.browser_opening)); return@Column
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (view.ownsControl) stringResource(R.string.browser_control_you) else stringResource(R.string.browser_control_readonly), Modifier.weight(1f))
            if (!view.ownsControl) TextButton(onClick = { latestActions.acquire() }, modifier = Modifier.testTag("browserAcquire")) { Text(stringResource(R.string.browser_acquire)) }
            if (view.ownsControl && view.participants.any { it != view.owner }) {
                var expanded by remember { mutableStateOf(false) }
                Box { TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.browser_handoff)) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        view.participants.filter { it != view.owner }.forEach { participant -> DropdownMenuItem(text = { Text("${participant.kind}: ${participant.id}") }, onClick = { expanded = false; latestActions.handoff(participant.id) }) }
                    }
                }
            }
        }
        if (view.notice != null) Text(stringResource(when (view.notice) {
            BrowserNotice.BUSY -> R.string.browser_busy; BrowserNotice.UNCERTAIN -> R.string.browser_uncertain
            BrowserNotice.DISCONNECTED -> R.string.browser_disconnected; BrowserNotice.FAILED -> R.string.browser_failed
            BrowserNotice.FRAME_FAILED -> R.string.browser_frame_failed
        }), color = MaterialTheme.colorScheme.error)
        Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            view.tabs.forEach { tab ->
                TextButton(onClick = { latestActions.selectTab(tab.id) }, enabled = held.isEmpty()) { Text(if (tab.title.isBlank()) tab.url else tab.title) }
                if (view.ownsControl) {
                    val closeLabel = stringResource(R.string.browser_close_tab, if (tab.title.isBlank()) tab.url else tab.title)
                    TextButton(onClick = { latestActions.closeTab(tab.id) }, enabled = held.isEmpty(), modifier = Modifier.semantics { contentDescription = closeLabel }) { Text("×") }
                }
            }
            if (view.ownsControl) TextButton(onClick = { latestActions.newTab("about:blank") }, enabled = held.isEmpty()) { Text(stringResource(R.string.browser_new_tab)) }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it; urlDirty = true }, Modifier.weight(1f), singleLine = true, enabled = view.ownsControl, label = { Text(stringResource(R.string.browser_address)) })
            TextButton(onClick = { if (latestActions.navigate(url)) urlDirty = false }, enabled = view.ownsControl && held.isEmpty() && url.isNotBlank()) { Text(stringResource(R.string.browser_go)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag("browserViewport").background(Color.Black).onSizeChanged { size = it }
            .onFocusChanged { focusOwner?.update(focusToken, active && it.hasFocus) }
            .onPreviewKeyEvent { event ->
                val pressedCodes = held.filter { it.startsWith("key:") }.map { it.removePrefix("key:") }.toSet()
                val input = browserHardwareInput(event.nativeKeyEvent, latestCanInput || latestCanRelease && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP, pressedCodes)
                input != null && latestEnqueue(PendingBrowserInput(input))
            }.focusRequester(focusRequester).focusable(enabled = canRelease)
            .pointerInteropFilter { event ->
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
            }, contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.browser_image), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(stringResource(if (image == null) R.string.browser_waiting else R.string.browser_frame_failed), color = Color.White)
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag("browserText"), enabled = view.ownsControl, label = { Text(stringResource(R.string.browser_text)) })
            TextButton(onClick = { enqueue(PendingBrowserInput(BrowserInput.Text(draft), draft)) }, enabled = canInput && draft.isNotBlank() && flight == null && queue.isEmpty(), modifier = Modifier.testTag("browserSendText")) { Text(stringResource(R.string.browser_send)) }
        }
        Row(Modifier.padding(horizontal = 12.dp)) { listOf("Enter", "Tab", "Escape").forEach { key -> TextButton(onClick = { enqueue(PendingBrowserInput(BrowserInput.Key("down", key, key))); enqueue(PendingBrowserInput(BrowserInput.Key("up", key, key))) }, enabled = canInput) { Text(key) } } }
    }
}
