package de.joinnoah.pi.remote

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Images the agent mentions in a chat, read from the session folder on the Mac
 * (`session.files.media`, `session.files.media.get`; protocol README, "Agent images"). The host
 * advertises the capability only on the `capabilities.v2:` projects route, never at authentication.
 */
internal const val FILES_MEDIA_CAPABILITY = "session.files.media.v1"

internal const val MAX_MEDIA_RASTER_BYTES = 20L * 1024 * 1024
internal const val MAX_MEDIA_SVG_BYTES = 2L * 1024 * 1024
internal const val MEDIA_CHUNK_BYTES = 49_152
private const val MAX_MEDIA_CHUNK_CHARS = 65_536
private const val MAX_MEDIA_PATH_BYTES = 4096
private val MEDIA_SHA256 = Regex("[a-f0-9]{64}")

/** The image types the host reports; the type comes from the bytes, never from the name. */
internal enum class MediaMime(val wire: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif"),
    SVG("image/svg+xml", "svg");

    val isSvg: Boolean get() = this == SVG
    val byteLimit: Long get() = if (isSvg) MAX_MEDIA_SVG_BYTES else MAX_MEDIA_RASTER_BYTES

    companion object {
        fun fromWire(value: String): MediaMime? = entries.firstOrNull { it.wire == value }
    }
}

/** Why the host sends no image for a path. */
internal enum class MediaOmitted(val wire: String) {
    TOO_LARGE("too_large"),
    NOT_AN_IMAGE("not_an_image"),
}

/** True for a path the host parser accepts: relative or POSIX absolute, no `.`/`..`/empty segment. */
internal fun validMediaPath(path: String): Boolean {
    if (path.encodeToByteArray().size > MAX_MEDIA_PATH_BYTES) return false
    if (path.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return false
    val segments = path.removePrefix("/").split('/')
    if (path.isEmpty() || path == "/") return false
    return segments.none { it.isEmpty() || it == "." || it == ".." }
}

internal class MediaMeta(
    val path: String,
    val mediaId: String,
    val mime: MediaMime,
    val totalBytes: Long,
    val sha256: String,
)

/** A parsed `files.media` result: a copy to fetch, or why there is none. */
internal sealed interface MediaStart {
    class Ready(val meta: MediaMeta) : MediaStart

    class Omitted(val reason: MediaOmitted) : MediaStart
}

internal class MediaChunk(val offset: Long, val bytes: ByteArray)

/** The fields of a `session.files.media`; throws [IllegalArgumentException] for an invalid path. */
internal fun mediaFields(sessionId: String, path: String): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId) && validMediaPath(path))
    return arrayOf("sessionId" to sessionId, "path" to path)
}

/** The fields of a `session.files.media.get`; throws [IllegalArgumentException] when invalid. */
internal fun mediaGetFields(sessionId: String, mediaId: String, offset: Long): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId) && canonicalAttachmentId(mediaId) && offset in 0..MAX_MEDIA_RASTER_BYTES)
    return arrayOf("sessionId" to sessionId, "mediaId" to mediaId, "offset" to offset)
}

/**
 * Validates a `files.media` result. The result path is always the normalised relative path: equal
 * to [requestedPath] when that was relative, and its tail when it was absolute.
 */
internal fun parseFilesMedia(data: JsonObject, sessionId: String, requestedPath: String): MediaStart {
    require(data.text("kind") == "files.media")
    require(data.text("sessionId") == sessionId)
    val path = data.text("path")
    require(validMediaPath(path) && !path.startsWith("/"))
    if (requestedPath.startsWith("/")) require(requestedPath.endsWith("/$path"))
    else require(requestedPath == path)
    if (data.containsKey("omitted")) {
        Wire.keys(data, setOf("kind", "sessionId", "path", "omitted"))
        val reason = requireNotNull(MediaOmitted.entries.firstOrNull { it.wire == data.text("omitted") })
        return MediaStart.Omitted(reason)
    }
    Wire.keys(data, setOf("kind", "sessionId", "path", "mediaId", "mimeType", "totalBytes", "sha256"))
    val mediaId = data.text("mediaId")
    require(canonicalAttachmentId(mediaId))
    val mime = requireNotNull(MediaMime.fromWire(data.text("mimeType")))
    val total = data.long("totalBytes")
    require(total in 1..mime.byteLimit)
    val sha256 = data.text("sha256")
    require(MEDIA_SHA256.matches(sha256))
    return MediaStart.Ready(MediaMeta(path, mediaId, mime, total, sha256))
}

/** Validates a `files.media.content` result against the copy and the offset requested. */
internal fun parseMediaChunk(data: JsonObject, sessionId: String, meta: MediaMeta, offset: Long): MediaChunk {
    Wire.keys(data, setOf("kind", "sessionId", "mediaId", "offset", "totalBytes", "sha256", "data"))
    require(data.text("kind") == "files.media.content")
    require(data.text("sessionId") == sessionId && data.text("mediaId") == meta.mediaId)
    require(data.long("offset") == offset)
    require(data.long("totalBytes") == meta.totalBytes && offset <= meta.totalBytes)
    require(MEDIA_SHA256.matches(data.text("sha256")) && data.text("sha256") == meta.sha256)
    val encoded = data.text("data")
    require(encoded.length <= MAX_MEDIA_CHUNK_CHARS)
    val bytes = Wire.decode(encoded)
    require(bytes.size <= MEDIA_CHUNK_BYTES && offset + bytes.size <= meta.totalBytes)
    require(bytes.isNotEmpty() || offset == meta.totalBytes)
    return MediaChunk(offset, bytes)
}

