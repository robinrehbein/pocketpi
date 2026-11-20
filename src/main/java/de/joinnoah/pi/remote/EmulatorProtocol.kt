package de.joinnoah.pi.remote

import java.text.Normalizer
import kotlinx.serialization.json.*

internal const val SESSION_EMULATOR_CAPABILITY = "session.emulator.v1"
/** A recovery watch gets emulator.frame.waiting while the Host's next capture is slow. */
internal const val SESSION_EMULATOR_RECOVERY_CAPABILITY = "session.emulator.recovery.v1"
/** Raw touch, text, long press, the extra keys and `emulator.rotate`; only on the `capabilities.v4:` route. */
internal const val SESSION_EMULATOR_CONTROLS_CAPABILITY = "session.emulator.controls.v1"
/**
 * Unicode text, the editing keys ([EMULATOR_KEYBOARD_KEYS]) and modifier chords, typed through the
 * emulator's gRPC keyboard. Only on the `capabilities.v4:` route, and only next to
 * [SESSION_EMULATOR_CONTROLS_CAPABILITY]; without it the host types text over ADB as ASCII.
 */
internal const val SESSION_EMULATOR_KEYBOARD_CAPABILITY = "session.emulator.keyboard.v1"
internal const val EMULATOR_FRAME_CHUNK_BYTES = 49152
internal const val EMULATOR_FRAME_MAX_BYTES = 1048576
private const val EMULATOR_MAX_SAFE = 9007199254740991L
public data class EmulatorProfileInfo(val id: String, val label: String)
public data class EmulatorGeometry(val width: Long, val height: Long, val rotation: Long, val revision: Long)
public data class EmulatorOwner(val kind: String, val id: String)
public data class EmulatorControl(val leaseId: String, val nextSequence: Long)
public sealed interface EmulatorInput {
    /**
     * [modifiers] (canonical order, see [EMULATOR_MODIFIERS]) and the keys outside
     * [EMULATOR_BASE_KEYS] and [EMULATOR_CONTROL_KEYS] need [SESSION_EMULATOR_KEYBOARD_CAPABILITY].
     */
    data class Key(val key: String, val modifiers: kotlin.collections.List<String> = emptyList()) : EmulatorInput
    data class Tap(val x: Long, val y: Long) : EmulatorInput
    data class Swipe(val x: Long, val y: Long, val endX: Long, val endY: Long, val durationMs: Long) : EmulatorInput
    /** Needs [SESSION_EMULATOR_CONTROLS_CAPABILITY], as do [Text], [Touch] and the keys in [EMULATOR_CONTROL_KEYS]. */
    data class LongPress(val x: Long, val y: Long, val durationMs: Long) : EmulatorInput
    /**
     * Printable ASCII ([emulatorTextValid]); with [SESSION_EMULATOR_KEYBOARD_CAPABILITY] any NFC
     * text that [emulatorKeyboardTextValid] accepts.
     */
    data class Text(val text: String) : EmulatorInput
    /**
     * Every pointer down or lifting in this event, in the coordinates of the frame with the
     * command's geometry revision. Pressure 0 lifts a pointer; later events omit it.
     */
    data class Touch(val touches: kotlin.collections.List<EmulatorTouchPoint>) : EmulatorInput
}
public data class EmulatorTouchPoint(val id: Int, val x: Long, val y: Long, val pressure: Int)
internal val EMULATOR_BASE_KEYS = setOf("Home","Back","Enter","Tab","Escape","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","Backspace")
/** The hardware keys of [SESSION_EMULATOR_CONTROLS_CAPABILITY]: Recents, Power and the volume. */
internal val EMULATOR_CONTROL_KEYS = setOf("AppSwitch","Power","VolumeUp","VolumeDown")
/** The editing keys of [SESSION_EMULATOR_KEYBOARD_CAPABILITY]. */
internal val EMULATOR_KEYBOARD_KEYS = setOf("Delete","MoveHome","MoveEnd","PageUp","PageDown")
/** Letter and digit keys: only in a chord with Control, Alt or Meta, as plain ones would be a second text path. */
internal val EMULATOR_CHARACTER_KEYS = ('A'..'Z').map { "Key$it" }.toSet() + ('0'..'9').map { "Digit$it" }
/** The keys a chord may press; the Android buttons (Home, Back, Recents, Power, volume) take no modifiers. */
internal val EMULATOR_CHORD_KEYS = setOf("Enter","Tab","Escape","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","Backspace") + EMULATOR_KEYBOARD_KEYS + EMULATOR_CHARACTER_KEYS
/** The host accepts a chord's modifiers only in this order, each at most once. */
internal val EMULATOR_MODIFIERS = listOf("Shift","Control","Alt","Meta")
internal const val EMULATOR_TEXT_MAX_CHARS = 256
internal const val EMULATOR_TOUCH_MAX_POINTERS = 10
internal const val EMULATOR_TOUCH_MAX_ID = 31
internal const val EMULATOR_TOUCH_MAX_PRESSURE = 1024
/** The host types text through Android's key map: 1 to 256 printable ASCII characters, at most seven literal `%s`. */
internal fun emulatorTextValid(text: String): Boolean =
    text.length in 1..EMULATOR_TEXT_MAX_CHARS && text.all { it.code in 0x20..0x7E } && text.windowed(2).count { it == "%s" } <= 7
