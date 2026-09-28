package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SubagentControlTest {
    private fun result(vararg fields: Pair<String, Any?>) =
        Wire.objectOf("kind" to "subagent.control", "sessionId" to "child", *fields)

    private fun childState(
        status: String = "idle",
        capabilities: Set<String> = setOf(SUBAGENT_CONTROL_CAPABILITY),
        control: ChildControl? = null,
        parent: String? = "parent",
    ): RemoteState {
        val session = Wire.objectOf("id" to "child", "origin" to "tui", "status" to status)
        return RemoteState(
            selection = RemoteSelection("host", "project", "child"),
            connected = true,
            session =
                if (parent == null) session
                else JsonObject(session + ("parentSessionId" to JsonPrimitive(parent))),
            status = status,
            capabilities = capabilities,
            childControls = control?.let { mapOf("child" to it) } ?: emptyMap(),
        )
    }

    @Test
    fun parsesEachStatusAndKeepsFieldsToTheirStatus() {
        assertEquals(
            SubagentControlResult("accepted", "agent-2", null),
            subagentControlResult(result("status" to "accepted", "agentId" to "agent-2", "reason" to "x"), "child"),
        )
        assertEquals(
            SubagentControlResult("refused", null, "Dispatch authority missing"),
            subagentControlResult(result("status" to "refused", "reason" to "Dispatch authority missing"), "child"),
        )
        assertEquals(SubagentControlResult("not_found", null, null),
            subagentControlResult(result("status" to "not_found"), "child"))
        assertEquals("unknown", subagentControlResult(result("status" to "paused"), "child").status)
    }

    @Test
    fun rejectsAnotherKindOrSession() {
        assertThrows(IllegalArgumentException::class.java) {
            subagentControlResult(Wire.objectOf("kind" to "accepted", "sessionId" to "child", "status" to "accepted"), "child")
        }
        assertThrows(IllegalArgumentException::class.java) {
            subagentControlResult(result("status" to "accepted"), "other")
        }
    }

    @Test
    fun controlsNeedAChildAndTheCapability() {
        assertTrue(childControlsAvailable(childState()))
        assertFalse(childControlsAvailable(childState(parent = null)))
        assertFalse(childControlsAvailable(childState(capabilities = setOf(STEER_CAPABILITY))))
        assertFalse(
            childControlsAvailable(
                childState().copy(unavailableCapabilities = setOf(SUBAGENT_CONTROL_CAPABILITY))
            )
        )
        assertFalse(canSteer(childState(status = "running", capabilities = emptySet())))
        assertTrue(canSteer(childState(status = "running")))
    }

    @Test
    fun composerOffersResumeWhenTheChildIsNotRunning() {
        assertTrue(offersChildResume(childState()))
        assertTrue(offersChildResume(childState(status = "offline")))
        assertFalse(offersChildResume(childState(status = "running")))
        assertTrue(
            offersChildResume(childState(status = "running", control = ChildControl(ChildControlPhase.NOT_RUNNING)))
        )
        assertFalse(offersChildResume(childState(capabilities = emptySet())))
    }

    @Test
    fun statusLineFollowsThePhase() {
        assertEquals(R.string.remote_child_idle, childControlStatus(childState()))
        assertNull(childControlStatus(childState(status = "running")))
        assertEquals(
            R.string.remote_child_stopped_by_you,
            childControlStatus(childState(control = ChildControl(ChildControlPhase.STOPPED_BY_YOU))),
        )
        assertEquals(
            R.string.remote_child_stopping,
            childControlStatus(childState(status = "running", control = ChildControl(ChildControlPhase.STOPPING))),
        )
        assertEquals(
            R.string.remote_child_refused_plain,
            childControlStatus(childState(control = ChildControl(ChildControlPhase.REFUSED, "No"))),
        )
        assertEquals(
            R.string.remote_child_idle,
            childControlStatus(childState(control = ChildControl(ChildControlPhase.RESUMED, agentId = "a"))),
        )
        assertEquals(
            R.string.remote_child_not_found,
            childControlStatus(childState(control = ChildControl(ChildControlPhase.NOT_FOUND))),
        )
    }
}
