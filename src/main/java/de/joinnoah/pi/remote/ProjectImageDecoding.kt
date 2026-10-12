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
private const val SVG_SLOT_WAIT_MILLIS = 4_000L
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
    val text = String(bytes, Charsets.ISO_8859_1).removePrefix("\u00EF\u00BB\u00BF")
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

/** What the bounded renderer answers: a picture, the verdict that these bytes cannot be drawn, or "try again". */
internal sealed interface SvgRender {
    class Ready(val bitmap: Bitmap) : SvgRender

    /** The bytes failed, crashed or timed out at this size; they are not tried again at that size. */
    data object Failed : SvgRender

    /** No render slot freed up in time, or every thread is stuck; says nothing about the bytes. */
    data object Busy : SvgRender
}

/** The outcome of [decodeProjectImage]. */
internal sealed interface ProjectImageDecode {
    class Ready(val bitmap: Bitmap) : ProjectImageDecode

    data object Malformed : ProjectImageDecode

    /** The SVG renderer had no free slot; the picture may be fine, so ask again. */
    data object Busy : ProjectImageDecode
}

internal fun ProjectImageDecode.bitmapOrNull(): Bitmap? = (this as? ProjectImageDecode.Ready)?.bitmap

/**
 * Renders SVGs on at most [threads] threads with a large stack, each for at most [timeoutMillis]. A
 * request waits up to [slotWaitMillis] for a free slot, so a valid SVG is not lost to a busy moment.
 * A parser cannot be interrupted, so a render that times out keeps its thread (and its slot) until
 * it ends; once every thread is stuck like that, requests answer [SvgRender.Busy] at once. The bytes
 * of a failed or timed-out render are remembered by (SHA-256, edge) and not rendered again at that
 * size in this process; [SvgRender.Busy] is never remembered.
 */
internal class BoundedSvgRenderer(
    private val threads: Int = SVG_RENDER_THREADS,
    private val timeoutMillis: Long = SVG_TIMEOUT_MILLIS,
    private val slotWaitMillis: Long = SVG_SLOT_WAIT_MILLIS,
    private val renderer: (ByteArray, Int) -> Bitmap? = ::renderSvg,
) {
    private val slots = java.util.concurrent.Semaphore(threads)
    private val stuck = java.util.concurrent.atomic.AtomicInteger()
    // At most [threads] tasks exist at once (one per permit), so the queue never overflows.
    private val pool =
        ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue()) { runnable ->
            Thread(null, runnable, "svg-render", SVG_THREAD_STACK_BYTES).apply { isDaemon = true }
        }
    private val failed =
        java.util.Collections.newSetFromMap(
            object : LinkedHashMap<Pair<String, Int>, Boolean>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, Boolean>?) =
                    size > MAX_FAILED_SVGS
            }
        )

    fun hasFailed(sha256: String, maxEdge: Int): Boolean = synchronized(failed) { (sha256 to maxEdge) in failed }

    /** Render slots in use, including those held by stuck threads; for tests. */
    fun slotsInUse(): Int = threads - slots.availablePermits()

    suspend fun render(sha256: String, bytes: ByteArray, maxEdge: Int): SvgRender {
        if (hasFailed(sha256, maxEdge)) return SvgRender.Failed
        if (stuck.get() >= threads) return SvgRender.Busy
        // A cancelled wait is interrupted before it holds a permit.
        if (!runInterruptible(Dispatchers.IO) { slots.tryAcquire(slotWaitMillis, TimeUnit.MILLISECONDS) }) return SvgRender.Busy
        // 0 running, 1 finished, 2 abandoned after the timeout (the thread is stuck until it ends).
        val state = java.util.concurrent.atomic.AtomicInteger(0)
        val task =
            try {
                pool.submit(
                    Callable {
                        try {
                            renderer(bytes, maxEdge)
                        } finally {
                            if (!state.compareAndSet(0, 1)) stuck.decrementAndGet()
                            slots.release()
                        }
                    }
                )
            } catch (e: RejectedExecutionException) {
                slots.release()
                return SvgRender.Busy
            }
        return try {
            val bitmap = runInterruptible(Dispatchers.IO) { task.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            if (bitmap == null) {
                remember(sha256, maxEdge)
                SvgRender.Failed
            } else SvgRender.Ready(bitmap)
        } catch (e: TimeoutException) {
            if (state.compareAndSet(0, 2)) stuck.incrementAndGet()
            remember(sha256, maxEdge)
            SvgRender.Failed
        } catch (e: ExecutionException) {
            remember(sha256, maxEdge)
            SvgRender.Failed
        } finally {
            task.cancel(true)
        }
    }

    private fun remember(sha256: String, maxEdge: Int) {
        synchronized(failed) { failed += sha256 to maxEdge }
    }
}

internal val projectSvgRenderer = BoundedSvgRenderer()

/**
 * Decodes a loaded image for display with a longest edge of at most [maxEdge]. A GIF shows its
 * first frame. Never decodes on the caller's thread.
 */
internal suspend fun decodeProjectImage(image: ProjectImageResult.Loaded, maxEdge: Int): ProjectImageDecode =
    if (image.isSvg)
        when (val result = projectSvgRenderer.render(image.sha256, image.bytes, maxEdge)) {
            is SvgRender.Ready -> ProjectImageDecode.Ready(result.bitmap)
            SvgRender.Failed -> ProjectImageDecode.Malformed
            SvgRender.Busy -> ProjectImageDecode.Busy
        }
    else
        withContext(Dispatchers.Default) { decodeSentImage(image.bytes, maxEdge) }
            ?.let { ProjectImageDecode.Ready(it) } ?: ProjectImageDecode.Malformed

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