/**
 * A key the host accepts, by shape alone: a plain key, or a chord of an [EMULATOR_CHORD_KEYS] key
 * with modifiers in canonical order, where a letter or digit needs more than Shift.
 */
internal fun emulatorKeyValid(key: String, modifiers: List<String>): Boolean {
    if (modifiers.isEmpty()) return key in EMULATOR_BASE_KEYS || key in EMULATOR_CONTROL_KEYS || key in EMULATOR_KEYBOARD_KEYS
    if (key !in EMULATOR_CHORD_KEYS) return false
    val at = modifiers.map { EMULATOR_MODIFIERS.indexOf(it) }
    if (at.any { it < 0 } || at.zipWithNext().any { (a, b) -> b <= a }) return false
    return key !in EMULATOR_CHARACTER_KEYS || modifiers.any { it != "Shift" }
}
/** The host refuses text that is not NFC rather than change it, so the client normalises what it sends. */
internal fun emulatorNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
/**
 * C0 and C1 controls, DEL, the line and paragraph separators and the Bidi_Control characters: none
 * is typed text, and the bidi ones can hide what a field holds. The host refuses them all.
 */
private fun emulatorForbiddenCodePoint(c: Int): Boolean =
    c in 0x00..0x1F || c in 0x7F..0x9F || c == 0x061C || c == 0x200E || c == 0x200F || c == 0x2028 || c == 0x2029 || c in 0x202A..0x202E || c in 0x2066..0x2069
/** Text for the gRPC keyboard: 1 to 256 code points, already NFC, no lone surrogate, no forbidden character. */
internal fun emulatorKeyboardTextValid(text: String): Boolean {
    if (text.isEmpty() || text.length > EMULATOR_TEXT_MAX_CHARS * 2) return false
    var i = 0; var points = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate()) { if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false } else if (c.isLowSurrogate()) return false
        val point = text.codePointAt(i)
        if (emulatorForbiddenCodePoint(point)) return false
        points++; i += Character.charCount(point)
    }
    return points <= EMULATOR_TEXT_MAX_CHARS && Normalizer.isNormalized(text, Normalizer.Form.NFC)
}
/** Inputs only a peer with [SESSION_EMULATOR_CONTROLS_CAPABILITY] may send. */
internal fun emulatorRequiresControls(input: EmulatorInput): Boolean = when (input) {
    is EmulatorInput.Key -> input.key in EMULATOR_CONTROL_KEYS
    is EmulatorInput.LongPress, is EmulatorInput.Text, is EmulatorInput.Touch -> true
    else -> false
}
/** Inputs only a peer with [SESSION_EMULATOR_KEYBOARD_CAPABILITY] may send: non-ASCII text, the editing keys, chords. */
internal fun emulatorRequiresKeyboard(input: EmulatorInput): Boolean = when (input) {
    is EmulatorInput.Text -> !emulatorTextValid(input.text)
    is EmulatorInput.Key -> input.modifiers.isNotEmpty() || (input.key !in EMULATOR_BASE_KEYS && input.key !in EMULATOR_CONTROL_KEYS)
    else -> false
}
/** Whether [input] may go out on a connection that negotiated [controls] and [keyboard]; the keyboard needs both. */
internal fun emulatorInputNegotiated(input: EmulatorInput, controls: Boolean, keyboard: Boolean): Boolean =
    (!emulatorRequiresControls(input) || controls) && (!emulatorRequiresKeyboard(input) || (controls && keyboard))
