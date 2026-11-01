package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeExportRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val bytes = "<!doctype html><p>native snapshot</p>".toByteArray()
    private val digest = AttachmentImportRules.sha256(bytes)
    private val exportId = "AAAAAAAAAAAAAAAAAAAAAA"

    private class Pairings(private val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)
        override fun save(hosts: List<PairedHost>) = Unit
    }
    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()
        override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
    }
    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var capabilities = listOf(EXPORT_CAPABILITY, "session.export.native.v1")
        var session = Wire.objectOf("id" to "session", "origin" to "worker", "status" to "idle", "projectId" to "project", "nativeExport" to true)
        override fun connect(host: PairedHost) { listener.ready(emptySet()) }
        override fun pair(value: JsonObject) = Unit
        override fun close() = Unit
        override fun send(payload: JsonObject) {
            sent += payload
            val data = when (payload.text("type")) {
                "projects.list" -> Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))), "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)))
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session)))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session)
                "session.snapshot" -> Wire.objectOf("kind" to "snapshot", "sessionId" to "session", "revision" to 0, "status" to "idle", "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()))
                "session.export" -> Wire.objectOf("kind" to "export", "sessionId" to "session", "exportId" to exportId, "fileName" to "pi-session-20260101-120000.html", "mimeType" to "text/html", "totalBytes" to bytes.size, "sha256" to digest)
                "session.export.get" -> Wire.objectOf("kind" to "export.content", "sessionId" to "session", "exportId" to exportId, "offset" to payload.long("offset"), "totalBytes" to bytes.size, "sha256" to digest, "data" to Wire.encode(bytes))
                else -> Wire.objectOf("kind" to "accepted")
            }
            listener.message(Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to data))
        }
        fun exports() = sent.filter { it.text("type").startsWith("session.export") }
    }
    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository = DefaultRemoteRepository(Pairings(host), Drafts(), transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime })
        repository.activate(selection)
        runCurrent()
        return repository
    }
    private suspend fun TestScope.export(repository: DefaultRemoteRepository): ExportResult {
        val result = async { repository.exportSession("session") }
        advanceUntilIdle()
        return result.await()
    }
    @Test fun qualifiedNativeSessionUsesExistingExportCommands() = runTest {
        val transport = Transport()
        val result = export(connected(transport)) as ExportResult.Ready
        assertArrayEquals(bytes, result.bytes)
        assertEquals(listOf("session.export", "session.export.get"), transport.exports().map { it.text("type") })
        assertTrue(transport.exports().all { it.text("sessionId") == "session" })
    }
    @Test fun legacyHostAndUnqualifiedTerminalNeverSendExport() = runTest {
        val legacy = Transport().apply { capabilities = listOf(EXPORT_CAPABILITY) }
        assertEquals(ExportFailure.UNSUPPORTED, (export(connected(legacy)) as ExportResult.Failed).failure)
        assertTrue(legacy.exports().isEmpty())
        val terminal = Transport().apply { session = Wire.objectOf("id" to "session", "origin" to "tui", "status" to "idle", "projectId" to "project") }
        assertEquals(ExportFailure.UNSUPPORTED, (export(connected(terminal)) as ExportResult.Failed).failure)
        assertTrue(terminal.exports().isEmpty())
    }
}
