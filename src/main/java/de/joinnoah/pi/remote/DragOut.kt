package de.joinnoah.pi.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes files dragged into other apps to `cacheDir/drag/<content>/<name>`, a folder the export
 * FileProvider serves. Each file sits in a folder named by a hash of its bytes, so two files with
 * the same name (`before/screen.png`, `after/screen.png`) never overwrite each other, and a file a
 * drag already offers never changes under it. It is separate from the shared images and artifacts,
 * so a drag never deletes a file a share is still offering. Only the latest [KEEP] folders stay;
 * [clear] runs at app start.
 */
internal object DragStorage {
    const val DIRECTORY = "drag"
    const val KEEP = 8

    /** Deletes every dragged file. Blocking. */
    @Synchronized
    fun clear(cacheDir: File) {
        File(cacheDir, DIRECTORY).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** The folder name for [bytes]: the first 128 bits of their SHA-256, in hex. */
    fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }

    /**
     * Marks [file] as just used, so the rotation keeps it longest; false when the rotation has
     * already deleted it. Blocking.
     */
    @Synchronized
    fun keep(file: File): Boolean {
        if (!file.isFile) return false
        file.parentFile?.setLastModified(System.currentTimeMillis())
        return true
    }

    /** Writes [bytes] as [name], keeping only the newest [KEEP] folders, and returns the file. Blocking. */
    @Synchronized
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIRECTORY)
        val folder = File(dir, contentKey(bytes))
        check(folder.isDirectory || folder.mkdirs()) { "Drag directory unavailable" }
        val file = File(folder, name)
        require(file.canonicalFile.parentFile == folder.canonicalFile) { "Unsafe drag file" }
        // The same bytes under the same name are already there: a drop may still read them.
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            // Written outside the served folder, so a half-written file is never offered.
            val temporary = File.createTempFile("drag-", ".tmp", cacheDir)
            try {
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
            } finally {
                temporary.delete()
            }
            check(file.length() == bytes.size.toLong()) { "Drag file could not be written" }
        }
        folder.setLastModified(System.currentTimeMillis())
        dir.listFiles()
            ?.filter { it != folder }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.deleteRecursively() }
        return file
    }
}

/**
 * What a drag carries, and the file in the drag folder it offers, if any: the rotation may delete
 * that file while the element waits, and [ready] notices it before the drag starts.
 */
internal class DragPayload(val data: DragAndDropTransferData, val file: File? = null) {
    /** False when the offered file is gone. Touches the disk. */
    val ready: Boolean get() = file?.isFile ?: true
}

/**
 * Marks a drag that starts in PocketPi, as the drag's local state: the chat's own drop target
 * leaves it alone, since it is meant for another app. Other apps never see the local state.
 */
internal object OwnDrag

/** A text drag (a file path) reaches any app; nothing needs a grant. */
internal const val DRAG_TEXT_FLAGS = View.DRAG_FLAG_GLOBAL

/** A file drag reaches any app, which may read the file's URI while it holds the drop. */
internal const val DRAG_FILE_FLAGS = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ

/** The clip of a dragged path: plain text, so a note, a terminal or a browser takes it as typed. */
internal fun pathClip(path: String): ClipData = ClipData.newPlainText(path.substringAfterLast('/'), path)

/** The clip of a dragged file: its content URI, typed so a drop target knows what it gets. */
internal fun fileClip(label: String, uri: Uri, mimeType: String): ClipData =
    ClipData(ClipDescription(label, arrayOf(mimeType)), ClipData.Item(uri))

internal fun pathTransfer(path: String) =
    DragPayload(DragAndDropTransferData(pathClip(path), localState = OwnDrag, flags = DRAG_TEXT_FLAGS))

/**
 * Writes [bytes] under [name] in the drag folder and returns the drag of its content URI, labelled
 * [label] and typed [mimeType]. Blocking.
 */
internal fun dragFile(context: Context, label: String, name: String, bytes: ByteArray, mimeType: String): DragPayload {
    val file = DragStorage.write(context.cacheDir, name, bytes)
    val uri = FileProvider.getUriForFile(context, exportAuthority(context), file)
    return DragPayload(
        DragAndDropTransferData(fileClip(label, uri, mimeType), localState = OwnDrag, flags = DRAG_FILE_FLAGS),
        file,
    )
}