internal sealed interface EmulatorCommand {
    val requestId: String; val sessionId: String
    data class List(override val requestId:String, override val sessionId:String):EmulatorCommand
    data class Open(override val requestId:String, override val sessionId:String,val profileId:String):EmulatorCommand
    data class Status(override val requestId:String, override val sessionId:String,val emulatorId:String):EmulatorCommand
    data class Participants(override val requestId:String, override val sessionId:String,val emulatorId:String):EmulatorCommand
    data class Detach(override val requestId:String, override val sessionId:String,val emulatorId:String):EmulatorCommand
    data class Acquire(override val requestId:String, override val sessionId:String,val emulatorId:String,val expectedGeneration:Long):EmulatorCommand
    data class Handoff(override val requestId:String, override val sessionId:String,val emulatorId:String,val leaseId:String,val generation:Long,val toOwnerId:String):EmulatorCommand
    data class Release(override val requestId:String, override val sessionId:String,val emulatorId:String,val leaseId:String,val generation:Long):EmulatorCommand
    data class Stop(override val requestId:String, override val sessionId:String,val emulatorId:String,val leaseId:String,val generation:Long):EmulatorCommand
    data class Input(override val requestId:String, override val sessionId:String,val emulatorId:String,val leaseId:String,val generation:Long,val geometryRevision:Long,val sequence:Long,val input:EmulatorInput):EmulatorCommand
    data class Watch(override val requestId:String, override val sessionId:String,val emulatorId:String,val generation:Long,val recovery:Boolean=false):EmulatorCommand
    data class Unwatch(override val requestId:String, override val sessionId:String,val emulatorId:String,val streamId:String):EmulatorCommand
    data class FrameAck(override val requestId:String, override val sessionId:String,val emulatorId:String,val streamId:String,val frameId:Long,val chunkIndex:Long):EmulatorCommand
    /** `emulator.rotate` ([SESSION_EMULATOR_CONTROLS_CAPABILITY]): [rotation] 0 to 3, in quarter turns. */
    data class Rotate(override val requestId:String, override val sessionId:String,val emulatorId:String,val leaseId:String,val generation:Long,val geometryRevision:Long,val sequence:Long,val rotation:Long):EmulatorCommand
}
internal sealed interface EmulatorResult {
    data class Profiles(val profiles:List<EmulatorProfileInfo>):EmulatorResult
    data class Opened(val emulatorId:String,val profileId:String,val label:String,val incarnation:String,val generation:Long,val geometry:EmulatorGeometry,val owner:EmulatorOwner?):EmulatorResult
    data class Status(val emulatorId:String,val profileId:String,val label:String,val incarnation:String,val generation:Long,val geometry:EmulatorGeometry,val owner:EmulatorOwner?,val control:EmulatorControl?):EmulatorResult
    data class Participants(val emulatorId:String,val generation:Long,val participants:List<EmulatorOwner>):EmulatorResult
    data class Lease(val emulatorId:String,val generation:Long,val geometry:EmulatorGeometry,val owner:EmulatorOwner,val leaseId:String,val nextSequence:Long):EmulatorResult
    data class Busy(val emulatorId:String,val generation:Long,val owner:EmulatorOwner):EmulatorResult
    data class InputAck(val emulatorId:String,val generation:Long,val sequence:Long,val status:String):EmulatorResult
    data class Released(val emulatorId:String,val generation:Long):EmulatorResult
    data class Detached(val emulatorId:String,val generation:Long):EmulatorResult
    data class Stopped(val emulatorId:String,val generation:Long):EmulatorResult
    data class Watching(val emulatorId:String,val streamId:String,val generation:Long):EmulatorResult
    data class Unwatched(val emulatorId:String,val streamId:String):EmulatorResult
    /** [geometry] is the display after the rotation, present exactly when [status] is `accepted`. */
    data class Rotated(val emulatorId:String,val generation:Long,val sequence:Long,val status:String,val geometry:EmulatorGeometry?):EmulatorResult
}
internal data class EmulatorStateEvent(val sessionId:String,val emulatorId:String,val profileId:String,val label:String,val incarnation:String,val generation:Long,val geometry:EmulatorGeometry,val owner:EmulatorOwner?,val control:EmulatorControl?)
internal data class EmulatorFrameWaitingEvent(val emulatorId:String,val incarnation:String,val streamId:String,val generation:Long)
internal data class EmulatorFrameEvent(val emulatorId:String,val incarnation:String,val streamId:String,val generation:Long,val geometryRevision:Long,val frameId:Long,val chunkIndex:Long,val chunkCount:Long,val totalBytes:Long,val width:Long,val height:Long,val displayWidth:Long,val displayHeight:Long,val rotation:Long,val data:String)

