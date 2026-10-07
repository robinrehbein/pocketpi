package de.joinnoah.pi.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectMediaTest {
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val aSha = "a".repeat(64)

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-media-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun meta(data: JsonObject) =
        MediaMeta(
            "build/shot.png", data.text("mediaId"), MediaMime.PNG, data.long("totalBytes"),
            data.text("sha256").takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: aSha,
        )

    @Test
    fun builtCommandsEqualTheValidFixtureCommands() {
        val commands = entries("valid").map { it.obj("payload") }.filter { it.text("type").startsWith("session.files.media") }
        assertEquals(6, commands.size)
        for (command in commands) {
            val expected = JsonObject(command - "type" - "requestId")
            val built =
                if (command.text("type") == "session.files.media") mediaFields(command.text("sessionId"), command.text("path"))
                else mediaGetFields(command.text("sessionId"), command.text("mediaId"), command.long("offset"))
            assertEquals(expected, Wire.objectOf(*built))
        }
    }

    @Test
    fun refusesEveryInvalidFixtureCommandItCanBuild() {
        val commands = entries("invalid").map { it.obj("payload") }.filter { it.text("type").startsWith("session.files.media") }
        var checked = 0
        for (command in commands) {
            // Extra or missing keys cannot be produced by the builders; only the values can be wrong.
            val keys = command.keys - "type" - "requestId"
            val build: () -> Unit =
                if (command.text("type") == "session.files.media") {
                    if (keys != setOf("sessionId", "path")) continue
                    { mediaFields(command.text("sessionId"), command.text("path")) }
                } else {
                    if (keys != setOf("sessionId", "mediaId", "offset")) continue
                    { mediaGetFields(command.text("sessionId"), command.text("mediaId"), command.long("offset")) }
                }
            checked++
            assertThrows(command.toString(), Exception::class.java) { build() }
        }
        assertTrue(checked >= 15)
    }

    @Test
    fun parsesValidResultsAndRejectsInvalidOnes() {
        val valid = entries("valid").map { it.obj("payload") }.filter { it.optionalText("type") == "result" && it.flag("ok") }
            .map { it.obj("data") }
        val starts = valid.filter { it.optionalText("kind") == "files.media" }
        assertEquals(5, starts.size)
        starts.forEach { parseFilesMedia(it, sessionId, it.text("path")) }
        val svg = parseFilesMedia(starts[1], sessionId, "docs/logo.svg") as MediaStart.Ready
        assertEquals(MediaMime.SVG, svg.meta.mime)
        assertEquals(MediaOmitted.TOO_LARGE, (parseFilesMedia(starts[3], sessionId, "build/shot.png") as MediaStart.Omitted).reason)
        // An absolute request is answered with the relative tail.
        parseFilesMedia(starts[0], sessionId, "/Users/robin/project/build/shot.png")
        assertThrows(IllegalArgumentException::class.java) { parseFilesMedia(starts[0], sessionId, "/Users/robin/other.png") }
        assertThrows(IllegalArgumentException::class.java) { parseFilesMedia(starts[0], sessionId, "build/other.png") }
        assertThrows(IllegalArgumentException::class.java) { parseFilesMedia(starts[0], "other-session", "build/shot.png") }

        val chunks = valid.filter { it.optionalText("kind") == "files.media.content" }
        assertEquals(3, chunks.size)
        for (chunk in chunks) parseMediaChunk(chunk, sessionId, meta(chunk).let { MediaMeta(it.path, it.mediaId, it.mime, it.totalBytes, aSha) }, chunk.long("offset"))

        val invalid = entries("invalid").map { it.obj("payload") }.filter { it.optionalText("type") == "result" }.map { it.obj("data") }
        var checked = 0
        for (data in invalid) {
            checked++
            val bad = {
                if (data.text("kind") == "files.media") parseFilesMedia(data, sessionId, data.text("path"))
                else parseMediaChunk(data, sessionId, meta(data), data.long("offset"))
            }
            assertThrows(data.toString().take(300), Exception::class.java) { bad() }
        }
        assertEquals(23, checked)
    }

    // ---- download ---------------------------------------------------------------------------

    private val content = ByteArray(MEDIA_CHUNK_BYTES * 2 + 17) { (it * 31).toByte() }

    private fun sha(bytes: ByteArray) = AttachmentImportRules.sha256(bytes)

    private fun startResult(path: String = "build/shot.png", mediaId: String = "AAAAAAAAAAAAAAAAAAAAAA", digest: String = sha(content)) =
        Wire.objectOf(
            "kind" to "files.media", "sessionId" to sessionId, "path" to path, "mediaId" to mediaId,
            "mimeType" to "image/png", "totalBytes" to content.size, "sha256" to digest,
        )

    private fun chunkResult(mediaId: String, offset: Long, digest: String = sha(content)): JsonObject {
        val from = offset.toInt()
        val length = minOf(MEDIA_CHUNK_BYTES, content.size - from)
        return Wire.objectOf(
            "kind" to "files.media.content", "sessionId" to sessionId, "mediaId" to mediaId, "offset" to offset,
            "totalBytes" to content.size, "sha256" to digest, "data" to Wire.encode(content.copyOfRange(from, from + length)),
        )
    }

    @Test
    fun downloadsChunkByChunkAndChecksTheDigest() = runTest {
        val offsets = mutableListOf<Long>()
        val result =
            downloadMedia(sessionId, "build/shot.png", { startResult() }, { id, offset -> offsets += offset; chunkResult(id, offset) })
        result as MediaDownload.Ready
        assertArrayEquals(content, result.bytes)
        assertEquals(listOf(0L, MEDIA_CHUNK_BYTES.toLong(), MEDIA_CHUNK_BYTES * 2L), offsets)
    }

    @Test
    fun omittedResultsEndTheDownloadWithoutReads() = runTest {
        val omitted =
            Wire.objectOf("kind" to "files.media", "sessionId" to sessionId, "path" to "a.png", "omitted" to "not_an_image")
        val result = downloadMedia(sessionId, "a.png", { omitted }, { _, _ -> error("no read") })
        assertEquals(MediaOmitted.NOT_AN_IMAGE, (result as MediaDownload.Omitted).reason)
    }

    @Test
    fun aCopyThatExpiresMidwayRestartsOnceAndThenFails() = runTest {
        var starts = 0
        val ok =
            downloadMedia(
                sessionId, "build/shot.png",
                { startResult(mediaId = if (starts++ == 0) "AAAAAAAAAAAAAAAAAAAAAA" else "AAAAAAAAAAAAAAAAAAAAAQ") },
                { id, offset ->
                    if (id.endsWith("A") && offset > 0) throw RemoteRequestException("not_found")
                    chunkResult(id, offset)
                },
            )
        assertEquals(2, starts)
        assertArrayEquals(content, (ok as MediaDownload.Ready).bytes)

        starts = 0
        val error =
            runCatching {
                downloadMedia(sessionId, "build/shot.png", { starts++; startResult() }, { id, offset ->
                    if (offset > 0) throw RemoteRequestException("not_found")
                    chunkResult(id, offset)
                })
            }.exceptionOrNull() as MediaException
        assertEquals(2, starts)
        assertEquals(MediaFailure.FAILED, error.failure)
    }

    @Test
    fun failuresAreSortedIntoRetryableAndFinal() = runTest {
        suspend fun failure(code: String, atStart: Boolean): MediaFailure =
            (runCatching {
                    downloadMedia(
                        sessionId, "build/shot.png",
                        { if (atStart) throw RemoteRequestException(code) else startResult() },
                        { _, _ -> throw RemoteRequestException(code) },
                    )
                }.exceptionOrNull() as MediaException).failure
        for (code in listOf("invalid_path", "not_found", "forbidden")) assertEquals(code, MediaFailure.UNAVAILABLE, failure(code, true))
        for (code in listOf("busy", "offline", "internal")) {
            assertEquals(code, MediaFailure.FAILED, failure(code, true))
            assertEquals(code, MediaFailure.FAILED, failure(code, false))
        }
    }

    @Test
    fun aWrongDigestAChunkOutOfOrderOrAStartOfAnotherSessionIsRejected() = runTest {
        fun failure(block: suspend () -> Unit): MediaFailure {
            var failure: MediaFailure? = null
            try {
                kotlinx.coroutines.runBlocking { block() }
            } catch (e: MediaException) {
                failure = e.failure
            }
            return checkNotNull(failure)
        }
        // The digest of the copy disagrees with the bytes.
        val wrong = "0".repeat(64)
        assertEquals(
            MediaFailure.FAILED,
            failure { downloadMedia(sessionId, "build/shot.png", { startResult(digest = wrong) }, { id, o -> chunkResult(id, o, wrong) }) },
        )
        // A chunk answering another offset.
        assertEquals(
            MediaFailure.UNAVAILABLE,
            failure { downloadMedia(sessionId, "build/shot.png", { startResult() }, { id, _ -> chunkResult(id, MEDIA_CHUNK_BYTES.toLong()) }) },
        )
        // A start for another path.
        assertEquals(
            MediaFailure.UNAVAILABLE,
            failure { downloadMedia(sessionId, "build/shot.png", { startResult(path = "x.png") }, { id, o -> chunkResult(id, o) }) },
        )
    }

    @Test
    fun mediaPathsFollowTheHostParser() {
        for (path in listOf("a.png", "build/shot.png", "/Users/robin/p/shot.png", ".cache/mock.svg", ".env.png"))
            assertTrue(path, validMediaPath(path))
        for (path in listOf("", "/", "a//b.png", "a/./b.png", "a/../b.png", "build/", "a\\b.png", "a\nb.png", "a\u0000.png", "a/".repeat(2100) + "b.png"))
            assertFalse(path, validMediaPath(path))
    }
}
