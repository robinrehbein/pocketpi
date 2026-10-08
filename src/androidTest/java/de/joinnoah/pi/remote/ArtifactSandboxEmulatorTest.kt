package de.joinnoah.pi.remote

import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the real WebView sandbox on a device. These are the checks Robolectric cannot make: that the
 * intercepted document loads behind the dead proxy, that a hostile page reaches no socket and finds
 * no WebRTC in any frame, that the zeros come from routing and not from a page that never tried, and
 * that Mermaid and the thumbnail capture really draw.
 *
 * The tests that change the process-wide proxy override put the production one back in [restoreProxy].
 */
class ArtifactSandboxEmulatorTest {
    @get:Rule val compose = createComposeRule()

    private val servers = mutableListOf<AutoCloseable>()
    private var proxyChanged = false

    @After fun closeServers() = servers.forEach { runCatching { it.close() } }

    /** Puts the production dead proxy back after a test that replaced or cleared it. */
    @After fun restoreProxy() {
        if (proxyChanged) setProxyOverride(artifactProxyConfig())
        proxyChanged = false
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Sets (or with null clears) the process-wide proxy override and waits until it is in force. */
    private fun setProxyOverride(config: ProxyConfig?) {
        assertTrue("This WebView has no PROXY_OVERRIDE; the sandbox would load nothing", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
        val applied = CountDownLatch(1)
        val direct = Executor { it.run() }
        instrumentation.runOnMainSync {
            val controller = ProxyController.getInstance()
            if (config == null) controller.clearProxyOverride(direct) { applied.countDown() }
            else controller.setProxyOverride(config, direct) { applied.countDown() }
        }
        assertTrue("The proxy override change was not confirmed", applied.await(10, TimeUnit.SECONDS))
    }

    /** Waits until the production network lock has the dead proxy in force, as the first view would. */
    private fun lockNetwork() {
        val done = CountDownLatch(1)
        var unsupported = false
        instrumentation.runOnMainSync {
            ArtifactNetworkLock.process.ensure(onReady = { done.countDown() }, onUnsupported = { unsupported = true; done.countDown() })
        }
        assertTrue("The network lock never answered", done.await(10, TimeUnit.SECONDS))
        assertFalse("The network lock reports no proxy override; the sandbox would load nothing", unsupported)
    }

    /**
     * The non-loopback address the probe also listens on: the first non-loopback IPv4 of the device
     * (10.0.2.15 on the emulator). Chromium bypasses a proxy for loopback unless told otherwise, and
     * libwebrtc may treat loopback differently on other devices, so a loopback-only probe could
     * under-report.
     */
    private fun probeAddress(): InetAddress {
        val address =
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        return checkNotNull(address) { "The device has no non-loopback IPv4 address to probe on" }
    }

    /**
     * One listener per way out, so a failure names the channel that leaked: `http` for URL loads,
     * `preconnect` for network hints, `turn` for the TURN-over-TCP ICE server, `stun` for the UDP one
     * and `ice` for UDP connectivity checks to an injected remote candidate. Each of the first four is
     * there twice: on the device's non-loopback address and, with a `Loopback` suffix, on 127.0.0.1.
     * CI saw preconnect, TURN-TCP and STUN-UDP reach loopback listeners before the network lock, so
     * loopback stays in the probe; the loopback ones also prove the implicit loopback bypass is gone.
     * Any connection or packet means the page got out.
     */
    private class Probe(
        val ip: String,
        val http: Int,
        val httpLoopback: Int,
        val preconnect: Int,
        val preconnectLoopback: Int,
        val turn: Int,
        val turnLoopback: Int,
        val stun: Int,
        val stunLoopback: Int,
        val ice: Int,
        val nonce: String,
        val hits: Map<String, AtomicInteger>,
    ) {
        fun counts(): Map<String, Int> = hits.mapValues { it.value.get() }
    }

    private fun tcpListener(address: InetAddress, counter: AtomicInteger, onLine: ((String) -> Unit)? = null): Int {
        val tcp = ServerSocket(0, 50, address).also { servers += it }
        Thread {
            try {
                while (true) {
                    val socket = tcp.accept()
                    counter.incrementAndGet()
                    if (onLine == null) socket.close()
                    else
                        Thread {
                            socket.use {
                                it.soTimeout = 5_000
                                runCatching { BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1)).readLine() }
                                    .getOrNull()?.let(onLine)
                            }
                        }.apply { isDaemon = true }.start()
                }
            } catch (_: SocketException) {}
        }.apply { isDaemon = true }.start()
        return tcp.localPort
    }

