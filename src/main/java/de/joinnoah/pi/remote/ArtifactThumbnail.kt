package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** The page is laid out this many CSS pixels wide, then scaled down to the card. */
internal const val THUMBNAIL_VIEWPORT_DP = 390

/** Card width over height of a thumbnail. */
internal const val THUMBNAIL_ASPECT = 16f / 10f

internal const val THUMBNAIL_SETTLE_MS = 700L
internal const val THUMBNAIL_TIMEOUT_MS = 5_000L

/** A memory-only cache of captured thumbnails and the limit on how many sandbox views run at once. */
internal object ArtifactThumbnails {
    data class Key(val kind: ArtifactKind, val sha256: String, val widthPx: Int, val dark: Boolean)

    private const val CAPACITY_BYTES = 8 * 1024 * 1024
    private const val MAX_LIVE_VIEWS = 2

    /** At most [MAX_LIVE_VIEWS] thumbnail WebViews exist at once; other cards wait with a skeleton. */
    val slots = Semaphore(MAX_LIVE_VIEWS)

    // Made on first use: the repository clears this on disconnect, also where no Android runtime exists.
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

    @Synchronized fun clear() {
        cache?.evictAll()
    }
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Draws [view], laid out at [logicalWidth] x [logicalHeight], into a bitmap of [width] x [height] over
 * [background]. Null if there is no memory for it.
 */
internal fun captureWebView(
    view: View,
    logicalWidth: Int,
    logicalHeight: Int,
    width: Int,
    height: Int,
    background: Int,
): Bitmap? =
    try {
        val full = Bitmap.createBitmap(logicalWidth, logicalHeight, Bitmap.Config.ARGB_8888)
        Canvas(full).also {
            it.drawColor(background)
            view.draw(it)
        }
        val scaled = Bitmap.createScaledBitmap(full, width, height, true)
        if (scaled !== full) full.recycle()
        scaled
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }

/** One live capture: the page, the signals it sends back, and the view once it exists. */
internal class ThumbnailCapture(val page: ArtifactPage) {
    private val loaded = CompletableDeferred<Boolean>()
    var view: WebView? = null

    val callbacks =
        ArtifactCallbacks(
            // Mermaid is only drawn once its title says so; a plain page is ready when it has loaded.
            onLoaded = { if (page.kind == ArtifactKind.Html) loaded.complete(true) },
            onTitle = {
                if (page.kind == ArtifactKind.Mermaid)
                    when (it) {
                        "ok" -> loaded.complete(true)
                        "error" -> loaded.complete(false)
                    }
            },
            onFailed = { loaded.complete(false) },
        )

    /** Waits for the page, lets it settle, and draws it. Null on failure or when nothing rendered. */
    suspend fun capture(logicalWidth: Int, logicalHeight: Int, width: Int, height: Int, background: Int): Bitmap? {
        val ready =
            withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
                loaded.await().also { if (it) delay(THUMBNAIL_SETTLE_MS) }
            }
        // On the hard timeout a plain page is drawn as far as it got; a diagram that is not drawn is not.
        if (ready == false || (ready == null && page.kind == ArtifactKind.Mermaid)) return null
        val target = view ?: return null
        return captureWebView(target, logicalWidth, logicalHeight, width, height, background)
    }
}

private sealed interface ThumbnailPhase {
    data object Waiting : ThumbnailPhase
    class Ready(val bitmap: Bitmap) : ThumbnailPhase
    data object Failed : ThumbnailPhase
}

/**
 * A live preview of an artifact as a picture. A sandboxed WebView is composed only while the picture
 * is made (at most two at a time, none for a cached picture), captured once, and disposed; the card
 * then shows the bitmap. [failed] is shown when the page could not be drawn. The card is not
 * interactive itself: [onClick] sits above the view so touches never reach the page.
 */
@Composable
internal fun ArtifactThumbnail(
    kind: ArtifactKind,
    text: String,
    sha256: String,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    failed: @Composable () -> Unit,
) {
    val dark = kind == ArtifactKind.Mermaid && MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = if (kind == ArtifactKind.Html) android.graphics.Color.WHITE else MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT).clipToBounds().testTag("artifactThumbnail")) {
        val widthPx = constraints.maxWidth
        val heightPx = (widthPx / THUMBNAIL_ASPECT).toInt().coerceAtLeast(1)
        val logicalWidth = with(density) { THUMBNAIL_VIEWPORT_DP.dp.roundToPx() }
        val logicalHeight = (logicalWidth / THUMBNAIL_ASPECT).toInt()
        val key = ArtifactThumbnails.Key(kind, sha256, widthPx, dark)
        var phase by remember(key) { mutableStateOf<ThumbnailPhase>(ArtifactThumbnails[key]?.let { ThumbnailPhase.Ready(it) } ?: ThumbnailPhase.Waiting) }
        var live by remember(key) { mutableStateOf<ThumbnailCapture?>(null) }
        LaunchedEffect(key) {
            if (phase != ThumbnailPhase.Waiting) return@LaunchedEffect
            ArtifactThumbnails.slots.withPermit {
                val capture = ThumbnailCapture(ArtifactPage(kind, text, dark))
                live = capture
                try {
                    val bitmap = capture.capture(logicalWidth, logicalHeight, widthPx, heightPx, background)
                    phase =
                        if (bitmap == null) ThumbnailPhase.Failed
                        else {
                            ArtifactThumbnails[key] = bitmap
                            ThumbnailPhase.Ready(bitmap)
                        }
                } finally {
                    live = null
                }
            }
        }
        when (val current = phase) {
            is ThumbnailPhase.Ready ->
                Image(
                    current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopStart,
                    modifier = Modifier.fillMaxSize().testTag("artifactThumbnailPicture"),
                )
            ThumbnailPhase.Failed -> Box(Modifier.fillMaxSize(), Alignment.Center) { failed() }
            ThumbnailPhase.Waiting -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                live?.let { capture ->
                    AndroidView(
                        factory = { context ->
                            createArtifactWebView(context, capture.page, capture.callbacks, background).also {
                                capture.view = it
                                it.isFocusable = false
                                it.isFocusableInTouchMode = false
                                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                it.setOnTouchListener { _, _ -> true }
                            }
                        },
                        onRelease = {
                            capture.view = null
                            disposeArtifactWebView(it)
                        },
                        modifier =
                            Modifier.clearAndSetSemantics {}
                                .layout { measurable, incoming ->
                                    // The page lays out at the logical size and is drawn scaled into the card.
                                    val placeable = measurable.measure(Constraints.fixed(logicalWidth, logicalHeight))
                                    val scale = incoming.maxWidth.toFloat() / logicalWidth
                                    layout(incoming.maxWidth, incoming.maxHeight) {
                                        placeable.placeWithLayer(0, 0) {
                                            scaleX = scale
                                            scaleY = scale
                                            transformOrigin = TransformOrigin(0f, 0f)
                                        }
                                    }
                                },
                    )
                }
            }
        }
        if (onClick != null)
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick))
    }
}
