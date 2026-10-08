package de.joinnoah.pi.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/*
 * The sandbox for HTML artifacts and Mermaid diagrams. Content here is written by an agent or by a
 * file the agent touched, so it is treated as hostile: JavaScript runs, but nothing leaves the
 * WebView. There is no JavaScript bridge (no addJavascriptInterface, no addWebMessageListener) on any
 * view made here, every navigation is refused, and every request except the one document (and, for
 * Mermaid, its two bundled scripts) is answered with an empty 403 before it reaches the network
 * stack. A Content-Security-Policy header and blockNetworkLoads back that up.
 */

internal enum class ArtifactKind { Html, Mermaid }

/** What [artifactRequestPolicy] decides for one request. */
internal enum class ArtifactDecision { Document, MermaidLibrary, MermaidViewerScript, Deny }

/** The origin the sandbox serves from. `.invalid` never resolves, so a leak could not reach a server. */
internal fun artifactHost(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(8).also(random::nextBytes)
    return "a" + bytes.joinToString("") { "%02x".format(it) } + ".artifact.invalid"
}

internal fun artifactDocumentUrl(host: String) = "https://$host/index.html"

/**
 * Whether the request for [url] may be answered, and with what. Only a GET of the exact document URL
 * on [host] is allowed, plus the two bundled Mermaid scripts for [ArtifactKind.Mermaid]. Anything else
 * (other hosts, other paths, queries, fragments in the request, other methods) is denied.
 */
internal fun artifactRequestPolicy(url: String, method: String, host: String, kind: ArtifactKind): ArtifactDecision {
    if (method != "GET") return ArtifactDecision.Deny
    val base = "https://$host/"
    if (!url.startsWith(base)) return ArtifactDecision.Deny
    return when (url.removePrefix(base)) {
        "index.html" -> ArtifactDecision.Document
        "mermaid.min.js" -> if (kind == ArtifactKind.Mermaid) ArtifactDecision.MermaidLibrary else ArtifactDecision.Deny
        "viewer.js" -> if (kind == ArtifactKind.Mermaid) ArtifactDecision.MermaidViewerScript else ArtifactDecision.Deny
        else -> ArtifactDecision.Deny
    }
}

/**
 * The CSP header. `sandbox allow-scripts` gives the page an opaque origin with no storage, forms,
 * popups or top navigation; the fetch directives close the rest. HTML artifacts may use inline
 * script and style; Mermaid only runs the two bundled scripts from [host].
 */
internal fun artifactContentSecurityPolicy(kind: ArtifactKind, host: String): String =
    when (kind) {
        ArtifactKind.Html ->
            "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; " +
                "connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
        ArtifactKind.Mermaid ->
            "sandbox allow-scripts; default-src 'none'; script-src https://$host; " +
                "style-src 'unsafe-inline'; img-src data:; font-src data:; " +
                "connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
    }

/** What a sandboxed view reports back. Called on the main thread. */
internal class ArtifactCallbacks(
    /** The document finished loading. */
    val onLoaded: () -> Unit = {},
    /** The page title changed; Mermaid signals "ok" or "error" this way. */
    val onTitle: (String) -> Unit = {},
    /** The main document failed to load or the web process died; the view must be dropped. */
    val onFailed: () -> Unit = {},
)

/** One page to show: what it is, where it is served from, and its text. */
internal class ArtifactPage(
    val kind: ArtifactKind,
    /** The HTML document, or for Mermaid the diagram source. */
    val text: String,
    val dark: Boolean = false,
    val host: String = artifactHost(),
) {
    val url: String get() = artifactDocumentUrl(host)
}

private val EMPTY_FORBIDDEN get() =
    WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun responseHeaders(csp: String) =
    mapOf(
        "Content-Security-Policy" to csp,
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
    )

/** Answers a request from the page or denies it; never reaches the network. */
internal fun artifactResponse(context: Context, page: ArtifactPage, url: String, method: String): WebResourceResponse {
    val csp = artifactContentSecurityPolicy(page.kind, page.host)
    fun asset(name: String, mime: String) =
        WebResourceResponse(mime, "utf-8", 200, "OK", responseHeaders(csp), context.assets.open("mermaid/$name"))
    return when (artifactRequestPolicy(url, method, page.host, page.kind)) {
        ArtifactDecision.Document ->
            WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                responseHeaders(csp),
                ByteArrayInputStream(
                    when (page.kind) {
                        ArtifactKind.Html -> page.text.toByteArray(Charsets.UTF_8)
                        ArtifactKind.Mermaid -> context.assets.open("mermaid/viewer.html").use { it.readBytes() }
                    }
                ),
            )
        ArtifactDecision.MermaidLibrary -> asset("mermaid.min.js", "text/javascript")
        ArtifactDecision.MermaidViewerScript -> asset("viewer.js", "text/javascript")
        ArtifactDecision.Deny -> EMPTY_FORBIDDEN
    }
}

