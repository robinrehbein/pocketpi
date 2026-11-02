package de.joinnoah.pi.remote

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local-only dialog. The only saved state for an in-flight export is a marker, never its text. */
@Composable
internal fun EditorRecoveryDialog(
    load: suspend () -> EditorRecoveryLoad,
    onExportSaved: (Uri) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var request by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<EditorRecoveryLoad?>(null) }
    var selected by remember { mutableStateOf<EditorRecoveryEntry?>(null) }
    val pending = remember { PendingEditorRecoveryExport() }
    var pendingMarker by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (pendingMarker && !pending.hasEntry()) {
            pendingMarker = false
            status = R.string.editor_recovery_export_lost
        }
    }
    LaunchedEffect(request) {
        result = null
        result = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { EditorRecoveryLoad.Failed }
    }
    fun complete(uri: Uri?) {
        val frozen = pending.take()
        pendingMarker = false
        if (frozen == null) {
            status = R.string.editor_recovery_export_lost
        } else if (uri != null) {
            writing = true
            scope.launch {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        val format = editorRecoveryExportFormat(frozen.text)
                        context.contentResolver.openOutputStream(uri, "w")?.use { writeEditorRecovery(frozen.text, format, it) }
                            ?: error("No output stream")
                    }
                    true
                } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                writing = false
                if (ok) onExportSaved(uri)
                status = if (ok) R.string.editor_recovery_export_saved else R.string.editor_recovery_export_failed
            }
        }
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), ::complete)
    val binaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), ::complete)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.editor_recovery_title), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_recovery_close)) }
                }
                status?.let { Text(stringResource(it), modifier = Modifier.testTag("recoveryExportStatus")) }
                if (pendingMarker) Text(stringResource(R.string.editor_recovery_export_pending))
                when (val current = result) {
                    null -> Text(stringResource(R.string.editor_recovery_loading))
                    EditorRecoveryLoad.Failed, EditorRecoveryLoad.Unavailable -> {
                        Text(stringResource(if (current == EditorRecoveryLoad.Failed) R.string.editor_recovery_error else R.string.editor_recovery_unavailable), Modifier.testTag("recoveryError"))
                        TextButton(onClick = { request++ }, modifier = Modifier.testTag("recoveryRetry")) { Text(stringResource(R.string.editor_recovery_retry)) }
                    }
                    is EditorRecoveryLoad.Ready -> {
                        if (current.entries.isEmpty()) Text(stringResource(R.string.editor_recovery_empty), Modifier.testTag("recoveryEmpty"))
                        else BoxWithConstraints(Modifier.weight(1f)) {
                            val wide = maxWidth >= 720.dp
                            Row(Modifier.fillMaxSize()) {
                                if (wide || selected == null) {
                                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                        current.entries.forEach { entry ->
                                            TextButton(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth().testTag(if (entry.kind == EditorRecoveryKind.DRAFT) "recoveryEntryDraft" else "recoveryEntryUncertain")) {
                                                Column(Modifier.fillMaxWidth()) {
                                                    Text(stringResource(if (entry.kind == EditorRecoveryKind.DRAFT) R.string.editor_recovery_draft else R.string.editor_recovery_uncertain))
                                                    RecoveryLabels(entry)
                                                }
                                            }
                                        }
                                    }
                                }
                                selected?.let { entry ->
                                    Column(Modifier.weight(if (wide) 2f else 1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                        TextButton(onClick = { selected = null }, modifier = Modifier.testTag("recoveryBackToList")) { Text(stringResource(R.string.editor_recovery_back)) }
                                        key(entry.id) {
                                            RecoveryDetail(entry, enabled = !pendingMarker && !writing) {
                                                pending.begin(entry)
                                                pendingMarker = true
                                                status = null
                                                val format = editorRecoveryExportFormat(entry.text)
                                                try {
                                                    if (format.extension == "txt") textLauncher.launch("editor-recovery.txt") else binaryLauncher.launch("editor-recovery.utf16")
                                                } catch (_: ActivityNotFoundException) {
                                                    pending.take()
                                                    pendingMarker = false
                                                    status = R.string.editor_recovery_export_failed
                                                } catch (_: SecurityException) {
                                                    pending.take()
                                                    pendingMarker = false
                                                    status = R.string.editor_recovery_export_failed
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryLabels(entry: EditorRecoveryEntry) {
    val host = entry.hostName ?: stringResource(R.string.editor_recovery_unknown_host, entry.key.routeId)
    Text(stringResource(R.string.editor_recovery_host, host), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.editor_recovery_session, entry.key.sessionId), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.editor_recovery_question, entry.key.questionId), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RecoveryDetail(entry: EditorRecoveryEntry, enabled: Boolean, onExport: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var window by remember(entry.id) { mutableStateOf(EditorWindows.atOffset(entry.text, 0)) }
    var section by remember(entry.id) { mutableStateOf("1") }
    val text = entry.text
    val preview = text.substring(window.start, window.end)
    fun navigate(next: EditorWindow) {
        window = next
        section = (next.start / EditorWindows.MAX_UNITS + 1).toString()
    }
    RecoveryLabels(entry)
    if (entry.kind == EditorRecoveryKind.UNCERTAIN_ATTEMPT) Text(stringResource(R.string.editor_recovery_warning), Modifier.testTag("recoveryUncertainWarning"))
    key(entry.id, window.start, window.end) {
        Text(
            preview,
            Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()).testTag("recoveryPreview"),
        )
    }
    Row {
        TextButton(onClick = { navigate(EditorWindows.atOffset(text, 0)) }, modifier = Modifier.testTag("recoveryFirst")) { Text(stringResource(R.string.editor_recovery_first)) }
        TextButton(onClick = { EditorWindows.previous(text, window)?.let { navigate(it) } }) { Text(stringResource(R.string.editor_recovery_previous)) }
        TextButton(onClick = { EditorWindows.next(text, window)?.let { navigate(it) } }) { Text(stringResource(R.string.editor_recovery_next)) }
        TextButton(onClick = { navigate(EditorWindows.last(text)) }, modifier = Modifier.testTag("recoveryLast")) { Text(stringResource(R.string.editor_recovery_last)) }
    }
    Row {
        OutlinedTextField(value = section, onValueChange = { section = it }, modifier = Modifier.weight(1f).testTag("recoverySection"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.editor_recovery_section, EditorWindows.sectionCount(text))) })
        val requested = section.toIntOrNull()
        TextButton(onClick = { requested?.let { navigate(EditorWindows.atSection(text, it)) } }, enabled = requested != null && requested in 1..EditorWindows.sectionCount(text), modifier = Modifier.testTag("recoveryJump")) { Text(stringResource(R.string.editor_recovery_jump)) }
    }
    TextButton(onClick = { clipboard.setText(AnnotatedString(preview)) }, modifier = Modifier.testTag("recoveryCopy")) { Text(stringResource(R.string.editor_recovery_copy)) }
    val format = remember(text) { editorRecoveryExportFormat(text) }
    if (format.extension == "utf16") Text(stringResource(R.string.editor_recovery_binary))
    TextButton(onClick = onExport, enabled = enabled, modifier = Modifier.testTag("recoveryExport")) { Text(stringResource(R.string.editor_recovery_export)) }
}
