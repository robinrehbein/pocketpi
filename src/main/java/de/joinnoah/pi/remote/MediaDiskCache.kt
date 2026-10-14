package de.joinnoah.pi.remote

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** A project image path as the host last described it: its stored path, type and SHA-256. */
data class MediaPointer(val path: String, val mimeType: String, val sha256: String)

/**
 * Sent attachments and project images kept on disk between runs, encrypted with the `.v2` streaming
 * format ([StreamingCipher]) under the Keystore key [alias].
 *
 * Image files are named by the SHA-256 of their bytes, so a copy never needs invalidating. A project
 * path is remembered by a small pointer file that names the digest, because the digest is only known
 * once the host has sent the image. Every route has its own folder, which goes when the route is
 * unpaired. The total size is capped; the least recently used files go first, and a read counts as a
 * use, so the newest and the visible images stay.
 *
 * Every read is checked against its digest. A file that cannot be decrypted is deleted; a lost key
 * clears the whole cache. A failure is a miss: nothing here throws to its caller. Callers run it off
 * the main thread.
 */
class MediaDiskCache(
    private val root: File,
    private val capacityBytes: Long = MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val alias: String = ALIAS,
    private val wrap: (ByteArray) -> ByteArray = { EncryptedFileStore.wrapKey(alias, it) },
    private val unwrap: (ByteArray) -> ByteArray = { EncryptedFileStore.unwrapKey(alias, it) },
) {
    /** The bytes stored under [sha256] for [routeId], verified against the digest, or null. */
    @Synchronized
    fun get(routeId: String, sha256: String): ByteArray? {
        if (!validDigest(sha256)) return null
        val bytes = read(file(routeId, "$sha256.v2")) ?: return null
        if (AttachmentImportRules.sha256(bytes) == sha256) return bytes
        Diagnostics.record(Diagnostics.Kind.CAUGHT, "media.digest", outcome = "mismatch")
        file(routeId, "$sha256.v2").delete()
        return null
    }

    /** Stores [bytes] under their own SHA-256 for [routeId]. Bytes that do not match [sha256] are dropped. */
    @Synchronized
    fun put(routeId: String, sha256: String, bytes: ByteArray) {
        if (!validDigest(sha256) || bytes.size > capacityBytes) return
        if (AttachmentImportRules.sha256(bytes) != sha256) return
        write(file(routeId, "$sha256.v2"), bytes)
    }

    /** The pointer of the project image [path] in [sessionId] on [routeId], or null. */
    @Synchronized
    fun pointer(routeId: String, sessionId: String, path: String): MediaPointer? {
        val bytes = read(pointerFile(routeId, sessionId, path)) ?: return null
        val fields = String(bytes, Charsets.UTF_8).split('\n')
        if (fields.size != 5 || fields[0] != sessionId || fields[1] != path || !validDigest(fields[4])) return null
        return MediaPointer(fields[2], fields[3], fields[4])
    }

    /** Remembers that [path] in [sessionId] on [routeId] is [pointer]. Fields that break the line format are refused. */
    @Synchronized
    fun putPointer(routeId: String, sessionId: String, path: String, pointer: MediaPointer) {
        val fields = listOf(sessionId, path, pointer.path, pointer.mimeType, pointer.sha256)
        if (fields.any { it.contains('\n') || it.contains('\r') }) return
        write(pointerFile(routeId, sessionId, path), fields.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** Deletes everything stored for [routeId]: its Mac was unpaired. */
    @Synchronized
    fun clearRoute(routeId: String) {
        folder(routeId).deleteRecursively()
    }

    /** Deletes the whole cache, as when its key or the pairings were lost. */
    @Synchronized
    fun clear() {
        root.deleteRecursively()
    }

    private fun folder(routeId: String): File = File(root, AttachmentImportRules.sha256(routeId.toByteArray()))

    private fun file(routeId: String, name: String): File = File(folder(routeId), name)

    private fun pointerFile(routeId: String, sessionId: String, path: String): File =
        file(routeId, "ptr-${AttachmentImportRules.sha256("$sessionId\n$path".toByteArray())}.v2")

    private fun read(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            FileInputStream(file).use { source ->
                StreamingCipher.readFrom(source, unwrap).use { it.readBytes() }
            }.also { file.setLastModified(now()) }
        } catch (error: Exception) {
            Diagnostics.caught("media.read", error)
            when {
                EncryptedFileStore.unreadable(error) == EncryptedFileStore.Unreadable.KEY -> {
                    clear()
                    EncryptedFileStore.dropKey(alias)
                }
                error is StreamingCipher.CorruptFileException ||
                    EncryptedFileStore.unreadable(error) != null -> file.delete()
            }
            // Any other failure, such as an IOException, is a miss and leaves the file alone.
            null
        }
    }

    /**
     * Writes [bytes] to a temporary file and renames it over [target], so a reader never sees a
     * half-written file. A failed write leaves the previous file, if any, in place.
     */
    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".new")
        try {
            FileOutputStream(temporary).use { output ->
                StreamingCipher.writeTo(ByteArrayInputStream(bytes), output, bytes.size.toLong(), wrap)
            }
            if (!temporary.renameTo(target)) throw IOException("Media file not replaced")
        } catch (error: Exception) {
            temporary.delete()
            Diagnostics.caught("media.write", error)
            if (EncryptedFileStore.unreadable(error) == EncryptedFileStore.Unreadable.KEY) {
                clear()
                EncryptedFileStore.dropKey(alias)
            }
            return
        }
        target.setLastModified(now())
        evict(keep = target)
    }

    /** Deletes the least recently used files, never [keep], until the cache fits its cap. */
    private fun evict(keep: File) {
        if (!root.isDirectory) return
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= capacityBytes) return
        for (candidate in files.sortedBy { it.lastModified() }) {
            if (total <= capacityBytes) break
            if (candidate == keep) continue
            total -= candidate.length()
            candidate.delete()
        }
    }

    companion object {
        /** The Keystore alias of the cache key; one key for every route. */
        const val ALIAS = "pi-remote-media-v1"

        /** The most the cache holds on disk; the least recently used files go beyond it. */
        const val MAX_BYTES = 64L * 1024 * 1024

        private val digest = Regex("[a-f0-9]{64}")

        private fun validDigest(value: String) = digest.matches(value)
    }
}
