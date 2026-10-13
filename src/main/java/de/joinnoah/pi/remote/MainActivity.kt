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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
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
        // Extras count only on the app's own non-exported alias; other apps reach this exported
        // activity too (LaunchIntents).
        val target = LaunchIntents.target(intent) ?: return
        notification = RemoteNotification(target.routeId, target.sessionId, ++delivery, target.jobs, target.jobId)
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
    val dark = isSystemInDarkTheme()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val view = LocalView.current
    val colors = remoteColorScheme(dark, synthwave = theme == "synthwave")
    val windowInfo = LocalWindowInfo.current
    val uriHandler =
        remember(activity, context, windowInfo) {
            // Read at each tap, outside composition: the window can enter split screen or change its
            // size without a new activity, and RemoteApp need not recompose while a window is resized.
            AdjacentUriHandler(context) {
                val size = windowInfo.containerDpSize
                opensLinksAdjacent(
                    inMultiWindow = activity?.isInMultiWindowMode == true,
                    width = size.width,
                    // Outside split screen the window fills the screen, so its smaller side is the
                    // screen's smallest width.
                    smallestWidth = minOf(size.width, size.height),
                )
            }
        }
    val windowBackground = colors.background
    DisposableEffect(activity, windowBackground) {
        // A dark mode switch no longer recreates the activity, so the window behind the content
        // follows the theme here instead of keeping the one it started with.
        activity?.window?.setBackgroundDrawable(windowBackground.toArgb().toDrawable())
        onDispose {}
    }
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
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
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
}
