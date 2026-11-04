package de.joinnoah.pi.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AppBackdrop(
    val image: ImageBitmap,
    val root: View,
    val windowSize: IntSize,
    val positionVersion: State<Int>,
)

/** Destination offset in screen pixels, or null if the captured window no longer fits. */
internal fun snapshotOffset(rootOrigin: IntOffset, surfaceOrigin: Offset, capturedSize: IntSize, currentSize: IntSize): IntOffset? =
    if (capturedSize != currentSize || currentSize.width <= 0 || currentSize.height <= 0) null
    else IntOffset((rootOrigin.x - surfaceOrigin.x).roundToInt(), (rootOrigin.y - surfaceOrigin.y).roundToInt())

// Three box passes at this radius create a deliberately stronger frost than the floating 24dp blur.
internal const val SNAPSHOT_BLUR_RADIUS_DP = 14f
internal fun snapshotSize(width: Int, height: Int): IntSize {
    val scale = maxOf(4f, maxOf(width, height) / 512f)
    return IntSize((width / scale).roundToInt().coerceAtLeast(1), (height / scale).roundToInt().coerceAtLeast(1))
}
internal fun snapshotBlurRadius(density: Float, windowWidth: Int, bitmapWidth: Int): Int =
    (SNAPSHOT_BLUR_RADIUS_DP * density * bitmapWidth / windowWidth.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)

/** Capture only the Activity window, never the popup's separate window. Call outside the popup. */
@Composable
internal fun rememberAppBackdrop(enabled: Boolean = true): AppBackdrop? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Build.FINGERPRINT == "robolectric") return null
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val activity = view.context.backdropActivity()
    val root = activity?.window?.decorView
    var rootSize by remember(root, enabled) { mutableStateOf(IntSize(root?.width ?: 0, root?.height ?: 0)) }
    val positionVersion = remember(root, enabled) { mutableIntStateOf(0) }
    DisposableEffect(root, enabled) {
        if (root == null || !enabled) onDispose { } else {
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            var x = location[0]
            var y = location[1]
            val layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    rootSize = IntSize(right - left, bottom - top)
                }
            }
            val preDraw = ViewTreeObserver.OnPreDrawListener {
                root.getLocationOnScreen(location)
                if (location[0] != x || location[1] != y) {
                    x = location[0]; y = location[1]
                    positionVersion.intValue++
                }
                true
            }
            root.addOnLayoutChangeListener(layout)
            val observer = root.viewTreeObserver
            observer.addOnPreDrawListener(preDraw)
            onDispose {
                root.removeOnLayoutChangeListener(layout)
                // Attach may merge listeners into a new observer and kill the captured one.
                val currentObserver = root.viewTreeObserver
                if (currentObserver.isAlive) currentObserver.removeOnPreDrawListener(preDraw)
                if (observer !== currentObserver && observer.isAlive) observer.removeOnPreDrawListener(preDraw)
            }
        }
    }
    var backdrop by remember(view, configuration, density, enabled, rootSize) { mutableStateOf<AppBackdrop?>(null) }
    LaunchedEffect(view, configuration, density, enabled, rootSize) {
        if (!enabled || activity == null || root == null || activity.isFinishing || activity.isDestroyed) return@LaunchedEffect
        delay(120) // Coalesce freeform resize steps, without recapturing on position changes.
        val window = activity.window
        // A sheet or dialog is a different window; the Activity snapshot would be the wrong backdrop.
        if (view.rootView !== root.rootView || !root.isAttachedToWindow || !root.isHardwareAccelerated || root.width <= 0 || root.height <= 0) return@LaunchedEffect
        val size = snapshotSize(root.width, root.height)
        val bitmap = runCatching { createBitmap(size.width, size.height) }.getOrNull() ?: return@LaunchedEffect
        val width = root.width
        val height = root.height
        val copied = withTimeoutOrNull(1000) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    PixelCopy.request(window, bitmap, { result ->
                        if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                    }, Handler(Looper.getMainLooper()))
                }.onFailure { if (continuation.isActive) continuation.resume(false) }
            }
        } == true
        if (!copied) return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) {
            runCatching {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                boxBlur(pixels, bitmap.width, bitmap.height, snapshotBlurRadius(density, width, bitmap.width))
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }.isSuccess
        }
        if (blurred && root.isAttachedToWindow && root.width == width && root.height == height) {
            backdrop = AppBackdrop(bitmap.asImageBitmap(), root, IntSize(width, height), positionVersion)
        }
    }
    return backdrop
}

private tailrec fun Context.backdropActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.backdropActivity()
    else -> null
}

/** Paint a screen-aligned snapshot behind content. Use after clipping to the container shape. */
internal fun Modifier.snapshotBackdrop(
    backdrop: AppBackdrop?, tint: Color, fallback: Color, scrim: Color = Color.Transparent,
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val location = remember { IntArray(2) }
    this.onGloballyPositioned { coordinates[0] = it; origin = it.positionOnScreen() }.drawBehind {
        val positionedOrigin = origin // Also invalidates drawing when the surface is laid out again.
        val offset = if (backdrop != null && backdrop.root.isAttachedToWindow) {
            backdrop.positionVersion.value // Invalidate drawing, not capture, on a window move.
            backdrop.root.getLocationOnScreen(location)
            val currentOrigin = coordinates[0]?.takeIf { it.isAttached }?.positionOnScreen() ?: positionedOrigin
            snapshotOffset(IntOffset(location[0], location[1]), currentOrigin, backdrop.windowSize,
                IntSize(backdrop.root.width, backdrop.root.height))
        } else null
        if (offset == null) drawRect(fallback) else {
            drawImage(backdrop!!.image, dstOffset = offset, dstSize = backdrop.windowSize)
            drawRect(scrim)
            drawRect(tint)
        }
    }
}

/** Draw the app copy aligned to the screen, then its scrim and tint. */
@Composable
internal fun BoxScope.AppBackdropLayer(
    backdrop: AppBackdrop, tint: Color, scrim: Color = Color.Transparent,
    fallback: Color = MaterialTheme.colorScheme.background,
) {
    Box(Modifier.matchParentSize().clipToBounds().snapshotBackdrop(backdrop, tint, fallback, scrim))
}
