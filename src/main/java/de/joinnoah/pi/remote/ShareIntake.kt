package de.joinnoah.pi.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/*
 * Content other apps share with PocketPi (ShareActivity) and photos taken from the composer.
 *
 * ShareActivity is exported, so everything it receives is checked here: only content URIs that
 * PocketPi may read, that belong to another app and that the attachment import can take; text only
 * from EXTRA_TEXT. No other extra counts, in particular none that names a session. The checked
 * content reaches MainActivity through the non-exported alias (LaunchIntents.share) as ClipData
 * with FLAG_GRANT_READ_URI_PERMISSION, so the read grant moves to MainActivity, which keeps it
 * while the import copies the files.
 */

/** A shared file the attachment import can take; [image] ones go in as photos (a normalized JPEG). */
internal data class SharedFile(val uri: Uri, val image: Boolean)

/**
 * Checked content of a share: [text] goes into the draft, [files] are attached. [skipped] counts
 * what was left out (an unsupported type, an unreadable or oversized file, more than
 * [ShareIntake.MAX_FILES] files, text over the draft's limit).
 */
internal data class SharedContent(
    val text: String? = null,
    val files: List<SharedFile> = emptyList(),
    val skipped: Int = 0,
) {
    val empty: Boolean get() = text == null && files.isEmpty()
}

/** Where a share goes. */
internal sealed interface ShareTarget {
    /** An existing session; [projectId] is null when only the route and session are known. */
    data class Session(val routeId: String, val projectId: String?, val sessionId: String) : ShareTarget

    /** A new session in this project. */
    data class NewSession(val routeId: String, val projectId: String) : ShareTarget

    /** The user chooses in the app's own navigation, under a banner. */
    data object Pick : ShareTarget
}

internal data class ShareRequest(val content: SharedContent, val target: ShareTarget)

/** A share handed to the layout; [delivery] tells two equal shares apart. */
internal data class PendingShare(val request: ShareRequest, val delivery: Long)

/** What [ShareIntake.parse] asks the system about a shared URI; a test replaces it. */
internal interface ShareAccess {
    /** The type the URI's provider reports, or null. */
    fun type(uri: Uri): String?

    /** PocketPi may read the URI: the sender granted it, or the provider needs no permission. */
    fun readable(uri: Uri): Boolean

    /** The size the provider reports, or null when it does not say. */
    fun size(uri: Uri): Long?

    /** The URI belongs to one of PocketPi's own providers. */
    fun own(uri: Uri): Boolean
}

/** [ShareAccess] through the content resolver; blocking, so call it off the main thread. */
internal class ContentShareAccess(private val context: Context) : ShareAccess {
    private val resolver = context.contentResolver

    override fun type(uri: Uri): String? = runCatching { resolver.getType(uri) }.getOrNull()

    override fun readable(uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    override fun size(uri: Uri): Long? =
        runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }
            .getOrNull()

    override fun own(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        if (authority.startsWith("${context.packageName}.")) return true
        return runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull() ==
            context.packageName
    }
}

internal object ShareIntake {
    /** The attachment limit of one draft. */
    const val MAX_FILES = 5

    /** The draft's own limit (DefaultRemoteRepository.draft). */
    const val MAX_TEXT_BYTES = 128 * 1024

    // What BitmapFactory decodes, and so what the photo import can normalize.
    private val IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp")

    // BitmapFactory decodes these only from API 28; below, the import would fail on them.
    private val HEIF_TYPES = setOf("image/heic", "image/heif")

