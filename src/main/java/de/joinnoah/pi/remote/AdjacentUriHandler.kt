package de.joinnoah.pi.remote

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri

/**
 * Opens links from the chat and the settings. In split screen, a desktop window (DeX) or any window
 * at least [TWO_PANE_MIN_WIDTH] wide, as [adjacent] says at the moment of the tap, the browser opens
 * in the other half of the screen beside PocketPi instead of covering it. Otherwise the link opens
 * the way Compose's own handler opens it.
 *
 * Like Compose's handler it throws [IllegalArgumentException], caused by the
 * [ActivityNotFoundException], when no app opens the link; text links drop that silently.
 */
internal class AdjacentUriHandler(
    private val context: Context,
    private val adjacent: () -> Boolean,
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            context.startActivity(linkIntent(uri, adjacent()))
        } catch (error: ActivityNotFoundException) {
            throw IllegalArgumentException("Can't open $uri.", error)
        }
    }
}

/** The view intent for [uri]; [adjacent] asks the system to place it beside this window. */
// The watch app compiles this file but never opens links through it.
@SuppressLint("WearRecents")
internal fun linkIntent(uri: String, adjacent: Boolean): Intent =
    Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
        // The adjacent flag only takes effect together with a new task.
        if (adjacent) addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/** Whether links open beside a window that is in [inMultiWindow] mode and [width] wide. */
internal fun opensLinksAdjacent(inMultiWindow: Boolean, width: Dp): Boolean =
    inMultiWindow || width >= TWO_PANE_MIN_WIDTH
