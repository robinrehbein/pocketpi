package de.joinnoah.pi.remote

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app
        get() = application as RemoteApplication

    private var notification by mutableStateOf<RemoteNotification?>(null)
    private var delivery = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        else {
            val route = savedInstanceState.getString("pendingRouteId")
            val session = savedInstanceState.getString("pendingSessionId")
            if (route != null && session != null)
                notification =
                    RemoteNotification(
                        route,
                        session,
                        ++delivery,
                        savedInstanceState.getBoolean("pendingJobs"),
                        savedInstanceState.getString("pendingJobId"),
                    )
        }
        setContent {
            CompositionLocalProvider(LocalPromptPredictions provides app.predictions) {
                RemoteApp(
                    app.repository,
                    app.settings,
                    app.pushConfigured,
                    app::enablePush,
                    notification,
                    { if (notification == it) notification = null },
                    app::disablePush,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        notification?.let {
            outState.putString("pendingRouteId", it.routeId)
            outState.putString("pendingSessionId", it.sessionId)
            outState.putBoolean("pendingJobs", it.jobs)
            it.jobId?.let { job -> outState.putString("pendingJobId", job) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        lifecycleScope.launch {
            try {
                app.repository.flush()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                app.repository.reportError(R.string.remote_storage_error)
            }
        }
        super.onStop()
    }

    private fun handleIntent(intent: Intent) {
        // Reopening from Recents replays the task's original intent; it must not reopen a session
        // from an old notification, widget row or shortcut.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val route = intent.getStringExtra("routeId") ?: return
        val session = intent.getStringExtra("target") ?: return
        notification =
            RemoteNotification(
                route,
                session,
                ++delivery,
                intent.getBooleanExtra(RemoteNotifications.EXTRA_JOBS, false),
                // Nullable on purpose: the strict push-id check, not the looser String one.
                intent.getStringExtra(RemoteNotifications.EXTRA_JOB).takeIf(::isOpaqueId),
            )
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun RemoteApp(
    repository: RemoteRepository,
    settings: SettingsRepository,
    pushConfigured: Boolean,
    enablePush: () -> Unit,
    notification: RemoteNotification? = null,
    consumeNotification: (RemoteNotification) -> Unit = {},
    disablePush: () -> Unit = {},
) {
    val preferences by settings.state.collectAsStateWithLifecycle()
    val theme = preferences.theme
    val dark =
        when (theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val activity = LocalActivity.current
    val view = LocalView.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                window.navigationBarColor =
                    if (dark) AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)
                    else AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
            }
        }
    }
    val colors = remoteColorScheme(dark)
    MaterialTheme(
        colorScheme = colors,
        typography = RemoteTypography,
        shapes =
            Shapes(
                extraLarge = RoundedCornerShape(28.dp),
                large = RoundedCornerShape(24.dp),
                medium = RoundedCornerShape(20.dp),
            ),
    ) {
        RemoteNavigation(
            repository,
            settings,
            pushConfigured,
            enablePush,
            notification,
            consumeNotification,
            disablePush,
        )
    }
}
