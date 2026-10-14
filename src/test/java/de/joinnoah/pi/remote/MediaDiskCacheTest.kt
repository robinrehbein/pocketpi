package de.joinnoah.pi.remote

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Seals data keys with a fixed AES key, as the Keystore wrap does, so the disk cache runs without Android. */
internal object TestMediaKey {
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")

    val wrap: (ByteArray) -> ByteArray = { value ->
        val iv = ByteArray(12) { 5 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        iv + cipher.doFinal(value)
    }

    val unwrap: (ByteArray) -> ByteArray = { sealed ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

class MediaDiskCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 0L

    private fun cache(capacity: Long = MediaDiskCache.MAX_BYTES) =
        MediaDiskCache(
            File(folder.root, "media-cache"),
            capacity,
            now = { clock },
            alias = "test",
            wrap = TestMediaKey.wrap,
            unwrap = TestMediaKey.unwrap,
        )

    private fun image(seed: Int, size: Int = 200) = ByteArray(size) { (it + seed).toByte() }

    private fun digest(bytes: ByteArray) = AttachmentImportRules.sha256(bytes)

    private fun files() = folder.root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun aStoredImageIsFoundByItsDigestOnItsRouteOnly() {
        val cache = cache()
        val bytes = image(1)
        cache.put("route-a", digest(bytes), bytes)
        assertArrayEquals(bytes, cache.get("route-a", digest(bytes)))
        assertNull(cache.get("route-b", digest(bytes)))
        assertNull(cache.get("route-a", digest(image(2))))
    }

    @Test
    fun bytesThatDoNotMatchTheirDigestAndUnsafeDigestsAreNotStored() {
        val cache = cache()
        val bytes = image(1)
        cache.put("route", digest(image(2)), bytes)
        assertNull(cache.get("route", digest(image(2))))
        cache.put("route", "../../escape", bytes)
        assertNull(cache.get("route", "../../escape"))
        assertTrue(files().isEmpty())
    }

    @Test
    fun theCapEvictsTheLeastRecentlyUsedImageFirst() {
        // A 200-byte image takes 291 bytes on disk: the header, the segment and its tag.
        val cache = cache(capacity = 600)
        val a = image(1)
        val b = image(2)
        val c = image(3)
        clock = 1
        cache.put("route", digest(a), a)
        clock = 2
        cache.put("route", digest(b), b)
        clock = 3
        assertNotNull(cache.get("route", digest(a)))
        clock = 4
        cache.put("route", digest(c), c)
        assertNull(cache.get("route", digest(b)))
        assertNotNull(cache.get("route", digest(a)))
        assertNotNull(cache.get("route", digest(c)))
        assertTrue(files().sumOf { it.length() } <= 600)
    }

    @Test
    fun anImageBiggerThanTheCapIsNotStored() {
        val cache = cache(capacity = 100)
        val bytes = image(1, size = 200)
        cache.put("route", digest(bytes), bytes)
        assertNull(cache.get("route", digest(bytes)))
    }

    @Test
    fun aDamagedFileIsDeletedAndReadsAsAMiss() {
        val cache = cache()
        val bytes = image(1)
        cache.put("route", digest(bytes), bytes)
        val file = files().single()
        val damaged = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        file.writeBytes(damaged)
        assertNull(cache.get("route", digest(bytes)))
        assertFalse(file.exists())
    }

    @Test
    fun aFileThatIsNotTheFormatIsDeletedAndReadsAsAMiss() {
        val cache = cache()
        val bytes = image(1)
        cache.put("route", digest(bytes), bytes)
        files().single().writeBytes(ByteArray(300) { 7 })
        assertNull(cache.get("route", digest(bytes)))
        assertTrue(files().isEmpty())
    }

    @Test
    fun aPointerRemembersThePathOfOneSessionOnOneRoute() {
        val cache = cache()
        val pointer = MediaPointer("images/a.png", "image/png", digest(image(1)))
        cache.putPointer("route", "session", "images/a.png", pointer)
        assertEquals(pointer, cache.pointer("route", "session", "images/a.png"))
        assertNull(cache.pointer("route", "other", "images/a.png"))
        assertNull(cache.pointer("route", "session", "images/b.png"))
        assertNull(cache.pointer("other-route", "session", "images/a.png"))
    }

    @Test
    fun aPointerWithALineBreakInItIsNotStored() {
        val cache = cache()
        val pointer = MediaPointer("images/a.png", "image/png", digest(image(1)))
        cache.putPointer("route", "session", "bad\npath", pointer)
        assertNull(cache.pointer("route", "session", "bad\npath"))
    }

    @Test
    fun clearRouteDropsOnlyThatRoute() {
        val cache = cache()
        val a = image(1)
        val b = image(2)
        cache.put("route-a", digest(a), a)
        cache.put("route-b", digest(b), b)
        cache.putPointer("route-a", "session", "images/a.png", MediaPointer("images/a.png", "image/png", digest(a)))
        cache.clearRoute("route-a")
        assertNull(cache.get("route-a", digest(a)))
        assertNull(cache.pointer("route-a", "session", "images/a.png"))
        assertArrayEquals(b, cache.get("route-b", digest(b)))
    }

    @Test
    fun clearDropsEverything() {
        val cache = cache()
        val a = image(1)
        cache.put("route-a", digest(a), a)
        cache.clear()
        assertNull(cache.get("route-a", digest(a)))
        assertTrue(files().isEmpty())
    }

    @Test
    fun retainRoutesDropsTheFoldersOfRoutesNoLongerPaired() {
        val cache = cache()
        val a = image(1)
        val b = image(2)
        cache.put("route-a", digest(a), a)
        cache.put("route-b", digest(b), b)
        cache.retainRoutes(listOf("route-b"))
        assertNull(cache.get("route-a", digest(a)))
        assertArrayEquals(b, cache.get("route-b", digest(b)))
    }

    @Test
    fun theTotalIsKeptAcrossWritesSoAWriteUnderTheCapEvictsNothing() {
        // Each 200-byte image takes 291 bytes on disk: two fit under 600, and the third evicts the first.
        val cache = cache(capacity = 600)
        val a = image(1)
        val b = image(2)
        val c = image(3)
        clock = 1
        cache.put("route", digest(a), a)
        clock = 2
        cache.put("route", digest(b), b)
        assertEquals(2, files().size)
        clock = 3
        cache.put("route", digest(c), c)
        assertNull(cache.get("route", digest(a)))
        assertArrayEquals(b, cache.get("route", digest(b)))
        assertArrayEquals(c, cache.get("route", digest(c)))
    }
}
