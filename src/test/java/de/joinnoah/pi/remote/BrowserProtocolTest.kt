package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserProtocolTest {
    private fun json(s: String) = Wire.json.parseToJsonElement(s).jsonObject
    private fun rejected(block: () -> Unit) = assertThrows(Exception::class.java) { block() }

    @Test fun sharedHostFixturesValidateAndCommandsRoundTrip() {
        val fixture = json(javaClass.getResource("/browser-v1.json")!!.readText())
        for (group in listOf("valid", "invalid")) for (entry in fixture.getValue(group).jsonArray) {
            val payload = entry.jsonObject.obj("payload")
            val check = {
                if (payload.text("type") == "result") parseBrowserResult(payload.obj("data"))
                else assertEquals(payload, encodeBrowserCommand(parseBrowserCommand(payload)))
            }
            if (group == "valid") check() else rejected { check() }
        }
    }

    @Test fun inputBoundsAndUnicodeAreEnforcedBeforeSending() {
        val base = """{"type":"browser.input","requestId":"r","sessionId":"s","browserId":"b","leaseId":"l","generation":1,"tabId":"t","viewportRevision":0,"sequence":0,"input":INPUT}"""
        fun check(input: JsonObject) = parseBrowserCommand(json(base.replace("INPUT", input.toString())))
        fun key(s: String) = buildJsonObject { put("kind", "key"); put("action", "down"); put("key", s); put("code", "KeyA") }
        fun text(s: String) = buildJsonObject { put("kind", "text"); put("text", s) }
        check(key("é".repeat(32))); rejected { check(key("é".repeat(33))) }
        check(text("a".repeat(4096))); rejected { check(text("a".repeat(4097))) }
        rejected { encodeBrowserCommand(BrowserCommand.Input("r", "s", "b", "l", 1, "t", 0, 0, BrowserInput.Text("\uD800"))) }
        check(json("""{"kind":"pointer","x":2048,"y":0,"action":"move","button":"left"}"""))
        rejected { check(json("""{"kind":"pointer","x":2048.1,"y":0,"action":"move","button":"left"}""")) }
        rejected { check(json("""{"kind":"key","action":"down","key":"a","code":"KeyA","ctrl":null}""")) }
    }

    @Test fun resultRejectsUnknownFieldsNullAndUnsafeCounts() {
        fun result(extra: String) = json("""{"kind":"browser.busy","browserId":"b","generation":1,"owner":{"kind":"device","id":"d"}$extra}""")
        assertTrue(parseBrowserResult(result("")) is BrowserResult.Busy)
        rejected { parseBrowserResult(result(",\"leaseId\":\"l\"")) }
        rejected { parseBrowserResult(json("""{"kind":"browser.opened","browserId":"b","generation":0,"incarnation":"i","tabs":[],"viewportRevision":0,"owner":null}""")) }
        rejected { parseBrowserResult(json("""{"kind":"browser.released","browserId":"b","generation":9007199254740992}""")) }
        rejected { parseBrowserResult(json("""{"kind":"browser.released","browserId":"b","generation":1.5}""")) }
    }
}
