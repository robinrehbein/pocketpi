package de.joinnoah.pi.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderActionsResponsiveTest {
    @Test
    fun stacksForNarrowWidthsAndLargeText() {
        assertTrue(stackFolderActions(280f, 1f))
        assertFalse(stackFolderActions(400f, 1f))
        assertTrue(stackFolderActions(400f, 1.5f))
        assertFalse(stackFolderActions(600f, 1.5f))
    }
}
