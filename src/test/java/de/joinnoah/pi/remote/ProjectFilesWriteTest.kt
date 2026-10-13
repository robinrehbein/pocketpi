package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectFilesWriteTest {
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-write-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun data(name: String): JsonObject =
        entries("valid").single { it.text("name") == name }.obj("payload").obj("data")

    @Test
    fun parsesEveryValidFixtureResult() {
        val results =
            entries("valid")
                .map { it.obj("payload") }
                .filter { it.text("type") == "result" && it.flag("ok") }
                .map { it.obj("data") }
                .filter { it.text("kind") == "files.written" }
        assertEquals(2, results.size)
        results.forEach { parseFilesWritten(it, sessionId, it.text("path")) }
        assertEquals(
            FileWritten("src/app/main.ts", "TmV3VmVyc2lvblh5ejk4Nw", 12),
            parseFilesWritten(data("written"), sessionId, "src/app/main.ts"),
        )
        assertEquals(0L, parseFilesWritten(data("written-empty"), sessionId, "empty.txt").size)
    }

    @Test
    fun rejectsEveryInvalidFixtureResult() {
        var checked = 0
        for (entry in entries("invalid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            checked++
            val data = payload.obj("data")
            assertTrue(entry.text("name"), runCatching { parseFilesWritten(data, sessionId, data.text("path")) }.isFailure)
        }
        assertEquals(4, checked)
    }

    @Test
    fun rejectsResultsForAnotherRequest() {
        val written = data("written")
        assertThrows(IllegalArgumentException::class.java) { parseFilesWritten(written, "other", "src/app/main.ts") }
        assertThrows(IllegalArgumentException::class.java) { parseFilesWritten(written, sessionId, "other.ts") }
        assertThrows(IllegalArgumentException::class.java) {
            parseFilesWritten(JsonObject(written + ("kind" to JsonPrimitive("files.read"))), sessionId, "src/app/main.ts")
        }
    }

    @Test
    fun theFixtureAdvertisesTheWriteCapability() {
        val advertised = data("projects-files-write-capability").getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY), advertised)
    }

    @Test
    fun writingNeedsBothCapabilitiesAndASession() {
        val selected = RemoteState(selection = RemoteSelection("host", "project", sessionId))
        assertTrue(canWriteFiles(selected.copy(capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY))))
        assertFalse(canWriteFiles(selected.copy(capabilities = setOf(FILES_CAPABILITY))))
        assertFalse(canWriteFiles(selected.copy(capabilities = setOf(FILES_WRITE_CAPABILITY))))
        assertFalse(
            canWriteFiles(
                selected.copy(
                    capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
                    unavailableCapabilities = setOf(FILES_WRITE_CAPABILITY),
                )
            )
        )
        assertFalse(canWriteFiles(RemoteState(capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY))))
    }

    @Test
    fun onlyAWholeTextFileIsEditable() {
        val text = OpenFile("a.kt", loading = false, version = "RmlsZVZlcnNpb25BYmMxMg", size = 2, content = "x\n")
        fun state(file: OpenFile, write: Boolean = true) =
            RemoteState(
                selection = RemoteSelection("host", "project", sessionId),
                capabilities = if (write) setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY) else setOf(FILES_CAPABILITY),
                files = FilesState(sessionId, loading = false, file = file),
            )
        assertTrue(canEditFile(state(text)))
        assertFalse(canEditFile(state(text, write = false)))
        assertFalse(canEditFile(state(text.copy(loading = true))))
        assertFalse(canEditFile(state(text.copy(failure = FilesFailure.BUSY))))
        assertFalse(canEditFile(state(text.copy(binary = true, content = ""))))
        assertFalse(canEditFile(state(text.copy(tooLarge = true, content = ""))))
        assertFalse(canEditFile(state(text.copy(nextOffset = 2))))
    }

    @Test
    fun autoExecutedConfigIsNotEditable() {
        for (path in listOf(".pi/settings.json", ".claude/settings.json", ".husky/pre-commit", ".githooks/pre-push",
            ".idea/workspace.xml", "packages/x/.vscode/tasks.json", ".Claude/settings.json", ".VSCODE/launch.json",
            ".envrc", "apps/web/.envrc", ".mcp.json", "packages/x/.MCP.json", ".cursor/rules/a.mdc", ".lefthook/pre-commit/a.sh",
            "lefthook.yml", "lefthook.yaml", ".lefthook.yml", ".lefthook.yaml", ".pre-commit-config.yaml",
            ".gemini/settings.json", ".codex/config.toml", ".mise/tasks/build", "mise.toml", "apps/web/.mise.toml",
            "mise.local.toml", ".mise.local.toml", ".config/mise/config.toml", ".huſky/pre-commit", ".ＣＬＡＵＤＥ/settings.json"))
            assertTrue(path, writeDeniedPath(path))
        for (path in listOf(".github/workflows/ci.yml", "notes.pi", "docs/.envrc.md", "src/claude/a.kt", "README.md", "mcp.json", "docs/.mcp.json.md",
            ".config/other/mise.md", "mise/notes.md", "docs/mise.toml.md"))
            assertFalse(path, writeDeniedPath(path))
        val file = OpenFile(".claude/settings.json", loading = false, version = "RmlsZVZlcnNpb25BYmMxMg", size = 2, content = "{}")
        val state =
            RemoteState(
                selection = RemoteSelection("host", "project", sessionId),
                capabilities = setOf(FILES_CAPABILITY, FILES_WRITE_CAPABILITY),
                files = FilesState(sessionId, loading = false, file = file),
            )
        assertFalse(canEditFile(state))
        assertTrue(canEditFile(state.copy(files = state.files?.copy(file = file.copy(path = ".github/workflows/ci.yml")))))
    }

    // ---- Prompt ---------------------------------------------------------------------------

    @Test
    fun anUnchangedEditHasNoPrompt() {
        assertNull(editorPrompt("a.kt", "same\n", "same\n"))
    }

    @Test
    fun promptIsTheIntroAndADiffFence() {
        val prompt = editorPrompt("src/a.kt", "one\ntwo\n", "one\nthree\n") as EditorPrompt.Ready
        val intro = "Here is a suggested change to `src/a.kt`. Apply it, adapting it if needed, and say what you changed."
        assertEquals(intro, editorPromptIntro("src/a.kt"))
        assertTrue(prompt.text, prompt.text.startsWith("$intro\n\n```diff\n--- a/src/a.kt\n+++ b/src/a.kt\n"))
        assertTrue(prompt.text, prompt.text.endsWith("\n```"))
        assertFalse(prompt.text, prompt.text.endsWith("\n\n```"))
        val translated = editorPrompt("src/a.kt", "one\n", "two\n", intro = "Vorschlag:") as EditorPrompt.Ready
        assertTrue(translated.text.startsWith("Vorschlag:\n\n```diff\n"))
    }

    @Test
    fun fenceOutgrowsBackticksInTheDiff() {
        val prompt = editorPrompt("README.md", "```kotlin\nx\n```\n", "```kotlin\ny\n```\n") as EditorPrompt.Ready
        assertTrue(prompt.text, prompt.text.contains("\n\n````diff\n"))
        assertTrue(prompt.text, prompt.text.endsWith("\n````"))
    }

    @Test
    fun aDiffOverTheQuoteCapIsRefused() {
        val big = "a".repeat(MAX_FILE_QUOTE_BYTES) + "\n"
        assertEquals(EditorPrompt.TooLarge, editorPrompt("a.txt", "", big))
        assertTrue(editorPrompt("a.txt", "", "small\n") is EditorPrompt.Ready)
    }
}
