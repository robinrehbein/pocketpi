package de.joinnoah.pi.remote

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/*
 * The two network layers of the artifact sandbox that sit below the URL loader. The URL loader is
 * already closed (shouldInterceptRequest, blockNetworkLoads, the CSP header), but WebRTC and network
 * hints such as <link rel=preconnect> open sockets without going through it, and no WebView setting
 * turns them off. Both layers fail closed: a view that cannot get them shows no page (proxy) or runs
 * no JavaScript (WebRTC).
 */

/** A proxy nothing listens on. Port 1 on loopback refuses every connection. */
internal const val ARTIFACT_DEAD_PROXY = "http://127.0.0.1:1"

/**
 * The proxy override for this process: every scheme goes to [proxy], nothing goes direct, and
 * [ProxyConfig.Builder.removeImplicitRules] drops Chromium's implicit bypass for loopback and
 * link-local addresses, so those go to [proxy] too. Never add a direct rule here.
 */
internal fun artifactProxyConfig(proxy: String = ARTIFACT_DEAD_PROXY): ProxyConfig =
    ProxyConfig.Builder().addProxyRule(proxy).removeImplicitRules().build()

/** The WebView calls [ArtifactNetworkLock] needs, apart so a test can stand in for them. */
internal interface ArtifactProxyBackend {
    /** Whether the installed WebView supports a proxy override. */
    fun supported(): Boolean

    /** Applies [config] process-wide and runs [onApplied] on [executor] once it is in force. */
    fun apply(config: ProxyConfig, executor: Executor, onApplied: Runnable)
}

private object WebViewProxyBackend : ArtifactProxyBackend {
    override fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    override fun apply(config: ProxyConfig, executor: Executor, onApplied: Runnable) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) error("PROXY_OVERRIDE is not supported")
        ProxyController.getInstance().setProxyOverride(config, executor, onApplied)
    }
}

/**
 * Layer 1: points every WebView in this process at [ARTIFACT_DEAD_PROXY]. HTTP(S), WebSocket,
 * preconnect sockets and WebRTC's TCP paths (TURN over TCP or TLS, ICE-TCP) then end at a refused
 * connection, and connections through the proxy do not resolve host names locally. A
 * `<link rel=dns-prefetch>` may still make the device do a DNS lookup: the network-hints resolver does
 * not go through the proxy (unmeasured). That is a known residual channel. UDP (STUN, TURN over UDP, ICE host
 * candidates) does not go through a proxy; [ARTIFACT_WEBRTC_BLOCK_SCRIPT] is the only layer for it.
 *
 * The override is applied lazily, the first time a sandboxed view is made, and only once per process.
 * It is process-wide on purpose and is never cleared in production: the app has no other WebView, and
 * any WebView added to the app later inherits the dead proxy. Custom Tabs, OkHttp and Firebase do not
 * use WebView's network stack and are unaffected.
 *
 * Main thread only.
 */
internal class ArtifactNetworkLock(private val backend: ArtifactProxyBackend) {
    private enum class State { Idle, Pending, Ready, Unsupported }

    private class Waiter(val onReady: () -> Unit, val onUnsupported: () -> Unit)

    private var state = State.Idle
    private val waiting = mutableListOf<Waiter>()

    /** Whether the dead proxy is in force. */
    val ready: Boolean get() = state == State.Ready

    /**
     * Runs [onReady] once the dead proxy is in force, or [onUnsupported] if it cannot be. Callers that
     * arrive while the override is being applied wait for the same result. [onUnsupported] means the
     * caller must not load anything.
     */
    fun ensure(onReady: () -> Unit, onUnsupported: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "ArtifactNetworkLock is main-thread only" }
        when (state) {
            State.Ready -> onReady()
            State.Unsupported -> onUnsupported()
            State.Pending -> waiting += Waiter(onReady, onUnsupported)
            State.Idle -> {
                // A clean false is permanent; a failed check is not, so a later view asks again.
                val supported = try { backend.supported() } catch (_: RuntimeException) { null }
                if (supported == false) {
                    state = State.Unsupported
                    onUnsupported()
                    return
                }
                state = State.Pending
                waiting += Waiter(onReady, onUnsupported)
                if (supported == null) {
                    settle(State.Idle)
                    return
                }
                val main = Handler(Looper.getMainLooper())
                try {
                    backend.apply(artifactProxyConfig(), Executor { main.post(it) }) { settle(State.Ready) }
                } catch (_: RuntimeException) {
                    // Not applied: fail every waiter, and let a later view try again.
                    settle(State.Idle)
                }
            }
        }
    }

    private fun settle(next: State) {
        if (state != State.Pending) return
        state = next
        val done = waiting.toList()
        waiting.clear()
        done.forEach { if (next == State.Ready) it.onReady() else it.onUnsupported() }
    }

    companion object {
        /** The lock every sandboxed view in the app uses. */
        val process = ArtifactNetworkLock(WebViewProxyBackend)
    }
}

/** The WebRTC interfaces [ARTIFACT_WEBRTC_BLOCK_SCRIPT] removes from every frame. */
internal val ARTIFACT_WEBRTC_INTERFACES =
    listOf(
        "RTCPeerConnection", "webkitRTCPeerConnection", "RTCDataChannel", "RTCIceCandidate",
        "RTCSessionDescription", "RTCRtpSender", "RTCRtpReceiver", "RTCRtpTransceiver", "RTCIceTransport",
        "RTCDtlsTransport", "RTCSctpTransport", "RTCCertificate", "RTCDTMFSender", "RTCPeerConnectionIceEvent",
        "RTCDataChannelEvent", "RTCTrackEvent", "RTCError", "RTCErrorEvent", "RTCEncodedAudioFrame",
        "RTCEncodedVideoFrame", "RTCRtpScriptTransform",
    )

/**
 * Layer 2: runs before any script of every document and removes WebRTC from its global object. Each
 * interface is deleted and then redefined as a non-configurable, read-only `undefined`, so a page can
 * neither use it nor put it back. This is the only layer that stops STUN and other UDP traffic.
 */
internal val ARTIFACT_WEBRTC_BLOCK_SCRIPT: String =
    """
    (function () {
      var names = ${ARTIFACT_WEBRTC_INTERFACES.joinToString(", ", "[", "]") { "\"$it\"" }};
      for (var i = 0; i < names.length; i++) {
        try { delete window[names[i]]; } catch (e) {}
        try {
          Object.defineProperty(window, names[i], { value: undefined, writable: false, enumerable: false, configurable: false });
        } catch (e) {}
      }
    })();
    """.trimIndent()

/**
 * Adds [ARTIFACT_WEBRTC_BLOCK_SCRIPT] to [view] for every origin. Must run before the first load.
 * False when the WebView cannot run document-start scripts or refused this one; the caller must then
 * turn JavaScript off for the view.
 */
internal fun installArtifactWebRtcBlock(view: WebView): Boolean =
    try {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) false
        else {
            WebViewCompat.addDocumentStartJavaScript(view, ARTIFACT_WEBRTC_BLOCK_SCRIPT, setOf("*"))
            true
        }
    } catch (_: RuntimeException) {
        false
    }