    private fun udpListener(address: InetAddress, counter: AtomicInteger): Int {
        val udp = DatagramSocket(0, address).also { servers += it }
        Thread {
            try {
                val buffer = ByteArray(2048)
                while (true) {
                    udp.receive(DatagramPacket(buffer, buffer.size))
                    counter.incrementAndGet()
                }
            } catch (_: SocketException) {}
        }.apply { isDaemon = true }.start()
        return udp.localPort
    }

    private fun listen(): Probe {
        val address = probeAddress()
        val loopback = InetAddress.getByName("127.0.0.1")
        val hits =
            listOf("http", "httpLoopback", "preconnect", "preconnectLoopback", "turn", "turnLoopback", "stun", "stunLoopback", "ice")
                .associateWith { AtomicInteger() }
        return Probe(
            ip = checkNotNull(address.hostAddress),
            http = tcpListener(address, hits.getValue("http")),
            httpLoopback = tcpListener(loopback, hits.getValue("httpLoopback")),
            preconnect = tcpListener(address, hits.getValue("preconnect")),
            preconnectLoopback = tcpListener(loopback, hits.getValue("preconnectLoopback")),
            turn = tcpListener(address, hits.getValue("turn")),
            turnLoopback = tcpListener(loopback, hits.getValue("turnLoopback")),
            stun = udpListener(address, hits.getValue("stun")),
            stunLoopback = udpListener(loopback, hits.getValue("stunLoopback")),
            ice = udpListener(address, hits.getValue("ice")),
            nonce = "n" + UUID.randomUUID().toString().replace("-", "").take(16),
            hits = hits,
        )
    }

    private fun show(
        page: ArtifactPage,
        callbacks: ArtifactCallbacks,
        webRtcBlock: ((WebView) -> Boolean)? = null,
        cdn: ArtifactCdnFetcher? = null,
    ): WebView {
        var view: WebView? = null
        compose.setContent {
            AndroidView(
                factory = { context ->
                    (
                        if (webRtcBlock == null) createArtifactWebView(context, page, callbacks, cdn = cdn)
                        else createArtifactWebView(context, page, callbacks, webRtcBlock = webRtcBlock, cdn = cdn)
                    ).also { view = it }
                },
                onRelease = ::disposeArtifactWebView,
                modifier = Modifier.size(300.dp, 190.dp),
            )
        }
        compose.waitUntil(10_000) { view != null }
        return checkNotNull(view)
    }

    private fun currentUrl(view: WebView): String? {
        var url: String? = null
        instrumentation.runOnMainSync { url = view.url }
        return url
    }

