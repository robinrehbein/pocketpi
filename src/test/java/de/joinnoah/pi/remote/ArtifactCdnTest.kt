package de.joinnoah.pi.remote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactCdnTest {
    private val hosts =
        listOf(
            "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "cdn.tailwindcss.com", "code.jquery.com",
            "fonts.googleapis.com", "fonts.gstatic.com",
        )

    @Test fun everyAllowlistedHostIsAllowedOverHttps() {
        hosts.forEach {
            assertTrue(it, artifactCdnAllowed("https://$it/x.js"))
            assertTrue(it, artifactCdnAllowed("https://$it/a/b.css?v=1"))
            assertTrue(it, artifactCdnAllowed("https://$it"))
            assertTrue(it, artifactCdnAllowed("https://$it:443/x.js"))
            assertTrue(it, artifactCdnAllowed("https://$it/x#frag"))
        }
    }

    @Test fun lookalikeHostsAreRefused() {
        listOf(
            "https://cdn.jsdelivr.net.evil.com/x.js",
            "https://evilcdn.jsdelivr.net.evil.com/x.js",
            "https://xcdn.jsdelivr.net/x.js",
            "https://cdn.jsdelivr.net-evil.com/x.js",
            "https://jsdelivr.net/x.js",
            "https://sub.cdn.jsdelivr.net/x.js",
            "https://www.unpkg.com/x.js",
            "https://unpkg.com.evil.example/x.js",
            "https://evil.example/cdn.jsdelivr.net/x.js",
            "https://evil.example/?https://unpkg.com/x.js",
            "https://evil.example#@unpkg.com/x.js",
            "https://fonts.googleapis.com.evil.example/css",
            "https://fonts.gstatic.com.cn/x.woff2",
            "https://cdnjs.cloudflare.com.evil.com/x.js",
            "https://cdn.tailwindcss.com.evil.com/",
            "https://code.jquery.com.evil.com/x.js",
            "https://googleapis.com/x",
            // Punycode and unicode look-alikes of allowlisted names.
            "https://xn--unpkg.com/x.js",
            "https://xn--cdn-jsdelivr-9fb.net/x.js",
            "https://unpкg.com/x.js",
            "https://cdn.jsdelivr.nеt/x.js",
            "https://сdn.jsdelivr.net/x.js",
        ).forEach { assertFalse(it, artifactCdnAllowed(it)) }
    }

    @Test fun onlyHttpsOnPort443WithoutCredentialsIsAllowed() {
        listOf(
            "http://unpkg.com/x.js",
            "ws://unpkg.com/x.js",
            "wss://unpkg.com/x.js",
            "ftp://unpkg.com/x.js",
            "file://unpkg.com/x.js",
            "data:text/javascript,1",
            "//unpkg.com/x.js",
            "unpkg.com/x.js",
            "HTTPS://unpkg.com/x.js",
            "https:unpkg.com/x.js",
            "https:///unpkg.com/x.js",
            "https://unpkg.com:80/x.js",
            "https://unpkg.com:8443/x.js",
            "https://unpkg.com:/x.js",
            "https://unpkg.com:0443/x.js",
            "https://unpkg.com:4430/x.js",
            "https://unpkg.com:443:443/x.js",
            "https://user@unpkg.com/x.js",
            "https://user:pass@unpkg.com/x.js",
            "https://unpkg.com@evil.example/x.js",
            "https://unpkg.com:443@evil.example/x.js",
            "https://unpkg.com%40evil.example/x.js",
            "https://[::1]/x.js",
            "https://127.0.0.1/x.js",
            "https://192.168.0.1/x.js",
            "https://localhost/x.js",
            "https:///",
            "https://",
            "",
        ).forEach { assertFalse(it, artifactCdnAllowed(it)) }
    }

    @Test fun oddSpellingsOfAllowlistedHostsAreRefused() {
        listOf(
            "https://UNPKG.com/x.js",
            "https://Cdn.Jsdelivr.net/x.js",
            "https://unpkg.com./x.js",
            "https://unpkg.com../x.js",
            "https://.unpkg.com/x.js",
            "https://unpkg.com\\@evil.example/x.js",
            "https://evil.example\\@unpkg.com/x.js",
            "https://unpkg.com\\x.js",
            "https:\\\\unpkg.com/x.js",
            " https://unpkg.com/x.js",
            "https://unpkg.com/x.js ",
            "https://unpkg.com /x.js",
            "https://unpkg.com\t/x.js",
            "https://unpkg.com\n/x.js",
            "https://unpkg.com\r\n/x.js",
            "https://unpkg.com\u0000/x.js",
            "https://unpkg.com%2e/x.js",
            "https://unpkg%2ecom/x.js",
            "https://unpkg。com/x.js",
            "https://unpkg.com​/x.js",
            "https://unpkg.com／.evil.example/x.js",
        ).forEach { assertFalse(it, artifactCdnAllowed(it)) }
    }

    @Test fun contentTypesAreFiltered() {
        listOf(
            "text/javascript", "application/javascript; charset=utf-8", "TEXT/CSS", "text/css; charset=UTF-8", "font/woff2", "font/ttf",
            "image/png", "image/svg+xml", "image/webp", "application/json", "application/wasm", "text/plain; charset=utf-8",
            "application/font-woff2",
        ).forEach { assertTrue(it, artifactCdnContentTypeAllowed(it)) }
        listOf(
            null, "", "text/html", "text/html; charset=utf-8", "application/xhtml+xml", "application/xml", "text/xml",
            "application/octet-stream", "application/pdf", "multipart/form-data", "video/mp4", "audio/mpeg", "fontx/woff",
            "application/x-www-form-urlencoded", "javascript", "text/javascript-evil",
        ).forEach { assertFalse(it.toString(), artifactCdnContentTypeAllowed(it)) }
    }

    @Test fun theCharsetIsReadFromTheContentType() {
        assertEquals("utf-8", artifactCdnCharset("text/css; charset=utf-8"))
        assertEquals("UTF-8", artifactCdnCharset("text/css; Charset=\"UTF-8\""))
        assertNull(artifactCdnCharset("text/css"))
        assertNull(artifactCdnCharset(null))
        assertNull(artifactCdnCharset("text/css; charset="))
    }

    private class Transport(private val handler: (String) -> ArtifactCdnRaw) : ArtifactCdnTransport {
        val urls = mutableListOf<String>()
        val agents = mutableListOf<String>()
        var closed = 0

        override fun get(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnRaw {
            urls += url
            agents += userAgent
            return handler(url)
        }
    }

    private fun raw(
        status: Int = 200,
        type: String? = "text/javascript",
        body: ByteArray = "ok".toByteArray(),
        location: String? = null,
        length: Long = body.size.toLong(),
        onClose: () -> Unit = {},
    ) = ArtifactCdnRaw(
        status, location, type, length,
        readBody = { limit, charge -> if (body.size > limit) { charge(limit + 1); null } else { charge(body.size.toLong()); body } },
        close = onClose,
    )

    private fun fetch(transport: Transport, url: String, budget: ArtifactCdnBudget = ArtifactCdnBudget()) =
        ArtifactCdnPolicyFetcher(transport).fetch(url, "UA", budget)

    private fun refused(reply: ArtifactCdnReply) = (reply as ArtifactCdnReply.Refused).status

    @Test fun anAllowedResponseIsDeliveredWithTypeAndCharset() {
        val transport = Transport { raw(type = "application/javascript; charset=UTF-8", body = "var a=1".toByteArray()) }
        val reply = fetch(transport, "https://cdn.jsdelivr.net/npm/a.js") as ArtifactCdnReply.Ok
        assertEquals("application/javascript", reply.mediaType)
        assertEquals("UTF-8", reply.charset)
        assertEquals("var a=1", reply.body.toString(Charsets.UTF_8))
        assertEquals(listOf("https://cdn.jsdelivr.net/npm/a.js"), transport.urls)
        assertEquals(listOf("UA"), transport.agents)
    }

    @Test fun aUrlOffTheAllowlistNeverReachesTheTransport() {
        val transport = Transport { raw() }
        listOf("https://evil.example/x.js", "http://unpkg.com/x.js", "https://unpkg.com.evil.example/x.js", "https://unpkg.com:8443/x.js")
            .forEach { assertEquals(it, 403, refused(fetch(transport, it))) }
        assertEquals(emptyList<String>(), transport.urls)
    }

    @Test fun redirectsAreFollowedOnlyWithinTheAllowlistAndTheLimit() {
        val chain = Transport { url ->
            when (url) {
                "https://unpkg.com/a" -> raw(302, location = "https://cdn.jsdelivr.net/b")
                "https://cdn.jsdelivr.net/b" -> raw(301, location = "/c")
                "https://cdn.jsdelivr.net/c" -> raw(307, location = "//cdnjs.cloudflare.com/d")
                else -> raw(body = "end".toByteArray())
            }
        }
        val ok = fetch(chain, "https://unpkg.com/a") as ArtifactCdnReply.Ok
        assertEquals("end", ok.body.toString(Charsets.UTF_8))
        assertEquals(4, chain.urls.size)

        val tooLong = Transport { raw(302, location = "https://unpkg.com/again") }
        assertEquals(403, refused(fetch(tooLong, "https://unpkg.com/a")))
        assertEquals(4, tooLong.urls.size)

        listOf(
            "https://evil.example/x.js", "http://unpkg.com/x.js", "https://unpkg.com.evil.example/x.js", "data:text/javascript,1",
            "https://user@unpkg.com/x.js", "https://unpkg.com:8443/x.js", "relative/path.js", "", null,
        ).forEach { target ->
            val transport = Transport { url -> if (url == "https://unpkg.com/a") raw(302, location = target) else raw() }
            assertEquals(target.toString(), 403, refused(fetch(transport, "https://unpkg.com/a")))
            assertEquals(target.toString(), listOf("https://unpkg.com/a"), transport.urls)
        }
    }

    @Test fun onlyA200IsDeliveredAndOtherStatusesBecome404() {
        listOf(201, 204, 206, 304, 400, 403, 404, 410, 429, 500, 503).forEach {
            assertEquals(it.toString(), 404, refused(fetch(Transport { _ -> raw(it) }, "https://unpkg.com/x")))
        }
    }

    @Test fun aDisallowedContentTypeIsRefused() {
        listOf("text/html", "application/octet-stream", null, "application/xml").forEach {
            assertEquals(it.toString(), 403, refused(fetch(Transport { _ -> raw(type = it) }, "https://unpkg.com/x")))
        }
    }

    @Test fun theSizeCapsAreEnforced() {
        val over = ARTIFACT_CDN_MAX_RESOURCE_BYTES + 1
        // Declared length too big.
        assertEquals(403, refused(fetch(Transport { _ -> raw(length = over) }, "https://unpkg.com/x")))
        // Declared length unknown or a lie: the read itself is capped.
        val limits = mutableListOf<Long>()
        val streaming =
            Transport { _ ->
                ArtifactCdnRaw(200, null, "text/css", -1, readBody = { limit, _ -> limits += limit; null })
            }
        assertEquals(403, refused(fetch(streaming, "https://unpkg.com/x")))
        assertEquals(listOf(ARTIFACT_CDN_MAX_RESOURCE_BYTES), limits)
        // Exactly at the cap passes.
        assertTrue(fetch(Transport { _ -> raw(body = ByteArray(1024), length = -1) }, "https://unpkg.com/x", ArtifactCdnBudget(1024)) is ArtifactCdnReply.Ok)
    }

    @Test fun thePageBudgetCapsTheTotalAndIsSharedAcrossRequests() {
        val budget = ArtifactCdnBudget(2_500)
        val transport = Transport { raw(body = ByteArray(1_000)) }
        assertTrue(fetch(transport, "https://unpkg.com/1", budget) is ArtifactCdnReply.Ok)
        assertTrue(fetch(transport, "https://unpkg.com/2", budget) is ArtifactCdnReply.Ok)
        // 500 bytes remain; a 1000 byte body no longer fits.
        assertEquals(403, refused(fetch(transport, "https://unpkg.com/3", budget)))
        assertEquals(500, budget.remaining())
        assertEquals(32L * 1024 * 1024, ARTIFACT_CDN_MAX_PAGE_BYTES)
        assertEquals(8L * 1024 * 1024, ARTIFACT_CDN_MAX_RESOURCE_BYTES)
    }

    @Test fun networkFailuresBecome504() {
        assertEquals(504, refused(fetch(Transport { _ -> throw IOException("offline") }, "https://unpkg.com/x")))
        assertEquals(504, refused(fetch(Transport { _ -> throw IllegalStateException("odd") }, "https://unpkg.com/x")))
        val failing = ArtifactCdnRaw(200, null, "text/css", -1, readBody = { _, _ -> throw IOException("cut off") })
        assertEquals(504, refused(fetch(Transport { _ -> failing }, "https://unpkg.com/x")))
    }

    @Test fun everyResponseIsClosedAndACancelledViewFetchesNothing() {
        var closed = 0
        val transport = Transport { url -> if (url.endsWith("/a")) raw(302, location = "https://unpkg.com/b", onClose = { closed++ }) else raw(onClose = { closed++ }) }
        fetch(transport, "https://unpkg.com/a")
        assertEquals(2, closed)
        val budget = ArtifactCdnBudget().also { it.cancel() }
        val idle = Transport { raw() }
        assertEquals(403, refused(fetch(idle, "https://unpkg.com/x", budget)))
        assertEquals(emptyList<String>(), idle.urls)
    }

    @Test fun cancellingTheBudgetRunsRegisteredActionsOnce() {
        val budget = ArtifactCdnBudget()
        var calls = 0
        val unregister = budget.onCancel { calls++ }
        budget.onCancel { calls += 10 }
        unregister()
        budget.cancel()
        budget.cancel()
        assertEquals(10, calls)
        assertTrue(budget.cancelled)
        // Registering after cancel runs at once.
        budget.onCancel { calls += 100 }
        assertEquals(110, calls)
    }

    @Test fun theUserAgentIsTheFixedOneAndNoPageHeadersExist() {
        // The transport interface has no parameter for page headers, cookies, Referer or Origin.
        val method = ArtifactCdnTransport::class.java.methods.single { it.name == "get" }
        assertEquals(listOf(String::class.java, String::class.java, ArtifactCdnBudget::class.java), method.parameterTypes.toList())
    }

    @Test fun refusedAndFailedBodiesStillCostBudget() {
        val budget = ArtifactCdnBudget(10_000)
        // Over the limit: the source was read up to limit + 1 before the body was refused.
        val over = Transport { _ -> ArtifactCdnRaw(200, null, "text/css", -1, readBody = { limit, charge -> charge(limit + 1); null }) }
        assertEquals(403, refused(fetch(over, "https://unpkg.com/x", budget)))
        assertTrue(budget.remaining() <= 0)
        val failBudget = ArtifactCdnBudget(10_000)
        val failing = Transport { _ -> ArtifactCdnRaw(200, null, "text/css", -1, readBody = { _, charge -> charge(4_000); throw IOException("cut") }) }
        assertEquals(504, refused(fetch(failing, "https://unpkg.com/x", failBudget)))
        assertEquals(6_000, failBudget.remaining())
        // A refusal before any read refunds the whole reservation.
        val untouched = ArtifactCdnBudget(10_000)
        assertEquals(403, refused(fetch(Transport { _ -> raw(type = "text/html") }, "https://unpkg.com/x", untouched)))
        assertEquals(10_000, untouched.remaining())
    }

    @Test fun reserveGrantsAtMostWhatIsLeftAndRefundGivesBack() {
        val budget = ArtifactCdnBudget(1_000)
        assertEquals(600, budget.reserve(600))
        assertEquals(400, budget.reserve(600))
        assertEquals(0, budget.reserve(600))
        budget.refund(250)
        assertEquals(250, budget.remaining())
    }

    @Test fun theRequestCapCountsEveryForwardedRequestWhateverItsStatus() {
        val budget = ArtifactCdnBudget(maxRequests = 3)
        val transport = Transport { raw(404) }
        repeat(3) { assertEquals(404, refused(fetch(transport, "https://unpkg.com/x", budget))) }
        assertEquals(403, refused(fetch(transport, "https://unpkg.com/x", budget)))
        assertEquals(3, transport.urls.size)
        assertEquals(300, ARTIFACT_CDN_MAX_REQUESTS)
    }

    @Test fun withoutAFetchPermitTheAnswerIs504AndNothingIsFetched() {
        val budget = ArtifactCdnBudget(permits = 1, permitWaitMillis = 50)
        assertTrue(budget.acquireFetch())
        val transport = Transport { raw() }
        assertEquals(504, refused(fetch(transport, "https://unpkg.com/x", budget)))
        assertEquals(emptyList<String>(), transport.urls)
        budget.releaseFetch()
        assertTrue(fetch(transport, "https://unpkg.com/x", budget) is ArtifactCdnReply.Ok)
        // The permit is released again after the fetch.
        assertTrue(budget.acquireFetch())
        assertEquals(6, ARTIFACT_CDN_CONCURRENT_FETCHES)
    }
}
