package de.joinnoah.pi.remote

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStatsTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/host-stats-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun name(entry: JsonObject) = entry.getValue("name").jsonPrimitive.content

    private fun payload(entry: JsonObject) = entry.getValue("payload").jsonObject

    private fun data(name: String): JsonObject =
        payload(entries("wireValid").single { name(it) == name }).getValue("data").jsonObject

    @Test
    fun fixturePinsTheCapability() {
        assertEquals(HOST_STATS_CAPABILITY, fixture.getValue("capability").jsonPrimitive.content)
        assertEquals("host.stats.v1", HOST_STATS_CAPABILITY)
    }

    @Test
    fun everyValidResultParses() {
        val results = entries("wireValid").map(::payload).filter { it["type"] == JsonPrimitive("result") }
        assertEquals(4, results.size)
        results.forEach { parseHostStats(it.getValue("data").jsonObject) }
    }

    @Test
    fun everyInvalidResultIsRefused() {
        val results = entries("wireInvalid").filter { payload(it)["type"] == JsonPrimitive("result") }
        assertTrue(results.size >= 7)
        results.forEach { entry ->
            assertThrows(name(entry), IllegalArgumentException::class.java) {
                parseHostStats(payload(entry).getValue("data").jsonObject)
            }
        }
    }

    @Test
    fun macSampleCarriesEveryField() {
        val stats = parseHostStats(data("mac"))
        assertEquals(HostPlatform.DARWIN, stats.platform)
        assertEquals(37.5, stats.cpuPercent!!, 0.0)
        assertEquals(10.0, stats.cores, 0.0)
        assertEquals(listOf(3.12, 2.5, 2.01), stats.load)
        assertEquals(HostUsage(12884901888, 17179869184), stats.memory)
        assertEquals(MemoryPressure.NORMAL, stats.pressure)
        assertEquals(HostBattery(87.0, charging = false, onAc = false), stats.battery)
        assertNull(stats.container)
    }

    @Test
    fun firstSampleHasNoCpuPercentAndNoOptionalFields() {
        val stats = parseHostStats(data("mac-first-sample"))
        assertNull(stats.cpuPercent)
        assertNull(stats.load)
        assertNull(stats.pressure)
        assertNull(stats.disk)
        assertNull(stats.battery)
    }

    @Test
    fun containerCarriesItsLimitsAndNoLoad() {
        val limited = parseHostStats(data("linux-container"))
        assertEquals(HostContainer(1.5, 2147483648), limited.container)
        assertEquals(1.5, limited.cores, 0.0)
        assertNull(limited.load)
        val unlimited = parseHostStats(data("linux-container-unlimited"))
        assertEquals(HostContainer(null, null), unlimited.container)
    }

    @Test
    fun capabilityGatesTheSection() {
        val connected = RemoteState(connected = true, capabilities = setOf(HOST_STATS_CAPABILITY))
        assertTrue(canUseHostStats(connected))
        assertFalse(canUseHostStats(connected.copy(connected = false)))
        assertFalse(canUseHostStats(connected.copy(capabilities = emptySet())))
        assertFalse(canUseHostStats(connected.copy(unavailableCapabilities = setOf(HOST_STATS_CAPABILITY))))
    }

    @Test
    fun formattingHelpers() {
        assertEquals(Triple(3L, 4L, 0L), uptimeParts(3 * 86400L + 4 * 3600L + 59))
        assertEquals(Triple(0L, 1L, 30L), uptimeParts(5400))
        assertEquals("1,5", formatNumber(1.5, 2, Locale.GERMANY))
        assertEquals("7.4", formatNumber(7.4, 1, Locale.US))
        assertEquals("73", formatNumber(72.6, 0, Locale.US))
        assertEquals(LoadLevel.NORMAL, levelOf(0.79))
        assertEquals(LoadLevel.WARN, levelOf(0.8))
        assertEquals(LoadLevel.CRITICAL, levelOf(0.95))
        assertEquals(0f, HostUsage(0, 0).fraction, 0f)
    }
}