    /**
     * Tries every way out we know of, then reports in its title: its origin and `typeof
     * RTCPeerConnection` in the main frame, in a fresh about:blank frame read synchronously after it is
     * attached (the hardest case for a document-start script), and in a srcdoc frame that posts its own
     * answer. WebRTC uses whichever of those realms still has it.
     */
    private fun probePage(probe: Probe): String {
        val base = "http://${probe.ip}:${probe.http}"
        val loop = "http://127.0.0.1:${probe.httpLoopback}"
        val hint = "http://${probe.ip}:${probe.preconnect}"
        val named = "${probe.nonce}.invalid"
        return """
            <!doctype html><html><head><title>probe</title>
            <meta http-equiv="refresh" content="0;url=$base/refresh">
            <link rel="prefetch" href="$base/prefetch"><link rel="preconnect" href="$hint"><link rel="dns-prefetch" href="$hint">
            <link rel="preconnect" href="http://127.0.0.1:${probe.preconnectLoopback}">
            <link rel="preconnect" href="https://pre-$named:${probe.preconnect}"><link rel="dns-prefetch" href="//dns-$named"><!-- Nothing observes DNS here: this exercises the path without proving it closed. -->
            <link rel="stylesheet" href="$base/style.css">
            </head><body>
            <img src="$base/img.png"><img src="$loop/img.png"><iframe src="$base/frame"></iframe><script src="$base/script.js"></script>
            <form id="f" action="$base/form" method="post"><input name="a" value="b"></form>
            <script>
            function attempt(f) { try { f(); } catch (e) {} }
            var realms = { main: typeof window.RTCPeerConnection, blank: "noframe", srcdoc: "noreport", renav: "noreport" };
            var blank = null;
            try {
              var frame = document.createElement("iframe");
              document.body.appendChild(frame);
              blank = frame.contentWindow;
              realms.blank = typeof blank.RTCPeerConnection;
            } catch (e) {
              // A frame the page cannot reach into is no way around the block; say why it failed.
              blank = null;
              realms.blank = "unreachable-" + (e && e.name ? e.name : "error");
            }
            // A frame whose document is replaced after its first load: the second document must still have no WebRTC.
            attempt(function () {
              var frame = document.createElement("iframe");
              var loads = 0;
              frame.onload = function () {
                loads++;
                try {
                  if (loads === 1) frame.contentWindow.location.replace("about:blank");
                  else realms.renav = typeof frame.contentWindow.RTCPeerConnection;
                } catch (e) {
                  realms.renav = "unreachable-" + (e && e.name ? e.name : "error");
                }
              };
              document.body.appendChild(frame);
            });
            window.addEventListener("message", function (e) {
              if (e.data && e.data.probe === "srcdoc") realms.srcdoc = String(e.data.type);
            });
            attempt(function () {
              var frame = document.createElement("iframe");
              frame.srcdoc = "<script>parent.postMessage({ probe: 'srcdoc', type: typeof RTCPeerConnection }, '*');<\/script>";
              document.body.appendChild(frame);
            });
            attempt(function () { fetch("$base/fetch", { mode: "no-cors" }).catch(function () {}); });
            attempt(function () { fetch("$loop/fetch", { mode: "no-cors" }).catch(function () {}); });
            attempt(function () { var x = new XMLHttpRequest(); x.open("GET", "$base/xhr"); x.send(); });
            attempt(function () { navigator.sendBeacon("$base/beacon", "x"); });
            attempt(function () { new WebSocket("ws://${probe.ip}:${probe.http}/ws"); });
            attempt(function () { new WebSocket("ws://127.0.0.1:${probe.httpLoopback}/ws"); });
            attempt(function () {
              var PC = window.RTCPeerConnection || window.webkitRTCPeerConnection || (blank && blank.RTCPeerConnection);
              var pc = new PC({ iceServers: [
                { urls: ["stun:${probe.ip}:${probe.stun}", "stun:127.0.0.1:${probe.stunLoopback}", "stun:stun-$named:${probe.stun}"] },
                { urls: [
                  "turn:${probe.ip}:${probe.turn}?transport=tcp", "turn:127.0.0.1:${probe.turnLoopback}?transport=tcp",
                  "turn:turn-$named:${probe.turn}?transport=tcp",
                ], username: "user", credential: "secret" },
              ] });
              pc.createDataChannel("x");
              var peer = new PC();
              pc.createOffer()
                .then(function (o) { return pc.setLocalDescription(o); })
                .then(function () { return peer.setRemoteDescription(pc.localDescription); })
                .then(function () { return peer.createAnswer(); })
                .then(function (a) { return peer.setLocalDescription(a); })
                .then(function () { return pc.setRemoteDescription(peer.localDescription); })
                .then(function () {
                  var mid = (pc.remoteDescription.sdp.match(/a=mid:(\S+)/) || [])[1];
                  return pc.addIceCandidate({ candidate: "candidate:1 1 udp 2122260223 ${probe.ip} ${probe.ice} typ host", sdpMid: mid, sdpMLineIndex: 0 });
                })
                .catch(function () {});
            });
            attempt(function () { window.open("$base/open"); });
            attempt(function () { document.getElementById("f").submit(); });
            attempt(function () { location = "$base/location"; });
            attempt(function () { location.href = "$base/href"; });
            setTimeout(function () {
              document.title = "probe-done:" + self.origin + "|main=" + realms.main + "|blank=" + realms.blank + "|srcdoc=" + realms.srcdoc + "|renav=" + realms.renav;
            }, 4000);
            </script></body></html>
        """.trimIndent()
    }

    /** The probe's title, split into origin and the realm answers. */
    private class Report(title: String) {
        private val parts = title.removePrefix("probe-done:").split("|")
        val origin = parts.first()
        val realms: Map<String, String> = parts.drop(1).associate { it.substringBefore("=") to it.substringAfter("=") }
    }

