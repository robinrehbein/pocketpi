package de.joinnoah.pi.remote

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class EmulatorImage(val png: ByteArray, val width: Long, val height: Long, val displayWidth: Long, val displayHeight: Long, val rotation: Long, val revision: Long, val frameId: Long)
public data class EmulatorView(val sessionId: String, val opening: Boolean = true, val profiles: List<EmulatorProfileInfo> = emptyList(), val profileId: String? = null, val label: String? = null, val emulatorId: String? = null, val incarnation: String? = null, val generation: Long = 0, val geometry: EmulatorGeometry? = null, val owner: EmulatorOwner? = null, val participants: List<EmulatorOwner> = emptyList(), val ownsControl: Boolean = false, val inputPending: Boolean = false, val image: EmulatorImage? = null, val notice: String? = null)

internal class EmulatorClient(private val scope: CoroutineScope, private val current: (String, Long) -> Boolean,
    private val request: suspend (Long, String, Array<out Pair<String, Any?>>) -> JsonObject,
    private val changed: (EmulatorView?) -> Unit, private val deviceId: () -> String? = { null },
    private val connectionAlive: (Long) -> Boolean = { true }, private val closeConnection: (Long) -> Unit = {},
    private val recoveryEnabled: () -> Boolean = { false }) {
    // Negotiated per open: a recovery watch keeps its deadline alive with emulator.frame.waiting.
    private var recovery = false
    var view: EmulatorView? = null; private set
    private var connection = 0L; private var token = 0L; private var lease: String? = null; private var sequence = 0L
    private var visible = true; private var disconnected = false; private var pending = false
    private var deadline: Job? = null
    private var stream: String? = null; private var assembler: EmulatorFrameAssembler? = null; private var watchToken = 0L; private var watching = false
    private val early = linkedMapOf<String, JsonObject>(); private var retiredStream: String? = null
    private fun set(v: EmulatorView?) { view = v; changed(v) }
    private fun valid(t: Long, s: String, c: Long) = token == t && current(s, c)
    private suspend fun send(c: Long, command: EmulatorCommand): EmulatorResult {
        val p = encodeEmulatorCommand(command)
        return parseEmulatorResult(request(c, p.getValue("type").jsonPrimitive.content, p.filterKeys { it != "type" && it != "requestId" }.map { it.key to it.value }.toTypedArray()))
    }
    fun open(session: String, c: Long): Boolean {
        if (!current(session, c)) return false
        close(); connection = c; visible = true; disconnected = false; recovery = recoveryEnabled(); val t = ++token
        set(EmulatorView(session))
        scope.launch { try {
            val r = send(c, EmulatorCommand.List(Wire.random(), session)) as EmulatorResult.Profiles
            if (valid(t, session, c)) set(view?.copy(opening = false, profiles = r.profiles))
        } catch (_: Exception) { if (valid(t, session, c)) set(view?.copy(opening = false, notice = "failed")) } }
        return true
    }
    fun selectProfile(profile: String): Boolean {
        val v = view ?: return false
        if (v.opening || v.emulatorId != null || v.profiles.none { it.id == profile } || !current(v.sessionId, connection)) return false
        val t = ++token; val c = connection; set(v.copy(opening = true, profileId = profile, notice = null))
        scope.launch { try {
            val r = send(c, EmulatorCommand.Open(Wire.random(), v.sessionId, profile)) as EmulatorResult.Opened
            if (!valid(t, v.sessionId, c)) {
                if (connectionAlive(c)) {
                    if (view?.sessionId == v.sessionId && view?.profileId == profile) closeConnection(c)
                    else try { send(c, EmulatorCommand.Detach(Wire.random(), v.sessionId, r.emulatorId)) } catch (_: Exception) { closeConnection(c) }
                }
                return@launch
            }
            check(r.profileId == profile)
            set(view?.copy(opening = false, emulatorId = r.emulatorId, label = r.label, incarnation = r.incarnation, generation = r.generation, geometry = r.geometry, owner = r.owner))
            refresh(); watch()
        } catch (_: Exception) { if (valid(t, v.sessionId, c)) set(view?.copy(opening = false, notice = "failed")) } }
        return true
    }
    private fun dropStream() {
        watchToken++; watching = false; deadline?.cancel(); deadline = null; early.clear(); assembler?.close(); assembler = null
        val id = stream; stream = null; if (id != null) retiredStream = id
        val v = view; val c = connection
        if (id != null && v?.emulatorId != null && current(v.sessionId, c)) scope.launch { try { send(c, EmulatorCommand.Unwatch(Wire.random(), v.sessionId, v.emulatorId, id)) } catch (_: Exception) {} }
    }
    fun close() {
        val v = view; val c = connection; dropStream(); token++; lease = null; pending = false; disconnected = false; set(null)
        if (v?.emulatorId != null && connectionAlive(c)) scope.launch { try { send(c, EmulatorCommand.Detach(Wire.random(), v.sessionId, v.emulatorId)) } catch (_: Exception) { if (connectionAlive(c)) closeConnection(c) } }
    }
    fun disconnected() { disconnected = view != null; dropStream(); token++; lease = null; pending = false; view?.let { set(it.copy(opening = false, ownsControl = false, inputPending = false, image = null, notice = if (it.inputPending) "uncertain" else "disconnected")) } }
    fun isDisconnectedFor(session: String?) = disconnected && session != null && view?.sessionId == session
    fun resumeIfDisconnected(session: String, c: Long): Boolean {
        if (!isDisconnectedFor(session) || !current(session, c)) return false
        disconnected = false; connection = c; recovery = recoveryEnabled(); val t = ++token; val v = view ?: return false
        val id = v.emulatorId ?: return open(session, c)
        val profile = v.profileId ?: return false
        set(v.copy(opening = true, ownsControl = false, inputPending = false, image = null))
        scope.launch { try {
            val r = send(c, EmulatorCommand.Open(Wire.random(), session, profile)) as EmulatorResult.Opened
            if (!valid(t, session, c)) { if (connectionAlive(c)) closeConnection(c); return@launch }
            check(r.profileId == profile)
            if (r.emulatorId == id && r.incarnation == v.incarnation) check(r.generation >= v.generation)
            set(view?.copy(opening = false, emulatorId = r.emulatorId, incarnation = r.incarnation, label = r.label, generation = r.generation, geometry = r.geometry, owner = r.owner, ownsControl = false, image = null))
            refresh(); watch()
        } catch (_: Exception) { if (valid(t, session, c)) fail("resume") } }
        return true
    }
    fun setVisible(value: Boolean) { visible = value; if (!value) { dropStream(); view?.let { set(it.copy(image = null)) } } else watch() }
    // Closes the whole connection: the Host then releases this device's lease. [reason] is logged.
    private fun fail(reason: String) { Diagnostics.record(Diagnostics.Kind.FAILURE, "emulator.closed", outcome = reason); lease = null; pending = false; dropStream(); view?.let { set(it.copy(ownsControl = false, inputPending = false, image = null, notice = "uncertain")) }; if (connectionAlive(connection)) closeConnection(connection) }
    fun refresh(): Boolean {
        val v = view ?: return false; val id = v.emulatorId ?: return false; val c = connection; val t = token
        if (!current(v.sessionId, c)) return false
        scope.launch { try {
            val r = send(c, EmulatorCommand.Status(Wire.random(), v.sessionId, id)) as EmulatorResult.Status
            val p = send(c, EmulatorCommand.Participants(Wire.random(), v.sessionId, id)) as EmulatorResult.Participants
            if (!valid(t, v.sessionId, c)) return@launch
            val old = view ?: return@launch
            check(r.emulatorId == id && r.profileId == old.profileId && r.incarnation == old.incarnation && p.emulatorId == id)
            if (r.generation < old.generation || r.generation != p.generation) return@launch
            val changedGeneration = r.generation != old.generation || r.geometry != old.geometry
            if (changedGeneration) { lease = null; dropStream() }
            set(old.copy(generation = r.generation, geometry = r.geometry, owner = r.owner, participants = p.participants, ownsControl = lease != null, image = if (changedGeneration) null else old.image)); watch()
        } catch (_: Exception) { if (valid(t, v.sessionId, c)) fail("refresh") } }
        return true
    }
    fun acquire(): Boolean {
        val v = view ?: return false; val id = v.emulatorId ?: return false; val c = connection; val t = token
        if (pending || lease != null || !visible || !current(v.sessionId, c)) return false
        pending = true
        scope.launch { try { when (val r = send(c, EmulatorCommand.Acquire(Wire.random(), v.sessionId, id, v.generation))) {
            is EmulatorResult.Lease -> {
                if (!valid(t, v.sessionId, c) || !visible || r.emulatorId != id || r.generation < (view?.generation ?: 0) || r.owner.kind != "device") {
                    if (connectionAlive(c)) try { send(c, EmulatorCommand.Release(Wire.random(), v.sessionId, id, r.leaseId, r.generation)) } catch (_: Exception) { closeConnection(c) }; return@launch
                }
                lease = r.leaseId; sequence = r.nextSequence; dropStream(); set(view?.copy(generation = r.generation, geometry = r.geometry, owner = r.owner, ownsControl = true, image = null, notice = null)); watch()
            }
            is EmulatorResult.Busy -> if (valid(t, v.sessionId, c) && r.emulatorId == id && r.generation >= (view?.generation ?: 0)) { dropStream(); set(view?.copy(generation = r.generation, owner = r.owner, image = null, notice = "busy")); watch() }
            else -> error("unexpected")
        } } catch (_: Exception) { if (valid(t, v.sessionId, c)) fail("acquire") } finally { if (valid(t, v.sessionId, c)) pending = false } }
        return true
    }
    fun input(input: EmulatorInput): Boolean {
        val v = view ?: return false; val id = v.emulatorId ?: return false; val l = lease ?: return false; val g = v.geometry ?: return false
        if (v.image?.revision != g.revision || pending || !visible || !current(v.sessionId, connection)) return false
        val c = connection; val t = token; val seq = sequence; pending = true; set(v.copy(inputPending = true))
        scope.launch { try {
            val r = send(c, EmulatorCommand.Input(Wire.random(), v.sessionId, id, l, v.generation, g.revision, seq, input)) as EmulatorResult.InputAck
            if (!valid(t, v.sessionId, c)) return@launch
            check(r.emulatorId == id && r.generation == v.generation && r.sequence == seq && r.status == "accepted")
            sequence++; set(view?.copy(inputPending = false))
        } catch (_: Exception) { if (valid(t, v.sessionId, c)) fail("input") } finally { if (valid(t, v.sessionId, c)) pending = false } }
        return true
    }
    fun handoff(owner: String): Boolean {
        val v = view ?: return false
        if (v.participants.none { it.id == owner && it != v.owner }) return false
        return control { current,id,l -> EmulatorCommand.Handoff(Wire.random(),current.sessionId,id,l,current.generation,owner) }
    }
    fun stop(): Boolean = control { v,id,l -> EmulatorCommand.Stop(Wire.random(),v.sessionId,id,l,v.generation) }
    private fun control(command: (EmulatorView,String,String)->EmulatorCommand): Boolean {
        val v = view ?: return false; val id = v.emulatorId ?: return false; val l = lease ?: return false; val c = connection; val t = token
        if (pending || !current(v.sessionId,c)) return false
        pending = true; lease = null; dropStream(); set(v.copy(ownsControl = false, image = null))
        scope.launch { try {
            val r = send(c, command(v,id,l)); if (!valid(t,v.sessionId,c)) return@launch
            when (r) {
                is EmulatorResult.Stopped -> {
                    check(r.emulatorId == id && r.generation >= v.generation)
                    dropStream(); token++; lease = null; pending = false; disconnected = false; set(null)
                }
                is EmulatorResult.Released -> { check(r.emulatorId == id && r.generation >= v.generation); refresh() }
                else -> error("unexpected")
            }
        } catch (_: Exception) { if (valid(t,v.sessionId,c)) fail("control") } finally { if (valid(t,v.sessionId,c)) pending = false } }
        return true
    }
    private fun watch() {
        val v = view ?: return; val id = v.emulatorId ?: return; val inc = v.incarnation ?: return; val g = v.geometry ?: return
        if (!visible || stream != null || watching || !current(v.sessionId,connection)) return
        val c = connection; val t = token; val w = ++watchToken; watching = true
        scope.launch { try {
            val r = send(c, EmulatorCommand.Watch(Wire.random(),v.sessionId,id,v.generation,recovery)) as EmulatorResult.Watching
            if (!valid(t,v.sessionId,c) || w != watchToken || r.emulatorId != id || r.generation != v.generation) {
                if (connectionAlive(c)) try { send(c,EmulatorCommand.Unwatch(Wire.random(),v.sessionId,id,r.streamId)) } catch (_: Exception) {}; return@launch
            }
            stream = r.streamId; assembler = EmulatorFrameAssembler(id,inc,r.streamId,v.generation,g.revision,System::currentTimeMillis,scope); watching = false
            armDeadline(r.streamId, t, w, v.sessionId, c)
            val buffered = early[r.streamId]; early.clear(); if (buffered != null) frame(buffered)
        } catch (_: Exception) { if (valid(t,v.sessionId,c) && w == watchToken) fail("watch") } }
    }
    private fun armDeadline(id: String, t: Long, w: Long, session: String, c: Long) {
        deadline?.cancel()
        deadline = scope.launch { delay(5500); if (stream == id && watchToken == w && valid(t, session, c)) fail("frame_deadline") }
    }
    fun frame(payload: JsonObject) {
        val v = view ?: return
        if (payload.optionalText("kind") == "emulator.frame.waiting") {
            // The Host's capture is slow but the stream is alive: wait one more deadline. The Host
            // ends a capture that stays slow, and the deadline then fails as before.
            if (!recovery) return
            val waiting = try { parseEmulatorFrameWaiting(payload) } catch (_: Exception) { return }
            val id = stream ?: return
            if (current(v.sessionId, connection) && waiting.emulatorId == v.emulatorId && waiting.incarnation == v.incarnation && waiting.streamId == id && waiting.generation == v.generation)
                armDeadline(id, token, watchToken, v.sessionId, connection)
            return
        }
        val chunk = try { parseEmulatorFrame(payload) } catch (_: Exception) {
            if (try { payload.optionalText("emulatorId") == v.emulatorId && payload.optionalText("streamId") == stream && stream != null } catch (_: Exception) { false }) fail("frame_parse")
            return
        }
        if (chunk.emulatorId != v.emulatorId || chunk.incarnation != v.incarnation || chunk.generation != v.generation || chunk.geometryRevision != v.geometry?.revision) return
        val geometry = v.geometry ?: return
        if (chunk.displayWidth != geometry.width || chunk.displayHeight != geometry.height || chunk.rotation != geometry.rotation) { fail("frame_geometry"); return }
        if (watching && assembler == null) { if (chunk.chunkIndex == 0L && chunk.streamId != retiredStream && early.size < 2) early[chunk.streamId] = payload; return }
        val a = assembler ?: return; val id = stream ?: return; if (chunk.streamId != id) return
        val c = connection; val t = token; val w = watchToken
        try { val receipt = a.accept(payload)
            armDeadline(id, t, w, v.sessionId, c)
            receipt.frame?.let { frame ->
                val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: error("Invalid PNG")
                try { check(bitmap.width.toLong() == frame.metadata.width && bitmap.height.toLong() == frame.metadata.height) } finally { bitmap.recycle() }
            }
            scope.launch { try {
                val ack = encodeEmulatorCommand(EmulatorCommand.FrameAck(Wire.random(),v.sessionId,requireNotNull(v.emulatorId),id,receipt.frameId,receipt.chunkIndex))
                request(c, "emulator.frame.ack", ack.filterKeys { it != "type" && it != "requestId" }.map { it.key to it.value }.toTypedArray())
                if (valid(t,v.sessionId,c) && w == watchToken && stream == id) receipt.frame?.let { f -> val m = f.metadata; if (f.frameId > (view?.image?.frameId ?: 0)) set(view?.copy(image = EmulatorImage(f.png,m.width,m.height,m.displayWidth,m.displayHeight,m.rotation,m.geometryRevision,f.frameId))) }
            } catch (_: Exception) { if (valid(t,v.sessionId,c) && w == watchToken) fail("frame_ack") } }
        } catch (_: Exception) { fail("frame_accept") }
    }
    fun state(payload: JsonObject) {
        val e = try { parseEmulatorState(payload) } catch (_: Exception) { return }; val v = view ?: return
        if (!current(v.sessionId,connection) || e.sessionId != v.sessionId || e.emulatorId != v.emulatorId || e.profileId != v.profileId || e.incarnation != v.incarnation || e.generation < v.generation || e.geometry.revision < (v.geometry?.revision ?: 0)) return
        if (e.generation != v.generation || e.geometry != v.geometry) {
            val wasInputPending = v.inputPending
            if (wasInputPending) token++
            val handed = !pending && e.control != null && e.owner?.kind == "device"
            lease = if (handed) e.control?.leaseId else null
            if (handed) sequence = requireNotNull(e.control).nextSequence
            if (wasInputPending) pending = false
            dropStream()
            set(v.copy(generation = e.generation, geometry = e.geometry, owner = e.owner, ownsControl = handed, inputPending = false, image = null, notice = if (wasInputPending) "uncertain" else null)); watch()
        } else if (!pending && lease == null && e.control != null && e.owner?.kind == "device") {
            lease = e.control.leaseId; sequence = e.control.nextSequence; dropStream()
            set(v.copy(owner = e.owner, ownsControl = true, image = null)); watch()
        } else set(v.copy(owner = e.owner))
    }
}
