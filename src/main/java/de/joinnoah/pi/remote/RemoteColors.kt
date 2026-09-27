package de.joinnoah.pi.remote

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Status and state colors outside the Material color roles. Each picks its tone from the
// surface luminance so it stays legible in both the light and the dark scheme.

@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

internal val IdleLight = Color(0xFF2F6D46)
internal val IdleDark = Color(0xFF91D5AC)
internal val WaitingLight = Color(0xFFB45309)
internal val WaitingDark = Color(0xFFF5B971)

@Composable
private fun idleColor(): Color = if (isDarkSurface()) IdleDark else IdleLight

@Composable
private fun waitingColor(): Color = if (isDarkSurface()) WaitingDark else WaitingLight

@Composable
internal fun sessionStatusColor(availability: SessionAvailability): Color =
    when (availability) {
        SessionAvailability.IDLE -> idleColor()
        SessionAvailability.RUNNING -> MaterialTheme.colorScheme.primary
        SessionAvailability.WAITING -> waitingColor()
        SessionAvailability.OFFLINE -> MaterialTheme.colorScheme.outline
    }

@Composable
internal fun chatStatusColor(status: String?, connected: Boolean): Color =
    when {
        !connected -> MaterialTheme.colorScheme.outline
        status == "idle" -> idleColor()
        status == "running" -> MaterialTheme.colorScheme.primary
        status == "waiting" -> waitingColor()
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
internal fun waitingContainerColor(): Color =
    if (isDarkSurface()) Color(0xFF4A3312) else Color(0xFFFDEBD3)

@Composable
internal fun onWaitingContainerColor(): Color =
    if (isDarkSurface()) Color(0xFFFFDDB3) else Color(0xFF8A3B0B)

// Diff rows. The '+'/'-' gutter carries the meaning too, so these only reinforce it.

@Composable
internal fun diffAddedContainer(): Color =
    if (isDarkSurface()) Color(0xFF1D3A28) else Color(0xFFDCF3E3)

@Composable
internal fun diffAddedContent(): Color =
    if (isDarkSurface()) Color(0xFFA8E3BA) else Color(0xFF1B5E33)

@Composable
internal fun diffRemovedContainer(): Color =
    if (isDarkSurface()) Color(0xFF4A2226) else Color(0xFFFBE1E0)

@Composable
internal fun diffRemovedContent(): Color =
    if (isDarkSurface()) Color(0xFFFFB4AE) else Color(0xFF8C1D18)

/** The app's color scheme; the home-screen widget uses the same one through Glance. */
internal fun remoteColorScheme(dark: Boolean): ColorScheme =
if (dark)
        darkColorScheme(
            primary = Color(0xFFABC7FF),
            primaryContainer = Color(0xFF244977),
            onPrimaryContainer = Color(0xFFD9E7FF),
            background = Color(0xFF101114),
            surface = Color(0xFF1D1E23),
            surfaceContainer = Color(0xFF24262C),
            onPrimary = Color(0xFF0B2F5E),
            inversePrimary = Color(0xFF245DC8),
            secondary = Color(0xFFBDC7DC),
            onSecondary = Color(0xFF273141),
            secondaryContainer = Color(0xFF3D4758),
            onSecondaryContainer = Color(0xFFD9E3F8),
            tertiary = Color(0xFFF5B971),
            onTertiary = Color(0xFF4A2800),
            tertiaryContainer = Color(0xFF4A3312),
            onTertiaryContainer = Color(0xFFFFDDB3),
            onBackground = Color(0xFFE2E4EB),
            onSurface = Color(0xFFE2E4EB),
            surfaceVariant = Color(0xFF30333B),
            onSurfaceVariant = Color(0xFFC3C7D1),
            surfaceTint = Color(0xFFABC7FF),
            inverseSurface = Color(0xFFE2E4EB),
            inverseOnSurface = Color(0xFF2E3036),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF8D929D),
            outlineVariant = Color(0xFF43474F),
            scrim = Color.Black,
            surfaceBright = Color(0xFF3E4047),
            surfaceDim = Color(0xFF101114),
            surfaceContainerLowest = Color(0xFF0B0C0F),
            surfaceContainerLow = Color(0xFF191A1F),
            surfaceContainerHigh = Color(0xFF2E3037),
            surfaceContainerHighest = Color(0xFF393B42),
        )
    else
        lightColorScheme(
            primary = Color(0xFF245DC8),
            primaryContainer = Color(0xFFD8E6FF),
            onPrimaryContainer = Color(0xFF123869),
            background = Color(0xFFF4F5F9),
            surface = Color.White,
            surfaceContainer = Color(0xFFECEEF5),
            onPrimary = Color.White,
            inversePrimary = Color(0xFFABC7FF),
            secondary = Color(0xFF56607A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDAE2F9),
            onSecondaryContainer = Color(0xFF131C2F),
            tertiary = Color(0xFFB45309),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFDEBD3),
            onTertiaryContainer = Color(0xFF8A3B0B),
            onBackground = Color(0xFF1A1C21),
            onSurface = Color(0xFF1A1C21),
            surfaceVariant = Color(0xFFE1E4EC),
            onSurfaceVariant = Color(0xFF454A55),
            surfaceTint = Color(0xFF245DC8),
            inverseSurface = Color(0xFF2E3036),
            inverseOnSurface = Color(0xFFF0F1F6),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = Color(0xFF666D7A),
            outlineVariant = Color(0xFFC5CAD5),
            scrim = Color.Black,
            surfaceBright = Color.White,
            surfaceDim = Color(0xFFD9DCE4),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF1F3F8),
            surfaceContainerHigh = Color(0xFFE4E7EF),
            surfaceContainerHighest = Color(0xFFDDE1EA),
        )

/** Marks a folder that brings its own pi resources, which would run on the Mac once trusted. */
@Composable
internal fun folderWarningColor(): Color = waitingColor()