    /** Shows [page] in the sandbox and waits for the probe's report. */
    private fun runProbe(page: ArtifactPage, webRtcBlock: ((WebView) -> Boolean)? = null): Pair<WebView, Report> {
        val done = CountDownLatch(1)
        var reported = ""
        val view = show(page, ArtifactCallbacks(onTitle = { if (it.startsWith("probe-done:")) { reported = it; done.countDown() } }), webRtcBlock)
        assertTrue(
            "The intercepted document did not run; blockNetworkLoads may block it, switch to loadDataWithBaseURL",
            done.await(30, TimeUnit.SECONDS),
        )
        return view to Report(reported)
    }

    @Test fun aHostilePageLoadsButReachesNoSocketFindsNoWebRtcAndStaysWhereItIs() {
        assertTrue("This WebView has no PROXY_OVERRIDE; the sandbox would load nothing", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
        assertTrue(
            "This WebView has no DOCUMENT_START_SCRIPT; artifacts would run without JavaScript",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        val probe = listen()
        val page = ArtifactPage(ArtifactKind.Html, probePage(probe))
        val (view, report) = runProbe(page)
        // This only shows the lock completed, not which proxy is in force; the routing test proves routing.
        assertTrue("The page loaded before the dead proxy was in force", ArtifactNetworkLock.process.ready)
        // The CSP sandbox header applied: the page has an opaque origin.
        assertEquals("null", report.origin)
        // Each frame is named, so a failure says which realm the WebRTC block did not reach.
        assertEquals("WebRTC is still there in the main frame", "undefined", report.realms["main"])
        // "unreachable-…" means the page could not touch the frame's window at all (the sandbox origin
        // isolates it), so the frame offers no WebRTC either. Only "function" would be a hole.
        val blank = report.realms["blank"].orEmpty()
        assertTrue(
            "WebRTC is reachable in a fresh about:blank frame (the document-start script did not reach it): $blank",
            blank == "undefined" || blank.startsWith("unreachable-"),
        )
        assertEquals(
            "WebRTC in a srcdoc frame: 'noreport' means the frame never answered (blocked or broken), anything else that the block missed it",
            "undefined",
            report.realms["srcdoc"],
        )
        val renav = report.realms["renav"].orEmpty()
        assertTrue(
            "WebRTC is reachable in a frame navigated to about:blank after its first load: $renav",
            renav == "undefined" || renav.startsWith("unreachable-"),
        )
        // Give late connections (ICE, retries) time to show up.
        Thread.sleep(3_000)
        val leaked = probe.counts().filterValues { it > 0 }
        assertTrue("The sandboxed page reached the network: $leaked (all: ${probe.counts()})", leaked.isEmpty())
        assertEquals(page.url, currentUrl(view))
    }

    /**
     * Shows that the sandbox's zero counts come from routing: the same probe in the sandbox, but with
     * the WebRTC block left out and a counting proxy in place of the dead one. WebRTC is then present,
     * and its TURN-over-TCP connection must arrive at the proxy as a CONNECT, not at the TURN listener.
     * UDP is not proxied; its counts are printed, not asserted.
     */
    @Test fun withoutTheWebRtcBlockTurnOverTcpStillGoesThroughTheProxy() {
        val probe = listen()
        lockNetwork()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val proxyHits = AtomicInteger()
        val proxyPort = tcpListener(InetAddress.getByName("127.0.0.1"), proxyHits) { lines += it }
        proxyChanged = true
        setProxyOverride(artifactProxyConfig("http://127.0.0.1:$proxyPort"))
        val page = ArtifactPage(ArtifactKind.Html, probePage(probe))
        val (view, report) = runProbe(page, webRtcBlock = { true })
        assertEquals("null", report.origin)
        assertEquals("Without the block the probe needs WebRTC to prove anything", "function", report.realms["main"])
        Thread.sleep(3_000)
        val counts = probe.counts()
        val seen = synchronized(lines) { lines.toList() }
        println("ArtifactSandboxEmulatorTest routing: proxy connections=${proxyHits.get()} lines=$seen probe=$counts")
        assertTrue(
            "TURN over TCP did not reach the proxy as CONNECT ${probe.ip}:${probe.turn}; the dead proxy may not cover it. Proxy saw: $seen",
            seen.any { it.startsWith("CONNECT ${probe.ip}:${probe.turn} ") },
        )
        assertTrue(
            "TURN over TCP to loopback did not reach the proxy as CONNECT 127.0.0.1:${probe.turnLoopback}; the loopback bypass may still apply. Proxy saw: $seen",
            seen.any { it.startsWith("CONNECT 127.0.0.1:${probe.turnLoopback} ") },
        )
        val tcp = setOf("http", "httpLoopback", "preconnect", "preconnectLoopback", "turn", "turnLoopback")
        val direct = counts.filterKeys { it in tcp }.filterValues { it > 0 }
        assertTrue("TCP went around the proxy: $direct (all: $counts)", direct.isEmpty())
        assertEquals(page.url, currentUrl(view))
    }

    /**
     * Positive control: the same page in a plain WebView (JavaScript on, no interception, no
     * blockNetworkLoads, no WebRTC block) with the proxy override cleared, served from the probe's
     * origin. If the listeners saw nothing here, the zero counts in the sandboxed run would prove
     * nothing; if the realms did not say "function" here, the "undefined" in the sandbox would not
     * either. It checks HTTP, and WebRTC on TCP (TURN) or UDP (STUN) on either address; every count is printed, and a
     * channel that is zero even here is visible in the CI log.
     */
    @Test fun aPlainWebViewReachesTheListenersSoTheProbeCanSeeALeak() {
        val probe = listen()
        proxyChanged = true
        setProxyOverride(null)
        val done = CountDownLatch(1)
        var reported = ""
        compose.setContent {
            AndroidView(
                factory = { context ->
                    WebView(context).also { view ->
                        @Suppress("SetJavaScriptEnabled")
                        view.settings.javaScriptEnabled = true
                        view.webChromeClient =
                            object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (title?.startsWith("probe-done:") == true) { reported = title; done.countDown() }
                                }
                            }
                        view.loadDataWithBaseURL("http://${probe.ip}:${probe.http}/", probePage(probe), "text/html", "utf-8", null)
                    }
                },
                onRelease = ::disposeArtifactWebView,
                modifier = Modifier.size(300.dp, 190.dp),
            )
        }
        assertTrue("The control page did not run", done.await(30, TimeUnit.SECONDS))
        val report = Report(reported)
        assertEquals("http://${probe.ip}:${probe.http}", report.origin)
        assertEquals(mapOf("main" to "function", "blank" to "function", "srcdoc" to "function", "renav" to "function"), report.realms)
        Thread.sleep(3_000)
        val counts = probe.counts()
        // Printed so a CI log shows which channels the control proves the listeners can see.
        println("ArtifactSandboxEmulatorTest control counts: $counts")
        // Plain HTTP to the non-loopback address is refused by the debug cleartext policy, which only
        // allows 127.0.0.1, so the loopback listener is the one that proves URL loads are visible.
        assertTrue("The control made no HTTP connection, so the probe cannot see one: $counts", counts.getValue("httpLoopback") > 0)
        val webRtc = listOf("turn", "turnLoopback", "stun", "stunLoopback").sumOf { counts.getValue(it) }
        // A previous CI control run showed preconnect=2 and preconnectLoopback=2.
        assertTrue("The control made no preconnect, so the probe cannot see one: $counts", counts.getValue("preconnect") > 0)
        assertTrue("The control made no loopback preconnect, so the probe cannot see one: $counts", counts.getValue("preconnectLoopback") > 0)
        assertTrue("The control reached no WebRTC listener, so the probe cannot see one: $counts", webRtc > 0)
    }

