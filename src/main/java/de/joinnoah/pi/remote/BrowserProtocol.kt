package de.joinnoah.pi.remote

import java.net.URI
import kotlinx.serialization.json.*

internal const val SESSION_BROWSER_CAPABILITY = "session.browser.v1"
private const val MAX_BROWSER_COUNT = 9_007_199_254_740_991L

public sealed interface BrowserInput {
    data class Pointer(val x: Double, val y: Double, val action: String, val button: String) : BrowserInput
    data class Wheel(val x: Double, val y: Double, val deltaX: Double, val deltaY: Double) : BrowserInput
    data class Key(val action: String, val key: String, val code: String, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false, val meta: Boolean = false) : BrowserInput
    data class Text(val text: String) : BrowserInput
}
internal sealed interface BrowserCommand {
    val requestId: String
    val sessionId: String
    data class Open(override val requestId: String, override val sessionId: String) : BrowserCommand
    data class Tabs(override val requestId: String, override val sessionId: String, val browserId: String) : BrowserCommand
    data class Participants(override val requestId: String, override val sessionId: String, val browserId: String) : BrowserCommand
    data class Acquire(override val requestId: String, override val sessionId: String, val browserId: String, val expectedGeneration: Long) : BrowserCommand
    data class Handoff(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val toOwnerId: String) : BrowserCommand
    data class Navigate(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val tabId: String, val url: String) : BrowserCommand
    data class NewTab(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val url: String) : BrowserCommand
    data class Close(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val tabId: String) : BrowserCommand
    data class Viewport(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val tabId: String, val width: Long, val height: Long, val deviceScale: Double, val orientation: String) : BrowserCommand
    data class Input(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long, val tabId: String, val viewportRevision: Long, val sequence: Long, val input: BrowserInput) : BrowserCommand
    data class Detach(override val requestId: String, override val sessionId: String, val browserId: String, val leaseId: String, val generation: Long) : BrowserCommand
}
public data class BrowserOwner(val kind: String, val id: String)
public data class BrowserTab(val id: String, val title: String, val url: String)
internal sealed interface BrowserResult {
    val browserId: String
    val generation: Long
    data class Opened(override val browserId: String, override val generation: Long, val incarnation: String, val owner: BrowserOwner?, val tabs: List<BrowserTab>, val viewportRevision: Long) : BrowserResult
    data class Lease(override val browserId: String, override val generation: Long, val leaseId: String, val owner: BrowserOwner, val viewportRevision: Long) : BrowserResult
    data class Busy(override val browserId: String, override val generation: Long, val owner: BrowserOwner) : BrowserResult
    data class Tabs(override val browserId: String, override val generation: Long, val tabs: List<BrowserTab>) : BrowserResult
    data class Participants(override val browserId: String, override val generation: Long, val participants: List<BrowserOwner>) : BrowserResult
    data class Viewport(override val browserId: String, override val generation: Long, val viewportRevision: Long) : BrowserResult
    data class InputAck(override val browserId: String, override val generation: Long, val sequence: Long, val status: String) : BrowserResult
    data class Released(override val browserId: String, override val generation: Long) : BrowserResult
}
private fun browserString(s: String, max: Int, empty: Boolean = false): String {
    require((empty || s.isNotEmpty()) && Wire.fitsUtf8(s, max))
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (c.isHighSurrogate()) { require(i < s.length && s[i].isLowSurrogate()); i++ } else require(!c.isLowSurrogate())
    }
    return s
}
private fun browserId(s: String): String = browserString(s, 256).also {
    require(it.none { c -> c.isWhitespace() || Character.isSpaceChar(c) || c.code in 0..31 || c.code in 127..159 || c == '\uFEFF' })
}
private fun JsonObject.id(key: String) = browserId(text(key))
private fun JsonObject.count(key: String, min: Long = 0, max: Long = MAX_BROWSER_COUNT): Long {
    val p = getValue(key).jsonPrimitive
    require(!p.isString && p !is JsonNull)
    val d = p.content.toDoubleOrNull() ?: error("Invalid number")
    require(d.isFinite() && d >= min.toDouble() && d <= max.toDouble() && d % 1.0 == 0.0)
    return d.toLong()
}
private fun JsonObject.number(key: String, min: Double, max: Double): Double {
    val p = getValue(key).jsonPrimitive
    require(!p.isString && p !is JsonNull)
    return (p.content.toDoubleOrNull() ?: error("Invalid number")).also { require(it.isFinite() && it in min..max) }
}
private fun JsonObject.choice(key: String, vararg options: String) = text(key).also { require(it in options) }
private fun browserUrl(s: String, newTab: Boolean = false): String {
    if (newTab && s == "about:blank") return s
    browserString(s, 4096)
    require(s.none { it.isWhitespace() || Character.isSpaceChar(it) || it.code in 0..31 || it.code in 127..159 || it == '\uFEFF' })
    val uri = URI(s)
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null)
    return s
}
private fun parseBrowserInput(o: JsonObject): BrowserInput = when (o.text("kind")) {
    "pointer" -> { Wire.keys(o, setOf("kind", "x", "y", "action", "button")); BrowserInput.Pointer(o.number("x", 0.0, 2048.0), o.number("y", 0.0, 2048.0), o.choice("action", "down", "up", "move"), o.choice("button", "left", "middle", "right")) }
    "wheel" -> { Wire.keys(o, setOf("kind", "x", "y", "deltaX", "deltaY")); BrowserInput.Wheel(o.number("x", 0.0, 2048.0), o.number("y", 0.0, 2048.0), o.number("deltaX", -4096.0, 4096.0), o.number("deltaY", -4096.0, 4096.0)) }
    "key" -> {
        Wire.keys(o, setOf("kind", "action", "key", "code"), setOf("ctrl", "alt", "shift", "meta"))
        fun modifier(k: String) = if (k in o) o.flag(k).also { require(it) } else false
        BrowserInput.Key(o.choice("action", "down", "up"), browserString(o.text("key"), 64), browserString(o.text("code"), 64), modifier("ctrl"), modifier("alt"), modifier("shift"), modifier("meta"))
    }
    "text" -> { Wire.keys(o, setOf("kind", "text")); BrowserInput.Text(browserString(o.text("text"), 4096)) }
    else -> error("Unknown browser input")
}
internal fun parseBrowserCommand(o: JsonObject): BrowserCommand {
    val type = o.text("type")
    val base = setOf("type", "requestId", "sessionId")
    val b = base + "browserId"
    val l = b + setOf("leaseId", "generation")
    val fields = when (type) {
        "browser.open" -> base
        "browser.tabs", "browser.participants" -> b
        "browser.acquire" -> b + "expectedGeneration"
        "browser.handoff" -> l + "toOwnerId"
        "browser.navigate" -> l + setOf("tabId", "url")
        "browser.new_tab" -> l + "url"
        "browser.close" -> l + "tabId"
        "browser.viewport" -> l + setOf("tabId", "width", "height", "deviceScale", "orientation")
        "browser.input" -> l + setOf("tabId", "viewportRevision", "sequence", "input")
        "browser.detach" -> l
        else -> error("Unknown browser command")
    }
    Wire.keys(o, fields)
    val r = o.id("requestId"); val s = o.id("sessionId")
    if (type == "browser.open") return BrowserCommand.Open(r, s)
    val browser = o.id("browserId")
    if (type == "browser.tabs") return BrowserCommand.Tabs(r, s, browser)
    if (type == "browser.participants") return BrowserCommand.Participants(r, s, browser)
    if (type == "browser.acquire") return BrowserCommand.Acquire(r, s, browser, o.count("expectedGeneration"))
    val lease = o.id("leaseId"); val generation = o.count("generation")
    return when (type) {
        "browser.handoff" -> BrowserCommand.Handoff(r, s, browser, lease, generation, o.id("toOwnerId"))
        "browser.navigate" -> BrowserCommand.Navigate(r, s, browser, lease, generation, o.id("tabId"), browserUrl(o.text("url")))
        "browser.new_tab" -> BrowserCommand.NewTab(r, s, browser, lease, generation, browserUrl(o.text("url"), newTab = true))
        "browser.close" -> BrowserCommand.Close(r, s, browser, lease, generation, o.id("tabId"))
        "browser.viewport" -> BrowserCommand.Viewport(r, s, browser, lease, generation, o.id("tabId"), o.count("width", 80, 2048), o.count("height", 80, 2048), o.number("deviceScale", 0.5, 3.0), o.choice("orientation", "portrait", "landscape"))
        "browser.input" -> BrowserCommand.Input(r, s, browser, lease, generation, o.id("tabId"), o.count("viewportRevision"), o.count("sequence"), parseBrowserInput(o.obj("input")))
        else -> BrowserCommand.Detach(r, s, browser, lease, generation)
    }
}
private fun browserNumber(n: Double): Number = if (n.isFinite() && n % 1.0 == 0.0 && n >= Long.MIN_VALUE.toDouble() && n < Long.MAX_VALUE.toDouble()) n.toLong() else n
private fun encodeBrowserInput(input: BrowserInput): JsonObject = when (input) {
    is BrowserInput.Pointer -> Wire.objectOf("kind" to "pointer", "x" to browserNumber(input.x), "y" to browserNumber(input.y), "action" to input.action, "button" to input.button)
    is BrowserInput.Wheel -> Wire.objectOf("kind" to "wheel", "x" to browserNumber(input.x), "y" to browserNumber(input.y), "deltaX" to browserNumber(input.deltaX), "deltaY" to browserNumber(input.deltaY))
    is BrowserInput.Key -> Wire.objectOf(*buildList<Pair<String, Any?>> {
        add("kind" to "key"); add("action" to input.action); add("key" to input.key); add("code" to input.code)
        if (input.ctrl) add("ctrl" to true); if (input.alt) add("alt" to true); if (input.shift) add("shift" to true); if (input.meta) add("meta" to true)
    }.toTypedArray())
    is BrowserInput.Text -> Wire.objectOf("kind" to "text", "text" to input.text)
}
internal fun encodeBrowserCommand(command: BrowserCommand): JsonObject {
    val pairs = mutableListOf<Pair<String, Any?>>()
    fun browser(type: String, id: String) { pairs.add("type" to type); pairs.add("browserId" to id) }
    fun lease(type: String, id: String, leaseId: String, generation: Long) { browser(type, id); pairs.add("leaseId" to leaseId); pairs.add("generation" to generation) }
    when (command) {
        is BrowserCommand.Open -> pairs.add("type" to "browser.open")
        is BrowserCommand.Tabs -> browser("browser.tabs", command.browserId)
        is BrowserCommand.Participants -> browser("browser.participants", command.browserId)
        is BrowserCommand.Acquire -> { browser("browser.acquire", command.browserId); pairs.add("expectedGeneration" to command.expectedGeneration) }
        is BrowserCommand.Handoff -> { lease("browser.handoff", command.browserId, command.leaseId, command.generation); pairs.add("toOwnerId" to command.toOwnerId) }
        is BrowserCommand.Navigate -> { lease("browser.navigate", command.browserId, command.leaseId, command.generation); pairs.add("tabId" to command.tabId); pairs.add("url" to command.url) }
        is BrowserCommand.NewTab -> { lease("browser.new_tab", command.browserId, command.leaseId, command.generation); pairs.add("url" to command.url) }
        is BrowserCommand.Close -> { lease("browser.close", command.browserId, command.leaseId, command.generation); pairs.add("tabId" to command.tabId) }
        is BrowserCommand.Viewport -> { lease("browser.viewport", command.browserId, command.leaseId, command.generation); pairs.add("tabId" to command.tabId); pairs.add("width" to command.width); pairs.add("height" to command.height); pairs.add("deviceScale" to browserNumber(command.deviceScale)); pairs.add("orientation" to command.orientation) }
        is BrowserCommand.Input -> { lease("browser.input", command.browserId, command.leaseId, command.generation); pairs.add("tabId" to command.tabId); pairs.add("viewportRevision" to command.viewportRevision); pairs.add("sequence" to command.sequence); pairs.add("input" to encodeBrowserInput(command.input)) }
        is BrowserCommand.Detach -> lease("browser.detach", command.browserId, command.leaseId, command.generation)
    }
    pairs.add("requestId" to command.requestId); pairs.add("sessionId" to command.sessionId)
    return Wire.objectOf(*pairs.toTypedArray()).also { parseBrowserCommand(it) }
}
private fun parseBrowserOwner(o: JsonObject): BrowserOwner {
    Wire.keys(o, setOf("kind", "id"))
    return BrowserOwner(o.choice("kind", "device", "agent"), o.id("id"))
}
private fun parseBrowserTabs(o: JsonObject): List<BrowserTab> {
    val tabs = o.getValue("tabs").jsonArray
    require(tabs.size <= 16)
    return tabs.map {
        val t = it.jsonObject
        Wire.keys(t, setOf("id", "title", "url"))
        BrowserTab(t.id("id"), browserString(t.text("title"), 1024, true), browserString(t.text("url"), 4096, true))
    }
}
internal fun parseBrowserResult(o: JsonObject): BrowserResult {
    val kind = o.text("kind")
    val base = setOf("kind", "browserId", "generation")
    when (kind) {
        "browser.opened" -> Wire.keys(o, base + setOf("incarnation", "tabs", "viewportRevision"), setOf("owner"))
        "browser.lease" -> Wire.keys(o, base + setOf("leaseId", "owner", "viewportRevision"))
        "browser.busy" -> Wire.keys(o, base + "owner")
        "browser.tabs" -> Wire.keys(o, base + "tabs")
        "browser.participants" -> Wire.keys(o, base + "participants")
        "browser.viewport" -> Wire.keys(o, base + "viewportRevision")
        "browser.input.ack" -> Wire.keys(o, base + setOf("sequence", "status"))
        "browser.released" -> Wire.keys(o, base)
        else -> error("Unknown browser result")
    }
    val id = o.id("browserId"); val generation = o.count("generation")
    return when (kind) {
        "browser.opened" -> BrowserResult.Opened(id, generation, o.id("incarnation"), if ("owner" in o) parseBrowserOwner(o.obj("owner")) else null, parseBrowserTabs(o), o.count("viewportRevision"))
        "browser.lease" -> BrowserResult.Lease(id, generation, o.id("leaseId"), parseBrowserOwner(o.obj("owner")), o.count("viewportRevision"))
        "browser.busy" -> BrowserResult.Busy(id, generation, parseBrowserOwner(o.obj("owner")))
        "browser.tabs" -> BrowserResult.Tabs(id, generation, parseBrowserTabs(o))
        "browser.participants" -> {
            val participants = o.getValue("participants").jsonArray
            require(participants.size <= 64)
            BrowserResult.Participants(id, generation, participants.map { parseBrowserOwner(it.jsonObject) })
        }
        "browser.viewport" -> BrowserResult.Viewport(id, generation, o.count("viewportRevision"))
        "browser.input.ack" -> BrowserResult.InputAck(id, generation, o.count("sequence"), o.choice("status", "accepted", "uncertain"))
        else -> BrowserResult.Released(id, generation)
    }
}