private fun eString(s:String,max:Int):String { require(s.isNotEmpty() && Wire.fitsUtf8(s,max)); var i=0; while(i<s.length){val c=s[i++];if(c.isHighSurrogate()){require(i<s.length && s[i].isLowSurrogate());i++} else require(!c.isLowSurrogate())};return s }
private fun eValidateId(s:String):String=eString(s,256).also { require(it.none { c -> c.isWhitespace() || Character.isSpaceChar(c) || c.code in 0..31 || c.code in 127..159 || c=='\uFEFF' }) }
private fun JsonObject.eId(key:String):String=eValidateId(text(key))
private fun JsonObject.eCount(key:String,min:Long=0,max:Long=EMULATOR_MAX_SAFE):Long {val p=getValue(key).jsonPrimitive;require(!p.isString && p !is JsonNull);val d=p.content.toDoubleOrNull() ?: error("Invalid number");require(d.isFinite() && d>=min.toDouble() && d<=max.toDouble() && d%1.0==0.0);return d.toLong()}
private fun eOwner(o:JsonObject):EmulatorOwner {Wire.keys(o,setOf("kind","id"));val k=o.text("kind");require(k=="device"||k=="agent");return EmulatorOwner(k,o.eId("id"))}
private fun eGeometry(o:JsonObject):EmulatorGeometry {Wire.keys(o,setOf("width","height","rotation","revision"));val w=o.eCount("width",1,16384);val h=o.eCount("height",1,16384);require(w*h<=8388608);return EmulatorGeometry(w,h,o.eCount("rotation",0,3),o.eCount("revision",1))}
private fun eControl(o:JsonObject):EmulatorControl {Wire.keys(o,setOf("leaseId","nextSequence"));return EmulatorControl(o.eId("leaseId"),o.eCount("nextSequence"))}
private fun eInput(input:EmulatorInput):JsonObject=when(input){
    is EmulatorInput.Key -> {require(emulatorKeyValid(input.key,input.modifiers));if(input.modifiers.isEmpty()) Wire.objectOf("kind" to "key","key" to input.key) else Wire.objectOf("kind" to "key","key" to input.key,"modifiers" to JsonArray(input.modifiers.map { JsonPrimitive(it) }))}
    is EmulatorInput.LongPress -> {require(input.x in 0..16383 && input.y in 0..16383 && input.durationMs in 400..2000);Wire.objectOf("kind" to "long_press","x" to input.x,"y" to input.y,"durationMs" to input.durationMs)}
    // Every ASCII text is keyboard text too; emulatorInputNegotiated tells the two apart.
    is EmulatorInput.Text -> {require(emulatorKeyboardTextValid(input.text));Wire.objectOf("kind" to "text","text" to input.text)}
    is EmulatorInput.Touch -> {
        require(input.touches.size in 1..EMULATOR_TOUCH_MAX_POINTERS && input.touches.map { it.id }.toSet().size == input.touches.size)
        require(input.touches.all { it.id in 0..EMULATOR_TOUCH_MAX_ID && it.x in 0..16383 && it.y in 0..16383 && it.pressure in 0..EMULATOR_TOUCH_MAX_PRESSURE })
        Wire.objectOf("kind" to "touch","touches" to JsonArray(input.touches.map { Wire.objectOf("id" to it.id,"x" to it.x,"y" to it.y,"pressure" to it.pressure) }))
    }
    is EmulatorInput.Tap -> {require(input.x in 0..16383 && input.y in 0..16383);Wire.objectOf("kind" to "tap","x" to input.x,"y" to input.y)}
    is EmulatorInput.Swipe -> {require(listOf(input.x,input.y,input.endX,input.endY).all { it in 0..16383 } && input.durationMs in 50..1000);Wire.objectOf("kind" to "swipe","x" to input.x,"y" to input.y,"endX" to input.endX,"endY" to input.endY,"durationMs" to input.durationMs)}
}
internal fun encodeEmulatorCommand(command:EmulatorCommand):JsonObject {
    eValidateId(command.requestId);eValidateId(command.sessionId)
    val p=mutableListOf<Pair<String,Any?>>("requestId" to command.requestId,"sessionId" to command.sessionId)
    fun context(type:String,id:String){eValidateId(id);p.add("type" to type);p.add("emulatorId" to id)}
    fun lease(type:String,id:String,lease:String,generation:Long){context(type,id);eValidateId(lease);require(generation in 0..EMULATOR_MAX_SAFE);p.add("leaseId" to lease);p.add("generation" to generation)}
    when(command){
        is EmulatorCommand.List -> p.add("type" to "emulator.list")
        is EmulatorCommand.Open -> {eValidateId(command.profileId);p.add("type" to "emulator.open");p.add("profileId" to command.profileId)}
        is EmulatorCommand.Status -> context("emulator.status",command.emulatorId)
        is EmulatorCommand.Participants -> context("emulator.participants",command.emulatorId)
        is EmulatorCommand.Detach -> context("emulator.detach",command.emulatorId)
        is EmulatorCommand.Acquire -> {context("emulator.acquire",command.emulatorId);require(command.expectedGeneration in 0..EMULATOR_MAX_SAFE);p.add("expectedGeneration" to command.expectedGeneration)}
        is EmulatorCommand.Handoff -> {lease("emulator.handoff",command.emulatorId,command.leaseId,command.generation);eValidateId(command.toOwnerId);p.add("toOwnerId" to command.toOwnerId)}
        is EmulatorCommand.Release -> lease("emulator.release",command.emulatorId,command.leaseId,command.generation)
        is EmulatorCommand.Stop -> lease("emulator.stop",command.emulatorId,command.leaseId,command.generation)
        is EmulatorCommand.Input -> {lease("emulator.input",command.emulatorId,command.leaseId,command.generation);require(command.geometryRevision in 1..EMULATOR_MAX_SAFE && command.sequence in 0..EMULATOR_MAX_SAFE);p.add("geometryRevision" to command.geometryRevision);p.add("sequence" to command.sequence);p.add("input" to eInput(command.input))}
        is EmulatorCommand.Watch -> {context("emulator.watch",command.emulatorId);require(command.generation in 0..EMULATOR_MAX_SAFE);p.add("generation" to command.generation);if(command.recovery)p.add("recovery" to true)}
        is EmulatorCommand.Unwatch -> {context("emulator.unwatch",command.emulatorId);eValidateId(command.streamId);p.add("streamId" to command.streamId)}
        is EmulatorCommand.Rotate -> {lease("emulator.rotate",command.emulatorId,command.leaseId,command.generation);require(command.geometryRevision in 1..EMULATOR_MAX_SAFE && command.sequence in 0..EMULATOR_MAX_SAFE && command.rotation in 0..3);p.add("geometryRevision" to command.geometryRevision);p.add("sequence" to command.sequence);p.add("rotation" to command.rotation)}
        is EmulatorCommand.FrameAck -> {context("emulator.frame.ack",command.emulatorId);eValidateId(command.streamId);require(command.frameId in 1..EMULATOR_MAX_SAFE && command.chunkIndex in 0..21);p.add("streamId" to command.streamId);p.add("frameId" to command.frameId);p.add("chunkIndex" to command.chunkIndex)}
    }
    return Wire.objectOf(*p.toTypedArray())
}
private val eStatusKeys=setOf("emulatorId","profileId","label","incarnation","generation","geometry")
internal fun parseEmulatorResult(o:JsonObject):EmulatorResult {
    val kind=o.text("kind");val b=setOf("kind","emulatorId","generation")
    when(kind){
        "emulator.profiles" -> {Wire.keys(o,setOf("kind","profiles"));val a=o.getValue("profiles").jsonArray;require(a.size<=16);return EmulatorResult.Profiles(a.map { val p=it.jsonObject;Wire.keys(p,setOf("id","label"));EmulatorProfileInfo(p.eId("id"),eString(p.text("label"),128)) })}
        "emulator.opened" -> Wire.keys(o,setOf("kind")+eStatusKeys,setOf("owner"))
        "emulator.status" -> Wire.keys(o,setOf("kind")+eStatusKeys,setOf("owner","control"))
        "emulator.participants" -> Wire.keys(o,b+"participants")
        "emulator.lease" -> Wire.keys(o,b+setOf("geometry","owner","leaseId","nextSequence"))
        "emulator.busy" -> Wire.keys(o,b+"owner")
        "emulator.input.ack" -> Wire.keys(o,b+setOf("sequence","status"))
        "emulator.released","emulator.detached","emulator.stopped" -> Wire.keys(o,b)
        "emulator.watching" -> Wire.keys(o,b+"streamId")
        "emulator.rotated" -> {
            Wire.keys(o,b+setOf("sequence","status"),setOf("geometry"))
            val status=o.text("status");require(status=="accepted"||status=="uncertain");require(("geometry" in o)==(status=="accepted"))
            return EmulatorResult.Rotated(o.eId("emulatorId"),o.eCount("generation"),o.eCount("sequence"),status,if("geometry" in o)eGeometry(o.obj("geometry")) else null)
        }
        "emulator.unwatched" -> {Wire.keys(o,setOf("kind","emulatorId","streamId"));return EmulatorResult.Unwatched(o.eId("emulatorId"),o.eId("streamId"))}
        else -> error("Unknown emulator result")
    }
    val id=o.eId("emulatorId");val generation=o.eCount("generation")
    return when(kind){
        "emulator.opened","emulator.status" -> {val profile=o.eId("profileId");val label=eString(o.text("label"),128);val incarnation=o.eId("incarnation");val geometry=eGeometry(o.obj("geometry"));val owner=if("owner" in o)eOwner(o.obj("owner")) else null;val control=if("control" in o)eControl(o.obj("control")) else null;require(control==null||owner!=null);if(kind=="emulator.opened") EmulatorResult.Opened(id,profile,label,incarnation,generation,geometry,owner) else EmulatorResult.Status(id,profile,label,incarnation,generation,geometry,owner,control)}
        "emulator.participants" -> {val a=o.getValue("participants").jsonArray;require(a.size<=64);EmulatorResult.Participants(id,generation,a.map { eOwner(it.jsonObject) })}
        "emulator.lease" -> EmulatorResult.Lease(id,generation,eGeometry(o.obj("geometry")),eOwner(o.obj("owner")),o.eId("leaseId"),o.eCount("nextSequence"))
        "emulator.busy" -> EmulatorResult.Busy(id,generation,eOwner(o.obj("owner")))
        "emulator.input.ack" -> {val status=o.text("status");require(status=="accepted"||status=="uncertain");EmulatorResult.InputAck(id,generation,o.eCount("sequence"),status)}
        "emulator.released" -> EmulatorResult.Released(id,generation)
        "emulator.detached" -> EmulatorResult.Detached(id,generation)
        "emulator.stopped" -> EmulatorResult.Stopped(id,generation)
        else -> EmulatorResult.Watching(id,o.eId("streamId"),generation)
    }
}
internal fun parseEmulatorState(o:JsonObject):EmulatorStateEvent {Wire.keys(o,setOf("type","kind","sessionId")+eStatusKeys,setOf("owner","control"));require(o.text("type")=="host.event" && o.text("kind")=="emulator.state");val owner=if("owner" in o)eOwner(o.obj("owner")) else null;val control=if("control" in o)eControl(o.obj("control")) else null;require(control==null||owner!=null);return EmulatorStateEvent(o.eId("sessionId"),o.eId("emulatorId"),o.eId("profileId"),eString(o.text("label"),128),o.eId("incarnation"),o.eCount("generation"),eGeometry(o.obj("geometry")),owner,control)}
internal fun parseEmulatorFrameWaiting(o:JsonObject):EmulatorFrameWaitingEvent {Wire.keys(o,setOf("type","kind","emulatorId","incarnation","streamId","generation","reason"));require(o.text("type")=="host.event" && o.text("kind")=="emulator.frame.waiting" && o.text("reason")=="capturing");return EmulatorFrameWaitingEvent(o.eId("emulatorId"),o.eId("incarnation"),o.eId("streamId"),o.eCount("generation"))}
internal fun parseEmulatorFrame(o:JsonObject):EmulatorFrameEvent {Wire.keys(o,setOf("type","kind","emulatorId","incarnation","streamId","generation","geometryRevision","frameId","chunkIndex","chunkCount","totalBytes","width","height","displayWidth","displayHeight","rotation","data"));require(o.text("type")=="host.event" && o.text("kind")=="emulator.frame");val count=o.eCount("chunkCount",1,22);val index=o.eCount("chunkIndex",0,count-1);val total=o.eCount("totalBytes",1,EMULATOR_FRAME_MAX_BYTES.toLong());require(count==(total+EMULATOR_FRAME_CHUNK_BYTES-1)/EMULATOR_FRAME_CHUNK_BYTES);val w=o.eCount("width",1,16384);val h=o.eCount("height",1,16384);val dw=o.eCount("displayWidth",1,16384);val dh=o.eCount("displayHeight",1,16384);require(w*h<=8388608 && dw*dh<=8388608);val data=o.text("data");val bytes=if(index==count-1) total-index*EMULATOR_FRAME_CHUNK_BYTES else EMULATOR_FRAME_CHUNK_BYTES.toLong();Wire.decode(data,bytes.toInt());return EmulatorFrameEvent(o.eId("emulatorId"),o.eId("incarnation"),o.eId("streamId"),o.eCount("generation"),o.eCount("geometryRevision",1),o.eCount("frameId",1),index,count,total,w,h,dw,dh,o.eCount("rotation",0,3),data)}

