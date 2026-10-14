package de.joinnoah.pi.remote

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
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
 * What an artifact viewer shows: the page's [text], a [generation] that gives the preview a fresh
 * sandboxed view, and the Code tab. The inspector's tab and its popped-out dialog share one, so a
 * reload in either shows in both.
 */
@Stable
internal class ArtifactViewerState(initialText: String) {
    var text by mutableStateOf(initialText)
        private set
    // A new number gives a new sandboxed view, even when the reloaded text is the same.
    var generation by mutableIntStateOf(0)
        private set
    var showCode by mutableStateOf(false)
    var previewFailed by mutableStateOf(false)
    var busy by mutableStateOf(false)

    /** Shows what [fetch] returns; false when it returned nothing and the page stays as it is. */
    suspend fun reload(fetch: suspend () -> String?): Boolean {
        busy = true
        try {
            val fresh = fetch() ?: return false
            text = fresh
            generation++
            previewFailed = false
            return true
        } finally {
            busy = false
        }
    }
}

/**
 * Full-screen view of an HTML artifact or a Mermaid diagram: a Preview in a fresh sandboxed WebView
 * (one per viewer, destroyed on close) and a Code tab. [reload] fetches the current source from the
 * Mac and is null where reloading makes no sense (a diagram in the message text). The inspector's
 * tab pops out into this dialog with its own [state].
 */
@Composable
internal fun ArtifactViewer(
    kind: ArtifactKind,
    path: String,
    title: String,
    initialText: String,
    reload: (suspend () -> String?)?,
    onDismiss: () -> Unit,
    state: ArtifactViewerState = remember { ArtifactViewerState(initialText) },
    onReloaded: () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ArtifactViewerBody(
            state, kind, path, title, reload,
            onClose = onDismiss,
            onReloaded = onReloaded,
            modifier = Modifier.testTag("artifactViewer"),
            insets = Modifier.statusBarsPadding().navigationBarsPadding(),
        )
    }
}

/**
 * The viewer apart from its dialog, for the dialog and the inspector's tab. [onClose] adds the
 * close button the dialog needs, [onPopOut] the button that opens the dialog from the tab;
 * [notice] shows under the title and [chips] after the Preview and Code chips.
 */
@Composable
internal fun ArtifactViewerBody(
    state: ArtifactViewerState,
    kind: ArtifactKind,
    path: String,
    title: String,
    reload: (suspend () -> String?)?,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onPopOut: (() -> Unit)? = null,
    onReloaded: () -> Unit = {},
    insets: Modifier = Modifier,
    notice: (@Composable () -> Unit)? = null,
    chips: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val text = state.text
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = if (kind == ArtifactKind.Html) android.graphics.Color.WHITE else MaterialTheme.colorScheme.surface.toArgb()
    val references = remember(text) { if (kind == ArtifactKind.Html) externalReferences(text) else emptyList() }
    val exportFailed = stringResource(R.string.remote_artifact_export_failed)
    val reloadFailed = stringResource(R.string.remote_artifact_reload_failed)
    val copyFailed = stringResource(R.string.remote_artifact_copy_failed)
    val shareTitle = stringResource(R.string.remote_artifact_share_title)
    val mime = if (kind == ArtifactKind.Html) "text/html" else "text/plain"
    val exportName = remember(path, kind) { ArtifactStorage.safeName(path, if (kind == ArtifactKind.Html) "html" else "mmd") }

    suspend fun export() =
        withContext(Dispatchers.IO) { runCatching { storeArtifact(context, exportName, state.text) }.getOrNull() }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(insets)) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (onClose != null) 4.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onClose != null)
                    IconButton(onClick = onClose, modifier = Modifier.testTag("artifactViewerClose")) {
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
                        enabled = !state.busy,
                        modifier = Modifier.testTag("artifactViewerReload"),
                        onClick = {
                            scope.launch {
                                if (state.reload(reload)) onReloaded()
                                else Toast.makeText(context, reloadFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Icon(Icons.Default.Refresh, stringResource(R.string.remote_artifact_reload)) }
                IconButton(
                    enabled = !state.busy,
                    modifier = Modifier.testTag("artifactViewerShare"),
                    onClick = {
                        scope.launch {
                            state.busy = true
                            try {
                                val uri = export()
                                if (uri == null) Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                                else context.startActivity(artifactShareIntent(uri, mime, shareTitle))
                            } finally {
                                state.busy = false
                            }
                        }
                    },
                ) { Icon(Icons.Default.Share, stringResource(R.string.remote_artifact_share)) }
                if (onPopOut != null)
                    IconButton(onClick = onPopOut, modifier = Modifier.testTag("artifactPopOut")) {
                        Icon(Icons.Default.Fullscreen, stringResource(R.string.remote_inspector_pop_out))
                    }
            }
            notice?.invoke()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.showCode,
                    onClick = { state.showCode = false },
                    label = { Text(stringResource(R.string.remote_artifact_preview)) },
                    modifier = Modifier.testTag("artifactTabPreview"),
                )
                FilterChip(
                    selected = state.showCode,
                    onClick = { state.showCode = true },
                    label = { Text(stringResource(R.string.remote_artifact_code)) },
                    modifier = Modifier.testTag("artifactTabCode"),
                )
                chips()
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.remote_artifact_libraries_only)) },
                    modifier = Modifier.testTag("artifactLibrariesOnly"),
                )
            }
            if (references.isNotEmpty() && !state.showCode)
                Text(
                    stringResource(R.string.remote_artifact_external_references),
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("artifactExternalNotice"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                if (state.showCode) {
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
                } else if (state.previewFailed)
                    Text(
                        stringResource(if (kind == ArtifactKind.Html) R.string.remote_artifact_preview_failed else R.string.remote_mermaid_failed),
                        Modifier.align(Alignment.Center).padding(16.dp).testTag("artifactPreviewFailed"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else
                    // A dark mode switch no longer recreates the activity: a new view draws the
                    // page in the new colors, while rotation and resizing keep the loaded one.
                    key(state.generation, dark) {
                        val page = remember { ArtifactPage(kind, text, dark) }
                        AndroidView(
                            factory = { ctx ->
                                createArtifactWebView(
                                    ctx,
                                    page,
                                    ArtifactCallbacks(
                                        onTitle = { if (kind == ArtifactKind.Mermaid && it == "error") state.previewFailed = true },
                                        onFailed = { state.previewFailed = true },
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
