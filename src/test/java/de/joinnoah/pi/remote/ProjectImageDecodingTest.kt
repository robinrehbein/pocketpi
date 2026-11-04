package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Color
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
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

    private fun loaded(bytes: ByteArray, mime: String) =
        ProjectImageResult.Loaded("a.x", mime, AttachmentImportRules.sha256(bytes), bytes)

    /** The package-private resolver registry of AndroidSVG; null means nothing external is ever read. */
    private fun fileResolver(): Any? =
        SVG::class.java.getDeclaredMethod("getFileResolver").apply { isAccessible = true }.invoke(null)

    private fun svg(body: String, attributes: String = "width=\"40\" height=\"20\"") =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" $attributes>$body</svg>".toByteArray()

    @Test
    fun rasterImagesAreBoundedByTheEdge() = runTest {
        val bitmap = decodeProjectImage(loaded(png(3000, 1000), "image/png"), 1024).bitmapOrNull()!!
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 1024)
        assertNull(decodeProjectImage(loaded(ByteArray(64) { 7 }, "image/png"), 1024).bitmapOrNull())
        // 60 megapixels are refused without decoding.
        assertNull(decodeProjectImage(loaded(png(10000, 6000, Color.WHITE), "image/png"), 1024).bitmapOrNull())
    }

    @Test
    fun aGifShowsItsFirstFrame() = runTest {
        // A minimal 1x1 GIF89a with one frame.
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
        val bitmap = decodeProjectImage(loaded(gif, "image/gif"), 1024).bitmapOrNull()
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
        assertNotNull(decodeProjectImage(loaded(svg("<circle r=\"5\"/>"), "image/svg+xml"), 256).bitmapOrNull())
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
        assertNull(decodeProjectImage(loaded(entity.toByteArray(), "image/svg+xml"), 100).bitmapOrNull())
    }

    private fun nested(depth: Int) =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">" + "<g>".repeat(depth) + "<rect width=\"5\" height=\"5\"/>" +
            "</g>".repeat(depth) + "</svg>").toByteArray()

    @Test
    fun deepNestingAndHugeElementCountsAreRefusedWithoutCrashing() = runTest {
        assertTrue(safeSvgBytes(nested(150)))
        assertNotNull(renderSvg(nested(150), 64))
        for (depth in listOf(250, 100_000)) {
            assertFalse(safeSvgBytes(nested(depth)))
            assertNull(renderSvg(nested(depth), 64))
            assertNull(decodeProjectImage(loaded(nested(depth), "image/svg+xml"), 64).bitmapOrNull())
        }
        val many = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">" + "<rect width=\"1\" height=\"1\"/>".repeat(60_000) + "</svg>").toByteArray()
        assertFalse(safeSvgBytes(many))
        assertNull(renderSvg(many, 64))
        // A throwable inside the renderer ends in null, never in a crash.
        assertEquals(SvgRender.Failed, BoundedSvgRenderer(renderer = { _, _ -> throw StackOverflowError() }).render("so", ByteArray(1), 10))
    }

    @Test
    fun anEncodingOtherThanUtf8IsRefused() {
        val tail = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        assertTrue(safeSvgBytes("<?xml version=\"1.0\" encoding=\"UTF-8\"?>$tail".toByteArray()))
        assertTrue(safeSvgBytes("<?xml version=\"1.0\"?>$tail".toByteArray()))
        assertFalse(safeSvgBytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>$tail".toByteArray()))
        assertFalse(safeSvgBytes("<?xml version='1.0' encoding='utf-16'?>$tail".toByteArray()))
    }

    @Test
    fun aSlotIsWaitedForAndOnlyStuckThreadsMakeItBusy() = runTest {
        val release = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val renderer = BoundedSvgRenderer(threads = 2, timeoutMillis = 10_000, slotWaitMillis = 300, renderer = { _, _ ->
            peak.set(maxOf(peak.get(), running.incrementAndGet()))
            release.await()
            running.decrementAndGet()
            null
        })
        val first = async(kotlinx.coroutines.Dispatchers.Default) { renderer.render("one", ByteArray(1), 10) }
        val second = async(kotlinx.coroutines.Dispatchers.Default) { renderer.render("two", ByteArray(1), 10) }
        while (running.get() < 2) Thread.sleep(5)
        // Both slots are taken by renders that are still within their time: a third waits, then is busy.
        assertEquals(SvgRender.Busy, renderer.render("three", ByteArray(1), 10))
        assertFalse(renderer.hasFailed("three", 10))
        release.countDown()
        first.await(); second.await()
        assertEquals(2, peak.get())
    }

    @Test
    fun aValidSvgThatWaitsForASlotIsRenderedNotMalformed() = runTest {
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.atomic.AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val renderer = BoundedSvgRenderer(threads = 1, timeoutMillis = 10_000, slotWaitMillis = 5_000, renderer = { _, _ ->
            if (started.incrementAndGet() == 1) release.await()
            bitmap
        })
        val first = async(kotlinx.coroutines.Dispatchers.Default) { renderer.render("one", ByteArray(1), 10) }
        while (started.get() < 1) Thread.sleep(5)
        val second = async(kotlinx.coroutines.Dispatchers.Default) { renderer.render("two", ByteArray(1), 10) }
        Thread.sleep(100)
        release.countDown()
        assertTrue(first.await() is SvgRender.Ready)
        assertTrue(second.await() is SvgRender.Ready)
    }

    @Test
    fun whenEveryThreadIsStuckPastItsTimeoutRequestsAreBusyAtOnce() = runTest {
        val release = java.util.concurrent.CountDownLatch(1)
        val renderer = BoundedSvgRenderer(threads = 2, timeoutMillis = 100, slotWaitMillis = 60_000, renderer = { _, _ ->
            while (true) try { release.await(); break } catch (e: InterruptedException) { }
            null
        })
        assertEquals(SvgRender.Failed, renderer.render("a", ByteArray(1), 10))
        assertEquals(SvgRender.Failed, renderer.render("b", ByteArray(1), 10))
        val started = System.nanoTime()
        // Not waiting 60 s for a slot that cannot come: Busy, not Malformed, and not remembered.
        assertEquals(SvgRender.Busy, renderer.render("c", ByteArray(1), 10))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertFalse(renderer.hasFailed("c", 10))
        release.countDown()
        // The threads end, the stuck count drops and a render works again.
        var again: SvgRender = SvgRender.Busy
        repeat(100) { if (again == SvgRender.Busy) { Thread.sleep(20); again = renderer.render("d", ByteArray(1), 10) } }
        assertEquals(SvgRender.Failed, again)
    }

    @Test
    fun aCancelledRenderGivesItsSlotBack() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val renderer = BoundedSvgRenderer(threads = 1, timeoutMillis = 30_000, slotWaitMillis = 100, renderer = { b, _ ->
            if (b.size == 1) { started.countDown(); Thread.sleep(30_000) }
            bitmap
        })
        val job = async(kotlinx.coroutines.Dispatchers.Default) { renderer.render("slow", ByteArray(1), 10) }
        started.await()
        assertEquals(1, renderer.slotsInUse())
        job.cancel()
        var released = false
        repeat(100) { if (!released) { Thread.sleep(20); released = renderer.slotsInUse() == 0 } }
        assertTrue(released)
        assertTrue(renderer.render("fast", ByteArray(2), 10) is SvgRender.Ready)
    }

    @Test
    fun bytesThatTimedOutOrFailedAreNotRenderedAgainAtThatSizeOnly() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // Fails only at the large size.
        val renderer = BoundedSvgRenderer(renderer = { _, edge -> calls.incrementAndGet(); if (edge > 1024) null else bitmap })
        assertEquals(SvgRender.Failed, renderer.render("x", ByteArray(1), 2048))
        assertEquals(SvgRender.Failed, renderer.render("x", ByteArray(1), 2048))
        assertEquals(1, calls.get())
        assertTrue(renderer.render("x", ByteArray(1), 1024) is SvgRender.Ready)
        assertEquals(2, calls.get())

        val slow = BoundedSvgRenderer(timeoutMillis = 100, renderer = { _, _ -> calls.incrementAndGet(); try { Thread.sleep(5_000) } catch (e: InterruptedException) { }; null })
        assertEquals(SvgRender.Failed, slow.render("slow", ByteArray(1), 10))
        assertTrue(slow.hasFailed("slow", 10))
        assertEquals(SvgRender.Failed, slow.render("slow", ByteArray(1), 10))
        assertEquals(3, calls.get())
    }

    @Test
    fun aByteOrderMarkDoesNotHideANonUtf8Encoding() {
        val tail = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertFalse(safeSvgBytes(bom + "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>$tail".toByteArray()))
        assertTrue(safeSvgBytes(bom + "<?xml version=\"1.0\" encoding=\"utf-8\"?>$tail".toByteArray()))
    }

    @Test
    fun theStructureScannerHandlesQuotedBracketsAndUnterminatedTags() {
        val head = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\">"
        // A '>' inside an attribute value does not end the tag, so these 300 nested groups still count.
        assertFalse(safeSvgBytes((head + "<g data-x=\"a>b\">".repeat(300) + "</svg>").toByteArray()))
        // Comments and CDATA with tag-like text are not elements.
        assertTrue(safeSvgBytes((head + "<!-- " + "<g>".repeat(500) + " --><![CDATA[" + "<g>".repeat(500) + "]]></svg>").toByteArray()))
        // An unterminated tag or comment ends the scan without error.
        assertTrue(safeSvgBytes((head + "<g data-x=\"never closed").toByteArray()))
        assertTrue(safeSvgBytes((head + "<!-- never closed").toByteArray()))
    }

    @Test
    fun aCssImportInsideAStyleElementRendersWithoutAResolver() = runTest {
        assertNull(fileResolver())
        val bytes = svg("<style>@import url(http://127.0.0.1:9/x.css); @import \"file:///etc/passwd\"; rect { fill: red }</style><rect width=\"40\" height=\"20\"/>")
        assertNotNull(renderSvg(bytes, 200))
        assertNull(fileResolver())
    }
}
