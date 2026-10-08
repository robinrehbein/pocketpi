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

    @Test fun everyNavigationIsBlockedAndNothingIsBridged() {
        val page = ArtifactPage(ArtifactKind.Html, "<p>hi</p>")
        val view = createArtifactWebView(context, page, ArtifactCallbacks())
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
        assertFalse(chrome.onShowFileChooser(null, { chooser = it }, null))
        assertEquals(null, chooser)
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
        assertEquals("\"\\u2028\\u2029\"", jsonQuote("  "))
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
