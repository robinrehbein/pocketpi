package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class FileDraftKey(val routeId: String, val sessionId: String, val path: String)

data class StoredFileDraft(
    val baseVersion: String,
    val original: String,
    val text: String,
    val pending: List<PendingWrite> = emptyList(),
)

interface FileDraftStorage {
    fun load(): Map<FileDraftKey, StoredFileDraft>
    fun save(drafts: Map<FileDraftKey, StoredFileDraft>)
    fun takeReset(): Boolean = false
}

/** An unreadable encrypted journal must never be mistaken for an empty one. */
class FileDraftStore(context: Context) : FileDraftStorage {
    private var verified = false
    private val file = EncryptedFileStore(context, "file-drafts.enc", "pi-remote-file-drafts-v1", preserveUnreadable = true)

    override fun load(): Map<FileDraftKey, StoredFileDraft> {
        val bytes = file.read()
        val result = if (bytes == null) emptyMap() else parseFileDrafts(bytes)
        verified = true
        return result
    }

    override fun save(drafts: Map<FileDraftKey, StoredFileDraft>) {
        if (!verified) load()
        val bytes = encodeFileDrafts(drafts)
        file.write(bytes)
    }
}

/** One owner serializes all reads and read-modify-write operations. */
class FileDraftJournal(
    private val storage: FileDraftStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded: Map<FileDraftKey, StoredFileDraft>? = null

    private fun current(): Map<FileDraftKey, StoredFileDraft> =
        loaded ?: storage.load().mapValues { (_, value) -> value.copy(pending = value.pending.toList()) }
            .also { loaded = it }

    private fun persist(updated: Map<FileDraftKey, StoredFileDraft>) {
        try {
            storage.save(updated)
        } catch (e: Exception) {
            // A failed verification can follow a successful commit. Reload before mutating.
            loaded = null
            throw e
        }
        loaded = updated
    }

    suspend fun get(key: FileDraftKey): StoredFileDraft? = withContext(dispatcher) {
        mutex.withLock { current()[key]?.let { it.copy(pending = it.pending.toList()) } }
    }

    suspend fun put(key: FileDraftKey, draft: StoredFileDraft) = withContext(dispatcher) {
        mutex.withLock {
            val updated = current() + (key to draft.copy(pending = draft.pending.toList()))
            // Validate even when a test or alternate implementation supplies the storage.
            encodeFileDrafts(updated)
            persist(updated)
        }
    }

    suspend fun remove(key: FileDraftKey) = withContext(dispatcher) {
        mutex.withLock {
            val updated = current() - key
            persist(updated)
        }
    }
}

private const val FILE_EDITOR_DRAFT_MAX_BYTES = 1024 * 1024
private const val FILE_EDITOR_JOURNAL_MAX_BYTES = 8 * 1024 * 1024

private fun validIdentity(value: String, max: Int): Boolean =
    value.isNotBlank() && value.encodeToByteArray().size <= max && value.none { it.isISOControl() }

private fun validateFileDrafts(drafts: Map<FileDraftKey, StoredFileDraft>) {
    require(drafts.size <= 8)
    drafts.forEach { (key, draft) ->
        require(validIdentity(key.routeId, 1024) && validIdentity(key.sessionId, 1024))
        require(validIdentity(key.path, 4096) && !key.path.startsWith('/') && '\\' !in key.path)
        require(key.path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        require(validIdentity(draft.baseVersion, 1024))
        require(draft.original.encodeToByteArray().size <= FILE_EDITOR_DRAFT_MAX_BYTES)
        require(draft.text.encodeToByteArray().size <= FILE_EDITOR_DRAFT_MAX_BYTES)
        require(draft.pending.size <= 16 && draft.pending.map { it.requestId }.distinct().size == draft.pending.size)
        draft.pending.forEach {
            require(validIdentity(it.requestId, 1024) && validIdentity(it.baseVersion, 1024))
            require(filesContentFits(it.content))
        }
    }
}

private fun encodeFileDrafts(drafts: Map<FileDraftKey, StoredFileDraft>): ByteArray {
    validateFileDrafts(drafts)
    val bytes = JsonArray(drafts.map { (key, draft) ->
        Wire.objectOf(
            "routeId" to key.routeId, "sessionId" to key.sessionId, "path" to key.path,
            "baseVersion" to draft.baseVersion, "original" to draft.original, "text" to draft.text,
            "pending" to JsonArray(draft.pending.map {
                Wire.objectOf("requestId" to it.requestId, "baseVersion" to it.baseVersion, "content" to it.content)
            }),
        )
    }).toString().encodeToByteArray()
    require(bytes.size <= FILE_EDITOR_JOURNAL_MAX_BYTES)
    return bytes
}

private fun parseFileDrafts(bytes: ByteArray): Map<FileDraftKey, StoredFileDraft> {
    require(bytes.size <= FILE_EDITOR_JOURNAL_MAX_BYTES)
    val result = mutableMapOf<FileDraftKey, StoredFileDraft>()
    Wire.json.parseToJsonElement(Wire.utf8(bytes)).jsonArray.forEach { element ->
        val item = element.jsonObject
        require(item.keys == setOf("routeId", "sessionId", "path", "baseVersion", "original", "text", "pending"))
        val key = FileDraftKey(item.text("routeId"), item.text("sessionId"), item.text("path"))
        val pending = item.getValue("pending").jsonArray.map {
            val entry = it.jsonObject
            require(entry.keys == setOf("requestId", "baseVersion", "content"))
            PendingWrite(entry.text("requestId"), entry.text("baseVersion"), entry.text("content"))
        }
        require(result.put(key, StoredFileDraft(item.text("baseVersion"), item.text("original"), item.text("text"), pending)) == null)
    }
    validateFileDrafts(result)
    return result
}
