package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class ChatViewportPositionsTest {
    private val key = RemoteNavKey.Chat("host", "project", "session")
    private val position = ChatViewportPosition("message", 4, 32, false)

    @Test fun positionIsIsolatedByHostProjectAndSession() {
        val positions = ChatViewportPositions()
        positions[key] = position
        assertEquals(position, positions[key])
        assertNull(positions[key.copy(routeId = "other")])
        assertNull(positions[key.copy(projectId = "other")])
        assertNull(positions[key.copy(sessionId = "other")])
    }

    @Test fun readsPromoteAndLeastRecentlyUsedPositionsAreEvicted() {
        val positions = ChatViewportPositions(2)
        val second = key.copy(sessionId = "second")
        val third = key.copy(sessionId = "third")
        positions[key] = position
        positions[second] = position
        assertEquals(position, positions[key])
        positions[third] = position
        assertNull(positions[second])
        assertEquals(position, positions[key])
    }

    @Test fun saveRestoreRetainsAnchorsOffsetsAndFollowingWithoutMessageText() {
        val positions = ChatViewportPositions()
        positions[key] = position
        positions[key.copy(sessionId = "second")] = ChatViewportPosition(null, 0, 0, true)
        val restored = ChatViewportPositions.restored(positions.saved())
        assertEquals(position, restored[key])
        assertEquals(ChatViewportPosition(null, 0, 0, true), restored[key.copy(sessionId = "second")])
    }

    @Test fun malformedSavedValuesAreIgnored() {
        assertNull(ChatViewportPositions.restored(listOf("incomplete"))[key])
        val invalid = listOf("host", "project", "session", "message", "-1", "0", "false")
        assertNull(ChatViewportPositions.restored(invalid)[key])
        assertNull(ChatViewportPositions.restored(invalid.toMutableList().apply { set(4, "0"); set(6, "maybe") })[key])
    }

    @Test fun stableAnchorSurvivesPrependedPagesAndChangedLeadingItems() {
        val ids = listOf("older", "message", "newer")
        assertEquals(1, restoredChatIndex(position, ids, 0))
        assertEquals(2, restoredChatIndex(position, ids, 1))
    }

    @Test fun missingAnchorUsesBoundedMessageRelativeFallback() {
        assertEquals(2, restoredChatIndex(position, listOf("a", "b"), 1))
        assertNull(restoredChatIndex(position, emptyList(), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeOffsetIsRejected() { ChatViewportPositions()[key] = position.copy(offset = -1) }
}