    /**
     * The CDN path end to end without internet: an allowlisted script is answered by an injected
     * fetcher, runs in the page, and sets the title. The probe listeners must still see nothing, so the
     * WebView itself reached no socket, and a non-allowlisted host never reaches the fetcher.
     */
    @Test fun anAllowlistedScriptIsServedByTheFetcherAndNothingElseGetsOut() {
        val probe = listen()
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val fetcher =
            ArtifactCdnFetcher { url, _, _ ->
                requested += url
                ArtifactCdnReply.Ok("text/javascript", "utf-8", "window.cdnMarker = 'cdn-ok';".toByteArray())
            }
        val html =
            """
            <!doctype html><html><head><title>start</title>
            <script src="https://cdn.jsdelivr.net/npm/marker@1/marker.js"></script>
            <script src="https://evil.example/x.js"></script>
            <script src="https://cdn.jsdelivr.net.evil.example/x.js"></script>
            <script src="http://${probe.ip}:${probe.http}/script.js"></script>
            </head><body><script>
            var viaFetch = "none";
            fetch("https://unpkg.com/lib/data.json").then(function (r) { return r.text(); }).then(function () { viaFetch = "fetch-ok"; }).catch(function () { viaFetch = "fetch-blocked"; });
            fetch("https://evil.example/data.json", { mode: "no-cors" }).catch(function () {});
            setTimeout(function () { document.title = "cdn-done:" + window.cdnMarker + "|" + viaFetch; }, 3000);
            </script></body></html>
            """.trimIndent()
        val done = CountDownLatch(1)
        var reported = ""
        show(
            ArtifactPage(ArtifactKind.Html, html),
            ArtifactCallbacks(onTitle = { if (it.startsWith("cdn-done:")) { reported = it; done.countDown() } }),
            cdn = fetcher,
        )
        assertTrue("The CDN page did not run", done.await(30, TimeUnit.SECONDS))
        assertEquals("cdn-done:cdn-ok|fetch-ok", reported)
        assertTrue("The allowlisted script never reached the fetcher: $requested", "https://cdn.jsdelivr.net/npm/marker@1/marker.js" in requested)
        val offList = synchronized(requested) { requested.filter { !artifactCdnAllowed(it) } }
        assertTrue("A non-allowlisted URL reached the fetcher: $offList", offList.isEmpty())
        assertFalse("evil.example" in requested.joinToString())
        Thread.sleep(1_000)
        val leaked = probe.counts().filterValues { it > 0 }
        assertTrue("The WebView reached a socket while the CDN path was in use: $leaked", leaked.isEmpty())
    }

