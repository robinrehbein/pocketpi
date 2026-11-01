package de.joinnoah.pi.remote

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class PagedJournalKey(val routeId: String, val sessionId: String, val questionId: String)
data class StoredPagedDraft(val text: String, val editedAt: Long)
data class StoredPagedAttempt(
    val key: PagedJournalKey,
    val attemptId: String,
    val grantId: String,
    val uploadId: String,
    val commitRequestId: String,
    val text: String,
    val createdAt: Long,
)
data class PagedJournalSnapshot(
    val drafts: Map<PagedJournalKey, StoredPagedDraft> = emptyMap(),
    val attempts: Map<String, StoredPagedAttempt> = emptyMap(),
)
interface PagedEditorStorage {
    fun load(): PagedJournalSnapshot
    fun save(snapshot: PagedJournalSnapshot)
}

/** Owns an independent encrypted journal. An unreadable file must never become an empty journal. */
class PagedEditorStore(context: Context) : PagedEditorStorage {
    private val file = EncryptedFileStore(context, "paged-editor-journal.enc", "pi-remote-paged-editor-journal-v1", preserveUnreadable = true)
    private var verified = false

    override fun load(): PagedJournalSnapshot {
        val result = file.read()?.let(::decodePagedJournal) ?: PagedJournalSnapshot()
        verified = true
        return result
    }

    override fun save(snapshot: PagedJournalSnapshot) {
        if (!verified) load()
        file.write(encodePagedJournal(snapshot))
    }
}

/** A single application owner serializes mutations and publishes them only after durable storage. */
class PagedEditorJournal(
    private val storage: PagedEditorStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded: PagedJournalSnapshot? = null

    private fun current(): PagedJournalSnapshot = loaded ?: storage.load().let {
        validatePagedJournal(it)
        PagedJournalSnapshot(it.drafts.toMap(), it.attempts.toMap()).also { snapshot -> loaded = snapshot }
    }

    suspend fun snapshot(): PagedJournalSnapshot = withContext(dispatcher) {
        mutex.withLock { current().let { PagedJournalSnapshot(it.drafts.toMap(), it.attempts.toMap()) } }
    }

    private fun persist(updated: PagedJournalSnapshot) {
        // Validate alternate storage implementations too, including the full encoded envelope.
        encodePagedJournal(updated)
        storage.save(updated)
        loaded = updated
    }

    suspend fun putDraft(key: PagedJournalKey, draft: StoredPagedDraft): Unit = withContext(dispatcher) {
        mutex.withLock { persist(current().let { it.copy(drafts = it.drafts + (key to draft)) }) }
    }

    suspend fun reserve(attempt: StoredPagedAttempt): Unit = withContext(dispatcher) {
        mutex.withLock {
            val state = current()
            val existing = state.attempts[attempt.attemptId]
            if (existing != null) {
                require(existing == attempt) { "Attempt ID already reserved" }
                return@withLock
            }
            require(state.attempts.values.none { it.key == attempt.key }) { "Question already has an unresolved attempt" }
            persist(state.copy(
                drafts = state.drafts + (attempt.key to StoredPagedDraft(attempt.text, attempt.createdAt)),
                attempts = state.attempts + (attempt.attemptId to attempt),
            ))
        }
    }

    suspend fun removeAttempt(attemptId: String): Unit = withContext(dispatcher) {
        mutex.withLock { persist(current().let { it.copy(attempts = it.attempts - attemptId) }) }
    }

    suspend fun removeDraft(key: PagedJournalKey): Unit = withContext(dispatcher) {
        mutex.withLock { persist(current().let { it.copy(drafts = it.drafts - key) }) }
    }
}

// Raw UTF-16 limits match the prior cache; the separately encoded envelope is bounded below
// EncryptedFileStore's 32 MiB ceiling. Admission may therefore fail before a count limit.
private const val PAGED_JOURNAL_MAX_DRAFT_BYTES = 4 * 1024 * 1024
private const val PAGED_JOURNAL_MAX_DRAFT_TOTAL = 8 * 1024 * 1024
private const val PAGED_JOURNAL_MAX_ATTEMPT_TOTAL = 8 * 1024 * 1024
private const val PAGED_JOURNAL_MAX_ENCODED_BYTES = 24 * 1024 * 1024
private const val PAGED_JOURNAL_MAX_ID_LENGTH = 1024