    /**
     * Whether a file of [type] is attached as a photo (true), as a file (false), or not at all
     * (null), on a device running [sdk].
     */
    fun kind(type: String?, sdk: Int = Build.VERSION.SDK_INT): Boolean? {
        val mime = type?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            mime in IMAGE_TYPES -> true
            mime in HEIF_TYPES && sdk >= Build.VERSION_CODES.P -> true
            mime == "application/pdf" -> false
            mime.startsWith("text/") -> false
            else -> null
        }
    }

    /**
     * The checked content of an ACTION_SEND or ACTION_SEND_MULTIPLE [intent], or null for any
     * other intent. Reads only EXTRA_TEXT, EXTRA_STREAM and the ClipData; blocking ([access]).
     */
    fun parse(intent: Intent, access: ShareAccess): SharedContent? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        var skipped = 0
        val uris = LinkedHashSet<Uri>()
        val clip = intent.clipData
        var clipText: String? = null
        if (clip != null)
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val uri = item.uri
                if (uri != null) uris += uri
                else if (clipText == null) clipText = item.text?.toString()
            }
        // A malformed parcel from the sender must not crash the share sheet.
        runCatching {
            if (intent.action == Intent.ACTION_SEND)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            else
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    ?.let { uris += it }
        }
        val files = mutableListOf<SharedFile>()
        for (uri in uris) {
            // Past the limit, a URI is only counted: asking its provider would cost binder calls
            // for each of possibly hundreds of shared images.
            if (files.size >= MAX_FILES) {
                skipped++
                continue
            }
            val image =
                if (uri.scheme != "content" || access.own(uri) || !access.readable(uri)) null
                else kind(access.type(uri))?.takeIf { (access.size(uri) ?: 0L) <= AttachmentImportRules.FILE_BYTES }
            if (image == null) skipped++ else files += SharedFile(uri, image)
        }
        val raw = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull() ?: clipText
        val text = raw?.trim()?.takeIf { it.isNotEmpty() }
        val fits = text?.takeIf { it.toByteArray().size <= MAX_TEXT_BYTES }
        if (text != null && fits == null) skipped++
        return SharedContent(fits, files, skipped)
    }

    /** How many more files a draft holding [attachments] files can take. */
    fun room(attachments: Int): Int = (MAX_FILES - attachments).coerceAtLeast(0)
}

/** "2 files and text", or what part of it there is, for the share picker's header. */
internal fun shareSummary(context: Context, content: SharedContent): String {
    val files =
        content.files.takeIf { it.isNotEmpty() }?.let {
            context.resources.getQuantityString(R.plurals.remote_share_files, it.size, it.size)
        }
    return when {
        files != null && content.text != null -> context.getString(R.string.remote_share_files_and_text, files)
        files != null -> files
        content.text != null -> context.getString(R.string.remote_share_text)
        else -> ""
    }
}

/** The bundle form of a [ShareRequest], for MainActivity's saved state. */
internal fun Bundle.putShare(prefix: String, request: ShareRequest) {
    val content = request.content
    putParcelableArrayList("${prefix}Uris", ArrayList(content.files.map { it.uri }))
    putBooleanArray("${prefix}Images", content.files.map { it.image }.toBooleanArray())
    content.text?.let { putString("${prefix}Text", it) }
    when (val target = request.target) {
        is ShareTarget.Session -> {
            putString("${prefix}Target", "session")
            putString("${prefix}Route", target.routeId)
            target.projectId?.let { putString("${prefix}Project", it) }
            putString("${prefix}Session", target.sessionId)
        }
        is ShareTarget.NewSession -> {
            putString("${prefix}Target", "new")
            putString("${prefix}Route", target.routeId)
            putString("${prefix}Project", target.projectId)
        }
        ShareTarget.Pick -> putString("${prefix}Target", "pick")
    }
}

internal fun Bundle.share(prefix: String): ShareRequest? {
    val kind = getString("${prefix}Target") ?: return null
    val uris = BundleCompat.getParcelableArrayList(this, "${prefix}Uris", Uri::class.java).orEmpty()
    val images = getBooleanArray("${prefix}Images") ?: BooleanArray(0)
    if (uris.size != images.size) return null
    val content = SharedContent(getString("${prefix}Text"), uris.mapIndexed { index, uri -> SharedFile(uri, images[index]) })
    val route = getString("${prefix}Route")
    val target =
        when (kind) {
            "session" -> ShareTarget.Session(route ?: return null, getString("${prefix}Project"), getString("${prefix}Session") ?: return null)
            "new" -> ShareTarget.NewSession(route ?: return null, getString("${prefix}Project") ?: return null)
            "pick" -> ShareTarget.Pick
            else -> return null
        }
    return ShareRequest(content, target)
}

/**
 * Puts [content] into the chat [chat] once it can take it: the text into the draft as soon as the
 * chat is selected, the files through the attachment import once it is connected and idle. Images
 * and other files go in two runs, since the import takes one kind per call. Returns false when
 * [chat] is not, or stops being, the selected chat before everything was handed over. Leaving
 * the chat cancels the caller's job; files not handed over by then are reported as dropped.
 */
