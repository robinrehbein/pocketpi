package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
 * Decodes [bytes] with a power-of-two sample size, or returns null when the bytes are not an
 * image or its dimensions are absurd. Run it off the main thread.
 *
 * With [cover] the sample size is the largest that keeps the longest edge at [maxEdge] or more and
 * the result is then scaled down to exactly [maxEdge]; without it the longest edge ends up at most
 * [maxEdge], which bounds memory for the full-size view.
 */
internal fun decodeSentImage(bytes: ByteArray, maxEdge: Int, cover: Boolean = false): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SENT_IMAGE_PIXELS) return null
    val longest = maxOf(width, height)
    var sample = 1
    if (cover) while (longest / (sample * 2) >= maxEdge) sample *= 2
    else while (longest / sample > maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?: return null
    val edge = maxOf(decoded.width, decoded.height)
    if (!cover || edge <= maxEdge) return decoded
    val scale = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(
            decoded,
            maxOf(1, (decoded.width * scale).toInt()),
            maxOf(1, (decoded.height * scale).toInt()),
            true,
        )
        .also { if (it !== decoded) decoded.recycle() }
}

/** Decoded thumbnails kept in memory only, so scrolling back shows them without decoding again. */
internal object SentImageThumbnails {
    private const val CAPACITY_BYTES = 8 * 1024 * 1024
    private var cache: LruCache<AttachmentByteCache.Key, Bitmap>? = null

    @Synchronized
    private fun cache() =
        cache
            ?: object : LruCache<AttachmentByteCache.Key, Bitmap>(CAPACITY_BYTES) {
                    override fun sizeOf(key: AttachmentByteCache.Key, value: Bitmap) = value.byteCount
                }
                .also { cache = it }

    operator fun get(key: AttachmentByteCache.Key): Bitmap? = cache()[key]

    operator fun set(key: AttachmentByteCache.Key, bitmap: Bitmap) {
        cache().put(key, bitmap)
    }

    @Synchronized
    fun clear() {
        cache?.evictAll()
    }
}

/** What a chat bubble needs to show the images it sent; [read] is [RemoteRepository.readAttachment]. */
internal class SentImageSource(
    val sessionId: String,
    val connected: Boolean,
    /** False while the host's capabilities are still unknown after connecting. */
    val capabilitiesKnown: Boolean,
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
    cover: Boolean,
): ConversationAttachmentImageState {
    // Right after connecting, capabilities are not known yet; asking now would report a wrong state.
    if (connected && !capabilitiesKnown) return ConversationAttachmentImageState.Loading
    return when (val result = read(sessionId, attachment)) {
        is AttachmentReadResult.Loaded ->
            withContext(Dispatchers.Default) { decodeSentImage(result.bytes, maxEdge, cover) }
                ?.let { bitmap ->
                    if (cover)
                        SentImageThumbnails[AttachmentByteCache.Key(sessionId, attachment.id, attachment.sha256)] = bitmap
                    ConversationAttachmentImageState.Ready(bitmap.asImageBitmap())
                } ?: ConversationAttachmentImageState.MalformedImage
        AttachmentReadResult.Unsupported -> ConversationAttachmentImageState.UnsupportedHost
        AttachmentReadResult.Unavailable -> ConversationAttachmentImageState.Unavailable
        AttachmentReadResult.Failed -> ConversationAttachmentImageState.ConnectionFailure
    }
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
                val slot =
                    remember(source.sessionId) {
                        SentImageSlot().also {
                            SentImageThumbnails[AttachmentByteCache.Key(source.sessionId, attachment.id, attachment.sha256)]
                                ?.let { bitmap ->
                                    it.thumbnail = ConversationAttachmentImageState.Ready(bitmap.asImageBitmap())
                                }
                        }
                    }
                slots[attachment.id] = slot
                val expired = attachment.expiresAt <= now
                LaunchedEffect(
                    source.sessionId, source.connected, source.capabilitiesKnown, source.supported,
                    slot.thumbnailRequests,
                ) {
                    // A thumbnail already shown stays through connection changes.
                    if (!expired && slot.thumbnail !is ConversationAttachmentImageState.Ready) {
                        slot.thumbnail = ConversationAttachmentImageState.Loading
                        slot.thumbnail = source.state(attachment, SENT_IMAGE_THUMBNAIL_EDGE, cover = true)
                    }
                }
                LaunchedEffect(source.sessionId, slot.largeRequests) {
                    if (!expired && slot.largeRequests > 0) {
                        slot.large = ConversationAttachmentImageState.Loading
                        slot.large = source.state(attachment, SENT_IMAGE_LARGE_EDGE, cover = false)
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
        // Release the full-size bitmap with the dialog.
        onClose = {
            slots[it]?.let { slot ->
                slot.largeRequests = 0
                slot.large = ConversationAttachmentImageState.Loading
            }
        },
        onRetry = { id, target ->
            slots[id]?.let { slot ->
                if (target == ConversationAttachmentPreviewTarget.Thumbnail) slot.thumbnailRequests++
                else slot.largeRequests++
            }
        },
    )
}

/** Splits [attachments] into consecutive runs that are all images (when [group]) or all not. */
internal fun attachmentRuns(attachments: List<RemoteAttachment>, group: Boolean): List<List<RemoteAttachment>> {
    val runs = mutableListOf<MutableList<RemoteAttachment>>()
    var previous: Boolean? = null
    for (attachment in attachments) {
        val image = group && attachment.kind == "image"
        if (image != previous) runs += mutableListOf<RemoteAttachment>()
        runs.last() += attachment
        previous = image
    }
    return runs
}
