package de.joinnoah.pi.remote

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `session-export-v1.json` wire entries and the export download loop. */
class ExportContentTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-export-v1.json")!!.readText()).jsonObject
    private val sessionId = "live-session"
    private val exportId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val fixtureMeta =
        ExportMeta(exportId, "pi-session-20260101-120000.html", 2, "a".repeat(64))

    private fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    private fun kind(payload: JsonObject) = payload["data"]?.jsonObject?.text("kind")

    @Test
    fun validRequestsAreExactlyWhatTheBuilderProduces() {
        val get = payloads("wireValid").map { it.second }.filter { it.text("type") == "session.export.get" }
        assertTrue(get.isNotEmpty())
        for (payload in get) {
            val built =
                Wire.objectOf(
                    "type" to "session.export.get",
                    "requestId" to payload.text("requestId"),
                    *exportGetFields(payload.text("sessionId"), payload.text("exportId"), payload.long("offset")),
                )
            assertEquals(payload, built)
        }
    }

    @Test
    fun invalidRequestsCannotBeBuilt() {
        val invalid =
            payloads("wireInvalid").filter {
                it.second.text("type") == "session.export.get" &&
                    it.second.keys == setOf("type", "requestId", "sessionId", "exportId", "offset")
            }
        assertTrue(invalid.isNotEmpty())
        for ((name, payload) in invalid) {
            assertThrows(name, IllegalArgumentException::class.java) {
                exportGetFields(payload.text("sessionId"), payload.text("exportId"), payload.long("offset"))
            }
        }
    }

    @Test
    fun validResultsAreAcceptedAndInvalidOnesRejected() {
        for ((name, payload) in payloads("wireValid").filter { kind(it.second) != null }) {
            val data = payload.obj("data")
            if (data.text("kind") == "export") {
                assertEquals(name, 2L, validatedExportMeta(data, sessionId).totalBytes)
            } else {
                val chunk = validatedExportChunk(data, sessionId, fixtureMeta, data.long("offset"))
                assertEquals(name, data.long("offset"), chunk.offset)
            }
        }
        val invalid = payloads("wireInvalid").filter { kind(it.second) != null }
        assertTrue(invalid.isNotEmpty())
        for ((name, payload) in invalid) {
            val data = payload.obj("data")
            assertThrows(name, Exception::class.java) {
                if (data.text("kind") == "export") validatedExportMeta(data, sessionId)
                else validatedExportChunk(data, sessionId, fixtureMeta, data.long("offset"))
            }
        }
    }

    @Test
    fun resultsForAnotherSessionOrExportAreRejected() {
        val meta = payloads("wireValid").first { kind(it.second) == "export" }.second.obj("data")
        assertThrows(IllegalArgumentException::class.java) { validatedExportMeta(meta, "other") }
        val chunk = payloads("wireValid").first { it.first == "chunk" }.second.obj("data")
        assertThrows(IllegalArgumentException::class.java) { validatedExportChunk(chunk, "other", fixtureMeta, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            validatedExportChunk(chunk, sessionId, ExportMeta("BBBBBBBBBBBBBBBBBBBBBB", "x", 2, "a".repeat(64)), 0)
        }
        assertThrows(IllegalArgumentException::class.java) { validatedExportChunk(chunk, sessionId, fixtureMeta, 1) }
    }

    // ---- download loop -------------------------------------------------------------------

    private val content = ByteArray(EXPORT_CHUNK_BYTES * 2 + 10) { (it * 31).toByte() }

    private fun meta(bytes: ByteArray = content, sha: String = AttachmentImportRules.sha256(bytes)) =
        Wire.objectOf(
            "kind" to "export",
            "sessionId" to sessionId,
            "exportId" to exportId,
            "fileName" to "pi-session-20260101-120000.html",
            "mimeType" to "text/html",
            "totalBytes" to bytes.size,
            "sha256" to sha,
        )

    private fun chunk(
        offset: Long,
        bytes: ByteArray = content,
        sha: String = AttachmentImportRules.sha256(bytes),
        length: Int = minOf(EXPORT_CHUNK_BYTES.toLong(), bytes.size - offset).toInt(),
    ) =
        Wire.objectOf(
            "kind" to "export.content",
            "sessionId" to sessionId,
            "exportId" to exportId,
            "offset" to offset,
            "totalBytes" to bytes.size,
            "sha256" to sha,
            "data" to Wire.encode(bytes.copyOfRange(offset.toInt(), offset.toInt() + length)),
        )

    private fun failure(block: suspend () -> Unit): ExportFailure =
        try {
            runBlocking { block() }
            fail("expected an export failure")
            error("unreachable")
        } catch (e: ExportException) {
            e.failure
        }

    @Test
    fun assemblesChunksInOrder() {
        val offsets = mutableListOf<Long>()
        val result =
            runBlocking {
                downloadExport(sessionId, { meta() }, { _, offset -> offsets.add(offset); chunk(offset) })
            }
        assertArrayEquals(content, result.bytes)
        assertEquals("pi-session-20260101-120000.html", result.fileName)
        assertEquals(listOf(0L, EXPORT_CHUNK_BYTES.toLong(), EXPORT_CHUNK_BYTES * 2L), offsets)
    }

    @Test
    fun advancesByTheDecodedLengthOfShorterChunks() {
        val offsets = mutableListOf<Long>()
        val result =
            runBlocking {
                downloadExport(sessionId, { meta() }, { _, offset ->
                    offsets.add(offset)
                    chunk(offset, length = minOf(20_000L, content.size - offset).toInt())
                })
            }
        assertArrayEquals(content, result.bytes)
        assertEquals(0L, offsets.first())
        assertEquals(20_000L, offsets[1])
    }

    @Test
    fun hashMismatchFails() {
        val wrong = "b".repeat(64)
        assertEquals(
            ExportFailure.HASH_MISMATCH,
            failure {
                downloadExport(sessionId, { meta(sha = wrong) }, { _, offset -> chunk(offset, sha = wrong) })
            },
        )
    }

    @Test
    fun offsetMismatchAndEmptyChunksAreProtocolErrors() {
        assertEquals(
            ExportFailure.PROTOCOL,
            // The second read is answered for offset 0 again.
            failure { downloadExport(sessionId, { meta() }, { _, _ -> chunk(0) }) },
        )
        assertEquals(
            ExportFailure.PROTOCOL,
            failure { downloadExport(sessionId, { meta() }, { _, offset -> chunk(offset, length = 0) }) },
        )
    }

    @Test
    fun oversizeTotalIsRejected() {
        val huge = meta().let { JsonObject(it + ("totalBytes" to JsonPrimitive(MAX_EXPORT_BYTES + 1))) }
        assertEquals(ExportFailure.PROTOCOL, failure { downloadExport(sessionId, { huge }, { _, o -> chunk(o) }) })
    }

    @Test
    fun restartsOnceWhenTheExportVanishesMidDownload() {
        var starts = 0
        var reads = 0
        val result =
            runBlocking {
                downloadExport(sessionId, { starts++; meta() }, { _, offset ->
                    reads++
                    if (starts == 1 && offset > 0) throw RemoteRequestException("not_found")
                    chunk(offset)
                })
            }
        assertEquals(2, starts)
        assertArrayEquals(content, result.bytes)
        assertEquals(2 + 3, reads)
    }

    @Test
    fun secondNotFoundFailsWithoutAThirdStart() {
        var starts = 0
        assertEquals(
            ExportFailure.NOT_FOUND,
            failure {
                downloadExport(sessionId, { starts++; meta() }, { _, offset ->
                    if (offset > 0) throw RemoteRequestException("not_found")
                    chunk(offset)
                })
            },
        )
        assertEquals(2, starts)
    }

    @Test
    fun hostErrorsMapToFailures() {
        fun code(code: String) =
            failure { downloadExport(sessionId, { throw RemoteRequestException(code) }, { _, o -> chunk(o) }) }
        assertEquals(ExportFailure.BUSY, code("busy"))
        assertEquals(ExportFailure.UNSUPPORTED, code("unsupported"))
        assertEquals(ExportFailure.OFFLINE, code("offline"))
        assertEquals(ExportFailure.TOO_LARGE, code("invalid_request"))
        assertEquals(ExportFailure.FAILED, code("internal"))
        assertEquals(
            ExportFailure.TIMED_OUT,
            failure { downloadExport(sessionId, { throw IllegalStateException("Request timed out") }, { _, o -> chunk(o) }) },
        )
        assertEquals(
            ExportFailure.FAILED,
            failure { downloadExport(sessionId, { meta() }, { _, _ -> throw RemoteRequestException("internal") }) },
        )
    }

    // ---- storage -------------------------------------------------------------------------

    @Test
    fun storageUsesSafeNamesAndKeepsOnlyTheLatestExport() {
        assertEquals("session.html", ExportStorage.safeName("../.."))
        assertEquals("session.html", ExportStorage.safeName(""))
        assertEquals("a-b.html", ExportStorage.safeName("a b.html"))
        assertEquals("pi-session-1.html", ExportStorage.safeName("/etc/pi-session-1.html"))
        val cache = Files.createTempDirectory("export-cache").toFile()
        try {
            val first = ExportStorage.write(cache, "pi-session-20260101-120000.html", "one".toByteArray())
            val second = ExportStorage.write(cache, "pi-session-20260102-120000.html", "two".toByteArray())
            assertFalse(first.exists())
            assertEquals("two", second.readText())
            assertEquals(listOf(second.name), second.parentFile!!.list()!!.toList())
        } finally {
            cache.deleteRecursively()
        }
    }
}