internal suspend fun deliverShare(repository: RemoteRepository, content: SharedContent, chat: RemoteSelection): Boolean {
    suspend fun awaitChat(ready: (RemoteState) -> Boolean): RemoteState? =
        repository.state.first { it.selection != chat || ready(it) }.takeIf { it.selection == chat }
    val groups =
        listOf(true, false)
            .map { image -> content.files.filter { it.image == image }.map { it.uri.toString() } to image }
            .filter { it.first.isNotEmpty() }
    var handed = 0
    try {
        if (repository.state.value.selection != chat) return false
        content.text?.let { text ->
            awaitChat { !it.loading } ?: return false
            val draft = prefilledDraft(repository.state.value.draft, text)
            if (draft.toByteArray().size > ShareIntake.MAX_TEXT_BYTES) repository.reportError(R.string.remote_share_text_too_long)
            else repository.draft(draft)
        }
        for ((uris, photo) in groups) {
            val ready =
                awaitChat { !it.loading && it.connected && !it.importingAttachments && !it.sending } ?: return false
            val batch = uris.take(ShareIntake.room(ready.attachments.size))
            if (batch.size < uris.size) repository.reportError(R.string.remote_attachment_limits)
            handed++
            if (batch.isEmpty()) continue
            repository.importAttachments(chat, batch, photo)
            // The import marks itself running at once, or refused and said why.
            if (!repository.state.value.importingAttachments) return true
            awaitChat { !it.importingAttachments } ?: return true
        }
        return true
    } catch (e: CancellationException) {
        if (handed < groups.size) repository.reportError(R.string.remote_share_dropped)
        throw e
    }
}

/**
 * Photos taken from the composer (ActivityResultContracts.TakePicture). The camera app writes
 * into a file of the app's cache, shared through the exports FileProvider; the import copies it
 * into the encrypted attachment store, and the capture is deleted once the import is over or did
 * not take it. A capture whose chat closed first is left for [sweep], which deletes captures older
 * than [KEEP_MILLIS] on app start and before each capture: the import reads a capture after the
 * result arrives, possibly in a process restarted behind the camera.
 */
internal object PhotoCapture {
    const val DIRECTORY = "camera"
    private const val KEEP_MILLIS = 60 * 60_000L

    /** Deletes captures older than [KEEP_MILLIS]. Blocking. */
    fun sweep(cacheDir: File, now: Long = System.currentTimeMillis()) {
        File(cacheDir, DIRECTORY).listFiles()?.filter { now - it.lastModified() > KEEP_MILLIS }?.forEach { it.delete() }
    }

    /** A new, empty file for the camera to write, as a content URI the camera app may write. Blocking. */
    fun newUri(context: Context, now: Long = System.currentTimeMillis()): Uri {
        val dir = File(context.cacheDir, DIRECTORY)
        check(dir.isDirectory || dir.mkdirs()) { "Camera directory unavailable" }
        sweep(context.cacheDir, now)
        val name = "photo-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } + ".jpg"
        val file = File(dir, name)
        check(file.createNewFile()) { "Camera file unavailable" }
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    /** Deletes the capture behind [uri] (no photo was taken, or the import is done with it). Blocking. */
    fun discard(context: Context, uri: String) {
        val name = uri.toUri().lastPathSegment ?: return
        if (!name.startsWith("photo-")) return
        File(File(context.cacheDir, DIRECTORY), name).delete()
    }

    /** [discard] off the main thread, independent of the screen that asked. */
    fun discardLater(context: Context, uri: String) {
        val app = context.applicationContext
        Thread { runCatching { discard(app, uri) } }.start()
    }

    /**
     * The camera's answer: a taken photo goes to [accept] for the chat it was taken in, as one
     * photo of the batch import, with a callback for when the import is done with it; the capture
     * is discarded then, or at once when nothing was taken or [accept] did not take it.
     */
    fun onResult(
        saved: Boolean,
        uri: String?,
        selection: RemoteSelection?,
        accept: (RemoteSelection, List<String>, Boolean, () -> Unit) -> Boolean,
        discard: (String) -> Unit,
    ) {
        if (uri == null) return
        if (saved && selection != null && accept(selection, listOf(uri), true) { discard(uri) }) return
        discard(uri)
    }
}
