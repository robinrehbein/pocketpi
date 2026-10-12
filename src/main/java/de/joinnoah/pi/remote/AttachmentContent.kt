package de.joinnoah.pi.remote

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonObject

/** Reading back an attachment this device sent (`session.attachments.get`). */
internal const val ATTACHMENT_READ_CAPABILITY = "session.attachments.read.v1"

internal const val MAX_ATTACHMENT_READ_BYTES = 20L * 1024 * 1024
internal const val ATTACHMENT_CHUNK_BYTES = 49_152
private const val MAX_ATTACHMENT_CHUNK_CHARS = 65_536
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/** The app only previews images it was allowed to send, so it never reads more than this. */
internal const val MAX_ATTACHMENT_IMAGE_BYTES = 2L * 1024 * 1024

/** Outcome of [RemoteRepository.readAttachment]; [Loaded] bytes passed the length and SHA-256 checks. */
sealed interface AttachmentReadResult {
    class Loaded(val bytes: ByteArray) : AttachmentReadResult

    /** No request was sent: the host lacks [ATTACHMENT_READ_CAPABILITY]. */
    data object Unsupported : AttachmentReadResult

    /** Not found, not allowed, expired or not valid; asking again returns the same answer. */
    data object Unavailable : AttachmentReadResult

    /** A dropped connection, a busy or failing host, or a timeout; asking again may work. */
    data object Failed : AttachmentReadResult
}

/** A reply that breaks the protocol; never retried, because the same bytes would come back. */
internal class AttachmentProtocolException(message: String) : IllegalStateException(message)

/** True for the canonical unpadded base64url form of 16 bytes. */
internal fun canonicalAttachmentId(id: String): Boolean =
    runCatching { Wire.decode(id, 16) }.isSuccess

/** Checks a `session.attachments.get` payload; throws [IllegalArgumentException] if it is not valid. */
internal fun requireAttachmentGet(payload: JsonObject) {
    Wire.keys(payload, setOf("type", "requestId", "sessionId", "attachmentId", "offset"))
    require(payload.text("type") == "session.attachments.get")
    require(opaqueId(payload.text("requestId")) && opaqueId(payload.text("sessionId")))
    require(canonicalAttachmentId(payload.text("attachmentId")))
    require(payload.long("offset") in 0..MAX_ATTACHMENT_READ_BYTES)
}

/** The fields [DefaultRemoteRepository] adds to the type and request ID of a read. */
internal fun attachmentGetFields(
    sessionId: String,
    attachmentId: String,
    offset: Long,
): Array<Pair<String, Any?>> =
    arrayOf<Pair<String, Any?>>(
            "sessionId" to sessionId,
            "attachmentId" to attachmentId,
            "offset" to offset,
        )
        .also {
            requireAttachmentGet(
                Wire.objectOf("type" to "session.attachments.get", "requestId" to "check", *it)
            )
        }

internal class ValidatedAttachmentChunk(
    val offset: Long,
    val totalBytes: Long,
    val sha256: String,
    val bytes: ByteArray,
)

/** Validates one `attachment.content` result against the request it answers. */
internal fun validatedAttachmentChunk(
    data: JsonObject,
    sessionId: String,
    attachmentId: String,
    offset: Long,
): ValidatedAttachmentChunk {
    Wire.keys(data, setOf("kind", "sessionId", "attachmentId", "offset", "totalBytes", "sha256", "data"))
    require(data.text("kind") == "attachment.content")
    require(data.text("sessionId") == sessionId && data.text("attachmentId") == attachmentId)
    require(data.long("offset") == offset)
    val total = data.long("totalBytes")
    require(total in 0..MAX_ATTACHMENT_READ_BYTES && offset <= total)
    val sha256 = data.text("sha256")
    require(Regex("[a-f0-9]{64}").matches(sha256))
    require(total != 0L || sha256 == EMPTY_SHA256)
    val encoded = data.text("data")
    require(encoded.length <= MAX_ATTACHMENT_CHUNK_CHARS)
    val bytes = Wire.decode(encoded)
    require(bytes.size.toLong() == minOf(ATTACHMENT_CHUNK_BYTES.toLong(), total - offset))
    return ValidatedAttachmentChunk(offset, total, sha256, bytes)
}

/**
 * Collects the chunks of one attachment by offset. The total and digest must equal what the
 * message declared, and the digest of the assembled bytes must match, before [bytes] is usable.
 */
internal class AttachmentReassembler(
    private val sessionId: String,
    private val attachment: RemoteAttachment,
) {
    private val buffer = ByteArrayOutputStream()
    private var complete = false

    /** The offset of the next request. */
    var offset = 0L
        private set

    /** Adds the reply to a request at [offset]; true once every byte has arrived and verified. */
    fun accept(data: JsonObject): Boolean {
        check(!complete)
        try {
            val chunk = validatedAttachmentChunk(data, sessionId, attachment.id, offset)
            require(chunk.totalBytes == attachment.size && chunk.sha256 == attachment.sha256)
            buffer.write(chunk.bytes)
            offset += chunk.bytes.size
            if (offset < chunk.totalBytes) return false
            require(AttachmentImportRules.sha256(buffer.toByteArray()) == attachment.sha256)
            complete = true
            return true
        } catch (e: Exception) {
            throw AttachmentProtocolException("Invalid attachment content")
        }
    }

    fun bytes(): ByteArray {
        check(complete)
        return buffer.toByteArray()
    }
}

/**
 * Verified image bytes kept in memory only, evicting the least recently used once [capacity] is
 * exceeded. Nothing here is ever written to disk.
 */
internal class AttachmentByteCache(private val capacity: Long = 16L * 1024 * 1024) {
    data class Key(val sessionId: String, val attachmentId: String, val sha256: String)

    private val entries = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var size = 0L

    @Synchronized operator fun get(key: Key): ByteArray? = entries[key]

    @Synchronized
    operator fun set(key: Key, bytes: ByteArray) {
        if (bytes.size > capacity) return
        entries.put(key, bytes)?.let { size -= it.size }
        size += bytes.size
        val iterator = entries.entries.iterator()
        while (size > capacity && iterator.hasNext()) {
            size -= iterator.next().value.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        size = 0
    }
}
