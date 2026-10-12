package de.joinnoah.pi.remote

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper

internal fun openOnPhone(context: Context, selection: RemoteSelection, failure: (Int) -> Unit) {
    val uri = watchHandoffUri(selection) ?: return
    val executor = ContextCompat.getMainExecutor(context)
    try {
        val future = RemoteActivityHelper(context, executor).startRemoteActivity(
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), null,
        )
        future.addListener({
            try {
                future.get()
                Toast.makeText(context, R.string.wear_phone_opened, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { failure(R.string.wear_phone_unavailable) }
        }, executor)
    } catch (_: Exception) { failure(R.string.wear_phone_unavailable) }
}
