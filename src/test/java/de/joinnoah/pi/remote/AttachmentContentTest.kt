package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `attachments-v1.json` wire entries and the chunk reassembly rules. */
class AttachmentContentTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/attachments-v1.json")!!.readText()).jsonObject
    private val sessionId = "history-session"
    private val attachmentId = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    private fun isGet(payload: JsonObject) = payload.text("type") == "session.attachments.get"

    @Test
    fun validRequestIsExactlyWhatTheBuilderProduces() {
        val get = payloads("wireValid").map { it.second }.single(::isGet)
        requireAttachmentGet(get)
        val built =
            Wire.objectOf(
                "type" to "session.attachments.get",
                "requestId" to get.text("requestId"),
                *attachmentGetFields(get.text("sessionId"), get.text("attachmentId"), get.long("offset")),
            )
        assertEquals(get, built)
    }

    @Test
    fun invalidRequestsAreRejectedAndCannotBeBuilt() {
        val invalid = payloads("wireInvalid").filter { isGet(it.second) }
        assertTrue(invalid.isNotEmpty())
        for ((name, payload) in invalid) {
            assertThrows(name, IllegalArgumentException::class.java) { requireAttachmentGet(payload) }
            if (payload.keys == setOf("type", "requestId", "sessionId", "attachmentId", "offset"))
                assertThrows(name, IllegalArgumentException::class.java) {
                    attachmentGetFields(payload.text("sessionId"), payload.text("attachmentId"), payload.long("offset"))
                }
        }
    }

    @Test
    fun offsetBoundsAndNoncanonicalIds() {
        attachmentGetFields("s", attachmentId, MAX_ATTACHMENT_READ_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            attachmentGetFields("s", attachmentId, MAX_ATTACHMENT_READ_BYTES + 1)
        }
        // The last character of a 16-byte ID must carry zero padding bits.
        assertFalse(canonicalAttachmentId("AAAAAAAAAAAAAAAAAAAAAB"))
        assertFalse(canonicalAttachmentId("AAAAAAAAAAAAAAAAAAAA+A"))
        assertTrue(canonicalAttachmentId(attachmentId))
    }

    @Test
    fun validContentIsAcceptedAndInvalidContentRejected() {
        for ((name, payload) in payloads("wireValid").filterNot { isGet(it.second) }) {
            val data = payload.obj("data")
            val chunk = validatedAttachmentChunk(data, sessionId, attachmentId, data.long("offset"))
            assertEquals(name, data.long("totalBytes"), chunk.totalBytes)
        }
        for ((name, payload) in payloads("wireInvalid").filterNot { isGet(it.second) }) {
            val data = payload.obj("data")
            assertThrows(name, IllegalArgumentException::class.java) {
                validatedAttachmentChunk(data, sessionId, attachmentId, data.long("offset"))
            }
        }
    }

    private val bytes = ByteArray(ATTACHMENT_CHUNK_BYTES * 2 + 10) { (it * 31).toByte() }
    private val digest = AttachmentImportRules.sha256(bytes)

    private fun attachment(content: ByteArray = bytes, sha256: String = AttachmentImportRules.sha256(content)) =
        RemoteAttachment(attachmentId, "photo.jpg", "image", "image/jpeg", content.size.toLong(), sha256, 1)

    private fun reply(
        offset: Long,
        content: ByteArray = bytes,
        total: Long = content.size.toLong(),
        sha256: String = AttachmentImportRules.sha256(content),
        length: Int = minOf(ATTACHMENT_CHUNK_BYTES.toLong(), total - offset).toInt(),
        data: String = Wire.encode(content.copyOfRange(offset.toInt(), offset.toInt() + length)),
        extra: Map<String, JsonElement> = emptyMap(),
        id: String = attachmentId,
    ) =
        JsonObject(
            mapOf(
                "kind" to JsonPrimitive("attachment.content"),
                "sessionId" to JsonPrimitive(sessionId),
                "attachmentId" to JsonPrimitive(id),
                "offset" to JsonPrimitive(offset),
                "totalBytes" to JsonPrimitive(total),
                "sha256" to JsonPrimitive(sha256),
                "data" to JsonPrimitive(data),
            ) + extra
        )

    private fun reassembler(content: ByteArray = bytes) = AttachmentReassembler(sessionId, attachment(content))

    private fun rejects(block: () -> Unit) = assertThrows(AttachmentProtocolException::class.java) { block() }

    @Test
    fun multiChunkReadReassemblesAndVerifiesTheDigest() {
        val reassembler = reassembler()
        assertFalse(reassembler.accept(reply(0)))
        assertEquals(ATTACHMENT_CHUNK_BYTES.toLong(), reassembler.offset)
        assertFalse(reassembler.accept(reply(ATTACHMENT_CHUNK_BYTES.toLong())))
        assertTrue(reassembler.accept(reply(ATTACHMENT_CHUNK_BYTES * 2L)))
        assertArrayEquals(bytes, reassembler.bytes())
        assertEquals(digest, AttachmentImportRules.sha256(reassembler.bytes()))
    }

    @Test
    fun emptyFileCompletesOnTheFirstReply() {
        val empty = ByteArray(0)
        val reassembler = reassembler(empty)
        assertTrue(reassembler.accept(reply(0, empty)))
        assertEquals(0, reassembler.bytes().size)
    }

    @Test
    fun eofReplyIsAValidChunkButAnOffsetAtTheEndNeedsEmptyData() {
        val end = bytes.size.toLong()
        val chunk = validatedAttachmentChunk(reply(end, data = ""), sessionId, attachmentId, end)
        assertEquals(0, chunk.bytes.size)
        assertThrows(IllegalArgumentException::class.java) {
            validatedAttachmentChunk(reply(end - 1, length = 0, data = ""), sessionId, attachmentId, end - 1)
        }
    }

    @Test
    fun wrongChunkLengthIsRejected() {
        rejects { reassembler().accept(reply(0, length = ATTACHMENT_CHUNK_BYTES - 1)) }
        rejects { reassembler().accept(reply(0, length = 0, data = "")) }
        val partial = reassembler().also { it.accept(reply(0)); it.accept(reply(ATTACHMENT_CHUNK_BYTES.toLong())) }
        rejects { partial.accept(reply(ATTACHMENT_CHUNK_BYTES * 2L, length = 9)) }
    }

    @Test
    fun offsetOrIdentityMismatchIsRejected() {
        rejects { reassembler().accept(reply(1, length = ATTACHMENT_CHUNK_BYTES)) }
        rejects { reassembler().accept(reply(0, id = "BBBBBBBBBBBBBBBBBBBBBB")) }
        val other = AttachmentReassembler("other", attachment())
        rejects { other.accept(reply(0)) }
    }

    @Test
    fun changedTotalOrDigestBetweenChunksIsRejected() {
        val first = reassembler().also { it.accept(reply(0)) }
        rejects { first.accept(reply(ATTACHMENT_CHUNK_BYTES.toLong(), total = bytes.size + 1L)) }
        val second = reassembler().also { it.accept(reply(0)) }
        rejects { second.accept(reply(ATTACHMENT_CHUNK_BYTES.toLong(), sha256 = "0".repeat(64))) }
    }

    @Test
    fun finalDigestMismatchIsRejected() {
        val tampered = bytes.copyOf().also { it[bytes.size - 1] = (it[bytes.size - 1] + 1).toByte() }
        val reassembler = reassembler()
        reassembler.accept(reply(0))
        reassembler.accept(reply(ATTACHMENT_CHUNK_BYTES.toLong()))
        rejects { reassembler.accept(reply(ATTACHMENT_CHUNK_BYTES * 2L, content = tampered, total = bytes.size.toLong(), sha256 = digest)) }
    }

    @Test
    fun declaredSizeAndDigestMustMatchTheMessage() {
        val wrongSize = AttachmentReassembler(sessionId, attachment().copy(size = bytes.size + 1L))
        rejects { wrongSize.accept(reply(0)) }
        val wrongDigest = AttachmentReassembler(sessionId, attachment(sha256 = "f".repeat(64)))
        rejects { wrongDigest.accept(reply(0)) }
    }

    @Test
    fun noncanonicalBase64AndExtraKeysAreRejected() {
        val small = ByteArray(5) { it.toByte() }
        val good = Wire.encode(small)
        assertTrue(reassembler(small).accept(reply(0, small)))
        for (data in listOf("$good=", good.replace('-', '+') + "+", "AAA/", good.dropLast(1) + "B", good.substring(0, good.length - 1) + "=")) {
            rejects { reassembler(small).accept(reply(0, small, data = data)) }
        }
        rejects { reassembler(small).accept(reply(0, small, extra = mapOf("extra" to JsonPrimitive(1)))) }
    }

    @Test
    fun attachmentsKeepTheirOrderInRunsOfImages() {
        fun a(id: String, kind: String) = RemoteAttachment(id, id, kind, "x/y", 1, "a".repeat(64), 1)
        val list = listOf(a("1", "image"), a("2", "image"), a("3", "file"), a("4", "image"))
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4")), attachmentRuns(list, true).map { r -> r.map { it.id } })
        assertEquals(1, attachmentRuns(list, false).size)
        assertEquals(emptyList<List<RemoteAttachment>>(), attachmentRuns(emptyList(), true))
    }

    @Test
    fun cacheEvictsTheLeastRecentlyUsedAndSkipsOversizedEntries() {
        val cache = AttachmentByteCache(10)
        val keys = (1..3).map { AttachmentByteCache.Key("s", "a$it", "h") }
        cache[keys[0]] = ByteArray(4)
        cache[keys[1]] = ByteArray(4)
        assertNotNull(cache[keys[0]])
        cache[keys[2]] = ByteArray(4)
        assertNull(cache[keys[1]])
        assertNotNull(cache[keys[0]])
        cache[AttachmentByteCache.Key("s", "big", "h")] = ByteArray(11)
        assertNull(cache[AttachmentByteCache.Key("s", "big", "h")])
    }
}
