package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Color
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProjectImageDecodingTest {
    private fun png(width: Int, height: Int, color: Int = Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun loaded(bytes: ByteArray, mime: String) = ProjectImageResult.Loaded("a.x", mime, "0".repeat(64), bytes)

    /** The package-private resolver registry of AndroidSVG; null means nothing external is ever read. */
    private fun fileResolver(): Any? =
        SVG::class.java.getDeclaredMethod("getFileResolver").apply { isAccessible = true }.invoke(null)

    private fun svg(body: String, attributes: String = "width=\"40\" height=\"20\"") =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" $attributes>$body</svg>".toByteArray()

    @Test
    fun rasterImagesAreBoundedByTheEdge() = runTest {
        val bitmap = decodeProjectImage(loaded(png(3000, 1000), "image/png"), 1024)!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1024)
        assertNull(decodeProjectImage(loaded(ByteArray(64) { 7 }, "image/png"), 1024))
        // 60 megapixels are refused without decoding.
        assertNull(decodeProjectImage(loaded(png(10000, 6000, Color.WHITE), "image/png"), 1024))
    }

    @Test
    fun aGifShowsItsFirstFrame() = runTest {
        // A minimal 1x1 GIF89a with one frame.
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
        val bitmap = decodeProjectImage(loaded(gif, "image/gif"), 1024)
        assertNotNull(bitmap)
        assertEquals(1, bitmap!!.width)
    }

    @Test
    fun anSvgIsRasterisedWithinTheAreaBound() = runTest {
        val wide = renderSvg(svg("<rect width=\"40\" height=\"20\" fill=\"red\"/>"), 800)!!
        assertEquals(800, wide.width)
        assertEquals(400, wide.height)
        val tall = renderSvg(svg("<rect width=\"1\" height=\"1\"/>", "width=\"1\" height=\"100000\""), PROJECT_IMAGE_VIEWER_EDGE)!!
        assertTrue(tall.width >= 1 && tall.height <= PROJECT_IMAGE_VIEWER_EDGE)
        assertTrue(wide.width.toLong() * wide.height <= PROJECT_IMAGE_VIEWER_EDGE.toLong() * PROJECT_IMAGE_VIEWER_EDGE)
        assertNotNull(decodeProjectImage(loaded(svg("<circle r=\"5\"/>"), "image/svg+xml"), 256))
    }

    @Test
    fun scriptsAndExternalImagesRenderWithoutAnyResolver() = runTest {
        assertNull("the app never registers a file resolver", fileResolver())
        val hostile =
            svg(
                "<script>alert(1)</script><image href=\"http://127.0.0.1:9/x.png\" width=\"10\" height=\"10\"/>" +
                    "<image xlink:href=\"file:///etc/passwd\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"10\" height=\"10\"/>" +
                    "<style>@import url(http://127.0.0.1:9/x.css);</style><rect width=\"40\" height=\"20\" fill=\"blue\"/>",
            )
        assertNotNull(renderSvg(hostile, 200))
        assertNull(fileResolver())
    }

    @Test
    fun entitiesDoctypeSubsetsOversizeAndMalformedSvgAreRefused() = runTest {
        val entity = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"b\">]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        assertFalse(safeSvgBytes(entity.toByteArray()))
        assertNull(renderSvg(entity.toByteArray(), 100))
        val subset = "<!DOCTYPE svg [ ]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        assertFalse(safeSvgBytes(subset.toByteArray()))
        val plainDoctype =
            "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\"><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        assertTrue(safeSvgBytes(plainDoctype.toByteArray()))
        assertFalse(safeSvgBytes(ByteArray((MAX_MEDIA_SVG_BYTES + 1).toInt()) { 'a'.code.toByte() }))
        assertFalse(safeSvgBytes("\u0000<\u0000s\u0000v\u0000g".toByteArray(Charsets.UTF_16LE)))
        assertNull(renderSvg("<svg".toByteArray(), 100))
        assertNull(renderSvg("not xml at all".toByteArray(), 100))
        assertNull(decodeProjectImage(loaded(entity.toByteArray(), "image/svg+xml"), 100))
    }
}
