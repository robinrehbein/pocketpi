package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ProjectArtifactState {
    data object Loading : ProjectArtifactState
    class Ready(val artifact: ProjectArtifactResult.Loaded) : ProjectArtifactState
    data object TooLarge : ProjectArtifactState
    data object NotAnArtifact : ProjectArtifactState
    data object Unavailable : ProjectArtifactState
    data object UnsupportedHost : ProjectArtifactState
    data object Busy : ProjectArtifactState
    data object ConnectionFailure : ProjectArtifactState
}

internal fun ProjectArtifactResult.toCardState(): ProjectArtifactState =
    when (this) {
        is ProjectArtifactResult.Loaded -> ProjectArtifactState.Ready(this)
        ProjectArtifactResult.UnsupportedHost -> ProjectArtifactState.UnsupportedHost
        ProjectArtifactResult.Unavailable -> ProjectArtifactState.Unavailable
        ProjectArtifactResult.TooLarge -> ProjectArtifactState.TooLarge
        ProjectArtifactResult.NotAnArtifact -> ProjectArtifactState.NotAnArtifact
        ProjectArtifactResult.Busy -> ProjectArtifactState.Busy
        ProjectArtifactResult.ConnectionFailure -> ProjectArtifactState.ConnectionFailure
    }

/** Reads the artifact at [path]; right after connecting, capabilities are unknown and the answer waits. */
internal suspend fun ProjectImageSource.artifactCardState(path: String): ProjectArtifactState {
    if (connected && !capabilitiesKnown) return ProjectArtifactState.Loading
    return readArtifact(sessionId, path, false).toCardState()
}

/** A state that may succeed when asked again. */
internal fun ProjectArtifactState.retryable() =
    this == ProjectArtifactState.ConnectionFailure || this == ProjectArtifactState.Busy

private val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private const val TITLE_SCAN_CHARS = 4096
private const val TITLE_MAX_CHARS = 80

/** The `<title>` found in the first 4 KB of [html], else [fallback]. */
internal fun artifactTitle(html: String, fallback: String): String {
    val raw = titleRegex.find(html.take(TITLE_SCAN_CHARS))?.groupValues?.get(1) ?: return fallback
    val title =
        raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(TITLE_MAX_CHARS)
    return title.ifEmpty { fallback }
}

/** Mermaid sources above these limits are shown as code only. */
internal const val MERMAID_MAX_BYTES = 100_000
internal const val MERMAID_MAX_LINES = 2_000

internal fun mermaidTooLarge(code: String): Boolean =
    code.toByteArray(Charsets.UTF_8).size > MERMAID_MAX_BYTES || code.count { it == '\n' } + 1 > MERMAID_MAX_LINES

/** The diagram kind, from the first line that is not blank, a `%%` comment or front matter. */
internal fun mermaidDiagramType(code: String): String {
    var frontMatter = false
    for (raw in code.lineSequence()) {
        val line = raw.trim()
        if (line == "---") {
            frontMatter = !frontMatter
            continue
        }
        if (frontMatter || line.isEmpty() || line.startsWith("%%")) continue
        return line.split(Regex("[\\s;:{]"), limit = 2).first().take(32)
    }
    return ""
}

internal fun mermaidLineCount(code: String): Int = code.trimEnd().count { it == '\n' } + 1

