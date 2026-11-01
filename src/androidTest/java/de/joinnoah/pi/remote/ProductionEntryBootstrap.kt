package de.joinnoah.pi.remote

import android.app.Application
import android.app.Activity
import android.os.SystemClock
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Instrumentation-only; caller must prove this is a fresh, owned emulator app. */
internal object ProductionEntryBootstrap {
    @Volatile var installed = false
        private set
    private val started = CountDownLatch(1)
    @Volatile private var appCreated = false
    fun completed() { appCreated = true; started.countDown() }
    fun awaitStarted() {
        check(started.await(20, TimeUnit.SECONDS) && installed && appCreated) { "Fixture application bootstrap did not complete" }
    }
    data class ActivityEvent(val kind: String, val activity: MainActivity, val elapsed: Long)
    val activityEvents = mutableListOf<ActivityEvent>() // main thread only
    private var application: Application? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        private fun record(kind: String, activity: Activity) { if (activity is MainActivity) activityEvents.add(ActivityEvent(kind, activity, SystemClock.elapsedRealtime())) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = record("created", activity)
        override fun onActivityStarted(activity: Activity) = record("started", activity)
        override fun onActivityResumed(activity: Activity) = record("resumed", activity)
        override fun onActivityPaused(activity: Activity) = record("paused", activity)
        override fun onActivityStopped(activity: Activity) = record("stopped", activity)
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = record("destroyed", activity)
    }
    val factoryRequests = AtomicInteger()
    val socketOpens = AtomicInteger()
    val socketFailures = AtomicInteger()
    @Volatile var lastFailureType: String? = null
        private set
    @Volatile var lastEndpointAllowed: Boolean? = null
        private set
    private var client: OkHttpClient? = null

    fun install(application: Application, args: Bundle) {
        check(application is RemoteApplication)
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debuggable application required" }
        check(args.getString("productionEntryFreshOwned") == "true")
        val label = requireNotNull(args.getString("productionEntryLabel"))
        require(label.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val route = requireNotNull(args.getString("productionEntryRoute"))
        require(route.matches(Regex("[A-Za-z0-9_-]{1,256}")))
        val url = requireNotNull(args.getString("productionEntryRelay")).toHttpUrl()
        require(url.scheme == "https" && url.host == "10.0.2.2" && url.port in 1..65535)
        require(url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty())
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(requireNotNull(args.getString("productionEntryCaBase64")), Base64.DEFAULT).inputStream()
        )
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("fixture", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val http = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager).pingInterval(20, TimeUnit.SECONDS).build()
        client = http
        val factory = WebSocket.Factory { request: Request, listener: WebSocketListener ->
            factoryRequests.incrementAndGet()
            val target = request.url
            val allowed = target.scheme == "https" && target.host == url.host && target.port == url.port &&
                target.encodedPath == "/v1/peer/$route" && target.query == null && target.fragment == null
            lastEndpointAllowed = allowed
            try {
                require(allowed) { "Unexpected fixture relay endpoint" }
                http.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socketOpens.incrementAndGet(); listener.onOpen(webSocket, response) }
                    override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(webSocket, code, reason)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(webSocket, code, reason)
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        socketFailures.incrementAndGet()
                        lastFailureType = t.javaClass.simpleName.take(80)
                        listener.onFailure(webSocket, t, response)
                    }
                })
            } catch (failure: Exception) {
                lastFailureType = failure.javaClass.simpleName.take(80)
                throw failure
            }
        }
        val preflight = File(application.noBackupFilesDir, "production-entry-preflight-$label.marker")
        val marker = File(application.noBackupFilesDir, "production-entry-$label.marker")
        check(preflight.isFile && !marker.exists()) { "Fresh owned emulator preflight marker required" }
        // A load can quarantine corrupt data, so inspect file presence without reading the store.
        listOf("paired-hosts.enc", "paired-hosts.enc.bak", "paired-hosts.enc.new").forEach {
            check(!File(application.noBackupFilesDir, it).exists()) { "Pairing storage already exists" }
        }
        check(preflight.renameTo(marker)) { "Unable to consume preflight marker" }
        application.stores.pairings.save(listOf(PairedHost(route, url.toString().removeSuffix("/"),
            requireNotNull(args.getString("productionEntryDeviceId")),
            requireNotNull(args.getString("productionEntryDeviceSecret")), "Isolated fixture")))
        this.application = application
        application.registerActivityLifecycleCallbacks(callbacks)
        DebugTransportFixture.install(factory)
        installed = true
    }

    fun http(): OkHttpClient = checkNotNull(client) { "Bootstrap not installed" }

    fun close() {
        DebugTransportFixture.clear()
        application?.unregisterActivityLifecycleCallbacks(callbacks)
        application = null
        client?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        client = null
    }
}
