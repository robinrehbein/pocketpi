package de.joinnoah.pi.remote

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject

/** Rendering a session as one HTML file and reading it back (`session.export`, `session.export.get`). */
internal const val EXPORT_CAPABILITY = "session.export.v1"

internal const val MAX_EXPORT_BYTES = 20L * 1024 * 1024
internal const val EXPORT_CHUNK_BYTES = 49_152
private const val MAX_EXPORT_CHUNK_CHARS = 65_536
private val EXPORT_FILE_NAME = Regex("pi-session-\\d{8}-\\d{6}\\.html")
private val SHA256_HEX = Regex("[a-f0-9]{64}")

/** Why an export did not produce a file. */
enum class ExportFailure {
    /** The host lacks [EXPORT_CAPABILITY], or the session cannot be exported (not host-owned). */
    UNSUPPORTED,
    /** The host holds another export of this session or reached a cap; asking again shortly may work. */
    BUSY,
    /** The export vanished again after one restart. */
    NOT_FOUND,
    OFFLINE,
    TIMED_OUT,
    TOO_LARGE,
    HASH_MISMATCH,
    /** The reply broke the protocol. */
    PROTOCOL,
    /** The device could not write the file. */
    STORAGE,
    FAILED,
}

internal class ExportException(val failure: ExportFailure) : Exception(failure.name)

/** Outcome of [RemoteRepository.exportSession]; [Ready] bytes passed the length and SHA-256 checks. */
sealed interface ExportResult {
    class Ready(val fileName: String, val bytes: ByteArray) : ExportResult

    data class Failed(val failure: ExportFailure) : ExportResult
}

internal class ExportMeta(
    val exportId: String,
    val fileName: String,
    val totalBytes: Long,
    val sha256: String,
)

internal class ExportChunk(val offset: Long, val bytes: ByteArray)

/** Validates a `session.export` result against the session it was requested for. */
internal fun validatedExportMeta(data: JsonObject, sessionId: String): ExportMeta {
    Wire.keys(
        data,
        setOf("kind", "sessionId", "exportId", "fileName", "mimeType", "totalBytes", "sha256"),
    )
    require(data.text("kind") == "export")
    require(data.text("sessionId") == sessionId)
    val exportId = data.text("exportId")
    require(canonicalAttachmentId(exportId))
    val fileName = data.text("fileName")
    require(EXPORT_FILE_NAME.matches(fileName))
    require(data.text("mimeType") == "text/html")
    val total = data.long("totalBytes")
    require(total in 1..MAX_EXPORT_BYTES)
    val sha256 = data.text("sha256")
    require(SHA256_HEX.matches(sha256))
    return ExportMeta(exportId, fileName, total, sha256)
}

/** Validates a `session.export.content` result against the export and the offset requested. */
internal fun validatedExportChunk(
    data: JsonObject,
    sessionId: String,
    meta: ExportMeta,
    offset: Long,
): ExportChunk {
    Wire.keys(data, setOf("kind", "sessionId", "exportId", "offset", "totalBytes", "sha256", "data"))
    require(data.text("kind") == "export.content")
    require(data.text("sessionId") == sessionId && data.text("exportId") == meta.exportId)
    require(data.long("offset") == offset)
    require(data.long("totalBytes") == meta.totalBytes && offset <= meta.totalBytes)
    require(data.text("sha256") == meta.sha256)
    val encoded = data.text("data")
    require(encoded.length <= MAX_EXPORT_CHUNK_CHARS)
    val bytes = Wire.decode(encoded)
    require(bytes.size <= EXPORT_CHUNK_BYTES && offset + bytes.size <= meta.totalBytes)
    require(bytes.isNotEmpty() || offset == meta.totalBytes)
    return ExportChunk(offset, bytes)
}

/** The fields of a `session.export.get`; throws [IllegalArgumentException] for an invalid request. */
internal fun exportGetFields(
    sessionId: String,
    exportId: String,
    offset: Long,
): Array<Pair<String, Any?>> {
    require(canonicalAttachmentId(exportId) && offset in 0..MAX_EXPORT_BYTES)
    return arrayOf("sessionId" to sessionId, "exportId" to exportId, "offset" to offset)
}

