package de.joinnoah.pi.remote

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.security.SecureRandom

/** A paste of more characters than this is offered as a text file instead of going into the field. */
internal const val LONG_PASTE_CHARS = 8 * 1024

/** A paste of more lines than this is offered as a text file instead of going into the field. */
internal const val LONG_PASTE_LINES = 200

/** Whether a pasted [text] is long enough to be offered as a text file ([LONG_PASTE_CHARS], [LONG_PASTE_LINES]). */
internal fun isLongPaste(text: String): Boolean =
    text.length > LONG_PASTE_CHARS || text.count { it == '\n' } + 1 > LONG_PASTE_LINES

/**
 * The text [after] has and [before] lacks at the place of one edit: the common start and end of the
 * two are left out. A deletion yields an empty text.
 */
internal fun insertedText(before: CharSequence, after: CharSequence): String {
    val limit = minOf(before.length, after.length)
    var start = 0
    while (start < limit && before[start] == after[start]) start++
    var end = 0
    while (end < limit - start && before[before.length - 1 - end] == after[after.length - 1 - end]) end++
    return after.subSequence(start, after.length - end).toString()
}

/**
 * The attachment a content URI of [mimeType] is, or null when it is not one: text stays with the
 * field, as a text file would without PocketPi looking at it, and so does anything not a content URI.
 */
internal fun attachmentOf(uri: String?, mimeType: String?): Picked? {
    if (uri == null || !uri.startsWith("content:", ignoreCase = true)) return null
    val mime = mimeType?.lowercase() ?: "application/octet-stream"
    if (mime.startsWith("text/")) return null
    return Picked(uri, photo = decodableImage(mime))
}

/**
 * Takes the images and files of [content] (a keyboard commit, a paste) and returns what the field
 * keeps: text, and every item that is not an attachment. The taken ones go to [attach] as one batch.
 */
internal fun takeAttachments(
    content: TransferableContent,
    typeOf: (Uri) -> String?,
    attach: (List<Picked>) -> Unit,
): TransferableContent? {
    val taken = mutableListOf<Picked>()
    val rest = content.consume { item ->
        val uri = item.uri?.toString()
        val picked = attachmentOf(uri, uri?.let { runCatching { typeOf(it.toUri()) }.getOrNull() })
        picked?.also(taken::add) != null
    }
    if (taken.isNotEmpty()) attach(taken)
    return rest
}

/**
 * The receiver the composer field installs with `Modifier.contentReceiver`: keyboard images and
 * stickers, and the images and files of a paste, go to [attach] instead of the field.
 */
internal fun composerContentListener(context: Context, attach: (List<Picked>) -> Unit): ReceiveContentListener =
    object : ReceiveContentListener {
        override fun onReceive(transferableContent: TransferableContent): TransferableContent? =
            takeAttachments(transferableContent, context.contentResolver::getType, attach)
    }

/**
 * Writes a pasted [text] to the export folder as a .txt file and returns it. The caller deletes it
 * once the import read it. Blocking.
 */
internal fun writePastedText(context: Context, text: String): File {
    val dir = File(context.cacheDir, "exports")
    check(dir.isDirectory || dir.mkdirs()) { "Export folder unavailable" }
    val name = "pasted-text-" + ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } + ".txt"
    val file = File(dir, name)
    file.writeText(text)
    return file
}

/** The content URI of a file [writePastedText] wrote, which the import reads. */
internal fun pastedTextUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, exportAuthority(context), file)

/** The inline offer above the field for a long paste: attach it as a text file, or paste it as text. */
@Composable
internal fun LongPasteOffer(onAttach: () -> Unit, onPaste: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp).testTag("longPasteOffer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.remote_long_paste_body),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onPaste, modifier = Modifier.testTag("longPastePaste")) {
            Text(stringResource(R.string.remote_long_paste_insert))
        }
        TextButton(onClick = onAttach, modifier = Modifier.testTag("longPasteAttach")) {
            Text(stringResource(R.string.remote_long_paste_attach))
        }
    }
}
