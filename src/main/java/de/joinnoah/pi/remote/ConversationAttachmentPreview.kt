package de.joinnoah.pi.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Decoded images and presentation states are supplied by the caller, never loaded here. */
internal sealed interface ConversationAttachmentImageState {
    data object Loading : ConversationAttachmentImageState
    data class Ready(val bitmap: ImageBitmap) : ConversationAttachmentImageState
    data object ConnectionFailure : ConversationAttachmentImageState
    data object Unavailable : ConversationAttachmentImageState
    data object Expired : ConversationAttachmentImageState
    data object UnsupportedHost : ConversationAttachmentImageState
    data object MalformedImage : ConversationAttachmentImageState
}

internal sealed interface ConversationAttachmentAvailability {
    data class AvailableUntil(val formattedDate: String) : ConversationAttachmentAvailability
    data class ExpiredOn(val formattedDate: String) : ConversationAttachmentAvailability
    data object Unavailable : ConversationAttachmentAvailability
}

internal enum class ConversationAttachmentPreviewTarget { Thumbnail, LargeImage }

internal data class ConversationAttachmentPreviewItem(
    val id: String,
    val name: String,
    val availability: ConversationAttachmentAvailability,
    val thumbnail: ConversationAttachmentImageState,
    val largeImage: ConversationAttachmentImageState,
)

/** Image-only UI. The caller retains the existing presentation of non-image attachments. */
@Composable
internal fun ConversationAttachmentPreview(
    items: List<ConversationAttachmentPreviewItem>,
    onOpen: (String) -> Unit,
    onRetry: (String, ConversationAttachmentPreviewTarget) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (String) -> Unit = {},
) {
    require(items.map { it.id }.distinct().size == items.size) { "Attachment IDs must be unique" }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.id == openId }
    LaunchedEffect(selected?.id) {
        if (selected == null) openId = null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) key(item.id) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = stringResource(R.string.remote_preview_attachment, item.name)
                Box(
                    (if (item.thumbnail is ConversationAttachmentImageState.Ready) Modifier.size(128.dp)
                    else Modifier.widthIn(max = 240.dp).heightIn(min = 128.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (item.thumbnail is ConversationAttachmentImageState.Ready)
                            Modifier.clickable(role = Role.Button, onClickLabel = label) {
                                openId = item.id
                                onOpen(item.id)
                            }
                        else Modifier)
                        .testTag("conversationAttachmentThumbnail-${item.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    AttachmentImageContent(item, item.thumbnail, ConversationAttachmentPreviewTarget.Thumbnail, onRetry)
                }
                AttachmentCaption(item)
            }
        }
    }
    selected?.let { item ->
        Dialog(
            onDismissRequest = {
                openId = null
                onClose(item.id)
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            Surface(
                Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { AttachmentCaption(item) }
                        IconButton(
                            onClick = {
                                openId = null
                                onClose(item.id)
                            },
                            modifier = Modifier.testTag("closeConversationAttachmentPreview"),
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.remote_close_preview))
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 128.dp, max = maxHeight)
                            .testTag("conversationAttachmentLarge-${item.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        AttachmentImageContent(item, item.largeImage, ConversationAttachmentPreviewTarget.LargeImage, onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentCaption(item: ConversationAttachmentPreviewItem) {
    Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val availability = when (val current = item.availability) {
        is ConversationAttachmentAvailability.AvailableUntil -> stringResource(R.string.remote_attachment_expires, current.formattedDate)
        is ConversationAttachmentAvailability.ExpiredOn -> stringResource(R.string.remote_attachment_expired, current.formattedDate)
        ConversationAttachmentAvailability.Unavailable -> stringResource(R.string.remote_preview_unavailable)
    }
    Text(availability, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun AttachmentImageContent(
    item: ConversationAttachmentPreviewItem,
    state: ConversationAttachmentImageState,
    target: ConversationAttachmentPreviewTarget,
    onRetry: (String, ConversationAttachmentPreviewTarget) -> Unit,
) {
    when (state) {
        is ConversationAttachmentImageState.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = stringResource(R.string.remote_preview_attachment, item.name),
            contentScale = if (target == ConversationAttachmentPreviewTarget.Thumbnail) ContentScale.Crop else ContentScale.Fit,
            modifier = (if (target == ConversationAttachmentPreviewTarget.Thumbnail) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .testTag("conversationAttachmentImage-${target.name}-${item.id}"),
        )
        else -> Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state == ConversationAttachmentImageState.Loading) CircularProgressIndicator(Modifier.size(24.dp))
            Text(
                stringResource(when (state) {
                    ConversationAttachmentImageState.Loading -> R.string.remote_sent_preview_loading
                    ConversationAttachmentImageState.ConnectionFailure -> R.string.remote_sent_preview_connection_failure
                    ConversationAttachmentImageState.Expired -> R.string.remote_sent_preview_expired
                    ConversationAttachmentImageState.UnsupportedHost -> R.string.remote_sent_preview_unsupported
                    ConversationAttachmentImageState.MalformedImage -> R.string.remote_sent_preview_malformed
                    else -> R.string.remote_preview_unavailable
                }),
                style = MaterialTheme.typography.labelSmall,
            )
            if (state == ConversationAttachmentImageState.ConnectionFailure) TextButton(
                onClick = { onRetry(item.id, target) },
                modifier = Modifier.testTag("conversationAttachmentRetry-${target.name}-${item.id}"),
            ) { Text(stringResource(R.string.remote_sent_preview_retry)) }
        }
    }
}
