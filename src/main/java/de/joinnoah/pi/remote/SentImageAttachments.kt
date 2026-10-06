package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest edge of the thumbnail and of the full-size view; decoding stays below these. */
internal const val SENT_IMAGE_THUMBNAIL_EDGE = 256
internal const val SENT_IMAGE_LARGE_EDGE = 2048

/** Images with more pixels than this are treated as malformed rather than decoded. */
private const val MAX_SENT_IMAGE_PIXELS = 50_000_000L

/**
 * Decodes [bytes] with a power-of-two sample size so the longest edge is at most [maxEdge], or
 * returns null when the bytes are not an image or its dimensions are absurd. Run it off the main
 * thread.
 */
internal fun decodeSentImage(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SENT_IMAGE_PIXELS) return null
    var sample = 1
    while (maxOf(width, height) / sample > maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

/** What a chat bubble needs to show the images it sent; [read] is [RemoteRepository.readAttachment]. */
internal class SentImageSource(
    val sessionId: String,
    val connected: Boolean,
    val supported: Boolean,
    val read: suspend (String, RemoteAttachment) -> AttachmentReadResult,
)

private class SentImageSlot {
    var thumbnail by mutableStateOf<ConversationAttachmentImageState>(ConversationAttachmentImageState.Loading)
    var large by mutableStateOf<ConversationAttachmentImageState>(ConversationAttachmentImageState.Loading)
    var thumbnailRequests by mutableIntStateOf(0)
    var largeRequests by mutableIntStateOf(0)
}

private suspend fun SentImageSource.state(
    attachment: RemoteAttachment,
    maxEdge: Int,
): ConversationAttachmentImageState =
    when (val result = read(sessionId, attachment)) {
        is AttachmentReadResult.Loaded ->
            withContext(Dispatchers.Default) { decodeSentImage(result.bytes, maxEdge) }
                ?.let { ConversationAttachmentImageState.Ready(it.asImageBitmap()) }
                ?: ConversationAttachmentImageState.MalformedImage
        AttachmentReadResult.Unsupported -> ConversationAttachmentImageState.UnsupportedHost
        AttachmentReadResult.Unavailable -> ConversationAttachmentImageState.Unavailable
        AttachmentReadResult.Failed -> ConversationAttachmentImageState.ConnectionFailure
    }

/**
 * Shows [images] sent in a bubble as thumbnails that open full size. Each image loads when this
 * is composed and stops when it leaves; an expired image or an unsupported host sends nothing.
 */
@Composable
internal fun SentImageAttachments(images: List<RemoteAttachment>, source: SentImageSource) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val now = System.currentTimeMillis()
    val slots = mutableMapOf<String, SentImageSlot>()
    val items =
        images.map { attachment ->
            key(attachment.id) {
                val slot = remember(source.sessionId) { SentImageSlot() }
                slots[attachment.id] = slot
                val expired = attachment.expiresAt <= now
                LaunchedEffect(source.sessionId, source.connected, source.supported, slot.thumbnailRequests) {
                    if (!expired) {
                        slot.thumbnail = ConversationAttachmentImageState.Loading
                        slot.thumbnail = source.state(attachment, SENT_IMAGE_THUMBNAIL_EDGE)
                    }
                }
                LaunchedEffect(source.sessionId, slot.largeRequests) {
                    if (!expired && slot.largeRequests > 0) {
                        slot.large = ConversationAttachmentImageState.Loading
                        slot.large = source.state(attachment, SENT_IMAGE_LARGE_EDGE)
                    }
                }
                val date = format.format(Date(attachment.expiresAt))
                ConversationAttachmentPreviewItem(
                    attachment.id,
                    attachment.name,
                    when {
                        expired -> ConversationAttachmentAvailability.ExpiredOn(date)
                        slot.thumbnail == ConversationAttachmentImageState.Unavailable ->
                            ConversationAttachmentAvailability.Unavailable
                        else -> ConversationAttachmentAvailability.AvailableUntil(date)
                    },
                    if (expired) ConversationAttachmentImageState.Expired else slot.thumbnail,
                    if (expired) ConversationAttachmentImageState.Expired else slot.large,
                )
            }
        }
    ConversationAttachmentPreview(
        items,
        onOpen = { slots[it]?.let { slot -> slot.largeRequests++ } },
        onRetry = { id, target ->
            slots[id]?.let { slot ->
                if (target == ConversationAttachmentPreviewTarget.Thumbnail) slot.thumbnailRequests++
                else slot.largeRequests++
            }
        },
    )
}
