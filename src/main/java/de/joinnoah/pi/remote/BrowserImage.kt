package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The caller owns the returned bitmap and must release it only after it is no longer displayed. */
internal fun decodeBrowserImage(image: BrowserImage): Bitmap? {
    if (image.jpeg.isEmpty() || image.jpeg.size > BROWSER_FRAME_MAX_BYTES ||
        image.width !in 1..6144 || image.height !in 1..6144 ||
        image.width.toLong() * image.height > BROWSER_FRAME_MAX_PIXELS
    ) return null

    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth != image.width || bounds.outHeight != image.height) {
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, options) ?: return null
        if (bitmap.width != image.width || bitmap.height != image.height || bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.recycle()
            null
        } else bitmap
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

internal data class BrowserImageFit(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun browserImageFit(containerWidth: Float, containerHeight: Float, imageWidth: Int, imageHeight: Int): BrowserImageFit? {
    if (!containerWidth.isFinite() || !containerHeight.isFinite() || containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
    val scale = minOf(containerWidth.toDouble() / imageWidth, containerHeight.toDouble() / imageHeight)
    val width = (imageWidth * scale).toFloat()
    val height = (imageHeight * scale).toFloat()
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return BrowserImageFit((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}

/** Returns viewport-relative CSS coordinates; scroll offsets are intentionally not added. */
internal fun browserImagePoint(fit: BrowserImageFit, x: Float, y: Float, cssWidth: Int, cssHeight: Int): Pair<Double, Double>? {
    if (!fit.left.isFinite() || !fit.top.isFinite() || !fit.width.isFinite() || !fit.height.isFinite() ||
        fit.width <= 0f || fit.height <= 0f || !x.isFinite() || !y.isFinite() || cssWidth <= 0 || cssHeight <= 0
    ) return null
    val relativeX = x.toDouble() - fit.left.toDouble()
    val relativeY = y.toDouble() - fit.top.toDouble()
    if (relativeX < 0.0 || relativeY < 0.0 || relativeX >= fit.width.toDouble() || relativeY >= fit.height.toDouble()) return null
    return (relativeX / fit.width * cssWidth) to (relativeY / fit.height * cssHeight)
}
