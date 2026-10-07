package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `session-tree-v1.json` wire entries and the tree gating, indent and error rules. */
class TreeContentTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-tree-v1.json")!!.readText()).jsonObject
    private val sessionId = "live-session"

    private fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    private fun kind(payload: JsonObject) = payload["data"]?.jsonObject?.text("kind")

    private fun node(
        id: String,
        parentId: String? = null,
        forkable: Boolean = true,
        kind: TreeNodeKind = TreeNodeKind.USER,
    ) = TreeNode(id, parentId, kind, "p", "2026-01-01T12:00:00.000Z", 0, forkable)

    private fun tree(leafId: String? = "a2", canNavigate: Boolean = true, nodes: List<TreeNode> = emptyList()) =
        SessionTree(sessionId, leafId, false, canNavigate, nodes)

    private val chat =
        RemoteState(
            connected = true,
            status = "idle",
            selection = RemoteSelection("host", "project", sessionId),
            capabilities = setOf(SESSION_TREE_CAPABILITY, SESSION_FORK_CAPABILITY),
            session = Wire.objectOf("id" to sessionId, "title" to "T", "origin" to "rpc"),
        )

    // ---- Fixture ------------------------------------------------------------------------------

    @Test
    fun validRequestsAreExactlyWhatTheBuildersProduce() {
        var checked = 0
        for ((name, payload) in payloads("wireValid").filter { it.second.text("type").startsWith("session.tree") }) {
            val fields =
                when (payload.text("type")) {
                    "session.tree" -> treeFields(payload.text("sessionId"))
                    "session.tree.navigate" ->
                        treeNavigateFields(
                            payload.text("sessionId"),
                            payload.text("nodeId"),
                            payload["summarize"]?.jsonPrimitive?.boolean ?: false,
                        )
                    else -> treeForkFields(payload.text("sessionId"), payload.text("nodeId"))
                }
            val built = Wire.objectOf("type" to payload.text("type"), "requestId" to payload.text("requestId"), *fields)
            // The navigate builder always names `summarize`; the host treats a missing one as false.
            val expected =
                if (payload.text("type") == "session.tree.navigate" && "summarize" !in payload)
                    JsonObject(payload + ("summarize" to JsonPrimitive(false)))
                else payload
            assertEquals(name, expected, built)
            checked++
        }
        assertEquals(5, checked)
    }

    @Test
    fun invalidNodeIdsCannotBeBuilt() {
        val invalid = payloads("wireInvalid").filter { it.second.text("type") != "session.tree" && it.second["nodeId"] != null && it.second.keys.size == 4 }
        assertTrue(invalid.map { it.first }.containsAll(listOf("navigate-empty-node", "navigate-node-with-space")))
        for ((name, payload) in invalid) {
            val nodeId = payload.text("nodeId")
            assertThrows(name, IllegalArgumentException::class.java) {
                if (payload.text("type") == "session.tree.navigate")
                    treeNavigateFields(payload.text("sessionId"), nodeId, false)
                else treeForkFields(payload.text("sessionId"), nodeId)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { treeNavigateFields(sessionId, "a".repeat(65), false) }
        assertThrows(IllegalArgumentException::class.java) { treeFields("") }
        assertTrue(validTreeNodeId("a-B_9"))
        assertFalse(validTreeNodeId("a.b"))
    }

    @Test
    fun validTreeResultsAreAcceptedAndInvalidOnesRejected() {
        val valid = payloads("wireValid").filter { kind(it.second) == "tree" }
        assertTrue(valid.size >= 6)
        for ((name, payload) in valid) {
            val data = payload.obj("data")
            val tree = validatedTree(data, sessionId)
            assertEquals(name, data.array("nodes").size, tree.nodes.size)
            assertEquals(name, data.flag("canNavigate"), tree.canNavigate)
            assertEquals(name, data.flag("truncated"), tree.truncated)
        }
        val invalid = payloads("wireInvalid").filter { kind(it.second) == "tree" }
        assertTrue(invalid.size >= 10)
        for ((name, payload) in invalid) {
            assertThrows(name, Exception::class.java) { validatedTree(payload.obj("data"), sessionId) }
        }
    }

    @Test
    fun branchedTreeKeepsItsStructure() {
        val data = payloads("wireValid").first { it.first == "tree-branched" }.second.obj("data")
        val tree = validatedTree(data, sessionId)
        assertEquals("a2", tree.leafId)
        assertEquals(listOf("u1", "a1", "u2", "a2", "t1"), tree.nodes.map { it.id })
        assertEquals(2, tree.nodes.first { it.id == "a1" }.children)
        assertFalse(tree.nodes.first { it.id == "t1" }.forkable)
        assertEquals(TreeNodeKind.TOOL, tree.nodes.first { it.id == "t1" }.kind)
        val all = validatedTree(payloads("wireValid").first { it.first == "tree-all-kinds" }.second.obj("data"), sessionId)
        assertEquals(TreeNodeKind.entries.toList(), all.nodes.map { it.kind })
        val empty = validatedTree(payloads("wireValid").first { it.first == "tree-empty" }.second.obj("data"), sessionId)
        assertNull(empty.leafId)
        assertTrue(empty.nodes.isEmpty())
    }

    @Test
    fun treeOfAnotherSessionIsRejected() {
        val data = payloads("wireValid").first { it.first == "tree-branched" }.second.obj("data")
        assertThrows(IllegalArgumentException::class.java) { validatedTree(data, "other") }
    }

    @Test
    fun navigatedResultsAreAcceptedAndInvalidOnesRejected() {
        val valid = payloads("wireValid").filter { kind(it.second) == "tree.navigated" }
        assertEquals(3, valid.size)
        val withText = validatedNavigation(valid.first { it.first == "navigated-text" }.second.obj("data"), sessionId)
        assertEquals(TreeNavigation("a1", "Try again"), withText)
        assertEquals(TreeNavigation("a1", null), validatedNavigation(valid.first { it.first == "navigated" }.second.obj("data"), sessionId))
        assertNull(validatedNavigation(valid.first { it.first == "navigated-empty-session" }.second.obj("data"), sessionId).leafId)
        val invalid = payloads("wireInvalid").filter { kind(it.second) == "tree.navigated" }
        assertEquals(3, invalid.size)
        for ((name, payload) in invalid) {
            assertThrows(name, Exception::class.java) { validatedNavigation(payload.obj("data"), sessionId) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedNavigation(valid.first().second.obj("data"), "other")
        }
    }

    @Test
    fun forkResultsAcceptTheAtModeAndRejectBadShapes() {
        val results = payloads("wireValid").filter { kind(it.second) == "fork" }
        assertEquals(setOf("fork-at", "fork-edit"), results.map { it.first }.toSet())
        val all = setOf("edit", "at")
        for ((name, payload) in results) {
            val data = payload.obj("data")
            val fork = validatedFork(data, data.text("sourceSessionId"), "project", all)
            assertEquals(name, data.text("mode"), fork.mode)
            assertEquals("forked", fork.session.text("id"))
        }
        val at = results.first { it.first == "fork-at" }.second.obj("data")
        assertNull(validatedFork(at, at.text("sourceSessionId"), "project", all).text)
        // `at` is not a mode of a message fork, and another project or source is no fork of ours.
        assertThrows(IllegalArgumentException::class.java) { validatedFork(at, at.text("sourceSessionId"), "project", setOf("edit")) }
        assertThrows(IllegalArgumentException::class.java) { validatedFork(at, "other", "project", all) }
        assertThrows(IllegalArgumentException::class.java) { validatedFork(at, at.text("sourceSessionId"), "other", all) }
        val invalid = payloads("wireInvalid").filter { kind(it.second) == "fork" }
        assertEquals(3, invalid.size)
        for ((name, payload) in invalid) {
            val data = payload.obj("data")
            assertThrows(name, Exception::class.java) {
                validatedFork(data, data.text("sourceSessionId"), "project", setOf("edit", "at", "retry"))
            }
        }
    }

    // ---- Gating -------------------------------------------------------------------------------

    @Test
    fun treeIsShownWhenHostAdvertisesItAndASessionIsSelected() {
        assertTrue(canShowTree(chat))
        assertFalse(canShowTree(chat.copy(capabilities = emptySet())))
        assertFalse(canShowTree(chat.copy(unavailableCapabilities = setOf(SESSION_TREE_CAPABILITY))))
        assertFalse(canShowTree(chat.copy(connected = false)))
        assertFalse(canShowTree(chat.copy(loading = true)))
        assertFalse(canShowTree(chat.copy(selection = RemoteSelection("host", "project", null))))
        // A read: every session state works.
        for (status in listOf("running", "waiting", "offline"))
            assertTrue(status, canShowTree(chat.copy(status = status)))
    }

    @Test
    fun continueInPlaceNeedsTheHostsGoAheadAnIdleSessionAndAnotherNode() {
        val nodes = listOf(node("u1"), node("a2", "u1"))
        val navigable = tree(nodes = nodes)
        assertTrue(canContinueInPlace(chat, navigable, nodes[0]))
        assertEquals(TreeContinue.ALREADY_HERE, continueAvailability(chat, navigable, nodes[1]))
        assertFalse(canContinueInPlace(chat, navigable, nodes[1]))
        assertEquals(TreeContinue.NOT_NAVIGABLE, continueAvailability(chat, tree(canNavigate = false, nodes = nodes), nodes[0]))
        for (status in listOf("running", "waiting", "offline"))
            assertEquals(status, TreeContinue.NOT_IDLE, continueAvailability(chat.copy(status = status), navigable, nodes[0]))
        assertEquals(TreeContinue.NOT_IDLE, continueAvailability(chat.copy(sending = true), navigable, nodes[0]))
        assertEquals(TreeContinue.NOT_NAVIGABLE, continueAvailability(chat.copy(connected = false), navigable, nodes[0]))
        assertEquals(TreeContinue.NOT_NAVIGABLE, continueAvailability(chat.copy(loading = true), navigable, nodes[0]))
        assertEquals(TreeContinue.NOT_NAVIGABLE, continueAvailability(chat.copy(capabilities = emptySet()), navigable, nodes[0]))
        // An empty session has no leaf to be at.
        assertTrue(canContinueInPlace(chat, tree(leafId = null, nodes = nodes), nodes[0]))
    }

    @Test
    fun forkFromANodeFollowsTheRewindRulesAndTheNodeFlag() {
        val forkable = node("a1", forkable = true)
        val tool = node("t1", forkable = false)
        assertTrue(canForkFromNode(chat, forkable))
        assertFalse(canForkFromNode(chat, tool))
        assertFalse(canForkFromNode(chat.copy(capabilities = setOf(SESSION_TREE_CAPABILITY)), forkable))
        assertFalse(canForkFromNode(chat.copy(capabilities = setOf(SESSION_FORK_CAPABILITY)), forkable))
        assertFalse(canForkFromNode(chat.copy(status = "running"), forkable))
        assertFalse(canForkFromNode(chat.copy(status = "waiting"), forkable))
        assertTrue(canForkFromNode(chat.copy(status = "offline"), forkable))
        val history = chat.copy(status = "running", session = Wire.objectOf("id" to sessionId, "origin" to "history"))
        assertTrue(canForkFromNode(history, forkable))
        val child = chat.copy(session = Wire.objectOf("id" to sessionId, "origin" to "rpc", "parentSessionId" to "p"))
        assertFalse(canForkFromNode(child, forkable))
        assertFalse(canForkFromNode(chat.copy(connected = false), forkable))
        assertFalse(canForkFromNode(chat.copy(loading = true), forkable))
    }

    // ---- Indent and draft ---------------------------------------------------------------------

    @Test
    fun indentFollowsDepthAndStopsAtTheCap() {
        val nodes = (0..9).map { node("n$it", if (it == 0) null else "n${it - 1}") } + node("side", "n1") + node("root2")
        val depths = treeDepths(nodes)
        assertEquals(0, depths["n0"])
        assertEquals(3, depths["n3"])
        assertEquals(9, depths["n9"])
        assertEquals(2, depths["side"])
        assertEquals(0, depths["root2"])
        assertEquals(listOf(0, 1, 5, 6, 6, 6), listOf(0, 1, 5, 6, 7, 9).map(::treeIndent))
        assertEquals(0, treeIndent(-1))
        assertEquals(MAX_TREE_INDENT, treeIndent(Int.MAX_VALUE))
    }

    @Test
    fun navigatedTextBecomesAComposerDraft() {
        assertNull(treeDraft(null))
        assertNull(treeDraft(""))
        assertEquals(ForkDraft("Try again", null, false), treeDraft("Try again"))
        val quote = MessageQuote("assistant-2", "assistant", "earlier")
        assertEquals(ForkDraft("again", quote, false), treeDraft(QuoteCodec.encode("again", quote)))
        assertEquals("/review", treeDraft("/review")?.text)
    }

    @Test
    fun timestampsParseOrFallBack() {
        assertEquals(1767268800000L, treeTimestampMillis("2026-01-01T12:00:00.000Z"))
        assertNull(treeTimestampMillis("yesterday"))
    }

    // ---- Errors -------------------------------------------------------------------------------

    @Test
    fun errorCodesMapToFailuresAndTexts() {
        val expected =
            mapOf(
                "busy" to (TreeFailure.BUSY to R.string.remote_tree_busy),
                "cancelled" to (TreeFailure.CANCELLED to R.string.remote_tree_cancelled),
                "unsupported" to (TreeFailure.UNSUPPORTED to R.string.remote_tree_unsupported),
                "not_found" to (TreeFailure.NOT_FOUND to R.string.remote_tree_not_found),
                "invalid_request" to (TreeFailure.INVALID to R.string.remote_tree_invalid),
                "timeout" to (TreeFailure.UNKNOWN_RESULT to R.string.remote_tree_unknown_result),
                "internal" to (TreeFailure.UNKNOWN_RESULT to R.string.remote_tree_unknown_result),
                "offline" to (TreeFailure.OFFLINE to R.string.remote_tree_offline),
                "forbidden" to (TreeFailure.FAILED to R.string.remote_tree_failed),
            )
        for ((code, pair) in expected) {
            val failure = treeFailure(RemoteRequestException(code))
            assertEquals(code, pair.first, failure)
            assertEquals(code, pair.second, treeFailureText(failure))
        }
        assertEquals(TreeFailure.UNKNOWN_RESULT, treeFailure(IllegalStateException("Request timed out")))
        assertEquals(TreeFailure.FAILED, treeFailure(IllegalStateException("other")))
        assertEquals(TreeFailure.FAILED, treeFailure(RuntimeException()))
        assertEquals(TreeFailure.PROTOCOL, treeFailure(TreeException(TreeFailure.PROTOCOL)))
        assertEquals(R.string.remote_tree_failed, treeFailureText(TreeFailure.PROTOCOL))
        assertTrue(treeResultUnknown(TreeFailure.UNKNOWN_RESULT))
        assertFalse(treeResultUnknown(TreeFailure.BUSY))
    }

    @Test
    fun forkFromANodeHasItsOwnErrors() {
        assertEquals(R.string.remote_fork_busy, treeForkError("busy"))
        assertEquals(R.string.remote_tree_unsupported, treeForkError("unsupported"))
        assertEquals(R.string.remote_tree_not_found, treeForkError("not_found"))
        assertEquals(R.string.remote_tree_fork_invalid, treeForkError("invalid_request"))
        assertEquals(R.string.remote_request_error, treeForkError("internal"))
        assertEquals(R.string.remote_request_error, treeForkError(null))
    }
}