/** Why a download produced no image; see [ProjectImageResult] for what the app shows. */
internal enum class MediaFailure {
    /** Not found, not allowed, or a reply that broke the protocol; asking again gives the same. */
    UNAVAILABLE,
    /** A dropped connection, a busy or failing host, a timeout or a damaged transfer. */
    FAILED,
}

internal class MediaException(val failure: MediaFailure) : Exception(failure.name)

/** What [downloadMedia] ends with: verified bytes, or the host's reason for sending none. */
internal sealed interface MediaDownload {
    class Ready(val meta: MediaMeta, val bytes: ByteArray) : MediaDownload

    class Omitted(val reason: MediaOmitted) : MediaDownload
}

private fun mediaRequestFailure(e: Exception): MediaException =
    when (e) {
        is RemoteRequestException ->
            MediaException(
                when (e.code) {
                    "invalid_path", "not_found", "forbidden", "invalid_request" -> MediaFailure.UNAVAILABLE
                    else -> MediaFailure.FAILED
                }
            )
        else -> MediaException(MediaFailure.FAILED)
    }

/**
 * Asks for the image with [start], then reads the copy with [read] from offset 0, chunk by chunk,
 * until `totalBytes` arrived, and checks the SHA-256. A `not_found` in the middle of the download
 * (the copy expired) starts over once; a second one fails. Throws [MediaException].
 */
internal suspend fun downloadMedia(
    sessionId: String,
    path: String,
    start: suspend () -> JsonObject,
    read: suspend (mediaId: String, offset: Long) -> JsonObject,
): MediaDownload {
    var restarted = false
    while (true) {
        val started =
            try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw mediaRequestFailure(e)
            }
        val meta =
            try {
                when (val parsed = parseFilesMedia(started, sessionId, path)) {
                    is MediaStart.Omitted -> return MediaDownload.Omitted(parsed.reason)
                    is MediaStart.Ready -> parsed.meta
                }
            } catch (e: Exception) {
                throw MediaException(MediaFailure.UNAVAILABLE)
            }
        val bytes =
            try {
                ByteArray(meta.totalBytes.toInt())
            } catch (e: OutOfMemoryError) {
                throw MediaException(MediaFailure.FAILED)
            }
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        var vanished = false
        while (offset < meta.totalBytes) {
            val data =
                try {
                    read(meta.mediaId, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemoteRequestException) {
                    if (e.code == "not_found" && !restarted) {
                        vanished = true
                        break
                    }
                    // A copy that vanished twice is a host under pressure, not a missing file.
                    throw if (e.code == "not_found") MediaException(MediaFailure.FAILED) else mediaRequestFailure(e)
                } catch (e: Exception) {
                    throw mediaRequestFailure(e)
                }
            val chunk =
                try {
                    parseMediaChunk(data, sessionId, meta, offset)
                } catch (e: Exception) {
                    throw MediaException(MediaFailure.UNAVAILABLE)
                }
            // Only the last chunk may be empty, and then offset == totalBytes ends the loop.
            if (chunk.bytes.isEmpty()) throw MediaException(MediaFailure.UNAVAILABLE)
            System.arraycopy(chunk.bytes, 0, bytes, offset.toInt(), chunk.bytes.size)
            digest.update(chunk.bytes)
            offset += chunk.bytes.size
        }
        if (vanished) {
            restarted = true
            continue
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (offset != meta.totalBytes || hash != meta.sha256) throw MediaException(MediaFailure.FAILED)
        return MediaDownload.Ready(meta, bytes)
    }
}

/** Outcome of [RemoteRepository.readProjectImage]; [Loaded] bytes passed the length and SHA-256 checks. */
sealed interface ProjectImageResult {
    class Loaded(
        /** The normalised path relative to the session folder. */
        val path: String,
        val mimeType: String,
        val sha256: String,
        val bytes: ByteArray,
    ) : ProjectImageResult {
        val isSvg: Boolean get() = mimeType == MediaMime.SVG.wire
    }

    /** No request was sent: the host lacks [FILES_MEDIA_CAPABILITY]. */
    data object Unsupported : ProjectImageResult

    /** Not found, not allowed, or not valid; asking again returns the same answer. */
    data object Unavailable : ProjectImageResult

    /** The host holds the file but it is over the size limit. */
    data object TooLarge : ProjectImageResult

    /** The host holds the file but its bytes are not a supported image. */
    data object NotAnImage : ProjectImageResult

    /** A dropped connection, a busy or failing host, or a timeout; asking again may work. */
    data object Failed : ProjectImageResult
}

/** Verified image bytes kept in memory only, evicting the least recently used. Never written to disk. */
internal class ProjectImageByteCache(private val capacity: Long = 32L * 1024 * 1024) {
    data class Key(val sessionId: String, val path: String)

    private val entries = LinkedHashMap<Key, ProjectImageResult.Loaded>(16, 0.75f, true)
    private var size = 0L

    @Synchronized operator fun get(key: Key): ProjectImageResult.Loaded? = entries[key]

    @Synchronized
    operator fun set(key: Key, value: ProjectImageResult.Loaded) {
        if (value.bytes.size > capacity) return
        entries.put(key, value)?.let { size -= it.bytes.size }
        size += value.bytes.size
        val iterator = entries.entries.iterator()
        while (size > capacity && iterator.hasNext()) {
            size -= iterator.next().value.bytes.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        size = 0
    }
}