@Composable
private fun ArtifactMessage(state: ProjectArtifactState, onRetry: () -> Unit) {
    Column(
        Modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state == ProjectArtifactState.Loading) CircularProgressIndicator(Modifier.size(24.dp))
        Text(
            stringResource(
                when (state) {
                    ProjectArtifactState.Loading -> R.string.remote_artifact_loading
                    ProjectArtifactState.TooLarge -> R.string.remote_artifact_too_large
                    ProjectArtifactState.NotAnArtifact -> R.string.remote_artifact_not_an_artifact
                    ProjectArtifactState.UnsupportedHost -> R.string.remote_artifact_unsupported
                    ProjectArtifactState.Busy -> R.string.remote_artifact_busy
                    ProjectArtifactState.ConnectionFailure -> R.string.remote_artifact_connection_failure
                    else -> R.string.remote_artifact_unavailable
                }
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        if (state.retryable())
            TextButton(onClick = onRetry, modifier = Modifier.testTag("projectArtifactRetry")) {
                Text(stringResource(R.string.remote_image_retry))
            }
    }
}

/**
 * An HTML file the agent mentioned. Like the image card it keeps one shape from loading to loaded and
 * fetches only while composed; a tap opens the sandboxed viewer.
 */
@Composable
internal fun ProjectArtifactCard(path: String, source: ProjectImageSource, modifier: Modifier = Modifier) {
    var state by remember(source.sessionId, path) { mutableStateOf<ProjectArtifactState>(ProjectArtifactState.Loading) }
    var requests by remember(source.sessionId, path) { mutableIntStateOf(0) }
    var viewing by remember(source.sessionId, path) { mutableStateOf(false) }
    LaunchedEffect(source.sessionId, path, source.connected, source.capabilitiesKnown, source.artifactSupported, requests) {
        // A page already shown stays through connection changes.
        if (state !is ProjectArtifactState.Ready) {
            state = ProjectArtifactState.Loading
            state = source.artifactCardState(path)
        }
    }
    val fileName = path.substringAfterLast('/')
    val ready = state as? ProjectArtifactState.Ready
    val open = stringResource(R.string.remote_artifact_open, fileName)
    val viewers = LocalInspectorViewers.current
    val reload: suspend () -> String? = { (source.readArtifact(source.sessionId, path, true) as? ProjectArtifactResult.Loaded)?.html }
    // Beside the chat the page opens in the inspector's tab, which offers the chat's later edits.
    fun view() {
        val artifact = ready?.artifact ?: return
        if (viewers == null) viewing = true
        else
            viewers.show(
                InspectorViewer.Artifact(
                    ArtifactKind.Html, path, artifactTitle(artifact.html, fileName), artifact.html, reload,
                    ArtifactWatch.File(path),
                )
            )
    }
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().testTag("projectArtifact-$path"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                // Dragged into another app, the card is the HTML file.
                .dragOut(ready?.artifact?.sha256, enabled = ready != null) {
                    ready?.artifact?.let { dragArtifact(context, path, it.html) }
                },
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column {
                if (ready != null) {
                    val artifact = ready.artifact
                    val title = remember(artifact.sha256) { artifactTitle(artifact.html, fileName) }
                    ArtifactThumbnail(
                        ArtifactKind.Html,
                        artifact.html,
                        artifact.sha256,
                        onClickLabel = open,
                        onClick = ::view,
                        failed = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = open, onClick = ::view)
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                stringResource(R.string.remote_artifact_badge),
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "$fileName · " + Formatter.formatShortFileSize(LocalContext.current, artifact.byteCount.toLong()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = ::view) { Text(stringResource(R.string.remote_artifact_open_action)) }
                    }
                } else
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                        ArtifactMessage(state) { requests++ }
                    }
            }
        }
    }
    if (viewing && ready != null) {
        val artifact = ready.artifact
        ArtifactViewer(
            kind = ArtifactKind.Html,
            path = path,
            title = artifactTitle(artifact.html, fileName),
            initialText = artifact.html,
            reload = reload,
            onDismiss = { viewing = false },
        )
    }
}

/** The page at [path] as an HTML file another app can take, written to the drag folder. */
internal suspend fun dragArtifact(context: Context, path: String, html: String): DragPayload =
    withContext(Dispatchers.IO) {
        dragFile(
            context,
            path.substringAfterLast('/'),
            ArtifactStorage.safeName(path, "html"),
            html.toByteArray(Charsets.UTF_8),
            "text/html",
        )
    }

/** A closed ```mermaid fence in assistant text: a live thumbnail of the diagram, the viewer on a tap. */
@Composable
internal fun MermaidCard(code: String, modifier: Modifier = Modifier, codeBlock: @Composable (String) -> Unit) {
    if (remember(code) { mermaidTooLarge(code) }) {
        Column(modifier.fillMaxWidth().testTag("mermaidTooLarge"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.remote_mermaid_too_large), style = MaterialTheme.typography.labelMedium)
            codeBlock(code)
        }
        return
    }
    var viewing by remember(code) { mutableStateOf(false) }
    val sha = remember(code) { sha256Hex(code) }
    val type = remember(code) { mermaidDiagramType(code) }
    val lines = remember(code) { mermaidLineCount(code) }
    val open = stringResource(R.string.remote_mermaid_open)
    val viewers = LocalInspectorViewers.current
    val badge = stringResource(R.string.remote_mermaid_badge)
    fun view() {
        if (viewers == null) viewing = true
        else viewers.show(InspectorViewer.Artifact(ArtifactKind.Mermaid, "diagram.mmd", badge, code, reload = null))
    }
    val summary =
        listOf(stringResource(R.string.remote_mermaid_badge), type)
            .filter { it.isNotEmpty() }
            .joinToString(" · ") + " · " + pluralStringResource(R.plurals.remote_mermaid_lines, lines, lines)
    Column(modifier.fillMaxWidth().testTag("mermaidCard"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column {
                ArtifactThumbnail(
                    ArtifactKind.Mermaid,
                    code,
                    sha,
                    onClickLabel = open,
                    onClick = ::view,
                    failed = {
                        Text(
                            stringResource(R.string.remote_mermaid_failed),
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = open, onClick = ::view)
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = ::view) { Text(stringResource(R.string.remote_artifact_open_action)) }
                }
            }
        }
    }
    if (viewing)
        ArtifactViewer(
            kind = ArtifactKind.Mermaid,
            path = "diagram.mmd",
            title = badge,
            initialText = code,
            reload = null,
            onDismiss = { viewing = false },
        )
}
