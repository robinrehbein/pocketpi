package de.joinnoah.pi.remote

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DragStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val cacheDir: File get() = folder.root

    private fun dragDir() = File(cacheDir, DragStorage.DIRECTORY)

    @Test
    fun sameNameWithOtherContentKeepsBothFiles() {
        // docs/before/screen.png and docs/after/screen.png share their safe name.
        val before = DragStorage.write(cacheDir, "screen.png", byteArrayOf(1, 2, 3))
        val after = DragStorage.write(cacheDir, "screen.png", byteArrayOf(4, 5, 6))
        assertNotEquals(before, after)
        assertEquals("screen.png", before.name)
        assertEquals("screen.png", after.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), before.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), after.readBytes())
        // No temporary file is left behind.
        assertEquals(listOf(DragStorage.DIRECTORY), cacheDir.list()!!.toList())
    }

    @Test
    fun sameContentAndNameIsTheSameFileAndNotRewritten() {
        val first = DragStorage.write(cacheDir, "page.html", "<html>".toByteArray())
        first.setLastModified(1_000)
        val again = DragStorage.write(cacheDir, "page.html", "<html>".toByteArray())
        assertEquals(first, again)
        assertEquals(1_000, again.lastModified())
        assertEquals(1, dragDir().list()!!.size)
    }

    @Test
    fun onlyTheNewestFoldersStayAndKeepMarksAFileAsUsed() {
        val oldest = DragStorage.write(cacheDir, "a.png", byteArrayOf(0))
        val kept = DragStorage.write(cacheDir, "b.png", byteArrayOf(1))
        oldest.parentFile!!.setLastModified(1_000)
        kept.parentFile!!.setLastModified(2_000)
        val others =
            (2 until DragStorage.KEEP).map { index ->
                DragStorage.write(cacheDir, "c.png", byteArrayOf(index.toByte())).also {
                    it.parentFile!!.setLastModified(3_000L + index)
                }
            }
        assertEquals(DragStorage.KEEP, dragDir().list()!!.size)
        // b.png is hovered again: the next write rotates a.png out instead.
        assertTrue(DragStorage.keep(kept))
        DragStorage.write(cacheDir, "d.png", byteArrayOf(99))
        assertEquals(DragStorage.KEEP, dragDir().list()!!.size)
        assertFalse(oldest.exists())
        assertFalse(DragStorage.keep(oldest))
        assertTrue(kept.isFile)
        assertTrue(others.all { it.isFile })
    }

    @Test
    fun unsafeNamesAreRefusedAndClearEmptiesTheFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            DragStorage.write(cacheDir, "../escape.png", byteArrayOf(1))
        }
        assertFalse(File(cacheDir, "escape.png").exists())
        DragStorage.write(cacheDir, "a.png", byteArrayOf(1))
        DragStorage.write(cacheDir, "b.png", byteArrayOf(2))
        DragStorage.clear(cacheDir)
        assertTrue(dragDir().list()!!.isEmpty())
    }
}
