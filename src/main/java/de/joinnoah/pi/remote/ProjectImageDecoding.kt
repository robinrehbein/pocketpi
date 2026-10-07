package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Longest edge of an image card in the chat. */
internal const val PROJECT_IMAGE_CARD_EDGE = 1024

/** Longest edge of the full-screen view; also the cap for a rasterised SVG. */
internal const val PROJECT_IMAGE_VIEWER_EDGE = SENT_IMAGE_LARGE_EDGE

private const val SVG_TIMEOUT_MILLIS = 5_000L
private const val SVG_RENDER_THREADS = 2
private const val SVG_THREAD_STACK_BYTES = 8L * 1024 * 1024
private const val MAX_SVG_ELEMENTS = 50_000
private const val MAX_SVG_DEPTH = 200
private const val MAX_FAILED_SVGS = 64
private val svgEncoding = Regex("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

/**
 * True when [bytes] may be handed to the SVG parser: at most [MAX_MEDIA_SVG_BYTES], no UTF-16/32
 * text (a NUL in the first bytes), an XML declaration that names no encoding but UTF-8, neither an
 * `<!ENTITY` nor a DOCTYPE internal subset, and a tree of at most [MAX_SVG_ELEMENTS] elements nested
 * at most [MAX_SVG_DEPTH] deep (the parser and renderer recurse). The host refuses some of these
 * too; this is the second line.
 */
internal fun safeSvgBytes(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_MEDIA_SVG_BYTES) return false
    if (bytes.take(64).any { it == 0.toByte() }) return false
    val text = String(bytes, Charsets.ISO_8859_1)
    svgEncoding.find(text.take(512))?.let { if (!it.groupValues[1].equals("utf-8", ignoreCase = true)) return false }
    if (text.contains("<!ENTITY")) return false
    val doctype = text.indexOf("<!DOCTYPE")
    if (doctype >= 0) {
        val end = text.indexOf('>', doctype)
        val subset = text.indexOf('[', doctype)
        if (subset >= 0 && (end < 0 || subset < end)) return false
    }
    return svgStructureWithinLimits(text)
}

/** One linear pass over the tags; comments, CDATA, processing instructions and quoted `>` are skipped. */
private fun svgStructureWithinLimits(text: String): Boolean {
    var index = 0
    var depth = 0
    var elements = 0
    while (true) {
        index = text.indexOf('<', index)
        if (index < 0 || index + 1 >= text.length) return true
        when {
            text.startsWith("<!--", index) -> {
                val end = text.indexOf("-->", index + 4)
                if (end < 0) return true
                index = end + 3
            }
            text.startsWith("<![CDATA[", index) -> {
                val end = text.indexOf("]]>", index + 9)
                if (end < 0) return true
                index = end + 3
            }
            text[index + 1] == '?' || text[index + 1] == '!' -> {
                val end = text.indexOf('>', index)
                if (end < 0) return true
                index = end + 1
            }
            else -> {
                val closing = text[index + 1] == '/'
                var cursor = index + 1
                var quote = '\u0000'
                while (cursor < text.length) {
                    val c = text[cursor]
                    if (quote != '\u0000') { if (c == quote) quote = '\u0000' }
                    else if (c == '"' || c == '\'') quote = c
                    else if (c == '>') break
                    cursor++
                }
                if (cursor >= text.length) return true
                if (closing) depth = maxOf(0, depth - 1)
                else {
                    if (++elements > MAX_SVG_ELEMENTS) return false
                    if (text[cursor - 1] != '/') if (++depth > MAX_SVG_DEPTH) return false
                }
                index = cursor + 1
            }
        }
    }
}

/**
 * Rasterises [bytes] with a longest edge of [maxEdge] (the area never exceeds `maxEdge²`), or
 * returns null for a malformed or unsafe SVG, including one that overflows the stack or memory.
 * No file resolver is ever registered, so an external `<image href>` and a CSS `@import` are
 * skipped (AndroidSVG only calls a resolver that exists) and scripts do not exist in this renderer;
 * internal entities are switched off. Blocking.
 */
internal fun renderSvg(bytes: ByteArray, maxEdge: Int): Bitmap? {
    if (!safeSvgBytes(bytes)) return null
    return try {
        SVG.setInternalEntitiesEnabled(false)
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // StackOverflowError and OutOfMemoryError included: a hostile file must not crash the app.
        null
    }
}

/**
 * Renders SVGs on at most [threads] threads with a large stack, each for at most [timeoutMillis].
 * A parser cannot be interrupted, so a render that times out keeps its thread; to bound the damage
 * the bytes of a render that failed or timed out (by SHA-256) are never rendered again in this
 * process, and a request that finds every thread busy fails at once without being remembered.
 */
internal class BoundedSvgRenderer(
    threads: Int = SVG_RENDER_THREADS,
    private val timeoutMillis: Long = SVG_TIMEOUT_MILLIS,
    private val renderer: (ByteArray, Int) -> Bitmap? = ::renderSvg,
) {
    private val pool =
        ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, SynchronousQueue()) { runnable ->
            Thread(null, runnable, "svg-render", SVG_THREAD_STACK_BYTES).apply { isDaemon = true }
        }
    private val failed =
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_FAILED_SVGS
            }
        )

    fun hasFailed(sha256: String): Boolean = synchronized(failed) { sha256 in failed }

    suspend fun render(sha256: String, bytes: ByteArray, maxEdge: Int): Bitmap? {
        if (hasFailed(sha256)) return null
        val task =
            try {
                pool.submit(Callable { renderer(bytes, maxEdge) })
            } catch (e: RejectedExecutionException) {
                return null
            }
        return try {
            runInterruptible(Dispatchers.IO) { task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
                .also { if (it == null) remember(sha256) }
        } catch (e: TimeoutException) {
            remember(sha256)
            null
        } catch (e: ExecutionException) {
            remember(sha256)
            null
        } finally {
            task.cancel(true)
        }
    }

    private fun remember(sha256: String) {
        synchronized(failed) { failed += sha256 }
    }
}

internal val projectSvgRenderer = BoundedSvgRenderer()

/**
 * Decodes a loaded image for display with a longest edge of at most [maxEdge], or null when it
 * is malformed. A GIF shows its first frame. Never decodes on the caller's thread.
 */
internal suspend fun decodeProjectImage(image: ProjectImageResult.Loaded, maxEdge: Int): Bitmap? =
    if (image.isSvg) projectSvgRenderer.render(image.sha256, image.bytes, maxEdge)
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

    /** Drops every size of [path] whose digest is not [keep]: a fresh read replaced the file. */
    fun dropStale(sessionId: String, path: String, keep: String) {
        val cache = synchronized(this) { cache } ?: return
        cache.snapshot().keys.filter { it.sessionId == sessionId && it.path == path && it.sha256 != keep }
            .forEach { cache.remove(it) }
    }

    @Synchronized
    fun clear() {
        cache?.evictAll()
    }
}
