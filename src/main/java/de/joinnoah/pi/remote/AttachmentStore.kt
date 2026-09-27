package de.joinnoah.pi.remote

import android.content.Context
import android.util.AtomicFile
import java.io.File
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

    fun read(attachment: LocalAttachment): ByteArray

    fun remove(id: String)

    fun cleanup(keepIds: Set<String>)
}

class AttachmentStore(
    private val context: Context,
    private val alias: String = "pi-remote-attachments-v1",
) : AttachmentStorage {
    private val root = context.noBackupFilesDir.canonicalFile

    private fun file(id: String): File {
        require(AttachmentImportRules.validId(id)) { "Invalid attachment identifier" }
        val file = File(root, "attachment-$id.enc")
        require(file.canonicalFile == file.absoluteFile) { "Unsafe attachment file" }
        for (suffix in listOf(".bak", ".new")) {
            val sibling = File(file.path + suffix)
            require(sibling.canonicalFile == sibling.absoluteFile) { "Unsafe attachment file" }
        }
        return file
    }

    private fun validate(attachment: LocalAttachment, bytes: ByteArray) {
        require(attachment.name == AttachmentImportRules.name(attachment.name))
        require(attachment.mimeType == AttachmentImportRules.mime(attachment.mimeType))
        require(attachment.kind == "file" || attachment.kind == "image")
        require(
            attachment.size == bytes.size.toLong() && bytes.size <= AttachmentImportRules.FILE_BYTES
        )
        require(
            attachment.kind != "image" ||
                (bytes.size <= AttachmentImportRules.IMAGE_BYTES &&
                    attachment.mimeType == "image/jpeg")
        )
        require(Regex("[a-f0-9]{64}").matches(attachment.sha256))
        require(
            MessageDigest.isEqual(
                attachment.sha256.toByteArray(),
                AttachmentImportRules.sha256(bytes).toByteArray(),
            )
        ) {
            "Attachment digest mismatch"
        }
    }

    @Synchronized
    override fun write(attachment: LocalAttachment, bytes: ByteArray) {
        val destination = file(attachment.id)
        validate(attachment, bytes)
        require(!destination.exists() && !File(destination.path + ".bak").exists()) {
            "Attachment already exists"
        }
        // A version byte also represents an empty file in the existing encrypted store format.
        EncryptedFileStore(context, destination.name, alias).write(byteArrayOf(1) + bytes)
    }

    @Synchronized
    override fun read(attachment: LocalAttachment): ByteArray {
        val source = file(attachment.id)
        require(source.length() <= AttachmentImportRules.FILE_BYTES + 29L)
        val encoded =
            requireNotNull(EncryptedFileStore(context, source.name, alias).read()) {
                "Attachment is unavailable"
            }
        require(encoded.isNotEmpty() && encoded[0] == 1.toByte())
        return encoded.copyOfRange(1, encoded.size).also { validate(attachment, it) }
    }

    @Synchronized
    override fun remove(id: String) {
        AtomicFile(file(id)).delete()
    }

    @Synchronized
    override fun cleanup(keepIds: Set<String>) {
        require(keepIds.all(AttachmentImportRules::validId))
        val pattern = Regex("attachment-([A-Za-z0-9_-]{22})\\.enc(?:\\.bak|\\.new)?")
        root.listFiles()?.forEach { candidate ->
            val id = pattern.matchEntire(candidate.name)?.groupValues?.get(1) ?: return@forEach
            if (id !in keepIds) remove(id)
        }
    }
}
