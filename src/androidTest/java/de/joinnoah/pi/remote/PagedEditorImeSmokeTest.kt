package de.joinnoah.pi.remote

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in feasibility check of a real system IME against a small Compose field. */
class PagedEditorImeSmokeTest {
    private val compose = createAndroidComposeRule<NavigationTestActivity>()
    private val visible = mutableStateOf(true)
    private val fieldStates = Collections.synchronizedList(mutableListOf<JSONObject>())
    @Volatile private var field = TextFieldValue("alpha beta gamma")
    private lateinit var nonce: String
    private var lastSnapshot: JSONObject? = null
    private var defaultIme: String? = null
    private var servicePid: Int? = null
    private val staleCheck = JSONObject()
    private val uri = Uri.parse("content://de.robinrehbein.pocketpi.test-ime")

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Pass pagedImeSmokeExpected=true to run the controlled IME smoke", args.getString("pagedImeSmokeExpected") == "true")
            nonce = requireNotNull(args.getString("pagedImeSmokeNonce")) { "pagedImeSmokeNonce is required" }
            require(Regex("[0-9a-fA-F]{32}").matches(nonce)) { "pagedImeSmokeNonce must be 32 hex characters" }
            NavigationTestActivity.contentFactory = {
                {
                    if (visible.value) {
                        var value by remember { mutableStateOf(TextFieldValue("alpha beta gamma")) }
                        OutlinedTextField(value = value, onValueChange = {
                            value = it
                            field = it
                            fieldStates.add(fieldJson(it))
                        }, modifier = Modifier.testTag("imeSmokeField"))
                    }
                }
            }
        }
        override fun after() { NavigationTestActivity.contentFactory = { {} } }
    }).around(compose)

    @Test fun realImeCompositionAndConnectionReplacement() {
        try {
            val context = compose.activity
            val expected = ComponentName(context.packageName, "de.joinnoah.pi.remote.testing.PocketPiTestIme")
            defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            assertEquals("The test IME must already be selected externally", expected, defaultIme?.let(ComponentName::unflattenFromString))
            assertTrue(call("reset").getBoolean("ok"))
            fieldStates.add(fieldJson(field))
            compose.onNodeWithTag("imeSmokeField").performClick()
            var state = awaitSnapshot { s ->
                s.optBoolean("active") && s.optBoolean("connection") && s.optString("packageName") == context.packageName &&
                    eventAfter(s, 0, "startInputView") { it.optInt("generation") == s.optInt("generation") && it.optString("packageName") == context.packageName }
            }
            assertEquals(expected, ComponentName.unflattenFromString(state.getString("component")))
            assertNotEquals("IME must run in a different process", Process.myPid(), state.getInt("pid"))
            servicePid = state.getInt("pid")
            val generation = state.getInt("generation")

            var mark = lastSequence(state)
            action(generation, "select", Bundle().apply { putInt("start", 6); putInt("end", 10) })
            state = awaitSnapshot { s -> s.optInt("generation") == generation && field.selection.start == 6 && field.selection.end == 10 &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == generation && it.optInt("newSelStart") == 6 && it.optInt("newSelEnd") == 10 } }
            mark = lastSequence(state)
            action(generation, "compose", Bundle().apply { putString("text", "ni") })
            state = awaitSnapshot { s -> s.optInt("generation") == generation && field.text == "alpha ni gamma" && field.composition?.start == 6 && field.composition?.end == 8 &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == generation && it.optInt("candidatesStart") == 6 && it.optInt("candidatesEnd") == 8 } }
            mark = lastSequence(state)
            action(generation, "compose", Bundle().apply { putString("text", "に") })
            state = awaitSnapshot { s -> s.optInt("generation") == generation && field.text == "alpha に gamma" && field.composition?.start == 6 && field.composition?.end == 7 &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == generation && it.optInt("candidatesStart") == 6 && it.optInt("candidatesEnd") == 7 } }
            mark = lastSequence(state)
            action(generation, "finish")
            state = awaitSnapshot { s -> s.optInt("generation") == generation && field.text == "alpha に gamma" && field.composition == null &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == generation && it.optInt("candidatesStart", -2) == -1 && it.optInt("candidatesEnd", -2) == -1 } }
            mark = lastSequence(state)
            action(generation, "commit", Bundle().apply { putString("text", "!") })
            state = awaitSnapshot { s -> s.optInt("generation") == generation && field.text == "alpha に! gamma" && field.selection.start == 8 && field.selection.end == 8 &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == generation && it.optInt("newSelStart") == 8 && it.optInt("newSelEnd") == 8 } }

            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { field = TextFieldValue("alpha beta gamma"); fieldStates.add(fieldJson(field)); visible.value = true }
            compose.onNodeWithTag("imeSmokeField").performClick()
            state = awaitSnapshot { s -> s.optBoolean("active") && s.optBoolean("connection") && s.optInt("generation") > generation &&
                eventAfter(s, 0, "startInputView") { it.optInt("generation") == s.optInt("generation") && it.optString("packageName") == context.packageName } }
            val newGeneration = state.getInt("generation")
            staleCheck.put("oldGeneration", generation)
            staleCheck.put("newGeneration", newGeneration)
            staleCheck.put("fieldBefore", fieldJson(field))
            val rejected = call("action", Bundle().apply { putString("op", "commit"); putInt("generation", generation); putString("text", "STALE") })
            staleCheck.put("response", rejected)
            assertFalse("Old connection generation must be rejected: $rejected", rejected.optBoolean("ok", true))
            assertEquals("stale generation", rejected.optString("error"))
            assertEquals(newGeneration, rejected.getInt("generation"))
            compose.waitForIdle()
            staleCheck.put("fieldAfter", fieldJson(field))
            assertEquals("alpha beta gamma", field.text)
            state = awaitSnapshot { s -> s.optInt("generation") == newGeneration && s.optBoolean("active") && s.optBoolean("connection") }
            mark = lastSequence(state)
            action(newGeneration, "select", Bundle().apply { putInt("start", 5); putInt("end", 5) })
            state = awaitSnapshot { s -> s.optInt("generation") == newGeneration && field.selection.start == 5 && field.selection.end == 5 &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == newGeneration && it.optInt("newSelStart") == 5 && it.optInt("newSelEnd") == 5 } }
            mark = lastSequence(state)
            action(newGeneration, "commit", Bundle().apply { putString("text", "X") })
            awaitSnapshot { s -> s.optInt("generation") == newGeneration && field.text == "alphaX beta gamma" &&
                eventAfter(s, mark, "updateSelection") { it.optInt("generation") == newGeneration && it.optInt("newSelStart") == 6 && it.optInt("newSelEnd") == 6 } }
            writeResult("proof.json", null)
        } catch (failure: Throwable) {
            if (staleCheck.has("fieldBefore") && !staleCheck.has("fieldAfter")) staleCheck.put("fieldAfterAtFailure", fieldJson(field))
            try { lastSnapshot = call("snapshot") } catch (_: Throwable) { }
            try { writeResult("diagnostic.json", failure) } catch (writeFailure: Throwable) { failure.addSuppressed(writeFailure) }
            throw failure
        }
    }

    private fun call(method: String, extras: Bundle? = null): JSONObject {
        val result = compose.activity.contentResolver.call(uri, method, null, extras)
        return JSONObject(requireNotNull(result?.getString("json")) { "No JSON from IME $method" })
    }

    private fun action(generation: Int, op: String, extras: Bundle = Bundle()) {
        extras.putString("op", op); extras.putInt("generation", generation)
        val result = call("action", extras)
        assertTrue("IME action $op failed: $result", result.optBoolean("ok") && result.optBoolean("result"))
    }

    private fun awaitSnapshot(predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(10_000) {
            val s = call("snapshot")
            lastSnapshot = s
            check(!s.optBoolean("overflow")) { "IME trace overflow" }
            servicePid?.let { check(s.getInt("pid") == it) { "IME process changed during the smoke test" } }
            predicate(s)
        }
        return requireNotNull(lastSnapshot)
    }

    private fun eventAfter(snapshot: JSONObject, sequence: Long, name: String, predicate: (JSONObject) -> Boolean = { true }): Boolean {
        val events = snapshot.getJSONArray("events")
        return (0 until events.length()).any { i ->
            val event = events.getJSONObject(i)
            event.getLong("sequence") > sequence && event.getString("event") == name && predicate(event)
        }
    }

    private fun lastSequence(snapshot: JSONObject): Long {
        val events = snapshot.getJSONArray("events")
        return if (events.length() == 0) 0 else events.getJSONObject(events.length() - 1).getLong("sequence")
    }

    private fun fieldJson(value: TextFieldValue) = JSONObject().apply {
        put("text", value.text); put("selectionStart", value.selection.start); put("selectionEnd", value.selection.end)
        put("compositionStart", value.composition?.start ?: JSONObject.NULL); put("compositionEnd", value.composition?.end ?: JSONObject.NULL)
    }

    private fun writeResult(filename: String, failure: Throwable?) {
        val dir = File(compose.activity.noBackupFilesDir, "paged-ime-smoke-$nonce")
        check(dir.mkdirs() || dir.isDirectory)
        val states = synchronized(fieldStates) { JSONArray(fieldStates.toList()) }
        val proof = JSONObject().apply {
            put("kind", "real-system-ime-compose-feasibility")
            put("nonce", nonce); put("defaultIme", defaultIme ?: JSONObject.NULL); put("appPid", Process.myPid())
            put("servicePid", lastSnapshot?.optInt("pid") ?: JSONObject.NULL)
            put("snapshot", lastSnapshot ?: JSONObject.NULL); put("fieldStates", states)
            put("staleGenerationCheck", staleCheck)
            if (failure != null) put("failure", failure.stackTraceToString())
        }
        File(dir, filename).writeText(proof.toString(2))
    }
}
