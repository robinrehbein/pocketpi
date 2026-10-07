package de.joinnoah.pi.remote

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_IMAGE_ZOOM = 6f

/** What the viewer shows while the full-size bitmap is made: a spinner, the picture, or an error. */
private sealed interface ViewerImage {
    data object Decoding : ViewerImage
    data object Failed : ViewerImage
    class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ViewerImage
}

/** Keeps a zoomed image covering the view: at most the overflow of the scaled size on each side. */
internal fun clampImageOffset(offset: Float, scale: Float, viewSize: Float): Float {
    val limit = (viewSize * (scale - 1f) / 2f).coerceAtLeast(0f)
    return offset.coerceIn(-limit, limit)
}

/**
 * Full-screen view of an agent image with pinch zoom, pan and double-tap reset. [path] is where the
 * image was asked for; Share and Save fetch the file again, so they carry what is on the Mac now.
 */
@Composable
internal fun ImageViewer(
    path: String,
    sha256: String,
    mimeType: String,
    source: ProjectImageSource,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The bytes come from the cache when the viewer opens, never from the card.
    val decoded by
        produceState<ViewerImage>(ViewerImage.Decoding, source.sessionId, path, sha256) {
            val key = ProjectImageBitmaps.Key(source.sessionId, path, sha256, PROJECT_IMAGE_VIEWER_EDGE)
            val cached = ProjectImageBitmaps[key]
            value =
                if (cached != null) ViewerImage.Ready(cached.asImageBitmap())
                else {
                    val image = source.read(source.sessionId, path, false) as? ProjectImageResult.Loaded
                    val bitmap =
                        image?.let { loaded ->
                            decodeProjectImage(loaded, PROJECT_IMAGE_VIEWER_EDGE)?.also {
                                ProjectImageBitmaps[
                                    ProjectImageBitmaps.Key(source.sessionId, path, loaded.sha256, PROJECT_IMAGE_VIEWER_EDGE)
                                ] = it
                            }
                        }
                    if (bitmap != null) ViewerImage.Ready(bitmap.asImageBitmap()) else ViewerImage.Failed
                }
        }
    val mime = MediaMime.fromWire(mimeType)
    val fileName = remember(path, mimeType) { mime?.let { ImageStorage.safeName(path, it) } ?: path.substringAfterLast('/') }
    var busy by remember { mutableStateOf(false) }
    val fetchFailed = stringResource(R.string.remote_image_fetch_failed)
    val saved = stringResource(R.string.remote_image_saved)
    val saveFailed = stringResource(R.string.remote_image_save_failed)
    val shareTitle = stringResource(R.string.remote_image_share_title)

    suspend fun fresh(): ProjectImageResult.Loaded? =
        (source.read(source.sessionId, path, true) as? ProjectImageResult.Loaded).also {
            if (it == null) Toast.makeText(context, fetchFailed, Toast.LENGTH_SHORT).show()
        }

    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    var written = false
                    try {
                        val latest = fresh()
                        if (latest != null)
                            written =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                                                it.write(latest.bytes)
                                            }
                                        }
                                        .isSuccess
                                }
                        if (latest != null) Toast.makeText(context, if (written) saved else saveFailed, Toast.LENGTH_SHORT).show()
                    } finally {
                        // The picker already created the file; never leave an empty one behind.
                        if (!written)
                            withContext(NonCancellable + Dispatchers.IO) {
                                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            }
                        busy = false
                    }
                }
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("imageViewer"), color = Color.Black, contentColor = Color.White) {
            Box(Modifier.fillMaxSize()) {
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                var size by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, MAX_IMAGE_ZOOM)
                                offsetX = clampImageOffset(offsetX + pan.x, scale, size.width.toFloat())
                                offsetY = clampImageOffset(offsetY + pan.y, scale, size.height.toFloat())
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = (decoded as? ViewerImage.Ready)?.bitmap
                    if (decoded == ViewerImage.Decoding)
                        CircularProgressIndicator(Modifier.testTag("imageViewerLoading"), color = Color.White)
                    else if (shown != null)
                        Image(
                            bitmap = shown,
                            contentDescription = fileName,
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier.fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                    )
                                    .testTag("imageViewerPicture"),
                        )
                    else Text(stringResource(R.string.remote_image_malformed))
                }
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.5f))
                        .statusBarsPadding().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("imageViewerClose")) {
                        Icon(Icons.Default.Close, stringResource(R.string.remote_image_close))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        enabled = !busy,
                        modifier = Modifier.testTag("imageViewerShare"),
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val latest = fresh() ?: return@launch
                                    val uri = withContext(Dispatchers.IO) { runCatching { storeProjectImage(context, latest) }.getOrNull() }
                                    if (uri == null) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                                    else context.startActivity(imageShareIntent(uri, latest.mimeType, shareTitle))
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Icon(Icons.Default.Share, stringResource(R.string.remote_image_share)) }
                    IconButton(
                        enabled = !busy,
                        modifier = Modifier.testTag("imageViewerSave"),
                        onClick = { saver.launch(fileName) },
                    ) { Icon(Icons.Default.Save, stringResource(R.string.remote_image_save)) }
                }
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.5f))
                        .navigationBarsPadding().padding(12.dp)
                ) {
                    Text(fileName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.remote_image_caption), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
