package de.joinnoah.pi.remote

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * Returns a function that puts text on the clipboard under [label]. Android 13 and later show
 * their own clipboard confirmation; older versions get a short "Copied" toast instead.
 */
@Composable
internal fun rememberCopyToClipboard(label: String): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.remote_copied)
    return { text ->
        scope.launch {
            val (clipText, _) = clipboardSafeText(text)
            val success =
                runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, clipText))) }.isSuccess
            if (success && Build.VERSION.SDK_INT < 33)
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