private fun validPagedId(id: String): Boolean {
    if (id.isBlank() || id.length > PAGED_JOURNAL_MAX_ID_LENGTH || id.any { it.isISOControl() }) return false
    var i = 0
    while (i < id.length) {
        val c = id[i++]
        if (c.isHighSurrogate()) {
            if (i == id.length || !id[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
private fun validatePagedJournal(snapshot: PagedJournalSnapshot) {
    require(snapshot.drafts.size <= 32 && snapshot.attempts.size <= 32)
    fun validKey(key: PagedJournalKey) = validPagedId(key.routeId) && validPagedId(key.sessionId) && validPagedId(key.questionId)
    var draftBytes = 0L
    snapshot.drafts.forEach { (key, draft) ->
        require(validKey(key) && draft.editedAt >= 0)
        val bytes = draft.text.length.toLong() * 2
        require(bytes <= PAGED_JOURNAL_MAX_DRAFT_BYTES)
        draftBytes += bytes
    }
    require(draftBytes <= PAGED_JOURNAL_MAX_DRAFT_TOTAL)
    var attemptBytes = 0L
    val keys = mutableSetOf<PagedJournalKey>()
    snapshot.attempts.forEach { (id, attempt) ->
        require(id == attempt.attemptId && validKey(attempt.key) && keys.add(attempt.key))
        require(listOf(id, attempt.grantId, attempt.uploadId, attempt.commitRequestId).all(::validPagedId))
        require(attempt.createdAt >= 0)
        attemptBytes += attempt.text.length.toLong() * 2
    }
    require(attemptBytes <= PAGED_JOURNAL_MAX_ATTEMPT_TOTAL)
}

// Encode Java/Kotlin UTF-16 code units directly: charset encoders replace isolated surrogates.
private fun encodeText(value: String): String {
    val bytes = ByteArray(value.length * 2)
    value.forEachIndexed { i, c -> bytes[i * 2] = (c.code ushr 8).toByte(); bytes[i * 2 + 1] = c.code.toByte() }
    return Base64.getEncoder().encodeToString(bytes)
}
private fun decodeText(value: String): String {
    val bytes = Base64.getDecoder().decode(value)
    require(bytes.size % 2 == 0 && Base64.getEncoder().encodeToString(bytes) == value)
    return CharArray(bytes.size / 2) { i -> (((bytes[i * 2].toInt() and 255) shl 8) or (bytes[i * 2 + 1].toInt() and 255)).toChar() }.concatToString()
}

// Fixed-shape arrays avoid ambiguous duplicate JSON object members. Duplicate record keys are rejected.
private fun encodePagedJournal(snapshot: PagedJournalSnapshot): ByteArray {
    validatePagedJournal(snapshot)
    fun key(k: PagedJournalKey) = listOf(JsonPrimitive(k.routeId), JsonPrimitive(k.sessionId), JsonPrimitive(k.questionId))
    val drafts = snapshot.drafts.map { (k, d) -> JsonArray(key(k) + listOf(JsonPrimitive(encodeText(d.text)), JsonPrimitive(d.editedAt))) }
    val attempts = snapshot.attempts.values.map { a -> JsonArray(key(a.key) + listOf(JsonPrimitive(a.attemptId), JsonPrimitive(a.grantId), JsonPrimitive(a.uploadId), JsonPrimitive(a.commitRequestId), JsonPrimitive(encodeText(a.text)), JsonPrimitive(a.createdAt))) }
    val bytes = JsonArray(listOf(JsonPrimitive(1), JsonArray(drafts), JsonArray(attempts))).toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= PAGED_JOURNAL_MAX_ENCODED_BYTES)
    return bytes
}

private fun decodePagedJournal(bytes: ByteArray): PagedJournalSnapshot {
    require(bytes.size <= PAGED_JOURNAL_MAX_ENCODED_BYTES)
    val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonArray
    require(root.size == 3 && root[0] == JsonPrimitive(1))
    fun array(element: JsonElement, size: Int): JsonArray = element.jsonArray.also { require(it.size == size) }
    fun string(element: JsonElement): String = element.jsonPrimitive.let { require(it.isString); it.content }
    fun time(element: JsonElement): Long = element.jsonPrimitive.let { require(!it.isString); it.content.toLong().also { n -> require(n >= 0 && n.toString() == it.content) } }
    fun key(a: JsonArray) = PagedJournalKey(string(a[0]), string(a[1]), string(a[2]))
    val drafts = linkedMapOf<PagedJournalKey, StoredPagedDraft>()
    for (entry in root[1].jsonArray) {
        val a = array(entry, 5)
        require(drafts.put(key(a), StoredPagedDraft(decodeText(string(a[3])), time(a[4]))) == null)
    }
    val attempts = linkedMapOf<String, StoredPagedAttempt>()
    for (entry in root[2].jsonArray) {
        val a = array(entry, 9)
        val attempt = StoredPagedAttempt(key(a), string(a[3]), string(a[4]), string(a[5]), string(a[6]), decodeText(string(a[7])), time(a[8]))
        require(attempts.put(attempt.attemptId, attempt) == null)
    }
    return PagedJournalSnapshot(drafts, attempts).also(::validatePagedJournal)
}