    @Test fun mermaidDrawsAValidDiagram() {
        val outcome = CountDownLatch(1)
        var title = ""
        show(
            ArtifactPage(ArtifactKind.Mermaid, "flowchart TD\n  A[Start] --> B{Ok?}\n  B -->|yes| C[Done]"),
            ArtifactCallbacks(onTitle = { if (it == "ok" || it == "error") { title = it; outcome.countDown() } }),
        )
        assertTrue("Mermaid did not answer", outcome.await(60, TimeUnit.SECONDS))
        assertEquals("ok", title)
    }

    @Test fun mermaidReportsAnInvalidDiagram() {
        val outcome = CountDownLatch(1)
        var title = ""
        show(
            ArtifactPage(ArtifactKind.Mermaid, "flowchart TD\n  A --> --> ((("),
            ArtifactCallbacks(onTitle = { if (it == "ok" || it == "error") { title = it; outcome.countDown() } }),
        )
        assertTrue("Mermaid did not answer", outcome.await(60, TimeUnit.SECONDS))
        assertEquals("error", title)
    }

    private fun capture(kind: ArtifactKind, text: String): android.graphics.Bitmap {
        val capture = ThumbnailCapture(ArtifactPage(kind, text))
        var view: WebView? = null
        compose.setContent {
            AndroidView(
                factory = { context ->
                    createArtifactWebView(context, capture.page, capture.callbacks, Color.WHITE).also {
                        view = it
                        capture.view = it
                    }
                },
                onRelease = ::disposeArtifactWebView,
                modifier = Modifier.size(390.dp, 244.dp),
            )
        }
        compose.waitUntil(10_000) { view?.let { it.width > 0 && it.height > 0 } == true }
        val target = checkNotNull(view)
        val bitmap =
            runBlocking {
                withContext(Dispatchers.Main) {
                    capture.capture(target.width, target.height, target.width / 2, target.height / 2, Color.WHITE)
                }
            }
        return checkNotNull(bitmap) { "No thumbnail was captured" }
    }

    @Test fun anHtmlThumbnailIsNotBlank() {
        val bitmap = capture(ArtifactKind.Html, "<body style=\"margin:0;background:#cc0000\"><h1 style=\"color:white\">Report</h1></body>")
        val corner = bitmap.getPixel(bitmap.width - 2, bitmap.height - 2)
        assertTrue("expected the red page, got #${Integer.toHexString(corner)}", Color.red(corner) > 150 && Color.green(corner) < 80)
    }

    @Test fun aMermaidThumbnailShowsTheDiagram() {
        val bitmap = capture(ArtifactKind.Mermaid, "flowchart LR\n  A[Start] --> B[Finish]")
        val colours = HashSet<Int>()
        for (x in 0 until bitmap.width step 3) for (y in 0 until bitmap.height step 3) colours += bitmap.getPixel(x, y)
        assertNotNull(bitmap)
        assertTrue("the diagram thumbnail is blank (${colours.size} colours)", colours.size > 8)
    }
}
