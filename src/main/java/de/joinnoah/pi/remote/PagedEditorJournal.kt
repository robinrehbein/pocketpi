package de.joinnoah.pi.remote

import android.content.Context
import java.util.Base64
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class PagedJournalKey(val routeId: String, val sessionId: String, val questionId: String)
data class StoredPagedDraft(val text: String, val editedAt: Long)
data class StoredPagedOutcomeBinding(val deviceId: String, val ownerIncarnationId: String, val byteLength: Int, val sha256: String)
data class StoredPagedAttempt(
    val key: PagedJournalKey,
    val attemptId: String,
    val grantId: String,
    val uploadId: String,
    val commitRequestId: String,
    val text: String,
    val createdAt: Long,
    val outcomeBinding: StoredPagedOutcomeBinding? = null,
)
class PagedReleaseCapacityException : IllegalStateException("Pending release capacity reached")

data class StoredPagedRelease(val releaseId: String, val key: PagedJournalKey, val deviceId: String, val ownerIncarnationId: String, val grantId: String, val uploadId: String, val commitRequestId: String, val byteLength: Int, val sha256: String)
data class PagedJournalSnapshot(
    val drafts: Map<PagedJournalKey, StoredPagedDraft> = emptyMap(),
    val attempts: Map<String, StoredPagedAttempt> = emptyMap(),
    val pendingReleases: Map<String, StoredPagedRelease> = emptyMap(),
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
        PagedJournalSnapshot(it.drafts.toMap(), it.attempts.toMap(), it.pendingReleases.toMap()).also { snapshot -> loaded = snapshot }
    }

    suspend fun snapshot(): PagedJournalSnapshot = withContext(dispatcher) {
        mutex.withLock { current().let { PagedJournalSnapshot(it.drafts.toMap(), it.attempts.toMap(), it.pendingReleases.toMap()) } }
    }

    private fun persist(updated: PagedJournalSnapshot) {
        // Validate alternate storage implementations too, including the full encoded envelope.
        encodePagedJournal(updated)
        try {
            storage.save(updated)
        } catch (e: Exception) {
            // A failed verification can follow a successful commit. Reload before mutating.
            loaded = null
            throw e
        }
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

    suspend fun retireApplied(attempt: StoredPagedAttempt): Unit = withContext(dispatcher) {
        mutex.withLock {
            val binding = requireNotNull(attempt.outcomeBinding) { "Legacy attempt has no positive binding" }
            val release = StoredPagedRelease(attempt.attemptId, attempt.key, binding.deviceId, binding.ownerIncarnationId,
                attempt.grantId, attempt.uploadId, attempt.commitRequestId, binding.byteLength, binding.sha256)
            val state = current()
            val existing = state.attempts[attempt.attemptId]
            val queued = state.pendingReleases[attempt.attemptId]
            if (existing == null) {
                require(queued == null || queued == release) { "Release ID already reserved" }
                return@withLock
            }
            require(existing == attempt) { "Attempt changed" }
            require(queued == null) { "Release ID already reserved" }
            // Validate the record separately: invalid metadata is not a capacity condition.
            validatePagedJournal(PagedJournalSnapshot(pendingReleases = mapOf(release.releaseId to release)))
            require(state.pendingReleases.values.none { it.copy(releaseId = "") == release.copy(releaseId = "") }) { "Release tuple already reserved" }
            val sameScope = state.pendingReleases.values.count { it.key.routeId == release.key.routeId && it.deviceId == release.deviceId }
            val encodedBytes = JsonArray((state.pendingReleases.values + release).map(::encodeRelease))
                .toString().toByteArray(Charsets.UTF_8).size
            if (state.pendingReleases.size >= 128 || sameScope >= 32 || encodedBytes > 512 * 1024)
                throw PagedReleaseCapacityException()
            val matchingDraft = StoredPagedDraft(attempt.text, attempt.createdAt)
            persist(state.copy(
                drafts = if (state.drafts[attempt.key] == matchingDraft) state.drafts - attempt.key else state.drafts,
                attempts = state.attempts - attempt.attemptId,
                pendingReleases = state.pendingReleases + (release.releaseId to release),
            ))
        }
    }

    suspend fun removeRelease(release: StoredPagedRelease): Unit = withContext(dispatcher) {
        mutex.withLock {
            val state = current()
            if (state.pendingReleases[release.releaseId] == release)
                persist(state.copy(pendingReleases = state.pendingReleases - release.releaseId))
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
        attempt.outcomeBinding?.let { binding ->
            require(validPagedId(binding.deviceId) && validPagedId(binding.ownerIncarnationId))
            require(binding.byteLength in 0..(1024 * 1024))
            require(binding.sha256.matches(Regex("[0-9a-f]{64}")))
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(attempt.text))
            require(encoded.remaining() == binding.byteLength)
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(hash == binding.sha256)
        }
        attemptBytes += attempt.text.length.toLong() * 2
    }
    require(attemptBytes <= PAGED_JOURNAL_MAX_ATTEMPT_TOTAL)
    require(snapshot.pendingReleases.size <= 128)
    val routeDeviceCounts = mutableMapOf<Pair<String, String>, Int>()
    val tuples = mutableSetOf<StoredPagedRelease>()
    var releaseBytes = 2L
    val opaque = Regex("[A-Za-z0-9_-]{1,256}")
    fun wireId(id: String): Boolean = id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= 256 &&
        id.none { it <= ' ' || it == '\u007f' } && validPagedId(id)
    snapshot.pendingReleases.forEach { (id, release) ->
        require(id == release.releaseId && validPagedId(id) && id !in snapshot.attempts)
        require(validPagedId(release.key.routeId) && wireId(release.key.sessionId) && wireId(release.key.questionId))
        require(opaque.matches(release.deviceId) && opaque.matches(release.ownerIncarnationId) &&
            opaque.matches(release.grantId) && opaque.matches(release.uploadId) && wireId(release.commitRequestId))
        require(release.byteLength in 0..(1024 * 1024) && release.sha256.matches(Regex("[0-9a-f]{64}")))
        require(tuples.add(release.copy(releaseId = "")))
        val scope = release.key.routeId to release.deviceId
        val count = (routeDeviceCounts[scope] ?: 0) + 1
        require(count <= 32)
        routeDeviceCounts[scope] = count
        val size = encodeRelease(release).toString().toByteArray(Charsets.UTF_8).size
        require(size <= 4096)
        releaseBytes += size + if (releaseBytes > 2L) 1 else 0
    }
    require(releaseBytes <= 512 * 1024)
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

private fun encodeRelease(r: StoredPagedRelease): JsonArray = JsonArray(listOf(
    JsonPrimitive(r.releaseId), JsonPrimitive(r.key.routeId), JsonPrimitive(r.key.sessionId), JsonPrimitive(r.key.questionId),
    JsonPrimitive(r.deviceId), JsonPrimitive(r.ownerIncarnationId), JsonPrimitive(r.grantId), JsonPrimitive(r.uploadId),
    JsonPrimitive(r.commitRequestId), JsonPrimitive(r.byteLength), JsonPrimitive(r.sha256),
))

// Fixed-shape arrays avoid ambiguous duplicate JSON object members. Duplicate record keys are rejected.
private fun encodePagedJournal(snapshot: PagedJournalSnapshot): ByteArray {
    validatePagedJournal(snapshot)
    fun key(k: PagedJournalKey) = listOf(JsonPrimitive(k.routeId), JsonPrimitive(k.sessionId), JsonPrimitive(k.questionId))
    val drafts = snapshot.drafts.map { (k, d) -> JsonArray(key(k) + listOf(JsonPrimitive(encodeText(d.text)), JsonPrimitive(d.editedAt))) }
    val attempts = snapshot.attempts.values.map { a ->
        val binding = a.outcomeBinding?.let { JsonArray(listOf(JsonPrimitive(it.deviceId), JsonPrimitive(it.ownerIncarnationId), JsonPrimitive(it.byteLength), JsonPrimitive(it.sha256))) } ?: JsonNull
        JsonArray(key(a.key) + listOf(JsonPrimitive(a.attemptId), JsonPrimitive(a.grantId), JsonPrimitive(a.uploadId), JsonPrimitive(a.commitRequestId), JsonPrimitive(encodeText(a.text)), JsonPrimitive(a.createdAt), binding))
    }
    val releases = snapshot.pendingReleases.values.map(::encodeRelease)
    val bytes = JsonArray(listOf(JsonPrimitive(3), JsonArray(drafts), JsonArray(attempts), JsonArray(releases))).toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= PAGED_JOURNAL_MAX_ENCODED_BYTES)
    return bytes
}

