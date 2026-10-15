package de.joinnoah.pi.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/** Watch-local client. No phone widgets, camera setup or credential copying; push is opt-in. */
class WearApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: RemoteRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    private var validated: String? = null
    @Volatile private var foreground = false
    private var pushRegistration: PushRegistration? = null
    private val forgottenRoutes = mutableSetOf<String>()
    internal fun deliverPush(payload: PushPayload) {
        scope.launch {
            if (payload.routeId in forgottenRoutes && repository.state.value.hosts.none { it.routeId == payload.routeId }) return@launch
            if (!pushConfigured || !settings.state.value.pushEnabled || isShowing(payload.routeId, payload.sessionId)) return@launch
            WearNotifications.post(this@WearApplication, payload)
        }
    }
    val pushConfigured: Boolean get() = listOf(
        BuildConfig.PI_REMOTE_FIREBASE_API_KEY, BuildConfig.PI_REMOTE_FIREBASE_APP_ID,
        BuildConfig.PI_REMOTE_FIREBASE_PROJECT_ID, BuildConfig.PI_REMOTE_FIREBASE_GCM_SENDER_ID,
    ).all(String::isNotBlank)
    internal fun isShowing(route: String, session: String): Boolean = foreground && repository.state.value.let {
        it.selection.routeId == route && it.selection.sessionId == session && it.session != null
    }
    internal fun onPushToken(token: String) { scope.launch { pushRegistration?.onTokenRotated(token) } }
    internal fun setPushEnabled(enabled: Boolean) {
        if (!pushConfigured) return
        settings.setPushEnabled(enabled)
        FirebaseMessaging.getInstance().isAutoInitEnabled = enabled
        if (enabled) pushRegistration?.onEnabled() else {
            pushRegistration?.onDisabled(); repository.setPushToken(null)
        }
    }
    private val pairMailbox = WatchPairMailbox()
    private val acceptedCode = MutableStateFlow<String?>(null)
    internal val pairCode = acceptedCode.asStateFlow()
    private val waitingPair = MutableStateFlow(false)
    internal val pairWaiting = waitingPair.asStateFlow()
    private var pairNonce: String? = null
    private var pairExpiry: Job? = null
    @Synchronized internal fun reservePairRequest(): String? {
        if (pairNonce != null) return null
        return Wire.random().also { nonce ->
            pairNonce = nonce; acceptedCode.value = null; waitingPair.value = true
            pairExpiry?.cancel()
            pairExpiry = scope.launch {
                delay(WATCH_PAIR_WAIT_MILLIS)
                if (cancelPairRequest(nonce)) repository.reportError(R.string.wear_pair_expired)
            }
        }
    }
    @Synchronized internal fun startPairRequest(nodeId: String, nonce: String): Boolean {
        if (pairNonce != nonce) return false
        pairMailbox.begin(nodeId, nonce)
        return true
    }
    @Synchronized internal fun cancelPairRequest(nonce: String? = null): Boolean {
        if (nonce != null && nonce != pairNonce) return false
        pairMailbox.cancel(); acceptedCode.value = null; pairNonce = null; waitingPair.value = false
        pairExpiry?.cancel(); pairExpiry = null
        return true
    }
    @Synchronized internal fun receivePairCode(nodeId: String, path: String, bytes: ByteArray) {
        pairMailbox.receive(nodeId, path, bytes)?.let { code ->
            acceptedCode.value = code; pairNonce = null; waitingPair.value = false
            pairExpiry?.cancel(); pairExpiry = null
        }
    }
    @Synchronized internal fun consumePairCode() { acceptedCode.value = null }

    override fun onCreate() {
        super.onCreate()
        // The watch has no theme picker and follows the watch face, so it never sets a per-app night mode.
        settings = DefaultSettingsRepository(this) {}
        repository = DefaultRemoteRepository(
            PairingStore(this), DraftStore(this), SecureRemoteTransport(scope), scope,
            navigationStorage = NavigationSnapshotStore(this),
            onRecoverySignal = { pushRegistration?.onRecovery() },
            onUnpaired = { forgottenRoutes += it; WearNotifications.forget(this, it) },
        )
        // Persisted opt-out must re-establish the repository's null-token intent on every start,
        // so an offline unregister is still sent when this process next connects to a host.
        if (!pushConfigured || !settings.state.value.pushEnabled) repository.setPushToken(null)
        WearNotifications.createChannel(this)
        if (pushConfigured) {
            FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
                .setApiKey(BuildConfig.PI_REMOTE_FIREBASE_API_KEY).setApplicationId(BuildConfig.PI_REMOTE_FIREBASE_APP_ID)
                .setProjectId(BuildConfig.PI_REMOTE_FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.PI_REMOTE_FIREBASE_GCM_SENDER_ID).build())
            pushRegistration = PushRegistration(scope, { pushConfigured }, { settings.state.value.pushEnabled },
                PushTokenRequester { success, failure -> FirebaseMessaging.getInstance().token.addOnSuccessListener(success).addOnFailureListener { failure() } },
                repository::setPushToken)
            if (settings.state.value.pushEnabled) {
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
                pushRegistration?.onStartup()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true; repository.setForeground(true)
                repository.state.value.selection.let { selection ->
                    if (selection.routeId != null && selection.sessionId != null) WearNotifications.cancel(this@WearApplication, selection.routeId, selection.sessionId)
                }
            }
            override fun onStop(owner: LifecycleOwner) { foreground = false; repository.setForeground(false) }
        })
        scope.launch {
            repository.state.map { it.selection.takeIf { _ -> it.session != null } }.distinctUntilChanged().collect { selection ->
                if (foreground && selection?.routeId != null && selection.sessionId != null)
                    WearNotifications.cancel(this@WearApplication, selection.routeId, selection.sessionId)
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                scope.launch {
                    val identity = network.toString()
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        validated = identity; repository.setValidatedNetwork(identity)
                    } else if (validated == identity) {
                        validated = null; repository.setValidatedNetwork(null)
                    }
                }
            }
            override fun onLost(network: Network) {
                scope.launch {
                    if (validated == network.toString()) { validated = null; repository.setValidatedNetwork(null) }
                }
            }
        })
    }
}
