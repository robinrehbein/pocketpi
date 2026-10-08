package de.joinnoah.pi.remote

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Above this many characters Copy is off: a larger clip can crash the clipboard binder. Share still works. */
internal const val ARTIFACT_COPY_MAX_CHARS = 200_000

/** The code tab shows at most this many characters of a line; Copy still copies the whole text. */
internal const val ARTIFACT_CODE_LINE_CHARS = 2_000

/**
 * Full-screen view of an HTML artifact or a Mermaid diagram: a Preview in a fresh sandboxed WebView
 * (one per viewer, destroyed on close) and a Code tab. [reload] fetches the current source from the
 * Mac and is null where reloading makes no sense (a diagram in the message text).
 */
@Composable
internal fun ArtifactViewer(
    kind: ArtifactKind,
    path: String,
    title: String,
    initialText: String,
    reload: (suspend () -> String?)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initialText) }
    // A new number gives a new sandboxed view, even when the reloaded text is the same.
    var generation by remember { mutableIntStateOf(0) }
    var showCode by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = if (kind == ArtifactKind.Html) android.graphics.Color.WHITE else MaterialTheme.colorScheme.surface.toArgb()
    val references = remember(text) { if (kind == ArtifactKind.Html) externalReferences(text) else emptyList() }
    val fileName = path.substringAfterLast('/')
    val exportFailed = stringResource(R.string.remote_artifact_export_failed)
    val reloadFailed = stringResource(R.string.remote_artifact_reload_failed)
    val copyFailed = stringResource(R.string.remote_artifact_copy_failed)
    val shareTitle = stringResource(R.string.remote_artifact_share_title)
    val mime = if (kind == ArtifactKind.Html) "text/html" else "text/plain"
    val exportName = remember(path, kind) { ArtifactStorage.safeName(path, if (kind == ArtifactKind.Html) "html" else "mmd") }

    suspend fun export() =
        withContext(Dispatchers.IO) { runCatching { storeArtifact(context, exportName, text) }.getOrNull() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("artifactViewer")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("artifactViewerClose")) {
                        Icon(Icons.Default.Close, stringResource(R.string.remote_artifact_close))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (reload != null)
                        IconButton(
                            enabled = !busy,
                            modifier = Modifier.testTag("artifactViewerReload"),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val fresh = reload()
                                        if (fresh == null) Toast.makeText(context, reloadFailed, Toast.LENGTH_SHORT).show()
                                        else {
                                            text = fresh
                                            generation++
                                            previewFailed = false
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) { Icon(Icons.Default.Refresh, stringResource(R.string.remote_artifact_reload)) }
                    IconButton(
                        enabled = !busy,
                        modifier = Modifier.testTag("artifactViewerShare"),
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val uri = export()
                                    if (uri == null) Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                                    else context.startActivity(artifactShareIntent(uri, mime, shareTitle))
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Icon(Icons.Default.Share, stringResource(R.string.remote_artifact_share)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !showCode,
                        onClick = { showCode = false },
                        label = { Text(stringResource(R.string.remote_artifact_preview)) },
                        modifier = Modifier.testTag("artifactTabPreview"),
                    )
                    FilterChip(
                        selected = showCode,
                        onClick = { showCode = true },
                        label = { Text(stringResource(R.string.remote_artifact_code)) },
                        modifier = Modifier.testTag("artifactTabCode"),
                    )
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.remote_artifact_libraries_only)) },
                        modifier = Modifier.testTag("artifactLibrariesOnly"),
                    )
                }
                if (references.isNotEmpty() && !showCode)
                    Text(
                        stringResource(R.string.remote_artifact_external_references),
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("artifactExternalNotice"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                    if (showCode) {
                        val lines = remember(text) { text.lines() }
                        Column(Modifier.fillMaxSize()) {
                            val copyAllowed = text.length <= ARTIFACT_COPY_MAX_CHARS
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    enabled = copyAllowed,
                                    onClick = {
                                        runCatching { clipboard.setText(AnnotatedString(text)) }
                                            .onFailure { Toast.makeText(context, copyFailed, Toast.LENGTH_SHORT).show() }
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp).testTag("artifactCopy"),
                                ) { Text(stringResource(R.string.remote_copy_code)) }
                                if (!copyAllowed)
                                    Text(
                                        stringResource(R.string.remote_artifact_copy_too_large),
                                        Modifier.weight(1f).testTag("artifactCopyTooLarge"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                                items(lines.size) { index ->
                                    Text(
                                        lines[index].take(ARTIFACT_CODE_LINE_CHARS),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    } else if (previewFailed)
                        Text(
                            stringResource(if (kind == ArtifactKind.Html) R.string.remote_artifact_preview_failed else R.string.remote_mermaid_failed),
                            Modifier.align(Alignment.Center).padding(16.dp).testTag("artifactPreviewFailed"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    else
                        key(generation) {
                            val page = remember { ArtifactPage(kind, text, dark) }
                            AndroidView(
                                factory = { ctx ->
                                    createArtifactWebView(
                                        ctx,
                                        page,
                                        ArtifactCallbacks(
                                            onTitle = { if (kind == ArtifactKind.Mermaid && it == "error") previewFailed = true },
                                            onFailed = { previewFailed = true },
                                        ),
                                        background,
                                    )
                                },
                                onRelease = ::disposeArtifactWebView,
                                modifier = Modifier.fillMaxSize().testTag("artifactPreview"),
                            )
                        }
                }
            }
        }
    }
}
