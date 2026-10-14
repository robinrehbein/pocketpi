package de.joinnoah.pi.remote

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMenusTest {
    @Test
    fun sessionMenuOffersRenameAndCloseOnlyWhereTheRowCan() {
        assertEquals(
            listOf(SessionMenuAction.OPEN, SessionMenuAction.RENAME, SessionMenuAction.CLOSE, SessionMenuAction.COPY_ID),
            sessionMenuActions(canRename = true, canClose = true),
        )
        assertEquals(listOf(SessionMenuAction.OPEN, SessionMenuAction.COPY_ID), sessionMenuActions(canRename = false, canClose = false))
        // A session that can fork offers its latest message's rewind, before the destructive Close.
        assertEquals(
            listOf(
                SessionMenuAction.OPEN, SessionMenuAction.RENAME, SessionMenuAction.FORK_LATEST,
                SessionMenuAction.CLOSE, SessionMenuAction.COPY_ID,
            ),
            sessionMenuActions(canRename = true, canClose = true, canFork = true),
        )
    }

    @Test
    fun retryAndForkOnlyOnAForkableUserMessage() {
        val user = messageMenuActions("user", "Try again", streaming = false, forkable = true, canAskToFix = true)
        assertEquals(
            listOf(MessageMenuAction.QUOTE, MessageMenuAction.COPY, MessageMenuAction.FORK, MessageMenuAction.EDIT_RETRY),
            user,
        )
        // A later bubble of the same message, or a chat that cannot rewind, has neither.
        val notForkable = messageMenuActions("user", "Try again", streaming = false, forkable = false, canAskToFix = true)
        assertFalse(MessageMenuAction.EDIT_RETRY in notForkable)
        assertFalse(MessageMenuAction.FORK in notForkable)
        // An assistant bubble never forks, even when the caller says it could.
        val assistant = messageMenuActions("assistant", "Done", streaming = false, forkable = true, canAskToFix = true)
        assertFalse(MessageMenuAction.EDIT_RETRY in assistant)
        assertTrue(MessageMenuAction.ASK_TO_FIX in assistant)
    }

    @Test
    fun aPendingPromptOffersNoQuoteButStillCopies() {
        val pending = messageMenuActions(
            "user", "Sent just now", streaming = false, forkable = false, canAskToFix = false, canQuote = false,
        )
        assertFalse(MessageMenuAction.QUOTE in pending)
        assertTrue(MessageMenuAction.COPY in pending)
    }

    @Test
    fun streamingTextIsQuotedButNotCopiedOrFixed() {
        assertEquals(
            listOf(MessageMenuAction.QUOTE),
            messageMenuActions("assistant", "Half an answer", streaming = true, forkable = false, canAskToFix = true),
        )
        assertTrue(messageMenuActions("assistant", "   ", streaming = false, forkable = false, canAskToFix = true).isEmpty())
        assertFalse(
            MessageMenuAction.ASK_TO_FIX in
                messageMenuActions("assistant", "Done", streaming = false, forkable = false, canAskToFix = false)
        )
    }

    @Test
    fun copyAsMarkdownOnlyWhereTheMarkdownDiffers() {
        val plain = messageMenuActions("assistant", "Just words.", streaming = false, forkable = false, canAskToFix = false)
        assertFalse(MessageMenuAction.COPY_MARKDOWN in plain)
        val marked = messageMenuActions("assistant", "**Bold** words.", streaming = false, forkable = false, canAskToFix = false)
        assertTrue(MessageMenuAction.COPY_MARKDOWN in marked)
    }

    @Test
    fun plainTextDropsMarksAndKeepsCode() {
        val markdown =
            """
            # Title
            - **bold** and `code`
            See [the docs](https://example.com/docs).
            ```kotlin
            val x = **1**
            ```
            """.trimIndent()
        assertEquals("Title\n• bold and code\nSee the docs.\nval x = **1**", markdownPlainText(markdown))
        val blocks =
            """
            | Name | **Size** |
            | --- | --- |
            | `a.kt` | 2 KB |
            ---
            ![The login screen](shots/login.png)
            """.trimIndent()
        assertEquals("Name\tSize\na.kt\t2 KB\nThe login screen", markdownPlainText(blocks))
    }

    @Test
    fun linkAtFindsTheUrlUnderAnOffset() {
        val text = inlineMarkdown("Read [docs](https://example.com/docs) now", Color.Blue)
        val start = text.text.indexOf("docs")
        assertEquals("https://example.com/docs", linkAt(text, start))
        assertEquals("https://example.com/docs", linkAt(text, start + 3))
        assertNull(linkAt(text, 0))
        assertNull(linkAt(text, text.length))
    }

    @Test
    fun toolMenuCopiesTheWholeBashCommand() {
        assertEquals(
            listOf(ToolMenuAction.OPEN, ToolMenuAction.PEEK, ToolMenuAction.COPY_COMMAND, ToolMenuAction.COPY_OUTPUT),
            toolMenuActions(canOpen = true, hasOutput = true, hasCommand = true),
        )
        assertEquals(listOf(ToolMenuAction.OPEN), toolMenuActions(canOpen = true, hasOutput = false, hasCommand = false))
        assertEquals("git status\ngit diff", toolCommand("bash", """{"command":"git status\ngit diff"}"""))
        assertNull(toolCommand("read", """{"command":"x"}"""))
        assertNull(toolCommand("bash", """{"command":"  "}"""))
        assertNull(toolCommand("bash", "not json"))
    }

    @Test
    fun fileMenuCopiesContentsOnlyWhenTheTileHoldsTheWholeFile() {
        assertEquals(
            listOf(FileMenuAction.OPEN, FileMenuAction.SEND, FileMenuAction.COPY_PATH, FileMenuAction.COPY_CONTENTS),
            fileMenuActions(openable = true, canSend = true, contentsLoaded = true),
        )
        assertEquals(listOf(FileMenuAction.OPEN, FileMenuAction.COPY_PATH), fileMenuActions(openable = true, canSend = false, contentsLoaded = false))
        assertEquals(listOf(FileMenuAction.COPY_PATH), fileMenuActions(openable = false, canSend = false, contentsLoaded = false))
    }

    @Test
    fun diffLineMenuCommentsOnlyWhereTheLineTakesOne() {
        assertEquals(
            listOf(DiffLineMenuAction.COMMENT, DiffLineMenuAction.COPY_LINE, DiffLineMenuAction.COPY_HUNK),
            diffLineMenuActions(commentable = true),
        )
        assertEquals(listOf(DiffLineMenuAction.COPY_LINE, DiffLineMenuAction.COPY_HUNK), diffLineMenuActions(commentable = false))
        assertEquals(
            listOf(DiffLineMenuAction.COMMENT, DiffLineMenuAction.COPY_LINE, DiffLineMenuAction.COPY_HUNK, DiffLineMenuAction.OPEN_FILE),
            diffLineMenuActions(commentable = true, openable = true),
        )
    }

    @Test
    fun projectMenuOffersWhatTheProjectAllows() {
        assertEquals(
            listOf(ProjectMenuAction.OPEN, ProjectMenuAction.NEW_SESSION, ProjectMenuAction.UNSHARE),
            projectMenuActions(canCreateSession = true, canUnshare = true),
        )
        assertEquals(listOf(ProjectMenuAction.OPEN), projectMenuActions(canCreateSession = false, canUnshare = false))
    }

    @Test
    fun hunkTextRunsFromItsHeaderToTheNextWithPrefixes() {
        val lines =
            listOf(
                DiffLine(DiffKind.HUNK, "@@ -1,2 +1,2 @@", null, null),
                DiffLine(DiffKind.CONTEXT, "keep", 1, 1),
                DiffLine(DiffKind.REMOVED, "old", 2, null),
                DiffLine(DiffKind.ADDED, "new", null, 2),
                DiffLine(DiffKind.HUNK, "@@ -9 +9 @@", null, null),
                DiffLine(DiffKind.ADDED, "later", null, 9),
            )
        assertEquals("@@ -1,2 +1,2 @@\n keep\n-old\n+new", diffHunkText(lines, 2))
        assertEquals("@@ -1,2 +1,2 @@\n keep\n-old\n+new", diffHunkText(lines, 0))
        assertEquals("@@ -9 +9 @@\n+later", diffHunkText(lines, 5))
        // A tool's diff has no headers, only empty dividers between its changes.
        val tool =
            listOf(
                DiffLine(DiffKind.REMOVED, "a", 1, null),
                DiffLine(DiffKind.HUNK, "", null, null),
                DiffLine(DiffKind.ADDED, "b", null, 1),
            )
        assertEquals("-a", diffHunkText(tool, 0))
        assertEquals("+b", diffHunkText(tool, 2))
        assertEquals("", diffHunkText(tool, 7))
    }

    @Test
    fun shiftF10AndTheMenuKeyOpenAMenu() {
        assertTrue(opensContextMenu(Key.F10, shift = true))
        assertTrue(opensContextMenu(Key.Menu, shift = false))
        assertFalse(opensContextMenu(Key.F10, shift = false))
        assertFalse(opensContextMenu(Key.Enter, shift = true))
    }

    @Test
    fun closeAlwaysJoinsTheMenuAndNeverTheRanking() {
        assertFalse(ChatAction.CLOSE in DEFAULT_CHAT_ACTIONS)
        val few = chatActionLayout(DEFAULT_CHAT_ACTIONS, setOf(ChatAction.REFRESH, ChatAction.SETTINGS))
        assertEquals(ChatActionLayout(few.shown, listOf(ChatAction.CLOSE)), few.withClose())
        // Three buttons without a menu: Close's chevron takes the third one's place.
        val three = chatActionLayout(DEFAULT_CHAT_ACTIONS, setOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.SETTINGS))
        assertEquals(3, three.shown.size)
        val closable = three.withClose()
        assertEquals(listOf(ChatAction.CHANGES, ChatAction.REFRESH), closable.shown)
        assertEquals(listOf(ChatAction.SETTINGS, ChatAction.CLOSE), closable.menu)
        // History keeps the button of its own.
        val narrow = three.withHistory().withClose()
        assertEquals(listOf(ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.HISTORY), narrow.shown)
        val many = chatActionLayout(DEFAULT_CHAT_ACTIONS, ChatAction.entries.toSet()).withHistory().withClose()
        assertEquals(ChatAction.CLOSE, many.menu.last())
        assertFalse(ChatAction.CLOSE in many.shown)
    }

    @Test
    fun emptySpaceBelowAListThatEndsAboveItsViewport() {
        // Items fill 300 px of an 800 px viewport, whose end lies at 780 past a 20 px top padding.
        assertEquals(480, emptySpaceBelowList(layout(total = 2, items = listOf(item(0, 0, 100), item(1, 110, 190)), end = 780)))
        // The last item is still below: nothing is empty.
        assertEquals(0, emptySpaceBelowList(layout(total = 5, items = listOf(item(3, 600, 100), item(4, 710, 100)).take(1), end = 780)))
        assertEquals(0, emptySpaceBelowList(layout(total = 1, items = listOf(item(0, 0, 900)), end = 780)))
        assertEquals(780, emptySpaceBelowList(layout(total = 0, items = emptyList(), end = 780)))
        // The after-padding is never empty space: a list scrolled to its end has none.
        assertEquals(0, emptySpaceBelowList(layout(total = 9, items = listOf(item(8, 500, 180)), end = 780, after = 100)))
        assertEquals(380, emptySpaceBelowList(layout(total = 2, items = listOf(item(0, 0, 100), item(1, 110, 190)), end = 780, after = 100)))
        assertEquals(680, emptySpaceBelowList(layout(total = 0, items = emptyList(), end = 780, after = 100)))
    }

    private fun item(index: Int, offset: Int, size: Int) =
        object : LazyListItemInfo {
            override val index = index
            override val key: Any = index
            override val offset = offset
            override val size = size
            override val contentType: Any? = null
        }

    private fun layout(total: Int, items: List<LazyListItemInfo>, end: Int, after: Int = 0) =
        object : LazyListLayoutInfo {
            override val visibleItemsInfo = items
            override val viewportStartOffset = -20
            override val viewportEndOffset = end
            override val totalItemsCount = total
            override val viewportSize = IntSize(400, 800)
            override val orientation = Orientation.Vertical
            override val reverseLayout = false
            override val beforeContentPadding = 20
            override val afterContentPadding = after
            override val mainAxisItemSpacing = 0
        }
}
