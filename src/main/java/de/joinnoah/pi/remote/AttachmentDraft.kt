package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

const val ATTACHMENTS_CAPABILITY = "session.attachments.v1"

data class RemoteAttachment(
    val id: String,
    val name: String,
    val kind: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val expiresAt: Long,
) {
    fun json(): JsonObject =
        Wire.objectOf(
            "id" to id,
            "name" to name,
            "kind" to kind,
            "mimeType" to mimeType,
            "size" to size,
            "sha256" to sha256,
            "expiresAt" to expiresAt,
        )
}

data class AttachmentUpload(
    val localId: String,
    val remoteId: String,
    val projectId: String,
    val attachment: RemoteAttachment? = null,
) {
    fun json(): JsonObject =
        Wire.objectOf(
            "localId" to localId,
            "remoteId" to remoteId,
            "projectId" to projectId,
            "attachment" to attachment?.json(),
        )
}

internal fun LocalAttachment.json(): JsonObject =
    JsonObject(descriptor() + ("id" to JsonPrimitive(id)))

internal fun LocalAttachment.descriptor(): JsonObject =
    Wire.objectOf(
        "name" to name,
        "kind" to kind,
        "mimeType" to mimeType,
        "size" to size,
        "sha256" to sha256,
    )

internal fun localAttachment(value: JsonObject): LocalAttachment =
    LocalAttachment(
            value.text("id"),
            value.text("name"),
            value.text("kind"),
            value.text("mimeType"),
            value.long("size"),
            value.text("sha256"),
        )
        .also {
            require(AttachmentImportRules.validId(it.id))
            require(
                it.name == AttachmentImportRules.name(it.name) &&
                    it.mimeType == AttachmentImportRules.mime(it.mimeType)
            )
            require(
                it.kind in setOf("file", "image") &&
                    it.size in 0..AttachmentImportRules.FILE_BYTES.toLong()
            )
            require(it.kind != "image" || it.size <= AttachmentImportRules.IMAGE_BYTES)
            require(Regex("[a-f0-9]{64}").matches(it.sha256))
        }

internal fun remoteAttachment(value: JsonObject): RemoteAttachment {
    Wire.keys(value, setOf("id", "name", "kind", "mimeType", "size", "sha256", "expiresAt"))
    val local = localAttachment(JsonObject(value - "expiresAt"))
    return RemoteAttachment(
        local.id,
        local.name,
        local.kind,
        local.mimeType,
        local.size,
        local.sha256,
        value.long("expiresAt").also { require(it >= 0) },
    )
}

internal fun attachmentUpload(value: JsonObject): AttachmentUpload =
    AttachmentUpload(
            value.text("localId"),
            value.text("remoteId"),
            value.text("projectId"),
            (value["attachment"] as? JsonObject)?.let(::remoteAttachment),
        )
        .also {
            require(
                AttachmentImportRules.validId(it.localId) &&
                    AttachmentImportRules.validId(it.remoteId)
            )
            require(it.attachment == null || it.attachment.id == it.remoteId)
        }

internal fun validAttachmentSet(values: List<LocalAttachment>): Boolean =
    values.size <= 5 &&
        values.map { it.id }.distinct().size == values.size &&
        values.sumOf { it.size } <= 40L * 1024 * 1024 &&
        values.filter { it.kind == "image" }.sumOf { it.size } <= 2L * 1024 * 1024

data class AttachedPrompt(val body: String, val attachments: List<RemoteAttachment> = emptyList())

internal object AttachmentCodec {
    private const val PREFIX = "[PocketPi attachments v1]\n"
    private const val SEPARATOR = "\n[/PocketPi attachments]\n\n"

    fun decode(text: String): AttachedPrompt {
        val literal = AttachedPrompt(text)
        if (!text.startsWith(PREFIX) || text.toByteArray().size > 128 * 1024) return literal
        return runCatching {
                val end = text.indexOf('\n', PREFIX.length)
                require(end >= 0 && text.startsWith(SEPARATOR, end))
                val line = text.substring(PREFIX.length, end)
                require('\r' !in line)
                val values =
                    Wire.json.parseToJsonElement(line).jsonArray.map { element ->
                        val value = element.jsonObject
                        val path = value.text("path")
                        require(
                            path.startsWith("/") &&
                                path.toByteArray().size <= 4096 &&
                                path.none(Character::isISOControl)
                        )
                        remoteAttachment(JsonObject(value - "path"))
                    }
                require(
                    values.isNotEmpty() &&
                        validAttachmentSet(
                            values.map { localAttachment(JsonObject(it.json() - "expiresAt")) }
                        )
                )
                AttachedPrompt(text.substring(end + SEPARATOR.length), values)
            }
            .getOrDefault(literal)
    }
}