private fun decodePagedJournal(bytes: ByteArray): PagedJournalSnapshot {
    require(bytes.size <= PAGED_JOURNAL_MAX_ENCODED_BYTES)
    val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonArray
    require(root.isNotEmpty() && (root[0] == JsonPrimitive(1) || root[0] == JsonPrimitive(2) || root[0] == JsonPrimitive(3)))
    val version = root[0].jsonPrimitive.content.toInt()
    require(root.size == if (version == 3) 4 else 3)
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
        val a = array(entry, if (version == 1) 9 else 10)
        val binding = if (version == 1 || a[9] == JsonNull) null else {
            val b = array(a[9], 4)
            val length = time(b[2])
            require(length <= Int.MAX_VALUE)
            StoredPagedOutcomeBinding(string(b[0]), string(b[1]), length.toInt(), string(b[3]))
        }
        val attempt = StoredPagedAttempt(key(a), string(a[3]), string(a[4]), string(a[5]), string(a[6]), decodeText(string(a[7])), time(a[8]), binding)
        require(attempts.put(attempt.attemptId, attempt) == null)
    }
    val releases = linkedMapOf<String, StoredPagedRelease>()
    if (version == 3) for (entry in root[3].jsonArray) {
        val a = array(entry, 11)
        val length = time(a[9]); require(length <= Int.MAX_VALUE)
        val release = StoredPagedRelease(string(a[0]), PagedJournalKey(string(a[1]), string(a[2]), string(a[3])),
            string(a[4]), string(a[5]), string(a[6]), string(a[7]), string(a[8]), length.toInt(), string(a[10]))
        require(releases.put(release.releaseId, release) == null)
    }
    return PagedJournalSnapshot(drafts, attempts, releases).also(::validatePagedJournal)
}
