package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Longest edge of an image card in the chat. */
internal const val PROJECT_IMAGE_CARD_EDGE = 1024

/** Longest edge of the full-screen view; also the cap for a rasterised SVG. */
internal const val PROJECT_IMAGE_VIEWER_EDGE = SENT_IMAGE_LARGE_EDGE

private const val SVG_TIMEOUT_SECONDS = 5L
private const val SVG_FALLBACK_EDGE = 1024

private val svgWorkers =
    Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "svg-render").apply { isDaemon = true }
    }

/**
 * True when [bytes] may be handed to the SVG parser: at most [MAX_MEDIA_SVG_BYTES], no UTF-16/32
 * text (a NUL in the first bytes), and neither an `<!ENTITY` nor a DOCTYPE internal subset. The
 * host refuses these too; this is the second line.
 */
internal fun safeSvgBytes(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_MEDIA_SVG_BYTES) return false
    if (bytes.take(64).any { it == 0.toByte() }) return false
    val text = String(bytes, Charsets.ISO_8859_1)
    if (text.contains("<!ENTITY")) return false
    val doctype = text.indexOf("<!DOCTYPE")
    if (doctype >= 0) {
        val end = text.indexOf('>', doctype)
        val subset = text.indexOf('[', doctype)
        if (subset >= 0 && (end < 0 || subset < end)) return false
    }
    return true
}

/**
 * Rasterises [bytes] with a longest edge of [maxEdge] (the area never exceeds `maxEdge²`), or
 * returns null for a malformed or unsafe SVG. No file resolver is ever registered, so an external
 * `<image href>` or stylesheet is not fetched and scripts do not exist in this renderer. Blocking.
 */
internal fun renderSvg(bytes: ByteArray, maxEdge: Int): Bitmap? {
    if (!safeSvgBytes(bytes)) return null
    return try {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val aspect =
            svg.documentAspectRatio.takeIf { it.isFinite() && it > 0f }
                ?: svg.documentWidth.takeIf { it > 0f }?.let { w -> svg.documentHeight.takeIf { it > 0f }?.let { w / it } }
                ?: 1f
        if (!aspect.isFinite() || aspect <= 0f) return null
        val edge = maxEdge.coerceIn(1, PROJECT_IMAGE_VIEWER_EDGE)
        val width = if (aspect >= 1f) edge else maxOf(1, (edge * aspect).toInt())
        val height = if (aspect >= 1f) maxOf(1, (edge / aspect).toInt()) else edge
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        svg.renderToPicture(width, height).let { Canvas(bitmap).drawPicture(it) }
        bitmap
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

/** [renderSvg] on its own thread, abandoned after [SVG_TIMEOUT_SECONDS]; the parser cannot be interrupted. */
private suspend fun renderSvgBounded(bytes: ByteArray, maxEdge: Int): Bitmap? =
    runInterruptible(Dispatchers.IO) {
        val task = svgWorkers.submit(Callable { renderSvg(bytes, maxEdge) })
        try {
            task.get(SVG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            null
        } finally {
            task.cancel(true)
        }
    }

/**
 * Decodes a loaded image for display with a longest edge of at most [maxEdge], or null when it
 * is malformed. A GIF shows its first frame. Never decodes on the caller's thread.
 */
internal suspend fun decodeProjectImage(image: ProjectImageResult.Loaded, maxEdge: Int): Bitmap? =
    if (image.isSvg) renderSvgBounded(image.bytes, maxEdge)
    else withContext(Dispatchers.Default) { decodeSentImage(image.bytes, maxEdge) }

/** Decoded agent images kept in memory only, so scrolling back does not decode again. */
internal object ProjectImageBitmaps {
    data class Key(val sessionId: String, val path: String, val sha256: String, val edge: Int)

    private const val CAPACITY_BYTES = 48 * 1024 * 1024
    private var cache: LruCache<Key, Bitmap>? = null

    @Synchronized
    private fun cache() =
        cache
            ?: object : LruCache<Key, Bitmap>(CAPACITY_BYTES) {
                    override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
                }
                .also { cache = it }

    operator fun get(key: Key): Bitmap? = cache()[key]

    operator fun set(key: Key, bitmap: Bitmap) {
        cache().put(key, bitmap)
    }

    @Synchronized
    fun clear() {
        cache?.evictAll()
    }
}