private fun requestFailure(e: Exception, fromStart: Boolean): ExportException =
    when (e) {
        is RemoteRequestException ->
            ExportException(
                when (e.code) {
                    "busy" -> ExportFailure.BUSY
                    // Nothing exists yet at the start, so "not found" is no expiry there.
                    "not_found" -> if (fromStart) ExportFailure.FAILED else ExportFailure.NOT_FOUND
                    "unsupported" -> ExportFailure.UNSUPPORTED
                    "offline" -> ExportFailure.OFFLINE
                    // A render over the limit is refused at the start; a read never is.
                    "invalid_request" -> if (fromStart) ExportFailure.TOO_LARGE else ExportFailure.PROTOCOL
                    else -> ExportFailure.FAILED
                }
            )
        is IllegalStateException ->
            ExportException(
                if (e.message == "Request timed out") ExportFailure.TIMED_OUT else ExportFailure.FAILED
            )
        else -> ExportException(ExportFailure.FAILED)
    }

/**
 * Starts an export with [start] and reads it with [read] from offset 0, chunk by chunk, until
 * `totalBytes` arrived, then checks the SHA-256. A `not_found` in the middle of the download
 * restarts the export once; a second one fails. Throws [ExportException].
 */
internal suspend fun downloadExport(
    sessionId: String,
    start: suspend () -> JsonObject,
    read: suspend (exportId: String, offset: Long) -> JsonObject,
): ExportResult.Ready {
    var restarted = false
    while (true) {
        val started =
            try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw requestFailure(e, fromStart = true)
            }
        val meta =
            try {
                validatedExportMeta(started, sessionId)
            } catch (e: Exception) {
                throw ExportException(ExportFailure.PROTOCOL)
            }
        val bytes =
            try {
                ByteArray(meta.totalBytes.toInt())
            } catch (e: OutOfMemoryError) {
                throw ExportException(ExportFailure.STORAGE)
            }
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        var vanished = false
        while (offset < meta.totalBytes) {
            val data =
                try {
                    read(meta.exportId, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RemoteRequestException) {
                    if (e.code == "not_found" && !restarted) {
                        vanished = true
                        break
                    }
                    throw requestFailure(e, fromStart = false)
                } catch (e: Exception) {
                    throw requestFailure(e, fromStart = false)
                }
            val chunk =
                try {
                    validatedExportChunk(data, sessionId, meta, offset)
                } catch (e: Exception) {
                    throw ExportException(ExportFailure.PROTOCOL)
                }
            // Only the last chunk may be empty, and then offset == totalBytes ends the loop.
            if (chunk.bytes.isEmpty()) throw ExportException(ExportFailure.PROTOCOL)
            System.arraycopy(chunk.bytes, 0, bytes, offset.toInt(), chunk.bytes.size)
            digest.update(chunk.bytes)
            offset += chunk.bytes.size
        }
        if (vanished) {
            restarted = true
            continue
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (offset != meta.totalBytes || hash != meta.sha256)
            throw ExportException(ExportFailure.HASH_MISMATCH)
        return ExportResult.Ready(meta.fileName, bytes)
    }
}

/** Where a running export stands in the chat, shown by `ExportStatus`. */
sealed interface ExportState {
    data object Exporting : ExportState

    data class Failed(val failure: ExportFailure) : ExportState

    /** The file is written; [uri] is the content URI to share. */
    data class Done(val uri: String) : ExportState
}

/** Writes exports to `cacheDir/exports/`, the only directory the export FileProvider serves. */
internal object ExportStorage {
    const val DIRECTORY = "exports"
    private const val FALLBACK_NAME = "session.html"

    /** A safe file name ending in `.html`; anything unusable falls back to [FALLBACK_NAME]. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".html")
        val cleaned =
            base.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(64)
        return if (cleaned.isEmpty()) FALLBACK_NAME else "$cleaned.html"
    }

    /** Deletes every stored export. Blocking. */
    fun clear(cacheDir: File) {
        File(cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** Deletes every earlier export, then writes [bytes] and returns the file. Blocking. */
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIRECTORY)
        check(dir.isDirectory || dir.mkdirs()) { "Export directory unavailable" }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, safeName(name))
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Unsafe export file" }
        // Written outside the served folder, so a half-written file is never shareable.
        val temporary = File(cacheDir, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
        check(file.length() == bytes.size.toLong()) { "Export file could not be written" }
        return file
    }
}
