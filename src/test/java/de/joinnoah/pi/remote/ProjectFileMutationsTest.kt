package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The file mutation wire against `files-mutations-v1.json`. The fixture is replaced by the Host's
 * copy byte for byte, so the tests walk its `valid` group by type and kind and need no particular
 * entry; negative cases live here.
 */
class ProjectFileMutationsTest {
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-mutations-v1.json")!!.readText()).jsonObject
    }

    private val payloads by lazy { fixture.getValue("valid").jsonArray.map { it.jsonObject.obj("payload") } }

    private val mutationCommands = setOf("session.files.create", "session.files.rename", "session.files.delete")

    private fun commands(type: String) = payloads.filter { it.text("type") == type }

    private fun results(kind: String) =
        payloads.filter { it.text("type") == "result" && it.flag("ok") }.map { it.obj("data") }.filter { it.optionalText("kind") == kind }

    private fun request(command: JsonObject) =
        FileMutationRequest(
            routeId = "route",
            deviceId = "device",
            sessionId = command.text("sessionId"),
            operationId = command.text("operationId"),
            epoch = command.text("epoch"),
            kind = FileMutationKind.entries.single { it.command == command.text("type") },
            path = command.text("path"),
            destinationPath = command.optionalText("destinationPath"),
            baseVersion = command.optionalText("baseVersion"),
        )

    @Test
    fun everyMutationCommandIsBuiltExactlyFromItsRequest() {
        val sent = payloads.filter { it.text("type") in mutationCommands }
        assertEquals(mutationCommands, sent.map { it.text("type") }.toSet())
        for (command in sent) {
            val request = request(command)
            assertTrue(validMutationRequest(request))
            val (type, fields) = fileMutationCommand(request)
            assertEquals(command.text("type"), type)
            assertEquals(JsonObject(command - "type" - "requestId"), Wire.objectOf(*fields.toTypedArray()))
        }
    }

    @Test
    fun statusListAndAcknowledgeCommandsMatchTheFixture() {
        for (type in listOf("session.files.mutation.status", "session.files.mutation.acknowledge")) {
            val command = commands(type).also { assertTrue(type, it.isNotEmpty()) }.first()
            val fields = fileMutationStatusFields(command.text("sessionId"), command.text("operationId"), command.text("epoch"))
            assertEquals(JsonObject(command - "type" - "requestId"), Wire.objectOf(*fields.toTypedArray()))
        }
        val lists = commands("session.files.mutation.list")
        assertTrue(lists.isNotEmpty())
        for (command in lists) {
            val fields = fileMutationListFields(command.text("sessionId"), command.optionalText("after"))
            assertEquals(JsonObject(command - "type" - "requestId"), Wire.objectOf(*fields.toTypedArray()))
        }
    }

    @Test
    fun everyFixtureRecordParsesAgainstItsOwnIdentity() {
        val records = results("files.mutation")
        assertTrue(records.isNotEmpty())
        for (data in records) {
            val record = parseFileMutationRecord(
                data, sessionId, data.text("operationId"),
                FileMutationKind.entries.single { it.wire == data.text("operation") },
                data.text("path"), data.optionalText("destinationPath"),
            )
            assertEquals(data.text("state"), record.state.wire)
            assertEquals(data.optionalText("reason"), record.reason?.wire)
        }
    }

    @Test
    fun everyFixtureListParsesWithItsEpoch() {
        val lists = results("files.mutation.list")
        assertTrue(lists.isNotEmpty())
        for (data in lists) {
            val list = parseFileMutationList(data, sessionId)
            assertEquals(data.text("epoch"), list.epoch)
            assertEquals(data.array("operations").size, list.operations.size)
            assertEquals(data.optionalText("nextAfter"), list.nextAfter)
        }
    }

    private val operationId = "b3BlcmF0aW9uLTAwMDItYg"
    private val epoch = "am91cm5hbC1lcG9jaC0wMQ"
    private val version = "YmFzZS12ZXJzaW9uLTAxeA"

    private val renamed = Wire.objectOf(
        "kind" to "files.mutation", "sessionId" to sessionId, "operationId" to operationId, "operation" to "rename",
        "path" to "src/old.ts", "destinationPath" to "src/new.ts", "state" to "applied", "acknowledged" to false,
    )

    private fun parseRenamed(data: JsonObject) =
        parseFileMutationRecord(data, sessionId, operationId, FileMutationKind.RENAME, "src/old.ts", "src/new.ts")

    private fun with(vararg values: Pair<String, Any?>) = JsonObject(renamed + Wire.objectOf(*values))

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test
    fun recordRejectsAnythingOutsideTheContract() {
        assertEquals(FileMutationHostState.APPLIED, parseRenamed(renamed).state)
        fails { parseRenamed(with("extra" to 1)) }
        fails { parseRenamed(JsonObject(renamed - "acknowledged")) }
        fails { parseRenamed(with("kind" to "files.written")) }
        fails { parseRenamed(with("sessionId" to "other")) }
        fails { parseRenamed(with("operationId" to "b3BlcmF0aW9uLTAwMDEtYQ")) }
        fails { parseRenamed(with("destinationPath" to "src/other.ts")) }
        fails { parseRenamed(JsonObject(renamed - "destinationPath")) }
        fails { parseRenamed(with("state" to "done")) }
        // reason exactly with refused, and only a known one.
        fails { parseRenamed(with("reason" to "exists")) }
        fails { parseRenamed(with("state" to "refused")) }
        fails { parseRenamed(with("state" to "refused", "reason" to "busy")) }
        assertEquals(FileMutationRefusal.EPOCH, parseRenamed(with("state" to "refused", "reason" to "epoch")).reason)
        // acknowledged only with unknown, and a real boolean.
        fails { parseRenamed(with("acknowledged" to true)) }
        fails { parseRenamed(with("acknowledged" to "false")) }
        assertTrue(parseRenamed(with("state" to "unknown", "acknowledged" to true)).acknowledged)
        // A create or delete record carries no destination.
        fails {
            parseFileMutationRecord(with("operation" to "delete"), sessionId, operationId, FileMutationKind.DELETE, "src/old.ts", null)
        }
    }

    @Test
    fun listRejectsAnythingOutsideTheContract() {
        val entry = Wire.objectOf("operationId" to operationId, "operation" to "delete", "path" to "a.txt", "state" to "unknown", "acknowledged" to false)
        fun list(vararg operations: JsonObject, extra: Map<String, JsonElement> = emptyMap()) =
            JsonObject(Wire.objectOf("kind" to "files.mutation.list", "sessionId" to sessionId, "epoch" to epoch, "operations" to JsonArray(operations.toList())) + extra)
        assertEquals(epoch, parseFileMutationList(list(entry), sessionId).epoch)
        fails { parseFileMutationList(list(JsonObject(entry + ("state" to JsonPrimitive("applied")))), sessionId) }
        fails { parseFileMutationList(list(JsonObject(entry + ("state" to JsonPrimitive("refused")) + ("reason" to JsonPrimitive("exists")))), sessionId) }
        fails { parseFileMutationList(list(entry, entry), sessionId) }
        fails { parseFileMutationList(list(JsonObject(entry + ("kind" to JsonPrimitive("files.mutation")))), sessionId) }
        fails { parseFileMutationList(list(entry, extra = mapOf("epoch" to JsonPrimitive("short"))), sessionId) }
        fails { parseFileMutationList(list(entry, extra = mapOf("nextAfter" to JsonPrimitive("b3BlcmF0aW9uLTAwMDItYh"))), sessionId) }
        fails { parseFileMutationList(list(entry), "other") }
        val many = (0 until 101).map { index ->
            JsonObject(entry + ("operationId" to JsonPrimitive(Wire.encode(ByteArray(16) { index.toByte() }))))
        }
        fails { parseFileMutationList(list(*many.toTypedArray()), sessionId) }
        assertEquals(100, parseFileMutationList(list(*many.take(100).toTypedArray()), sessionId).operations.size)
    }

    @Test
    fun requestsMustFitTheContractBeforeTheyAreSent() {
        val rename = FileMutationRequest("route", "device", sessionId, operationId, epoch, FileMutationKind.RENAME, "src/old.ts", "src/new.ts", version)
        assertTrue(validMutationRequest(rename))
        assertFalse(validMutationRequest(rename.copy(destinationPath = "lib/new.ts")))
        assertFalse(validMutationRequest(rename.copy(destinationPath = "src/old.ts")))
        assertFalse(validMutationRequest(rename.copy(baseVersion = null)))
        assertFalse(validMutationRequest(rename.copy(operationId = "operation")))
        assertFalse(validMutationRequest(rename.copy(epoch = "b3BlcmF0aW9uLTAwMDItYh")))
        val create = FileMutationRequest("route", "device", sessionId, operationId, epoch, FileMutationKind.CREATE, "docs/new.md")
        assertTrue(validMutationRequest(create))
        assertFalse(validMutationRequest(create.copy(baseVersion = version)))
        for (path in listOf("", "/abs", "a//b", "a/./b", "a/../b", "a\\b", "a\u0000b", "x".repeat(4097))) {
            assertFalse(path, validMutationRequest(create.copy(path = path)))
        }
        assertFalse(validMutationName("a/b"))
        assertTrue(validMutationName("notes.md"))
    }
}
