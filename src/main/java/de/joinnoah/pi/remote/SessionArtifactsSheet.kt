package de.joinnoah.pi.remote

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the gallery shows: the list is loading, loaded, or could not be read. */
internal sealed interface ArtifactGalleryState {
    data object Loading : ArtifactGalleryState

    class Loaded(val list: SessionArtifactList) : ArtifactGalleryState

    data object Unsupported : ArtifactGalleryState

    data object Unavailable : ArtifactGalleryState

    data object Failed : ArtifactGalleryState
}

internal fun SessionArtifactListResult.toGalleryState(): ArtifactGalleryState =
    when (this) {
        is SessionArtifactListResult.Loaded -> ArtifactGalleryState.Loaded(list)
        SessionArtifactListResult.Unsupported -> ArtifactGalleryState.Unsupported
        SessionArtifactListResult.Unavailable -> ArtifactGalleryState.Unavailable
        SessionArtifactListResult.Failed -> ArtifactGalleryState.Failed
    }

/** What one gallery card shows once its artifact has been opened. */
internal sealed interface ArtifactCardContent {
    data object Loading : ArtifactCardContent

    /** An HTML page or Mermaid source for [ArtifactThumbnail]. */
    class Text(val type: ArtifactType, val loaded: ProjectArtifactResult.Loaded) : ArtifactCardContent

    /** A rasterised SVG; the viewer reads the bytes again from the repository cache. */
    class Picture(val bitmap: ImageBitmap, val image: ProjectImageResult.Loaded) : ArtifactCardContent

    class Problem(@StringRes val message: Int, val retryable: Boolean) : ArtifactCardContent
}

/** Opens [artifact] at the version the list named, so the card and its viewer show the same snapshot. */
internal suspend fun loadArtifactCard(
    sessionId: String,
    artifact: SessionArtifact,
    open: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult,
): ArtifactCardContent =
    when (val result = open(artifact.id, artifact.version)) {
        is SessionArtifactOpenResult.Text -> ArtifactCardContent.Text(result.type, result.loaded)
        is SessionArtifactOpenResult.Svg -> {
            val image = result.image
            val key = ProjectImageBitmaps.Key(sessionId, image.path, image.sha256, PROJECT_IMAGE_CARD_EDGE)
            val cached = ProjectImageBitmaps[key]
            when (val decoded = if (cached != null) ProjectImageDecode.Ready(cached) else decodeProjectImage(image, PROJECT_IMAGE_CARD_EDGE)) {
                is ProjectImageDecode.Ready -> {
                    ProjectImageBitmaps[key] = decoded.bitmap
                    ArtifactCardContent.Picture(decoded.bitmap.asImageBitmap(), image)
                }
                ProjectImageDecode.Malformed -> ArtifactCardContent.Problem(R.string.remote_artifacts_card_malformed, false)
                ProjectImageDecode.Busy -> ArtifactCardContent.Problem(R.string.remote_artifacts_card_busy, true)
            }
        }
        SessionArtifactOpenResult.TooLarge -> ArtifactCardContent.Problem(R.string.remote_artifacts_card_too_large, false)
        SessionArtifactOpenResult.NotAnArtifact ->
            ArtifactCardContent.Problem(R.string.remote_artifacts_card_not_an_artifact, false)
        SessionArtifactOpenResult.Busy -> ArtifactCardContent.Problem(R.string.remote_artifacts_card_busy, true)
        SessionArtifactOpenResult.ConnectionFailure -> ArtifactCardContent.Problem(R.string.remote_artifacts_card_failed, true)
        SessionArtifactOpenResult.Unsupported, SessionArtifactOpenResult.Unavailable ->
            ArtifactCardContent.Problem(R.string.remote_artifacts_card_unavailable, false)
    }