/** Applies every sandbox setting. Kept apart from view creation so a test can check them one by one. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configureArtifactWebView(view: WebView) {
    with(view.settings) {
        javaScriptEnabled = true
        blockNetworkLoads = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        builtInZoomControls = false
        displayZoomControls = false
    }
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    view.setDownloadListener { _, _, _, _, _ -> }
    view.setNetworkAvailable(false)
    view.isHapticFeedbackEnabled = false
}

/** Refuses geolocation, permission prompts, file choosers and new windows; reports Mermaid's title signal. */
internal class ArtifactChromeClient(private val callbacks: ArtifactCallbacks) : WebChromeClient() {
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<android.net.Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        return false
    }

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false

    override fun onReceivedTitle(view: WebView?, title: String?) {
        callbacks.onTitle(title.orEmpty())
    }
}

/** Blocks every navigation, serves [page] from memory and denies everything else. */
internal class ArtifactWebViewClient(
    private val context: Context,
    private val page: ArtifactPage,
    private val callbacks: ArtifactCallbacks,
) : WebViewClient() {
    private var finished = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val target = request ?: return EMPTY_FORBIDDEN
        return artifactResponse(context, page, target.url.toString(), target.method)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = EMPTY_FORBIDDEN

    override fun onPageFinished(view: WebView?, url: String?) {
        if (finished || url != page.url) return
        finished = true
        if (page.kind == ArtifactKind.Mermaid)
            view?.evaluateJavascript("renderDiagram(${jsonQuote(page.text)}, ${page.dark})", null)
        callbacks.onLoaded()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
        if (request?.isForMainFrame == true) callbacks.onFailed()
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        callbacks.onFailed()
        return true
    }
}

/**
 * A configured, locked-down WebView showing [page]. [background] fills behind the page.
 * The caller owns it and must pass it to [disposeArtifactWebView].
 */
internal fun createArtifactWebView(
    context: Context,
    page: ArtifactPage,
    callbacks: ArtifactCallbacks,
    background: Int = Color.WHITE,
): WebView =
    WebView(context).also { view ->
        configureArtifactWebView(view)
        view.setBackgroundColor(background)
        view.webViewClient = ArtifactWebViewClient(context, page, callbacks)
        view.webChromeClient = ArtifactChromeClient(callbacks)
        view.loadUrl(page.url)
    }

/** Tears a sandboxed view down so nothing it held outlives it. */
internal fun disposeArtifactWebView(view: WebView) {
    runCatching {
        view.stopLoading()
        view.loadUrl("about:blank")
        view.clearHistory()
        view.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        view.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }
}

/**
 * Quotes [text] as a JavaScript string literal that is also safe inside an HTML script block:
 * U+2028 and U+2029 (line terminators in old engines) and `</` are escaped.
 */
internal fun jsonQuote(text: String): String =
    buildString(text.length + 2) {
        append('"')
        for ((index, c) in text.withIndex()) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '/' && index > 0 && text[index - 1] == '<' -> append("\\/")
                c == ' ' -> append("\\u2028")
                c == ' ' -> append("\\u2029")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

private val externalReferenceRegex =
    Regex("""(?:\b(?:src|href|poster|data|action)\s*=\s*|url\(\s*)(?:"([^"]*)"|'([^']*)'|([^\s>)"']+))""", RegexOption.IGNORE_CASE)

private const val EXTERNAL_SCAN_CHARS = 256 * 1024

/**
 * References in the first 256 KB of [html] that the sandbox cannot satisfy: anything other than a
 * `data:` or `blob:` URL, a `#fragment`, or `javascript:`. Used only for a notice; the sandbox
 * refuses these requests either way.
 */
internal fun externalReferences(html: String): List<String> =
    externalReferenceRegex
        .findAll(html.take(EXTERNAL_SCAN_CHARS))
        .map { it.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty() }
        .filter { ref ->
            val value = ref.trim().lowercase()
            value.isNotEmpty() && !value.startsWith("#") && !value.startsWith("data:") &&
                !value.startsWith("blob:") && !value.startsWith("javascript:")
        }
        .distinct()
        .toList()
