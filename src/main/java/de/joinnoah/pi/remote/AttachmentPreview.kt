package de.joinnoah.pi.remote

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AttachmentPreviewStrip(
    attachments: List<LocalAttachment>,
    onRemove: (String) -> Unit,
    storage: AttachmentStorage? = null,
) {
    val context = LocalContext.current
    val photoStorage = storage ?: remember(context.applicationContext) {
        AttachmentStore(context.applicationContext)
    }
    var openId by remember { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments, key = LocalAttachment::id) { attachment ->
            if (attachment.hasImagePreview()) {
                PhotoThumbnail(
                    attachment = attachment,
                    storage = photoStorage,
                    onOpen = { openId = attachment.id },
                    onRemove = { onRemove(attachment.id) },
                )
            } else {
                FileThumbnail(attachment, onRemove = { onRemove(attachment.id) })
            }
        }
    }
    attachments.firstOrNull { it.id == openId && it.hasImagePreview() }?.let { attachment ->
        PhotoDialog(attachment, photoStorage, onDismiss = { openId = null })
    }
}

private fun LocalAttachment.hasImagePreview(): Boolean =
    kind == "image" || mimeType.startsWith("image/", ignoreCase = true)

@Composable
private fun PhotoThumbnail(
    attachment: LocalAttachment,
    storage: AttachmentStorage,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val photo by rememberPhoto(attachment, storage, maxSide = 192)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.remote_preview_attachment, attachment.name), onClick = onOpen)
                    .testTag("attachmentPreview-${attachment.id}"),
                contentAlignment = Alignment.Center,
            ) {
                if (photo is PhotoLoad.Loaded)
                    Image(
                        bitmap = (photo as PhotoLoad.Loaded).bitmap,
                        contentDescription = stringResource(R.string.remote_preview_attachment, attachment.name),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).testTag("attachmentThumbnailImage-${attachment.id}"),
                    )
                else Icon(Icons.Default.ImageIcon, null)
            }
            Column(Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) {
                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(Formatter.formatShortFileSize(LocalContext.current, attachment.size), style = MaterialTheme.typography.labelSmall)
            }
            RemoveAttachment(attachment, onRemove)
        }
    }
}

@Composable
private fun FileThumbnail(attachment: LocalAttachment, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, Modifier.padding(start = 12.dp))
            Column(Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) {
                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(Formatter.formatShortFileSize(LocalContext.current, attachment.size), style = MaterialTheme.typography.labelSmall)
            }
            RemoveAttachment(attachment, onRemove)
        }
    }
}

@Composable
private fun RemoveAttachment(attachment: LocalAttachment, onRemove: () -> Unit) {
    IconButton(onClick = onRemove) {
        Icon(Icons.Default.Close, stringResource(R.string.remote_remove_attachment, attachment.name))
    }
}

@Composable
private fun PhotoDialog(
    attachment: LocalAttachment,
    storage: AttachmentStorage,
    onDismiss: () -> Unit,
) {
    val photo by rememberPhoto(attachment, storage, maxSide = 2048)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        attachment.name,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("closeAttachmentPreview")) {
                        Icon(Icons.Default.Close, stringResource(R.string.remote_close_preview))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp, max = maxHeight)
                        .testTag("attachmentLargePreview"),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val current = photo) {
                        is PhotoLoad.Loaded ->
                            Image(
                                bitmap = current.bitmap,
                                contentDescription = attachment.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
                                    .testTag("attachmentLargeImage"),
                            )
                        PhotoLoad.Loading -> CircularProgressIndicator()
                        PhotoLoad.Unavailable -> Text(stringResource(R.string.remote_preview_unavailable))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPhoto(
    attachment: LocalAttachment,
    storage: AttachmentStorage,
    maxSide: Int,
) = produceState<PhotoLoad>(PhotoLoad.Loading, attachment.id, attachment.sha256, storage, maxSide) {
    value = withContext(Dispatchers.IO) {
        try {
            val bytes = storage.read(attachment)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000)
            require(bounds.outWidth.toLong() * bounds.outHeight <= 200000000)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            if (bitmap == null) PhotoLoad.Unavailable else PhotoLoad.Loaded(bitmap.asImageBitmap())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PhotoLoad.Unavailable
        }
    }
}

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Unavailable : PhotoLoad
    data class Loaded(val bitmap: ImageBitmap) : PhotoLoad
}
