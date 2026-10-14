package de.joinnoah.pi.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.system.OsConstants
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

class RemoteApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pushRegistration: PushRegistration
    lateinit var repository: RemoteRepository
        private set

    lateinit var settings: SettingsRepository
        private set

    internal lateinit var home: HomeSurfaces
        private set

    internal lateinit var predictions: PromptPredictions
        private set

    internal lateinit var appLock: AppLock
        private set

    val pushConfigured: Boolean
        get() =
            listOf(
                    BuildConfig.PI_REMOTE_FIREBASE_API_KEY,
                    BuildConfig.PI_REMOTE_FIREBASE_APP_ID,
                    BuildConfig.PI_REMOTE_FIREBASE_PROJECT_ID,
                    BuildConfig.PI_REMOTE_FIREBASE_GCM_SENDER_ID,
                )
                .all { it.isNotBlank() }

    override fun onCreate() {
        super.onCreate()
        // Exports hold chat content; none outlives the process that shared it.
        Thread { runCatching { ExportStorage.clear(cacheDir) } }.start()
        // Shared agent images are copies of project files; none outlives the process either.
        Thread { runCatching { ImageStorage.clear(cacheDir) } }.start()
        // Opened or shared artifacts are copies of project files too.
        Thread { runCatching { ArtifactStorage.clear(cacheDir) } }.start()
        settings = DefaultSettingsRepository(this)
        appLock = AppLock(AppLockStore(this), deviceSecure = { deviceSecure(this) })
        val attachments = AttachmentStore(this)
        val importer = AttachmentImporter(this, attachments)
        repository =
            DefaultRemoteRepository(
                // Pushes check their route in the cache this keeps, without decrypting the store.
                RouteCachingPairings(PairingStore(this), PairedRouteCache(this)),
                DraftStore(this),
                remoteTransport(this, scope),
                scope,
                navigationStorage = NavigationSnapshotStore(this),
                attachmentStorage = attachments,
                attachmentImporter = { uri, photo ->
                    importer.import(android.net.Uri.parse(uri), photo)
                },
                onRecoverySignal = { onPushRecoverySignal() },
                onUnpaired = ::onUnpaired,
                projectHint = ::sessionProjectHint,
            )
        observeRecovery()
        predictions = PromptPredictions(this, scope, PairingStore(this))
        home = HomeSurfaces(this, scope, PairingStore(this))
        home.observe(repository.state)
        observeOpenedSession()
        RemoteNotifications.createChannel(this)
        RemoteNotifications.deleteSyncChannel(this)
        if (pushConfigured) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.PI_REMOTE_FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.PI_REMOTE_FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.PI_REMOTE_FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.PI_REMOTE_FIREBASE_GCM_SENDER_ID)
                    .build(),
            )
            pushRegistration =
                PushRegistration(
                    scope = scope,
                    configured = { pushConfigured },
                    enabled = { settings.state.value.pushEnabled },
                    requester = PushTokenRequester { success, failure ->
                        FirebaseMessaging.getInstance().token
                            .addOnSuccessListener(success)
                            .addOnFailureListener { failure() }
                    },
                    register = repository::setPushToken,
                    unregister = { repository.setPushToken(null) },
                    optedOut = { settings.state.value.pushOptedOut },
                )
            if (settings.state.value.pushEnabled) {
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
            }
            pushRegistration.onStartup()
        }
    }

    fun enablePush() {
        if (!pushConfigured) return
        settings.setPushEnabled(true)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        pushRegistration.onEnabled()
    }

    /**
     * Switches push off: no more pushes are posted, and the Mac is told to forget this phone's
     * token (now if connected, otherwise on the next connection, including after a restart).
     */
    fun disablePush() {
        if (!pushConfigured) return
        settings.setPushEnabled(false)
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        pushRegistration.onDisabled()
    }

    /** Forgets everything the home surfaces, suggestions and notifications knew about a host. */
    private fun onUnpaired(routeId: String) {
        home.forgetRoute(routeId)
        predictions.forgetRoute(routeId)
        QuestionUpgrades.forgetRoute(this, routeId)
        CompletionUpgrades.names.forgetRoute(routeId)
        RemoteNotifications.cancelRoute(this, routeId)
    }

    /**
     * Where the home snapshot or the label worker last saw a session, for a tapped notification
     * whose session the repository's caches do not hold.
     */
    private suspend fun sessionProjectHint(routeId: String, sessionId: String): String? =
        home.load().sessions.find { it.routeId == routeId && it.sessionId == sessionId }?.projectId
            ?: CompletionUpgrades.names.get(routeId, sessionId)?.projectId

    /** A push arrived for a paired host; called on the messaging worker thread. */
    internal fun onPushEvent(payload: PushPayload) {
        home.onPush(payload)
    }

    /** A notification action answered a question; called off the main thread. */
    internal fun onQuestionAnsweredInBackground(
        routeId: String,
        sessionId: String,
        answer: kotlinx.serialization.json.JsonObject,
    ) {
        home.onAnswered(routeId, sessionId)
        if (isPlanApproval(answer)) predictions.recordPlanApproval(routeId, sessionId)
    }

    /** True unless the host was unpaired in this process or is missing from loaded pairings. */
    internal fun isPaired(routeId: String): Boolean =
        !home.isForgotten(routeId) &&
            repository.state.value.hosts.let { hosts -> hosts.isEmpty() || hosts.any { it.routeId == routeId } }

    /** True while the app is in front and showing this very session. */
    internal fun isShowing(routeId: String, sessionId: String): Boolean {
        val current = repository.state.value
        return foreground &&
            current.selection.routeId == routeId &&
            current.selection.sessionId == sessionId &&
            current.session != null
    }

    @Volatile private var foreground = false

    // Opening a session in the app resolves its notification and ranks its shortcut.
    private fun observeOpenedSession() {
        scope.launch {
            repository.state
                .map { state ->
                    val route = state.selection.routeId
                    val session = state.selection.sessionId
                    if (route != null && session != null && state.session != null) route to session
                    else null
                }
                .distinctUntilChanged()
                .collect { opened ->
                    if (opened != null && foreground) {
                        RemoteNotifications.cancel(this@RemoteApplication, opened.first, opened.second)
                        home.reportOpened(opened.first, opened.second)
                    }
                }
        }
    }

    fun onPushToken(token: String) {
        if (::pushRegistration.isInitialized) pushRegistration.onTokenRotated(token)
    }

    private fun onPushRecoverySignal() {
        if (::pushRegistration.isInitialized) pushRegistration.onRecovery()
    }

    private fun observeRecovery() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    repository.state.value.let { state ->
                        val route = state.selection.routeId
                        val session = state.selection.sessionId
                        if (route != null && session != null && state.session != null)
                            RemoteNotifications.cancel(this@RemoteApplication, route, session)
                    }
                    repository.setForeground(true)
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    repository.setForeground(false)
                }
            }
        )
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                // Main thread only: each callback hops there before it touches the tracker.
                private val tracker = NetworkTracker()

                private fun report(change: () -> NetworkTracker.Report?) {
                    scope.launch {
                        val report = change() ?: return@launch
                        repository.setNetwork(report.identity, report.validated, report.path)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val running =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    val checked = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    // A VPN carries the transports of the networks underneath it (the list of
                    // those networks is no public API), so roaming between Wi-Fi and LTE keeps the
                    // VPN's network and changes these.
                    val under =
                        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) null
                        else VPN_UNDERLYING_TRANSPORTS.filter(capabilities::hasTransport).joinToString(",")
                    val identity = network.toString()
                    report { tracker.capabilities(identity, internet, running, checked, under) }
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    // Temporary IPv6 addresses rotate on their own; they do not move the route.
                    val addresses =
                        properties.linkAddresses
                            .filter { (it.flags and OsConstants.IFA_F_TEMPORARY) == 0 }
                            .mapNotNull { it.address.hostAddress }
                    val identity = network.toString()
                    val name = properties.interfaceName
                    report { tracker.links(identity, name, addresses) }
                }

                override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
                    // Blocked is as good as lost; unblocked reports the network again.
                    val identity = network.toString()
                    report { tracker.blocked(identity, isBlocked) }
                }

                override fun onLost(network: Network) {
                    val identity = network.toString()
                    report { tracker.lost(identity) }
                }
            }
        )
    }
}

/**
 * The process's one OkHttp client. The relay sockets ([SecureRemoteTransport]'s default and
 * [BackgroundRemoteClient]'s) and the artifact CDN derive their clients from it with
 * `newBuilder()`, so they share its dispatcher, connection pool and threads and set only their own
 * timeouts, ping interval and cache. Built on first use; the wear app uses it too.
 */
internal val baseHttpClient: OkHttpClient by lazy { OkHttpClient() }

/** Transports a VPN's network takes over from the networks it runs on. */
private val VPN_UNDERLYING_TRANSPORTS =
    listOf(
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
    )
