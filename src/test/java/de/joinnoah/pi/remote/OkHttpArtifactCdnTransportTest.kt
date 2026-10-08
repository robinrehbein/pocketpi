package de.joinnoah.pi.remote

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The real OkHttp transport against a local server. The transport is called directly because the
 * https/443 allowlist would refuse a localhost URL. Plain HTTP is allowed through the test-only
 * `configure` hook.
 */
class OkHttpArtifactCdnTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpArtifactCdnTransport
    private lateinit var cache: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        cache = File.createTempFile("cdn-cache", "").also { it.delete(); it.mkdirs() }
        transport = OkHttpArtifactCdnTransport(cache) { connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)) }
    }

    @After fun tearDown() {
        server.close()
        cache.deleteRecursively()
    }

    private fun gzip(size: Int): Buffer {
        val bytes = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(ByteArray(size) { 'a'.code.toByte() }) }
        return Buffer().write(bytes.toByteArray())
    }

    @Test fun theRequestCarriesOnlyTheExpectedHeaders() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/css").body("a{}").build())
        val raw = transport.get(server.url("/x.css").toString(), "UA-test", ArtifactCdnBudget())
        raw.close()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Referer"])
        assertNull(request.headers["Origin"])
        assertEquals("UA-test", request.headers["User-Agent"])
        val names = request.headers.names().map { it.lowercase() }.toSet()
        assertEquals(setOf("host", "connection", "accept-encoding", "user-agent"), names)
    }

    @Test fun redirectsAreReportedAndNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://unpkg.com/y.js").build())
        val raw = transport.get(server.url("/x.js").toString(), "UA", ArtifactCdnBudget())
        assertEquals(302, raw.status)
        assertEquals("https://unpkg.com/y.js", raw.location)
        raw.close()
        assertEquals(1, server.requestCount)
    }

    @Test fun aGzipBodyThatDecodesBeyondTheLimitIsRefusedAndItsBytesAreCharged() {
        val limit = 1_000L
        // Tiny on the wire, 200 KB once decoded; OkHttp inflates it and drops Content-Length.
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/javascript").addHeader("Content-Encoding", "gzip").body(gzip(200_000)).build(),
        )
        val raw = transport.get(server.url("/big.js").toString(), "UA", ArtifactCdnBudget())
        assertEquals(-1L, raw.contentLength)
        var charged = 0L
        val body = raw.readBody(limit) { charged += it }
        raw.close()
        assertNull(body)
        assertTrue("charged $charged", charged >= limit + 1)
        // Only a read-ahead segment or so beyond limit + 1, never the whole 200 KB.
        assertTrue("charged $charged", charged < 100_000)
    }

    @Test fun aGzipBodyWithinTheLimitIsDeliveredDecodedAndCharged() {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/javascript").addHeader("Content-Encoding", "gzip").body(gzip(500)).build(),
        )
        val raw = transport.get(server.url("/ok.js").toString(), "UA", ArtifactCdnBudget())
        var charged = 0L
        val body = raw.readBody(1_000) { charged += it }
        raw.close()
        assertEquals(500, body!!.size)
        assertEquals(500L, charged)
    }

    @Test fun cancellingTheViewMidBodyBecomes504() {
        val budget = ArtifactCdnBudget()
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/javascript")
                .body(Buffer().write(ByteArray(100_000)))
                .throttleBody(1_000, 1, TimeUnit.SECONDS)
                .build(),
        )
        val viaServer =
            object : ArtifactCdnTransport {
                override fun get(url: String, userAgent: String, budget: ArtifactCdnBudget) =
                    transport.get(server.url("/slow.js").toString(), userAgent, budget)
            }
        Thread {
            Thread.sleep(500)
            budget.cancel()
        }.start()
        val started = System.nanoTime()
        val reply = ArtifactCdnPolicyFetcher(viaServer).fetch("https://unpkg.com/slow.js", "UA", budget)
        assertEquals(504, (reply as ArtifactCdnReply.Refused).status)
        assertTrue("took too long", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
        assertTrue(budget.cancelled)
    }

    @Test fun aCancelledReadThrowsIoException() {
        val budget = ArtifactCdnBudget()
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/css").body(Buffer().write(ByteArray(50_000))).throttleBody(500, 1, TimeUnit.SECONDS).build(),
        )
        val raw = transport.get(server.url("/slow.css").toString(), "UA", budget)
        budget.cancel()
        try {
            raw.readBody(1_000_000) { }
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        } finally {
            raw.close()
        }
        assertFalse(raw.status != 200)
    }
}
