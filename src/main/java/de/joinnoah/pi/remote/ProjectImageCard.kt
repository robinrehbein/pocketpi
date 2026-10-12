package de.joinnoah.pi.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What an assistant message needs to show the project images it mentions; [read] is
 * [RemoteRepository.readProjectImage]. Other Markdown surfaces get no source and show the alt text.
 */
internal class ProjectImageSource(
    val sessionId: String,
    val connected: Boolean,
    /** False while the host's capabilities are still unknown after connecting. */
    val capabilitiesKnown: Boolean,
    val supported: Boolean,
    val read: suspend (sessionId: String, path: String, fresh: Boolean) -> ProjectImageResult,
    /** [RemoteRepository.readProjectArtifact]; HTML cards and Mermaid diagrams appear only where this exists. */
    val readArtifact: suspend (sessionId: String, path: String, fresh: Boolean) -> ProjectArtifactResult = { _, _, _ ->
        ProjectArtifactResult.UnsupportedHost
    },
    val artifactSupported: Boolean = false,
)

internal val LocalProjectImageSource = staticCompositionLocalOf<ProjectImageSource?> { null }

internal sealed interface ProjectImageState {
    data object Loading : ProjectImageState
    /** Holds the thumbnail only; the viewer fetches the bytes again from the cache when it opens. */
    class Ready(val image: ImageBitmap, val sha256: String, val mimeType: String) : ProjectImageState
    data object TooLarge : ProjectImageState
    data object NotAnImage : ProjectImageState
    data object Unavailable : ProjectImageState
    data object UnsupportedHost : ProjectImageState
    data object Busy : ProjectImageState
    /** The SVG renderer had no free slot; the picture itself may be fine. */
    data object RenderBusy : ProjectImageState
    data object ConnectionFailure : ProjectImageState
    data object Malformed : ProjectImageState
}

/** Reads and decodes the card image of [path]; the first-loaded version of a file stays. */
internal suspend fun ProjectImageSource.cardState(path: String): ProjectImageState {
    // Right after connecting, capabilities are not known yet; asking now would report a wrong state.
    if (connected && !capabilitiesKnown) return ProjectImageState.Loading
    return when (val result = read(sessionId, path, false)) {
        is ProjectImageResult.Loaded -> {
            val key = ProjectImageBitmaps.Key(sessionId, path, result.sha256, PROJECT_IMAGE_CARD_EDGE)
            val cached = ProjectImageBitmaps[key]
            when (val decoded = if (cached != null) ProjectImageDecode.Ready(cached) else decodeProjectImage(result, PROJECT_IMAGE_CARD_EDGE)) {
                is ProjectImageDecode.Ready -> {
                    ProjectImageBitmaps[key] = decoded.bitmap
                    ProjectImageState.Ready(decoded.bitmap.asImageBitmap(), result.sha256, result.mimeType)
                }
                ProjectImageDecode.Malformed -> ProjectImageState.Malformed
                ProjectImageDecode.Busy -> ProjectImageState.RenderBusy
            }
        }
        ProjectImageResult.Unsupported -> ProjectImageState.UnsupportedHost
        ProjectImageResult.Unavailable -> ProjectImageState.Unavailable
        ProjectImageResult.TooLarge -> ProjectImageState.TooLarge
        ProjectImageResult.NotAnImage -> ProjectImageState.NotAnImage
        ProjectImageResult.Busy -> ProjectImageState.Busy
        ProjectImageResult.Failed -> ProjectImageState.ConnectionFailure
    }
}

private const val CARD_ASPECT = 4f / 3f

/**
 * An image the agent mentioned. The card keeps one fixed shape from loading to loaded, so the chat
 * does not jump; it fetches only while composed and opens the full-screen viewer on a tap.
 */
@Composable
internal fun ProjectImageCard(alt: String, path: String, source: ProjectImageSource, modifier: Modifier = Modifier) {
    var state by remember(source.sessionId, path) { mutableStateOf<ProjectImageState>(ProjectImageState.Loading) }
    var requests by remember(source.sessionId, path) { mutableIntStateOf(0) }
    // Not saved: a rotation must not reopen the viewer by itself.
    var viewing by remember(source.sessionId, path) { mutableStateOf(false) }
    LaunchedEffect(source.sessionId, path, source.connected, source.capabilitiesKnown, source.supported, requests) {
        // An image already shown stays through connection changes.
        if (state !is ProjectImageState.Ready) {
            state = ProjectImageState.Loading
            state = source.cardState(path)
        }
    }
    val fileName = path.substringAfterLast('/')
    val ready = state as? ProjectImageState.Ready
    val label = stringResource(R.string.remote_image_open, fileName)
    Column(modifier.fillMaxWidth().testTag("projectImage-$path"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    // The cap comes first so a wide layout is capped too; the shape then narrows.
                    .heightIn(max = 320.dp)
                    .aspectRatio(CARD_ASPECT)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (ready != null)
                            Modifier.clickable(role = Role.Button, onClickLabel = label) { viewing = true }
                        else Modifier
                    ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ready != null)
                    Image(
                        bitmap = ready.image,
                        contentDescription = alt.ifBlank { fileName },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("projectImagePicture-$path"),
                    )
                else ProjectImageMessage(state) { requests++ }
            }
        }
        Text(
            fileName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (viewing && ready != null) ImageViewer(path, ready.sha256, ready.mimeType, source) { viewing = false }
}

@Composable
private fun ProjectImageMessage(state: ProjectImageState, onRetry: () -> Unit) {
    Column(
        Modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state == ProjectImageState.Loading) CircularProgressIndicator(Modifier.size(24.dp))
        Text(
            stringResource(
                when (state) {
                    ProjectImageState.Loading -> R.string.remote_image_loading
                    ProjectImageState.TooLarge -> R.string.remote_image_too_large
                    ProjectImageState.NotAnImage -> R.string.remote_image_not_an_image
                    ProjectImageState.UnsupportedHost -> R.string.remote_image_unsupported
                    ProjectImageState.Busy -> R.string.remote_image_busy
                    ProjectImageState.RenderBusy -> R.string.remote_image_render_busy
                    ProjectImageState.ConnectionFailure -> R.string.remote_image_connection_failure
                    ProjectImageState.Malformed -> R.string.remote_image_malformed
                    else -> R.string.remote_image_unavailable
                }
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        if (state == ProjectImageState.ConnectionFailure || state == ProjectImageState.Busy ||
            state == ProjectImageState.RenderBusy)
            TextButton(onClick = onRetry, modifier = Modifier.testTag("projectImageRetry")) {
                Text(stringResource(R.string.remote_image_retry))
            }
    }
}
