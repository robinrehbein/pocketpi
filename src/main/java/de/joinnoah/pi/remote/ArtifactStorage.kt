package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes artifacts the user chose to open elsewhere or share to `cacheDir/artifacts/`, a folder the
 * export FileProvider serves. Only the latest few files stay; [clear] runs at app start.
 */
internal object ArtifactStorage {
    const val DIRECTORY = "artifacts"
    const val KEEP = 4
    private const val FALLBACK_BASE = "artifact"

    /** A safe file name built from the base name of [path], ending in [extension]. */
    fun safeName(path: String, extension: String): String {
        val base = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(64)
        return "${cleaned.ifEmpty { FALLBACK_BASE }}.$extension"
    }

    /** Deletes every stored artifact. Blocking. */
    fun clear(cacheDir: File) {
        File(cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** Writes [bytes] as [name], keeping only the newest [KEEP] files, and returns the file. Blocking. */
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIRECTORY)
        check(dir.isDirectory || dir.mkdirs()) { "Artifact directory unavailable" }
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
        val file = File(dir, name)
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Unsafe artifact file" }
        // Written outside the served folder, so a half-written file is never shareable.
        val temporary = File(cacheDir, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
        check(file.length() == bytes.size.toLong()) { "Artifact file could not be written" }
        return file
    }
}

/** Writes [text] under [name] in the artifact folder and returns the shareable content URI. Blocking. */
internal fun storeArtifact(context: Context, name: String, text: String): Uri {
    val file = ArtifactStorage.write(context.cacheDir, name, text.toByteArray(Charsets.UTF_8))
    return FileProvider.getUriForFile(context, exportAuthority(context), file)
}

/** The chooser that opens [uri] in a browser or other viewer; the user picks the app. */
internal fun artifactViewIntent(uri: Uri, mimeType: String, title: CharSequence): Intent {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    view.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(view, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** The chooser that shares [uri] with another app. */
internal fun artifactShareIntent(uri: Uri, mimeType: String, title: CharSequence): Intent =
    imageShareIntent(uri, mimeType, title)
