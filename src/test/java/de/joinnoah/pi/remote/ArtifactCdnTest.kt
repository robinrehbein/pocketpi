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

    /** One path of the versioned shape each host accepts. */
    private val samplePaths =
        mapOf(
            "cdnjs.cloudflare.com" to "/ajax/libs/three.js/r128/three.min.js",
            "cdn.jsdelivr.net" to "/npm/chart.js@4.4.0/dist/chart.umd.min.js",
            "unpkg.com" to "/react@18.3.1/umd/react.production.min.js",
            "cdn.tailwindcss.com" to "/3.4.16",
            "code.jquery.com" to "/jquery-3.7.1.min.js",
            "fonts.googleapis.com" to "/css2?family=Inter:wght@400;600",
            "fonts.gstatic.com" to "/s/inter/v13/UcC73FwrK3iLTeHuS_fvQtMwCp50KnMa1ZL7.woff2",
        )

    @Test fun everyAllowlistedHostIsAllowedOverHttps() {
        assertEquals(hosts.toSet(), samplePaths.keys)
        samplePaths.forEach { (host, path) ->
            assertTrue(host, artifactCdnAllowed("https://$host$path"))
            assertTrue(host, artifactCdnAllowed("https://$host:443$path"))
            assertTrue(host, artifactCdnAllowed("https://$host$path#frag"))
        }
    }

    @Test fun versionedPackagePathsAreAllowed() {
        listOf(
            "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css",
            "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-solid-900.woff2",
            "https://cdnjs.cloudflare.com/ajax/libs/d3/7.8.5/d3.min.js",
            "https://cdn.jsdelivr.net/npm/chart.js@4",
            "https://cdn.jsdelivr.net/npm/@observablehq/plot@0.6/+esm",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css",
            "https://cdn.jsdelivr.net/npm/mermaid@10.9.0-rc.1/dist/mermaid.min.js",
            "https://cdn.jsdelivr.net/gh/user/repo@1.2.3/dist/x.js",
            "https://unpkg.com/@babel/standalone@7.24.0/babel.min.js",
            "https://unpkg.com/react-dom@18/",
            "https://cdn.tailwindcss.com",
            "https://cdn.tailwindcss.com/",
            "https://code.jquery.com/jquery-3.7.1.slim.min.js",
            "https://code.jquery.com/jquery-migrate-3.4.1.min.js",
            "https://code.jquery.com/ui/1.13.2/jquery-ui.min.js",
            "https://code.jquery.com/ui/1.13.2/themes/base/jquery-ui.css",
            "https://code.jquery.com/color/jquery.color-2.2.0.min.js",
            "https://fonts.googleapis.com/css?family=Roboto",
            "https://fonts.googleapis.com/icon?family=Material+Icons",
            "https://fonts.gstatic.com/s/materialicons/v140/flUhRq6tzZclQEJ-Vdg-IuiaDsNc.woff2",
        ).forEach { assertTrue(it, artifactCdnAllowed(it)) }
    }

    @Test fun pathsThatAreNotAVersionedPackageAreRefused() {
        listOf(
            "https://unpkg.com/x.js",
            "https://unpkg.com/react",
            "https://unpkg.com/react@latest/index.js",
            "https://unpkg.com/react@/index.js",
            "https://unpkg.com/",
            "https://unpkg.com",
            "https://cdn.jsdelivr.net/npm/chart.js",
            "https://cdn.jsdelivr.net/npm/chart.js@latest/dist/chart.js",
            "https://cdn.jsdelivr.net/combine/npm/a@1,npm/b@2",
            "https://cdn.jsdelivr.net/a.js",
            "https://cdnjs.cloudflare.com/ajax/libs/d3/latest/d3.min.js",
            "https://cdnjs.cloudflare.com/x.js",
            "https://cdn.tailwindcss.com/beacon",
            "https://cdn.tailwindcss.com/3.4.16/x",
            "https://code.jquery.com/jquery.js",
            "https://code.jquery.com/secret-data.js",
            "https://fonts.googleapis.com/css2",
            "https://fonts.googleapis.com/css2?text=secret",
            "https://fonts.googleapis.com/x?family=Inter",
            "https://fonts.gstatic.com/l/font?kit=abc&skey=def",
            "https://fonts.gstatic.com/s/inter/v13/x.js",
            // Dot segments, empty segments, percent escapes and over-long paths.
            "https://unpkg.com/react@18.3.1/../../x.js",
            "https://unpkg.com/react@18.3.1/./x.js",
            "https://unpkg.com/react@18.3.1//x.js",
            "https://unpkg.com/react@18.3.1/%2e%2e/x.js",
            "https://unpkg.com/react@18.3.1/a%20b.js",
            "https://unpkg.com/react@18.3.1/" + "a".repeat(600) + ".js",
        ).forEach { assertFalse(it, artifactCdnAllowed(it)) }
    }

    @Test fun theQueryAndFragmentAreStripped() {
        assertEquals(
            "https://unpkg.com/react@18.3.1/umd/react.js",
            artifactCdnRequestUrl("https://unpkg.com/react@18.3.1/umd/react.js?secret=1#frag"),
        )
        assertEquals(
            "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-solid-900.woff2",
            artifactCdnRequestUrl("https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-solid-900.woff2?v=6.4.0"),
        )
        assertEquals("https://cdn.jsdelivr.net/npm/x@1/x.js", artifactCdnRequestUrl("https://cdn.jsdelivr.net/npm/x@1/x.js?"))
        assertEquals("https://code.jquery.com/jquery-3.7.1.min.js", artifactCdnRequestUrl("https://code.jquery.com:443/jquery-3.7.1.min.js#x"))
        assertEquals("https://cdn.tailwindcss.com/", artifactCdnRequestUrl("https://cdn.tailwindcss.com?beacon=1"))
    }

    @Test fun googleFontsAndTailwindKeepOnlyTheirKnownParameters() {
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Inter:wght@400;600&family=Roboto+Mono&display=swap",
            artifactCdnRequestUrl("https://fonts.googleapis.com/css2?family=Inter:wght@400;600&text=secret&family=Roboto+Mono&display=swap&x=1#f"),
        )
        assertEquals(
            "https://fonts.googleapis.com/css2?family=Inter",
            artifactCdnRequestUrl("https://fonts.googleapis.com/css2?family=Inter&display=whatever"),
        )
        // Without a family the stylesheet request has no use and is refused.
        assertNull(artifactCdnRequestUrl("https://fonts.googleapis.com/css2?display=swap"))
        assertNull(artifactCdnRequestUrl("https://fonts.googleapis.com/css2?family=" + "a".repeat(300)))
        assertEquals(
            "https://cdn.tailwindcss.com/3.4.16?plugins=forms,typography",
            artifactCdnRequestUrl("https://cdn.tailwindcss.com/3.4.16?plugins=forms,typography,secret,forms&x=1"),
        )
        assertEquals("https://cdn.tailwindcss.com/", artifactCdnRequestUrl("https://cdn.tailwindcss.com/?plugins=secret"))
    }

    @Test fun theFetcherRequestsTheCleanedUrlAtEveryHop() {
        val transport = Transport { url ->
            if (url == "https://cdn.tailwindcss.com/?plugins=forms") raw(302, location = "/3.4.16?plugins=forms&leak=1") else raw()
        }
        assertTrue(fetch(transport, "https://cdn.tailwindcss.com?plugins=forms&leak=1#x") is ArtifactCdnReply.Ok)
        assertEquals(listOf("https://cdn.tailwindcss.com/?plugins=forms", "https://cdn.tailwindcss.com/3.4.16?plugins=forms"), transport.urls)
        // A redirect to an unversioned path on an allowlisted host is refused before it is requested.
        val unversioned = Transport { url -> if (url.endsWith("/a.js")) raw(302, location = "/beacon/data.js") else raw() }
        assertEquals(403, refused(fetch(unversioned, "https://unpkg.com/a@1.0.0/a.js")))
        assertEquals(listOf("https://unpkg.com/a@1.0.0/a.js"), unversioned.urls)
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
            "https://evil.example/?https://unpkg.com/x@1.0.0/x.js",
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
            "https://unpkg.com:80/x@1.0.0/x.js",
            "https://unpkg.com:8443/x@1.0.0/x.js",
            "https://unpkg.com:/x@1.0.0/x.js",
            "https://unpkg.com:0443/x@1.0.0/x.js",
            "https://unpkg.com:4430/x@1.0.0/x.js",
            "https://unpkg.com:443:443/x@1.0.0/x.js",
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
            " https://unpkg.com/x@1.0.0/x.js",
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
        val reply = fetch(transport, "https://cdn.jsdelivr.net/npm/a@1.0.0/a.js") as ArtifactCdnReply.Ok
        assertEquals("application/javascript", reply.mediaType)
        assertEquals("UTF-8", reply.charset)
        assertEquals("var a=1", reply.body.toString(Charsets.UTF_8))
        assertEquals(listOf("https://cdn.jsdelivr.net/npm/a@1.0.0/a.js"), transport.urls)
        assertEquals(listOf("UA"), transport.agents)
    }

    @Test fun aUrlOffTheAllowlistNeverReachesTheTransport() {
        val transport = Transport { raw() }
        listOf("https://evil.example/x.js", "http://unpkg.com/x.js", "https://unpkg.com.evil.example/x.js", "https://unpkg.com:8443/x@1.0.0/x.js")
            .forEach { assertEquals(it, 403, refused(fetch(transport, it))) }
        assertEquals(emptyList<String>(), transport.urls)
    }

    @Test fun redirectsAreFollowedOnlyWithinTheAllowlistAndTheLimit() {
        val chain = Transport { url ->
            when (url) {
                "https://unpkg.com/a@1.0.0/a.js" -> raw(302, location = "https://cdn.jsdelivr.net/npm/b@1.0.0/b.js")
                "https://cdn.jsdelivr.net/npm/b@1.0.0/b.js" -> raw(301, location = "/npm/c@1.0.0/c.js")
                "https://cdn.jsdelivr.net/npm/c@1.0.0/c.js" -> raw(307, location = "//cdnjs.cloudflare.com/ajax/libs/d/1.0.0/d.js")
                else -> raw(body = "end".toByteArray())
            }
        }
        val ok = fetch(chain, "https://unpkg.com/a@1.0.0/a.js") as ArtifactCdnReply.Ok
        assertEquals("end", ok.body.toString(Charsets.UTF_8))
        assertEquals(4, chain.urls.size)

        val tooLong = Transport { raw(302, location = "https://unpkg.com/again@1.0.0/again.js") }
        assertEquals(403, refused(fetch(tooLong, "https://unpkg.com/a@1.0.0/a.js")))
        assertEquals(4, tooLong.urls.size)

        listOf(
            "https://evil.example/x.js", "http://unpkg.com/x.js", "https://unpkg.com.evil.example/x.js", "data:text/javascript,1",
            "https://user@unpkg.com/x.js", "https://unpkg.com:8443/x@1.0.0/x.js", "relative/path.js", "", null,
            // On the allowlisted host but not a versioned package path.
            "https://unpkg.com/x", "https://cdn.jsdelivr.net/npm/x/x.js", "/latest.js",
        ).forEach { target ->
            val transport = Transport { url -> if (url == "https://unpkg.com/a@1.0.0/a.js") raw(302, location = target) else raw() }
            assertEquals(target.toString(), 403, refused(fetch(transport, "https://unpkg.com/a@1.0.0/a.js")))
            assertEquals(target.toString(), listOf("https://unpkg.com/a@1.0.0/a.js"), transport.urls)
        }
    }

    @Test fun onlyA200IsDeliveredAndOtherStatusesBecome404() {
        listOf(201, 204, 206, 304, 400, 403, 404, 410, 429, 500, 503).forEach {
            assertEquals(it.toString(), 404, refused(fetch(Transport { _ -> raw(it) }, "https://unpkg.com/x@1.0.0/x.js")))
        }
    }

    @Test fun aDisallowedContentTypeIsRefused() {
        listOf("text/html", "application/octet-stream", null, "application/xml").forEach {
            assertEquals(it.toString(), 403, refused(fetch(Transport { _ -> raw(type = it) }, "https://unpkg.com/x@1.0.0/x.js")))
        }
    }

    @Test fun theSizeCapsAreEnforced() {
        val over = ARTIFACT_CDN_MAX_RESOURCE_BYTES + 1
        // Declared length too big.
        assertEquals(403, refused(fetch(Transport { _ -> raw(length = over) }, "https://unpkg.com/x@1.0.0/x.js")))
        // Declared length unknown or a lie: the read itself is capped.
        val limits = mutableListOf<Long>()
        val streaming =
            Transport { _ ->
                ArtifactCdnRaw(200, null, "text/css", -1, readBody = { limit, _ -> limits += limit; null })
            }
        assertEquals(403, refused(fetch(streaming, "https://unpkg.com/x@1.0.0/x.js")))
        assertEquals(listOf(ARTIFACT_CDN_MAX_RESOURCE_BYTES), limits)
        // Exactly at the cap passes.
        assertTrue(fetch(Transport { _ -> raw(body = ByteArray(1024), length = -1) }, "https://unpkg.com/x@1.0.0/x.js", ArtifactCdnBudget(1024)) is ArtifactCdnReply.Ok)
    }

    @Test fun thePageBudgetCapsTheTotalAndIsSharedAcrossRequests() {
        val budget = ArtifactCdnBudget(2_500)
        val transport = Transport { raw(body = ByteArray(1_000)) }
        assertTrue(fetch(transport, "https://unpkg.com/1@1.0.0/1.js", budget) is ArtifactCdnReply.Ok)
        assertTrue(fetch(transport, "https://unpkg.com/2@1.0.0/2.js", budget) is ArtifactCdnReply.Ok)
        // 500 bytes remain; a 1000 byte body no longer fits.
        assertEquals(403, refused(fetch(transport, "https://unpkg.com/3@1.0.0/3.js", budget)))
        assertEquals(500, budget.remaining())
        assertEquals(32L * 1024 * 1024, ARTIFACT_CDN_MAX_PAGE_BYTES)
        assertEquals(8L * 1024 * 1024, ARTIFACT_CDN_MAX_RESOURCE_BYTES)
    }

    @Test fun networkFailuresBecome504() {
        assertEquals(504, refused(fetch(Transport { _ -> throw IOException("offline") }, "https://unpkg.com/x@1.0.0/x.js")))
        assertEquals(504, refused(fetch(Transport { _ -> throw IllegalStateException("odd") }, "https://unpkg.com/x@1.0.0/x.js")))
        val failing = ArtifactCdnRaw(200, null, "text/css", -1, readBody = { _, _ -> throw IOException("cut off") })
        assertEquals(504, refused(fetch(Transport { _ -> failing }, "https://unpkg.com/x@1.0.0/x.js")))
    }

    @Test fun everyResponseIsClosedAndACancelledViewFetchesNothing() {
        var closed = 0
        val transport = Transport { url -> if (url.endsWith("/a.js")) raw(302, location = "https://unpkg.com/b@1.0.0/b.js", onClose = { closed++ }) else raw(onClose = { closed++ }) }
        fetch(transport, "https://unpkg.com/a@1.0.0/a.js")
        assertEquals(2, closed)
        val budget = ArtifactCdnBudget().also { it.cancel() }
        val idle = Transport { raw() }
        assertEquals(403, refused(fetch(idle, "https://unpkg.com/x@1.0.0/x.js", budget)))
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
        assertEquals(403, refused(fetch(over, "https://unpkg.com/x@1.0.0/x.js", budget)))
        assertTrue(budget.remaining() <= 0)
        val failBudget = ArtifactCdnBudget(10_000)
        val failing = Transport { _ -> ArtifactCdnRaw(200, null, "text/css", -1, readBody = { _, charge -> charge(4_000); throw IOException("cut") }) }
        assertEquals(504, refused(fetch(failing, "https://unpkg.com/x@1.0.0/x.js", failBudget)))
        assertEquals(6_000, failBudget.remaining())
        // A refusal before any read refunds the whole reservation.
        val untouched = ArtifactCdnBudget(10_000)
        assertEquals(403, refused(fetch(Transport { _ -> raw(type = "text/html") }, "https://unpkg.com/x@1.0.0/x.js", untouched)))
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
        repeat(3) { assertEquals(404, refused(fetch(transport, "https://unpkg.com/x@1.0.0/x.js", budget))) }
        assertEquals(403, refused(fetch(transport, "https://unpkg.com/x@1.0.0/x.js", budget)))
        assertEquals(3, transport.urls.size)
        assertEquals(300, ARTIFACT_CDN_MAX_REQUESTS)
    }

    @Test fun withoutAFetchPermitTheAnswerIs504AndNothingIsFetched() {
        val budget = ArtifactCdnBudget(permits = 1, permitWaitMillis = 50)
        assertTrue(budget.acquireFetch())
        val transport = Transport { raw() }
        assertEquals(504, refused(fetch(transport, "https://unpkg.com/x@1.0.0/x.js", budget)))
        assertEquals(emptyList<String>(), transport.urls)
        budget.releaseFetch()
        assertTrue(fetch(transport, "https://unpkg.com/x@1.0.0/x.js", budget) is ArtifactCdnReply.Ok)
        // The permit is released again after the fetch.
        assertTrue(budget.acquireFetch())
        assertEquals(6, ARTIFACT_CDN_CONCURRENT_FETCHES)
    }
}
