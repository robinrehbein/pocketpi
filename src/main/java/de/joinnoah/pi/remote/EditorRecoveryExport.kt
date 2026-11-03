package de.joinnoah.pi.remote

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class EditorRecoveryExportFormat(val mimeType: String, val extension: String)

internal fun editorRecoveryExportFormat(text: String): EditorRecoveryExportFormat {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1]))
                return EditorRecoveryExportFormat("application/octet-stream", "utf16")
            i += 2
        } else {
            if (Character.isLowSurrogate(c)) return EditorRecoveryExportFormat("application/octet-stream", "utf16")
            i++
        }
    }
    return EditorRecoveryExportFormat("text/plain", "txt")
}

internal fun writeEditorRecovery(text: String, format: EditorRecoveryExportFormat, output: OutputStream) {
    // Buffer writes to document providers; closing this wrapper never closes the caller's stream.
    val buffered = BufferedOutputStream(output, 8192)
    if (format.extension == "utf16") {
        buffered.write(0xff)
        buffered.write(0xfe)
        for (c in text) {
            buffered.write(c.code and 0xff)
            buffered.write(c.code ushr 8)
        }
        buffered.flush()
    } else {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // Closing the writer signals end-of-input to the encoder, including a trailing surrogate.
        val nonClosing = object : FilterOutputStream(buffered) {
            override fun close() { flush() }
        }
        OutputStreamWriter(nonClosing, encoder).use { it.write(text) }
    }
}

/** Owns only the immutable entry chosen when the document picker was launched. */
internal class PendingEditorRecoveryExport {
    private var entry: EditorRecoveryEntry? = null
    fun begin(value: EditorRecoveryEntry) { check(entry == null); entry = value }
    fun take(): EditorRecoveryEntry? = entry.also { entry = null }
    fun hasEntry(): Boolean = entry != null
}
