package de.joinnoah.pi.remote

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest

data class LocalAttachment(
    val id: String,
    val name: String,
    val kind: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
)

/** These blocking operations belong on an IO dispatcher. */
interface AttachmentStorage {
    fun write(attachment: LocalAttachment, bytes: ByteArray)

    /**
     * Stores the bytes of [input] as [attachment]. They must match its size and digest, which the
     * store checks once they are written.
     */
    fun write(attachment: LocalAttachment, input: InputStream) {
        write(attachment, AttachmentImportRules.readLimited(input))
    }

    /**
     * Opens the bytes of [attachment] for reading in pieces. The size and digest are checked when
     * the stream ends, so a reader that reads to the end has checked them.
     */
    fun open(attachment: LocalAttachment): InputStream = ByteArrayInputStream(read(attachment))

    fun read(attachment: LocalAttachment): ByteArray

    fun remove(id: String)

    fun cleanup(keepIds: Set<String>)
}

/**
 * Attachment bytes in `noBackupFilesDir`. New files use the streaming `.v2` format
 * ([StreamingCipher]). Files written before it are `attachment-<id>.enc`, one [EncryptedFileStore]
 * each: they are read as before and never rewritten.
 */
class AttachmentStore(
    private val context: Context,
    private val alias: String = "pi-remote-attachments-v1",
) : AttachmentStorage {
    private val root = context.noBackupFilesDir.canonicalFile

    private fun legacyFile(id: String): File = file(id, "enc")

    private fun streamFile(id: String): File = file(id, "v2")

    private fun file(id: String, extension: String): File {
        require(AttachmentImportRules.validId(id)) { "Invalid attachment identifier" }
        val file = File(root, "attachment-$id.$extension")
        require(file.canonicalFile == file.absoluteFile) { "Unsafe attachment file" }
        for (suffix in listOf(".bak", ".new")) {
            val sibling = File(file.path + suffix)
            require(sibling.canonicalFile == sibling.absoluteFile) { "Unsafe attachment file" }
        }
        return file
    }

    private fun validate(attachment: LocalAttachment) {
        require(attachment.name == AttachmentImportRules.name(attachment.name))
        require(attachment.mimeType == AttachmentImportRules.mime(attachment.mimeType))
        require(attachment.kind == "file" || attachment.kind == "image")
        require(attachment.size in 0L..AttachmentImportRules.FILE_BYTES.toLong())
        require(
            attachment.kind != "image" ||
                (attachment.size <= AttachmentImportRules.IMAGE_BYTES &&
                    attachment.mimeType == "image/jpeg")
        )
        require(Regex("[a-f0-9]{64}").matches(attachment.sha256))
    }

    /** The bytes that were stored or read must have the size and digest of [attachment]. */
    private fun checkContent(attachment: LocalAttachment, size: Long, sha256: String) {
        require(
            attachment.size == size &&
                MessageDigest.isEqual(attachment.sha256.toByteArray(), sha256.toByteArray())
        ) {
            "Attachment digest mismatch"
        }
    }

    private fun stored(id: String): Boolean =
        listOf(legacyFile(id), streamFile(id)).any {
            it.exists() || File(it.path + ".bak").exists()
        }

    @Synchronized
    override fun write(attachment: LocalAttachment, bytes: ByteArray) =
        write(attachment, ByteArrayInputStream(bytes))

    @Synchronized
    override fun write(attachment: LocalAttachment, input: InputStream) {
        validate(attachment)
        require(!stored(attachment.id)) { "Attachment already exists" }
        val target = AtomicFile(streamFile(attachment.id))
        val output = target.startWrite()
        try {
            val written =
                StreamingCipher.writeTo(input, output, AttachmentImportRules.FILE_BYTES.toLong()) {
                    EncryptedFileStore.wrapKey(alias, it)
                }
            checkContent(attachment, written.size, written.sha256)
            target.finishWrite(output)
        } catch (e: Exception) {
            Diagnostics.caught("attachment.write", e)
            target.failWrite(output)
            throw e
        }
    }

    @Synchronized
    override fun open(attachment: LocalAttachment): InputStream {
        validate(attachment)
        streamedFile(attachment)?.let {
            return it
        }
        val source = legacyFile(attachment.id)
        require(source.length() <= AttachmentImportRules.FILE_BYTES + 29L)
        // A legacy file that does not decrypt is quarantined alone by EncryptedFileStore, and then
        // reads as missing here.
        val encoded =
            requireNotNull(EncryptedFileStore(context, source.name, alias).read()) {
                "Attachment is unavailable"
            }
        require(encoded.isNotEmpty() && encoded[0] == 1.toByte())
        val bytes = encoded.copyOfRange(1, encoded.size)
        checkContent(attachment, bytes.size.toLong(), AttachmentImportRules.sha256(bytes))
        return ByteArrayInputStream(bytes)
    }

    /** The stream of the `.v2` file of [attachment], or null when it has none. */
    private fun streamedFile(attachment: LocalAttachment): InputStream? {
        val target = AtomicFile(streamFile(attachment.id))
        val source =
            try {
                target.openRead()
            } catch (_: FileNotFoundException) {
                return null
            }
        val plain =
            try {
                StreamingCipher.readFrom(source) { EncryptedFileStore.unwrapKey(alias, it) }
            } catch (error: Exception) {
                source.close()
                quarantine(target, error)
            }
        return Checked(plain, attachment, target)
    }

    /**
     * Deletes a `.v2` file that can never be read again, and drops its key when the Keystore has
     * lost it. The read then fails as a missing attachment. Any other failure, such as a transient
     * IOException, passes through and leaves the file alone.
     */
    private fun quarantine(target: AtomicFile, error: Exception): Nothing {
        val unreadable = EncryptedFileStore.unreadable(error)
        if (error !is StreamingCipher.CorruptFileException && unreadable == null) throw error
        Diagnostics.caught("attachment.quarantine", error)
        if (unreadable == EncryptedFileStore.Unreadable.KEY) EncryptedFileStore.dropKey(alias)
        target.delete()
        throw IllegalArgumentException("Attachment is unavailable")
    }

    /** Reads a `.v2` payload and checks its size and digest once it ends. */
    private inner class Checked(
        private val source: InputStream,
        private val attachment: LocalAttachment,
        private val target: AtomicFile,
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (finished) return -1
            val count =
                try {
                    source.read(buffer, offset, length)
                } catch (error: Exception) {
                    quarantine(target, error)
                }
            if (count < 0) {
                finished = true
                checkContent(attachment, size, AttachmentImportRules.hex(digest.digest()))
                return -1
            }
            size += count
            require(size <= attachment.size) { "Attachment digest mismatch" }
            digest.update(buffer, offset, count)
            return count
        }

        override fun close() = source.close()
    }

    @Synchronized
    override fun read(attachment: LocalAttachment): ByteArray =
        open(attachment).use { input ->
            val bytes = ByteArray(attachment.size.toInt())
            // Reading to the end checks the digest, so a short or long file fails here.
            require(AttachmentImportRules.readInto(input, bytes) == bytes.size && input.read() < 0) {
                "Attachment digest mismatch"
            }
            bytes
        }

    @Synchronized
    override fun remove(id: String) {
        AtomicFile(legacyFile(id)).delete()
        AtomicFile(streamFile(id)).delete()
    }

    @Synchronized
    override fun cleanup(keepIds: Set<String>) {
        require(keepIds.all(AttachmentImportRules::validId))
        val pattern = Regex("attachment-([A-Za-z0-9_-]{22})\\.(?:enc|v2)(?:\\.bak|\\.new)?")
        root.listFiles()?.forEach { candidate ->
            val id = pattern.matchEntire(candidate.name)?.groupValues?.get(1) ?: return@forEach
            if (id !in keepIds) remove(id)
        }
    }
}
