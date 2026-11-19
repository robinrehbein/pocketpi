package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host limits (`session.limits.v1`). Only the `capabilities.v4:` route advertises it, and every v4
 * `projects` result then carries a `limits` object; no other route's result may carry one. Without
 * it the client keeps its built-in values.
 */
internal const val SESSION_LIMITS_CAPABILITY = "session.limits.v1"
private const val LIMITS_ROUTE_PREFIX = "capabilities.v4:"
private const val MAX_FRAME_BYTES = 16L * 1024 * 1024

public data class StreamLimits(val maxFrameRate: Long, val minFrameIntervalMs: Long, val frameMaxBytes: Long, val frameChunkBytes: Long)
public data class ViewportLimits(val minWidth: Long, val maxWidth: Long, val minHeight: Long, val maxHeight: Long, val minDeviceScale: Double, val maxDeviceScale: Double)
public data class BrowserLimits(val viewport: ViewportLimits, val stream: StreamLimits, val maxInFlightInputs: Long)
public data class EmulatorLimits(val stream: StreamLimits, val maxInFlightInputs: Long)
public data class SessionLimits(val browser: BrowserLimits, val emulator: EmulatorLimits)

private fun JsonElement?.limitsObject(vararg keys: String): JsonObject {
    val o = this as? JsonObject ?: throw IllegalArgumentException("Invalid limits")
    require(o.keys == keys.toSet()) { "Invalid limits" }
    return o
}

private fun JsonObject.limitsNumber(key: String): Double {
    val p = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("Invalid limits")
    require(!p.isString && p !is JsonNull && p.content != "true" && p.content != "false") { "Invalid limits" }
    return p.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Invalid limits")
}

private fun JsonObject.limitsInt(key: String, min: Long, max: Long): Long {
    val d = limitsNumber(key)
    require(d % 1.0 == 0.0 && d >= min.toDouble() && d <= max.toDouble()) { "Invalid limits" }
    return d.toLong()
}

private fun parseStreamLimits(value: JsonElement?): StreamLimits {
    val o = value.limitsObject("maxFrameRate", "minFrameIntervalMs", "frameMaxBytes", "frameChunkBytes")
    val frameMax = o.limitsInt("frameMaxBytes", 1, MAX_FRAME_BYTES)
    return StreamLimits(o.limitsInt("maxFrameRate", 1, 120), o.limitsInt("minFrameIntervalMs", 0, 60_000), frameMax, o.limitsInt("frameChunkBytes", 1, frameMax))
}

/** Strict, as the host's `validSessionLimits`: exact keys, bounded integers, each minimum at most its maximum. */
internal fun parseSessionLimits(value: JsonElement?): SessionLimits {
    val o = value.limitsObject("browser", "emulator")
    val browser = o["browser"].limitsObject("viewport", "stream", "maxInFlightInputs")
    val emulator = o["emulator"].limitsObject("stream", "maxInFlightInputs")
    val v = browser["viewport"].limitsObject("minWidth", "maxWidth", "minHeight", "maxHeight", "minDeviceScale", "maxDeviceScale")
    val minWidth = v.limitsInt("minWidth", 1, 16384)
    val minHeight = v.limitsInt("minHeight", 1, 16384)
    val minScale = v.limitsNumber("minDeviceScale").also { require(it in 0.1..10.0) { "Invalid limits" } }
    val viewport = ViewportLimits(
        minWidth, v.limitsInt("maxWidth", minWidth, 16384), minHeight, v.limitsInt("maxHeight", minHeight, 16384),
        minScale, v.limitsNumber("maxDeviceScale").also { require(it in minScale..10.0) { "Invalid limits" } },
    )
    return SessionLimits(
        BrowserLimits(viewport, parseStreamLimits(browser["stream"]), browser.limitsInt("maxInFlightInputs", 1, 1024)),
        EmulatorLimits(parseStreamLimits(emulator["stream"]), emulator.limitsInt("maxInFlightInputs", 1, 1024)),
    )
}

/**
 * The `limits` of a `projects` result [data] answering request [requestId], or null when it has
 * none. Throws when a result for any route but `capabilities.v4:` carries `limits`, or when they
 * are malformed.
 */
internal fun parseProjectsLimits(requestId: String, data: JsonObject): SessionLimits? {
    if ("limits" !in data) return null
    require(requestId.startsWith(LIMITS_ROUTE_PREFIX)) { "Limits outside the v4 route" }
    return parseSessionLimits(data["limits"])
}

/**
 * [parseProjectsLimits], with malformed or misplaced limits read as none: the built-in values
 * then hold, and the projects and capabilities of the same result stay usable.
 */
internal fun parseProjectsLimitsOrNull(requestId: String, data: JsonObject): SessionLimits? =
    try { parseProjectsLimits(requestId, data) } catch (_: Exception) { null }

/**
 * The browser viewport bounds the pane asks for: the host's [limits] where it sent them, narrowed
 * to the built-in bounds, which the client's strict command encoder checks; the built-in bounds
 * when the two do not overlap or the host sent none.
 */
internal data class BrowserViewportBounds(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int, val minScale: Double, val maxScale: Double)
internal val BUILT_IN_BROWSER_VIEWPORT = BrowserViewportBounds(80, 2048, 80, 2048, 0.5, 3.0)

internal fun browserViewportBounds(limits: ViewportLimits?): BrowserViewportBounds {
    val b = BUILT_IN_BROWSER_VIEWPORT
    limits ?: return b
    val narrowed = BrowserViewportBounds(
        maxOf(b.minWidth.toLong(), limits.minWidth).toInt(), minOf(b.maxWidth.toLong(), limits.maxWidth).toInt(),
        maxOf(b.minHeight.toLong(), limits.minHeight).toInt(), minOf(b.maxHeight.toLong(), limits.maxHeight).toInt(),
        maxOf(b.minScale, limits.minDeviceScale), minOf(b.maxScale, limits.maxDeviceScale),
    )
    return if (narrowed.minWidth <= narrowed.maxWidth && narrowed.minHeight <= narrowed.maxHeight && narrowed.minScale <= narrowed.maxScale) narrowed else b
}

/**
 * How many inputs the client may have queued or unacknowledged at once: the host's limit where it
 * sent one, never more than the client's own [builtIn] bound.
 */
internal fun inFlightInputBound(hostLimit: Long?, builtIn: Int): Int =
    if (hostLimit == null) builtIn else minOf(hostLimit, builtIn.toLong()).toInt().coerceAtLeast(1)

/**
 * How long a watched stream may stay silent before it counts as stalled: the built-in [deadline],
 * extended by the host's pause after each frame where that pause exceeds the built-in one.
 */
internal fun streamDeadlineMs(stream: StreamLimits?, deadline: Long, builtInIntervalMs: Long = 250L): Long =
    if (stream == null) deadline else deadline + (stream.minFrameIntervalMs - builtInIntervalMs).coerceAtLeast(0)