private fun JsonObject.eInt(key:String,min:Long,max:Long):Long=eCount(key,min,max)
private fun parseEmulatorInput(o:JsonObject):EmulatorInput=when(o.text("kind")){
    "key" -> {
        Wire.keys(o,setOf("kind","key"),setOf("modifiers"))
        // An empty list is refused, not read as no modifiers: the host refuses it too.
        val modifiers=if("modifiers" in o) o.getValue("modifiers").jsonArray.map { val p=it.jsonPrimitive;require(p.isString);p.content }.also { require(it.isNotEmpty()) } else emptyList()
        EmulatorInput.Key(o.text("key").also { require(emulatorKeyValid(it,modifiers)) },modifiers)
    }
    "tap" -> {Wire.keys(o,setOf("kind","x","y"));EmulatorInput.Tap(o.eInt("x",0,16383),o.eInt("y",0,16383))}
    "swipe" -> {Wire.keys(o,setOf("kind","x","y","endX","endY","durationMs"));EmulatorInput.Swipe(o.eInt("x",0,16383),o.eInt("y",0,16383),o.eInt("endX",0,16383),o.eInt("endY",0,16383),o.eInt("durationMs",50,1000))}
    "long_press" -> {Wire.keys(o,setOf("kind","x","y","durationMs"));EmulatorInput.LongPress(o.eInt("x",0,16383),o.eInt("y",0,16383),o.eInt("durationMs",400,2000))}
    "text" -> {Wire.keys(o,setOf("kind","text"));val p=o.getValue("text").jsonPrimitive;require(p.isString);EmulatorInput.Text(p.content.also { require(emulatorKeyboardTextValid(it)) })}
    "touch" -> {
        Wire.keys(o,setOf("kind","touches"));val a=o.getValue("touches").jsonArray
        require(a.size in 1..EMULATOR_TOUCH_MAX_POINTERS)
        val touches=a.map { val t=it.jsonObject;Wire.keys(t,setOf("id","x","y","pressure"));EmulatorTouchPoint(t.eInt("id",0,EMULATOR_TOUCH_MAX_ID.toLong()).toInt(),t.eInt("x",0,16383),t.eInt("y",0,16383),t.eInt("pressure",0,EMULATOR_TOUCH_MAX_PRESSURE.toLong()).toInt()) }
        require(touches.map { it.id }.toSet().size==touches.size);EmulatorInput.Touch(touches)
    }
    else -> error("Unknown emulator input")
}
/** Strict parse of the lease-bound `emulator.input` and `emulator.rotate` commands, as the host reads them. */
internal fun parseEmulatorLeaseCommand(o:JsonObject):EmulatorCommand {
    val type=o.text("type");val base=setOf("type","requestId","sessionId","emulatorId","leaseId","generation","geometryRevision","sequence")
    return when(type){
        "emulator.input" -> {Wire.keys(o,base+"input");EmulatorCommand.Input(o.eId("requestId"),o.eId("sessionId"),o.eId("emulatorId"),o.eId("leaseId"),o.eCount("generation"),o.eCount("geometryRevision",1),o.eCount("sequence"),parseEmulatorInput(o.obj("input")))}
        "emulator.rotate" -> {Wire.keys(o,base+"rotation");EmulatorCommand.Rotate(o.eId("requestId"),o.eId("sessionId"),o.eId("emulatorId"),o.eId("leaseId"),o.eCount("generation"),o.eCount("geometryRevision",1),o.eCount("sequence"),o.eCount("rotation",0,3))}
        else -> error("Unknown emulator command")
    }
}
