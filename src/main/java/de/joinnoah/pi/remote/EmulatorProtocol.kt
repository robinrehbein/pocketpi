package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

internal const val SESSION_EMULATOR_CAPABILITY = "session.emulator.v1"
internal const val EMULATOR_FRAME_CHUNK_BYTES = 49152
internal const val EMULATOR_FRAME_MAX_BYTES = 1048576
private const val EMULATOR_MAX_SAFE = 9007199254740991L
public data class EmulatorProfileInfo(val id: String, val label: String)
public data class EmulatorGeometry(val width: Long, val height: Long, val rotation: Long, val revision: Long)
public data class EmulatorOwner(val kind: String, val id: String)
public data class EmulatorControl(val leaseId: String, val nextSequence: Long)
public sealed interface EmulatorInput {
    data class Key(val key: String) : EmulatorInput
    data class Tap(val x: Long, val y: Long) : EmulatorInput
    data class Swipe(val x: Long, val y: Long, val endX: Long, val endY: Long, val durationMs: Long) : EmulatorInput
}
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
    data class Watch(override val requestId:String, override val sessionId:String,val emulatorId:String,val generation:Long):EmulatorCommand
    data class Unwatch(override val requestId:String, override val sessionId:String,val emulatorId:String,val streamId:String):EmulatorCommand
    data class FrameAck(override val requestId:String, override val sessionId:String,val emulatorId:String,val streamId:String,val frameId:Long,val chunkIndex:Long):EmulatorCommand
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
}
internal data class EmulatorStateEvent(val sessionId:String,val emulatorId:String,val profileId:String,val label:String,val incarnation:String,val generation:Long,val geometry:EmulatorGeometry,val owner:EmulatorOwner?,val control:EmulatorControl?)
internal data class EmulatorFrameEvent(val emulatorId:String,val incarnation:String,val streamId:String,val generation:Long,val geometryRevision:Long,val frameId:Long,val chunkIndex:Long,val chunkCount:Long,val totalBytes:Long,val width:Long,val height:Long,val displayWidth:Long,val displayHeight:Long,val rotation:Long,val data:String)

private fun eString(s:String,max:Int):String { require(s.isNotEmpty() && Wire.fitsUtf8(s,max)); var i=0; while(i<s.length){val c=s[i++];if(c.isHighSurrogate()){require(i<s.length && s[i].isLowSurrogate());i++} else require(!c.isLowSurrogate())};return s }
private fun eValidateId(s:String):String=eString(s,256).also { require(it.none { c -> c.isWhitespace() || Character.isSpaceChar(c) || c.code in 0..31 || c.code in 127..159 || c=='\uFEFF' }) }
private fun JsonObject.eId(key:String):String=eValidateId(text(key))
private fun JsonObject.eCount(key:String,min:Long=0,max:Long=EMULATOR_MAX_SAFE):Long {val p=getValue(key).jsonPrimitive;require(!p.isString && p !is JsonNull);val d=p.content.toDoubleOrNull() ?: error("Invalid number");require(d.isFinite() && d>=min.toDouble() && d<=max.toDouble() && d%1.0==0.0);return d.toLong()}
private fun eOwner(o:JsonObject):EmulatorOwner {Wire.keys(o,setOf("kind","id"));val k=o.text("kind");require(k=="device"||k=="agent");return EmulatorOwner(k,o.eId("id"))}
private fun eGeometry(o:JsonObject):EmulatorGeometry {Wire.keys(o,setOf("width","height","rotation","revision"));val w=o.eCount("width",1,16384);val h=o.eCount("height",1,16384);require(w*h<=8388608);return EmulatorGeometry(w,h,o.eCount("rotation",0,3),o.eCount("revision",1))}
private fun eControl(o:JsonObject):EmulatorControl {Wire.keys(o,setOf("leaseId","nextSequence"));return EmulatorControl(o.eId("leaseId"),o.eCount("nextSequence"))}
private fun eInput(input:EmulatorInput):JsonObject=when(input){
    is EmulatorInput.Key -> {require(input.key in setOf("Home","Back","Enter","Tab","Escape","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","Backspace"));Wire.objectOf("kind" to "key","key" to input.key)}
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
        is EmulatorCommand.Watch -> {context("emulator.watch",command.emulatorId);require(command.generation in 0..EMULATOR_MAX_SAFE);p.add("generation" to command.generation)}
        is EmulatorCommand.Unwatch -> {context("emulator.unwatch",command.emulatorId);eValidateId(command.streamId);p.add("streamId" to command.streamId)}
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
internal fun parseEmulatorFrame(o:JsonObject):EmulatorFrameEvent {Wire.keys(o,setOf("type","kind","emulatorId","incarnation","streamId","generation","geometryRevision","frameId","chunkIndex","chunkCount","totalBytes","width","height","displayWidth","displayHeight","rotation","data"));require(o.text("type")=="host.event" && o.text("kind")=="emulator.frame");val count=o.eCount("chunkCount",1,22);val index=o.eCount("chunkIndex",0,count-1);val total=o.eCount("totalBytes",1,EMULATOR_FRAME_MAX_BYTES.toLong());require(count==(total+EMULATOR_FRAME_CHUNK_BYTES-1)/EMULATOR_FRAME_CHUNK_BYTES);val w=o.eCount("width",1,16384);val h=o.eCount("height",1,16384);val dw=o.eCount("displayWidth",1,16384);val dh=o.eCount("displayHeight",1,16384);require(w*h<=8388608 && dw*dh<=8388608);val data=o.text("data");val bytes=if(index==count-1) total-index*EMULATOR_FRAME_CHUNK_BYTES else EMULATOR_FRAME_CHUNK_BYTES.toLong();Wire.decode(data,bytes.toInt());return EmulatorFrameEvent(o.eId("emulatorId"),o.eId("incarnation"),o.eId("streamId"),o.eCount("generation"),o.eCount("geometryRevision",1),o.eCount("frameId",1),index,count,total,w,h,dw,dh,o.eCount("rotation",0,3),data)}
