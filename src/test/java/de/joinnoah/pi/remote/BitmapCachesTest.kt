package de.joinnoah.pi.remote

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BitmapCachesTest {
    private val mb = 1024 * 1024

    @After
    fun restore() {
        ProjectImageBitmaps.resize(ProjectImageBitmaps.MAX_BYTES)
        ArtifactThumbnails.resize(ArtifactThumbnails.MAX_BYTES)
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
    }

    @Test
    fun theCachesAreSizedFromTheHeap() {
        BitmapCaches.configure(192)
        assertEquals(24 * mb, ProjectImageBitmaps.capacityBytes)
        assertEquals(6 * mb, ArtifactThumbnails.capacityBytes)
        // A large heap gets no more than before; a tiny one still caches something.
        BitmapCaches.configure(1024)
        assertEquals(48 * mb, ProjectImageBitmaps.capacityBytes)
        assertEquals(8 * mb, ArtifactThumbnails.capacityBytes)
        assertEquals(mb, bitmapCacheBytes(4, 32, 8 * mb))
    }

    @Test
    fun aHiddenOrBackgroundedAppDropsTheDecodedBitmaps() {
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) {
            val image = ProjectImageBitmaps.Key("session", "a.png", "sha", 100)
            val thumbnail = ArtifactThumbnails.Key(ArtifactKind.entries.first(), "sha", 100, false)
            ProjectImageBitmaps[image] = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            ArtifactThumbnails[thumbnail] = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            BitmapCaches.trim(level)
            assertNull(ProjectImageBitmaps[image])
            assertNull(ArtifactThumbnails[thumbnail])
        }
    }

    @Test
    fun aRunningAppKeepsItsBitmaps() {
        val image = ProjectImageBitmaps.Key("session", "a.png", "sha", 100)
        ProjectImageBitmaps[image] = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        BitmapCaches.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN - 1)
        assertNotNull(ProjectImageBitmaps[image])
    }
}
