package de.joinnoah.pi.remote.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A same-UID, non-exported control surface for the debug IME process only. */
class PocketPiTestImeControlProvider : ContentProvider() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        fun wrap(json: JSONObject) = Bundle().apply { putString("json", json.toString()) }
        fun failure(message: String) = wrap(JSONObject().put("ok", false).put("error", message))
        if (Binder.getCallingUid() != Process.myUid()) return failure("caller UID rejected")
        if (arg != null) return failure("arg is unsupported")
        if (method !in setOf("snapshot", "reset", "action")) return failure("unknown method")
        val appContext = context ?: return failure("provider context unavailable")
        // 0 = queued, 1 = started, 2 = cancelled before start. The CAS makes
        // the timeout outcome reliable even when the handler races the caller.
        val state = AtomicInteger(0)
        val task = FutureTask {
            if (!state.compareAndSet(0, 1)) throw IllegalStateException("cancelled before start")
            when (method) {
                "snapshot" -> PocketPiTestIme.snapshot(appContext)
                "reset" -> PocketPiTestIme.reset()
                else -> PocketPiTestIme.action(appContext, extras)
            }
        }
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run()
            else if (!handler.post(task)) return failure("main handler unavailable")
            wrap(task.get(5, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            val notStarted = state.compareAndSet(0, 2)
            task.cancel(false)
            failure(if (notStarted) "timeout; operation not started" else "timeout; operation outcome unknown")
        } catch (e: Exception) {
            task.cancel(false)
            failure("control call failed: ${e.javaClass.simpleName}")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
