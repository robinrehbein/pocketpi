package de.joinnoah.pi.remote.testing

import android.content.ComponentName
import android.inputmethodservice.InputMethodService
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only IME. All state is confined to the IME process main thread. */
class PocketPiTestIme : InputMethodService() {
    companion object {
        private const val MAX_TRACE = 2048
        private const val MAX_TRACE_CHARS = 128 * 1024
        private const val MAX_TEXT = 8192
        private var service: PocketPiTestIme? = null
        private var generation = 0
        private var sequence = 0L
        private var active = false
        private var editorPackage: String? = null
        private var overflow = false
        private var traceChars = 0
        private val events = ArrayList<JSONObject>()

        private fun record(name: String, details: JSONObject = JSONObject()) {
            val event = details.put("sequence", ++sequence)
                .put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
                .put("event", name).put("generation", generation)
            val eventChars = event.toString().length + 1
            if (!overflow && events.size < MAX_TRACE && traceChars + eventChars <= MAX_TRACE_CHARS) {
                events.add(event)
                traceChars += eventChars
            } else {
                overflow = true
            }
        }

        private fun response(ok: Boolean, error: String? = null): JSONObject =
            JSONObject().put("ok", ok).put("generation", generation).also {
                if (error != null) it.put("error", error)
            }

        fun reset(): JSONObject {
            events.clear()
            traceChars = 0
            overflow = false
            return response(true)
        }

        fun snapshot(context: android.content.Context): JSONObject {
            val ime = service
            val permitted = active && editorPackage == context.packageName
            val connection = if (permitted) ime?.currentInputConnection else null
            val result = JSONObject()
                .put("component", ComponentName(context, PocketPiTestIme::class.java).flattenToShortString())
                .put("pid", Process.myPid()).put("generation", generation).put("active", active)
                .put("packageName", editorPackage ?: JSONObject.NULL)
                .put("connection", connection != null).put("overflow", overflow)
                .put("events", JSONArray(events))
            if (connection != null) {
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                val value = extracted?.text?.toString()
                if (value != null && value.length <= MAX_TEXT) {
                    result.put("text", value).put("selectionStart", extracted.selectionStart)
                        .put("selectionEnd", extracted.selectionEnd)
                }
            }
            return result
        }

        fun action(context: android.content.Context, args: android.os.Bundle?): JSONObject {
            if (overflow) return response(false, "trace overflow; reset required")
            if (args == null || !args.containsKey("generation") || args.get("generation") !is Int) {
                return response(false, "generation is required")
            }
            val requestedGeneration = args.getInt("generation")
            if (requestedGeneration != generation) return response(false, "stale generation")
            if (!active || editorPackage != context.packageName) return response(false, "no active own-package editor")
            val connection = service?.currentInputConnection ?: return response(false, "no current input connection")
            val op = args.get("op") as? String ?: return response(false, "op is required")
            val details = JSONObject().put("op", op)
            val returned = when (op) {
                "select" -> {
                    if (args.get("start") !is Int || args.get("end") !is Int) return response(false, "selection is required")
                    val start = args.getInt("start")
                    val end = args.getInt("end")
                    if (start < 0 || end < 0) return response(false, "selection must be nonnegative")
                    details.put("start", start).put("end", end)
                    connection.setSelection(start, end)
                }
                "compose", "commit" -> {
                    if (args.containsKey("cursor") && (args.get("cursor") !is Int || args.getInt("cursor") != 1)) {
                        return response(false, "cursor must be 1")
                    }
                    val text = args.get("text") as? String ?: return response(false, "text is required")
                    if (text.length > MAX_TEXT) return response(false, "text exceeds limit")
                    details.put("text", text).put("cursor", 1)
                    if (op == "compose") connection.setComposingText(text, 1) else connection.commitText(text, 1)
                }
                "finish" -> connection.finishComposingText()
                else -> return response(false, "unknown op")
            }
            record("action", details.put("result", returned))
            return response(true).put("result", returned)
        }
    }

    override fun onCreate() {
        super.onCreate()
        service = this
    }

    override fun onCreateInputView(): View = View(this).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (48 * resources.displayMetrics.density).toInt(),
        )
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        generation++
        active = true
        editorPackage = attribute?.packageName
        record("startInput", JSONObject().put("packageName", editorPackage ?: JSONObject.NULL)
            .put("restarting", restarting).put("inputType", attribute?.inputType ?: 0)
            .put("imeOptions", attribute?.imeOptions ?: 0).put("fieldId", attribute?.fieldId ?: 0)
            .put("initialSelStart", attribute?.initialSelStart ?: -1)
            .put("initialSelEnd", attribute?.initialSelEnd ?: -1))
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        record("startInputView", JSONObject().put("packageName", info?.packageName ?: JSONObject.NULL)
            .put("restarting", restarting))
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        record("updateSelection", JSONObject().put("oldSelStart", oldSelStart).put("oldSelEnd", oldSelEnd)
            .put("newSelStart", newSelStart).put("newSelEnd", newSelEnd)
            .put("candidatesStart", candidatesStart).put("candidatesEnd", candidatesEnd))
    }

    override fun onFinishInput() {
        super.onFinishInput()
        active = false
        editorPackage = null
        record("finishInput")
    }

    override fun onDestroy() {
        active = false
        editorPackage = null
        service = null
        super.onDestroy()
    }
}
