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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the real WebView sandbox on a device. These are the checks Robolectric cannot make: that the
 * intercepted document loads with `blockNetworkLoads` on, that a hostile page reaches no socket, and
 * that Mermaid and the thumbnail capture really draw.
 */
class ArtifactSandboxEmulatorTest {
    @get:Rule val compose = createComposeRule()

    private val servers = mutableListOf<AutoCloseable>()

    @After fun closeServers() = servers.forEach { runCatching { it.close() } }

    /**
     * One loopback listener per way out, so a failure names the channel that leaked: `http` for every
     * URL load, `preconnect` for network hints, `turn` for the TURN-over-TCP ICE server and `stun` for
     * the UDP one. Any connection or packet means the page got out.
     */
    private class Probe(val http: Int, val preconnect: Int, val turn: Int, val stun: Int, val hits: Map<String, AtomicInteger>) {
        fun counts(): Map<String, Int> = hits.mapValues { it.value.get() }
    }

    private fun tcpListener(counter: AtomicInteger): Int {
        val tcp = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { servers += it }
        Thread {
            try {
                while (true) tcp.accept().also { counter.incrementAndGet(); it.close() }
            } catch (_: SocketException) {}
        }.apply { isDaemon = true }.start()
        return tcp.localPort
    }

    private fun udpListener(counter: AtomicInteger): Int {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { servers += it }
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
        val hits = linkedMapOf("http" to AtomicInteger(), "preconnect" to AtomicInteger(), "turn" to AtomicInteger(), "stun" to AtomicInteger())
        return Probe(
            http = tcpListener(hits.getValue("http")),
            preconnect = tcpListener(hits.getValue("preconnect")),
            turn = tcpListener(hits.getValue("turn")),
            stun = udpListener(hits.getValue("stun")),
            hits = hits,
        )
    }

    private fun show(page: ArtifactPage, callbacks: ArtifactCallbacks): WebView {
        var view: WebView? = null
        compose.setContent {
            AndroidView(
                factory = { context -> createArtifactWebView(context, page, callbacks).also { view = it } },
                onRelease = ::disposeArtifactWebView,
                modifier = Modifier.size(300.dp, 190.dp),
            )
        }
        compose.waitUntil(10_000) { view != null }
        return checkNotNull(view)
    }

    private fun currentUrl(view: WebView): String? {
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { url = view.url }
        return url
    }

    private fun probePage(probe: Probe): String {
        val base = "http://127.0.0.1:${probe.http}"
        val hint = "http://127.0.0.1:${probe.preconnect}"
        return """
            <!doctype html><html><head><title>probe</title>
            <meta http-equiv="refresh" content="0;url=$base/refresh">
            <link rel="prefetch" href="$base/prefetch"><link rel="preconnect" href="$hint"><link rel="dns-prefetch" href="$hint">
            <link rel="stylesheet" href="$base/style.css">
            </head><body>
            <img src="$base/img.png"><iframe src="$base/frame"></iframe><script src="$base/script.js"></script>
            <form id="f" action="$base/form" method="post"><input name="a" value="b"></form>
            <script>
            function attempt(f) { try { f(); } catch (e) {} }
            attempt(function () { fetch("$base/fetch", { mode: "no-cors" }).catch(function () {}); });
            attempt(function () { var x = new XMLHttpRequest(); x.open("GET", "$base/xhr"); x.send(); });
            attempt(function () { navigator.sendBeacon("$base/beacon", "x"); });
            attempt(function () { new WebSocket("ws://127.0.0.1:${probe.http}/ws"); });
            attempt(function () {
              var pc = new RTCPeerConnection({ iceServers: [
                { urls: "stun:127.0.0.1:${probe.stun}" },
                { urls: "turn:127.0.0.1:${probe.turn}?transport=tcp", username: "user", credential: "secret" },
              ] });
              pc.createDataChannel("x");
              pc.createOffer().then(function (o) { return pc.setLocalDescription(o); }).catch(function () {});
            });
            attempt(function () { window.open("$base/open"); });
            attempt(function () { document.getElementById("f").submit(); });
            attempt(function () { location = "$base/location"; });
            attempt(function () { location.href = "$base/href"; });
            setTimeout(function () { document.title = "probe-done:" + self.origin; }, 2500);
            </script></body></html>
        """.trimIndent()
    }

    @Test fun aHostilePageLoadsButReachesNoSocketAndStaysWhereItIs() {
        val probe = listen()
        val page = ArtifactPage(ArtifactKind.Html, probePage(probe))
        val done = CountDownLatch(1)
        var reported = ""
        val view = show(page, ArtifactCallbacks(onTitle = { if (it.startsWith("probe-done:")) { reported = it; done.countDown() } }))
        assertTrue(
            "The intercepted document did not run; blockNetworkLoads may block it, switch to loadDataWithBaseURL",
            done.await(30, TimeUnit.SECONDS),
        )
        // The CSP sandbox header applied: the page has an opaque origin.
        assertEquals("probe-done:null", reported)
        // Give late connections (ICE, retries) time to show up.
        Thread.sleep(2_000)
        val leaked = probe.counts().filterValues { it > 0 }
        assertTrue("The sandboxed page reached the network: $leaked (all: ${probe.counts()})", leaked.isEmpty())
        assertEquals(page.url, currentUrl(view))
    }

    /**
     * Positive control: the same page in a plain WebView (JavaScript on, no interception, no
     * blockNetworkLoads) served from the loopback origin. If the listeners saw nothing here, the zero
     * counts in the sandboxed run would prove nothing. It checks the TCP side (HTTP fetches and the
     * TURN-over-TCP ICE server) and the UDP side (the STUN ICE server).
     */
    @Test fun aPlainWebViewReachesTheListenersSoTheProbeCanSeeALeak() {
        val probe = listen()
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
                        view.loadDataWithBaseURL("http://127.0.0.1:${probe.http}/", probePage(probe), "text/html", "utf-8", null)
                    }
                },
                onRelease = ::disposeArtifactWebView,
                modifier = Modifier.size(300.dp, 190.dp),
            )
        }
        assertTrue("The control page did not run", done.await(30, TimeUnit.SECONDS))
        assertEquals("probe-done:http://127.0.0.1:${probe.http}", reported)
        Thread.sleep(2_000)
        val counts = probe.counts()
        // Printed so a CI log shows which channels the control proves the listeners can see.
        println("ArtifactSandboxEmulatorTest control counts: $counts")
        assertTrue("The control made no HTTP connection, so the probe cannot see one: $counts", counts.getValue("http") > 0)
        assertTrue("The control reached no WebRTC listener, so the probe cannot see one: $counts", counts.getValue("turn") + counts.getValue("stun") > 0)
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
