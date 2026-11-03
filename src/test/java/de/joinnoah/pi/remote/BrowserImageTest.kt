package de.joinnoah.pi.remote

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserImageTest {
    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(format, 100, out))
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun image(bytes: ByteArray = encoded(Bitmap.CompressFormat.JPEG), width: Int = 4, height: Int = 2) =
        BrowserImage(bytes, width, height, 400, 200, 1.0, 12.0, 25.0, 1L, 1L)

    @Test fun validJpegDecodesToDeclaredDimensions() {
        val bitmap = requireNotNull(decodeBrowserImage(image()))
        assertEquals(4, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        bitmap.recycle()
    }

    @Test fun rejectsInvalidBytesAndPngMasqueradingAsJpeg() {
        assertNull(decodeBrowserImage(image(byteArrayOf(1, 2, 3))))
        assertNull(decodeBrowserImage(image(encoded(Bitmap.CompressFormat.PNG))))
    }

    @Test fun rejectsMismatchedAndOversizedDimensionsAndBytes() {
        val jpeg = encoded(Bitmap.CompressFormat.JPEG)
        assertNull(decodeBrowserImage(image(jpeg, width = 5)))
        assertNull(decodeBrowserImage(image(jpeg, width = 6144, height = 6144)))
        assertNull(decodeBrowserImage(image(jpeg, width = 6145)))
        assertNull(decodeBrowserImage(image(jpeg, width = 0)))
        assertNull(decodeBrowserImage(image(ByteArray(1048577))))
    }

    @Test fun containFitCentersPortraitAndLandscapeImages() {
        assertEquals(BrowserImageFit(50f, 0f, 100f, 200f), browserImageFit(200f, 200f, 100, 200))
        assertEquals(BrowserImageFit(0f, 50f, 200f, 100f), browserImageFit(200f, 200f, 200, 100))
        assertNull(browserImageFit(Float.NaN, 100f, 1, 1))
        assertNull(browserImageFit(0f, 100f, 1, 1))
        assertNull(browserImageFit(100f, 100f, 0, 1))
    }

    @Test fun pointsMapToViewportCssCoordinatesAndRejectLetterboxAndEdges() {
        val fit = BrowserImageFit(50f, 0f, 100f, 200f)
        assertEquals(0.0 to 0.0, browserImagePoint(fit, 50f, 0f, 400, 800))
        assertEquals(200.0 to 400.0, browserImagePoint(fit, 100f, 100f, 400, 800))
        assertNull(browserImagePoint(fit, 49f, 100f, 400, 800))
        assertNull(browserImagePoint(fit, 150f, 100f, 400, 800))
        assertNull(browserImagePoint(fit, 100f, 200f, 400, 800))
        assertNull(browserImagePoint(fit, Float.NaN, 100f, 400, 800))
        assertNull(browserImagePoint(fit, 100f, 100f, 0, 800))
    }
}
