package de.joinnoah.pi.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

enum class FileMutationKind { CREATE, RENAME, DELETE }

data class FileMutationRequest(
    val routeId: String,
    val deviceId: String,
    val sessionId: String,
    val operationId: String,
    val kind: FileMutationKind,
    val path: String,
    val destinationPath: String? = null,
    val baseVersion: String? = null,
)

data class FileMutationKey(val deviceId: String, val operationId: String)

enum class FileMutationState { RESERVED, UNKNOWN, ACKNOWLEDGED_UNKNOWN, TERMINAL }

sealed class FileMutationResult {
    data object Applied : FileMutationResult()
    data class Refused(val reason: String) : FileMutationResult()
}

data class FileMutationEntry(
    val request: FileMutationRequest,
    val state: FileMutationState,
    val result: FileMutationResult? = null,
)

sealed class FileMutationReservation {
    data class New(val entry: FileMutationEntry) : FileMutationReservation()
    data class Existing(val entry: FileMutationEntry) : FileMutationReservation()
}

interface FileMutationStorage {
    // Stable, immutable canonical key: all wrappers for the same backend must compare equal.
    // Storage implementations must normalize and secure this identity consistently.
    val lockIdentity: Any
    fun load(): Map<FileMutationKey, FileMutationEntry>
    fun save(entries: Map<FileMutationKey, FileMutationEntry>)
}

class FileMutationJournal(
    private val storage: FileMutationStorage,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int,
) {
    private val lockIdentity = storage.lockIdentity
    private val mutex = mutexFor(lockIdentity)

    init {
        require(maxEntries > 0)
    }

    private fun entries(): Map<FileMutationKey, FileMutationEntry> {
        return storage.load().toMap().also { loaded ->
            check(loaded.size <= maxEntries) { "Mutation journal exceeds capacity" }
            loaded.forEach { (key, entry) ->
                validate(entry.request)
                check((entry.state == FileMutationState.TERMINAL) == (entry.result != null)) {
                    "Journal terminal state and result do not match"
                }
                entry.result?.let { validateResult(it) }
                check(key == FileMutationKey(entry.request.deviceId, entry.request.operationId)) {
                    "Journal entry key does not match its request"
                }
            }
        }
    }

    private fun persist(updated: Map<FileMutationKey, FileMutationEntry>) {
        // Always load again on the next operation, including after an uncertain save.
        storage.save(updated.toMap())
    }

    suspend fun reserve(request: FileMutationRequest): FileMutationReservation = withContext(dispatcher) {
        mutex.withLock {
            validate(request)
            val current = entries()
            val key = FileMutationKey(request.deviceId, request.operationId)
            val existing = current[key]
            if (existing != null) {
                check(existing.request == request) { "Operation ID already reserved for another request" }
                FileMutationReservation.Existing(existing)
            } else {
                check(current.size < maxEntries) { "Mutation journal is full" }
                val entry = FileMutationEntry(request, FileMutationState.RESERVED)
                persist(current + (key to entry))
                FileMutationReservation.New(entry)
            }
        }
    }

    suspend fun pending(routeId: String, deviceId: String, sessionId: String): List<FileMutationEntry> =
        withContext(dispatcher) {
            mutex.withLock {
                entries().values.filter { entry ->
                    entry.request.routeId == routeId && entry.request.deviceId == deviceId &&
                        entry.request.sessionId == sessionId &&
                        (entry.state == FileMutationState.RESERVED || entry.state == FileMutationState.UNKNOWN)
                }
            }
        }

    suspend fun markUnknown(key: FileMutationKey): Unit = withContext(dispatcher) {
        mutex.withLock {
            val current = entries()
            val entry = checkNotNull(current[key]) { "Mutation is not reserved" }
            if (entry.state == FileMutationState.RESERVED) {
                persist(current + (key to entry.copy(state = FileMutationState.UNKNOWN)))
            }
        }
    }

    suspend fun acknowledgeUnknown(key: FileMutationKey): Unit = withContext(dispatcher) {
        mutex.withLock {
            val current = entries()
            val entry = checkNotNull(current[key]) { "Mutation is not reserved" }
            check(entry.state != FileMutationState.RESERVED) { "Mutation outcome is not marked unknown" }
            if (entry.state == FileMutationState.UNKNOWN) {
                persist(current + (key to entry.copy(state = FileMutationState.ACKNOWLEDGED_UNKNOWN)))
            }
        }
    }

    suspend fun complete(request: FileMutationRequest, result: FileMutationResult): Unit = withContext(dispatcher) {
        mutex.withLock {
            validate(request)
            validateResult(result)
            val current = entries()
            val key = FileMutationKey(request.deviceId, request.operationId)
            val entry = checkNotNull(current[key]) { "Mutation is not reserved" }
            check(entry.request == request) { "Operation ID already reserved for another request" }
            if (entry.state == FileMutationState.TERMINAL) {
                check(entry.result == result) { "Terminal result cannot be changed" }
            } else {
                persist(current + (key to entry.copy(state = FileMutationState.TERMINAL, result = result)))
            }
        }
    }

    companion object {
        private val locks = mutableMapOf<Any, WeakReference<Mutex>>()

        private fun mutexFor(identity: Any): Mutex = synchronized(locks) {
            locks.entries.removeAll { it.value.get() == null }
            locks[identity]?.get() ?: Mutex().also {
                locks[identity] = WeakReference(it)
            }
        }

        private fun validText(value: String, limit: Int): Boolean {
            if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > limit) return false
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character.isISOControl()) return false
                if (character.isHighSurrogate()) {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index++
                } else if (character.isLowSurrogate()) return false
                index++
            }
            return true
        }

        private fun validPath(path: String): Boolean =
            validText(path, 4096) && !path.startsWith('/') && '\\' !in path &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun validateResult(result: FileMutationResult) {
            if (result is FileMutationResult.Refused) require(validText(result.reason, 1024))
        }

        private fun validate(request: FileMutationRequest) {
            require(listOf(request.routeId, request.deviceId, request.sessionId, request.operationId)
                .all { validText(it, 1024) })
            require(validPath(request.path))
            request.destinationPath?.let { require(validPath(it)) }
            request.baseVersion?.let { require(validText(it, 1024)) }
            when (request.kind) {
                FileMutationKind.CREATE -> require(request.destinationPath == null && request.baseVersion == null)
                FileMutationKind.RENAME -> {
                    val destination = requireNotNull(request.destinationPath)
                    require(request.baseVersion != null)
                    require(destination != request.path && destination.substringBeforeLast('/', "") == request.path.substringBeforeLast('/', ""))
                }
                FileMutationKind.DELETE -> require(request.destinationPath == null && request.baseVersion != null)
            }
        }
    }
}
