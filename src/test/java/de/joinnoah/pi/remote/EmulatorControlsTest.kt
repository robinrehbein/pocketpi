package de.joinnoah.pi.remote

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmulatorControlsTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/emulator-controls-v1.json")!!.readText()).jsonObject
    }

    @Test fun fixtureCommandsRoundTripAndInvalidOnesAreRefused() {
        val commands = fixture.obj("commands")
        for (entry in commands.getValue("valid").jsonArray) {
            val o = entry.jsonObject
            assertEquals(o, encodeEmulatorCommand(parseEmulatorLeaseCommand(o)))
        }
        for (entry in commands.getValue("invalid").jsonArray)
            assertThrows(entry.toString(), Exception::class.java) { parseEmulatorLeaseCommand(entry.jsonObject) }
        val results = fixture.obj("results")
        for (entry in results.getValue("valid").jsonArray) assertTrue(parseEmulatorResult(entry.jsonObject) is EmulatorResult.Rotated)
        for (entry in results.getValue("invalid").jsonArray)
            assertThrows(entry.toString(), Exception::class.java) { parseEmulatorResult(entry.jsonObject) }
    }

    @Test fun textIsPrintableAsciiOnly() {
        assertTrue(emulatorTextValid("Hello, world! ~"))
        assertFalse(emulatorTextValid("Grüße"))
        assertFalse(emulatorTextValid("line\nbreak"))
        assertFalse(emulatorTextValid(""))
        assertFalse(emulatorTextValid("x".repeat(257)))
        assertFalse(emulatorTextValid("%s".repeat(8)))
    }

    private data class Call(val type: String, val fields: Map<String, Any?>, val reply: CompletableDeferred<JsonObject>)
    private val geometry = Wire.objectOf("width" to 2, "height" to 3, "rotation" to 0, "revision" to 1)
    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
    private fun frame(stream: String, generation: Int): JsonObject {
        val bytes = png()
        return Wire.objectOf("type" to "host.event", "kind" to "emulator.frame", "emulatorId" to "e", "incarnation" to "i", "streamId" to stream,
            "generation" to generation, "geometryRevision" to 1, "frameId" to 1, "chunkIndex" to 0, "chunkCount" to 1, "totalBytes" to bytes.size,
            "width" to 2, "height" to 3, "displayWidth" to 2, "displayHeight" to 3, "rotation" to 0, "data" to Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    /** A client in control with a frame shown; inputs and rotations wait in [held] for their replies. */
    private fun controlling(scope: TestScope, controls: Boolean, held: MutableList<Call>, closed: MutableList<Long>, limits: EmulatorLimits? = null,
        controlsNow: () -> Boolean = { controls }, statusGeneration: () -> Int? = { null }): EmulatorClient {
        var generation = 1
        val self = Wire.objectOf("kind" to "device", "id" to "me")
        val client = EmulatorClient(scope.backgroundScope, { _, _ -> true }, { _, type, fields ->
            val map = fields.toMap()
            when (type) {
                "emulator.list" -> Wire.objectOf("kind" to "emulator.profiles", "profiles" to JsonArray(listOf(Wire.objectOf("id" to "p", "label" to "Phone"))))
                "emulator.open" -> Wire.objectOf("kind" to "emulator.opened", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to generation, "geometry" to geometry)
                "emulator.status" -> Wire.objectOf("kind" to "emulator.status", "emulatorId" to "e", "profileId" to "p", "label" to "Phone", "incarnation" to "i", "generation" to (statusGeneration() ?: generation), "geometry" to geometry, "owner" to self)
                "emulator.participants" -> Wire.objectOf("kind" to "emulator.participants", "emulatorId" to "e", "generation" to (statusGeneration() ?: generation), "participants" to JsonArray(emptyList()))
                "emulator.watch" -> Wire.objectOf("kind" to "emulator.watching", "emulatorId" to "e", "generation" to map["generation"], "streamId" to "stream$generation")
                "emulator.unwatch" -> Wire.objectOf("kind" to "emulator.unwatched", "emulatorId" to "e", "streamId" to map["streamId"])
                "emulator.frame.ack" -> Wire.objectOf("kind" to "accepted")
                "emulator.acquire" -> { generation = 2
                    Wire.objectOf("kind" to "emulator.lease", "emulatorId" to "e", "generation" to generation, "geometry" to geometry, "owner" to self, "leaseId" to "lease", "nextSequence" to 5) }
                else -> CompletableDeferred<JsonObject>().also { held.add(Call(type, map, it)) }.await()
            }
        }, {}, { "d" }, { true }, { closed.add(it) }, { false }, controlsNow, { limits }, { scope.testScheduler.currentTime })
        assertTrue(client.open("session", 1)); scope.runCurrent()
        assertTrue(client.selectProfile("p")); scope.runCurrent()
        assertTrue(client.acquire()); scope.runCurrent()
        client.frame(frame("stream2", 2)); scope.runCurrent()
        assertNotNull(client.view?.image)
        assertEquals(controls, client.view?.controls)
        return client
    }

    private fun ack(call: Call, status: String = "accepted") = call.reply.complete(Wire.objectOf("kind" to "emulator.input.ack", "emulatorId" to "e", "generation" to 2, "sequence" to call.fields["sequence"].toString().toLong(), "status" to status))
    private fun touch(vararg points: EmulatorTouchPoint) = EmulatorInput.Touch(points.toList())

    @Test fun withoutTheCapabilityNoControlGoesOut() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = false, held, closed)
        assertFalse(client.input(EmulatorInput.Key("Power")))
        assertFalse(client.input(EmulatorInput.Text("hi")))
        assertFalse(client.touch(touch(EmulatorTouchPoint(0, 1, 1, 512))))
        assertFalse(client.rotate(1))
        assertTrue(client.input(EmulatorInput.Key("Home")))
        scope.runCurrent()
        assertEquals(listOf("emulator.input"), held.map { it.type })
    }

    @Test fun textAndKeysGoOutAndNonAsciiTextNever() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertFalse(client.input(EmulatorInput.Text("Grüße")))
        assertTrue(held.isEmpty())
        assertTrue(client.input(EmulatorInput.Text("hello"))); scope.runCurrent()
        // Text is never replaced by a later input.
        assertFalse(client.input(EmulatorInput.Text("again")))
        val sent = held.single()
        assertEquals("""{"kind":"text","text":"hello"}""", sent.fields["input"].toString())
        ack(sent); scope.runCurrent()
        assertTrue(client.input(EmulatorInput.Key("VolumeUp"))); scope.runCurrent()
        assertEquals("""{"kind":"key","key":"VolumeUp"}""", held.last().fields["input"].toString())
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun rotationTakesTheNextSequenceAndTheNewGeometry() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.rotate(1)); scope.runCurrent()
        val call = held.single()
        assertEquals("emulator.rotate", call.type)
        assertEquals("5", call.fields["sequence"].toString())
        assertEquals("1", call.fields["rotation"].toString())
        assertTrue(client.view!!.inputPending)
        call.reply.complete(Wire.objectOf("kind" to "emulator.rotated", "emulatorId" to "e", "generation" to 2, "sequence" to 5, "status" to "accepted",
            "geometry" to Wire.objectOf("width" to 3, "height" to 2, "rotation" to 1, "revision" to 2)))
        scope.runCurrent()
        assertEquals(EmulatorGeometry(3, 2, 1, 2), client.view!!.geometry)
        assertFalse(client.view!!.inputPending)
        assertNull(client.view!!.image)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun theHostsStateEventBeforeTheRotateAnswerKeepsControl() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.rotate(1)); scope.runCurrent()
        val rotated = Wire.objectOf("width" to 3, "height" to 2, "rotation" to 1, "revision" to 2)
        // The Host queues emulator.state with the new geometry ahead of the emulator.rotated result.
        client.state(Wire.objectOf("type" to "host.event", "kind" to "emulator.state", "sessionId" to "session", "emulatorId" to "e", "profileId" to "p", "label" to "Phone",
            "incarnation" to "i", "generation" to 2, "geometry" to rotated, "owner" to Wire.objectOf("kind" to "device", "id" to "me"),
            "control" to Wire.objectOf("leaseId" to "lease", "nextSequence" to 6)))
        scope.runCurrent()
        assertTrue(client.view!!.ownsControl)
        assertTrue(client.view!!.rotating)
        assertNull(client.view!!.notice)
        held.single { it.type == "emulator.rotate" }.reply.complete(Wire.objectOf("kind" to "emulator.rotated", "emulatorId" to "e", "generation" to 2, "sequence" to 5, "status" to "accepted", "geometry" to rotated))
        scope.runCurrent()
        assertEquals(EmulatorGeometry(3, 2, 1, 2), client.view!!.geometry)
        assertTrue(client.view!!.ownsControl)
        assertFalse(client.view!!.inputPending)
        assertFalse(client.view!!.rotating)
        assertNull(client.view!!.notice)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun anUncertainRotationClosesTheConnection() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.rotate(3)); scope.runCurrent()
        held.single().reply.complete(Wire.objectOf("kind" to "emulator.rotated", "emulatorId" to "e", "generation" to 2, "sequence" to 5, "status" to "uncertain"))
        scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view!!.notice)
        assertEquals(listOf(1L), closed)
    }

    @Test fun touchIsPipelinedThrottledAndCoalescedButNeverDropsALift() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.touch(touch(EmulatorTouchPoint(0, 1, 1, 512)))); scope.runCurrent()
        assertEquals(1, held.size)
        // Within the interval: moves wait and replace each other.
        assertTrue(client.touch(touch(EmulatorTouchPoint(0, 0, 2, 512))))
        assertTrue(client.touch(touch(EmulatorTouchPoint(0, 1, 2, 512))))
        assertTrue(client.touch(touch(EmulatorTouchPoint(0, 1, 2, 0))))
        // Another input waits for the gesture.
        assertFalse(client.input(EmulatorInput.Key("Home")))
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS + 1); scope.runCurrent()
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS + 1); scope.runCurrent()
        assertEquals(listOf(5L, 6L, 7L), held.map { it.fields["sequence"].toString().toLong() })
        assertEquals("""{"kind":"touch","touches":[{"id":0,"x":1,"y":2,"pressure":512}]}""", held[1].fields["input"].toString())
        assertEquals("""{"kind":"touch","touches":[{"id":0,"x":1,"y":2,"pressure":0}]}""", held[2].fields["input"].toString())
        assertTrue(client.view!!.inputPending)
        held.forEach { ack(it) }; scope.runCurrent()
        assertFalse(client.view!!.inputPending)
        assertTrue(client.input(EmulatorInput.Key("Home")))
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun touchesInFlightStayBelowTheHostLimit() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val limits = EmulatorLimits(StreamLimits(4, 250, 1048576, 49152), 2)
        val client = controlling(scope, controls = true, held, closed, limits)
        // Pointer changes never coalesce: down, second down, lift, lift.
        val events = listOf(
            touch(EmulatorTouchPoint(0, 1, 1, 512)),
            touch(EmulatorTouchPoint(0, 1, 1, 512), EmulatorTouchPoint(1, 0, 0, 512)),
            touch(EmulatorTouchPoint(0, 1, 1, 0), EmulatorTouchPoint(1, 0, 0, 512)),
            touch(EmulatorTouchPoint(1, 0, 0, 0)),
        )
        events.forEach { assertTrue(client.touch(it)) }
        repeat(4) { scope.testScheduler.advanceTimeBy(100); scope.runCurrent() }
        assertEquals(2, held.size)
        ack(held[0]); scope.runCurrent(); scope.testScheduler.advanceTimeBy(100); scope.runCurrent()
        assertEquals(3, held.size)
        ack(held[1]); ack(held[2]); scope.runCurrent(); scope.testScheduler.advanceTimeBy(100); scope.runCurrent()
        assertEquals(4, held.size)
        assertEquals(listOf(5L, 6L, 7L, 8L), held.map { it.fields["sequence"].toString().toLong() })
    }

    @Test fun aFailedTouchLeavesTheGestureInDoubt() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.touch(touch(EmulatorTouchPoint(0, 1, 1, 512)))); scope.runCurrent()
        ack(held.single(), status = "uncertain"); scope.runCurrent()
        assertEquals(EmulatorNotice.UNCERTAIN, client.view!!.notice)
        assertEquals(listOf(1L), closed)
        assertFalse(client.touch(touch(EmulatorTouchPoint(0, 1, 1, 0))))
    }

    @Test fun theTrackerKeepsSmallIdsAndLiftsPointers() {
        val tracker = EmulatorTouchTracker()
        val sent = mutableListOf<EmulatorInput.Touch>()
        fun p(key: Long, down: Boolean, was: Boolean, x: Long, inside: Boolean = true) =
            EmulatorTouchTracker.Pointer(key, down, was, if (inside) x to 0L else null, x to 0L, 0.5f)
        tracker.event(listOf(p(900, true, false, 1))) { sent += it; true }
        tracker.event(listOf(p(900, true, true, 2), p(901, true, false, 5))) { sent += it; true }
        // Down outside the image: ignored until it lifts.
        tracker.event(listOf(p(900, true, true, 2), p(901, true, true, 5), p(902, true, false, 9, inside = false))) { sent += it; true }
        tracker.event(listOf(p(900, false, true, 3), p(901, true, true, 5))) { sent += it; true }
        tracker.event(listOf(p(901, false, true, 6))) { sent += it; true }
        assertEquals(listOf(
            listOf(EmulatorTouchPoint(0, 1, 0, 512)),
            listOf(EmulatorTouchPoint(0, 2, 0, 512), EmulatorTouchPoint(1, 5, 0, 512)),
            listOf(EmulatorTouchPoint(0, 2, 0, 512), EmulatorTouchPoint(1, 5, 0, 512)),
            listOf(EmulatorTouchPoint(0, 3, 0, 0), EmulatorTouchPoint(1, 5, 0, 512)),
            listOf(EmulatorTouchPoint(1, 6, 0, 0)),
        ), sent.map { it.touches })
        // A refused down forgets its pointer.
        sent.clear()
        tracker.event(listOf(p(903, true, false, 1))) { false }
        tracker.event(listOf(p(903, true, true, 2))) { sent += it; true }
        assertTrue(sent.isEmpty())
    }

    private fun inputs(held: List<Call>) = held.filter { it.type == "emulator.input" }.map { it.fields["input"].toString() }
    private fun down(id: Int, x: Long = 1, y: Long = 1) = EmulatorTouchPoint(id, x, y, 512)
    private fun lift(id: Int, x: Long = 1, y: Long = 1) = EmulatorTouchPoint(id, x, y, 0)

    @Test fun touchStaysWithinTheTransportBudget() {
        assertTrue(EMULATOR_TOUCH_INTERVAL_MS >= 70)
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        // Pointer changes never coalesce; without host limits at most four wait for their acks.
        val events = listOf(touch(down(0)), touch(down(0), down(1)), touch(lift(0), down(1)), touch(lift(1)), touch(down(2)), touch(lift(2)))
        events.forEach { assertTrue(client.touch(it)) }
        scope.runCurrent()
        assertEquals(1, held.size)
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS - 10); scope.runCurrent()
        assertEquals(1, held.size)
        repeat(6) { scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent() }
        assertEquals(EMULATOR_TOUCH_MAX_IN_FLIGHT, held.size)
        assertEquals(4, EMULATOR_TOUCH_MAX_IN_FLIGHT)
        held.forEach { ack(it) }; scope.runCurrent()
        repeat(2) { scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent() }
        assertEquals(6, held.size)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun anAckBeforeThePumpTimerStillTakesTheLift() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.touch(touch(down(0)))); scope.runCurrent()
        assertTrue(client.touch(touch(down(0, y = 2))))
        ack(held.single()); scope.runCurrent()
        // Nothing in flight, a move waiting for the timer: the gesture still owns the pipeline.
        assertTrue(client.touch(touch(lift(0, y = 2))))
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent()
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent()
        assertEquals("""{"kind":"touch","touches":[{"id":0,"x":1,"y":2,"pressure":0}]}""", inputs(held).last())
        held.drop(1).forEach { ack(it) }; scope.runCurrent()
        assertFalse(client.view!!.inputPending)
        assertTrue(client.input(EmulatorInput.Key("Home")))
    }

    @Test fun hidingMidGestureLiftsEveryPointer() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.touch(touch(down(0)))); scope.runCurrent()
        assertTrue(client.touch(touch(down(0), down(1, x = 0, y = 2))))
        client.setVisible(false)
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent()
        // The second down never went out; one lift covers both pointers.
        assertEquals(listOf(
            """{"kind":"touch","touches":[{"id":0,"x":1,"y":1,"pressure":512}]}""",
            """{"kind":"touch","touches":[{"id":0,"x":1,"y":1,"pressure":0},{"id":1,"x":0,"y":2,"pressure":0}]}""",
        ), inputs(held))
        // The pane's own late lift has nothing left to lift.
        assertTrue(client.touch(touch(lift(0))))
        held.forEach { ack(it) }; scope.runCurrent()
        assertEquals(2, held.size)
        assertFalse(client.view!!.inputPending)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun aLiftIsClampedToTheCurrentGeometry() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.touch(touch(down(0)))); scope.runCurrent()
        ack(held.single()); scope.runCurrent()
        // The lift lands inside the 2 by 3 display.
        assertTrue(client.touch(touch(lift(0, x = 99, y = 99))))
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS); scope.runCurrent()
        assertEquals("""{"kind":"touch","touches":[{"id":0,"x":1,"y":2,"pressure":0}]}""", inputs(held).last())
    }

    @Test fun aLiftThatCannotGoOutGivesTheLeaseBack() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        var controls = true
        val client = controlling(scope, controls = true, held, closed, controlsNow = { controls })
        assertTrue(client.touch(touch(down(0)))); scope.runCurrent()
        ack(held.single()); scope.runCurrent()
        controls = false
        assertFalse(client.touch(touch(lift(0))))
        scope.runCurrent()
        assertEquals("emulator.release", held.last().type)
        assertFalse(client.view!!.ownsControl)
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun aRefreshThatMovesTheGenerationOnMidGestureDoesNotWedgeTheClient() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        var status: Int? = null
        val client = controlling(scope, controls = true, held, closed, statusGeneration = { status })
        assertTrue(client.touch(touch(down(0)))); scope.runCurrent()
        assertTrue(client.touch(touch(down(0, y = 2))))
        status = 3
        assertTrue(client.refresh()); scope.runCurrent()
        val view = client.view!!
        assertEquals(3L, view.generation)
        assertFalse(view.ownsControl)
        assertFalse(view.inputPending)
        // The touch in flight may or may not have landed.
        assertEquals(EmulatorNotice.UNCERTAIN, view.notice)
        // Its late acknowledgement belongs to the abandoned gesture.
        ack(held.single()); scope.runCurrent()
        scope.testScheduler.advanceTimeBy(EMULATOR_TOUCH_INTERVAL_MS * 2); scope.runCurrent()
        assertEquals(1, held.size)
        assertTrue(client.acquire())
        assertEquals(emptyList<Long>(), closed)
    }

    @Test fun keysAndTextWaitForARotation() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        val client = controlling(scope, controls = true, held, closed)
        assertTrue(client.rotate(1)); scope.runCurrent()
        assertTrue(client.view!!.rotating)
        assertFalse(client.input(EmulatorInput.Key("Home")))
        held.single().reply.complete(Wire.objectOf("kind" to "emulator.rotated", "emulatorId" to "e", "generation" to 2, "sequence" to 5, "status" to "accepted",
            "geometry" to Wire.objectOf("width" to 3, "height" to 2, "rotation" to 1, "revision" to 2)))
        scope.runCurrent()
        assertFalse(client.view!!.rotating)
        assertEquals(1, held.size)
    }

    @Test fun aCapabilityWithdrawnAfterOpenIsRefusedLocally() {
        val scope = TestScope(); val held = mutableListOf<Call>(); val closed = mutableListOf<Long>()
        var controls = true
        val client = controlling(scope, controls = true, held, closed, controlsNow = { controls })
        controls = false
        assertFalse(client.input(EmulatorInput.Key("Power")))
        assertFalse(client.input(EmulatorInput.Text("hi")))
        assertFalse(client.touch(touch(down(0))))
        assertFalse(client.rotate(1))
        scope.runCurrent()
        assertTrue(held.isEmpty())
        assertTrue(client.view!!.ownsControl)
    }

    @Test fun theTrackerKeepsARefusedLiftAndLiftsOnClear() {
        val tracker = EmulatorTouchTracker()
        val sent = mutableListOf<EmulatorInput.Touch>()
        fun p(key: Long, down: Boolean, was: Boolean, x: Long) = EmulatorTouchTracker.Pointer(key, down, was, x to 0L, x to 0L, 0.5f)
        tracker.event(listOf(p(1, true, false, 1))) { true }
        tracker.event(listOf(p(1, false, true, 2))) { false }
        // The next event of another finger lifts it again.
        tracker.event(listOf(p(2, true, false, 1))) { sent += it; true }
        assertEquals(listOf(EmulatorTouchPoint(1, 1, 0, 512), EmulatorTouchPoint(0, 2, 0, 0)), sent.single().touches)
        sent.clear()
        tracker.clear { sent += it; true }
        assertEquals(listOf(EmulatorTouchPoint(1, 1, 0, 0)), sent.single().touches)
        sent.clear()
        tracker.clear { sent += it; true }
        assertTrue(sent.isEmpty())
    }
}
