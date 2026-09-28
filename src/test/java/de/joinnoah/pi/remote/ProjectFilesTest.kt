package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectFilesTest {
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val version = "RmlsZVZlcnNpb25BYmMxMg"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun data(name: String): JsonObject =
        entries("valid").single { it.text("name") == name }.obj("payload").obj("data")

    /** Parses a fixture result as the request that matches it would. */
    private fun parse(data: JsonObject): Any =
        when (data.text("kind")) {
            "files.list" -> parseFilesList(data, sessionId, data.text("path"))
            else -> parseFilesRead(data, sessionId, data.text("path"), data.long("offset"), null)
        }

    @Test
    fun parsesEveryValidFixtureResult() {
        val results =
            entries("valid")
                .map { it.obj("payload") }
                .filter { it.text("type") == "result" && it.flag("ok") }
                .map { it.obj("data") }
                .filter { it.text("kind").startsWith("files.") }
        assertEquals(11, results.size)
        results.forEach(::parse)

        val root = parseFilesList(data("list-root"), sessionId, "")
        assertEquals(
            listOf(
                FileEntry(".github", FileEntryType.DIR),
                FileEntry("src", FileEntryType.DIR),
                FileEntry("vendor", FileEntryType.SUBMODULE),
                FileEntry("latest", FileEntryType.SYMLINK),
                FileEntry("README.md", FileEntryType.FILE, 2048),
            ),
            root.entries,
        )
        assertEquals("README.md", root.nextAfter)
        assertFalse(root.truncated)
        assertTrue(parseFilesList(data("list-last-page-truncated"), sessionId, "src/app").truncated)
        assertEquals(FilesUnavailable.GIT_UNAVAILABLE, parseFilesList(data("list-git-unavailable"), sessionId, "").unavailable)
        assertEquals(FilesUnavailable.NOT_A_REPOSITORY, parseFilesList(data("list-not-a-repository"), sessionId, "").unavailable)

        val text = parseFilesRead(data("read-text"), sessionId, "src/app/main.ts", 0, null)
        assertEquals(FileChunk("src/app/main.ts", version, 13, 0, "hello\nworld\n\n", binary = false), text)
        assertEquals(9L, parseFilesRead(data("read-text-first-of-two-pages"), sessionId, "src/app/main.ts", 0, null).nextOffset)
        val last = parseFilesRead(data("read-text-last-page"), sessionId, "src/app/main.ts", 299990, version)
        assertNull(last.nextOffset)
        assertTrue(parseFilesRead(data("read-binary"), sessionId, "assets/logo.png", 0, null).binary)
        assertTrue(parseFilesRead(data("read-too-large"), sessionId, "data/dump.sql", 0, null).tooLarge)
    }

    @Test
    fun rejectsEveryInvalidFixtureResult() {
        var checked = 0
        for (entry in entries("invalid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            checked++
            assertTrue(entry.text("name"), runCatching { parse(payload.obj("data")) }.isFailure)
        }
        assertEquals(14, checked)
    }

    @Test
    fun rejectsResultsForAnotherRequest() {
        val list = data("list-root")
        assertThrows(IllegalArgumentException::class.java) { parseFilesList(list, "other", "") }
        assertThrows(IllegalArgumentException::class.java) { parseFilesList(list, sessionId, "src") }
        val read = data("read-text")
        assertThrows(IllegalArgumentException::class.java) { parseFilesRead(read, sessionId, "other.ts", 0, null) }
        assertThrows(IllegalArgumentException::class.java) { parseFilesRead(read, sessionId, "src/app/main.ts", 9, null) }
        // A later page must carry the first page's version.
        assertThrows(IllegalArgumentException::class.java) {
            parseFilesRead(read, sessionId, "src/app/main.ts", 0, "T3RoZXJWZXJzaW9uQWJjMQ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFilesList(JsonObject(list + ("extra" to JsonPrimitive(1))), sessionId, "")
        }
    }

    @Test
    fun rejectsOverlongContent() {
        // Quotes are escaped on the wire, so the JSON-encoded length decides, not the character count.
        val quotes = "\"".repeat(100 * 1024)
        val data = JsonObject(data("read-text") + mapOf("content" to JsonPrimitive(quotes), "size" to JsonPrimitive(quotes.length)))
        assertThrows(IllegalArgumentException::class.java) { parseFilesRead(data, sessionId, "src/app/main.ts", 0, null) }
        val fits = JsonObject(data + ("content" to JsonPrimitive("a".repeat(100 * 1024))))
        assertEquals(100 * 1024, parseFilesRead(fits, sessionId, "src/app/main.ts", 0, null).content.length)
    }

    @Test
    fun linesDropTheFinalLineEndAndCarriageReturns() {
        assertEquals(listOf("hello", "world", ""), fileLines("hello\nworld\n\n"))
        assertEquals(listOf("a", "b"), fileLines("a\r\nb"))
        assertTrue(fileLines("").isEmpty())
        assertEquals(listOf(""), fileLines("\n"))
    }

    @Test
    fun paths() {
        assertEquals("", parentPath("src"))
        assertEquals("src/app", parentPath("src/app/main.ts"))
        assertEquals("src", childPath("", "src"))
        assertEquals("src/app", childPath("src", "app"))
    }

    @Test
    fun lineTapsStartAndEndARange() {
        val start = null.tap(5)
        assertEquals(LineSelection(5), start)
        assertEquals(5..5, start.first..start.last)
        val range = start.tap(3)
        assertEquals(3, range.first)
        assertEquals(5, range.last)
        // A third tap starts over.
        assertEquals(LineSelection(9), range.tap(9))
    }

    // ---- Quote --------------------------------------------------------------------------

    @Test
    fun quoteIsAFencedBlockWithLanguageTag() {
        assertEquals(
            "From `a.kt`, lines 3–5:\n\n```kotlin\nval a = 1\nval b = 2\nval c = 3\n```",
            fileQuotePrompt("From `a.kt`, lines 3–5:", "Look at `a.kt` lines 3–5.", "val a = 1\nval b = 2\nval c = 3", SyntaxLanguage.KOTLIN),
        )
        assertEquals("Intro\n\n```\nplain\n```", fileQuotePrompt("Intro", "Ref", "plain", null))
    }

    @Test
    fun fenceOutgrowsBackticksInTheContent() {
        assertEquals("```", codeFence("no ticks, one ` and two ``"))
        assertEquals("````", codeFence("```kotlin\nx\n```"))
        assertEquals("``````", codeFence("`````"))
        val prompt = fileQuotePrompt("Intro", "Ref", "```js\nx\n```", SyntaxLanguage.MARKDOWN)
        assertEquals("Intro\n\n````markdown\n```js\nx\n```\n````", prompt)
    }

    @Test
    fun quoteOverTheCapBecomesAReference() {
        val atCap = "a".repeat(MAX_FILE_QUOTE_BYTES)
        assertTrue(fileQuotePrompt("Intro", "Ref", atCap, null).startsWith("Intro"))
        assertEquals("Ref", fileQuotePrompt("Intro", "Ref", atCap + "a", null))
        // The cap counts UTF-8 bytes: 11000 three-byte characters are over 32 KiB.
        assertEquals("Ref", fileQuotePrompt("Intro", "Ref", "€".repeat(11_000), null))
    }
}
