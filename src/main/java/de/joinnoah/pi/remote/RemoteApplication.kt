package de.joinnoah.pi.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RemoteApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var validatedNetwork: String? = null
    private lateinit var pushRegistration: PushRegistration
    lateinit var repository: RemoteRepository
        private set

    lateinit var settings: SettingsRepository
        private set

    internal lateinit var home: HomeSurfaces
        private set

    internal lateinit var predictions: PromptPredictions
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
        settings = DefaultSettingsRepository(this)
        val attachments = AttachmentStore(this)
        val importer = AttachmentImporter(this, attachments)
        repository =
            DefaultRemoteRepository(
                PairingStore(this),
                DraftStore(this),
                SecureRemoteTransport(scope),
                scope,
                navigationStorage = NavigationSnapshotStore(this),
                attachmentStorage = attachments,
                attachmentImporter = { uri, photo ->
                    importer.import(android.net.Uri.parse(uri), photo)
                },
                onRecoverySignal = { onPushRecoverySignal() },
                onUnpaired = ::onUnpaired,
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
                )
            if (settings.state.value.pushEnabled) {
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
                pushRegistration.onStartup()
            }
        }
    }

    fun enablePush() {
        if (!pushConfigured) return
        settings.setPushEnabled(true)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        pushRegistration.onEnabled()
    }

    /** Forgets everything the home surfaces, suggestions and notifications knew about a host. */
    private fun onUnpaired(routeId: String) {
        home.forgetRoute(routeId)
        predictions.forgetRoute(routeId)
        QuestionUpgrades.forgetRoute(this, routeId)
        RemoteNotifications.cancelRoute(this, routeId)
    }

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
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch {
                        val identity = network.toString()
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            validatedNetwork = identity
                            repository.setValidatedNetwork(identity)
                        } else if (validatedNetwork == identity) {
                            validatedNetwork = null
                            repository.setValidatedNetwork(null)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch {
                        if (validatedNetwork == network.toString()) {
                            validatedNetwork = null
                            repository.setValidatedNetwork(null)
                        }
                    }
                }
            }
        )
    }
}
