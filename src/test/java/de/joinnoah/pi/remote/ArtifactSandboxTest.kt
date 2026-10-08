package de.joinnoah.pi.remote

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtifactSandboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = "abc123.artifact.invalid"

    @Test fun onlyTheDocumentIsServedForAnHtmlArtifact() {
        val html = ArtifactKind.Html
        assertEquals(ArtifactDecision.Document, artifactRequestPolicy("https://$host/index.html", "GET", host, html))
        listOf(
            "https://$host/index.html?x=1",
            "https://$host/other.html",
            "https://$host/mermaid.min.js",
            "https://$host/viewer.js",
            "https://$host/favicon.ico",
            "https://$host/",
            "https://evil.example/index.html",
            "https://${host}.evil.example/index.html",
            "http://$host/index.html",
            "https://other.artifact.invalid/index.html",
            "file:///data/data/x",
            "content://x/y",
            "data:text/html,hi",
            "ws://$host/index.html",
        ).forEach { assertEquals(it, ArtifactDecision.Deny, artifactRequestPolicy(it, "GET", host, html)) }
    }

    @Test fun otherMethodsAreDenied() {
        listOf("POST", "PUT", "OPTIONS", "HEAD", "get").forEach {
            assertEquals(it, ArtifactDecision.Deny, artifactRequestPolicy("https://$host/index.html", it, host, ArtifactKind.Html))
        }
    }

    @Test fun mermaidAddsExactlyTwoBundledScripts() {
        val mermaid = ArtifactKind.Mermaid
        assertEquals(ArtifactDecision.Document, artifactRequestPolicy("https://$host/index.html", "GET", host, mermaid))
        assertEquals(ArtifactDecision.MermaidLibrary, artifactRequestPolicy("https://$host/mermaid.min.js", "GET", host, mermaid))
        assertEquals(ArtifactDecision.MermaidViewerScript, artifactRequestPolicy("https://$host/viewer.js", "GET", host, mermaid))
        assertEquals(ArtifactDecision.Deny, artifactRequestPolicy("https://$host/mermaid.min.js.map", "GET", host, mermaid))
        assertEquals(ArtifactDecision.Deny, artifactRequestPolicy("https://$host/../etc", "GET", host, mermaid))
    }

    @Test fun theHostIsRandomAndNeverResolves() {
        val hosts = List(20) { artifactHost() }
        assertEquals(20, hosts.toSet().size)
        hosts.forEach { assertTrue(it, it.matches(Regex("a[0-9a-f]{16}\\.artifact\\.invalid"))) }
        assertEquals("https://$host/index.html", artifactDocumentUrl(host))
    }

    @Test fun theContentSecurityPolicyClosesEveryFetchDirective() {
        val html = artifactContentSecurityPolicy(ArtifactKind.Html, host)
        listOf(
            "sandbox allow-scripts", "default-src 'none'", "frame-src 'none'",
            "form-action 'none'", "base-uri 'none'", "media-src data: blob:",
        ).forEach { assertTrue(it, it in html) }
        assertFalse("connect-src 'none'" in html)
        assertFalse("http:" in html)
        assertFalse("*" in html)
        assertFalse("allow-same-origin" in html)
        val mermaid = artifactContentSecurityPolicy(ArtifactKind.Mermaid, host)
        assertTrue("script-src https://$host;" in mermaid)
        assertFalse("unsafe-eval" in mermaid)
        assertFalse("unsafe-inline'; style" in mermaid)
        assertTrue("connect-src 'none'" in mermaid)
    }

    @Test fun theHtmlPolicyNamesTheCdnAllowlistAndNothingElse() {
        val html = artifactContentSecurityPolicy(ArtifactKind.Html, host)
        val scripts = "https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com"
        assertEquals(
            "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' $scripts; " +
                "style-src 'unsafe-inline' $scripts https://fonts.googleapis.com; img-src data: blob: $scripts; " +
                "font-src data: https://fonts.gstatic.com $scripts; media-src data: blob:; " +
                "connect-src $scripts; frame-src 'none'; form-action 'none'; base-uri 'none'",
            html,
        )
        // Every host in the header is on the allowlist, and the header names no other.
        val hosts = Regex("https://([^\\s;]+)").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue(hosts.all { artifactCdnAllowed("https://$it/x") })
        assertFalse(host in html)
    }

    @Test fun settingsAreLockedDown() {
        val view = WebView(context)
        configureArtifactWebView(view)
        @Suppress("DEPRECATION")
        with(view.settings) {
            assertTrue(javaScriptEnabled)
            assertTrue(blockNetworkLoads)
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            assertFalse(allowFileAccessFromFileURLs)
            assertFalse(allowUniversalAccessFromFileURLs)
            assertFalse(domStorageEnabled)
            assertFalse(databaseEnabled)
            assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertFalse(supportMultipleWindows())
            assertTrue(mediaPlaybackRequiresUserGesture)
            assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
        }
    }

    /** Stands in for WebView's proxy override; [applied] runs the pending callback. */
    private class FakeProxy(
        var supported: Boolean = true,
        var throwOnApply: Boolean = false,
        var throwOnSupported: Boolean = false,
    ) : ArtifactProxyBackend {
        val configs = mutableListOf<androidx.webkit.ProxyConfig>()
        private var pending: (() -> Unit)? = null

        override fun supported(): Boolean {
            if (throwOnSupported) throw IllegalStateException("no WebView")
            return supported
        }

        override fun apply(config: androidx.webkit.ProxyConfig, executor: java.util.concurrent.Executor, onApplied: Runnable) {
            if (throwOnApply) throw IllegalStateException("refused")
            configs += config
            pending = { executor.execute(onApplied) }
        }

        fun applied() {
            checkNotNull(pending).invoke()
            pending = null
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun lastLoaded(view: WebView) = org.robolectric.Shadows.shadowOf(view).lastLoadedUrl

    private fun readyLock() =
        FakeProxy().let { proxy -> ArtifactNetworkLock(proxy).also { it.ensure({}, {}); proxy.applied() } }

    private fun sandboxed(
        page: ArtifactPage,
        lock: ArtifactNetworkLock,
        callbacks: ArtifactCallbacks = ArtifactCallbacks(),
        webRtcBlock: (WebView) -> Boolean = { true },
    ) = createArtifactWebView(context, page, callbacks, networkLock = lock, webRtcBlock = webRtcBlock)

    @Test fun theProxyConfigSendsEverythingToTheDeadProxy() {
        val config = artifactProxyConfig()
        val rule = config.proxyRules.single()
        assertEquals("http://127.0.0.1:1", rule.url)
        assertEquals(androidx.webkit.ProxyConfig.MATCH_ALL_SCHEMES, rule.schemeFilter)
        // removeImplicitRules: loopback and link-local are not bypassed. No direct rule, no other bypass.
        assertEquals(listOf("<-loopback>"), config.bypassRules)
        assertFalse(config.isReverseBypassEnabled)
        assertEquals("http://10.0.2.15:3128", artifactProxyConfig("http://10.0.2.15:3128").proxyRules.single().url)
    }

    @Test fun theFirstViewWaitsForTheProxyAndLaterViewsDoNot() {
        val proxy = FakeProxy()
        val lock = ArtifactNetworkLock(proxy)
        val first = ArtifactPage(ArtifactKind.Html, "<p>1</p>")
        val second = ArtifactPage(ArtifactKind.Html, "<p>2</p>")
        val a = sandboxed(first, lock)
        val b = sandboxed(second, lock)
        idle()
        assertEquals(null, lastLoaded(a))
        assertEquals(null, lastLoaded(b))
        assertFalse(lock.ready)
        proxy.applied()
        assertTrue(lock.ready)
        assertEquals(first.url, lastLoaded(a))
        assertEquals(second.url, lastLoaded(b))
        val third = ArtifactPage(ArtifactKind.Html, "<p>3</p>")
        assertEquals(third.url, lastLoaded(sandboxed(third, lock)))
        assertEquals("the override is applied once per process", 1, proxy.configs.size)
        assertEquals(artifactProxyConfig().proxyRules.single().url, proxy.configs.single().proxyRules.single().url)
        assertEquals(listOf("<-loopback>"), proxy.configs.single().bypassRules)
    }

    @Test fun withoutTheProxyOverrideNothingLoadsAndTheViewFails() {
        val proxy = FakeProxy(supported = false)
        val lock = ArtifactNetworkLock(proxy)
        var failed = 0
        val view = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>hi</p>"), lock, ArtifactCallbacks(onFailed = { failed++ }))
        assertEquals("onFailed is posted, not called inside the factory", 0, failed)
        idle()
        assertEquals(1, failed)
        assertEquals(null, lastLoaded(view))
        assertTrue(proxy.configs.isEmpty())
        // It stays refused for later views.
        val next = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>again</p>"), lock, ArtifactCallbacks(onFailed = { failed++ }))
        idle()
        assertEquals(2, failed)
        assertEquals(null, lastLoaded(next))
    }

    @Test fun aRefusedOverrideFailsEveryWaiterAndIsTriedAgain() {
        val proxy = FakeProxy(throwOnApply = true)
        val lock = ArtifactNetworkLock(proxy)
        var failed = 0
        val view = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>hi</p>"), lock, ArtifactCallbacks(onFailed = { failed++ }))
        idle()
        assertEquals(1, failed)
        assertEquals(null, lastLoaded(view))
        assertFalse(lock.ready)
        proxy.throwOnApply = false
        val page = ArtifactPage(ArtifactKind.Html, "<p>retry</p>")
        val retry = sandboxed(page, lock)
        proxy.applied()
        assertEquals(page.url, lastLoaded(retry))
    }

    @Test fun aFailingSupportCheckFailsTheWaiterButIsAskedAgainAndACleanFalseIsPermanent() {
        val proxy = FakeProxy(throwOnSupported = true)
        val lock = ArtifactNetworkLock(proxy)
        var failed = 0
        val view = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>hi</p>"), lock, ArtifactCallbacks(onFailed = { failed++ }))
        idle()
        assertEquals(1, failed)
        assertEquals(null, lastLoaded(view))
        assertFalse(lock.ready)
        proxy.throwOnSupported = false
        val page = ArtifactPage(ArtifactKind.Html, "<p>retry</p>")
        val retry = sandboxed(page, lock)
        proxy.applied()
        assertEquals(page.url, lastLoaded(retry))

        val refusing = FakeProxy(supported = false)
        val refused = ArtifactNetworkLock(refusing)
        refused.ensure({}, {})
        refusing.supported = true
        var unsupported = 0
        refused.ensure({}, { unsupported++ })
        assertEquals("a clean false stays refused", 1, unsupported)
        assertTrue(refusing.configs.isEmpty())
    }

    @Test fun aViewDisposedBeforeTheProxyIsReadyIsNotLoadedOrFailed() {
        val proxy = FakeProxy()
        val lock = ArtifactNetworkLock(proxy)
        var failed = 0
        val view = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>hi</p>"), lock, ArtifactCallbacks(onFailed = { failed++ }))
        disposeArtifactWebView(view)
        proxy.applied()
        assertEquals("about:blank", lastLoaded(view))
        assertEquals(0, failed)
    }

    @Test fun withoutTheWebRtcBlockAnHtmlPageRunsNoScript() {
        val lock = readyLock()
        var blocked: WebView? = null
        val page = ArtifactPage(ArtifactKind.Html, "<p>hi</p>")
        val view = sandboxed(page, lock, webRtcBlock = { blocked = it; false })
        assertTrue("the block is offered the view itself", blocked === view)
        assertFalse(view.settings.javaScriptEnabled)
        assertEquals(page.url, lastLoaded(view))
        val withBlock = sandboxed(ArtifactPage(ArtifactKind.Html, "<p>ok</p>"), lock)
        assertTrue(withBlock.settings.javaScriptEnabled)
    }

    @Test fun withoutTheWebRtcBlockAMermaidDiagramFailsAndIsNotLoaded() {
        val proxy = FakeProxy()
        val lock = ArtifactNetworkLock(proxy)
        var failed = 0
        val view = sandboxed(ArtifactPage(ArtifactKind.Mermaid, "graph TD\nA-->B"), lock, ArtifactCallbacks(onFailed = { failed++ }), webRtcBlock = { false })
        idle()
        assertEquals(1, failed)
        assertFalse(view.settings.javaScriptEnabled)
        assertEquals(null, lastLoaded(view))
    }

    @Test fun theWebRtcBlockRemovesEveryInterfaceForGood() {
        val script = ARTIFACT_WEBRTC_BLOCK_SCRIPT
        listOf(
            "RTCPeerConnection", "webkitRTCPeerConnection", "RTCDataChannel", "RTCIceCandidate", "RTCSessionDescription",
            "RTCRtpSender", "RTCRtpReceiver", "RTCRtpTransceiver", "RTCIceTransport", "RTCDtlsTransport",
            "RTCSctpTransport", "RTCCertificate", "RTCDTMFSender", "RTCPeerConnectionIceEvent", "RTCDataChannelEvent",
            "RTCTrackEvent", "RTCError", "RTCErrorEvent", "RTCEncodedAudioFrame", "RTCEncodedVideoFrame",
            "RTCRtpScriptTransform",
        ).forEach { assertTrue(it, "\"$it\"" in script) }
        assertTrue("delete window[names[i]]" in script)
        assertTrue("value: undefined, writable: false, enumerable: false, configurable: false" in script)
    }

    @Test fun everyNavigationIsBlockedAndNothingIsBridged() {
        val page = ArtifactPage(ArtifactKind.Html, "<p>hi</p>")
        val lock = readyLock()
        val view = sandboxed(page, lock)
        val client = view.webViewClient as ArtifactWebViewClient
        assertTrue(client.shouldOverrideUrlLoading(view, null as android.webkit.WebResourceRequest?))
        @Suppress("DEPRECATION")
        assertTrue(client.shouldOverrideUrlLoading(view, "https://example.com/"))
        val shadow = org.robolectric.Shadows.shadowOf(view)
        assertEquals(page.url, shadow.lastLoadedUrl)
        assertTrue("no JavaScript interface may be registered", shadow.getJavascriptInterface("Android") == null)
        disposeArtifactWebView(view)
    }

    @Test fun deniedRequestsAreEmpty403s() {
        val page = ArtifactPage(ArtifactKind.Html, "<p>hi</p>", host = host)
        val denied = artifactResponse(context, page, "https://example.com/x.js", "GET")
        assertEquals(403, denied.statusCode)
        assertEquals(0, denied.data.readBytes().size)
        assertEquals(403, artifactResponse(context, page, "https://$host/index.html", "POST").statusCode)
    }

    @Test fun theDocumentCarriesThePolicyHeader() {
        val page = ArtifactPage(ArtifactKind.Html, "<p>héllo</p>", host = host)
        val response = artifactResponse(context, page, page.url, "GET")
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        assertEquals(artifactContentSecurityPolicy(ArtifactKind.Html, host), response.responseHeaders["Content-Security-Policy"])
        assertEquals("off", response.responseHeaders["X-DNS-Prefetch-Control"])
        assertEquals("<p>héllo</p>", response.data.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun mermaidServesItsBundledAssetsOnly() {
        val page = ArtifactPage(ArtifactKind.Mermaid, "graph TD\nA-->B", host = host)
        val library = artifactResponse(context, page, "https://$host/mermaid.min.js", "GET")
        assertEquals(200, library.statusCode)
        assertTrue(library.data.readNBytes(16).isNotEmpty())
        val viewer = artifactResponse(context, page, "https://$host/viewer.js", "GET")
        assertTrue(viewer.data.readBytes().toString(Charsets.UTF_8).contains("renderDiagram"))
        val index = artifactResponse(context, page, page.url, "GET")
        val body = index.data.readBytes().toString(Charsets.UTF_8)
        assertTrue("mermaid.min.js" in body)
        assertEquals(artifactContentSecurityPolicy(ArtifactKind.Mermaid, host), index.responseHeaders["Content-Security-Policy"])
        // The diagram text never goes into the document; it is passed in after load.
        assertFalse("A-->B" in body)
    }

    @Test fun theChromeClientRefusesEverythingItMayBeAsked() {
        val chrome = ArtifactChromeClient(ArtifactCallbacks())
        var geo: Triple<String?, Boolean, Boolean>? = null
        chrome.onGeolocationPermissionsShowPrompt("https://x", android.webkit.GeolocationPermissions.Callback { o, a, r -> geo = Triple(o, a, r) })
        assertEquals(Triple<String?, Boolean, Boolean>("https://x", false, false), geo)
        assertFalse(chrome.onCreateWindow(null, false, true, null))
        var chooser: Array<android.net.Uri>? = arrayOf()
        assertTrue(chrome.onShowFileChooser(null, { chooser = it }, null))
        assertEquals(null, chooser)
        val results = List(4) { org.robolectric.shadows.ShadowJsPromptResult.newInstance() }
        assertTrue(chrome.onJsAlert(null, "https://x", "hi", results[0]))
        assertTrue(chrome.onJsConfirm(null, "https://x", "sure?", results[1]))
        assertTrue(chrome.onJsBeforeUnload(null, "https://x", "leave?", results[2]))
        assertTrue(chrome.onJsPrompt(null, "https://x", "name?", "d", results[3]))
        results.forEach { assertTrue(org.robolectric.Shadows.shadowOf(it).wasCancelled()) }
    }

    @Test fun titleChangesReachTheCallback() {
        val titles = mutableListOf<String>()
        ArtifactChromeClient(ArtifactCallbacks(onTitle = { titles += it })).also {
            it.onReceivedTitle(null, "ok")
            it.onReceivedTitle(null, null)
        }
        assertEquals(listOf("ok", ""), titles)
    }

    @Test fun jsonQuoteIsSafeInsideAScriptBlock() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsonQuote("a\"b\\c\nd"))
        assertEquals("\"<\\/script>\"", jsonQuote("</script>"))
        assertEquals("\"\\u2028\\u2029\"", jsonQuote("\u2028\u2029"))
        assertEquals("\"\\u0000\\u001f\"", jsonQuote("\u0000\u001f"))
        assertEquals("\"é😀\"", jsonQuote("é😀"))
        assertNotEquals("\"<\"", jsonQuote("</"))
    }

    @Test fun externalReferencesListWhatThePreviewCannotLoad() {
        val html =
            """
            <link rel="stylesheet" href="https://cdn.example/x.css">
            <script src='app.js'></script>
            <img src="data:image/png;base64,AAAA"><a href="#top">x</a><a href="javascript:void(0)">y</a>
            <img src="blob:abc"><div style="background:url(img/bg.png)"></div>
            <style>@font-face { src: url("fonts/a.woff2") }</style>
            <img src="">
            """.trimIndent()
        assertEquals(listOf("https://cdn.example/x.css", "app.js", "img/bg.png", "fonts/a.woff2"), externalReferences(html))
        assertEquals(emptyList<String>(), externalReferences("<p>plain</p><svg><use href=\"#a\"/></svg>"))
    }

    @Test fun externalReferencesCanonicaliseBeforeTheAllowlistCheck() {
        val html =
            """
            <script src="HTTPS://CDN.JSDELIVR.NET/npm/a.js"></script>
            <script src="https://unpkg.com:443/b.js"></script>
            <link href="https://fonts.googleapis.com/css2?family=A&amp;display=swap">
            <script src="//cdnjs.cloudflare.com/c.js"></script>
            <script src="https://unpkg.com&#58;8443/x.js"></script>
            <script src="https://evil.example/&amp;.js"></script>
            """.trimIndent()
        assertEquals(listOf("https://unpkg.com&#58;8443/x.js", "https://evil.example/&amp;.js"), externalReferences(html))
    }

    @Test fun externalReferencesOnlyScanTheStart() {
        val html = "x".repeat(300 * 1024) + "<img src=\"late.png\">"
        assertEquals(emptyList<String>(), externalReferences(html))
        assertNotNull(externalReferences("<img src=\"early.png\">" + html).singleOrNull())
    }

    private class CountingFetcher(val reply: ArtifactCdnReply) : ArtifactCdnFetcher {
        val urls = mutableListOf<String>()

        override fun fetch(url: String, userAgent: String, budget: ArtifactCdnBudget): ArtifactCdnReply {
            urls += url
            return reply
        }
    }

    private fun request(url: String, method: String = "GET", mainFrame: Boolean = false, headers: Map<String, String> = emptyMap()) =
        object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = method
            override fun getRequestHeaders() = headers
        }

    private fun clientWith(fetcher: ArtifactCdnFetcher, kind: ArtifactKind = ArtifactKind.Html) =
        ArtifactWebViewClient(context, ArtifactPage(kind, "<p>x</p>", host = host), ArtifactCallbacks(), lazyOf(fetcher), "UA")

    @Test fun anAllowlistedGetIsAnsweredFromTheFetcherWithCorsHeaders() {
        val fetcher = CountingFetcher(ArtifactCdnReply.Ok("text/javascript", "utf-8", "window.x=1".toByteArray()))
        val response = clientWith(fetcher).shouldInterceptRequest(null, request("https://cdn.jsdelivr.net/npm/x.js", headers = mapOf("Cookie" to "a=b", "Referer" to "r")))!!
        assertEquals(listOf("https://cdn.jsdelivr.net/npm/x.js"), fetcher.urls)
        assertEquals(200, response.statusCode)
        assertEquals("text/javascript", response.mimeType)
        assertEquals("*", response.responseHeaders["Access-Control-Allow-Origin"])
        assertEquals("cross-origin", response.responseHeaders["Cross-Origin-Resource-Policy"])
        assertEquals("nosniff", response.responseHeaders["X-Content-Type-Options"])
        assertEquals("window.x=1", response.data.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun everythingElseStaysAnEmpty403AndNeverReachesTheFetcher() {
        val fetcher = CountingFetcher(ArtifactCdnReply.Ok("text/javascript", null, ByteArray(1)))
        val client = clientWith(fetcher)
        listOf(
            request("https://cdn.jsdelivr.net.evil.example/x.js"),
            request("https://evil.example/x.js"),
            request("http://cdn.jsdelivr.net/x.js"),
            request("https://cdn.jsdelivr.net:8443/x.js"),
            request("https://cdn.jsdelivr.net/x.js", method = "POST"),
            request("https://cdn.jsdelivr.net/x.js", method = "HEAD"),
            request("https://cdn.jsdelivr.net/x.js", mainFrame = true),
        ).forEach {
            val response = client.shouldInterceptRequest(null, it)!!
            assertEquals("${it.method} ${it.url}", 403, response.statusCode)
            assertEquals(0, response.data.readBytes().size)
        }
        assertEquals(emptyList<String>(), fetcher.urls)
        // Mermaid has no CDN.
        val mermaid = clientWith(fetcher, ArtifactKind.Mermaid)
        assertEquals(403, mermaid.shouldInterceptRequest(null, request("https://cdn.jsdelivr.net/x.js"))!!.statusCode)
        assertEquals(emptyList<String>(), fetcher.urls)
    }

    @Test fun aRefusedFetchReachesThePageAsAnEmptyErrorResponse() {
        listOf(404, 504, 403).forEach { status ->
            val response = clientWith(CountingFetcher(ArtifactCdnReply.Refused(status))).shouldInterceptRequest(null, request("https://unpkg.com/x.js"))!!
            assertEquals(status, response.statusCode)
            assertEquals(0, response.data.readBytes().size)
        }
    }

    @Test fun referencesOnTheAllowlistAreNotListedAsExternal() {
        val html =
            """<script src="https://cdn.jsdelivr.net/npm/chart.js"></script><script src="//unpkg.com/x"></script>
               <link href="https://fonts.googleapis.com/css2?family=Inter" rel="stylesheet">
               <script src="https://cdn.jsdelivr.net.evil.example/x.js"></script><img src="http://unpkg.com/x.png"><img src="logo.png">"""
        assertEquals(
            listOf("https://cdn.jsdelivr.net.evil.example/x.js", "http://unpkg.com/x.png", "logo.png"),
            externalReferences(html),
        )
    }
}
