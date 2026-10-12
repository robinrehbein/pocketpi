package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes shared agent images to `cacheDir/images/`, a folder the export FileProvider serves. Only
 * the latest few files stay; [clear] runs at app start, like for exports.
 */
internal object ImageStorage {
    const val DIRECTORY = "images"
    const val KEEP = 4
    private const val FALLBACK_BASE = "image"

    /** A safe file name for [path] ending in the extension of [mime], which comes from the bytes. */
    fun safeName(path: String, mime: MediaMime): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(64)
        return "${cleaned.ifEmpty { FALLBACK_BASE }}.${mime.extension}"
    }

    /** Deletes every stored image. Blocking. */
    fun clear(cacheDir: File) {
        File(cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** Writes [bytes] as [name], keeping only the newest [KEEP] files, and returns the file. Blocking. */
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIRECTORY)
        check(dir.isDirectory || dir.mkdirs()) { "Image directory unavailable" }
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
        val file = File(dir, name)
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Unsafe image file" }
        // Written outside the served folder, so a half-written file is never shareable.
        val temporary = File(cacheDir, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
        check(file.length() == bytes.size.toLong()) { "Image file could not be written" }
        return file
    }
}

/** Writes [image] to the image cache folder and returns the shareable content URI. Blocking. */
internal fun storeProjectImage(context: Context, image: ProjectImageResult.Loaded): Uri {
    val mime = requireNotNull(MediaMime.fromWire(image.mimeType))
    val file = ImageStorage.write(context.cacheDir, ImageStorage.safeName(image.path, mime), image.bytes)
    return FileProvider.getUriForFile(context, exportAuthority(context), file)
}

/** The chooser that shares the image with another app, typed by the sniffed [mimeType]. */
internal fun imageShareIntent(uri: Uri, mimeType: String, title: CharSequence): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