/** The image viewer's source for one SVG artifact: every read opens that artifact at that version. */
internal fun artifactImageSource(
    sessionId: String,
    artifact: SessionArtifact,
    open: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult,
) =
    ProjectImageSource(
        sessionId, connected = true, capabilitiesKnown = true, supported = true,
        read = { _, _, _ ->
            when (val result = open(artifact.id, artifact.version)) {
                is SessionArtifactOpenResult.Svg -> result.image
                SessionArtifactOpenResult.TooLarge -> ProjectImageResult.TooLarge
                SessionArtifactOpenResult.NotAnArtifact, is SessionArtifactOpenResult.Text -> ProjectImageResult.NotAnImage
                SessionArtifactOpenResult.Busy -> ProjectImageResult.Busy
                SessionArtifactOpenResult.ConnectionFailure -> ProjectImageResult.Failed
                SessionArtifactOpenResult.Unsupported -> ProjectImageResult.Unsupported
                SessionArtifactOpenResult.Unavailable -> ProjectImageResult.Unavailable
            }
        },
    )

private fun SessionArtifact.typeLabel(): Int =
    when (type) {
        ArtifactType.HTML -> R.string.remote_artifacts_type_html
        ArtifactType.SVG -> R.string.remote_artifacts_type_svg
        ArtifactType.MERMAID -> R.string.remote_artifacts_type_mermaid
    }

/**
 * `/artifacts`: the HTML pages, SVG images and Mermaid diagrams recorded for the session, newest
 * first, as a grid of live thumbnails. [list] reads the list and [open] one artifact; a tap opens
 * the viewer the chat uses for the same kind of content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionArtifactsSheet(
    sessionId: String,
    list: suspend () -> SessionArtifactListResult,
    open: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("artifactsSheet"),
    ) {
        SessionArtifactsGallery(sessionId, list, open, Modifier.heightIn(max = maxHeight).navigationBarsPadding())
    }
}

/** The sheet's content, apart from the sheet itself. */
@Composable
internal fun SessionArtifactsGallery(
    sessionId: String,
    list: suspend () -> SessionArtifactListResult,
    open: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<ArtifactGalleryState>(ArtifactGalleryState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val latestList by rememberUpdatedState(list)
    LaunchedEffect(reload) {
        state = ArtifactGalleryState.Loading
        state = latestList().toGalleryState()
    }
    Column(modifier.fillMaxWidth().testTag("artifactGallery")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.remote_artifacts_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(
                onClick = { reload++ },
                enabled = state != ArtifactGalleryState.Loading,
                modifier = Modifier.testTag("artifactsReload"),
            ) {
                Icon(Icons.Default.Refresh, stringResource(R.string.remote_artifacts_reload))
            }
        }
        when (val current = state) {
            ArtifactGalleryState.Loading ->
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("artifactsLoading"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.remote_artifacts_loading))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            ArtifactGalleryState.Unsupported -> GalleryProblem(R.string.remote_artifacts_unsupported, false) { reload++ }
            ArtifactGalleryState.Unavailable -> GalleryProblem(R.string.remote_artifacts_unavailable, true) { reload++ }
            ArtifactGalleryState.Failed -> GalleryProblem(R.string.remote_artifacts_failed, true) { reload++ }
            is ArtifactGalleryState.Loaded ->
                if (current.list.artifacts.isEmpty())
                    Text(
                        stringResource(R.string.remote_artifacts_empty),
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("artifactsEmpty"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 170.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f, fill = false).testTag("artifactGrid"),
                    ) {
                        items(current.list.artifacts, key = { it.id }) { artifact ->
                            ArtifactCard(sessionId, artifact, open)
                        }
                        if (current.list.truncated)
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    stringResource(R.string.remote_artifacts_truncated),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("artifactsTruncated"),
                                )
                            }
                    }
        }
    }
}

@Composable
private fun GalleryProblem(@StringRes message: Int, retryable: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(message),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("artifactsError"),
        )
        if (retryable)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("artifactsRetry")) {
                Text(stringResource(R.string.remote_artifacts_retry))
            }
    }
}

