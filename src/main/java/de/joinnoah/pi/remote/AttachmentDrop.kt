package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean

/** One item of a dropped or pasted clip: a URI with its type, or plain text. */
internal data class ClipPart(val uri: String?, val mimeType: String?, val text: String?)

/** What a clip brings to the chat: attachments to import as one batch, and text for the draft. */
internal data class ClipIntake(val attachments: List<Picked>, val text: String)

/**
 * Image types the importer scales into a JPEG: those BitmapFactory decodes on [sdk]. Other images
 * go in as files, unchanged: SVG, TIFF, PSD, RAW, HEIF before Android 9, AVIF before Android 12,
 * and GIF, which a JPEG would flatten to its first frame.
 */
internal fun decodableImage(mimeType: String, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp", "image/bmp", "image/x-ms-bmp" -> true
        "image/heic", "image/heif" -> sdk >= Build.VERSION_CODES.P
        "image/avif" -> sdk >= Build.VERSION_CODES.S
        else -> false
    }

/**
 * Splits clip [parts] into attachments and text. A content URI becomes an attachment, imported as
 * a photo when it is a decodable image. With [textFiles] off (a paste), a content URI of a text type
 * is not taken: the field pastes it as text, as it would without PocketPi looking. A drop takes it,
 * since a log file dragged from My Files is a file. Plain text items and web links are joined for
 * the draft.
 */
internal fun clipIntake(parts: List<ClipPart>, textFiles: Boolean): ClipIntake {
    val attachments = mutableListOf<Picked>()
    val text = mutableListOf<String>()
    for (part in parts) {
        val uri = part.uri?.takeIf { it.startsWith("content:", ignoreCase = true) }
        val mimeType = part.mimeType?.lowercase() ?: "application/octet-stream"
        if (uri != null && (textFiles || !mimeType.startsWith("text/")))
            attachments += Picked(uri, photo = decodableImage(mimeType))
        // A dragged link may come as a web URI alone.
        else if (uri == null)
            (part.text ?: part.uri?.takeIf { it.startsWith("http", ignoreCase = true) })
                ?.takeIf { it.isNotBlank() }
                ?.let(text::add)
    }
    return ClipIntake(attachments, text.joinToString("\n"))
}

/** The parts of this clip; [typeOf] is the content resolver's type, the clip's own type the fallback. */
internal fun ClipData.parts(typeOf: (Uri) -> String?): List<ClipPart> {
    val description = description
    val single = description?.takeIf { it.mimeTypeCount == 1 }?.getMimeType(0)
    return (0 until itemCount).map { index ->
        val item = getItemAt(index)
        val uri = item.uri
        ClipPart(
            uri?.toString(),
            uri?.let { runCatching { typeOf(it) }.getOrNull() ?: single },
            item.text?.toString(),
        )
    }
}

/**
 * Whether a clip of [mimeTypes] may hold an image or a file. Only such a clip is read on a paste:
 * a plain text one stays the field's, untouched.
 */
internal fun mayHoldAttachments(mimeTypes: List<String>): Boolean =
    mimeTypes.any { !it.startsWith("text/") || it == ClipDescription.MIMETYPE_TEXT_URILIST }

/**
 * A drag the chat takes: images, text and other files (`application/…`) from another app. A drag
 * with a [localState] started in PocketPi ([OwnDrag]) and is meant for another app.
 */
internal fun acceptsDrop(localState: Any?, mimeTypes: Set<String>): Boolean =
    localState == null &&
        mimeTypes.any { type -> listOf("image/", "text/", "application/").any { type.startsWith(it, ignoreCase = true) } }

/** Ctrl+V and Shift+Insert paste; Ctrl+Shift+V pastes plain text, so it stays the field's. */
internal fun isPasteKey(key: Key, down: Boolean, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean =
    down && !alt && ((key == Key.V && ctrl && !shift) || (key == Key.Insert && shift && !ctrl))

/** The images and files on the clipboard, or null when it holds none (plain text, nothing). */
internal fun clipboardIntake(context: Context): ClipIntake? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return runCatching {
        val description = clipboard.primaryClipDescription ?: return null
        if (!mayHoldAttachments((0 until description.mimeTypeCount).map(description::getMimeType))) return null
        val clip = clipboard.primaryClip ?: return null
        clipIntake(clip.parts(context.contentResolver::getType), textFiles = false)
    }.getOrNull()?.takeIf { it.attachments.isNotEmpty() }
}

/**
 * Takes a drop on the chat: its attachments go to [attach] as one batch, its plain text to
 * [insertText]. [release] gives back the drop's read grants exactly once: after the import read
 * every URI (the `onDone` [attach] gets), or at once when nothing is imported. False when the drop
 * brought nothing the chat took.
 */
internal fun acceptChatDrop(
    parts: List<ClipPart>,
    attach: (items: List<Picked>, onDone: () -> Unit) -> Unit,
    insertText: (String) -> Boolean,
    release: () -> Unit,
): Boolean {
    val intake = clipIntake(parts, textFiles = true)
    val inserted = intake.text.isNotEmpty() && insertText(intake.text)
    if (intake.attachments.isEmpty()) {
        release()
        return inserted
    }
    val released = AtomicBoolean(false)
    attach(intake.attachments) { if (released.compareAndSet(false, true)) release() }
    return true
}

/** Covers the chat while a drag it takes hovers over it. */
@Composable
internal fun DropScrim(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f), shape)
            .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            .testTag("dropScrim")
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.remote_drop_to_attach),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
