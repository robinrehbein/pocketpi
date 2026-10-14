package de.joinnoah.pi.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInFilesTest {
    private fun activity(name: String, arguments: String, details: ToolDetails? = null) =
        ConversationItem.Activity("a", "a", name, arguments, null, "complete", false, details = details)

    @Test
    fun relativePathsStayAndPathsClimbingOutAreOutside() {
        assertEquals("src/Foo.kt", projectRelativePath("src/Foo.kt"))
        assertEquals("src/Foo.kt", projectRelativePath("./src//Foo.kt"))
        assertEquals("Foo.kt", projectRelativePath("src/../Foo.kt"))
        assertNull(projectRelativePath("../other/Foo.kt"))
        assertNull(projectRelativePath(""))
        assertNull(projectRelativePath("."))
    }

    @Test
    fun absolutePathsLoseTheSessionFolder() {
        val hints = SessionFolderHints(roots = listOf("/Users/robin/pocketpi"))
        assertEquals("src/Foo.kt", projectRelativePath("/Users/robin/pocketpi/src/Foo.kt", hints))
        // A sibling folder that merely shares the prefix is outside.
        assertNull(projectRelativePath("/Users/robin/pocketpi-old/src/Foo.kt", hints))
        assertNull(projectRelativePath("/etc/hosts", hints))
        assertNull(projectRelativePath("/Users/robin/pocketpi", hints))
    }

    @Test
    fun withoutAFolderPathTheProjectNameAndToolPathsPlaceAnAbsolutePath() {
        val named = SessionFolderHints(projectName = "pocketpi")
        assertEquals("src/main/Foo.kt", projectRelativePath("/Users/robin/pocketpi/src/main/Foo.kt", named))
        assertNull(projectRelativePath("/Users/robin/other/Foo.kt", named))
        val used = SessionFolderHints(toolPaths = setOf("src/Foo.kt"))
        assertEquals("src/Foo.kt", projectRelativePath("/Users/robin/x/src/Foo.kt", used))
        assertNull(projectRelativePath("/Users/robin/x/src/Bar.kt", used))
    }

    @Test
    fun anAbsoluteAndARelativeToolPathOfOneFileGiveAwayTheFolder() {
        val hints =
            sessionFolderHints(
                buildJsonObject { put("id", "s") },
                buildJsonObject { put("id", "p"); put("name", "Renamed project") },
                listOf("src/Foo.kt", "/Users/robin/code/app/src/Foo.kt"),
            )
        assertEquals(listOf("/Users/robin/code/app"), hints.roots)
        assertEquals("build.gradle.kts", projectRelativePath("/Users/robin/code/app/build.gradle.kts", hints))
        // A cwd the host sends wins.
        val sent = sessionFolderHints(buildJsonObject { put("cwd", "/w/app") }, null, emptyList())
        assertEquals("a.kt", projectRelativePath("/w/app/a.kt", sent))
    }

    @Test
    fun aFileOutsideTheProjectWithACommonNameIsNotTakenForTheProjectsOwn() {
        // pi read the project's README.md, then another checkout's.
        val readme = sessionFolderHints(null, buildJsonObject { put("name", "pocketpi") }, listOf("README.md", "/Users/robin/other-repo/README.md"))
        assertEquals(emptyList<String>(), readme.roots)
        assertNull(projectRelativePath("/Users/robin/other-repo/README.md", readme))
        assertNull(projectRelativePath("/Users/robin/other-repo/src/Foo.kt", readme))
        // A one-segment match never names the session folder; npm's own package.json is not the project's.
        val npm = sessionFolderHints(null, null, listOf("package.json", "/Users/robin/.nvm/versions/node/v22/lib/node_modules/npm/package.json"))
        assertEquals(emptyList<String>(), npm.roots)
        assertNull(projectRelativePath("/Users/robin/.nvm/versions/node/v22/lib/node_modules/npm/lib/cli.js", npm))
        // ... unless the folder is named like the project.
        val named = sessionFolderHints(null, buildJsonObject { put("name", "app") }, listOf("package.json", "/Users/robin/app/package.json"))
        assertEquals(listOf("/Users/robin/app"), named.roots)
        // Folders that disagree name none.
        val split = sessionFolderHints(null, null, listOf("src/Foo.kt", "/a/one/src/Foo.kt", "/b/two/src/Foo.kt"))
        assertEquals(emptyList<String>(), split.roots)
        // With the session folder known, nothing outside it is inside, however it ends.
        val known = sessionFolderHints(null, null, listOf("src/Foo.kt", "/w/app/src/Foo.kt"))
        assertNull(projectRelativePath("/w/other/src/Foo.kt", known))
    }

    @Test
    fun theProjectNamePlacesOnlyPathsInAHomeFolderNamingItOnce() {
        val named = SessionFolderHints(projectName = "pocketpi")
        assertNull(projectRelativePath("/tmp/pocketpi/x.log", named))
        assertNull(projectRelativePath("/Users/robin/.cache/pocketpi/x.log", named))
        assertNull(projectRelativePath("/Users/robin/pocketpi/old/pocketpi/a.kt", named))
        assertNull(projectRelativePath("/Users/pocketpi/a.kt", named))
        assertEquals("a.kt", projectRelativePath("/home/robin/code/pocketpi/a.kt", named))
        assertEquals("a.kt", projectRelativePath("~/pocketpi/a.kt", named))
    }

    @Test
    fun toolCallsOpenAtTheLinesTheyWorkedOn() {
        assertEquals(FileTarget("src/a.kt", LineSelection(10, 29)), toolFileTarget(activity("read", """{"path":"src/a.kt","offset":10,"limit":20}""")))
        assertEquals(FileTarget("src/a.kt", LineSelection(10)), toolFileTarget(activity("read", """{"path":"src/a.kt","offset":10}""")))
        assertEquals(FileTarget("src/a.kt"), toolFileTarget(activity("read", """{"path":"src/a.kt"}""")))
        assertEquals(FileTarget("b.kt"), toolFileTarget(activity("write", """{"path":"b.kt","content":"x"}""")))
        assertNull(toolFileTarget(activity("bash", """{"command":"ls"}""")))
        // Absurd numbers neither overflow nor read the file on and on.
        assertEquals(FileTarget("a.kt"), toolFileTarget(activity("read", """{"path":"a.kt","offset":1e12,"limit":5}""")))
        assertEquals(FileTarget("a.kt", LineSelection(9_999_999, 10_000_000)), toolFileTarget(activity("read", """{"path":"a.kt","offset":9999999,"limit":10000000}""")))
        val patch = "@@ -3,3 +3,4 @@\n a\n-b\n+c\n+d\n e\n"
        val edit = activity("edit", """{"path":"c.kt","edits":[{"oldText":"b","newText":"c\nd"}]}""", ToolDetails(patch, 4, false))
        assertEquals(FileTarget("c.kt", LineSelection(4, 5)), toolFileTarget(edit))
        // Without a usable patch, the first line the host says changed.
        val cut = activity("edit", """{"path":"c.kt"}""", ToolDetails("", 7, true))
        assertEquals(FileTarget("c.kt", LineSelection(7)), toolFileTarget(cut))
    }

    @Test
    fun aRemovedLineOpensWhereTheNewFileContinues() {
        val lines = patchDiff("@@ -1,3 +1,2 @@\n a\n-b\n c\n")
        assertEquals(2, newLineNear(lines, 2))
        assertEquals(LineSelection(2), editedLines(lines))
        // A deleted file has no lines left.
        assertNull(editedLines(patchDiff("@@ -1,2 +0,0 @@\n-a\n-b\n")))
    }

    @Test
    fun fileReferencesNeedALineAndASourceExtensionOrAKnownPath() {
        assertEquals(FileReference("src/Foo.kt", LineSelection(42)), fileReference("src/Foo.kt:42"))
        assertEquals(FileReference("src/Foo.kt", LineSelection(42, 50)), fileReference("src/Foo.kt:42-50"))
        assertEquals(FileReference("Foo.kt", LineSelection(7)), fileReference("Foo.kt:7:12"))
        assertNull(fileReference("src/Foo.kt"))
        assertNull(fileReference("example.com:443"))
        assertNull(fileReference("localhost:8080"))
        assertNull(fileReference("https://example.com/a.kt:3"))
        assertNull(fileReference("10:30"))
        assertNull(fileReference("Makefile:12"))
        assertEquals(FileReference("Makefile", LineSelection(12)), fileReference("Makefile:12") { it == "Makefile" })
    }

    @Test
    fun theOpenerOffersCopyPathOutsideTheProjectOrWithoutFiles() {
        val opened = mutableListOf<FileTarget>()
        val copied = mutableListOf<String>()
        val opener = FileOpener(SessionFolderHints(roots = listOf("/w/app"), toolPaths = setOf("Makefile")), true, { opened += it }, { copied += it })
        assertEquals(FileOpenAction.Open(FileTarget("a.kt", LineSelection(3))), opener.action("/w/app/a.kt", LineSelection(3)))
        assertEquals(FileOpenAction.CopyPath("/etc/hosts"), opener.action("/etc/hosts"))
        opener.perform(opener.action("/etc/hosts"))
        assertEquals(listOf("/etc/hosts"), copied)
        assertTrue(opener.knows("./Makefile"))
        assertFalse(opener.knows("README.md"))
        val offline = FileOpener(SessionFolderHints(), false, { opened += it }, { copied += it })
        assertEquals(FileOpenAction.CopyPath("a.kt"), offline.action("a.kt"))
        assertTrue(opened.isEmpty())
    }
}
