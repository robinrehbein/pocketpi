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
            "sandbox allow-scripts", "default-src 'none'", "connect-src 'none'", "frame-src 'none'",
            "form-action 'none'", "base-uri 'none'", "img-src data: blob:", "font-src data:",
        ).forEach { assertTrue(it, it in html) }
        assertFalse("http" in html)
        assertFalse("allow-same-origin" in html)
        val mermaid = artifactContentSecurityPolicy(ArtifactKind.Mermaid, host)
        assertTrue("script-src https://$host;" in mermaid)
        assertFalse("unsafe-eval" in mermaid)
        assertFalse("unsafe-inline'; style" in mermaid)
        assertTrue("connect-src 'none'" in mermaid)
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
    private class FakeProxy(var supported: Boolean = true, var throwOnApply: Boolean = false) : ArtifactProxyBackend {
        val configs = mutableListOf<androidx.webkit.ProxyConfig>()
        private var pending: (() -> Unit)? = null

        override fun supported() = supported

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

    @Test fun externalReferencesOnlyScanTheStart() {
        val html = "x".repeat(300 * 1024) + "<img src=\"late.png\">"
        assertEquals(emptyList<String>(), externalReferences(html))
        assertNotNull(externalReferences("<img src=\"early.png\">" + html).singleOrNull())
    }
}
