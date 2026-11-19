package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLimitsTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/session-limits-v1.json")!!.readText()).jsonObject
    }
    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }
    private fun payload(entry: JsonObject) = entry.getValue("payload").jsonObject

    @Test fun fixturePinsTheCapabilityAndRoute() {
        assertEquals(SESSION_LIMITS_CAPABILITY, fixture.getValue("capability").jsonPrimitive.content)
        val v4 = Wire.objectOf("kind" to "projects", "limits" to payload(entries("wireValid").single { it.text("name") == "result-with-limits" }).obj("data").getValue("limits"))
        for (id in fixture.obj("route").getValue("allowed").jsonArray) assertNotNull(parseProjectsLimits(id.jsonPrimitive.content, v4))
        for (id in fixture.obj("route").getValue("refused").jsonArray.filter { it.jsonPrimitive.isString })
            assertThrows(IllegalArgumentException::class.java) { parseProjectsLimits(id.jsonPrimitive.content, v4) }
    }

    @Test fun everyValidResultParsesAndEveryInvalidOneIsRefused() {
        for (entry in entries("wireValid")) {
            val p = payload(entry)
            if (p.text("type") != "result") continue
            val limits = parseProjectsLimits(p.text("requestId"), p.obj("data"))
            if (entry.text("name") == "result-without-limits") assertNull(limits) else assertNotNull(entry.text("name"), limits)
        }
        for (entry in entries("wireInvalid")) {
            val p = payload(entry)
            assertThrows(entry.text("name"), Exception::class.java) { parseProjectsLimits(p.text("requestId"), p.obj("data")) }
            assertNull(parseProjectsLimitsOrNull(p.text("requestId"), p.obj("data")))
        }
    }

    @Test fun fractionalDeviceScalesAreKept() {
        val p = payload(entries("wireValid").single { it.text("name") == "result-fractional-device-scale" })
        val limits = requireNotNull(parseProjectsLimits(p.text("requestId"), p.obj("data")))
        assertEquals(0.75, limits.browser.viewport.minDeviceScale, 0.0)
        assertEquals(2.5, limits.browser.viewport.maxDeviceScale, 0.0)
        assertEquals(BrowserViewportBounds(80, 2048, 80, 2048, 0.75, 2.5), browserViewportBounds(limits.browser.viewport))
    }

    @Test fun limitsOnlyNarrowTheBuiltInBounds() {
        assertEquals(BUILT_IN_BROWSER_VIEWPORT, browserViewportBounds(null))
        assertEquals(BrowserViewportBounds(200, 1024, 80, 2048, 1.0, 3.0), browserViewportBounds(ViewportLimits(200, 1024, 1, 16384, 1.0, 10.0)))
        // No overlap with the built-in bounds: the built-in ones hold.
        assertEquals(BUILT_IN_BROWSER_VIEWPORT, browserViewportBounds(ViewportLimits(4096, 8192, 80, 2048, 0.5, 3.0)))
        assertEquals(32, inFlightInputBound(null, 32))
        assertEquals(8, inFlightInputBound(8, 32))
        assertEquals(32, inFlightInputBound(1024, 32))
        assertEquals(5500L, streamDeadlineMs(null, 5500L))
        val slow = StreamLimits(1, 1000, 1048576, 49152)
        assertEquals(6250L, streamDeadlineMs(slow, 5500L))
        assertTrue(streamDeadlineMs(StreamLimits(4, 250, 1048576, 49152), 5500L) == 5500L)
    }
}
