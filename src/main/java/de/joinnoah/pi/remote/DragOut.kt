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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Writes files dragged into other apps to `cacheDir/drag/`, a folder the export FileProvider
 * serves. It is separate from the shared images and artifacts, so a drag never deletes a file a
 * share is still offering. Only the latest few files stay; [clear] runs at app start.
 */
internal object DragStorage {
    const val DIRECTORY = "drag"
    const val KEEP = 8

    /** Deletes every dragged file. Blocking. */
    fun clear(cacheDir: File) {
        File(cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** Writes [bytes] as [name], keeping only the newest [KEEP] files, and returns the file. Blocking. */
    fun write(cacheDir: File, name: String, bytes: ByteArray): File {
        val dir = File(cacheDir, DIRECTORY)
        check(dir.isDirectory || dir.mkdirs()) { "Drag directory unavailable" }
        val file = File(dir, name)
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "Unsafe drag file" }
        // The file a drop may still read stays when the same file is dragged again.
        if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        dir.listFiles()
            ?.filter { it != file }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP - 1)
            ?.forEach { it.delete() }
        // Written outside the served folder, so a half-written file is never offered.
        val temporary = File(cacheDir, "drag-${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
        check(file.length() == bytes.size.toLong()) { "Drag file could not be written" }
        return file
    }
}

/** Writes [bytes] under [name] in the drag folder and returns its content URI. Blocking. */
internal fun storeDragFile(context: Context, name: String, bytes: ByteArray): Uri {
    val file = DragStorage.write(context.cacheDir, name, bytes)
    return FileProvider.getUriForFile(context, exportAuthority(context), file)
}

/** A text drag (a file path) reaches any app; nothing needs a grant. */
internal const val DRAG_TEXT_FLAGS = View.DRAG_FLAG_GLOBAL

/** A file drag reaches any app, which may read the file's URI while it holds the drop. */
internal const val DRAG_FILE_FLAGS = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ

/** The clip of a dragged path: plain text, so a note, a terminal or a browser takes it as typed. */
internal fun pathClip(path: String): ClipData = ClipData.newPlainText(path.substringAfterLast('/'), path)

/** The clip of a dragged file: its content URI, typed so a drop target knows what it gets. */
internal fun fileClip(label: String, uri: Uri, mimeType: String): ClipData =
    ClipData(ClipDescription(label, arrayOf(mimeType)), ClipData.Item(uri))

internal fun pathTransfer(path: String) = DragAndDropTransferData(pathClip(path), flags = DRAG_TEXT_FLAGS)

internal fun fileTransfer(label: String, uri: Uri, mimeType: String) =
    DragAndDropTransferData(fileClip(label, uri, mimeType), flags = DRAG_FILE_FLAGS)

/**
 * Lets this element be dragged into another app (split screen, DeX, a second window). A mouse
 * drags by pressing and moving; a finger or the S Pen by a long press and then moving, so a long
 * press alone keeps its own action (peek, menu) and a swipe still scrolls. [prepare] builds what
 * is dragged; it runs as soon as a pointer hovers or presses the element, ahead of the drag, so a
 * file it writes is ready when the drag starts, and again after [key] changes. A drag that starts
 * before it is ready, or when it returns null, does nothing.
 */
internal fun Modifier.dragOut(
    key: Any?,
    enabled: Boolean = true,
    prepare: suspend () -> DragAndDropTransferData?,
): Modifier = if (!enabled) this else this then DragOutElement(key, prepare)

private data class DragOutElement(
    val key: Any?,
    val prepare: suspend () -> DragAndDropTransferData?,
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
    private var prepare: suspend () -> DragAndDropTransferData?,
) : DelegatingNode(), DrawModifierNode {
    private var prepared: DragAndDropTransferData? = null
    private var preparing: Job? = null
    /** The element's last drawing, which the system shows under the pointer while dragging. */
    private var shadow: GraphicsLayer? = null
    private var shadowSize = Size.Zero

    private val source =
        delegate(
            DragAndDropSourceModifierNode {
                val data = prepared ?: return@DragAndDropSourceModifierNode
                val layer = shadow
                startDragAndDropTransfer(data, shadowSize) { layer?.let { drawLayer(it) } }
            }
        )

    init {
        // Hover and press only look: the element's own clicks and the list's scroll see them too.
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Enter || event.type == PointerEventType.Press) warmUp()
                    }
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
                    if (prepared != null) source.requestDragAndDropTransfer(moved.position)
                }
            }
        )
    }

    fun update(key: Any?, prepare: suspend () -> DragAndDropTransferData?) {
        this.prepare = prepare
        if (key == this.key) return
        this.key = key
        preparing?.cancel()
        preparing = null
        prepared = null
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
