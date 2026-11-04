package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionArtifactsTest {
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val artifactId = "Zk3_xY9aB-0qWe7RtYuI1o"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/session-artifacts-v1.json")!!.readText()).jsonObject
    }

    private fun payloads(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject.obj("payload") }

    private fun results(group: String, kind: String) =
        payloads(group).filter { it.optionalText("type") == "result" && it.obj("data").optionalText("kind") == kind }
            .map { it.obj("data") }

    private fun open(data: JsonObject, version: Int? = null) =
        parseFilesMedia(data, sessionId, "artifacts/$artifactId", { artifactPathMatches(it, artifactId, version) })

    @Test
    fun builtOpenCommandsEqualTheValidFixtureCommands() {
        val commands = payloads("valid").filter { it.text("type") == "session.artifacts.open" }
        assertEquals(2, commands.size)
        for (command in commands) {
            val expected = JsonObject(command - "type" - "requestId")
            val version = command["version"]?.jsonPrimitive?.int
            assertEquals(expected, Wire.objectOf(*artifactOpenFields(command.text("sessionId"), command.text("artifactId"), version)))
        }
        assertEquals(setOf("sessionId"), JsonObject(payloads("valid").first { it.text("type") == "session.artifacts.list" } - "type" - "requestId").keys)
    }

    @Test
    fun refusesEveryInvalidOpenCommandItCanBuild() {
        var checked = 0
        for (command in payloads("invalid").filter { it.optionalText("type") == "session.artifacts.open" }) {
            val keys = command.keys - "type" - "requestId"
            // Extra or missing keys cannot be produced by the builder; only the values can be wrong.
            if (!keys.all { it in setOf("sessionId", "artifactId", "version") } || "artifactId" !in keys) continue
            val primitive = command["version"]?.jsonPrimitive
            // A string or a fraction is not an Int, so the builder cannot be asked for it.
            if (primitive != null && (primitive.isString || primitive.longOrNull == null)) continue
            val version = primitive?.long?.toInt()
            checked++
            assertThrows(command.toString(), IllegalArgumentException::class.java) {
                artifactOpenFields(command.text("sessionId"), command.text("artifactId"), version)
            }
        }
        assertTrue("checked $checked", checked >= 6)
    }

    @Test
    fun parsesTheValidListResults() {
        val lists = results("valid", "artifacts.list")
        assertEquals(3, lists.size)
        val empty = parseArtifactsList(lists[0], sessionId)
        assertTrue(empty.artifacts.isEmpty() && !empty.truncated)
        val entries = parseArtifactsList(lists[1], sessionId)
        assertEquals(listOf(ArtifactType.HTML, ArtifactType.SVG, ArtifactType.MERMAID), entries.artifacts.map { it.type })
        val report = entries.artifacts.first()
        assertEquals(artifactId, report.id)
        assertEquals("Report", report.title)
        assertEquals(2, report.version)
        assertEquals("a".repeat(64), report.sha256)
        assertEquals(1234L, report.bytes)
        assertEquals(1_767_225_600_000L, report.createdAt)
        assertEquals(report.createdAt + 5 * 60_000, report.updatedAt)
        assertFalse(entries.truncated)
        assertTrue(parseArtifactsList(lists[2], sessionId).truncated)
    }

    @Test
    fun rejectsEveryInvalidListResult() {
        val invalid = results("invalid", "artifacts.list")
        assertTrue(invalid.size >= 20)
        for (data in invalid)
            assertThrows(data.toString().take(300), Exception::class.java) { parseArtifactsList(data, sessionId) }
    }

    @Test
    fun aListForAnotherSessionIsRejected() {
        val data = results("valid", "artifacts.list").first()
        assertThrows(IllegalArgumentException::class.java) { parseArtifactsList(data, "other-session") }
    }

    @Test
    fun parsesTheValidOpenResultsIncludingMermaid() {
        val starts = results("valid", "files.media")
        assertEquals(5, starts.size)
        val kinds = starts.mapNotNull { (open(it) as? MediaStart.Ready)?.meta?.mime }
        assertEquals(listOf(MediaMime.HTML, MediaMime.SVG, MediaMime.MERMAID), kinds)
        assertEquals(MediaMime.MERMAID.wire, "text/vnd.mermaid")
        assertEquals("mmd", MediaMime.MERMAID.extension)
        assertEquals(MAX_MEDIA_MERMAID_BYTES, MediaMime.MERMAID.byteLimit)
        val omitted = starts.mapNotNull { (open(it) as? MediaStart.Omitted)?.reason }
        assertEquals(listOf(MediaOmitted.TOO_LARGE, MediaOmitted.NOT_AN_IMAGE), omitted)
    }

    @Test
    fun aVersionAskedForMustBeTheVersionInThePath() {
        val html = results("valid", "files.media").first()
        assertTrue(open(html, 2) is MediaStart.Ready)
        assertThrows(IllegalArgumentException::class.java) { open(html, 1) }
    }

    @Test
    fun mermaidIsRefusedOutsideAnArtifactOpen() {
        val mermaid = results("valid", "files.media").first { it.optionalText("mimeType") == MediaMime.MERMAID.wire }
        assertThrows(IllegalArgumentException::class.java) { parseFilesMedia(mermaid, sessionId, mermaid.text("path")) }
    }

    @Test
    fun rejectsEveryInvalidOpenResult() {
        val invalid = results("invalid", "files.media")
        assertTrue(invalid.size >= 6)
        for (data in invalid) assertThrows(data.toString(), Exception::class.java) { open(data) }
    }

    @Test
    fun theLogicalPathMustNameTheArtifact() {
        assertTrue(artifactPathMatches("artifacts/$artifactId/3.mmd", artifactId, null))
        assertFalse(artifactPathMatches("artifacts/$artifactId/03.html", artifactId, null))
        assertFalse(artifactPathMatches("artifacts/$artifactId/0.html", artifactId, null))
        assertFalse(artifactPathMatches("artifacts/$artifactId/1.pdf", artifactId, null))
        assertFalse(artifactPathMatches("artifacts/Aa1_Bb2-Cc3Dd4Ee5Ff6Gg/1.html", artifactId, null))
        assertFalse(artifactPathMatches("other/$artifactId/1.html", artifactId, null))
        assertFalse(artifactPathMatches("artifacts/$artifactId/1.html/x", artifactId, null))
    }

    @Test
    fun theGalleryIsOfferedOnlyWithTheCapability() {
        val state = RemoteState(connected = true, selection = RemoteSelection(sessionId = "s"), capabilities = setOf(ARTIFACTS_CAPABILITY))
        assertTrue(canShowArtifacts(state))
        assertFalse(canShowArtifacts(state.copy(capabilities = emptySet())))
        assertFalse(canShowArtifacts(state.copy(unavailableCapabilities = setOf(ARTIFACTS_CAPABILITY))))
    }
}
