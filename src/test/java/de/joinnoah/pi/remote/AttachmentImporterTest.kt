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

    @Test
    fun dropAttachesEveryContentUriAndKeepsPlainTextForTheDraft() {
        val intake =
            clipIntake(
                listOf(
                    ClipPart("content://media/shot.png", "image/png", null),
                    ClipPart("content://docs/spec.pdf", "application/pdf", null),
                    ClipPart("content://docs/build.log", "text/plain", null),
                    ClipPart("content://docs/icon.svg", "image/svg+xml", null),
                    ClipPart("content://docs/unknown", null, null),
                    ClipPart(null, null, "first line"),
                    ClipPart(null, null, "  "),
                    ClipPart("https://example.com/page", "text/html", null),
                    ClipPart("file:///sdcard/secret", null, null),
                    ClipPart(null, null, "second line"),
                ),
                textFiles = true,
            )
        assertEquals(
            listOf(
                Picked("content://media/shot.png", photo = true),
                Picked("content://docs/spec.pdf", photo = false),
                Picked("content://docs/build.log", photo = false),
                Picked("content://docs/icon.svg", photo = false),
                Picked("content://docs/unknown", photo = false),
            ),
            intake.attachments,
        )
        // Only content URIs are read; a web link goes into the draft, a file URI nowhere.
        assertEquals("first line\nhttps://example.com/page\nsecond line", intake.text)
    }

    @Test
    fun pasteLeavesTextAndTextUrisToTheField() {
        assertTrue(clipIntake(listOf(ClipPart(null, null, "hello")), textFiles = false).attachments.isEmpty())
        assertTrue(
            clipIntake(listOf(ClipPart("content://notes/note", "text/plain", "note")), textFiles = false)
                .attachments.isEmpty()
        )
        assertEquals(
            listOf(Picked("content://clip/shot", photo = true)),
            clipIntake(listOf(ClipPart("content://clip/shot", "IMAGE/PNG", null)), textFiles = false).attachments,
        )
        assertFalse(mayHoldAttachments(listOf("text/plain")))
        assertFalse(mayHoldAttachments(listOf("text/plain", "text/html")))
        assertTrue(mayHoldAttachments(listOf("text/uri-list")))
        assertTrue(mayHoldAttachments(listOf("image/png")))
        assertTrue(mayHoldAttachments(listOf("text/plain", "application/pdf")))
    }

    @Test
    fun aDropIsTakenUnlessItStartedInPocketPi() {
        assertTrue(acceptsDrop(null, setOf("image/png")))
        assertTrue(acceptsDrop(null, setOf("text/plain")))
        assertTrue(acceptsDrop(null, setOf("application/pdf")))
        assertFalse(acceptsDrop(null, setOf("video/mp4")))
        // A drag that starts in PocketPi is meant for another app.
        assertFalse(acceptsDrop(OwnDrag, setOf("image/png")))
    }

    @Test
    fun dropReleasesItsGrantsOnlyAfterTheImportIsDone() {
        var released = 0
        var batch: Pair<List<Picked>, () -> Unit>? = null
        val inserted = mutableListOf<String>()
        val taken =
            acceptChatDrop(
                listOf(
                    ClipPart("content://media/shot.png", "image/png", null),
                    ClipPart("content://docs/spec.pdf", "application/pdf", null),
                ),
                attach = { items, onDone -> batch = items to onDone },
                insertText = { inserted += it; true },
                release = { released++ },
            )
        assertTrue(taken)
        assertEquals(2, batch!!.first.size)
        assertEquals(0, released)
        batch!!.second()
        batch!!.second()
        assertEquals(1, released)
        assertTrue(inserted.isEmpty())

        // Text alone goes into the draft and gives the grants back at once.
        released = 0
        assertTrue(
            acceptChatDrop(listOf(ClipPart(null, null, "fix this")), { _, _ -> fail() }, { inserted += it; true }) { released++ }
        )
        assertEquals(listOf("fix this"), inserted)
        assertEquals(1, released)
        assertFalse(acceptChatDrop(listOf(ClipPart(null, null, " ")), { _, _ -> fail() }, { true }) { released++ })
        assertEquals(2, released)
    }

    @Test
    fun onlyImagesBitmapFactoryDecodesImportAsPhotos() {
        for (type in listOf("image/jpeg", "image/png", "IMAGE/WEBP", "image/bmp"))
            assertTrue(type, decodableImage(type, sdk = 26))
        // TIFF, PSD and RAW never decode; a GIF would lose its animation.
        for (type in listOf("image/tiff", "image/vnd.adobe.photoshop", "image/x-adobe-dng", "image/gif", "image/svg+xml"))
            assertFalse(type, decodableImage(type, sdk = 36))
        assertFalse(decodableImage("image/heic", sdk = 27))
        assertTrue(decodableImage("image/heic", sdk = 28))
        assertTrue(decodableImage("image/heif", sdk = 28))
        assertFalse(decodableImage("image/avif", sdk = 30))
        assertTrue(decodableImage("image/avif", sdk = 31))
        // A PSD dropped with a PDF and a PNG goes in as a file, so it cannot fail the batch.
        assertEquals(
            listOf(
                Picked("content://docs/art.psd", photo = false),
                Picked("content://docs/spec.pdf", photo = false),
                Picked("content://docs/shot.png", photo = true),
                Picked("content://docs/loop.gif", photo = false),
            ),
            clipIntake(
                listOf(
                    ClipPart("content://docs/art.psd", "image/vnd.adobe.photoshop", null),
                    ClipPart("content://docs/spec.pdf", "application/pdf", null),
                    ClipPart("content://docs/shot.png", "image/png", null),
                    ClipPart("content://docs/loop.gif", "image/gif", null),
                ),
                textFiles = true,
            ).attachments,
        )
    }
}
