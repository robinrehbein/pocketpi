package de.joinnoah.pi.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class BrowserInputReceipt(val sequence: Long, val status: String)
public enum class BrowserNotice { BUSY, UNCERTAIN, DISCONNECTED, FAILED, FRAME_FAILED }
public data class BrowserImage(val jpeg: ByteArray, val width: Int, val height: Int, val cssWidth: Int, val cssHeight: Int, val deviceScale: Double, val scrollX: Double, val scrollY: Double, val frameId: Long, val viewportRevision: Long)
public data class BrowserView(val sessionId: String, val opening: Boolean = true, val browserId: String? = null, val incarnation: String? = null, val generation: Long = 0, val viewportRevision: Long = 0, val owner: BrowserOwner? = null, val participants: List<BrowserOwner> = emptyList(), val tabs: List<BrowserTab> = emptyList(), val selectedTabId: String? = null, val ownsControl: Boolean = false, val inputPending: Boolean = false, val receipt: BrowserInputReceipt? = null, val notice: BrowserNotice? = null, val image: BrowserImage? = null, val moving: Boolean = false, val documentEpoch: Long = 0)

/** Selection and connection identity travel with every asynchronous operation. */
internal class BrowserClient(
    private val scope: CoroutineScope,
    private val current: (String, Long) -> Boolean,
    private val request: suspend (Long, String, Array<out Pair<String, Any?>>) -> JsonObject,
    private val changed: (BrowserView?) -> Unit,
    private val deviceId: () -> String? = { null },
    private val connectionAlive: (Long) -> Boolean = { true },
    private val closeConnection: (Long) -> Unit = {},
    private val recoveryEnabled: () -> Boolean = { false },
) {
    var view: BrowserView? = null
        private set
    private var connection = 0L
    private var token = 0L
    private var lease: String? = null
    private val acquiringConnections = mutableSetOf<Long>()
    private var releaseInFlight = 0
    private var sequence = -1L
    private var confirmedNextSequence = 0L
    private var visible = true
    private var stream: String? = null
    private var assembler: BrowserFrameAssembler? = null
    private var watchToken = 0L
    private var watching = false
    private var watchDeadline: Job? = null
    private val early = linkedMapOf<String, JsonObject>()
    private var retiredStream: String? = null
    private var refreshPending = false
    private var refreshAgain = false
    private var metadataEpoch = 0L
    private var controlPending = false
    private var controlOperation = 0L
    private var recovery = false
    private var disconnectedView = false
    private var interruptedInput = false
    private data class Size(val tab: String, val width: Int, val height: Int, val scale: Double, val orientation: String)
    private var resizeActive: Size? = null
    private var resizeNext: Size? = null
    private var resizeApplied: Size? = null
    private fun set(value: BrowserView?) { view = value; changed(value) }
    private fun valid(t: Long, s: String, c: Long) = t == token && current(s, c)
    private suspend fun send(c: Long, command: BrowserCommand): JsonObject {
        val payload = encodeBrowserCommand(command)
        return request(c, payload.getValue("type").jsonPrimitive.content, payload.filterKeys { it != "type" && it != "requestId" }.map { it.key to it.value }.toTypedArray())
    }
    private suspend fun streamRequest(c: Long, type: String, vararg fields: Pair<String, Any?>): JsonObject = request(c, type, fields)
    fun open(session: String, c: Long): Boolean {
        if (!current(session, c)) return false
        if (isDisconnectedFor(session)) return resumeIfDisconnected(session, c)
        close(); connection = c; val t = ++token; visible = true; recovery = recoveryEnabled()
        set(BrowserView(session))
        requestOpen(session, c, t)
        return true
    }
    private fun requestOpen(session: String, c: Long, t: Long) {
        scope.launch {
            try {
                val result = parseBrowserResult(send(c, BrowserCommand.Open(Wire.random(), session, recovery))) as BrowserResult.Opened
                if (!valid(t, session, c)) return@launch
                set(BrowserView(session, false, result.browserId, result.incarnation, result.generation, result.viewportRevision, result.owner, tabs = result.tabs, selectedTabId = result.tabs.firstOrNull()?.id))
                watch()
            } catch (_: Exception) { if (valid(t, session, c)) set(view?.copy(opening = false, notice = BrowserNotice.FAILED)) }
        }
    }
    fun isDisconnectedFor(session: String?) = disconnectedView && session != null && view?.sessionId == session

    fun resumeIfDisconnected(session: String, c: Long): Boolean {
        if (!current(session, c) || !isDisconnectedFor(session)) return false
        val existing = view ?: return false
        disconnectedView = false
        connection = c
        recovery = recoveryEnabled()
        if (existing.browserId == null) {
            val t = ++token
            set(existing.copy(opening = true, notice = if (interruptedInput) BrowserNotice.UNCERTAIN else null))
            requestOpen(session, c, t)
            return true
        }
        set(existing.copy(notice = if (interruptedInput) BrowserNotice.UNCERTAIN else null))
        return refresh()
    }
    private fun dropStream() {
        watchToken++; watching = false; watchDeadline?.cancel(); watchDeadline = null; early.clear(); assembler?.close(); assembler = null
        val old = stream; stream = null; if (old != null) retiredStream = old
        val v = view; val c = connection
        if (old != null && v?.browserId != null && current(v.sessionId, c)) scope.launch {
            try { streamRequest(c, "browser.unwatch", "sessionId" to v.sessionId, "browserId" to v.browserId, "streamId" to old) } catch (_: Exception) { }
        }
    }
    private fun release(v: BrowserView, l: String, c: Long, t: Long, refreshAfter: Boolean = true) {
        val b = v.browserId ?: return
        if (!connectionAlive(c)) return
        releaseInFlight++
        scope.launch {
            try {
                val result = parseBrowserResult(send(c, BrowserCommand.Detach(Wire.random(), v.sessionId, b, l, v.generation)))
                check(result is BrowserResult.Released && result.browserId == b && result.generation > v.generation)
                if (valid(t, v.sessionId, c)) {
                    val current = view
                    if (current != null && result.generation >= current.generation) set(current.copy(generation = result.generation))
                }
            } catch (_: Exception) {
                if (connectionAlive(c)) closeConnection(c)
            } finally { releaseInFlight--; if (refreshAfter && valid(t, v.sessionId, c)) refresh() }
        }
    }
    private fun detach(refreshAfter: Boolean = true) {
        val v = view; val l = lease; lease = null
        if (l != null && v != null) release(v, l, connection, token, refreshAfter)
    }
    private fun frameFailed() {
        token++; resetPending(preserveControl = true); dropStream(); detach(refreshAfter = false)
        view?.let { set(it.copy(ownsControl = false, inputPending = false, image = null, notice = BrowserNotice.FRAME_FAILED)) }
    }
    fun close() { dropStream(); detach(); token++; resetPending(); disconnectedView = false; interruptedInput = false; set(null) }
    fun disconnected() {
        interruptedInput = interruptedInput || view?.inputPending == true
        disconnectedView = view != null
        dropStream(); lease = null; token++; resetPending()
        view?.let { set(it.copy(opening = false, ownsControl = false, inputPending = false, image = null, notice = BrowserNotice.DISCONNECTED)) }
    }
    private fun resetPending(preserveControl: Boolean = false) { if (!preserveControl) { controlOperation++; controlPending = false }; refreshPending = false; refreshAgain = false; resizeActive = null; resizeNext = null; resizeApplied = null }
    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { if (view?.opening != true) { token++; resetPending() }; dropStream(); detach(); view?.let { set(it.copy(ownsControl = false, inputPending = false, image = null)) } }
        else watch()
    }
    fun acquire(): Boolean {
        val v = view ?: return false; val b = v.browserId ?: return false
        if (!current(v.sessionId, connection) || lease != null || connection in acquiringConnections || controlPending || releaseInFlight > 0 || !visible) return false
        val t = token; val c = connection
        acquiringConnections.add(c)
        scope.launch {
            try {
                when (val r = parseBrowserResult(send(c, BrowserCommand.Acquire(Wire.random(), v.sessionId, b, v.generation)))) {
                    is BrowserResult.Lease -> {
                        val self = r.owner.kind == "device"
                        if (valid(t, v.sessionId, c) && r.browserId == b && self && visible && r.generation >= (view?.generation ?: 0)) {
                            interruptedInput = false
                            lease = r.leaseId; sequence = -1L; confirmedNextSequence = 0L; resizeApplied = null; dropStream(); set(view?.copy(generation = r.generation, viewportRevision = r.viewportRevision, owner = r.owner, ownsControl = true, notice = null, image = null)); watch()
                        } else if (self && r.browserId == b && connectionAlive(c)) {
                            release(v.copy(generation = r.generation), r.leaseId, c, t)
                        }
                    }
                    is BrowserResult.Busy -> if (valid(t, v.sessionId, c) && r.browserId == b && r.generation >= (view?.generation ?: 0)) set(view?.copy(generation = r.generation, owner = r.owner, notice = BrowserNotice.BUSY))
                    else -> error("Unexpected result")
                }
            } catch (_: Exception) {
                if (valid(t, v.sessionId, c)) uncertain()
                else if (connectionAlive(c)) closeConnection(c)
            } finally { acquiringConnections.remove(c) }
        }; return true
    }
    private fun uncertain() {
        val v = view; val l = lease; val c = connection; val t = token
        lease = null; dropStream(); v?.let { set(it.copy(ownsControl = false, inputPending = false, notice = BrowserNotice.UNCERTAIN, image = null)) }
        if (v != null && l != null) release(v, l, c, t)
        else if (connectionAlive(c)) closeConnection(c)
    }
    private fun mutate(build: (BrowserView, String, String) -> BrowserCommand, done: () -> Unit = {}, beforeDispatch: () -> Unit = {}, result: (BrowserResult) -> Unit = {}): Boolean {
        val v = view ?: return false; val b = v.browserId ?: return false; val l = lease ?: return false
        if (!current(v.sessionId, connection) || controlPending) return false
        val cmd = try { build(v, b, l).also { encodeBrowserCommand(it) } } catch (_: Exception) { return false }
        val t = token; val c = connection
        controlPending = true; val operation = ++controlOperation
        beforeDispatch()
        scope.launch { try {
            val r = parseBrowserResult(send(c, cmd)); if (!valid(t, v.sessionId, c)) return@launch
            if (r.browserId != b || r.generation < v.generation) { uncertain(); return@launch }; result(r)
        } catch (_: Exception) { if (valid(t, v.sessionId, c)) uncertain() }
          finally { if (operation == controlOperation) { controlPending = false; if (t == token) done() else { resizeActive = null; resizeNext = null } } } }
        return true
    }
    fun handoff(owner: String): Boolean {
        val ok = mutate({ v,b,l -> BrowserCommand.Handoff(Wire.random(), v.sessionId,b,l,v.generation,owner) }) { refresh() }
        if (ok) { lease = null; dropStream(); view?.let { set(it.copy(ownsControl = false, image = null)) } }
        return ok
    }
    private fun stopPageStream() { metadataEpoch++; dropStream(); set(view?.copy(image = null)) }
    fun newTab(url: String): Boolean {
        val before = view?.tabs?.map { it.id }?.toSet() ?: return false
        return mutate({ v,b,l -> BrowserCommand.NewTab(Wire.random(),v.sessionId,b,l,v.generation,url) }, beforeDispatch = ::stopPageStream) { r ->
            if (r is BrowserResult.Tabs) {
                val added = r.tabs.firstOrNull { it.id !in before }
                if (added != null) { resizeApplied = null; set(view?.copy(tabs = r.tabs, selectedTabId = added.id)) }
            }
            refresh()
        }
    }
    fun navigate(url: String) = mutate({ v,b,l -> BrowserCommand.Navigate(Wire.random(),v.sessionId,b,l,v.generation,requireNotNull(v.selectedTabId),url) }, beforeDispatch = ::stopPageStream) { refresh() }
    fun closeTab(id: String) = mutate({ v,b,l -> BrowserCommand.Close(Wire.random(),v.sessionId,b,l,v.generation,id) }, beforeDispatch = { if (view?.selectedTabId == id) stopPageStream() }) { refresh() }
    fun selectTab(id: String) { val v = view ?: return; if (v.tabs.none { it.id == id } || id == v.selectedTabId) return; resizeApplied = null; dropStream(); set(v.copy(selectedTabId = id, image = null)); watch() }
    fun resize(width: Int, height: Int, scale: Double, orientation: String): Boolean {
        val size = Size(view?.selectedTabId ?: return false, width, height, scale, orientation)
        if (size == resizeActive || (resizeActive == null && size == resizeApplied)) return true
        if (resizeActive != null) { resizeNext = size; return true }
        if (controlPending) return false
        resizeActive = size
        val admitted = mutate({ v,b,l -> BrowserCommand.Viewport(Wire.random(),v.sessionId,b,l,v.generation,requireNotNull(v.selectedTabId),width.toLong(),height.toLong(),scale,orientation) }, done = {
            resizeActive = null
            val next = resizeNext; resizeNext = null
            if (next != null && next != resizeApplied) resize(next.width, next.height, next.scale, next.orientation)
        }) { r ->
            if (r is BrowserResult.Viewport && r.generation == view?.generation && r.viewportRevision >= (view?.viewportRevision ?: 0)) {
                dropStream(); set(view?.copy(viewportRevision = r.viewportRevision, image = null))
                if (view?.selectedTabId == size.tab) { resizeApplied = size; watch() }
            } else uncertain()
        }
        if (!admitted) resizeActive = null
        return admitted
    }
    fun input(input: BrowserInput): Boolean {
        val v = view ?: return false; if (v.inputPending || sequence >= 9007199254740991L) return false
        if (v.moving && input !is BrowserInput.Wheel && !(input is BrowserInput.Pointer && input.action == "up") && !(input is BrowserInput.Key && input.action == "up")) return false
        val next = sequence + 1
        sequence = next
        set(v.copy(inputPending = true, receipt = null))
        val ok = mutate({ a,b,l -> BrowserCommand.Input(Wire.random(),a.sessionId,b,l,a.generation,requireNotNull(a.selectedTabId),a.viewportRevision,next,input) }) { r ->
            if (r is BrowserResult.InputAck && r.generation == v.generation && view?.generation == v.generation && r.sequence == next && r.status == "accepted") { confirmedNextSequence = maxOf(confirmedNextSequence, next + 1); set(view?.copy(inputPending = false, receipt = BrowserInputReceipt(next,r.status))) } else uncertain()
        }
        if (!ok) { sequence--; set(view?.copy(inputPending = false)) }; return ok
    }
    fun refresh(): Boolean {
        val v = view ?: return false; val b = v.browserId ?: return false; if (!current(v.sessionId,connection)) return false
        if (refreshPending) { refreshAgain = true; return true }
        refreshPending = true
        val t = token; val c = connection; val metadata = metadataEpoch
        scope.launch { try {
            val opened = parseBrowserResult(send(c, BrowserCommand.Open(Wire.random(),v.sessionId,recovery))) as BrowserResult.Opened
            val participants = parseBrowserResult(send(c, BrowserCommand.Participants(Wire.random(),v.sessionId,b))) as BrowserResult.Participants
            if (!valid(t,v.sessionId,c) || metadata != metadataEpoch) return@launch
            val current = view ?: return@launch
            if (opened.browserId != b || opened.incarnation != current.incarnation || participants.browserId != b) { frameFailed(); return@launch }
            if (opened.generation != participants.generation || opened.generation < current.generation || opened.viewportRevision < current.viewportRevision) return@launch
            if (lease != null && opened.generation == current.generation && opened.viewportRevision > current.viewportRevision) return@launch
            val selected = current.selectedTabId?.takeIf { id -> opened.tabs.any { it.id == id } } ?: opened.tabs.firstOrNull()?.id
            val changed = opened.generation != current.generation || opened.viewportRevision != current.viewportRevision || selected != current.selectedTabId
            if (opened.generation != current.generation) lease = null
            if (changed) { resizeApplied = null; dropStream() }
            set(current.copy(generation = opened.generation, viewportRevision = opened.viewportRevision, owner = opened.owner, tabs = opened.tabs, participants = participants.participants, selectedTabId = selected, ownsControl = lease != null, image = if (changed) null else current.image)); watch()
        } catch (_: Exception) { if (valid(t,v.sessionId,c)) set(view?.copy(notice = BrowserNotice.FAILED)) }
          finally { if (t == token) { refreshPending = false; val again = refreshAgain; refreshAgain = false; if (again) refresh() } } }; return true
    }
    private fun watch() {
        val v = view ?: return; val b = v.browserId ?: return; val tab = v.selectedTabId ?: return
        if (v.viewportRevision == 0L || !visible || stream != null || watching || !current(v.sessionId,connection)) return
        watching = true; val w = ++watchToken; val t = token; val c = connection
        scope.launch { try {
            val fields = mutableListOf<Pair<String, Any?>>("sessionId" to v.sessionId,"browserId" to b,"tabId" to tab,"generation" to v.generation,"viewportRevision" to v.viewportRevision)
            if (recovery) fields.add("recovery" to true)
            val r = streamRequest(c,"browser.watch", *fields.toTypedArray())
            validateBrowserStreamResult(r)
            val id = r.getValue("streamId").jsonPrimitive.content
            if (!valid(t,v.sessionId,c) || w != watchToken || r.getValue("kind").jsonPrimitive.content != "browser.watching" || r.getValue("browserId").jsonPrimitive.content != b || r.getValue("generation").jsonPrimitive.content.toLong() != v.generation || r.getValue("viewportRevision").jsonPrimitive.content.toLong() != v.viewportRevision) {
                if (connectionAlive(c)) try { streamRequest(c,"browser.unwatch","sessionId" to v.sessionId,"browserId" to b,"streamId" to id) } catch (_: Exception) { }; return@launch
            }
            stream = id; assembler = BrowserFrameAssembler(b,tab,id,v.generation,v.viewportRevision,System::currentTimeMillis,scope); watching = false
            armWatchDeadline(id, t, w, v.sessionId, c)
            val buffered = early[id]; early.clear(); if (buffered != null) frame(buffered)
        } catch (_: Exception) { if (valid(t,v.sessionId,c) && w == watchToken) { frameFailed() } } }
    }
    private fun armWatchDeadline(id: String, t: Long, w: Long, session: String, c: Long) {
        watchDeadline?.cancel()
        watchDeadline = scope.launch {
            delay(5500)
            if (stream == id && watchToken == w && valid(t, session, c)) {
                frameFailed()
            }
        }
    }
    fun frame(payload: JsonObject) {
        val v = view ?: return
        if (!recovery && payload["kind"]?.jsonPrimitive?.content in setOf("browser.document", "browser.frame.waiting")) return
        if (recovery && payload["kind"]?.jsonPrimitive?.content == "browser.document") { document(payload); return }
        if (recovery && payload["kind"]?.jsonPrimitive?.content == "browser.frame.waiting") {
            val waiting = try { parseBrowserWaiting(payload) } catch (_: Exception) { return }
            val id = stream ?: return
            if (visible && current(v.sessionId, connection) && waiting.browserId == v.browserId && waiting.tabId == v.selectedTabId && waiting.streamId == id && waiting.generation == v.generation && waiting.viewportRevision == v.viewportRevision) {
                set(v.copy(moving = true)); armWatchDeadline(id, token, watchToken, v.sessionId, connection)
            }
            return
        }
        if (watching && assembler == null) {
            val chunk = try { parseBrowserFrame(payload) } catch (_: Exception) { return }
            if (chunk.browserId == v.browserId && chunk.tabId == v.selectedTabId && chunk.generation == v.generation && chunk.viewportRevision == v.viewportRevision && chunk.chunkIndex == 0 && chunk.streamId != retiredStream && chunk.streamId !in early && early.size < 2) early[chunk.streamId] = payload
            return
        }
        val a = assembler ?: return; val id = stream ?: return; val c = connection; val t = token; val w = watchToken
        val chunk = try { parseBrowserFrame(payload) } catch (_: Exception) { frameFailed(); return }
        if (chunk.browserId != v.browserId || chunk.tabId != v.selectedTabId || chunk.streamId != id || chunk.generation != v.generation || chunk.viewportRevision != v.viewportRevision) return
        try {
            val receipt = a.accept(payload)
            armWatchDeadline(id, t, w, v.sessionId, c)
            scope.launch { try { streamRequest(c,"browser.frame.ack","sessionId" to v.sessionId,"browserId" to v.browserId,"streamId" to id,"frameId" to receipt.frameId,"chunkIndex" to receipt.chunkIndex) } catch (_: Exception) { if (stream == id && w == watchToken && valid(t, v.sessionId, c)) { frameFailed() } } }
            receipt.frame?.let { f -> val m = f.metadata; set(view?.copy(moving = false, notice = view?.notice?.takeUnless { it == BrowserNotice.FRAME_FAILED }, image = BrowserImage(f.jpeg,m.width,m.height,m.cssWidth,m.cssHeight,m.deviceScale,m.scrollX,m.scrollY,f.frameId,v.viewportRevision))) }
        } catch (_: Exception) { frameFailed() }
    }
    private fun document(payload: JsonObject) {
        val event = try { parseBrowserDocument(payload) } catch (_: Exception) { return }
        val v = view ?: return
        if (!current(v.sessionId, connection) || event.sessionId != v.sessionId || event.browserId != v.browserId || event.incarnation != v.incarnation || event.tabId != v.selectedTabId || event.generation != v.generation || event.revision <= v.viewportRevision) return
        if (event.fromRevision > v.viewportRevision) {
            token++; metadataEpoch++; refreshPending = false; refreshAgain = false; resizeApplied = null; resizeNext = null
            dropStream(); detach(refreshAfter = false)
            set(v.copy(ownsControl = false, inputPending = false, receipt = null, image = null, moving = false, documentEpoch = v.documentEpoch + 1, notice = BrowserNotice.UNCERTAIN))
            return
        }
        val knownLease = lease
        val continuity = knownLease != null && event.leaseId == knownLease && event.nextSequence != null && event.nextSequence >= confirmedNextSequence
        token++; metadataEpoch++; refreshPending = false; refreshAgain = false; resizeApplied = null; resizeNext = null
        dropStream()
        if (continuity) { val next = requireNotNull(event.nextSequence); confirmedNextSequence = next; sequence = next - 1 } else detach(refreshAfter = false)
        set(v.copy(viewportRevision = event.revision, tabs = event.tabs, owner = event.owner, ownsControl = continuity, inputPending = false, receipt = null, image = null, moving = false, documentEpoch = v.documentEpoch + 1, notice = if (v.inputPending || knownLease != null && !continuity) BrowserNotice.UNCERTAIN else null))
        watch()
    }
}
