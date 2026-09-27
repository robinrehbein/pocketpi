package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentDraftTest {
    private val item =
        LocalAttachment(
            "AAAAAAAAAAAAAAAAAAAAAA",
            "document.txt",
            "file",
            "text/plain",
            3,
            "a".repeat(64),
        )

    @Test
    fun sharedAttachmentFixturesDecodeBeforeQuotesAndInvalidRemainLiteral() {
        val fixture =
            Wire.json
                .parseToJsonElement(javaClass.getResource("/attachments-v1.json")!!.readText())
                .jsonObject
        for (value in fixture.array("valid")) {
            val decoded = AttachmentCodec.decode(value.text("text"))
            assertEquals(value.text("body"), decoded.body)
            assertEquals(
                value.array("attachments").map { remoteAttachment(JsonObject(it - "path")) },
                decoded.attachments,
            )
            val bubble =
                conversationItems(
                        listOf(
                            Wire.objectOf(
                                "id" to "m",
                                "role" to "user",
                                "state" to "complete",
                                "text" to value.text("text"),
                            )
                        )
                    )
                    .single() as ConversationItem.Bubble
            assertEquals("Read the file.", bubble.text)
            assertEquals("previous", bubble.quote?.messageId)
            assertEquals(decoded.attachments, bubble.attachments)
        }
        for (value in fixture.array("invalid")) {
            val decoded = AttachmentCodec.decode(value.text("text"))
            assertEquals(value.text("text"), decoded.body)
            assertTrue(decoded.attachments.isEmpty())
        }
    }

    @Test
    fun attachmentOnlyHistoryAndLegacyManifestKeepMetadataWithoutHostPaths() {
        val metadata =
            RemoteAttachment(
                item.id,
                item.name,
                item.kind,
                item.mimeType,
                item.size,
                item.sha256,
                1234,
            )
        val manifest =
            JsonObject(metadata.json() + ("path" to JsonPrimitive("/private/host/document.txt")))
        val decoded =
            AttachmentCodec.decode(
                "[PocketPi attachments v1]\n" +
                    JsonArray(listOf(manifest)) +
                    "\n[/PocketPi attachments]\n\n"
            )
        assertEquals("", decoded.body)
        assertEquals(listOf(metadata), decoded.attachments)
        assertFalse(decoded.attachments.single().json().containsKey("path"))
        val message =
            Wire.objectOf(
                "id" to "message",
                "role" to "user",
                "text" to "",
                "state" to "complete",
                "attachments" to JsonArray(listOf(metadata.json())),
            )
        assertEquals(
            listOf(metadata),
            (conversationItems(listOf(message)).single() as ConversationItem.Bubble).attachments,
        )
    }

    @Test
    fun setBoundsCountAndImageBudgetBeforeSend() {
        assertFalse(validAttachmentSet((0..5).map { item.copy(id = it.toString()) }))
        assertFalse(
            validAttachmentSet(
                (0..2).map { item.copy(id = it.toString(), kind = "image", size = 1024 * 1024) }
            )
        )
        assertTrue(validAttachmentSet(listOf(item)))
    }
}