@Composable
private fun ArtifactCard(
    sessionId: String,
    artifact: SessionArtifact,
    open: suspend (artifactId: String, version: Int?) -> SessionArtifactOpenResult,
) {
    var content by remember(artifact.id, artifact.version) { mutableStateOf<ArtifactCardContent>(ArtifactCardContent.Loading) }
    var requests by remember(artifact.id, artifact.version) { mutableIntStateOf(0) }
    // Not saved: a rotation must not reopen the viewer by itself.
    var viewing by remember(artifact.id, artifact.version) { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState(open)
    // Only a card in view is composed, so only visible artifacts are fetched.
    LaunchedEffect(artifact.id, artifact.version, requests) {
        if (content !is ArtifactCardContent.Picture && content !is ArtifactCardContent.Text) {
            content = ArtifactCardContent.Loading
            content = loadArtifactCard(sessionId, artifact) { id, version -> latestOpen(id, version) }
        }
    }
    val label = stringResource(R.string.remote_artifacts_open, artifact.title)
    val ready = content.let { it is ArtifactCardContent.Text || it is ArtifactCardContent.Picture }
    val meta =
        stringResource(
            R.string.remote_artifacts_meta,
            stringResource(artifact.typeLabel()),
            artifact.version,
            DateUtils.getRelativeTimeSpanString(artifact.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString(),
        )
    Column(Modifier.fillMaxWidth().testTag("artifactCard-${artifact.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            when (val current = content) {
                is ArtifactCardContent.Text ->
                    ArtifactThumbnail(
                        if (current.type == ArtifactType.MERMAID) ArtifactKind.Mermaid else ArtifactKind.Html,
                        current.loaded.html,
                        current.loaded.sha256,
                        onClickLabel = label,
                        onClick = { viewing = true },
                        failed = {
                            Text(
                                artifact.title,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                is ArtifactCardContent.Picture ->
                    Image(
                        current.bitmap,
                        contentDescription = artifact.title,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT)
                                .clickable(role = Role.Button, onClickLabel = label) { viewing = true }
                                .testTag("artifactPicture-${artifact.id}"),
                    )
                else ->
                    Box(Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT), contentAlignment = Alignment.Center) {
                        if (current is ArtifactCardContent.Problem)
                            Column(
                                Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    stringResource(current.message),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.testTag("artifactCardProblem-${artifact.id}"),
                                )
                                if (current.retryable)
                                    TextButton(onClick = { requests++ }, modifier = Modifier.testTag("artifactCardRetry-${artifact.id}")) {
                                        Text(stringResource(R.string.remote_artifacts_card_retry))
                                    }
                            }
                        else CircularProgressIndicator(Modifier.size(24.dp))
                    }
            }
        }
        Column(
            Modifier.fillMaxWidth().then(
                if (ready) Modifier.clickable(role = Role.Button, onClickLabel = label) { viewing = true } else Modifier
            ),
        ) {
            Text(artifact.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val shown = content
    if (viewing && shown is ArtifactCardContent.Text)
        ArtifactViewer(
            kind = if (shown.type == ArtifactType.MERMAID) ArtifactKind.Mermaid else ArtifactKind.Html,
            path = shown.loaded.path,
            title = artifact.title,
            initialText = shown.loaded.html,
            // Reload asks for the latest version, which may be newer than the card's.
            reload = { (open(artifact.id, null) as? SessionArtifactOpenResult.Text)?.loaded?.html },
            onDismiss = { viewing = false },
        )
    if (viewing && shown is ArtifactCardContent.Picture) {
        val source = remember(sessionId, artifact.id, artifact.version) { artifactImageSource(sessionId, artifact, open) }
        ImageViewer(
            "${artifact.title.replace('/', '-')}.svg",
            shown.image.sha256,
            shown.image.mimeType,
            source,
        ) { viewing = false }
    }
}
