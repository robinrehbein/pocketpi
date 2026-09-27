package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.serialization.json.*

data class DraftKey(val routeId: String, val sessionId: String)

data class PendingFollowUp(
    val requestId: String,
    val text: String,
    val status: String = "pending",
    val delivery: String = "follow_up",
)

data class StoredDraft(
    val text: String = "",
    val mutationId: String? = null,
    val submittedText: String? = null,
    val quote: MessageQuote? = null,
    val submittedQuote: MessageQuote? = null,
    val attachments: List<LocalAttachment> = emptyList(),
    val submittedAttachments: List<LocalAttachment> = emptyList(),
    val uploads: List<AttachmentUpload> = emptyList(),
    val followUps: List<PendingFollowUp> = emptyList(),
    /** Pending review comments of the changes view; an optional key in the stored JSON. */
    val reviewComments: List<ReviewComment> = emptyList(),
)

interface DraftStorage {
    fun load(): Map<DraftKey, StoredDraft>

    fun save(drafts: Map<DraftKey, StoredDraft>)
}

class DraftStore(
    context: Context,
    name: String = "session-drafts.enc",
    alias: String = "pi-remote-drafts-v1",
) : DraftStorage {
    private val file = EncryptedFileStore(context, name, alias)

    override fun load(): Map<DraftKey, StoredDraft> = file.read()?.let(::parseStoredDrafts).orEmpty()

    override fun save(drafts: Map<DraftKey, StoredDraft>) {
        require(drafts.values.all { it.text.toByteArray().size <= 128 * 1024 && it.followUps.size <= 64 })
        file.write(
            JsonArray(
                    drafts.map { (key, value) ->
                        Wire.objectOf(
                            "routeId" to key.routeId,
                            "sessionId" to key.sessionId,
                            "text" to value.text,
                            "mutationId" to value.mutationId,
                            "submittedText" to value.submittedText,
                            "quote" to value.quote?.json(),
                            "submittedQuote" to value.submittedQuote?.json(),
                            "attachments" to JsonArray(value.attachments.map { it.json() }),
                            "submittedAttachments" to
                                JsonArray(value.submittedAttachments.map { it.json() }),
                            "uploads" to JsonArray(value.uploads.map { it.json() }),
                            "followUps" to JsonArray(value.followUps.map {
                                Wire.objectOf("requestId" to it.requestId, "text" to it.text, "status" to it.status,
                                    "delivery" to it.delivery)
                            }),
                            "reviewComments" to
                                value.reviewComments.takeIf { it.isNotEmpty() }
                                    ?.let { comments -> JsonArray(comments.map { it.json() }) },
                        )
                    }
                )
                .toString()
                .toByteArray()
        )
    }
}

/** The drafts in a decrypted draft file. */
internal fun parseStoredDrafts(bytes: ByteArray): Map<DraftKey, StoredDraft> =
    Wire.json.parseToJsonElement(Wire.utf8(bytes)).jsonArray.associate { element ->
        val value = element.jsonObject
        val text = value.text("text")
        require(text.toByteArray().size <= 128 * 1024)
        DraftKey(value.text("routeId"), value.text("sessionId")) to
            StoredDraft(
                text,
                value.optionalText("mutationId"),
                value.optionalText("submittedText"),
                (value["quote"] as? JsonObject)?.let {
                    requireNotNull(QuoteCodec.parse(it))
                },
                (value["submittedQuote"] as? JsonObject)?.let {
                    requireNotNull(QuoteCodec.parse(it))
                },
                (value["attachments"] as? JsonArray)
                    ?.map { localAttachment(it.jsonObject) }
                    .orEmpty()
                    .also { require(validAttachmentSet(it)) },
                (value["submittedAttachments"] as? JsonArray)
                    ?.map { localAttachment(it.jsonObject) }
                    .orEmpty()
                    .also { require(validAttachmentSet(it)) },
                (value["uploads"] as? JsonArray)
                    ?.map { attachmentUpload(it.jsonObject) }
                    .orEmpty(),
                (value["followUps"] as? JsonArray)
                    ?.map {
                        val item = it.jsonObject
                        val id = item.text("requestId")
                        val body = item.text("text")
                        val status = item.text("status")
                        require(Wire.decode(id, 16).size == 16)
                        require(body.toByteArray().size <= 128 * 1024)
                        require(status in setOf("pending", "accepted", "uncertain", "delivered", "cancelled"))
                        val delivery = item.optionalText("delivery") ?: "follow_up"
                        require(delivery in setOf("follow_up", "steer"))
                        PendingFollowUp(id, body, status, delivery)
                    }
                    .orEmpty()
                    .also { require(it.size <= 64 && it.map(PendingFollowUp::requestId).distinct().size == it.size) },
                storedReviewComments(value["reviewComments"]),
            )
    }
