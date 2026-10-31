package de.joinnoah.pi.remote

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal data class EmulatorPaneActions(val select: (String)->Boolean, val acquire: ()->Boolean, val handoff: (String)->Boolean, val stop: ()->Boolean, val input: (EmulatorInput)->Boolean, val refresh: ()->Boolean, val visible: (Boolean)->Unit)

@Composable internal fun EmulatorPane(view: EmulatorView, actions: EmulatorPaneActions, close: ()->Unit) {
    DisposableEffect(actions) { actions.visible(true); onDispose { actions.visible(false) } }
    var confirmStop by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.emulator_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = close) { Text(stringResource(R.string.browser_close)) }
        }
        if (view.opening) LinearProgressIndicator(Modifier.fillMaxWidth())
        view.notice?.let { Text(stringResource(when (it) { "busy" -> R.string.emulator_busy; "disconnected" -> R.string.emulator_disconnected; "uncertain" -> R.string.emulator_uncertain; else -> R.string.emulator_failed })) }
        if (view.emulatorId == null) {
            Text(stringResource(R.string.emulator_select_profile))
            view.profiles.forEach { profile -> TextButton(onClick = { actions.select(profile.id) }, enabled = !view.opening) { Text(profile.label) } }
        } else {
            Text(view.label ?: "")
            Row {
                TextButton(onClick = { actions.acquire() }, enabled = !view.ownsControl && !view.opening) { Text(stringResource(R.string.emulator_acquire)) }
                TextButton(onClick = { actions.refresh() }) { Text(stringResource(R.string.remote_refresh)) }
                TextButton(onClick = { confirmStop = true }, enabled = view.ownsControl) { Text(stringResource(R.string.emulator_stop)) }
            }
            if (view.ownsControl) view.participants.filter { it != view.owner }.forEach { owner ->
                TextButton(onClick = { actions.handoff(owner.id) }) { Text(stringResource(R.string.emulator_handoff, owner.kind, owner.id)) }
            }
            val image = view.image
            if (image != null) {
                val bitmap = remember(image) { BitmapFactory.decodeByteArray(image.png, 0, image.png.size)?.asImageBitmap() }
                if (bitmap != null) Image(bitmap, contentDescription = stringResource(R.string.emulator_screen), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 600.dp).testTag("emulatorScreen").pointerInput(image.width, image.height, image.displayWidth, image.displayHeight, image.rotation, image.revision, view.ownsControl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true); val start = down.position; val startTime = down.uptimeMillis
                            var last = start; var endTime = startTime; var canceled = down.isConsumed; var finished = false
                            while (!finished && !canceled) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) { canceled = true; break }
                                last = change.position; endTime = change.uptimeMillis
                                if (!change.pressed) finished = true
                                else if (kotlin.math.abs(start.x-last.x) + kotlin.math.abs(start.y-last.y) >= 8f) change.consume()
                            }
                            val scale = min(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                            val left = (size.width - image.width * scale) / 2f; val top = (size.height - image.height * scale) / 2f
                            fun point(x: Float, y: Float): Pair<Long,Long>? {
                                if (x < left || y < top || x >= left + image.width * scale || y >= top + image.height * scale) return null
                                return ((x-left) / (image.width*scale) * image.displayWidth).toLong().coerceIn(0,image.displayWidth-1) to ((y-top) / (image.height*scale) * image.displayHeight).toLong().coerceIn(0,image.displayHeight-1)
                            }
                            val a = point(start.x,start.y); val b = point(last.x,last.y)
                            if (finished && !canceled && currentView.ownsControl && !currentView.inputPending && a != null && b != null) {
                                if (kotlin.math.abs(start.x-last.x) + kotlin.math.abs(start.y-last.y) < 8f) actions.input(EmulatorInput.Tap(a.first,a.second))
                                else actions.input(EmulatorInput.Swipe(a.first,a.second,b.first,b.second,(endTime-startTime).coerceIn(50,1000)))
                            }
                        }
                    })
            }
            listOf(listOf("Home", "Back", "Enter", "Tab", "Escape"), listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Backspace")).forEach { row ->
                Row { row.forEach { key -> TextButton(onClick = { actions.input(EmulatorInput.Key(key)) }, enabled = view.ownsControl && image != null && !view.inputPending, contentPadding = PaddingValues(4.dp)) { Text(key) } } }
            }
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(stringResource(R.string.emulator_stop_confirm)) }, confirmButton = { TextButton(onClick = { confirmStop = false; actions.stop() }) { Text(stringResource(R.string.emulator_stop)) } }, dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.remote_terminal_cancel)) } })
}
