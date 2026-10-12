package de.joinnoah.pi.remote

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectImageStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun namesAreSafeAndCarryTheSniffedExtension() {
        assertEquals("shot.png", ImageStorage.safeName("build/shot.png", MediaMime.PNG))
        // The name says png, the bytes say jpeg: the bytes win.
        assertEquals("shot.jpg", ImageStorage.safeName("build/shot.png", MediaMime.JPEG))
        assertEquals("a-b.svg", ImageStorage.safeName("/x/a b.SVG", MediaMime.SVG))
        assertEquals("image.gif", ImageStorage.safeName("../..", MediaMime.GIF))
        assertEquals("image.png", ImageStorage.safeName("", MediaMime.PNG))
    }

    @Test
    fun onlyTheLatestFewImagesStayAndClearRemovesAll() {
        val cache = folder.newFolder("cache")
        for (index in 1..7) {
            val file = ImageStorage.write(cache, "i$index.png", byteArrayOf(index.toByte()))
            file.setLastModified(1_000L * index)
        }
        val kept = File(cache, ImageStorage.DIRECTORY).list()!!.sorted()
        assertEquals(ImageStorage.KEEP, kept.size)
        assertTrue("i7.png" in kept)
        assertFalse("i1.png" in kept)
        assertTrue(cache.listFiles()!!.none { it.name.endsWith(".tmp") })
        ImageStorage.clear(cache)
        assertEquals(0, File(cache, ImageStorage.DIRECTORY).list()!!.size)
    }

    @Test
    fun anImageNameCannotEscapeTheFolder() {
        val cache = folder.newFolder("cache2")
        assertThrows(IllegalArgumentException::class.java) { ImageStorage.write(cache, "../x.png", byteArrayOf(1)) }
    }

    @Test
    fun zoomOffsetsStayInsideTheOverflow() {
        assertEquals(0f, clampImageOffset(50f, 1f, 1000f), 0f)
        assertEquals(500f, clampImageOffset(900f, 2f, 1000f), 0f)
        assertEquals(-500f, clampImageOffset(-900f, 2f, 1000f), 0f)
        assertEquals(120f, clampImageOffset(120f, 2f, 1000f), 0f)
    }
}
