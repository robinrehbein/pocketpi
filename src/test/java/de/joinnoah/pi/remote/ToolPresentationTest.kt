package de.joinnoah.pi.remote

import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolPresentationTest {
    private val fixtures: Map<String, JsonObject> by lazy {
        Json.parseToJsonElement(javaClass.getResource("/insights-v1.json")!!.readText())
            .jsonObject
            .getValue("valid")
            .jsonArray
            .associate { it.jsonObject.text("name") to it.jsonObject.obj("payload") }
    }

    private fun activity(
        id: String,
        name: String?,
        arguments: String?,
        state: String = "complete",
        details: ToolDetails? = null,
    ) =
        ConversationItem.Activity(
            id,
            id,
            name,
            arguments,
            null,
            state,
            false,
            argumentsTruncated = arguments != null && parsedToolArguments(arguments) == null,
            details = details,
        )

    private fun session(
        id: String,
        title: String,
        parent: String? = "parent",
        availability: SessionAvailability = SessionAvailability.RUNNING,
        updatedAt: Long? = null,
        preview: String? = null,
    ) = SessionListItem(id, title, parent, 0, false, availability, preview, updatedAt, false, false, false)

    private fun subagent(id: String, vararg agents: AgentProgress) =
        ConversationItem.Subagent(id, id, "parallel", agents.toList(), false, null, false)

    @Test
    fun editArgumentsAcceptsEveryPiShape() {
        val array = """{"path":"a.kt","edits":[{"oldText":"a","newText":"b"},{"oldText":"c","newText":"d"}]}"""
        assertEquals(
            EditArguments("a.kt", listOf(EditChange("a", "b"), EditChange("c", "d"))),
            editArguments(array),
        )
        val asString =
            buildJsonObject {
                    put("file_path", "b.kt")
                    put("edits", """[{"oldText":"x","newText":"y"}]""")
                }
                .toString()
        assertEquals(EditArguments("b.kt", listOf(EditChange("x", "y"))), editArguments(asString))
        val single = """{"path":"c.kt","edits":{"oldText":"1","newText":"2"}}"""
        assertEquals(EditArguments("c.kt", listOf(EditChange("1", "2"))), editArguments(single))
        val legacy = """{"path":"d.kt","oldText":"old","newText":"new"}"""
        assertEquals(EditArguments("d.kt", listOf(EditChange("old", "new"))), editArguments(legacy))
        val snake = """{"file_path":"e.kt","old_string":"o","new_string":"n"}"""
        assertEquals(EditArguments("e.kt", listOf(EditChange("o", "n"))), editArguments(snake))
        val wrongTypes = """{"path":"f.kt","edits":[{"oldText":1,"newText":"b"},"x",{"oldText":{},"newText":[]}],"oldText":null}"""
        assertEquals(EditArguments("f.kt", emptyList()), editArguments(wrongTypes))
        assertNull(editArguments("""{"path":"""))
        assertNull(editArguments(null))
        assertNull(editArguments("""{"edits":"not json"}"""))
    }

    @Test
    fun toolPathFallsBackToTheTruncatedJsonPrefix() {
        assertEquals("src/a.kt", toolPath(activity("a", "read", """{"path":"src/a.kt"}""")))
        assertEquals("src/b.kt", toolPath(activity("b", "edit", """{"file_path":"src/b.kt","edits":[]}""")))
        assertEquals(
            "src/c.kt",
            toolPath(activity("c", "write", """{"path":"src/c.kt","content":"unterminated""")),
        )
        assertEquals(
            "dir/\"quoted\"/ü.kt",
            toolPath(activity("d", "write", """{ "file_path" : "dir/\"quoted\"/ü.kt", "content":"x""")),
        )
        assertTrue(activity("d", "write", """{"path":"x","content":"cut""").argumentsTruncated)
        assertNull(toolPath(activity("e", "write", """{"content":"x","path":"late.kt""")))
        assertNull(toolPath(activity("f", "write", null)))
    }

    @Test
    fun patchDiffReadsTheEditFixtureWithLineNumbers() {
        val message = fixtures.getValue("message-edit-details").obj("message")
        val details = toolDetails(message)!!
        assertEquals(2, details.firstChangedLine)
        assertFalse(details.truncated)
        val lines = patchDiff(details.patch)
        assertEquals(DiffKind.HUNK, lines.first().kind)
        assertEquals("@@ -1,6 +1,6 @@", lines.first().text)
        val body = lines.drop(1)
        assertEquals(
            listOf(
                DiffKind.CONTEXT,
                DiffKind.REMOVED,
                DiffKind.ADDED,
                DiffKind.CONTEXT,
                DiffKind.CONTEXT,
                DiffKind.CONTEXT,
                DiffKind.CONTEXT,
            ),
            body.map { it.kind },
        )
        assertEquals(DiffLine(DiffKind.CONTEXT, "package a", 1, 1), body[0])
        assertEquals(DiffLine(DiffKind.REMOVED, "const val GREETING = \"Hello\"", 2, null), body[1])
        assertEquals(DiffLine(DiffKind.ADDED, "const val GREETING = \"Hello, pi\"", null, 2), body[2])
        assertEquals(DiffLine(DiffKind.CONTEXT, "", 3, 3), body[3])
        assertEquals(DiffLine(DiffKind.CONTEXT, "}", 6, 6), body[6])
    }

    @Test
    fun patchDiffToleratesTruncationHeadersAndNoNewlineMarkers() {
        val patch =
            "Index: x\n====\n--- a\n+++ a\n@@ -10,8 +10,9 @@\n ctx\n-old\n\\ No newline at end of file\n+new\n+more\n ctx2\n partial"
        val lines = patchDiff(patch)
        assertEquals("@@ -10,8 +10,9 @@", lines.first().text)
        assertEquals(
            listOf(
                DiffLine(DiffKind.CONTEXT, "ctx", 10, 10),
                DiffLine(DiffKind.REMOVED, "old", 11, null),
                DiffLine(DiffKind.ADDED, "new", null, 11),
                DiffLine(DiffKind.ADDED, "more", null, 12),
                DiffLine(DiffKind.CONTEXT, "ctx2", 12, 13),
                DiffLine(DiffKind.CONTEXT, "partial", 13, 14),
            ),
            lines.drop(1),
        )
        val twoHunks = "@@ -1 +1 @@\n-a\n+b\ntrailing garbage\n@@ -5,0 +6,1 @@\n+c\n"
        assertEquals(
            listOf(DiffKind.HUNK, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.HUNK, DiffKind.ADDED),
            patchDiff(twoHunks).map { it.kind },
        )
        assertTrue(patchDiff("no hunks here").isEmpty())
    }

    @Test
    fun changeDiffUsesLineLcsAndFallsBackForHugeChanges() {
        val lines =
            changeDiff(
                listOf(
                    EditChange("a\nb\nc\nd", "a\nB\nc\nd\ne"),
                    EditChange("", "new"),
                )
            )
        assertEquals(
            listOf(
                DiffLine(DiffKind.CONTEXT, "a", null, null),
                DiffLine(DiffKind.REMOVED, "b", null, null),
                DiffLine(DiffKind.ADDED, "B", null, null),
                DiffLine(DiffKind.CONTEXT, "c", null, null),
                DiffLine(DiffKind.CONTEXT, "d", null, null),
                DiffLine(DiffKind.ADDED, "e", null, null),
                DiffLine(DiffKind.HUNK, "", null, null),
                DiffLine(DiffKind.ADDED, "new", null, null),
            ),
            lines,
        )
        val old = (1..600).joinToString("\n") { "line $it" }
        val new = (1..600).joinToString("\n") { if (it == 300) "changed" else "line $it" }
        val huge = changeDiff(listOf(EditChange(old, new)))
        assertEquals(1200, huge.size)
        assertTrue(huge.take(600).all { it.kind == DiffKind.REMOVED })
        assertTrue(huge.drop(600).all { it.kind == DiffKind.ADDED })
    }

    @Test
    fun toolDiffPrefersDetailsAndFallsBackToArguments() {
        val arguments = """{"path":"a.kt","edits":[{"oldText":"x","newText":"y\nz"}]}"""
        val fromArgs = toolDiff(activity("a", "edit", arguments))!!
        assertFalse(fromArgs.fromDetails)
        assertFalse(fromArgs.truncated)
        assertEquals(2, fromArgs.added)
        assertEquals(1, fromArgs.removed)
        val details = ToolDetails("--- a.kt\n+++ a.kt\n@@ -1 +1 @@\n-x\n+y\n", 1, true)
        val fromDetails = toolDiff(activity("b", "edit", arguments, details = details))!!
        assertTrue(fromDetails.fromDetails)
        assertTrue(fromDetails.truncated)
        assertEquals(1, fromDetails.added)
        assertEquals(1, fromDetails.removed)
        assertSame(fromDetails, toolDiff(activity("c", "edit", arguments, details = details)))
        val unusable = ToolDetails("--- a.kt\n+++ a.kt\n", null, false)
        assertFalse(toolDiff(activity("d", "edit", arguments, details = unusable))!!.fromDetails)
        assertNull(toolDiff(activity("e", "write", arguments)))
        assertNull(toolDiff(activity("f", "edit", """{"path":"a.kt","edits":[{"oldText":"x""")))
        assertNull(toolDiff(activity("g", "edit", """{"path":"a.kt"}""")))
    }

    @Test
    fun touchedFilesGroupsChangedAndReadPaths() {
        val items =
            listOf(
                activity("1", "read", """{"path":"a.kt"}"""),
                activity("2", "read", """{"path":"b.kt"}"""),
                activity("3", "edit", """{"path":"a.kt","edits":[]}"""),
                activity("4", "write", """{"path":"c.kt","content":"cut"""),
                activity("5", "read", """{"path":"b.kt"}"""),
                activity("6", "bash", """{"command":"ls"}"""),
                activity("7", "edit", """{"path":"a.kt","edits":[]}"""),
                ConversationItem.Bubble("8", "8", "user", null, "hi", null, false, null),
            )
        val touched = touchedFiles(items)
        assertEquals(listOf(TouchedFile("a.kt", 2, "7"), TouchedFile("c.kt", 1, "4")), touched.changed)
        assertEquals(listOf(TouchedFile("b.kt", 2, "5")), touched.read)
        assertEquals(3, touched.total)
    }

    @Test
    fun timelineMarkersClassifyAndPositionItems() {
        val items =
            listOf(
                ConversationItem.Bubble("u", "u", "user", null, "hi", null, false, null),
                activity("q", "questionnaire", "{}"),
                activity("e", "edit", "{}"),
                activity("w", "write", "{}", state = "error"),
                activity("p", "submit_plan", "{}"),
                subagent("s", AgentProgress("x", "failed", null, null)),
                ConversationItem.Bubble("b", "b", "assistant", null, "oops", null, false, null, error = true),
                ConversationItem.Thinking("t", "t", "", false),
                activity("r", "read", "{}"),
            )
        val markers = timelineMarkers(items)
        assertEquals(
            listOf(
                TimelineMarker("q", TimelineMarkerKind.QUESTION, 1f / 8),
                TimelineMarker("e", TimelineMarkerKind.EDIT, 2f / 8),
                TimelineMarker("w", TimelineMarkerKind.ERROR, 3f / 8),
                TimelineMarker("p", TimelineMarkerKind.PLAN, 4f / 8),
                TimelineMarker("s", TimelineMarkerKind.ERROR, 5f / 8),
                TimelineMarker("b", TimelineMarkerKind.ERROR, 6f / 8),
            ),
            markers,
        )
        assertEquals(listOf("w", "s", "b"), onlyErrors(items).map { it.id })
        assertEquals(0f, timelineMarkers(listOf(activity("x", "edit", "{}"))).single().position)
        val many = (0 until 250).map { activity("m$it", "edit", "{}") }
        val capped = timelineMarkers(many)
        assertEquals(200, capped.size)
        assertEquals("m50", capped.first().itemId)
        assertEquals(1f, capped.last().position)
    }

    @Test
    fun resolveSubagentChildPrefersIdThenTaskTitle() {
        val byId = subagent("s", AgentProgress("explorer", "running", null, null, sessionId = "child-9"))
        // An id the session list does not know yet resolves to nothing, so the caller refreshes.
        assertEquals(ChildResolution.None, resolveSubagentChild(byId, 0, "parent", emptyList()))
        assertEquals(
            ChildResolution.Exact("child-9"),
            resolveSubagentChild(byId, 0, "parent", listOf(session("child-9", "explorer: child"))),
        )
        val item =
            subagent(
                "s",
                AgentProgress("explorer", "succeeded", null, null, task = "Find the wire parser in protocol"),
                AgentProgress("reviewer", "running", null, null),
            )
        val sessions =
            listOf(
                session("c1", "explorer: Find the wire parser in protocol", availability = SessionAvailability.IDLE, updatedAt = 1),
                session("c2", "explorer: something else", availability = SessionAvailability.IDLE, updatedAt = 5),
                session("c3", "reviewer: check diff", updatedAt = 2),
                session("c4", "reviewer: other diff", updatedAt = 3),
                session("other", "reviewer: unrelated", parent = "elsewhere"),
            )
        assertEquals(ChildResolution.Exact("c1"), resolveSubagentChild(item, 0, "parent", sessions))
        val ambiguous = resolveSubagentChild(item, 1, "parent", sessions) as ChildResolution.Candidates
        assertEquals(listOf("c4", "c3", "c2", "c1"), ambiguous.sessions.map { it.id })
        assertEquals(ChildResolution.None, resolveSubagentChild(item, 1, "nobody", sessions))
        assertEquals(ChildResolution.None, resolveSubagentChild(item, 5, "parent", sessions))
    }

    @Test
    fun subagentStripMergesChildrenAndProgress() {
        val items =
            listOf(
                subagent("old", AgentProgress("ancient", "running", null, null, sessionId = "gone-0")),
                subagent("s1", AgentProgress("done", "succeeded", null, null)),
                subagent("s2", AgentProgress("finished", "succeeded", null, null)),
                subagent(
                    "s3",
                    AgentProgress("explorer", "running", "preview", "read", sessionId = "c1"),
                    AgentProgress("planner", "queued", null, null, task = "Plan it", sessionId = "missing-1"),
                    AgentProgress("reviewer", "running", "looking", null, task = "Review the diff"),
                ),
            )
        val sessions =
            listOf(
                session("c1", "explorer child", preview = "child preview"),
                session("c2", "waiting child", availability = SessionAvailability.WAITING, preview = "asks"),
                session("c3", "idle child", availability = SessionAvailability.IDLE),
                session("x", "foreign", parent = "elsewhere"),
            )
        val strip = subagentStrip(items, sessions, "parent")
        assertEquals(
            listOf(
                SubagentStripEntry("c1", "explorer", "explorer child", "running", "read", true),
                SubagentStripEntry("c2", null, "waiting child", "running", "asks", true),
                SubagentStripEntry(null, "reviewer", "Review the diff", "running", "looking", false),
                SubagentStripEntry("missing-1", "planner", "Plan it", "queued", null, false, openable = false),
            ),
            strip.entries,
        )
        assertEquals(3, strip.running)
        assertEquals(setOf("missing-1"), strip.missingSessionIds)
        assertEquals(1, strip.unmatchedRunning)
    }

    @Test
    fun subagentStripMatchesChildrenByTaskHeuristic() {
        val items = listOf(subagent("s", AgentProgress("reviewer", "running", null, "grep", task = "Review the diff")))
        val sessions = listOf(session("c", "reviewer: Review the diff"))
        val strip = subagentStrip(items, sessions, "parent")
        assertEquals(listOf(SubagentStripEntry("c", "reviewer", "reviewer: Review the diff", "running", "grep", true)), strip.entries)
        assertEquals(0, strip.unmatchedRunning)
        assertTrue(strip.missingSessionIds.isEmpty())
    }

    @Test
    fun sessionListItemsDropsMalformedRows() {
        val good = Wire.objectOf("id" to "a", "title" to "A", "status" to "running", "origin" to "rpc")
        val bad = Wire.objectOf("id" to "b", "title" to JsonObject(emptyMap()), "status" to "idle", "origin" to "rpc")
        val missing = Wire.objectOf("id" to "c")
        val items = sessionListItems(listOf(good, bad, missing), connected = true, loading = false)
        assertEquals(listOf("a"), items.map { it.id })
        assertEquals(SessionAvailability.RUNNING, items.single().availability)
    }

    @Test
    fun formatHelpersUseTheLocaleDecimalSeparator() {
        val en = Locale.ENGLISH
        val de = Locale.GERMAN
        assertEquals("0", formatTokenCount(0, en))
        assertEquals("950", formatTokenCount(950, en))
        assertEquals("1.2k", formatTokenCount(1_234, en))
        assertEquals("1,2k", formatTokenCount(1_234, de))
        assertEquals("1k", formatTokenCount(1_000, en))
        assertEquals("35k", formatTokenCount(34_567, en))
        assertEquals("1M", formatTokenCount(999_600, en))
        assertEquals("3.4M", formatTokenCount(3_400_000, en))
        assertEquals("3,4M", formatTokenCount(3_400_000, de))
        assertEquals("$0.012", formatCost(0.012, en))
        assertEquals("$0,012", formatCost(0.012, de))
        assertEquals("$0.0188", formatCost(0.01875, en))
        assertEquals("$0.00042", formatCost(0.00042, en))
        assertEquals("$0", formatCost(0.0, en))
        assertEquals("$1.25", formatCost(1.2489, en))
        assertEquals("$12,50", formatCost(12.5, de))
        assertEquals("$1.00", formatCost(0.9999, en))
    }
}
