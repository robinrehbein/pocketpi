package de.joinnoah.pi.remote

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorRecoveryExportTest {
    @Test fun validUnicodeIsWrittenAsExactUtf8IncludingControlsAndSupplementaryCharacters() {
        val text = "A\u0000\n😀é"
        val output = ByteArrayOutputStream()
        val format = editorRecoveryExportFormat(text)
        assertEquals("text/plain", format.mimeType)
        assertEquals("txt", format.extension)
        writeEditorRecovery(text, format, output)
        assertArrayEquals(byteArrayOf(0x41, 0, 10, 0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x80.toByte(), 0xc3.toByte(), 0xa9.toByte()), output.toByteArray())
    }

    @Test fun isolatedSurrogatesArePreservedAsBomAndLittleEndianCodeUnits() {
        val text = "A\uD800\u0000\uDC00"
        val output = ByteArrayOutputStream()
        val format = editorRecoveryExportFormat(text)
        assertEquals("application/octet-stream", format.mimeType)
        assertEquals("utf16", format.extension)
        writeEditorRecovery(text, format, output)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41, 0, 0, 0xd8.toByte(), 0, 0, 0, 0xdc.toByte()), output.toByteArray())
    }
    @Test fun pendingExportKeepsOriginalSelectionUntilPickerReturns() {
        val key = PagedJournalKey("host", "session", "question")
        val first = EditorRecoveryEntry(key, EditorRecoveryKind.DRAFT, "first", 1)
        val second = EditorRecoveryEntry(key, EditorRecoveryKind.UNCERTAIN_ATTEMPT, "second", 2, "attempt")
        val pending = PendingEditorRecoveryExport()
        pending.begin(first)
        val selected = second
        val frozen = requireNotNull(pending.take())
        val output = ByteArrayOutputStream()
        writeEditorRecovery(frozen.text, editorRecoveryExportFormat(frozen.text), output)
        assertEquals("second", selected.text)
        assertArrayEquals(byteArrayOf(102, 105, 114, 115, 116), output.toByteArray())
        assertEquals(null, pending.take())
    }

    @Test fun largeInvalidTextUsesBoundedWritesToTheProvider() {
        val text = "x".repeat(1_048_575) + "\uD800"
        var writes = 0
        var bytes = 0
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { writes++; bytes++ }
            override fun write(value: ByteArray, offset: Int, length: Int) { writes++; bytes += length }
        }
        writeEditorRecovery(text, editorRecoveryExportFormat(text), output)
        assertEquals(2 + 2 * text.length, bytes)
        org.junit.Assert.assertTrue("Provider writes must be buffered", writes <= 1_000)
    }

    @Test fun utf8WriterRejectsInvalidSurrogatesInsteadOfReplacingThem() {
        val output = ByteArrayOutputStream()
        try {
            writeEditorRecovery("\uD800", EditorRecoveryExportFormat("text/plain", "txt"), output)
            org.junit.Assert.fail("Invalid UTF-16 must not be silently replaced")
        } catch (_: java.nio.charset.CharacterCodingException) {
            // Expected.
        }
    }

}
