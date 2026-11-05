package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The load of the machine the host runs on (`host.stats`, protocol README "Host load"). Advertised
 * only on the `capabilities.v3:` route of `projects.list`. On Linux inside a container the CPU and
 * memory numbers are the container's, and [HostStats.container] carries its limits.
 */
internal const val HOST_STATS_CAPABILITY = "host.stats.v1"

/** How long the details sheet waits after one answer before it asks again. */
internal const val HOST_STATS_POLL_MILLIS = 1_000L

private const val MAX_CORES = 4096.0
private const val MAX_LOAD = 1_000_000.0
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

enum class HostPlatform(val wire: String) { DARWIN("darwin"), LINUX("linux"), OTHER("other") }

enum class MemoryPressure(val wire: String) { NORMAL("normal"), WARN("warn"), CRITICAL("critical") }

data class HostUsage(val usedBytes: Long, val totalBytes: Long) {
    /** Used share, 0..1; 0 when the total is unknown. */
    val fraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toDouble() / totalBytes).toFloat()
}

data class HostBattery(val percent: Double, val charging: Boolean, val onAc: Boolean)

data class HostContainer(val cpuLimitCores: Double?, val memoryLimitBytes: Long?)

data class HostStats(
    val platform: HostPlatform,
    val sampledAt: Long,
    val uptimeSeconds: Long,
    /** 0..100; null until the host has a baseline. */
    val cpuPercent: Double?,
    val cores: Double,
    /** 1, 5 and 15 minute load averages; null inside a container. */
    val load: List<Double>?,
    val memory: HostUsage,
    val pressure: MemoryPressure?,
    val disk: HostUsage?,
    val battery: HostBattery?,
    val container: HostContainer?,
)

/** Stats show with the capability and while the host has not reported it unavailable. */
internal fun canUseHostStats(state: RemoteState): Boolean =
    state.connected && HOST_STATS_CAPABILITY in state.capabilities && HOST_STATS_CAPABILITY !in state.unavailableCapabilities

private fun JsonObject.hsObject(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)

private fun JsonObject.hsNumber(key: String, min: Double, max: Double): Double {
    val primitive = requireNotNull(this[key] as? JsonPrimitive)
    require(!primitive.isString)
    val value = requireNotNull(primitive.doubleOrNull)
    require(value.isFinite() && value in min..max)
    return value
}

private fun JsonObject.hsCount(key: String): Long {
    val primitive = requireNotNull(this[key] as? JsonPrimitive)
    require(!primitive.isString)
    val value = requireNotNull(primitive.longOrNull)
    require(value in 0..MAX_SAFE_INTEGER)
    return value
}

private fun JsonObject.hsFlag(key: String): Boolean {
    val primitive = requireNotNull(this[key] as? JsonPrimitive)
    require(!primitive.isString)
    return requireNotNull(primitive.booleanOrNull)
}

private fun JsonObject.hsText(key: String): String {
    val primitive = requireNotNull(this[key] as? JsonPrimitive)
    require(primitive.isString)
    return primitive.content
}

private fun JsonObject.hsUsage(): HostUsage {
    val used = hsCount("usedBytes")
    val total = hsCount("totalBytes")
    require(used <= total)
    return HostUsage(used, total)
}

/** Validates a `host.stats` result as strictly as the host's own validator. */
internal fun parseHostStats(data: JsonObject): HostStats {
    require(data.hsText("kind") == "host.stats")
    Wire.keys(
        data,
        setOf("kind", "platform", "sampledAt", "uptimeSeconds", "cpu", "memory"),
        setOf("disk", "battery", "container"),
    )
    val platformWire = data.hsText("platform")
    val platform = requireNotNull(HostPlatform.entries.firstOrNull { it.wire == platformWire })
    val cpu = data.hsObject("cpu")
    Wire.keys(cpu, setOf("cores"), setOf("usagePercent", "load"))
    val load =
        cpu["load"]?.let { value ->
            val items = requireNotNull(value as? JsonArray)
            require(items.size == 3)
            items.map { item ->
                val primitive = requireNotNull(item as? JsonPrimitive)
                require(!primitive.isString)
                val n = requireNotNull(primitive.doubleOrNull)
                require(n.isFinite() && n in 0.0..MAX_LOAD)
                n
            }
        }
    val memoryObject = data.hsObject("memory")
    Wire.keys(memoryObject, setOf("usedBytes", "totalBytes"), setOf("pressure"))
    val pressure =
        memoryObject["pressure"]?.let {
            val wire = memoryObject.hsText("pressure")
            requireNotNull(MemoryPressure.entries.firstOrNull { p -> p.wire == wire })
        }
    val disk =
        data["disk"]?.let {
            val o = data.hsObject("disk")
            Wire.keys(o, setOf("usedBytes", "totalBytes"))
            o.hsUsage()
        }
    val battery =
        data["battery"]?.let {
            val o = data.hsObject("battery")
            Wire.keys(o, setOf("percent", "charging", "onAc"))
            HostBattery(o.hsNumber("percent", 0.0, 100.0), o.hsFlag("charging"), o.hsFlag("onAc"))
        }
    val container =
        data["container"]?.let {
            val o = data.hsObject("container")
            Wire.keys(o, emptySet(), setOf("cpuLimitCores", "memoryLimitBytes"))
            HostContainer(
                o["cpuLimitCores"]?.let { o.hsNumber("cpuLimitCores", 0.01, MAX_CORES) },
                o["memoryLimitBytes"]?.let { o.hsCount("memoryLimitBytes") },
            )
        }
    require(platform == HostPlatform.LINUX || container == null)
    require(container == null || load == null)
    require(platform == HostPlatform.DARWIN || pressure == null)
    return HostStats(
        platform = platform,
        sampledAt = data.hsCount("sampledAt"),
        uptimeSeconds = data.hsCount("uptimeSeconds"),
        cpuPercent = cpu["usagePercent"]?.let { cpu.hsNumber("usagePercent", 0.0, 100.0) },
        cores = cpu.hsNumber("cores", 0.01, MAX_CORES),
        load = load,
        memory = memoryObject.hsUsage(),
        pressure = pressure,
        disk = disk,
        battery = battery,
        container = container,
    )
}
