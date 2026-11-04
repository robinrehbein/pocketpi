package de.joinnoah.pi.remote

import java.util.Locale
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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

    // ---- Repository ---------------------------------------------------------------------

    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")

    private class Pairings(val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)

        override fun save(hosts: List<PairedHost>) {}
    }

    private class Drafts : DraftStorage {
        var values = emptyMap<DraftKey, StoredDraft>()

        override fun load() = values

        override fun save(drafts: Map<DraftKey, StoredDraft>) {
            values = drafts.toMap()
        }
    }

    private class Transport(val capabilities: List<String>, val stats: JsonObject) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()

        override fun connect(host: PairedHost) = listener.ready(emptySet())

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            val data =
                when (payload.text("type")) {
                    "projects.list" ->
                        Wire.objectOf(
                            "kind" to "projects",
                            "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                            "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)),
                        )
                    "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(emptyList()))
                    "host.stats" -> stats
                    else -> Wire.objectOf("kind" to "accepted")
                }
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to data)
            )
        }

        fun stats() = sent.filter { it.text("type") == "host.stats" }
    }

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository =
            DefaultRemoteRepository(
                Pairings(host),
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                now = { testScheduler.currentTime },
            )
        repository.activate(RemoteSelection("host", "project", null))
        runCurrent()
        return repository
    }

    @Test
    fun repositoryKeepsTheCapabilityFromTheV3RouteAndSendsTheFixtureCommand() = runTest {
        val transport = Transport(listOf(HOST_STATS_CAPABILITY), data("mac"))
        val repository = connected(transport)
        assertTrue(canUseHostStats(repository.state.value))
        assertEquals(37.5, repository.hostStats().cpuPercent!!, 0.0)
        val request = payload(entries("wireValid").single { name(it) == "request" })
        assertEquals(listOf(JsonObject(request - "requestId")), transport.stats().map { JsonObject(it - "requestId") })
    }

    @Test
    fun repositorySendsNothingWithoutTheCapability() = runTest {
        val transport = Transport(emptyList(), data("mac"))
        val repository = connected(transport)
        assertFalse(canUseHostStats(repository.state.value))
        val error = runCatching { repository.hostStats() }.exceptionOrNull()
        assertTrue(error.toString(), error?.isUnsupported == true)
        assertTrue(transport.stats().isEmpty())
    }
}
