package de.joinnoah.pi.remote

import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val TERMINAL_ORIGIN = "https://terminal.pocketpi.invalid"
private const val TERMINAL_URL = "$TERMINAL_ORIGIN/index.html"

internal interface TerminalRendererCallbacks {
    fun onReady()
    fun onWritten()
    fun onSize(cols: Int, rows: Int)
    fun onFailure()
}

/** One in-flight message; terminal output never becomes JavaScript source. */
internal class TerminalRenderer internal constructor(val view: WebView, private val callbacks: TerminalRendererCallbacks) {
    private var disposed = false
    private var ready = false
    private var writing = false

    internal fun ready() { if (!disposed) { ready = true; callbacks.onReady() } }
    internal fun written() { if (!disposed && writing) { writing = false; callbacks.onWritten() } }
    internal fun size(cols: Int, rows: Int) { if (!disposed) callbacks.onSize(cols, rows) }
    internal fun fail() { if (!disposed) callbacks.onFailure() }
    internal fun alive() = !disposed
    fun write(text: String): Boolean {
        if (disposed || !ready || writing || text.length > 16384) return false
        writing = true
        return try {
            WebViewCompat.postWebMessage(view, WebMessageCompat(text), Uri.parse(TERMINAL_ORIGIN))
            true
        } catch (_: RuntimeException) { writing = false; fail(); false }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        view.stopLoading()
        view.destroy()
    }
}

internal fun createTerminalWebView(context: Context, callbacks: TerminalRendererCallbacks): TerminalRenderer {
    val view = WebView(context)
    // A wrap-content WebView gives percentage/viewport document heights a zero base.
    view.layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val renderer = TerminalRenderer(view, callbacks)
    view.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        domStorageEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
    }
    view.webChromeClient = WebChromeClient()
    view.setDownloadListener { _, _, _, _, _ -> }
    view.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val uri = request.url
            val paths = mapOf("/index.html" to "text/html", "/terminal.css" to "text/css", "/terminal.js" to "text/javascript", "/lib/xterm.js" to "text/javascript", "/css/xterm.css" to "text/css")
            val mime = paths[uri.path]
            if (uri.scheme == "https" && uri.host == "terminal.pocketpi.invalid" && uri.port == -1 && uri.query == null && mime != null) {
                return WebResourceResponse(mime, "UTF-8", context.assets.open("terminal${uri.path}"))
            }
            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArray(0).inputStream())
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) renderer.fail()
        }
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { renderer.fail(); return true }
    }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) || !installArtifactWebRtcBlock(view)) {
        view.settings.javaScriptEnabled = false
        view.post { renderer.fail() }
        return renderer
    }
    try {
        WebViewCompat.addWebMessageListener(view, "terminalBridge", setOf(TERMINAL_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
            val data = message.data
            if (!isMainFrame || sourceOrigin.toString() != TERMINAL_ORIGIN || data == null || data.length > 128) return@addWebMessageListener
            runCatching {
                val obj = JSONObject(data)
                when (obj.optString("type")) {
                    "ready" -> if (obj.length() == 1) renderer.ready()
                    "written" -> if (obj.length() == 1) renderer.written()
                    "size" -> if (obj.length() == 3) {
                        val cols = obj.getInt("cols"); val rows = obj.getInt("rows")
                        if (cols in 20..500 && rows in 5..300) renderer.size(cols, rows)
                    }
                }
            }
        }
        ArtifactNetworkLock.process.ensure(
            onReady = { if (renderer.alive()) view.loadUrl(TERMINAL_URL) },
            onUnsupported = { renderer.fail() },
        )
    } catch (_: RuntimeException) { renderer.fail() }
    return renderer
}
