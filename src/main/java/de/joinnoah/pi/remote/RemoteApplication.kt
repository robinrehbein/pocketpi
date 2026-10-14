package de.joinnoah.pi.remote

import android.app.ActivityManager
import android.app.Application
import android.content.Context
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

    /** The process's stores; services, receivers and workers reach them through [Context.stores]. */
    internal val stores by lazy { AppStores(this) }

    private lateinit var pushRegistration: PushRegistration
    lateinit var repository: RemoteRepository
        private set

    lateinit var settings: SettingsRepository
        private set

    internal lateinit var home: HomeSurfaces
        private set

    /** The phone's pins and read marks of sessions; see [SessionMarksStore]. */
    internal lateinit var sessionMarks: SessionMarksStore
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
        scope.launch(Dispatchers.IO) {
            // Exports hold chat content; none outlives the process that shared it.
            runCatching { ExportStorage.clear(cacheDir) }
            // Shared agent images are copies of project files; none outlives the process either.
            runCatching { ImageStorage.clear(cacheDir) }
            // Opened or shared artifacts are copies of project files too.
            runCatching { ArtifactStorage.clear(cacheDir) }
            // So are files dragged into other apps.
            runCatching { DragStorage.clear(cacheDir) }
            // Camera captures the import is done with are deleted then; this catches the rest.
            runCatching { PhotoCapture.sweep(cacheDir) }
        }
        BitmapCaches.configure(getSystemService(ActivityManager::class.java).memoryClass)
        settings = DefaultSettingsRepository(this)
        LiveRunSettings.preload(this)
        appLock = AppLock(AppLockStore(this), deviceSecure = { deviceSecure(this) })
        val attachments = stores.attachments
        val importer = AttachmentImporter(this, attachments)
        repository =
            DefaultRemoteRepository(
                stores.pairings,
                stores.drafts,
                remoteTransport(this, scope),
                scope,
                navigationStorage = stores.navigation,
                attachmentStorage = attachments,
                attachmentImporter = { uri, photo ->
                    importer.import(android.net.Uri.parse(uri), photo)
                },
                onRecoverySignal = { onPushRecoverySignal() },
                onUnpaired = ::onUnpaired,
                projectHint = ::sessionProjectHint,
                media = stores.media,
            )
        observeRecovery()
        predictions = PromptPredictions(this, scope, stores.pairings)
        home = HomeSurfaces(this, scope, stores.pairings)
        home.observe(repository.state)
        sessionMarks = SessionMarksStore(this)
        sessionMarks.observe(repository.state, scope)
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BitmapCaches.trim(level)
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

    /** A notification reply started the session's next run; called off the main thread. */
    internal fun onRepliedInBackground(routeId: String, sessionId: String) {
        home.onAnswered(routeId, sessionId)
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
 * The process's one instance of each encrypted store, so that each file has one store, one lock
 * and one cached key no matter how many parts of the app read it. Each store is made on first use.
 */
internal class AppStores(
    /** The application; tests pass a context with its own files directory. */
    private val app: Context,
) {
    /** The paired route IDs a push checks; [pairings] keeps them in step. */
    val routes by lazy { PairedRouteCache(app) }

    /** The pairing file itself; only a push's route check, which fills [routes], reads it directly. */
    val pairingFile by lazy {
        PairingStore(
            app,
            onReset = {
                routes.onPairingsReset()
                // Without the pairings no route is known, so no kept image is ever asked for again.
                media.clear()
            },
        )
    }

    /** The pairings, with the route cache and the notifications' host names kept in step. */
    val pairings: PairingStorage by lazy {
        RouteCachingPairings(pairingFile, routes) { hosts -> hosts.forEach(RemoteNotifications::rememberHost) }
    }

    val drafts by lazy { DraftStore(app) }

    val navigation by lazy { NavigationSnapshotStore(app) }

    val attachments by lazy { AttachmentStore(app) }

    /** Sent and project images kept on disk ([MediaDiskCache]); cleared with the pairings. */
    val media by lazy { MediaDiskCache(java.io.File(app.noBackupFilesDir, "media-cache")) }

    /** The widget's and shortcuts' snapshot ([HomeSurfaces]). */
    val home by lazy { EncryptedFileStore(app, "home-sessions.enc", "pi-remote-home-v1") }

    /** The prompt history ([PromptPredictions]). */
    val prompts by lazy { EncryptedFileStore(app, "prompt-history.enc", "pi-remote-prompts-v1") }
}

/**
 * The stores of this process: the phone app's own, or, in a process whose application is not
 * [RemoteApplication] (the watch app, some tests), a new set that still shares each file's lock and
 * key with any other.
 */
internal val Context.stores: AppStores
    get() = (applicationContext as? RemoteApplication)?.stores ?: AppStores(applicationContext)

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