/**
 * Lets this element be dragged into another app (split screen, DeX, a second window). A mouse
 * drags by pressing and moving; a finger or the S Pen by a long press and then moving, so a long
 * press alone keeps its own action (peek, menu) and a swipe still scrolls. [prepare] builds what
 * is dragged; it runs as soon as a pointer hovers or presses the element, ahead of the drag, so a
 * file it writes is ready when the drag starts, and again after [key] changes or once the drag
 * folder's rotation deleted that file. A drag that starts before it is ready, or when it returns
 * null, does nothing.
 */
internal fun Modifier.dragOut(
    key: Any?,
    enabled: Boolean = true,
    prepare: suspend () -> DragPayload?,
): Modifier = if (!enabled) this else this then DragOutElement(key, prepare)

private data class DragOutElement(
    val key: Any?,
    val prepare: suspend () -> DragPayload?,
) : ModifierNodeElement<DragOutNode>() {
    override fun create() = DragOutNode(key, prepare)

    override fun update(node: DragOutNode) = node.update(key, prepare)

    override fun InspectorInfo.inspectableProperties() {
        name = "dragOut"
        properties["key"] = key
    }
}

private class DragOutNode(
    private var key: Any?,
    private var prepare: suspend () -> DragPayload?,
) : DelegatingNode(), DrawModifierNode {
    private var prepared: DragPayload? = null
    private var preparing: Job? = null
    /** The element's last drawing, which the system shows under the pointer while dragging. */
    private var shadow: GraphicsLayer? = null
    private var shadowSize = Size.Zero

    private val source =
        delegate(
            DragAndDropSourceModifierNode {
                val data = prepared?.takeIf { it.ready }?.data ?: return@DragAndDropSourceModifierNode
                val layer = shadow
                startDragAndDropTransfer(data, shadowSize) { layer?.let { drawLayer(it) } }
            }
        )

    init {
        // Hover and press only look: the element's own clicks and the list's scroll see them too.
        // After a press the scope waits for the release; a hover has no pointer down.
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Enter || event.type == PointerEventType.Press) refresh()
                }
            }
        )
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    // The element's click takes the press too; the drag starts only on a move.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start =
                        if (down.type == PointerType.Mouse) down
                        else awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val moved =
                        awaitTouchSlopOrCancellation(start.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                    val payload = prepared ?: return@awaitEachGesture
                    // The rotation deleted the file while the element waited: write it again.
                    if (payload.ready) source.requestDragAndDropTransfer(moved.position)
                    else if (prepared === payload) {
                        preparing?.cancel()
                        preparing = null
                        prepared = null
                        warmUp()
                    }
                }
            }
        )
    }

    fun update(key: Any?, prepare: suspend () -> DragPayload?) {
        this.prepare = prepare
        if (key == this.key) return
        this.key = key
        preparing?.cancel()
        preparing = null
        prepared = null
    }

    /**
     * Gets the drag ready on a hover or a press: prepares it the first time, and later marks its
     * file as just used, so other elements' drags do not rotate it out, or prepares it again when
     * they already did.
     */
    private fun refresh() {
        val payload = prepared ?: return warmUp()
        val file = payload.file ?: return
        if (preparing?.isActive == true) return
        preparing =
            coroutineScope.launch {
                val kept = withContext(Dispatchers.IO) { DragStorage.keep(file) }
                if (!kept && prepared === payload) {
                    prepared = null
                    preparing = null
                    warmUp()
                }
            }
    }

    private fun warmUp() {
        if (prepared != null || preparing?.isActive == true) return
        val forKey = key
        preparing =
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val data =
                    try {
                        prepare()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Diagnostics.caught("dragOut.prepare", e)
                        null
                    }
                if (forKey == key) prepared = data
            }
    }

    override fun ContentDrawScope.draw() {
        val layer = shadow ?: requireGraphicsContext().createGraphicsLayer().also { shadow = it }
        shadowSize = size
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }

    override fun onDetach() {
        shadow?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        shadow = null
        preparing = null
        prepared = null
    }
}
