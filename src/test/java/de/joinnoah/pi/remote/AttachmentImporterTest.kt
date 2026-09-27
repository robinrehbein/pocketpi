package de.joinnoah.pi.remote

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class AttachmentImporterTest {
    @Test
    fun namesRemovePathsControlsAndTraversalAndPreserveUnicodeWithinByteLimit() {
        assertEquals("report.txt", AttachmentImportRules.name("../../report.txt"))
        assertEquals("photo.jpg", AttachmentImportRules.name("C:\\secret\\photo.jpg"))
        assertEquals("ab.txt", AttachmentImportRules.name("a\n\u202Eb.txt"))
        assertEquals("attachment", AttachmentImportRules.name(".."))
        val result = AttachmentImportRules.name("😀".repeat(100))
        assertTrue(result.toByteArray().size <= 255)
        assertFalse(result.contains('\uFFFD'))
        assertEquals("😀".repeat(63), result)
        assertTrue(
            AttachmentImportRules.jpegName("ü".repeat(200) + ".png").toByteArray().size <= 255
        )
        assertTrue(AttachmentImportRules.jpegName("hello.png").endsWith(".jpg"))
    }

    @Test
    fun normalizedNamesRemainStableAfterBoundaryCleanupAndUtf8Truncation() {
        assertEquals("report.txt", AttachmentImportRules.name(". report.txt"))
        assertEquals("report.txt", AttachmentImportRules.name(" . report.txt . "))
        assertEquals("attachment", AttachmentImportRules.name(" . . "))
        val cases =
            listOf(
                ". report.txt",
                " . report.txt . ",
                " . . ",
                ".....",
                "__",
                "a".repeat(254) + " .tail",
                "a".repeat(254) + ".tail",
                "ü".repeat(127) + ".tail",
                "a".repeat(250) + ".long.png",
            )
        for (value in cases) {
            val normalized = AttachmentImportRules.name(value)
            assertEquals(normalized, AttachmentImportRules.name(normalized))
            val jpeg = AttachmentImportRules.jpegName(value)
            assertEquals(jpeg, AttachmentImportRules.name(jpeg))
            assertTrue(jpeg.toByteArray().size <= 255)
        }
    }

    @Test
    fun readingRejectsActualBytesBeyondLimitWithoutTrustingAvailableOrDeclaredSize() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            AttachmentImportRules.readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3),
        )
        val stream =
            object : InputStream() {
                var count = 0

                override fun available() = 0

                override fun read(): Int {
                    count++
                    return 1
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentImportRules.readLimited(stream, 8)
        }
        assertEquals(9, stream.count)
        assertArrayEquals(
            byteArrayOf(),
            AttachmentImportRules.readLimited(ByteArrayInputStream(byteArrayOf()), 8),
        )
    }

    @Test
    fun identifiersMetadataAndDigestsAreValidated() {
        assertTrue(AttachmentImportRules.validId("0123456789abcdefghijkl"))
        assertFalse(AttachmentImportRules.validId("../../somewhere"))
        assertEquals("application/octet-stream", AttachmentImportRules.mime("bad\nmime"))
        assertEquals("image/png", AttachmentImportRules.mime("image/png"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AttachmentImportRules.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun imageSamplingBoundsDimensionsBeforeDecode() {
        assertEquals(4, AttachmentImportRules.sampleSize(8000, 6000))
        assertEquals(1, AttachmentImportRules.sampleSize(1024, 768))
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentImportRules.sampleSize(0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentImportRules.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }
}
