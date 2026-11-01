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
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.serialization.json.JsonObject
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
    data class ActivityEvent(val kind: String, val activity: MainActivity, val elapsed: Long, val hadSavedState: Boolean? = null)
    val activityEvents = mutableListOf<ActivityEvent>() // main thread only
    private var application: Application? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        private fun record(kind: String, activity: Activity) { if (activity is MainActivity) activityEvents.add(ActivityEvent(kind, activity, SystemClock.elapsedRealtime())) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) activityEvents.add(ActivityEvent("created", activity, SystemClock.elapsedRealtime(), state != null))
        }
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

    private const val OBSERVER_LIMIT = 1024
    private val inbound = mutableListOf<JsonObject>()
    private val writes = mutableListOf<JsonObject>()
    @Volatile var observerOverflow = false
        private set
    fun inboundMessages(): List<JsonObject> = synchronized(inbound) { inbound.toList() }
    fun writeAttempts(): List<JsonObject> = synchronized(writes) { writes.toList() }
    private fun record(target: MutableList<JsonObject>, payload: JsonObject) = synchronized(target) {
        if (target.size < OBSERVER_LIMIT) target.add(payload) else observerOverflow = true
    }
    private fun observe(transport: RemoteTransport): RemoteTransport = object : RemoteTransport by transport {
        override fun send(payload: JsonObject) { record(writes, payload); transport.send(payload) }
        override suspend fun sendGuarded(payload: JsonObject, beforePossibleWrite: () -> Unit) {
            transport.sendGuarded(payload) { record(writes, payload); beforePossibleWrite() }
        }
        override var listener: RemoteTransport.Listener
            get() = transport.listener
            set(value) { transport.listener = object : RemoteTransport.Listener by value {
                override fun message(payload: JsonObject) { record(inbound, payload); value.message(payload) }
            } }
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun fixtureIdentity(args: Bundle): String {
        val fields = listOf("productionEntryRoute", "productionEntryRelay", "productionEntryDeviceId", "productionEntryDeviceSecret",
            "productionEntrySessionId", "productionEntryProjectId", "productionEntryCaBase64")
            .map { requireNotNull(args.getString(it)) }
        require(fields.all { it.isNotEmpty() && !it.contains('\n') && !it.contains('\r') })
        return sha256(fields.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

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
        val mode = args.getString("productionEntryMode") ?: "seed"
        require(mode in setOf("seed", "restore", "unpaired"))
        val fileRecovery = args.getString("productionEntryFileDraftRecovery") == "true"
        val recovery = args.getString("productionEntryProcessRecovery") == "true" || fileRecovery
        if (recovery) check(requireNotNull(args.getString("productionEntryIdentity")) == fixtureIdentity(args)) {
            "Fixture identity does not match bootstrap arguments"
        }
        check(mode != "restore" || recovery) { "Restore requires the process recovery fixture" }
        val pairing = File(application.noBackupFilesDir, "paired-hosts.enc")
        if (mode == "restore") {
            val identity = requireNotNull(args.getString("productionEntryIdentity"))
            val pairingHash = requireNotNull(args.getString("productionEntryPairingSha256"))
            require(identity.matches(Regex("[0-9a-f]{64}")) && pairingHash.matches(Regex("[0-9a-f]{64}")))
            val preflight = File(application.noBackupFilesDir, "production-entry-restore-preflight-$label.marker")
            val consumed = File(application.noBackupFilesDir, "production-entry-restore-$label.marker")
            val proof = File(application.filesDir, if (fileRecovery) "production-entry-file-recovery-$label-a.json" else "production-entry-recovery-$label-a.json")
            check(preflight.isFile && !consumed.exists() && proof.isFile && pairing.isFile)
            check(listOf("paired-hosts.enc.bak", "paired-hosts.enc.new").none { File(application.noBackupFilesDir, it).exists() }) {
                "Pairing atomic-file siblings must be absent"
            }
            check(preflight.readText() == "$identity\n$pairingHash\n") { "Restore preflight identity mismatch" }
            val saved = JSONObject(proof.readText())
            check(saved.getString("identity") == identity && saved.getString("pairingSha256") == pairingHash)
            check(sha256(pairing.readBytes()) == pairingHash) { "Pairing file changed since phase A" }
            check(preflight.renameTo(consumed)) { "Unable to consume restore preflight" }
        } else {
            val preflight = File(application.noBackupFilesDir, "production-entry-preflight-$label.marker")
            val marker = File(application.noBackupFilesDir, "production-entry-$label.marker")
            check(preflight.isFile && !marker.exists()) { "Fresh owned emulator preflight marker required" }
            // A load can quarantine corrupt data; inspect presence without loading the store.
            listOf("paired-hosts.enc", "paired-hosts.enc.bak", "paired-hosts.enc.new").forEach {
                check(!File(application.noBackupFilesDir, it).exists()) { "Pairing storage already exists" }
            }
            check(preflight.renameTo(marker)) { "Unable to consume preflight marker" }
            if (mode == "seed") application.stores.pairings.save(listOf(PairedHost(route, url.toString().removeSuffix("/"),
                requireNotNull(args.getString("productionEntryDeviceId")),
                requireNotNull(args.getString("productionEntryDeviceSecret")), "Isolated fixture")))
        }
        this.application = application
        application.registerActivityLifecycleCallbacks(callbacks)
        DebugTransportFixture.install(factory, if (recovery || args.getString("productionEntryObserve") == "true") ::observe else null)
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
